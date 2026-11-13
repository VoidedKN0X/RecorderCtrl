package com.vknxworks.recorderctrl.transcribe

import kotlinx.serialization.Serializable

/** One line of a transcript: what was said between [startMs] and [endMs] of the recording. */
@Serializable
data class Segment(val startMs: Long, val endMs: Long, val text: String)

/** A recording's transcript, stored next to the recording as `<recording file>.transcript.json`. */
@Serializable
data class Transcript(
    val version: Int = 1,
    /** The recording's file name in RecorderCtrl's storage. */
    val recording: String,
    /** Whisper's language code ("en", "nl", ...), detected or chosen. */
    val language: String = "",
    /** The model file it was made with, e.g. ggml-base.bin. */
    val model: String = "",
    val createdAt: Long = 0L,
    val segments: List<Segment> = emptyList(),
) {
    /** All text on one line, for search. */
    val fullText: String get() = segments.joinToString(" ") { it.text }
}

/** Plain text and SubRip (.srt) subtitles from a transcript. */
object TranscriptWriter {

    /** One line per segment, no timestamps. */
    fun toTxt(segments: List<Segment>): String = buildString {
        for (s in segments) {
            val t = clean(s.text)
            if (t.isEmpty()) continue
            append(t).append('\n')
        }
    }

    /**
     * SubRip: numbered cues, `HH:MM:SS,mmm --> HH:MM:SS,mmm`, the text, and a blank line. Empty lines are skipped (a blank
     * line inside a cue would end it), and a cue never ends before it starts.
     */
    fun toSrt(segments: List<Segment>): String = buildString {
        var n = 0
        for (s in segments) {
            val t = clean(s.text)
            if (t.isEmpty()) continue
            n++
            val start = s.startMs.coerceAtLeast(0)
            val end = s.endMs.coerceAtLeast(start)
            append(n).append('\n')
            append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n')
            append(t).append("\n\n")
        }
    }

    /** `01:02:03,045`. */
    fun srtTime(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        val h = v / 3_600_000
        val m = (v / 60_000) % 60
        val s = (v / 1000) % 60
        val milli = v % 1000
        return "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT, h, m, s, milli)
    }

    /** Trims, and turns line breaks and runs of spaces into single spaces. */
    fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
