package com.vknxworks.recorderctrl.data

import android.util.AtomicFile
import com.vknxworks.recorderctrl.transcribe.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Transcripts, stored next to their recordings in RecorderCtrl's private folder as `<recording file>.transcript.json`.
 * Deleting a recording deletes its transcript too (see [Recordings.delete]).
 */
class Transcripts(private val recordings: Recordings) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Recording file name to its transcript's text, for searching the recordings list. */
    private val _search = MutableStateFlow<Map<String, String>>(emptyMap())
    val search: StateFlow<Map<String, String>> = _search

    private fun fileFor(recordingFile: String) = File(recordings.dir, recordingFile + SUFFIX)

    suspend fun load() = withContext(Dispatchers.IO) {
        val map = recordings.dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .mapNotNull { f -> read(f)?.let { t -> f.name.removeSuffix(SUFFIX) to t.fullText } }
            .toMap()
        _search.value = map
    }

    private fun read(f: File): Transcript? = runCatching {
        json.decodeFromString(Transcript.serializer(), String(AtomicFile(f).readFully(), Charsets.UTF_8))
    }.getOrNull()

    suspend fun get(r: Recording): Transcript? = withContext(Dispatchers.IO) {
        val f = fileFor(r.file)
        if (f.isFile) read(f) else null
    }

    suspend fun save(t: Transcript): Boolean = withContext(Dispatchers.IO) {
        val f = AtomicFile(fileFor(t.recording))
        val out = runCatching { f.startWrite() }.getOrNull() ?: return@withContext false
        try {
            out.write(json.encodeToString(Transcript.serializer(), t).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
            _search.value = _search.value + (t.recording to t.fullText)
            true
        } catch (e: Exception) {
            f.failWrite(out)
            false
        }
    }

    suspend fun delete(recordingFile: String) = withContext(Dispatchers.IO) {
        deleteFiles(recordings.dir, recordingFile)
        _search.value = _search.value - recordingFile
    }

    fun clear() {
        _search.value = emptyMap()
    }

    companion object {
        const val SUFFIX = ".transcript.json"

        /** True for a transcript file (or AtomicFile's temporary copies of one), which isn't a recording. */
        fun isTranscriptFile(name: String) = name.contains(SUFFIX)

        fun deleteFiles(dir: File, recordingFile: String) {
            listOf("", ".new", ".bak").forEach { File(dir, recordingFile + SUFFIX + it).delete() }
        }
    }
}
