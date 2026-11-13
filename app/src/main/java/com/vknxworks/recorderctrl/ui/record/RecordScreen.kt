package com.vknxworks.recorderctrl.ui.record

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.data.Recording
import com.vknxworks.recorderctrl.record.Player
import com.vknxworks.recorderctrl.record.RecorderService
import com.vknxworks.recorderctrl.record.SaveResult
import com.vknxworks.recorderctrl.record.Status
import com.vknxworks.recorderctrl.ui.common.formatDuration
import com.vknxworks.recorderctrl.ui.common.hasPermission
import com.vknxworks.recorderctrl.ui.common.openAppSettings
import com.vknxworks.recorderctrl.ui.components.ConfirmDialog
import com.vknxworks.recorderctrl.ui.components.CtrlHeroCard
import com.vknxworks.recorderctrl.ui.components.TabTitle
import com.vknxworks.recorderctrl.ui.components.WarningCard
import com.vknxworks.recorderctrl.ui.components.bottomClearance
import com.vknxworks.recorderctrl.ui.lock.AppLock
import com.vknxworks.recorderctrl.ui.theme.CtrlColors
import com.vknxworks.recorderctrl.ui.theme.CtrlShapes
import com.vknxworks.recorderctrl.ui.theme.CtrlType
import com.vknxworks.recorderctrl.ui.theme.Gutter
import kotlinx.coroutines.delay

/** The Record tab. */
@Composable
fun RecordScreen(onSaved: (Recording) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        TabTitle(stringResource(R.string.tab_record))
        RecordPanel(onSaved)
        Spacer(Modifier.bottomClearance())
    }
}

/** Timer, level meter and the record / pause / stop buttons. Also used when another app asks for a recording. */
@Composable
fun RecordPanel(onSaved: (Recording) -> Unit, showQuality: Boolean = true) {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val state by RecorderService.state.collectAsStateWithLifecycle()
    val result by RecorderService.result.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var asked by remember { mutableIntStateOf(0) }
    var micDenied by remember { mutableStateOf(false) }

    LaunchedEffect(state.status) {
        while (state.status == Status.RECORDING) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(100)
        }
        now = android.os.SystemClock.elapsedRealtime()
    }
    LaunchedEffect(result) {
        when (val r = result) {
            is SaveResult.Saved -> { RecorderService.result.value = null; onSaved(r.recording) }
            SaveResult.Failed -> {
                RecorderService.result.value = null
                Toast.makeText(context, context.getString(R.string.record_failed), Toast.LENGTH_LONG).show()
            }
            SaveResult.Discarded -> RecorderService.result.value = null
            null -> {}
        }
    }
    // "Keep screen on" while recording, only on this screen.
    val activity = context as? Activity
    DisposableEffect(settings.keepScreenOn, state.active) {
        val on = settings.keepScreenOn && state.active
        if (on) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        asked++
        if (res[android.Manifest.permission.RECORD_AUDIO] == true) {
            micDenied = false
            Player.stop()
            RecorderService.send(context, RecorderService.ACTION_START)
        } else {
            micDenied = true
        }
    }

    fun record() {
        if (context.hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            Player.stop()
            RecorderService.send(context, RecorderService.ACTION_START)
        } else {
            val blocked = asked > 0 && activity != null && !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)
            if (blocked) {
                openAppSettings(context)
            } else {
                AppLock.expectReturn()
                permission.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (micDenied) {
            WarningCard(stringResource(R.string.mic_denied), Icons.Outlined.MicOff, Modifier.clickable { openAppSettings(context) })
        }
        CtrlHeroCard(
            label = stringResource(
                when (state.status) {
                    Status.RECORDING -> R.string.status_recording
                    Status.PAUSED -> R.string.status_paused
                    Status.SAVING -> R.string.status_saving
                    Status.IDLE -> R.string.status_ready
                },
            ),
            meta = if (showQuality) {
                stringResource(settings.quality.label) + (if (settings.stereo) " · " + stringResource(R.string.stereo_short) else "")
            } else null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                formatDuration(state.elapsed(now), tenths = true),
                style = CtrlType.Hero.copy(fontSize = 58.sp),
                color = CtrlColors.OnAccent,
                maxLines = 1,
            )
            Levels(state.levels, Modifier.fillMaxWidth().height(64.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state.status) {
                Status.IDLE -> BigButton(stringResource(R.string.record), Icons.Outlined.Mic, accent = true, Modifier.weight(1f)) { record() }
                Status.RECORDING, Status.PAUSED -> {
                    SquareAction(Icons.Outlined.Delete, stringResource(R.string.discard)) { confirmDiscard = true }
                    if (state.status == Status.RECORDING) {
                        BigButton(stringResource(R.string.pause), Icons.Outlined.Pause, accent = false, Modifier.weight(1f)) { RecorderService.send(context, RecorderService.ACTION_PAUSE) }
                    } else {
                        BigButton(stringResource(R.string.resume), Icons.Outlined.PlayArrow, accent = false, Modifier.weight(1f)) { RecorderService.send(context, RecorderService.ACTION_RESUME) }
                    }
                    BigButton(stringResource(R.string.stop_save), Icons.Outlined.Stop, accent = true, Modifier.weight(1f)) { RecorderService.send(context, RecorderService.ACTION_STOP) }
                }
                Status.SAVING -> BigButton(stringResource(R.string.status_saving), Icons.Outlined.Stop, accent = false, Modifier.weight(1f), enabled = false) {}
            }
        }
        if (state.status == Status.IDLE) {
            Text(stringResource(R.string.record_hint), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.discard_title),
            body = stringResource(R.string.discard_body),
            confirm = stringResource(R.string.discard),
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                RecorderService.send(context, RecorderService.ACTION_DISCARD)
            },
        )
    }
}

/** Loudness bars, newest on the right. */
@Composable
private fun Levels(levels: List<Float>, modifier: Modifier) {
    val bar = CtrlColors.OnAccent
    Canvas(modifier) {
        val n = RecorderService.LEVELS
        val w = size.width / n
        val shown = levels.takeLast(n)
        val offset = n - shown.size
        shown.forEachIndexed { i, l ->
            val h = (size.height * (0.06f + 0.94f * l)).coerceAtMost(size.height)
            drawRoundRect(
                color = bar.copy(alpha = 0.35f + 0.65f * l),
                topLeft = Offset((offset + i) * w + w * 0.2f, (size.height - h) / 2),
                size = Size(w * 0.6f, h),
                cornerRadius = CornerRadius(w * 0.3f),
            )
        }
    }
}

@Composable
private fun BigButton(label: String, icon: ImageVector, accent: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (accent) CtrlColors.Accent else CtrlColors.Surface
    val fg = when {
        !enabled -> CtrlColors.Inactive
        accent -> CtrlColors.OnAccent
        else -> CtrlColors.Text
    }
    Row(
        modifier.height(64.dp).clip(CtrlShapes.Button).background(bg)
            .then(if (accent) Modifier else Modifier.border(1.dp, CtrlColors.ControlOutline, CtrlShapes.Button))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1)
    }
}

@Composable
private fun SquareAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CtrlShapes.Button).background(CtrlColors.Surface).border(1.dp, CtrlColors.ControlOutline, CtrlShapes.Button)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(24.dp), tint = CtrlColors.Error) }
}
