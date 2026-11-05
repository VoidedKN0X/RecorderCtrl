package com.vknxworks.recorderctrl.ui.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * Protects a window while a PIN, lock or confirmation screen is on it:
 * - other apps' floating windows (chat heads, overlays) are hidden over it (HIDE_OVERLAY_WINDOWS), so
 *   nothing can cover or imitate the screen while you type a PIN or confirm a destructive action;
 * - its content is marked as sensitive for accessibility (Android 14+), so only real accessibility tools
 *   such as screen readers can read it, not apps that misuse the accessibility API.
 *
 * Counted per window, so nested screens (a confirmation dialog over a PIN screen) keep it on until the last
 * one closes. Everywhere else, overlays and accessibility services work as usual.
 */
object ScreenGuard {
    private val counts = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        val n = (counts[window] ?: 0) + 1
        counts[window] = n
        if (n == 1) apply(window, true)
    }

    fun release(window: Window) {
        val n = (counts[window] ?: return) - 1
        if (n <= 0) {
            counts.remove(window)
            apply(window, false)
        } else {
            counts[window] = n
        }
    }

    private fun apply(window: Window, on: Boolean) {
        runCatching { window.setHideOverlayWindows(on) }
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                window.decorView.setAccessibilityDataSensitive(
                    if (on) View.ACCESSIBILITY_DATA_SENSITIVE_YES else View.ACCESSIBILITY_DATA_SENSITIVE_AUTO,
                )
            }
        }
    }
}

/** Applies [ScreenGuard] to the window this composable is shown in (the activity, or a dialog) while [active]. */
@Composable
fun GuardWindow(active: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val window = if (active) view.hostWindow() else null
        if (window != null) ScreenGuard.acquire(window)
        onDispose { if (window != null) ScreenGuard.release(window) }
    }
}

private fun View.hostWindow(): Window? =
    (parent as? DialogWindowProvider)?.window ?: context.guardActivity()?.window

private tailrec fun Context.guardActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.guardActivity()
    else -> null
}
