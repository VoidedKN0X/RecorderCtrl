package com.vknxworks.recorderctrl.edit

/** Trim and cut, worked out without Android so it can be unit-tested. */
object EditMath {
    /** The shortest piece that can be kept or removed. */
    const val MIN_MS = 500L

    /** Keep the selection, or remove it and join what's before and after. */
    enum class Mode { TRIM, CUT }

    /** Whether [startMs]..[endMs] of a recording [durationMs] long gives a usable result with [mode]. */
    fun valid(mode: Mode, startMs: Long, endMs: Long, durationMs: Long): Boolean {
        if (startMs < 0 || endMs > durationMs || endMs - startMs < MIN_MS) return false
        return when (mode) {
            Mode.TRIM -> startMs > 0 || endMs < durationMs
            Mode.CUT -> durationMs - (endMs - startMs) >= MIN_MS
        }
    }

    /**
     * Where a moment [t] of the original ends up after the edit, or null when it's removed. Trim keeps
     * [start, end) and moves it to 0; cut removes [start, end) and moves what follows back.
     */
    fun mapTime(mode: Mode, t: Long, startMs: Long, endMs: Long): Long? = when (mode) {
        Mode.TRIM -> if (t in startMs until endMs) t - startMs else null
        Mode.CUT -> when {
            t < startMs -> t
            t >= endMs -> t - (endMs - startMs)
            else -> null
        }
    }

    /** Bookmarks after the edit: moved along, the ones in a removed part dropped. */
    fun mapBookmarks(mode: Mode, marks: List<Long>, startMs: Long, endMs: Long): List<Long> =
        marks.mapNotNull { mapTime(mode, it, startMs, endMs) }.distinct().sorted()

    /** A byte range in PCM data, rounded down to whole frames. */
    fun byteAt(ms: Long, bytesPerMs: Double, frameBytes: Int, dataBytes: Long): Long {
        val raw = (ms * bytesPerMs).toLong().coerceIn(0, dataBytes)
        return raw - raw % frameBytes
    }
}
