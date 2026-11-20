package com.vknxworks.recorderctrl

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.recorderctrl.ui.lock.AppLockGate
import com.vknxworks.recorderctrl.ui.lock.GuardWindow
import com.vknxworks.recorderctrl.ui.lock.lockCovering
import com.vknxworks.recorderctrl.ui.nav.AppRoot
import com.vknxworks.recorderctrl.ui.theme.RecorderCtrlTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) take(intent)
        // Hidden from screenshots until the saved settings say otherwise, and no frame drawn before they're in.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val firstFrame = FirstFrameGate(this)
        setContent { CtrlWindow(this, onSettingsShown = { firstFrame.ready = true }) { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    /** The recording notification opens the Record tab; a transcription notification opens that recording. */
    private fun take(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHOW_RECORD -> container.openTab.value = 0
            ACTION_SHOW_RECORDING -> {
                val id = intent.getLongExtra(EXTRA_RECORDING_ID, -1L)
                if (id >= 0) container.justSaved.value = id
                container.openTab.value = 1
            }
        }
    }

    companion object {
        const val ACTION_SHOW_RECORD = "com.vknxworks.recorderctrl.SHOW_RECORD"
        const val ACTION_SHOW_RECORDING = "com.vknxworks.recorderctrl.SHOW_RECORDING"
        const val EXTRA_RECORDING_ID = "recording_id"
    }
}

/**
 * The Ctrl window: follows the phone's dark theme, applies "Hide in screenshots" / "Hide in recent apps",
 * and shows nothing of the app while the app lock is locked.
 */
@Composable
fun CtrlWindow(activity: ComponentActivity, onSettingsShown: () -> Unit = {}, content: @Composable () -> Unit) {
    val c = activity.container
    val dark = isSystemInDarkTheme()
    DisposableEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(DARK_BAR) else SystemBarStyle.light(LIGHT_BAR, DARK_BAR)
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        onDispose { }
    }
    // Read before the settings: once this is true, the settings read below are the saved ones.
    val loaded by c.settings.loaded.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    SideEffect { if (loaded) onSettingsShown() }
    val secure = !loaded || settings.hideScreenshots || lockCovering(c.vault)
    val blankRecents = settings.hideRecents || c.vault.enabled
    // No overlays over the lock and PIN screens, and their content hidden from accessibility misuse.
    GuardWindow(lockCovering(c.vault), sensitive = true)
    LaunchedEffect(secure, blankRecents) {
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.setRecentsScreenshotEnabled(!blankRecents)
    }
    RecorderCtrlTheme(dark) {
        AppLockGate { content() }
    }
}

private val DARK_BAR = android.graphics.Color.rgb(8, 8, 10)
private val LIGHT_BAR = android.graphics.Color.rgb(245, 245, 241)

/**
 * Holds back the window's first frame until the saved settings have been applied to what is drawn, so the
 * app doesn't flash its default settings first. The system's start screen stays up for those few
 * milliseconds; after [MAX_WAIT_MS] the frame is drawn regardless.
 */
internal class FirstFrameGate(activity: ComponentActivity) {
    /** Set once a composition has run with the saved settings. */
    @Volatile
    var ready = false

    init {
        val content: View = activity.findViewById(android.R.id.content)
        val started = SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!ready && SystemClock.uptimeMillis() - started < MAX_WAIT_MS) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    private companion object {
        const val MAX_WAIT_MS = 500L
    }
}
