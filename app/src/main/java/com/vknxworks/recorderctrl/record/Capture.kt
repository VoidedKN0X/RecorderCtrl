package com.vknxworks.recorderctrl.record

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** One running recording, whatever records it. */
interface Capture {
    /** The loudest moment since the last call, 0..32767. */
    fun maxAmplitude(): Int
    fun pause(): Boolean
    fun resume(): Boolean

    /** Stops and finishes the file. False when nothing usable was recorded. */
    fun stop(): Boolean
    fun release()
}

/** AAC or Opus through Android's MediaRecorder (the recording was made this way before the lossless option). */
class MediaCapture(private val r: MediaRecorder) : Capture {
    override fun maxAmplitude(): Int = runCatching { r.maxAmplitude }.getOrDefault(0)
    override fun pause(): Boolean = runCatching { r.pause() }.isSuccess
    override fun resume(): Boolean = runCatching { r.resume() }.isSuccess
    override fun stop(): Boolean = runCatching { r.stop() }.isSuccess
    override fun release() {
        runCatching { r.release() }
    }
}

/**
 * Lossless: 16-bit PCM from AudioRecord, written straight into a WAV file. The header's sizes are brought up to
 * date every couple of seconds, so a recording cut off by a crash or power loss still plays (and [WavFormat.repair]
 * fixes the rest when the app starts again). Writing happens on its own thread; [onError] is called on the main
 * thread when the file can't be written any more (storage full) or the 4 GiB WAV limit is near.
 */
class WavCapture private constructor(
    private val record: AudioRecord,
    private val file: RandomAccessFile,
    private val rate: Int,
    private val channels: Int,
    private val onError: () -> Unit,
) : Capture {
    private val peak = AtomicInteger(0)
    @Volatile private var paused = false
    @Volatile private var running = true
    @Volatile private var failed = false
    private var dataBytes = 0L
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())

    private val thread = Thread({ loop() }, "RecorderCtrl-wav").apply { priority = Thread.MAX_PRIORITY }

    private fun start() {
        file.setLength(0)
        file.write(WavFormat.header(rate, channels, 0))
        record.startRecording()
        thread.start()
    }

    private fun loop() {
        val buf = ShortArray(rate * channels / 10) // 100 ms
        val bytes = ByteArray(buf.size * 2)
        var lastHeader = System.currentTimeMillis()
        while (running) {
            if (paused) {
                synchronized(lock) { if (paused && running) lock.wait(200) }
                continue
            }
            val n = record.read(buf, 0, buf.size)
            if (n <= 0) {
                if (n < 0 && running && !paused) fail()
                continue
            }
            var max = 0
            for (i in 0 until n) {
                val v = buf[i].toInt()
                bytes[2 * i] = (v and 0xFF).toByte()
                bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                val a = abs(v)
                if (a > max) max = a
            }
            peak.accumulateAndGet(max) { a, b -> maxOf(a, b) }
            try {
                synchronized(lock) {
                    if (!running) return
                    file.write(bytes, 0, n * 2)
                    dataBytes += n * 2
                    val now = System.currentTimeMillis()
                    if (now - lastHeader > 2_000) {
                        lastHeader = now
                        writeHeader()
                    }
                }
            } catch (e: Exception) {
                fail()
                return
            }
            if (dataBytes >= WavFormat.MAX_DATA_BYTES) {
                fail()
                return
            }
        }
    }

    /** Writes the sizes into the header and goes back to the end. Called with [lock] held. */
    private fun writeHeader() {
        val end = file.filePointer
        file.seek(0)
        file.write(WavFormat.header(rate, channels, dataBytes))
        file.seek(end)
    }

    private fun fail() {
        if (failed) return
        failed = true
        main.post(onError)
    }

    override fun maxAmplitude(): Int = peak.getAndSet(0)

    override fun pause(): Boolean {
        if (paused) return true
        paused = true
        runCatching { record.stop() }
        return true
    }

    override fun resume(): Boolean {
        if (!paused) return true
        val ok = runCatching { record.startRecording() }.isSuccess
        if (ok) synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
        return ok
    }

    override fun stop(): Boolean {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
        runCatching { record.stop() }
        runCatching { thread.join(2_000) }
        return synchronized(lock) {
            runCatching {
                writeHeader()
                file.fd.sync()
            }.isSuccess && dataBytes > 0
        }
    }

    override fun release() {
        running = false
        runCatching { record.release() }
        runCatching { file.close() }
    }

    companion object {
        /**
         * Starts a lossless recording into [out]. Throws when the microphone or the file can't be opened.
         * [device]: the microphone to use, or null for the phone's choice.
         */
        @android.annotation.SuppressLint("MissingPermission") // Only started after RECORD_AUDIO was granted.
        fun start(out: File, source: Int, rate: Int, channels: Int, device: AudioDeviceInfo?, onError: () -> Unit): WavCapture {
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) throw IllegalStateException("Unsupported format")
            val record = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min * 4, rate * channels * 2 / 2))
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("AudioRecord not ready")
            }
            if (device != null) record.setPreferredDevice(device)
            val raf = try {
                RandomAccessFile(out, "rw")
            } catch (e: Exception) {
                record.release()
                throw e
            }
            val c = WavCapture(record, raf, rate, channels, onError)
            try {
                c.start()
            } catch (e: Exception) {
                c.release()
                throw e
            }
            return c
        }
    }
}
