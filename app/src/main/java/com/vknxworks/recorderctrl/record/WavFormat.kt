package com.vknxworks.recorderctrl.record

import java.io.File
import java.io.RandomAccessFile

/**
 * The WAV (RIFF) file format for the lossless recording option: 16-bit little-endian PCM with a plain 44-byte
 * header. Worked out without Android so it can be unit-tested.
 */
object WavFormat {
    const val HEADER_BYTES = 44

    /** RIFF sizes are 32-bit: stop a little before 4 GiB. */
    const val MAX_DATA_BYTES = 0xFFFF_FFFFL - HEADER_BYTES - 1024 * 1024

    /** A 44-byte header for [dataBytes] bytes of 16-bit PCM at [rate] Hz with [channels] channels. */
    fun header(rate: Int, channels: Int, dataBytes: Long): ByteArray {
        val bits = 16
        val blockAlign = channels * bits / 8
        val byteRate = rate * blockAlign
        val data = dataBytes.coerceIn(0, MAX_DATA_BYTES + 1024 * 1024)
        val b = ByteArray(HEADER_BYTES)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, ch -> b[at + i] = ch.code.toByte() }
        fun le32(at: Int, v: Long) { for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        fun le16(at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }
        ascii(0, "RIFF")
        le32(4, 36 + data)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1) // PCM
        le16(22, channels)
        le32(24, rate.toLong())
        le32(28, byteRate.toLong())
        le16(32, blockAlign)
        le16(34, bits)
        ascii(36, "data")
        le32(40, data)
        return b
    }

    /** Where the PCM data is in a WAV file: offset, length, sample rate, channels, bits. */
    data class Layout(val dataOffset: Long, val dataBytes: Long, val rate: Int, val channels: Int, val bits: Int) {
        val frameBytes: Int get() = (channels * bits / 8).coerceAtLeast(1)
        val bytesPerMs: Double get() = rate.toDouble() * frameBytes / 1000.0
    }

    /**
     * Reads the chunks of a PCM WAV file ([head]: at least its first bytes, [fileLength]: its full size). The data
     * length is taken from the file size when the header says 0 or more than is there (a recording that was cut off).
     * Null when it isn't 16-bit PCM WAV.
     */
    fun layout(head: ByteArray, fileLength: Long): Layout? {
        fun str(at: Int) = if (at + 4 <= head.size) String(head, at, 4, Charsets.US_ASCII) else ""
        fun u32(at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((head[at + i].toLong() and 0xFF) shl (8 * i)) }
        fun u16(at: Int): Int = (head[at].toInt() and 0xFF) or ((head[at + 1].toInt() and 0xFF) shl 8)
        if (str(0) != "RIFF" || str(8) != "WAVE") return null
        var at = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var format = 0
        while (at + 8 <= head.size) {
            val id = str(at)
            val size = u32(at + 4)
            if (id == "fmt " && at + 24 <= head.size) {
                format = u16(at + 8)
                channels = u16(at + 10)
                rate = u32(at + 12).toInt()
                bits = u16(at + 22)
            } else if (id == "data") {
                if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return null
                val offset = (at + 8).toLong()
                val there = (fileLength - offset).coerceAtLeast(0)
                val frame = channels * 2
                var len = if (size == 0L || size > there) there else size
                len -= len % frame
                return Layout(offset, len, rate, channels, bits)
            }
            at += 8 + size.toInt().coerceAtLeast(0) + (size % 2).toInt()
            if (size < 0 || size > head.size) break
        }
        return null
    }

    /**
     * Puts the right sizes in the header of a WAV file that was cut off while recording (the sizes are only written
     * now and then), so it can be played. Only touches files this app wrote: a 44-byte header in our layout.
     */
    fun repair(file: File): Boolean = runCatching {
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < HEADER_BYTES) return@use false
            val head = ByteArray(HEADER_BYTES)
            raf.readFully(head)
            val l = layout(head, raf.length()) ?: return@use false
            if (l.dataOffset != HEADER_BYTES.toLong()) return@use false
            raf.seek(0)
            raf.write(header(l.rate, l.channels, l.dataBytes))
            true
        }
    }.getOrDefault(false)
}
