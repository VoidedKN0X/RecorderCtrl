package com.vknxworks.recorderctrl.record

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import com.vknxworks.recorderctrl.data.Recording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class PlayState(
    val id: Long? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
)

/** Plays one recording at a time, from the app's private storage. Pauses when another app takes the audio. */
object Player {
    private var mp: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var am: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp ?: return
            runCatching { _state.value = _state.value.copy(positionMs = p.currentPosition.toLong()) }
            if (_state.value.playing) handler.postDelayed(this, 100)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    /** Plays [r] from the start, or toggles it when it's already loaded. */
    fun toggle(context: Context, r: Recording, file: File) {
        if (_state.value.id == r.id && mp != null) {
            if (_state.value.playing) pause() else resume(context)
            return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.absolutePath)
            p.prepare()
        } catch (e: Exception) {
            runCatching { p.release() }
            return
        }
        p.setOnCompletionListener {
            _state.value = _state.value.copy(playing = false, positionMs = 0L)
            runCatching { it.seekTo(0) }
            abandonFocus()
        }
        mp = p
        _state.value = PlayState(r.id, false, 0L, p.duration.toLong().coerceAtLeast(0), _state.value.speed)
        resume(context)
    }

    private fun resume(context: Context) {
        val p = mp ?: return
        if (RecorderService.state.value.active) return
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
        am = manager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        focus = req
        if (manager?.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return
        runCatching {
            p.playbackParams = PlaybackParams().setSpeed(_state.value.speed)
            p.start()
        }.onFailure { return }
        _state.value = _state.value.copy(playing = true)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun pause() {
        val p = mp ?: return
        runCatching { if (p.isPlaying) p.pause() }
        _state.value = _state.value.copy(playing = false, positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(_state.value.positionMs))
        abandonFocus()
    }

    fun seek(ms: Long) {
        val p = mp ?: return
        runCatching { p.seekTo(ms.coerceIn(0, _state.value.durationMs).toInt()) }
        _state.value = _state.value.copy(positionMs = ms)
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        val p = mp ?: return
        if (_state.value.playing) runCatching { p.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        mp?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mp = null
        _state.value = PlayState(speed = _state.value.speed)
        abandonFocus()
    }

    /** Stops if [id] is the recording that's loaded (it was deleted). */
    fun forget(id: Long) {
        if (_state.value.id == id) stop()
    }

    private fun abandonFocus() {
        focus?.let { f -> am?.abandonAudioFocusRequest(f) }
        focus = null
    }
}
