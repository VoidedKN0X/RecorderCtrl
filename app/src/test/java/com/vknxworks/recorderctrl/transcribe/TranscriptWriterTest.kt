package com.vknxworks.recorderctrl.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptWriterTest {
    private val segments = listOf(
        Segment(0, 2_500, " Hello there."),
        Segment(2_500, 61_005, "This is\na test.  "),
        Segment(61_005, 61_005, "   "),
        Segment(3_723_045, 3_725_000, "Over an hour in."),
    )

    @Test
    fun srtTimes() {
        assertEquals("00:00:00,000", TranscriptWriter.srtTime(0))
        assertEquals("00:00:02,500", TranscriptWriter.srtTime(2_500))
        assertEquals("00:01:01,005", TranscriptWriter.srtTime(61_005))
        assertEquals("01:02:03,045", TranscriptWriter.srtTime(3_723_045))
        assertEquals("100:00:00,000", TranscriptWriter.srtTime(360_000_000))
        assertEquals("00:00:00,000", TranscriptWriter.srtTime(-5))
    }

    @Test
    fun srtCuesAreNumberedAndSkipEmptyLines() {
        val expected = "1\n" +
            "00:00:00,000 --> 00:00:02,500\n" +
            "Hello there.\n\n" +
            "2\n" +
            "00:00:02,500 --> 00:01:01,005\n" +
            "This is a test.\n\n" +
            "3\n" +
            "01:02:03,045 --> 01:02:05,000\n" +
            "Over an hour in.\n\n"
        assertEquals(expected, TranscriptWriter.toSrt(segments))
    }

    @Test
    fun srtCueNeverEndsBeforeItStarts() {
        val srt = TranscriptWriter.toSrt(listOf(Segment(5_000, 4_000, "Backwards")))
        assertEquals("1\n00:00:05,000 --> 00:00:05,000\nBackwards\n\n", srt)
    }

    @Test
    fun srtOfNothingIsEmpty() {
        assertEquals("", TranscriptWriter.toSrt(emptyList()))
    }

    @Test
    fun txtIsOneLinePerSegment() {
        assertEquals("Hello there.\nThis is a test.\nOver an hour in.\n", TranscriptWriter.toTxt(segments))
        assertEquals("", TranscriptWriter.toTxt(emptyList()))
    }

    @Test
    fun keepsUnicode() {
        val s = listOf(Segment(0, 1000, "Ça va? 你好 👋"))
        assertEquals("Ça va? 你好 👋\n", TranscriptWriter.toTxt(s))
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nÇa va? 你好 👋\n\n", TranscriptWriter.toSrt(s))
    }

    @Test
    fun fullTextJoinsSegments() {
        val t = Transcript(recording = "a.m4a", segments = listOf(Segment(0, 1, "one"), Segment(1, 2, "two")))
        assertEquals("one two", t.fullText)
    }
}
