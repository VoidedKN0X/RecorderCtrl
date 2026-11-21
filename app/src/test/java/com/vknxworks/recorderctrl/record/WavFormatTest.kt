package com.vknxworks.recorderctrl.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WavFormatTest {
    @Test fun headerRoundTrips() {
        val h = WavFormat.header(48_000, 2, 19_200)
        assertEquals(44, h.size)
        val l = WavFormat.layout(h, 44L + 19_200)!!
        assertEquals(44L, l.dataOffset)
        assertEquals(19_200L, l.dataBytes)
        assertEquals(48_000, l.rate)
        assertEquals(2, l.channels)
        assertEquals(4, l.frameBytes)
        assertEquals(192.0, l.bytesPerMs, 0.0001)
    }

    @Test fun cutOffFileUsesWhatIsThereInWholeFrames() {
        val h = WavFormat.header(48_000, 2, 0)
        val l = WavFormat.layout(h, 44L + 1003)!!
        assertEquals(1000L, l.dataBytes)
    }

    @Test fun sizeLargerThanTheFileIsCappedToTheFile() {
        val h = WavFormat.header(16_000, 1, 1_000_000)
        assertEquals(500L, WavFormat.layout(h, 44L + 500)!!.dataBytes)
    }

    @Test fun notWav() {
        assertNull(WavFormat.layout(ByteArray(44), 44))
        val h = WavFormat.header(48_000, 1, 10)
        h[34] = 24 // 24-bit
        assertNull(WavFormat.layout(h, 54))
    }
}
