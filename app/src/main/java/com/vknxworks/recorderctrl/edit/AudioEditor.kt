package com.vknxworks.recorderctrl.edit

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.vknxworks.recorderctrl.edit.EditMath.Mode
import com.vknxworks.recorderctrl.record.WavFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Trim (keep a part) and cut (remove a part) without re-encoding: the audio is copied as it is, so nothing is
 * lost. WAV is copied byte for byte; AAC (.m4a) and Opus (.ogg) are copied packet by packet with Android's
 * MediaExtractor and MediaMuxer, so the edges fall on the nearest packet (about 20 ms). The original file is
 * only read; the result goes to a new file.
 */
object AudioEditor {
    /** Writes the edited audio from [src] into [dst]. Throws when it can't. */
    fun edit(src: File, dst: File, mode: Mode, startMs: Long, endMs: Long) {
        when (src.extension.lowercase()) {
            "wav" -> editWav(src, dst, mode, startMs, endMs)
            "m4a", "mp4" -> editPackets(src, dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, mode, startMs, endMs)
            "ogg", "opus" -> editPackets(src, dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, mode, startMs, endMs)
            else -> throw IllegalArgumentException("Unsupported file")
        }
    }

    private fun editWav(src: File, dst: File, mode: Mode, startMs: Long, endMs: Long) {
        RandomAccessFile(src, "r").use { input ->
            val head = ByteArray(minOf(input.length(), 4096L).toInt())
            input.readFully(head)
            val l = WavFormat.layout(head, input.length()) ?: throw IllegalArgumentException("Not a WAV file")
            val a = EditMath.byteAt(startMs, l.bytesPerMs, l.frameBytes, l.dataBytes)
            val b = EditMath.byteAt(endMs, l.bytesPerMs, l.frameBytes, l.dataBytes)
            val ranges = when (mode) {
                Mode.TRIM -> listOf(a to b)
                Mode.CUT -> listOf(0L to a, b to l.dataBytes)
            }.filter { it.second > it.first }
            val total = ranges.sumOf { it.second - it.first }
            if (total <= 0) throw IllegalArgumentException("Nothing left")
            RandomAccessFile(dst, "rw").use { out ->
                out.setLength(0)
                out.write(WavFormat.header(l.rate, l.channels, total))
                val buf = ByteArray(256 * 1024)
                for ((from, to) in ranges) {
                    input.seek(l.dataOffset + from)
                    var left = to - from
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) throw java.io.EOFException()
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                out.fd.sync()
            }
        }
    }

    private fun editPackets(src: File, dst: File, outputFormat: Int, mode: Mode, startMs: Long, endMs: Long) {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(src.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024
            val m = MediaMuxer(dst.absolutePath, outputFormat)
            muxer = m
            val out = m.addTrack(format)
            m.start()
            started = true
            val buf = ByteBuffer.allocate(maxOf(maxSize, 64 * 1024))
            val info = MediaCodec.BufferInfo()
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            var written = 0
            var lastUs = -1L
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                val keep = when (mode) {
                    Mode.TRIM -> t in startUs until endUs
                    Mode.CUT -> t < startUs || t >= endUs
                }
                if (keep) {
                    var outUs = when (mode) {
                        Mode.TRIM -> t - startUs
                        Mode.CUT -> if (t < startUs) t else t - (endUs - startUs)
                    }
                    // Timestamps must keep going up.
                    if (outUs <= lastUs) outUs = lastUs + 1
                    lastUs = outUs
                    info.set(0, size, outUs, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(out, buf, info)
                    written++
                }
                if (mode == Mode.TRIM && t >= endUs) break
                ex.advance()
            }
            if (written == 0) throw IllegalArgumentException("Nothing left")
            m.stop()
            started = false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { ex.release() }
        }
    }
}
