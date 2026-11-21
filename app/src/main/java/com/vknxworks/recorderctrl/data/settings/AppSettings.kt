package com.vknxworks.recorderctrl.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.recorderctrl.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "recorderctrl")

/** How recordings are saved. */
enum class Quality(@StringRes val label: Int, @StringRes val detail: Int) {
    STANDARD(R.string.quality_standard, R.string.quality_standard_sub),
    HIGH(R.string.quality_high, R.string.quality_high_sub),
    OPUS(R.string.quality_opus, R.string.quality_opus_sub),
    /** 16-bit PCM in a WAV file, recorded with AudioRecord: nothing is thrown away. */
    LOSSLESS(R.string.quality_lossless, R.string.quality_lossless_sub),
}

/** Which microphone to record with. A chosen one that isn't connected falls back to the phone's own choice. */
enum class Mic(@StringRes val label: Int, @StringRes val detail: Int) {
    AUTO(R.string.mic_auto, R.string.mic_auto_sub),
    BUILT_IN(R.string.mic_builtin, R.string.mic_builtin_sub),
    WIRED(R.string.mic_wired, R.string.mic_wired_sub),
    BLUETOOTH(R.string.mic_bluetooth, R.string.mic_bluetooth_sub),
}

/** Which microphone processing Android applies. */
enum class Source(@StringRes val label: Int, @StringRes val detail: Int) {
    STANDARD(R.string.source_standard, R.string.source_standard_sub),
    VOICE(R.string.source_voice, R.string.source_voice_sub),
    UNPROCESSED(R.string.source_unprocessed, R.string.source_unprocessed_sub),
}

/** How long RecorderCtrl can be in the background before the app lock asks again (-1: only after a restart). */
enum class LockTimeout(@StringRes val label: Int, val millis: Long) {
    IMMEDIATELY(R.string.lock_again_immediately, 0L),
    MINUTE_1(R.string.lock_again_1m, 60_000L),
    MINUTES_5(R.string.lock_again_5m, 5 * 60_000L),
    MINUTES_15(R.string.lock_again_15m, 15 * 60_000L),
    HOUR_1(R.string.lock_again_1h, 60 * 60_000L),
    RESTART(R.string.lock_again_restart, -1L),
}

data class AppSettings(
    val quality: Quality = Quality.STANDARD,
    val stereo: Boolean = false,
    val source: Source = Source.STANDARD,
    /** Keep the screen on while the recording screen is open and recording. */
    val keepScreenOn: Boolean = false,
    /** App lock: when it asks again after RecorderCtrl was in the background. */
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    /** "Hide in screenshots": FLAG_SECURE on the app's windows (screenshots, recordings and the Recents preview). */
    val hideScreenshots: Boolean = false,
    /** "Hide in recent apps": the Recents view shows a blank card. Screenshots still work. */
    val hideRecents: Boolean = false,
    /** Speech-to-text: whisper's language code, or "auto" to detect it. */
    val transcribeLanguage: String = "auto",
    /** The microphone to record with. */
    val mic: Mic = Mic.AUTO,
    /** Pause a recording while a phone or internet call is going on (and carry on after it). */
    val pauseOnCall: Boolean = true,
    /** Transcribe recordings without a transcript by themselves while the phone is charging. */
    val autoTranscribe: Boolean = false,
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val quality = stringPreferencesKey("quality")
        val stereo = booleanPreferencesKey("stereo")
        val source = stringPreferencesKey("source")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
        val transcribeLanguage = stringPreferencesKey("transcribe_language")
        val mic = stringPreferencesKey("mic")
        val pauseOnCall = booleanPreferencesKey("pause_on_call")
        val autoTranscribe = booleanPreferencesKey("auto_transcribe")
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private val current = MutableStateFlow(AppSettings())
    private val read = MutableStateFlow(false)

    /** The settings; the defaults until the saved ones have been read (see [loaded]). */
    val settings: StateFlow<AppSettings> = current

    /**
     * True once the saved settings have been read from storage. Always set after [settings] got its first
     * saved value, so whoever sees `true` here also sees the saved settings.
     */
    val loaded: StateFlow<Boolean> = read

    init {
        scope.launch {
            prefs.map { it.toSettings() }.collect { s ->
                current.value = s
                read.value = true
            }
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        quality = enumOr(this[K.quality], Quality.STANDARD),
        stereo = this[K.stereo] ?: false,
        source = enumOr(this[K.source], Source.STANDARD),
        keepScreenOn = this[K.keepScreenOn] ?: false,
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
        transcribeLanguage = this[K.transcribeLanguage]?.takeIf { it.isNotBlank() && it.length <= 8 } ?: "auto",
        mic = enumOr(this[K.mic], Mic.AUTO),
        pauseOnCall = this[K.pauseOnCall] ?: true,
        autoTranscribe = this[K.autoTranscribe] ?: false,
    )

    /** Saves a change. If the phone's storage fails, the change is dropped instead of closing the app. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            context.appDataStore.edit { p ->
                val s = transform(p.toSettings())
                p[K.quality] = s.quality.name
                p[K.stereo] = s.stereo
                p[K.source] = s.source.name
                p[K.keepScreenOn] = s.keepScreenOn
                p[K.lockTimeout] = s.lockTimeout.name
                p[K.hideScreenshots] = s.hideScreenshots
                p[K.hideRecents] = s.hideRecents
                p[K.transcribeLanguage] = s.transcribeLanguage
                p[K.mic] = s.mic.name
                p[K.pauseOnCall] = s.pauseOnCall
                p[K.autoTranscribe] = s.autoTranscribe
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
        }
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
