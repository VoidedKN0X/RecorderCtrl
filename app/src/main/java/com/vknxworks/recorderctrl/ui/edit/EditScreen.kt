package com.vknxworks.recorderctrl.ui.edit

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.edit.EditMath
import com.vknxworks.recorderctrl.record.Player
import com.vknxworks.recorderctrl.record.RecorderService
import com.vknxworks.recorderctrl.ui.common.formatDuration
import com.vknxworks.recorderctrl.ui.components.CtrlButton
import com.vknxworks.recorderctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.recorderctrl.ui.components.CtrlSquareButton
import com.vknxworks.recorderctrl.ui.components.FieldNote
import com.vknxworks.recorderctrl.ui.components.MessageView
import com.vknxworks.recorderctrl.ui.components.ScreenTopBar
import com.vknxworks.recorderctrl.ui.nav.LocalNavigator
import com.vknxworks.recorderctrl.ui.theme.CtrlColors
import com.vknxworks.recorderctrl.ui.theme.CtrlType
import com.vknxworks.recorderctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/**
 * Trim and cut: pick a part of a recording, then keep only that part or remove it. The result is saved as a new
 * recording; the original stays as it is (delete it yourself if you don't need it any more).
 */
@Composable
fun EditScreen(id: Long) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val list by c.recordings.list.collectAsStateWithLifecycle()
    val play by Player.state.collectAsStateWithLifecycle()
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    val r = list.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.edit_title))
        if (r == null) {
            MessageView(Icons.Outlined.GraphicEq, stringResource(R.string.edit_missing))
        } else {
            EditBody(r)
        }
    }
}

@Composable
private fun EditBody(r: com.vknxworks.recorderctrl.data.Recording) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val play by Player.state.collectAsStateWithLifecycle()
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    run {
        val dur = r.durationMs.coerceAtLeast(1)
        var start by rememberSaveable(r.id) { mutableLongStateOf(0L) }
        var end by rememberSaveable(r.id) { mutableLongStateOf(dur) }
        var busy by remember { mutableStateOf(false) }
        // The new recording, once saved: this screen closes (only while it's still showing).
        var made by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(made) { if (made != null) nav.back() }
        // Stop the preview at the end of the selection.
        LaunchedEffect(play.positionMs, play.playing) {
            if (play.id == r.id && play.playing && play.positionMs >= end) Player.pause()
        }
        fun save(mode: EditMath.Mode) {
            if (busy) return
            busy = true
            Player.forget(r.id)
            val name = context.getString(if (mode == EditMath.Mode.TRIM) R.string.edit_name_trimmed else R.string.edit_name_cut, r.name)
            // The app's own scope: leaving this screen doesn't stop a save that has started.
            c.appScope.launch {
                val result = c.recordings.edit(r, mode, start, end, name)
                busy = false
                if (result != null) {
                    Toast.makeText(context, R.string.edit_saved, Toast.LENGTH_SHORT).show()
                    c.justSaved.value = result.id
                    made = result.id
                } else {
                    Toast.makeText(context, R.string.edit_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(r.name, style = MaterialTheme.typography.titleMedium, color = CtrlColors.Text)
            Text(stringResource(R.string.edit_selection, formatDuration(start), formatDuration(end), formatDuration(end - start)), style = CtrlType.Data, color = CtrlColors.Muted)
            RangeSlider(
                value = (start.toFloat() / dur)..(end.toFloat() / dur),
                onValueChange = { v ->
                    start = (v.start * dur).toLong().coerceIn(0, dur)
                    end = (v.endInclusive * dur).toLong().coerceIn(start, dur)
                },
                enabled = !busy,
                colors = SliderDefaults.colors(thumbColor = CtrlColors.Accent, activeTrackColor = CtrlColors.Accent, inactiveTrackColor = CtrlColors.Raised),
            )
            // Fine adjustment, a second at a time.
            NudgeRow(stringResource(R.string.edit_start), start, enabled = !busy, onMinus = { start = (start - 1_000).coerceAtLeast(0) }, onPlus = { start = (start + 1_000).coerceAtMost(end) })
            NudgeRow(stringResource(R.string.edit_end), end, enabled = !busy, onMinus = { end = (end - 1_000).coerceAtLeast(start) }, onPlus = { end = (end + 1_000).coerceAtMost(dur) })
            val previewing = play.id == r.id && play.playing
            CtrlSecondaryButton(
                onClick = {
                    if (previewing) Player.pause() else Player.playFrom(context, r, c.recordings.file(r), start)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !rec.active,
            ) {
                Icon(if (previewing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (previewing) R.string.pause else R.string.edit_preview), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            CtrlButton(
                stringResource(R.string.edit_keep),
                onClick = { save(EditMath.Mode.TRIM) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.ContentCut,
                enabled = !busy && !rec.active && EditMath.valid(EditMath.Mode.TRIM, start, end, dur),
            )
            CtrlSecondaryButton(
                onClick = { save(EditMath.Mode.CUT) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !rec.active && EditMath.valid(EditMath.Mode.CUT, start, end, dur),
            ) {
                Icon(Icons.Outlined.Remove, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.edit_remove), style = MaterialTheme.typography.titleSmall)
            }
            FieldNote(stringResource(if (busy) R.string.edit_saving else R.string.edit_note))
        }
    }
}

@Composable
private fun NudgeRow(label: String, value: Long, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text, modifier = Modifier.weight(1f))
        CtrlSquareButton(Icons.Outlined.Remove, stringResource(R.string.edit_back_1s), onClick = onMinus, enabled = enabled)
        Text(formatDuration(value), style = CtrlType.Data, color = CtrlColors.Text)
        CtrlSquareButton(Icons.Outlined.Add, stringResource(R.string.edit_forward_1s), onClick = onPlus, enabled = enabled)
    }
}
