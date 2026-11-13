package com.vknxworks.recorderctrl.transcribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.vknxworks.recorderctrl.MainActivity
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.data.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A transcription that's running. [progress] is 0..1, or null while the model loads. */
data class TranscribeJob(val recordingId: Long, val recordingFile: String, val name: String, val progress: Float? = null)

/** How a transcription ended, for the screen to show once. */
sealed interface TranscribeOutcome {
    val recordingId: Long

    data class Done(override val recordingId: Long, val lines: Int) : TranscribeOutcome
    data class Failed(override val recordingId: Long, val reason: TranscribeError) : TranscribeOutcome
    data class Cancelled(override val recordingId: Long) : TranscribeOutcome
}

enum class TranscribeError { NO_MODEL, UNSUPPORTED, MODEL_FAILED, AUDIO_FAILED, FAILED }

/**
 * Speech-to-text for one recording at a time, on the phone, in a foreground service (type media processing) with a
 * progress notification and Cancel, so it keeps going with the screen off or in another app. Only RecorderCtrl starts
 * it (not exported). The audio and the transcript never leave the app.
 */
class TranscribeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var session: WhisperSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getLongExtra(EXTRA_ID, -1L), intent.getStringExtra(EXTRA_LANGUAGE) ?: "auto")
            ACTION_CANCEL -> cancel()
            else -> if (job == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(id: Long, language: String) {
        if (job != null) return
        cancelled = false
        lastShown = -1
        val r = container.recordings.list.value.firstOrNull { it.id == id }
        if (r == null) {
            stopSelf()
            return
        }
        createChannels(this)
        val first = TranscribeJob(r.id, r.file, r.name)
        try {
            startForeground(NOTIFICATION_ID, progressNotification(first), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } catch (e: Exception) {
            _outcome.value = TranscribeOutcome.Failed(r.id, TranscribeError.FAILED)
            stopSelf()
            return
        }
        _state.value = first
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecorderCtrl:transcribe")
            ?.apply { setReferenceCounted(false); acquire(MAX_RUN_MS) }
        job = scope.launch {
            val outcome = withContext(Dispatchers.Default) { transcribe(r, language) }
            finish(outcome)
        }
    }

    private suspend fun transcribe(r: Recording, language: String): TranscribeOutcome {
        val c = container
        val model = c.models.model.value ?: return TranscribeOutcome.Failed(r.id, TranscribeError.NO_MODEL)
        if (!WhisperLib.supported) return TranscribeOutcome.Failed(r.id, TranscribeError.UNSUPPORTED)
        val s = WhisperSession.open(model.file) ?: return TranscribeOutcome.Failed(r.id, TranscribeError.MODEL_FAILED)
        session = s
        try {
            // English-only models can't do anything else.
            var lang = if (!s.multilingual) "en" else language
            val decoder = runCatching { AudioDecoder(c.recordings.file(r)) }.getOrNull()
                ?: return TranscribeOutcome.Failed(r.id, TranscribeError.AUDIO_FAILED)
            val segments = ArrayList<Segment>()
            val totalSamples = (r.durationMs * AudioDecoder.SAMPLE_RATE / 1000).coerceAtLeast(1)
            var doneSamples = 0L
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
            decoder.use { d ->
                while (true) {
                    if (!currentCoroutineIsActive()) return TranscribeOutcome.Cancelled(r.id)
                    val chunk = runCatching { d.read(CHUNK_SAMPLES) }.getOrNull()
                        ?: return TranscribeOutcome.Failed(r.id, TranscribeError.AUDIO_FAILED)
                    if (chunk.isEmpty()) break
                    val offsetMs = doneSamples * 1000 / AudioDecoder.SAMPLE_RATE
                    val chunkStart = doneSamples
                    // Progress while whisper works on this block.
                    val ticker = scope.launch {
                        while (isActive) {
                            val inChunk = chunk.size * (s.progress / 100f)
                            update(r, ((chunkStart + inChunk) / totalSamples).coerceIn(0f, 0.99f))
                            delay(1000)
                        }
                    }
                    val result = try {
                        s.run(chunk, lang, threads)
                    } finally {
                        ticker.cancel()
                    }
                    if (cancelled) return TranscribeOutcome.Cancelled(r.id)
                    result ?: return TranscribeOutcome.Failed(r.id, TranscribeError.FAILED)
                    // Detect the language once, then keep it for the rest of the recording.
                    if (lang == "auto" && result.language.isNotBlank() && result.language != "auto") lang = result.language
                    result.segments.mapTo(segments) { it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs) }
                    doneSamples += chunk.size
                    update(r, (doneSamples.toFloat() / totalSamples).coerceIn(0f, 0.99f))
                }
            }
            // The recording may have been deleted meanwhile.
            if (c.recordings.list.value.none { it.id == r.id }) return TranscribeOutcome.Cancelled(r.id)
            val t = Transcript(
                recording = r.file,
                language = if (lang == "auto") "" else lang,
                model = model.name,
                createdAt = System.currentTimeMillis(),
                segments = segments,
            )
            return if (c.transcripts.save(t)) TranscribeOutcome.Done(r.id, segments.size)
            else TranscribeOutcome.Failed(r.id, TranscribeError.FAILED)
        } finally {
            session = null
            s.close()
        }
    }

    @Volatile private var cancelled = false

    private fun currentCoroutineIsActive() = !cancelled && job?.isActive != false

    private var lastShown = -1

    private fun update(r: Recording, progress: Float) {
        val s = _state.value ?: return
        if (s.recordingId != r.id) return
        val next = s.copy(progress = progress)
        _state.value = next
        val pct = (progress * 100).toInt()
        if (pct != lastShown) {
            lastShown = pct
            runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, progressNotification(next)) }
        }
    }

    private fun cancel() {
        cancelled = true
        session?.cancel()
        if (job == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finish(outcome: TranscribeOutcome) {
        val name = _state.value?.name.orEmpty()
        _state.value = null
        _outcome.value = outcome
        job = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (outcome !is TranscribeOutcome.Cancelled) resultNotification(outcome, name)
        stopSelf()
    }

    /** Android ends media processing after 6 hours a day: stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancel()
    }

    override fun onDestroy() {
        cancelled = true
        session?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (_state.value != null) _state.value = null
        scope.cancel()
        super.onDestroy()
    }

    private fun openIntent(id: Long): PendingIntent = PendingIntent.getActivity(
        this, id.toInt(),
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_RECORDING)
            .putExtra(MainActivity.EXTRA_RECORDING_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(j: TranscribeJob): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val p = j.progress
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notif_transcribing))
            .setContentText(if (p == null) getString(R.string.transcribe_loading_model) else getString(R.string.transcribe_percent, (p * 100).toInt()))
            .setProgress(100, ((p ?: 0f) * 100).toInt(), p == null)
            .setContentIntent(openIntent(j.recordingId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            // The recording's name stays off the lock screen.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    private fun resultNotification(o: TranscribeOutcome, name: String) {
        val title = getString(if (o is TranscribeOutcome.Done) R.string.notif_transcript_ready else R.string.notif_transcript_failed)
        val text = if (o is TranscribeOutcome.Failed) getString(errorText(o.reason)) else name
        val n = Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent(o.recordingId))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, n) }
    }

    companion object {
        private const val CHANNEL_PROGRESS = "transcribing"
        private const val CHANNEL_DONE = "transcripts"
        private const val NOTIFICATION_ID = 2
        private const val RESULT_NOTIFICATION_ID = 3
        private const val ACTION_START = "com.vknxworks.recorderctrl.TRANSCRIBE"
        private const val ACTION_CANCEL = "com.vknxworks.recorderctrl.TRANSCRIBE_CANCEL"
        private const val EXTRA_ID = "id"
        private const val EXTRA_LANGUAGE = "language"

        /** Five minutes of 16 kHz audio (about 19 MB) per pass, so long recordings never need all their audio in memory. */
        private const val CHUNK_SAMPLES = 5 * 60 * AudioDecoder.SAMPLE_RATE

        /** Media processing may run 6 hours a day; the wake lock never outlives that. */
        private const val MAX_RUN_MS = 6 * 60 * 60 * 1000L

        private val _state = MutableStateFlow<TranscribeJob?>(null)
        val state: StateFlow<TranscribeJob?> = _state

        private val _outcome = MutableStateFlow<TranscribeOutcome?>(null)
        /** The last transcription's end; the screen clears it after showing it. */
        val outcome: MutableStateFlow<TranscribeOutcome?> = _outcome

        fun start(context: Context, r: Recording, language: String) {
            val i = Intent(context, TranscribeService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_ID, r.id).putExtra(EXTRA_LANGUAGE, language)
            runCatching { context.startForegroundService(i) }
                .onFailure { _outcome.value = TranscribeOutcome.Failed(r.id, TranscribeError.FAILED) }
        }

        fun cancel(context: Context) {
            if (_state.value == null) return
            runCatching { context.startService(Intent(context, TranscribeService::class.java).setAction(ACTION_CANCEL)) }
        }

        fun errorText(e: TranscribeError): Int = when (e) {
            TranscribeError.NO_MODEL -> R.string.transcribe_error_no_model
            TranscribeError.UNSUPPORTED -> R.string.transcribe_error_unsupported
            TranscribeError.MODEL_FAILED -> R.string.transcribe_error_model
            TranscribeError.AUDIO_FAILED -> R.string.transcribe_error_audio
            TranscribeError.FAILED -> R.string.transcribe_error_failed
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.ch_transcribing), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.ch_transcribing_desc)
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, context.getString(R.string.ch_transcripts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.ch_transcripts_desc)
                },
            )
        }
    }
}
