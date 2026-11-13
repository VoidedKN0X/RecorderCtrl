package com.vknxworks.recorderctrl.transcribe

import java.io.File

/**
 * whisper.cpp, built into RecorderCtrl (libs: recorderctrl_whisper). It runs on the phone's processor only and reads
 * nothing but the model file and the samples it's given.
 */
object WhisperLib {
    /**
     * The library is built for ARMv8.2-A with half-precision maths and dot product (every phone core from 2017 on).
     * Older processors would crash on those instructions, so they're checked before the library is loaded.
     */
    val supported: Boolean by lazy { cpuSupported() && loaded }

    private val loaded: Boolean by lazy {
        cpuSupported() && runCatching { System.loadLibrary("recorderctrl_whisper") }.isSuccess
    }

    private fun cpuSupported(): Boolean = runCatching {
        val features = File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
        // Every core must have them (big.LITTLE phones list each core).
        features.isNotEmpty() && features.all { "asimddp" in it && ("asimdhp" in it || "fphp" in it) }
    }.getOrDefault(false)

    /** Whisper's language codes ("en", "de", ...), or empty when the library can't run here. */
    val languages: List<String> by lazy {
        if (!supported) emptyList() else runCatching { nativeLanguages().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }

    @JvmStatic external fun nativeOpen(path: String): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeProgress(handle: Long): Int
    @JvmStatic external fun nativeIsMultilingual(handle: Long): Boolean
    @JvmStatic external fun nativeRun(handle: Long, samples: FloatArray, language: String, threads: Int): Int
    @JvmStatic external fun nativeSegmentCount(handle: Long): Int
    @JvmStatic external fun nativeSegmentStart(handle: Long, i: Int): Long
    @JvmStatic external fun nativeSegmentEnd(handle: Long, i: Int): Long
    @JvmStatic external fun nativeSegmentText(handle: Long, i: Int): ByteArray
    @JvmStatic external fun nativeDetectedLanguage(handle: Long): String
    @JvmStatic external fun nativeLanguages(): Array<String>
}

/** One loaded model. Not thread-safe: one transcription at a time; [cancel] may be called from any thread. */
class WhisperSession private constructor(@Volatile private var handle: Long) : AutoCloseable {
    val multilingual: Boolean get() = handle != 0L && WhisperLib.nativeIsMultilingual(handle)

    /** 0..100 for the current [run]. */
    val progress: Int @Synchronized get() = if (handle == 0L) 0 else WhisperLib.nativeProgress(handle)

    @Synchronized
    fun cancel() {
        if (handle != 0L) WhisperLib.nativeCancel(handle)
    }

    /** Result of one [run]: segments with times relative to the samples, and the language used. */
    class Result(val segments: List<Segment>, val language: String)

    /** Transcribes 16 kHz mono samples; null when it failed or was cancelled. */
    fun run(samples: FloatArray, language: String, threads: Int): Result? {
        val h = handle
        if (h == 0L) return null
        if (WhisperLib.nativeRun(h, samples, language, threads) != 0) return null
        val n = WhisperLib.nativeSegmentCount(h)
        val segments = (0 until n).map { i ->
            Segment(
                // whisper's times are in centiseconds.
                startMs = WhisperLib.nativeSegmentStart(h, i) * 10,
                endMs = WhisperLib.nativeSegmentEnd(h, i) * 10,
                text = String(WhisperLib.nativeSegmentText(h, i), Charsets.UTF_8).trim(),
            )
        }.filter { it.text.isNotEmpty() }
        return Result(segments, WhisperLib.nativeDetectedLanguage(h).ifBlank { language })
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) WhisperLib.nativeClose(h)
    }

    companion object {
        fun open(model: File): WhisperSession? {
            if (!WhisperLib.supported) return null
            val h = WhisperLib.nativeOpen(model.absolutePath)
            return if (h == 0L) null else WhisperSession(h)
        }
    }
}
