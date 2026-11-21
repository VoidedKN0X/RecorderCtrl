package com.vknxworks.recorderctrl.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A settings file: RecorderCtrl's recording and speech-to-text settings as JSON, in a file the user picks. It holds
 * no recordings, transcripts, speech model, PIN, or app-lock and privacy settings, and an import never changes
 * those. Every field is optional: a missing one leaves the current setting as it is.
 */
@Serializable
data class RecorderBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val appVersion: String? = null,
    val createdAt: String? = null,
    val quality: String? = null,
    val stereo: Boolean? = null,
    val source: String? = null,
    val mic: String? = null,
    val pauseOnCall: Boolean? = null,
    val keepScreenOn: Boolean? = null,
    val transcribeLanguage: String? = null,
    val autoTranscribe: Boolean? = null,
) {
    companion object {
        const val FORMAT = "recorderctrl-settings"
        const val VERSION = 1
    }
}

/** Not a RecorderCtrl settings file, or ([newer]) one from a newer RecorderCtrl. */
class BackupFormatException(val newer: Boolean) : Exception()

object SettingsBackups {
    const val MAX_BYTES = 64 * 1024
    private val language = Regex("[a-z]{2,3}|auto")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun export(s: AppSettings, appVersion: String, now: String): String = json.encodeToString(
        RecorderBackup.serializer(),
        RecorderBackup(
            appVersion = appVersion,
            createdAt = now,
            quality = s.quality.name,
            stereo = s.stereo,
            source = s.source.name,
            mic = s.mic.name,
            pauseOnCall = s.pauseOnCall,
            keepScreenOn = s.keepScreenOn,
            transcribeLanguage = s.transcribeLanguage,
            autoTranscribe = s.autoTranscribe,
        ),
    )

    fun parse(text: String): RecorderBackup {
        val b = runCatching { json.decodeFromString(RecorderBackup.serializer(), text) }.getOrNull()
            ?: throw BackupFormatException(newer = false)
        if (b.format != RecorderBackup.FORMAT) throw BackupFormatException(newer = false)
        if (b.version > RecorderBackup.VERSION) throw BackupFormatException(newer = true)
        return b
    }

    /** The settings after importing [b]. The app lock, Hide in screenshots and Hide in recent apps always stay. */
    fun apply(b: RecorderBackup, current: AppSettings): AppSettings = current.copy(
        quality = enumOr(b.quality, current.quality),
        stereo = b.stereo ?: current.stereo,
        source = enumOr(b.source, current.source),
        mic = enumOr(b.mic, current.mic),
        pauseOnCall = b.pauseOnCall ?: current.pauseOnCall,
        keepScreenOn = b.keepScreenOn ?: current.keepScreenOn,
        transcribeLanguage = b.transcribeLanguage?.takeIf { language.matches(it) } ?: current.transcribeLanguage,
        autoTranscribe = b.autoTranscribe ?: current.autoTranscribe,
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
