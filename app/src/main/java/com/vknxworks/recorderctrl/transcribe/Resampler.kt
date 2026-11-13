package com.vknxworks.recorderctrl.transcribe

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming sample-rate converter for mono float audio: band-limited (windowed-sinc) interpolation, so going from
 * 48 kHz down to whisper's 16 kHz doesn't fold anything above 8 kHz back into the speech band.
 *
 * Feed it blocks of any size with [process] and call [flush] once at the end; the output is the same as converting
 * everything in one go, and has `round(inputSamples * outRate / inRate)` samples in total.
 */
class Resampler(private val inRate: Int, private val outRate: Int, zeroCrossings: Int = 12) {
    init {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive" }
    }

    private val passThrough = inRate == outRate

    /** Input samples per output sample. */
    private val step = inRate.toDouble() / outRate

    /** The low-pass cut-off as a fraction of the input Nyquist frequency: a little below the lower of the two. */
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.94

    /** How many input samples the kernel reaches on each side. */
    private val halfWidth = ceil(zeroCrossings / cutoff).toInt()

    /** The kernel, sampled [RES] times per input sample from 0 to [halfWidth], interpolated linearly. */
    private val table = FloatArray(halfWidth * RES + 2).also { t ->
        for (i in t.indices) {
            val x = i.toDouble() / RES
            t[i] = if (x >= halfWidth) 0f else (cutoff * sinc(cutoff * x) * blackman(x / halfWidth)).toFloat()
        }
    }

    // Pending input; buf[0] is input sample number `origin` (it starts with halfWidth samples of silence).
    private var buf = FloatArray(4096)
    private var len = halfWidth
    private var origin = -halfWidth.toLong()

    /** The next output sample, as a position in the input (input sample numbers, fractional). */
    private var nextOut = 0L
    private var totalIn = 0L
    private var flushed = false

    /** Converts [count] samples from [input]; returns the output samples that are ready. */
    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        check(!flushed) { "Already flushed" }
        require(count in 0..input.size)
        totalIn += count
        if (passThrough) return input.copyOf(count)
        append(input, count)
        return produce(limit = Long.MAX_VALUE)
    }

    /** Ends the stream: returns the last output samples. */
    fun flush(): FloatArray {
        check(!flushed) { "Already flushed" }
        flushed = true
        if (passThrough) return FloatArray(0)
        append(FloatArray(halfWidth + 1), halfWidth + 1)
        return produce(limit = expectedOutput(totalIn))
    }

    private fun expectedOutput(inSamples: Long): Long = Math.round(inSamples.toDouble() * outRate / inRate)

    private fun append(input: FloatArray, count: Int) {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        System.arraycopy(input, 0, buf, len, count)
        len += count
    }

    private fun produce(limit: Long): FloatArray {
        // Output sample k can be made once input sample floor(k * step) + halfWidth has arrived.
        val reachable = ceil((origin + len - halfWidth) / step).toLong()
        val count = (minOf(reachable, limit) - nextOut + 1).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val out = FloatArray(count)
        var n = 0
        while (nextOut < limit && n < out.size) {
            val t = nextOut * step // input position of this output sample
            val centre = floor(t).toLong()
            if (centre + halfWidth - origin >= len) break
            out[n++] = sample(t, centre)
            nextOut++
        }
        // Keep only what the next output samples still need.
        val keepFrom = floor(nextOut * step).toLong() - halfWidth
        val drop = (keepFrom - origin).toInt().coerceIn(0, len)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, len - drop)
            len -= drop
            origin += drop
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun sample(t: Double, centre: Long): Float {
        var acc = 0.0
        val first = centre - halfWidth + 1
        val last = centre + halfWidth
        var i = first
        while (i <= last) {
            val idx = (i - origin).toInt()
            if (idx >= 0) {
                val d = kotlin.math.abs(t - i) * RES
                val k = d.toInt()
                if (k + 1 < table.size) {
                    val f = (d - k).toFloat()
                    acc += buf[idx] * (table[k] + (table[k + 1] - table[k]) * f)
                }
            }
            i++
        }
        return acc.toFloat()
    }

    companion object {
        private const val RES = 512

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Blackman window over [-1, 1], given |x|. */
        private fun blackman(x: Double): Double {
            val n = 0.5 + 0.5 * x // 0.5 at the centre, 1 at the edge
            return 0.42 - 0.5 * cos(2 * PI * n) + 0.08 * cos(4 * PI * n)
        }

        /** Averages interleaved channels into one. */
        fun downmix(interleaved: FloatArray, count: Int, channels: Int): FloatArray {
            if (channels <= 1) return interleaved.copyOf(count)
            val frames = count / channels
            val out = FloatArray(frames)
            for (f in 0 until frames) {
                var s = 0f
                val base = f * channels
                for (c in 0 until channels) s += interleaved[base + c]
                out[f] = s / channels
            }
            return out
        }
    }
}
