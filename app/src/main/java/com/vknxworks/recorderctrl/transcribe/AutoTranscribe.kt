package com.vknxworks.recorderctrl.transcribe

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.data.Transcripts
import com.vknxworks.recorderctrl.record.RecorderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Transcribe while charging": a JobScheduler job that only runs while the phone is charging. It starts the normal
 * transcription (TranscribeService) for the oldest recording without a transcript, one at a time; when that one is
 * done the job is set up again for the next. Unplugging the phone cancels an automatic transcription (the
 * recording is picked up again the next time). The job isn't kept over a phone restart: it's set up again when
 * RecorderCtrl next starts (opening it, or recording).
 */
object AutoTranscribe {
    private const val JOB_ID = 4201

    /** Recordings that failed in this run of the app, so the job doesn't keep trying them. */
    private val skipped = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    fun skip(id: Long) {
        skipped += id
    }

    /** Sets the job up (or keeps it) when "Transcribe while charging" is on ([on]: the setting as just saved). */
    fun schedule(context: Context, on: Boolean? = null) {
        val app = context.applicationContext
        if (!(on ?: app.container.settings.settings.value.autoTranscribe)) return
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, AutoTranscribeJob::class.java))
            .setRequiresCharging(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        runCatching { js.schedule(info) }
    }

    fun cancel(context: Context) {
        runCatching { context.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }

    /** Starts the next automatic transcription if there is one. True when one was started. */
    internal suspend fun runOnce(context: Context): Boolean {
        val c = context.applicationContext.container
        withTimeoutOrNull(30_000) { c.settings.loaded.first { it } } ?: return false
        val s = c.settings.settings.value
        if (!s.autoTranscribe || !WhisperLib.supported) return false
        if (TranscribeService.state.value != null || RecorderService.state.value.active) return false
        withTimeoutOrNull(60_000) { c.recordings.loaded.first { it } } ?: return false
        c.models.load()
        if (c.models.model.value == null) return false
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
        if (!charging) return false
        val next = withContext(Dispatchers.IO) {
            c.recordings.list.value.sortedBy { it.createdAt }.firstOrNull { r ->
                r.id !in skipped && !Transcripts.hasAny(c.recordings.dir, r.file)
            }
        } ?: return false
        return TranscribeService.start(context.applicationContext, next, s.transcribeLanguage, auto = true)
    }
}

/** The job itself: hands the work to [TranscribeService] and finishes. */
class AutoTranscribeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        app.container.appScope.launch {
            runCatching { AutoTranscribe.runOnce(app) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
