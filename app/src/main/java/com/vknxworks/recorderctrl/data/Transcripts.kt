package com.vknxworks.recorderctrl.data

import android.util.AtomicFile
import com.vknxworks.recorderctrl.data.crypto.DataKeyStore
import com.vknxworks.recorderctrl.data.crypto.SealedFile
import com.vknxworks.recorderctrl.transcribe.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Transcripts, stored next to their recordings in RecorderCtrl's private folder as `<recording file>.transcript.json`.
 * Deleting a recording deletes its transcript too (see [Recordings.delete]).
 *
 * The files are encrypted at rest with the app's data key ([DataKeyStore], [SealedFile]). A transcript from before
 * encryption is still read, and is replaced by its encrypted copy when the app starts ([SealedFile.sealInPlace]);
 * without a data key ordinary files are read and written as before. An encrypted transcript that can't be opened is
 * never overwritten: it shows in [unreadable], and making the transcript again first moves it aside as
 * `<recording file>.transcript.json.unreadable`.
 */
class Transcripts(private val recordings: Recordings, private val keys: DataKeyStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Recording file name to its transcript's text, for searching the recordings list. */
    private val _search = MutableStateFlow<Map<String, String>>(emptyMap())
    val search: StateFlow<Map<String, String>> = _search

    private val _unreadable = MutableStateFlow<Set<String>>(emptySet())

    /** Recording file names whose transcript is encrypted and can't be opened right now (the key is missing, or the file is damaged). */
    val unreadable: StateFlow<Set<String>> = _unreadable

    private fun fileFor(recordingFile: String) = File(recordings.dir, recordingFile + SUFFIX)

    /**
     * Reads every transcript's text for search, and encrypts the ones from before encryption. Runs on a background
     * thread; the data key is fetched (or made) here too, never on the main thread.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        keys.get(create = true)
        val dir = recordings.dir
        runCatching { restoreAside(dir) }
        val map = HashMap<String, String>()
        val bad = HashSet<String>()
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .forEach { f ->
                val name = f.name.removeSuffix(SUFFIX)
                val t = read(f, migrate = true) { bad.add(name) }
                if (t != null) map[name] = t.fullText
            }
        _search.value = map
        _unreadable.value = bad
    }

    /** Tries the transcripts that couldn't be opened again. */
    suspend fun retry() {
        withContext(Dispatchers.IO) { keys.reset() }
        load()
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

    private fun decode(plain: ByteArray): Transcript? =
        runCatching { json.decodeFromString(Transcript.serializer(), String(plain, Charsets.UTF_8)) }.getOrNull()

    /**
     * The transcript in [f], or null when there is none to use. [onUnreadable] is called when the file is encrypted and
     * can't be opened; the file is left exactly as it is. With [migrate] an ordinary file that reads fine is replaced
     * by its encrypted copy; if that fails for any reason the ordinary file stays in use.
     */
    private fun read(f: File, migrate: Boolean, onUnreadable: () -> Unit = {}): Transcript? {
        return synchronized(fileLock) {
            val bytes = runCatching { AtomicFile(f).readFully() }.getOrNull() ?: return null
            val sealed = SealedFile.isSealed(bytes)
            val plain = if (sealed) open(bytes) else bytes
            if (plain == null) {
                onUnreadable()
                return null
            }
            val t = decode(plain) ?: return null
            if (migrate && !sealed) {
                try {
                    val key = keys.get(create = true)
                    if (key != null) SealedFile.sealInPlace(key, f, File(f.parentFile, f.name + SEAL_TMP))
                } catch (e: Throwable) {
                    // The ordinary file stays in use.
                }
            }
            t
        }
    }

    /**
     * Puts back a transcript that was moved aside because it couldn't be opened, when it opens now and its recording
     * has no other transcript.
     */
    private fun restoreAside(dir: File) {
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SUFFIX + ASIDE) }.forEach { aside ->
            synchronized(fileLock) {
                try {
                    val base = File(dir, aside.name.removeSuffix(ASIDE))
                    val recording = File(dir, base.name.removeSuffix(SUFFIX))
                    if (!base.exists() && recording.isFile) {
                        val bytes = aside.readBytes()
                        if (SealedFile.isSealed(bytes) && open(bytes)?.let(::decode) != null) aside.renameTo(base)
                    }
                } catch (e: Exception) {
                    // Left where it is.
                }
            }
        }
    }

    suspend fun get(r: Recording): Transcript? = withContext(Dispatchers.IO) {
        val f = fileFor(r.file)
        var bad = false
        val t = if (f.isFile) read(f, migrate = false) { bad = true } else null
        if (bad) _unreadable.update { it + r.file } else if (r.file in _unreadable.value) _unreadable.update { it - r.file }
        t
    }

    suspend fun save(t: Transcript): Boolean = withContext(Dispatchers.IO) {
        val ok = synchronized(fileLock) {
            val base = fileFor(t.recording)
            // An encrypted transcript that can't be opened is kept aside, never overwritten.
            val old = if (base.isFile) runCatching { AtomicFile(base).readFully() }.getOrNull() else null
            if (old != null && SealedFile.isSealed(old) && open(old) == null && !moveAside(base)) return@synchronized false
            val plain = runCatching { json.encodeToString(Transcript.serializer(), t).toByteArray(Charsets.UTF_8) }.getOrNull()
                ?: return@synchronized false
            // Encrypted when there's a data key; the ordinary file otherwise, as before.
            val bytes = keys.get(create = true)?.let { SealedFile.sealChecked(it, plain) } ?: plain
            val f = AtomicFile(base)
            val out = runCatching { f.startWrite() }.getOrNull() ?: return@synchronized false
            try {
                out.write(bytes)
                f.finishWrite(out)
                true
            } catch (e: Exception) {
                f.failWrite(out)
                false
            }
        }
        if (ok) {
            _search.update { it + (t.recording to t.fullText) }
            _unreadable.update { it - t.recording }
        }
        ok
    }

    /** Moves a transcript file out of the way without deleting it (or an earlier one set aside). False when that fails. */
    private fun moveAside(base: File): Boolean = try {
        var aside = File(base.parentFile, base.name + ASIDE)
        if (aside.exists()) aside = File(base.parentFile, base.name + ASIDE + "." + System.currentTimeMillis())
        base.renameTo(aside)
    } catch (e: Exception) {
        false
    }

    suspend fun delete(recordingFile: String) = withContext(Dispatchers.IO) {
        deleteFiles(recordings.dir, recordingFile)
        _search.update { it - recordingFile }
        _unreadable.update { it - recordingFile }
    }

    fun clear() {
        _search.value = emptyMap()
        _unreadable.value = emptySet()
    }

    companion object {
        const val SUFFIX = ".transcript.json"
        private const val SEAL_TMP = ".seal.tmp"
        private const val ASIDE = ".unreadable"

        /**
         * Held while a transcript file is read, written, encrypted in place or deleted, so encrypting an old transcript
         * can never undo a newer save or bring back a transcript that was just deleted.
         */
        val fileLock = Any()

        /** Whether a recording has a transcript (or one kept aside that can't be opened). Reads the folder. */
        fun hasAny(dir: File, recordingFile: String): Boolean {
            val prefix = recordingFile + SUFFIX
            return dir.listFiles().orEmpty().any { it.isFile && it.name.startsWith(prefix) && !it.name.endsWith(".new") }
        }

        /** True for a transcript file (or a temporary or set-aside copy of one), which isn't a recording. */
        fun isTranscriptFile(name: String) = name.contains(SUFFIX)

        /** Deletes a recording's transcript with every temporary or set-aside copy of it. */
        fun deleteFiles(dir: File, recordingFile: String) {
            synchronized(fileLock) {
                listOf("", ".new", ".bak", SEAL_TMP, ASIDE).forEach { File(dir, recordingFile + SUFFIX + it).delete() }
                val prefix = recordingFile + SUFFIX
                dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(prefix) }.forEach { it.delete() }
            }
        }
    }
}
