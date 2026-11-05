package com.vknxworks.recorderctrl

import android.content.Context
import com.vknxworks.recorderctrl.data.Recordings
import com.vknxworks.recorderctrl.data.lock.LockVault
import com.vknxworks.recorderctrl.data.settings.AppSettings
import com.vknxworks.recorderctrl.data.settings.SettingsRepository
import com.vknxworks.recorderctrl.record.Player
import com.vknxworks.recorderctrl.record.RecorderService
import com.vknxworks.recorderctrl.ui.lock.AppLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)
    val recordings = Recordings(context)

    /** Which tab to open (from the recording notification). */
    val openTab = MutableStateFlow<Int?>(null)

    /** The recording that was just saved, opened in the Recordings tab. */
    val justSaved = MutableStateFlow<Long?>(null)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "recorderctrl")

    init {
        appScope.launch { recordings.load(RecorderService.state.value.part) }
        AppLock.onLocked = { onLocked() }
    }

    suspend fun enableLock(pin: String) {
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
    }

    fun disableLock() {
        vault.disable()
    }

    /**
     * "Forgot PIN?": the PIN can't be recovered, and skipping it would make the lock pointless, so every
     * recording and setting is deleted and the lock turned off.
     */
    suspend fun forgetEverything() {
        if (RecorderService.state.value.active) RecorderService.send(context, RecorderService.ACTION_DISCARD)
        Player.stop()
        recordings.deleteAll()
        settings.update { AppSettings() }
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        Player.pause()
        vault.close()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }
}

val Context.container: AppContainer
    get() = (applicationContext as RecorderCtrlApp).container
