package com.vknxworks.recorderctrl.transcribe

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes a recording (AAC .m4a or Opus .ogg, any sample rate, mono or stereo) with Android's own decoders and hands
 * it out as 16 kHz mono float samples, a block at a time, so an hour-long recording never has to fit in memory.
 */
class AudioDecoder(file: File) : Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private var inputDone = false
    private var outputDone = false
    private var resampler: Resampler? = null
    private var currentRate = 0
    private var channels = 1
    private var floatPcm = false
    private var pending = FloatArray(0)
    private var pendingLen = 0
    private val info = MediaCodec.BufferInfo()

    init {
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            setOutputFormat(format)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
    }

    private fun setOutputFormat(f: MediaFormat) {
        channels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else channels
        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48_000
        if (resampler == null || rate != currentRate) {
            // A rate change mid-stream is rare (it only happens with the first output buffer); finish the old one.
            resampler?.let { add(it.flush()) }
            resampler = Resampler(rate, SAMPLE_RATE)
            currentRate = rate
        }
    }

    /** Up to [max] samples at 16 kHz mono; an empty array once the recording has ended. */
    fun read(max: Int): FloatArray {
        while (pendingLen < max && !outputDone) step()
        val n = minOf(max, pendingLen)
        val out = pending.copyOf(n)
        System.arraycopy(pending, n, pending, 0, pendingLen - n)
        pendingLen -= n
        return out
    }

    private fun step() {
        if (!inputDone) {
            val i = codec.dequeueInputBuffer(TIMEOUT_US)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i)!!
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                    extractor.advance()
                }
            }
        }
        val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> setOutputFormat(codec.outputFormat)
            o >= 0 -> {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val interleaved: FloatArray
                    if (floatPcm) {
                        val fb = buf.asFloatBuffer()
                        interleaved = FloatArray(fb.remaining()).also { fb.get(it) }
                    } else {
                        val sb = buf.asShortBuffer()
                        interleaved = FloatArray(sb.remaining())
                        for (k in interleaved.indices) interleaved[k] = sb.get(k) / 32768f
                    }
                    val mono = Resampler.downmix(interleaved, interleaved.size, channels)
                    add(resampler!!.process(mono))
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                    add(resampler!!.flush())
                }
            }
        }
    }

    private fun add(samples: FloatArray) {
        if (samples.isEmpty()) return
        if (pendingLen + samples.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + samples.size))
        System.arraycopy(samples, 0, pending, pendingLen, samples.size)
        pendingLen += samples.size
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    companion object {
        /** whisper wants 16 kHz mono. */
        const val SAMPLE_RATE = 16_000
        private const val TIMEOUT_US = 10_000L
    }
}
