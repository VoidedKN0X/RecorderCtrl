package com.vknxworks.recorderctrl.transcribe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    private fun tone(freq: Double, rate: Int, seconds: Double, amp: Double = 1.0) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun convert(input: FloatArray, from: Int, to: Int, block: Int = input.size): FloatArray {
        val r = Resampler(from, to)
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val n = minOf(block, input.size - i)
            r.process(input.copyOfRange(i, i + n)).forEach { out.add(it) }
            i += n
        }
        r.flush().forEach { out.add(it) }
        return out.toFloatArray()
    }

    /** RMS of the middle part, away from the start and end transients. */
    private fun rms(x: FloatArray): Double {
        val from = x.size / 4
        val to = x.size * 3 / 4
        var s = 0.0
        for (i in from until to) s += x[i] * x[i].toDouble()
        return sqrt(s / (to - from))
    }

    @Test
    fun outputLengthMatchesRatio() {
        for ((from, to) in listOf(48_000 to 16_000, 44_100 to 16_000, 8_000 to 16_000, 22_050 to 16_000, 16_000 to 16_000)) {
            val input = FloatArray(from * 2 + 37)
            val out = convert(input, from, to)
            assertEquals("$from -> $to", Math.round(input.size.toDouble() * to / from), out.size.toLong())
        }
    }

    @Test
    fun keepsSpeechBandToneFrom48k() {
        val out = convert(tone(1000.0, 48_000, 1.0), 48_000, 16_000)
        assertEquals(1 / sqrt(2.0), rms(out), 0.02)
        // Still a 1 kHz tone: compare with an ideal one, aligned at time zero.
        val ideal = tone(1000.0, 16_000, 1.0)
        for (i in 4000 until 12000) assertEquals("sample $i", ideal[i], out[i], 0.02f)
    }

    @Test
    fun keepsUpperSpeechBand() {
        val out = convert(tone(5000.0, 48_000, 1.0), 48_000, 16_000)
        assertTrue("5 kHz should pass, rms ${rms(out)}", rms(out) > 0.65)
    }

    @Test
    fun removesToneAboveNewNyquist() {
        // 12 kHz can't exist at 16 kHz; without filtering it would fold back to 4 kHz at full level.
        val out = convert(tone(12_000.0, 48_000, 1.0), 48_000, 16_000)
        assertTrue("12 kHz should be filtered out, rms ${rms(out)}", rms(out) < 0.01)
    }

    @Test
    fun keepsDcLevel() {
        val out = convert(FloatArray(44_100) { 0.5f }, 44_100, 16_000)
        for (i in 2000 until out.size - 2000) assertEquals(0.5f, out[i], 0.01f)
    }

    @Test
    fun upsamplesToo() {
        val out = convert(tone(440.0, 8_000, 1.0), 8_000, 16_000)
        val ideal = tone(440.0, 16_000, 1.0)
        for (i in 4000 until 12000) assertEquals(ideal[i], out[i], 0.02f)
    }

    @Test
    fun blockSizeDoesNotChangeTheResult() {
        val input = tone(300.0, 48_000, 0.5, 0.8)
        val whole = convert(input, 48_000, 16_000)
        for (block in listOf(1, 7, 480, 1023, 4096)) {
            val parts = convert(input, 48_000, 16_000, block)
            assertEquals(whole.size, parts.size)
            for (i in whole.indices) assertEquals("block $block, sample $i", whole[i], parts[i], 1e-5f)
        }
    }

    @Test
    fun sameRateIsUnchanged() {
        val input = tone(500.0, 16_000, 0.1)
        assertArrayEquals(input, convert(input, 16_000, 16_000, 333), 0f)
    }

    @Test
    fun emptyInputGivesNothing() {
        val r = Resampler(48_000, 16_000)
        assertEquals(0, r.process(FloatArray(0)).size)
        assertEquals(0, r.flush().size)
    }

    @Test
    fun downmixAveragesChannels() {
        val stereo = floatArrayOf(1f, -1f, 0.5f, 0.25f, 0f, 1f)
        assertArrayEquals(floatArrayOf(0f, 0.375f, 0.5f), Resampler.downmix(stereo, stereo.size, 2), 1e-6f)
        val mono = floatArrayOf(0.1f, 0.2f)
        assertArrayEquals(mono, Resampler.downmix(mono, 2, 1), 0f)
        // A cut-off last frame is dropped.
        assertEquals(1, Resampler.downmix(floatArrayOf(1f, 1f, 1f), 3, 2).size)
    }

    @Test
    fun noClippingOverflowOnFullScaleSquare() {
        val square = FloatArray(48_000) { if ((it / 24) % 2 == 0) 1f else -1f }
        val out = convert(square, 48_000, 16_000)
        // Band-limiting a square wave overshoots a little (Gibbs), but never wildly.
        assertTrue(out.all { abs(it) < 1.3f })
    }
}
