package com.vknxworks.recorderctrl.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.vknxworks.recorderctrl.MainActivity
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.data.Recording
import com.vknxworks.recorderctrl.data.settings.Quality
import com.vknxworks.recorderctrl.data.settings.Source
import com.vknxworks.recorderctrl.ui.common.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10

enum class Status { IDLE, RECORDING, PAUSED, SAVING }

data class RecState(
    val status: Status = Status.IDLE,
    /** Recorded time before the current stretch (pauses don't count). */
    val accumulatedMs: Long = 0L,
    /** elapsedRealtime when the current stretch started; 0 while paused. */
    val runningSince: Long = 0L,
    /** The last few seconds of loudness, 0..1, newest last. */
    val levels: List<Float> = emptyList(),
    val part: File? = null,
) {
    fun elapsed(now: Long = SystemClock.elapsedRealtime()): Long = accumulatedMs + if (runningSince > 0) (now - runningSince).coerceAtLeast(0) else 0
    val active: Boolean get() = status == Status.RECORDING || status == Status.PAUSED
}

/** What a save ended with, for the screen to show once. */
sealed interface SaveResult {
    data class Saved(val recording: Recording) : SaveResult
    data object Failed : SaveResult
    data object Discarded : SaveResult
}

/**
 * Records with Android's MediaRecorder in a foreground service (type microphone), so a recording keeps going
 * with the screen off or in another app. Only RecorderCtrl starts it (not exported).
 */
class RecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var createdAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val meter = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val s = _state.value
            if (s.status == Status.RECORDING) {
                val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                // Log scale: quiet speech shows, shouting doesn't pin it.
                val level = if (amp <= 0) 0f else ((20 * log10(amp / 32767.0) + 60) / 60).toFloat().coerceIn(0f, 1f)
                _state.value = s.copy(levels = (s.levels + level).takeLast(LEVELS))
            }
            handler.postDelayed(this, 50)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish(save = true)
            ACTION_DISCARD -> finish(save = false)
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        createChannel(this)
        try {
            startForeground(NOTIFICATION_ID, notification(Status.RECORDING, 0L), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android refused (no microphone permission, or started from the background).
            _result.value = SaveResult.Failed
            stopSelf()
            return
        }
        val settings = container.settings.settings.value
        createdAt = System.currentTimeMillis()
        val ext = if (settings.quality == Quality.OPUS) "ogg" else "m4a"
        val part = container.recordings.newPartFile(ext, createdAt)
        val r = MediaRecorder(this)
        try {
            r.setAudioSource(audioSource(settings.source))
            if (settings.quality == Quality.OPUS) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioEncodingBitRate(if (settings.stereo) 128_000 else 64_000)
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(
                    when {
                        settings.quality == Quality.HIGH && settings.stereo -> 320_000
                        settings.quality == Quality.HIGH -> 192_000
                        settings.stereo -> 192_000
                        else -> 96_000
                    },
                )
            }
            r.setAudioSamplingRate(48_000)
            r.setAudioChannels(if (settings.stereo) 2 else 1)
            r.setOutputFile(part)
            r.setOnErrorListener { _, _, _ -> finish(save = true) }
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish(save = true)
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            part.delete()
            _result.value = SaveResult.Failed
            _state.value = RecState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recorder = r
        _state.value = RecState(Status.RECORDING, 0L, SystemClock.elapsedRealtime(), emptyList(), part)
        handler.post(meter)
    }

    private fun audioSource(s: Source): Int = when (s) {
        Source.STANDARD -> MediaRecorder.AudioSource.MIC
        Source.VOICE -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        Source.UNPROCESSED -> {
            val am = getSystemService(AudioManager::class.java)
            if (am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private fun pause() {
        val r = recorder ?: return
        val s = _state.value
        if (s.status != Status.RECORDING) return
        if (runCatching { r.pause() }.isFailure) return
        _state.value = s.copy(status = Status.PAUSED, accumulatedMs = s.elapsed(), runningSince = 0L)
        notifyState()
    }

    private fun resume() {
        val r = recorder ?: return
        val s = _state.value
        if (s.status != Status.PAUSED) return
        if (runCatching { r.resume() }.isFailure) return
        _state.value = s.copy(status = Status.RECORDING, runningSince = SystemClock.elapsedRealtime())
        notifyState()
    }

    private fun notifyState() {
        val s = _state.value
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(s.status, s.elapsed())) }
    }

    /** Stops recording; saves the file (or deletes it), then the service ends. */
    private fun finish(save: Boolean) {
        val r = recorder
        val s = _state.value
        handler.removeCallbacks(meter)
        recorder = null
        if (r == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // stop() throws when nothing was recorded yet (a tap on Stop right after Record).
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val part = s.part
        _state.value = s.copy(status = Status.SAVING, runningSince = 0L)
        scope.launch {
            val result: SaveResult = when {
                part == null -> SaveResult.Failed
                !save -> { part.delete(); SaveResult.Discarded }
                !stopped -> { part.delete(); SaveResult.Failed }
                else -> container.recordings.add(part, defaultName(this@RecorderService, createdAt), createdAt)?.let { SaveResult.Saved(it) } ?: SaveResult.Failed
            }
            _result.value = result
            _state.value = RecState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Android is ending the service while recording: keep what was recorded.
        if (recorder != null) {
            handler.removeCallbacks(meter)
            val r = recorder
            recorder = null
            runCatching { r?.stop() }
            runCatching { r?.release() }
            val s = _state.value
            s.part?.let { part ->
                val name = defaultName(this, createdAt)
                val at = createdAt
                // The app's scope outlives this service.
                container.appScope.launch { container.recordings.add(part, name, at)?.let { _result.value = SaveResult.Saved(it) } }
            }
            _state.value = RecState()
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    private fun notification(status: Status, elapsed: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, RecorderService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = status == Status.PAUSED
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(if (paused) R.string.notif_paused else R.string.notif_recording))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused) {
            b.setContentText(formatDuration(elapsed))
            b.addAction(Notification.Action.Builder(null, getString(R.string.resume), action(ACTION_RESUME, 2)).build())
        } else {
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsed).setShowWhen(true)
            b.addAction(Notification.Action.Builder(null, getString(R.string.pause), action(ACTION_PAUSE, 1)).build())
        }
        b.addAction(Notification.Action.Builder(null, getString(R.string.stop_save), action(ACTION_STOP, 3)).build())
        return b.build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        const val LEVELS = 120
        const val ACTION_START = "com.vknxworks.recorderctrl.START"
        const val ACTION_PAUSE = "com.vknxworks.recorderctrl.PAUSE"
        const val ACTION_RESUME = "com.vknxworks.recorderctrl.RESUME"
        const val ACTION_STOP = "com.vknxworks.recorderctrl.STOP"
        const val ACTION_DISCARD = "com.vknxworks.recorderctrl.DISCARD"

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state

        private val _result = MutableStateFlow<SaveResult?>(null)
        /** The last save; the screen clears it after showing it. */
        val result: MutableStateFlow<SaveResult?> = _result

        fun send(context: Context, action: String) {
            val i = Intent(context, RecorderService::class.java).setAction(action)
            runCatching {
                if (action == ACTION_START) context.startForegroundService(i) else context.startService(i)
            }.onFailure { if (action == ACTION_START) _result.value = SaveResult.Failed }
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.ch_recording), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.ch_recording_desc)
                    setShowBadge(false)
                },
            )
        }

        fun defaultName(context: Context, at: Long): String {
            val t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
            return context.getString(R.string.default_name, t.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH.mm.ss", java.util.Locale.getDefault())))
        }
    }
}
