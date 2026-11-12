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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "recorderctrl")

/** How recordings are saved. */
enum class Quality(@StringRes val label: Int, @StringRes val detail: Int) {
    STANDARD(R.string.quality_standard, R.string.quality_standard_sub),
    HIGH(R.string.quality_high, R.string.quality_high_sub),
    OPUS(R.string.quality_opus, R.string.quality_opus_sub),
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
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: StateFlow<AppSettings> = prefs
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings() = AppSettings(
        quality = enumOr(this[K.quality], Quality.STANDARD),
        stereo = this[K.stereo] ?: false,
        source = enumOr(this[K.source], Source.STANDARD),
        keepScreenOn = this[K.keepScreenOn] ?: false,
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
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
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
        }
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
