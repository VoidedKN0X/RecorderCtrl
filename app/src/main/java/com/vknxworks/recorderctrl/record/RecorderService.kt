package com.vknxworks.recorderctrl.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
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
import com.vknxworks.recorderctrl.data.settings.Mic
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
    val part: File? = null,
    /** Moments marked while recording (ms of recorded time). */
    val bookmarks: List<Long> = emptyList(),
    /** Paused by RecorderCtrl itself because a call started; it carries on when the call ends. */
    val pausedForCall: Boolean = false,
    /** The microphone chosen in Settings isn't connected, so the phone's own choice is used. */
    val micFallback: Boolean = false,
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
    private var recorder: Capture? = null
    /** A Bluetooth microphone was selected through the communication device, to be cleared at the end. */
    private var commDeviceSet = false
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var createdAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** True while [meter] is scheduled. */
    private var metering = false

    /**
     * The level meter: 20 readings a second for the waveform on the Record screen. It only runs while it has
     * something to show and someone to show it to (see [updateMeter]); the recording itself doesn't depend on it.
     */
    private val meter = object : Runnable {
        override fun run() {
            if (!metering) return
            val r = recorder ?: return
            val amp = r.maxAmplitude()
            // Log scale: quiet speech shows, shouting doesn't pin it.
            val level = if (amp <= 0) 0f else ((20 * log10(amp / 32767.0) + 60) / 60).toFloat().coerceIn(0f, 1f)
            _levels.value = (_levels.value + level).takeLast(LEVELS)
            handler.postDelayed(this, 50)
        }
    }

    /**
     * Starts or stops the meter: it runs only while recording (not while paused) and while a screen is showing
     * the levels. With the screen off or in another app nothing reads the microphone level, so the phone isn't
     * woken 20 times a second for a waveform nobody sees.
     */
    private fun updateMeter() {
        val r = recorder
        val want = r != null && _state.value.status == Status.RECORDING && _levels.subscriptionCount.value > 0
        if (want && !metering) {
            metering = true
            // maxAmplitude is the loudest moment since it was last read: drop what built up while nobody looked.
            r?.maxAmplitude()
            handler.removeCallbacks(meter)
            handler.postDelayed(meter, 50)
        } else if (!want && metering) {
            metering = false
            handler.removeCallbacks(meter)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // A screen started or stopped showing the levels.
        scope.launch { _levels.subscriptionCount.collect { updateMeter() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish(save = true)
            ACTION_DISCARD -> finish(save = false)
            ACTION_BOOKMARK -> {
                bookmark()
                if (recorder == null) stopSelf()
            }
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
        val ext = when (settings.quality) {
            Quality.OPUS -> "ogg"
            Quality.LOSSLESS -> "wav"
            else -> "m4a"
        }
        val part = container.recordings.newPartFile(ext, createdAt)
        val mic = chooseMic(settings.mic)
        // A Bluetooth headset's microphone is only reachable as a communication device, with voice processing.
        val source = if (mic.bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else audioSource(settings.source)
        val capture: Capture = try {
            if (settings.quality == Quality.LOSSLESS) {
                WavCapture.start(part, source, 48_000, if (settings.stereo && !mic.bluetooth) 2 else 1, mic.device) { finish(save = true) }
            } else {
                val r = MediaRecorder(this)
                try {
                    r.setAudioSource(source)
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
                    mic.device?.let { r.setPreferredDevice(it) }
                    r.setOutputFile(part)
                    r.setOnErrorListener { _, _, _ -> finish(save = true) }
                    r.setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish(save = true)
                    }
                    r.prepare()
                    r.start()
                } catch (e: Exception) {
                    runCatching { r.release() }
                    throw e
                }
                MediaCapture(r)
            }
        } catch (e: Exception) {
            clearMic()
            part.delete()
            _result.value = SaveResult.Failed
            _state.value = RecState()
            _levels.value = emptyList()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recorder = capture
        _levels.value = emptyList()
        _state.value = RecState(Status.RECORDING, 0L, SystemClock.elapsedRealtime(), part, micFallback = mic.fallback)
        if (settings.pauseOnCall) watchCalls()
        updateMeter()
    }

    /** The microphone to use: a device, or null for the phone's own choice. */
    private class MicPick(val device: AudioDeviceInfo?, val bluetooth: Boolean = false, val fallback: Boolean = false)

    private fun chooseMic(choice: Mic): MicPick {
        if (choice == Mic.AUTO) return MicPick(null)
        val am = getSystemService(AudioManager::class.java) ?: return MicPick(null, fallback = true)
        val inputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }.getOrDefault(emptyList())
        return when (choice) {
            Mic.AUTO -> MicPick(null)
            Mic.BUILT_IN -> inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { MicPick(it) } ?: MicPick(null, fallback = true)
            Mic.WIRED -> inputs.firstOrNull { it.type in WIRED_INPUTS }?.let { MicPick(it) } ?: MicPick(null, fallback = true)
            Mic.BLUETOOTH -> {
                // Android routes a headset's microphone together with its speaker: select the headset as the
                // communication device (the matching microphone follows) and record with voice processing.
                val sink = runCatching { am.availableCommunicationDevices.firstOrNull { it.type in BLUETOOTH_DEVICES } }.getOrNull()
                val ok = sink != null && runCatching { am.setCommunicationDevice(sink) }.getOrDefault(false)
                if (!ok) return MicPick(null, fallback = true)
                commDeviceSet = true
                MicPick(inputs.firstOrNull { it.type in BLUETOOTH_DEVICES }, bluetooth = true)
            }
        }
    }

    private fun clearMic() {
        if (!commDeviceSet) return
        commDeviceSet = false
        runCatching { getSystemService(AudioManager::class.java)?.clearCommunicationDevice() }
    }

    /**
     * "Pause during calls": while the phone rings or a call (phone or internet) is going on, Android's audio mode
     * isn't "normal". The recording pauses then, and carries on when the mode is normal again, unless you paused or
     * resumed it yourself meanwhile. Needs no permission: only the audio mode is read, nothing about the call.
     */
    private fun watchCalls() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val l = AudioManager.OnModeChangedListener { mode -> handler.post { onAudioMode(mode) } }
        if (runCatching { am.addOnModeChangedListener(mainExecutor, l) }.isSuccess) modeListener = l
        onAudioMode(am.mode)
    }

    private fun onAudioMode(mode: Int) {
        val s = _state.value
        if (mode != AudioManager.MODE_NORMAL) {
            if (s.status == Status.RECORDING) {
                pause()
                _state.value = _state.value.copy(pausedForCall = true)
                notifyState()
            }
        } else if (s.status == Status.PAUSED && s.pausedForCall) {
            resume()
        }
    }

    private fun stopWatchingCalls() {
        modeListener?.let { l -> runCatching { getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(l) } }
        modeListener = null
    }

    /** Marks the current moment of the recording. */
    private fun bookmark() {
        val s = _state.value
        if (!s.active || s.bookmarks.size >= MAX_BOOKMARKS) return
        val at = s.elapsed()
        if (s.bookmarks.lastOrNull()?.let { at - it < 1_000 } == true) return
        _state.value = s.copy(bookmarks = s.bookmarks + at)
        notifyState()
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
        if (!r.pause()) return
        _state.value = s.copy(status = Status.PAUSED, accumulatedMs = s.elapsed(), runningSince = 0L, pausedForCall = false)
        updateMeter()
        notifyState()
    }

    private fun resume() {
        val r = recorder ?: return
        val s = _state.value
        if (s.status != Status.PAUSED) return
        if (!r.resume()) return
        _state.value = s.copy(status = Status.RECORDING, runningSince = SystemClock.elapsedRealtime(), pausedForCall = false)
        updateMeter()
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
        metering = false
        handler.removeCallbacks(meter)
        recorder = null
        if (r == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        stopWatchingCalls()
        // stop() fails when nothing was recorded yet (a tap on Stop right after Record).
        val stopped = r.stop()
        r.release()
        clearMic()
        val part = s.part
        _state.value = s.copy(status = Status.SAVING, runningSince = 0L)
        scope.launch {
            val result: SaveResult = when {
                part == null -> SaveResult.Failed
                !save -> { part.delete(); SaveResult.Discarded }
                !stopped -> { part.delete(); SaveResult.Failed }
                else -> container.recordings.add(part, defaultName(this@RecorderService, createdAt), createdAt, s.bookmarks)?.let { SaveResult.Saved(it) } ?: SaveResult.Failed
            }
            _result.value = result
            if (result is SaveResult.Saved) com.vknxworks.recorderctrl.transcribe.AutoTranscribe.schedule(this@RecorderService)
            _state.value = RecState()
            _levels.value = emptyList()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Android is ending the service while recording: keep what was recorded.
        metering = false
        handler.removeCallbacks(meter)
        if (recorder != null) {
            val r = recorder
            recorder = null
            stopWatchingCalls()
            r?.stop()
            r?.release()
            clearMic()
            val s = _state.value
            s.part?.let { part ->
                val name = defaultName(this, createdAt)
                val at = createdAt
                val marks = s.bookmarks
                // The app's scope outlives this service.
                container.appScope.launch { container.recordings.add(part, name, at, marks)?.let { _result.value = SaveResult.Saved(it) } }
            }
            _state.value = RecState()
            _levels.value = emptyList()
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
            .setContentTitle(
                getString(
                    when {
                        paused && _state.value.pausedForCall -> R.string.notif_paused_call
                        paused -> R.string.notif_paused
                        else -> R.string.notif_recording
                    },
                ),
            )
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
        b.addAction(Notification.Action.Builder(null, getString(R.string.bookmark), action(ACTION_BOOKMARK, 4)).build())
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
        const val ACTION_BOOKMARK = "com.vknxworks.recorderctrl.BOOKMARK"
        private const val MAX_BOOKMARKS = 500
        private val WIRED_INPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
        private val BLUETOOTH_DEVICES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state

        private val _levels = MutableStateFlow<List<Float>>(emptyList())
        /**
         * The last few seconds of loudness while recording, 0..1, newest last. Only measured while a screen is
         * collecting this, so collect it only while it is shown.
         */
        val levels: StateFlow<List<Float>> = _levels

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
