package com.vknxworks.recorderctrl.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import com.vknxworks.recorderctrl.data.crypto.DataKeyStore
import com.vknxworks.recorderctrl.data.crypto.SealedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** One saved recording. The audio lives in RecorderCtrl's private storage as [file]. */
@Serializable
data class Recording(
    val id: Long,
    val file: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String,
)

/**
 * The recordings: audio files in the app's private folder (encrypted with the phone, invisible to other apps), and
 * a small index with their names and lengths. Other apps only get a recording when you share or save a copy.
 *
 * The index (`recordings.json`) is encrypted at rest with the app's data key ([DataKeyStore], [SealedFile]); the audio
 * files are not (the player, the decoder and other apps open them by path or link). An index from before encryption
 * is still read and then replaced by its encrypted copy; without a data key the ordinary file is used as before.
 */
class Recordings(private val context: Context, private val keys: DataKeyStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _list = MutableStateFlow<List<Recording>>(emptyList())
    val list: StateFlow<List<Recording>> = _list

    val dir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }
    private val indexFile get() = File(context.filesDir, INDEX)
    private val index get() = AtomicFile(indexFile)

    /** An index that can't be opened couldn't be moved aside: nothing is written over it in this run. */
    @Volatile private var indexBlocked = false

    fun file(r: Recording) = File(dir, r.file)

    /**
     * Reads the index, keeps recordings whose file is there, and recovers ones that were cut off (the app was
     * stopped while recording). [active] is the file being recorded right now, which is left alone.
     */
    suspend fun load(active: File? = null) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            // The data key is fetched (or made) here, on a background thread, before anything needs it.
            val key = keys.get(create = true)
            val bytes = runCatching { index.readFully() }.getOrNull()
            val wasSealed = bytes != null && SealedFile.isSealed(bytes)
            val plain = if (bytes != null && wasSealed) open(bytes) else bytes
            if (wasSealed && plain == null) {
                // Encrypted and can't be opened: kept aside, never overwritten. The list is built again from the audio files.
                if (!moveIndexAside()) indexBlocked = true
            }
            val onDisk = plain?.let(::decode) ?: emptyList()
            // An index that was set aside earlier and opens again gives the names back.
            val recovered = runCatching { recoverAside() }.getOrDefault(emptyList())
            var saved = onDisk
            for ((_, old) in recovered) {
                val byFile = old.associateBy { it.file }
                val have = saved.map { it.file }.toSet()
                saved = saved.map { cur ->
                    val was = byFile[cur.file]
                    // Only over an entry that was rebuilt from the file (named after it), never over a name the user chose since.
                    if (was != null && cur.name == File(cur.file).nameWithoutExtension) was else cur
                } + old.filter { it.file !in have }
            }
            // A recording interrupted by a crash or power loss: keep it if Android can still read it.
            dir.listFiles().orEmpty().filter { it.name.endsWith(".part") && it.absolutePath != active?.absolutePath }.forEach { part ->
                if (durationOf(part) <= 0 || !part.renameTo(File(dir, part.name.removeSuffix(".part")))) part.delete()
            }
            val known = saved.filter { File(dir, it.file).isFile }
            val names = known.map { it.file }.toSet()
            val found = dir.listFiles().orEmpty()
                .filter { it.isFile && !it.name.endsWith(".part") && !Transcripts.isTranscriptFile(it.name) && it.name !in names }
                .mapNotNull { f ->
                    durationOf(f).takeIf { it > 0 }?.let { d ->
                        Recording(f.lastModified(), f.name, f.nameWithoutExtension, f.lastModified(), d, f.length(), mimeOf(f.name))
                    }
                }
            val all = (known + found).distinctBy { it.file }.sortedByDescending { it.createdAt }
            _list.value = all
            if (all != onDisk) {
                if (write(all)) recovered.forEach { runCatching { it.first.delete() } }
            } else if (bytes != null && !wasSealed && key != null) {
                // An ordinary index from before encryption: replaced by its encrypted copy, or left as it is.
                try {
                    SealedFile.sealInPlace(key, indexFile, File(context.filesDir, INDEX_SEAL_TMP))
                } catch (e: Throwable) {
                    // The ordinary file stays in use.
                }
            }
        }
    }

    /** Sealed bytes opened with the data key, or null when there is no key or it doesn't open them. */
    private fun open(sealed: ByteArray): ByteArray? {
        val key = keys.get(create = false) ?: return null
        return try {
            SealedFile.open(key, sealed)
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(plain: ByteArray): List<Recording>? =
        runCatching { json.decodeFromString(ListSerializer(Recording.serializer()), String(plain, Charsets.UTF_8)) }.getOrNull()

    /** Moves the index out of the way without deleting it (or an earlier one set aside). False when that fails. */
    private fun moveIndexAside(): Boolean = try {
        var aside = File(context.filesDir, INDEX_ASIDE)
        if (aside.exists()) aside = File(context.filesDir, "$INDEX_ASIDE.${System.currentTimeMillis()}")
        indexFile.renameTo(aside)
    } catch (e: Exception) {
        false
    }

    private fun asideIndexes(): List<File> =
        context.filesDir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(INDEX_ASIDE) }.sortedBy { it.name }

    /** The indexes kept aside that open now, each with its file. */
    private fun recoverAside(): List<Pair<File, List<Recording>>> = asideIndexes().mapNotNull { f ->
        try {
            val bytes = f.readBytes()
            if (!SealedFile.isSealed(bytes)) return@mapNotNull null
            val list = open(bytes)?.let(::decode) ?: return@mapNotNull null
            f to list
        } catch (e: Exception) {
            null
        }
    }

    /** Writes the index in one step: encrypted when there's a data key, the ordinary file otherwise. True when it worked. */
    private fun write(list: List<Recording>): Boolean {
        if (indexBlocked) return false
        val plain = runCatching { json.encodeToString(ListSerializer(Recording.serializer()), list).toByteArray(Charsets.UTF_8) }.getOrNull()
            ?: return false
        val bytes = keys.get(create = true)?.let { SealedFile.sealChecked(it, plain) } ?: plain
        val f = index
        val out = runCatching { f.startWrite() }.getOrNull() ?: return false
        return try {
            out.write(bytes)
            f.finishWrite(out)
            true
        } catch (e: Exception) {
            f.failWrite(out)
            false
        }
    }

    private fun change(f: (List<Recording>) -> List<Recording>) {
        synchronized(lock) {
            val next = f(_list.value).sortedByDescending { it.createdAt }
            _list.value = next
            write(next)
        }
    }

    /** A new file to record into; it gets its real name when the recording is saved. */
    fun newPartFile(ext: String, createdAt: Long): File = File(dir, "rec_$createdAt.$ext.part")

    /** Adds a finished recording; returns it, or null when the file is empty or unreadable. */
    suspend fun add(part: File, name: String, createdAt: Long): Recording? = withContext(Dispatchers.IO) {
        val target = File(dir, part.name.removeSuffix(".part"))
        if (!part.renameTo(target)) return@withContext null
        val d = durationOf(target)
        if (d <= 0 || target.length() == 0L) {
            target.delete()
            return@withContext null
        }
        val r = Recording(createdAt, target.name, name, createdAt, d, target.length(), mimeOf(target.name))
        change { it.filter { x -> x.file != r.file } + r }
        r
    }

    fun rename(r: Recording, name: String) = change { l -> l.map { if (it.id == r.id) it.copy(name = name.take(80)) else it } }

    /** Deletes the recording and its transcript. */
    suspend fun delete(r: Recording) = withContext(Dispatchers.IO) {
        file(r).delete()
        Transcripts.deleteFiles(dir, r.file)
        change { l -> l.filter { it.id != r.id } }
    }

    /** Saves a copy to the phone's Recordings folder, where other apps can see it. Returns its uri. */
    suspend fun exportCopy(r: Recording): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val ext = r.file.substringAfterLast('.', "m4a")
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, safeName(r.name) + "." + ext)
                put(MediaStore.Audio.Media.MIME_TYPE, r.mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS + "/RecorderCtrl")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: return@runCatching null
            try {
                resolver.openOutputStream(uri)?.use { out -> file(r).inputStream().use { it.copyTo(out) } } ?: error("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }.getOrNull()
    }

    /** Removes everything, transcripts included ("Forgot PIN"). */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        synchronized(Transcripts.fileLock) { dir.listFiles().orEmpty().forEach { it.delete() } }
        synchronized(lock) {
            // Deleting everything includes an index that was kept aside because it couldn't be opened.
            runCatching { asideIndexes().forEach { it.delete() } }
            if (indexBlocked) {
                runCatching { indexFile.delete() }
                indexBlocked = false
            }
        }
        change { emptyList() }
    }

    companion object {
        private const val INDEX = "recordings.json"
        private const val INDEX_SEAL_TMP = "recordings.json.seal.tmp"
        private const val INDEX_ASIDE = "recordings.json.unreadable"

        fun durationOf(f: File): Long = runCatching {
            MediaMetadataRetriever().use { m ->
                m.setDataSource(f.absolutePath)
                m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)

        fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "ogg", "opus" -> "audio/ogg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            else -> "audio/*"
        }

        /** A file name without characters other apps or file systems choke on. */
        fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "Recording" }
    }
}
