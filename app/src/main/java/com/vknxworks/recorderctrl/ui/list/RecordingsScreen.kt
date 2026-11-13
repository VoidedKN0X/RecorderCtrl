package com.vknxworks.recorderctrl.ui.list

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.data.Recording
import com.vknxworks.recorderctrl.record.Player
import com.vknxworks.recorderctrl.record.RecorderService
import com.vknxworks.recorderctrl.ui.common.formatDuration
import com.vknxworks.recorderctrl.ui.common.share
import com.vknxworks.recorderctrl.ui.common.sizeText
import com.vknxworks.recorderctrl.ui.common.whenText
import com.vknxworks.recorderctrl.ui.components.ConfirmDialog
import com.vknxworks.recorderctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.recorderctrl.ui.components.CtrlSquareButton
import com.vknxworks.recorderctrl.ui.components.CtrlTextField
import com.vknxworks.recorderctrl.ui.components.MessageView
import com.vknxworks.recorderctrl.ui.components.NameDialog
import com.vknxworks.recorderctrl.ui.components.TabTitle
import com.vknxworks.recorderctrl.ui.components.bottomClearance
import com.vknxworks.recorderctrl.ui.theme.CtrlColors
import com.vknxworks.recorderctrl.ui.theme.CtrlShapes
import com.vknxworks.recorderctrl.ui.theme.CtrlType
import com.vknxworks.recorderctrl.ui.theme.Gutter
import com.vknxworks.recorderctrl.transcribe.TranscribeJob
import com.vknxworks.recorderctrl.transcribe.TranscribeService
import com.vknxworks.recorderctrl.ui.nav.LocalNavigator
import com.vknxworks.recorderctrl.ui.nav.TranscriptRoute
import com.vknxworks.recorderctrl.ui.transcript.RunningCard
import com.vknxworks.recorderctrl.ui.transcript.TranscribeDialog
import com.vknxworks.recorderctrl.ui.transcript.TranscribeOutcomeToast
import com.vknxworks.recorderctrl.ui.transcript.startTranscribe
import kotlinx.coroutines.launch

@Composable
fun RecordingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val list by c.recordings.list.collectAsStateWithLifecycle()
    val play by Player.state.collectAsStateWithLifecycle()
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    // A recording that was just saved opens, ready to play.
    val justSaved by c.justSaved.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(justSaved) {
        justSaved?.let { open = it; c.justSaved.value = null }
    }
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var transcribing by remember { mutableStateOf<Recording?>(null) }
    val texts by c.transcripts.search.collectAsStateWithLifecycle()
    val job by TranscribeService.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    TranscribeOutcomeToast()
    var deleting by remember { mutableStateOf<Recording?>(null) }
    val q = query.trim().lowercase()
    // Search the names and the transcripts.
    val shown = remember(list, q, texts) {
        if (q.isEmpty()) list else list.filter { it.name.lowercase().contains(q) || texts[it.file]?.contains(q, ignoreCase = true) == true }
    }
    val total = list.sumOf { it.durationMs }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TabTitle(
                stringResource(R.string.tab_recordings),
                label = if (list.isEmpty()) null else context.resources.getQuantityString(R.plurals.recordings_count, list.size, list.size) + " · " + formatDuration(total),
            )
        }
        if (list.size > 4 || q.isNotEmpty()) {
            item {
                CtrlTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.clear), onClick = { query = "" }, size = 36.dp, tint = CtrlColors.Muted)
                    }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
                )
            }
        }
        if (list.isEmpty()) {
            item { MessageView(Icons.Outlined.GraphicEq, stringResource(R.string.no_recordings), stringResource(R.string.no_recordings_body), modifier = Modifier.heightIn(min = 320.dp)) }
        } else if (shown.isEmpty()) {
            item { MessageView(Icons.Outlined.SearchOff, stringResource(R.string.no_results), null, modifier = Modifier.heightIn(min = 240.dp)) }
        }
        items(shown, key = { it.id }) { r ->
            RecordingCard(
                r, expanded = open == r.id, playing = play.id == r.id && play.playing,
                hasTranscript = texts.containsKey(r.file),
                snippet = if (q.isNotEmpty() && !r.name.lowercase().contains(q)) texts[r.file]?.let { snippet(it, q) } else null,
                running = job?.takeIf { it.recordingId == r.id },
                onTranscribe = { startTranscribe(context, r) { transcribing = r } },
                onTranscript = { nav.navigate(TranscriptRoute(r.id)) },
                position = if (play.id == r.id) play.positionMs else 0L,
                speed = play.speed, canPlay = !rec.active,
                onToggleOpen = { open = if (open == r.id) null else r.id },
                onPlay = { open = r.id; Player.toggle(context, r, c.recordings.file(r)) },
                onSeek = { ms -> if (play.id == r.id) Player.seek(ms) },
                onSpeed = { Player.setSpeed(it) },
                onShare = { if (!share(context, r, c.recordings.file(r))) Toast.makeText(context, context.getString(R.string.no_app), Toast.LENGTH_SHORT).show() },
                onSave = {
                    scope.launch {
                        val ok = c.recordings.exportCopy(r) != null
                        Toast.makeText(context, context.getString(if (ok) R.string.saved_copy else R.string.save_copy_failed), Toast.LENGTH_SHORT).show()
                    }
                },
                onRename = { renaming = r },
                onDelete = { deleting = r },
                modifier = Modifier.padding(horizontal = Gutter, vertical = 5.dp),
            )
        }
        item { Spacer(Modifier.bottomClearance(0.dp)) }
    }

    transcribing?.let { r -> TranscribeDialog(r) { transcribing = null } }
    renaming?.let { r ->
        NameDialog(stringResource(R.string.rename), r.name, onDismiss = { renaming = null }) { name ->
            c.recordings.rename(r, name)
            renaming = null
        }
    }
    deleting?.let { r ->
        ConfirmDialog(
            title = stringResource(R.string.delete_title),
            body = stringResource(R.string.delete_body, r.name),
            confirm = stringResource(R.string.delete),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                Player.forget(r.id)
                if (job?.recordingId == r.id) TranscribeService.cancel(context)
                scope.launch {
                    c.recordings.delete(r)
                    c.transcripts.delete(r.file)
                }
            },
        )
    }
}

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

/** A few words around the first match of [q] in [text]. */ fun snippet(text: String, q: String, around: Int = 32): String {
    val i = text.indexOf(q, ignoreCase = true)
    if (i < 0) return text.take(around * 2)
    val start = (i - around).coerceAtLeast(0)
    val end = (i + q.length + around).coerceAtMost(text.length)
    return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
}

@Composable
private fun RecordingCard(
    r: Recording, expanded: Boolean, playing: Boolean, position: Long, speed: Float, canPlay: Boolean,
    hasTranscript: Boolean, snippet: String?, running: TranscribeJob?,
    onTranscribe: () -> Unit, onTranscript: () -> Unit,
    onToggleOpen: () -> Unit, onPlay: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit,
    onShare: () -> Unit, onSave: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, modifier: Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxWidth().clip(CtrlShapes.Card).background(CtrlColors.Surface)
            .border(1.dp, if (expanded) CtrlColors.ControlOutline else CtrlColors.Outline, CtrlShapes.Card)
            .clickable(onClick = onToggleOpen).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CtrlShapes.Control).background(if (playing) CtrlColors.Accent else CtrlColors.Raised)
                    .clickable(enabled = canPlay, role = Role.Button, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    stringResource(if (playing) R.string.pause else R.string.play),
                    tint = if (!canPlay) CtrlColors.Inactive else if (playing) CtrlColors.OnAccent else CtrlColors.Text,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, color = CtrlColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(whenText(context, r.createdAt), formatDuration(r.durationMs), sizeText(context, r.sizeBytes)).joinToString(" · "),
                    style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1,
                )
            }
            if (hasTranscript) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.Subtitles, stringResource(R.string.transcript), Modifier.size(18.dp), tint = CtrlColors.Muted)
            }
        }
        if (snippet != null) {
            Text(stringResource(R.string.in_transcript, snippet), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (running != null) RunningCard(running.progress)
        if (expanded) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val dur = r.durationMs.coerceAtLeast(1)
            Slider(
                value = if (dragging) dragValue else (position.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { onSeek((dragValue * dur).toLong()); dragging = false },
                colors = SliderDefaults.colors(thumbColor = CtrlColors.Accent, activeTrackColor = CtrlColors.Accent, inactiveTrackColor = CtrlColors.Raised),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(if (dragging) (dragValue * dur).toLong() else position) + " / " + formatDuration(r.durationMs),
                    style = CtrlType.Data, color = CtrlColors.Muted, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.clip(CtrlShapes.Chip).border(1.dp, CtrlColors.ControlOutline, CtrlShapes.Chip)
                        .clickable { onSpeed(SPEEDS[(SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % SPEEDS.size]) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text(stringResource(R.string.speed_x, speed.toString().removeSuffix(".0")), style = CtrlType.LabelStrong, color = CtrlColors.Text) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CtrlSquareButton(Icons.Outlined.Share, stringResource(R.string.share), onClick = onShare)
                CtrlSquareButton(Icons.Outlined.SaveAlt, stringResource(R.string.save_copy), onClick = onSave)
                CtrlSquareButton(Icons.Outlined.Edit, stringResource(R.string.rename), onClick = onRename)
                Spacer(Modifier.weight(1f))
                CtrlSquareButton(Icons.Outlined.Delete, stringResource(R.string.delete), onClick = onDelete, tint = CtrlColors.Error)
            }
            if (running == null) {
                CtrlSecondaryButton(onClick = if (hasTranscript) onTranscript else onTranscribe, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Subtitles, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (hasTranscript) R.string.transcript else R.string.transcribe), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}
