package com.vknxworks.recorderctrl.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
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
 */
class Recordings(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _list = MutableStateFlow<List<Recording>>(emptyList())
    val list: StateFlow<List<Recording>> = _list

    val dir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }
    private val index get() = AtomicFile(File(context.filesDir, "recordings.json"))

    fun file(r: Recording) = File(dir, r.file)

    /**
     * Reads the index, keeps recordings whose file is there, and recovers ones that were cut off (the app was
     * stopped while recording). [active] is the file being recorded right now, which is left alone.
     */
    suspend fun load(active: File? = null) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val saved = runCatching { json.decodeFromString<List<Recording>>(String(index.readFully(), Charsets.UTF_8)) }.getOrDefault(emptyList())
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
            if (all != saved) write(all)
        }
    }

    private fun write(list: List<Recording>) {
        val f = index
        val out = runCatching { f.startWrite() }.getOrNull() ?: return
        try {
            out.write(json.encodeToString(ListSerializer(Recording.serializer()), list).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }

    private fun change(f: (List<Recording>) -> List<Recording>) = synchronized(lock) {
        val next = f(_list.value).sortedByDescending { it.createdAt }
        _list.value = next
        write(next)
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
        dir.listFiles().orEmpty().forEach { it.delete() }
        change { emptyList() }
    }

    companion object {
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
