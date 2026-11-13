package com.vknxworks.recorderctrl.ui.transcript

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.data.Recording
import com.vknxworks.recorderctrl.data.Recordings
import com.vknxworks.recorderctrl.record.Player
import com.vknxworks.recorderctrl.record.RecorderService
import com.vknxworks.recorderctrl.transcribe.Segment
import com.vknxworks.recorderctrl.transcribe.TranscribeService
import com.vknxworks.recorderctrl.transcribe.Transcript
import com.vknxworks.recorderctrl.transcribe.TranscriptWriter
import com.vknxworks.recorderctrl.ui.common.formatDuration
import com.vknxworks.recorderctrl.ui.common.launch
import com.vknxworks.recorderctrl.ui.components.ConfirmDialog
import com.vknxworks.recorderctrl.ui.components.CtrlBottomSheet
import com.vknxworks.recorderctrl.ui.components.CtrlProgressBar
import com.vknxworks.recorderctrl.ui.components.CtrlSquareButton
import com.vknxworks.recorderctrl.ui.components.GroupRow
import com.vknxworks.recorderctrl.ui.components.MessageView
import com.vknxworks.recorderctrl.ui.components.ScreenTopBar
import com.vknxworks.recorderctrl.ui.components.SectionLabel
import com.vknxworks.recorderctrl.ui.lock.AppLock
import com.vknxworks.recorderctrl.ui.nav.LocalNavigator
import com.vknxworks.recorderctrl.ui.theme.CtrlColors
import com.vknxworks.recorderctrl.ui.theme.CtrlShapes
import com.vknxworks.recorderctrl.ui.theme.CtrlType
import com.vknxworks.recorderctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A recording's transcript: a player at the top, then every line with its time. Tapping a line plays the recording
 * from there; the line being played is highlighted. Export saves it as .txt or .srt through the system's file picker.
 */
@Composable
fun TranscriptScreen(recordingId: Long) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val list by c.recordings.list.collectAsStateWithLifecycle()
    val r = list.firstOrNull { it.id == recordingId }
    val index by c.transcripts.search.collectAsStateWithLifecycle()
    val job by TranscribeService.state.collectAsStateWithLifecycle()
    val play by Player.state.collectAsStateWithLifecycle()
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    TranscribeOutcomeToast()

    // Reloads when the transcript changes (made again, or deleted).
    val transcript by produceState<Transcript?>(null, r?.file, index[r?.file]) {
        value = r?.let { c.transcripts.get(it) }
    }

    fun saved(ok: Boolean) = Toast.makeText(context, context.getString(if (ok) R.string.export_saved else R.string.export_failed), Toast.LENGTH_SHORT).show()
    fun writer(text: () -> String) = { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text().toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
            }
            saved(ok)
        }
        Unit
    }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), writer { TranscriptWriter.toTxt(transcript?.segments.orEmpty()) })
    val srtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip"), writer { TranscriptWriter.toSrt(transcript?.segments.orEmpty()) })

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(r?.name ?: stringResource(R.string.transcript)) {
            if (transcript != null) CtrlSquareButton(Icons.Outlined.IosShare, stringResource(R.string.export), onClick = { menu = true })
        }
        if (r == null) {
            MessageView(Icons.Outlined.SubtitlesOff, stringResource(R.string.transcript_missing))
            return@Column
        }
        val running = job?.takeIf { it.recordingId == r.id }
        PlayerBar(r, playing = play.id == r.id && play.playing, position = if (play.id == r.id) play.positionMs else 0L, canPlay = !rec.active)
        if (running != null) {
            RunningCard(running.progress, Modifier.padding(horizontal = Gutter, vertical = 6.dp))
        }
        val t = transcript
        when {
            t == null && running == null -> MessageView(
                Icons.Outlined.Subtitles, stringResource(R.string.transcript_missing),
                actionLabel = stringResource(R.string.transcribe),
                onAction = { startTranscribe(context, r) { transcribing = true } },
            )
            t == null -> Unit
            t.segments.isEmpty() -> MessageView(Icons.Outlined.SubtitlesOff, stringResource(R.string.transcript_empty), stringResource(R.string.transcript_empty_body))
            else -> Lines(r, t, playingId = play.id, position = play.positionMs, canPlay = !rec.active)
        }
    }

    if (menu && r != null) {
        CtrlBottomSheet(onDismissRequest = { menu = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                val base = Recordings.safeName(r.name)
                GroupRow(stringResource(R.string.export_txt), icon = Icons.Outlined.Description, onClick = {
                    menu = false
                    AppLock.expectReturn()
                    runCatching { txtLauncher.launch("$base.txt") }
                })
                GroupRow(stringResource(R.string.export_srt), icon = Icons.Outlined.Subtitles, onClick = {
                    menu = false
                    AppLock.expectReturn()
                    runCatching { srtLauncher.launch("$base.srt") }
                })
                GroupRow(stringResource(R.string.share_text), icon = Icons.Outlined.Share, onClick = {
                    menu = false
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, r.name)
                        .putExtra(Intent.EXTRA_TEXT, TranscriptWriter.toTxt(transcript?.segments.orEmpty()))
                    if (!context.launch(Intent.createChooser(send, context.getString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                        Toast.makeText(context, context.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
                    }
                })
                GroupRow(stringResource(R.string.transcript_redo), icon = Icons.Outlined.Refresh, onClick = {
                    menu = false
                    startTranscribe(context, r) { transcribing = true }
                })
                GroupRow(stringResource(R.string.transcript_delete), icon = Icons.Outlined.Delete, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = {
                    menu = false
                    deleting = true
                })
            }
        }
    }
    if (deleting && r != null) {
        ConfirmDialog(
            title = stringResource(R.string.transcript_delete_title),
            body = stringResource(R.string.transcript_delete_body, r.name),
            confirm = stringResource(R.string.delete),
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                scope.launch {
                    c.transcripts.delete(r.file)
                    nav.back()
                }
            },
        )
    }
    if (transcribing && r != null) TranscribeDialog(r) { transcribing = false }
}

/** Opens the Transcribe dialog, or says why it can't (no model, or another recording is being transcribed). */
fun startTranscribe(context: android.content.Context, r: Recording, open: () -> Unit) {
    val c = context.container
    when {
        TranscribeService.state.value != null ->
            Toast.makeText(context, context.getString(R.string.transcribe_busy), Toast.LENGTH_LONG).show()
        !com.vknxworks.recorderctrl.transcribe.WhisperLib.supported ->
            Toast.makeText(context, context.getString(R.string.transcribe_error_unsupported), Toast.LENGTH_LONG).show()
        c.models.model.value == null ->
            Toast.makeText(context, context.getString(R.string.transcribe_need_model), Toast.LENGTH_LONG).show()
        else -> open()
    }
}

/** "Transcribing 42%" with a bar and Cancel. */
@Composable
fun RunningCard(progress: Float?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxWidth().clip(CtrlShapes.Card).background(CtrlColors.Raised).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (progress == null) stringResource(R.string.transcribe_loading_model)
                else stringResource(R.string.transcribing) + " · " + stringResource(R.string.transcribe_percent, (progress * 100).toInt()),
                style = CtrlType.LabelStrong, color = CtrlColors.Text, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.clip(CtrlShapes.Chip).border(1.dp, CtrlColors.ControlOutline, CtrlShapes.Chip)
                    .clickable(role = Role.Button) { TranscribeService.cancel(context) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(stringResource(R.string.cancel), style = CtrlType.LabelStrong, color = CtrlColors.Text) }
        }
        CtrlProgressBar(progress ?: 0f, color = CtrlColors.Accent)
    }
}

@Composable
private fun PlayerBar(r: Recording, playing: Boolean, position: Long, canPlay: Boolean) {
    val context = LocalContext.current
    val c = context.container
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = r.durationMs.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CtrlShapes.Control).background(if (playing) CtrlColors.Accent else CtrlColors.Raised)
                .clickable(enabled = canPlay, role = Role.Button) { Player.toggle(context, r, c.recordings.file(r)) },
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
            Slider(
                value = if (dragging) dragValue else (position.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    if (canPlay) Player.playFrom(context, r, c.recordings.file(r), (dragValue * dur).toLong())
                    dragging = false
                },
                colors = SliderDefaults.colors(thumbColor = CtrlColors.Accent, activeTrackColor = CtrlColors.Accent, inactiveTrackColor = CtrlColors.Raised),
            )
            Text(
                formatDuration(if (dragging) (dragValue * dur).toLong() else position) + " / " + formatDuration(r.durationMs),
                style = CtrlType.Data, color = CtrlColors.Muted,
            )
        }
    }
}

@Composable
private fun Lines(r: Recording, t: Transcript, playingId: Long?, position: Long, canPlay: Boolean) {
    val context = LocalContext.current
    val c = context.container
    val state = rememberLazyListState()
    val active = if (playingId == r.id) t.segments.indexOfLast { it.startMs <= position } else -1
    val playing by Player.state.collectAsStateWithLifecycle()
    // Follow the playback while it's running, unless the line is already on screen.
    LaunchedEffect(active, playing.playing) {
        if (active < 0 || !playing.playing || playing.id != r.id) return@LaunchedEffect
        val item = active + 1 // the header is item 0
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        if (item !in visible.drop(1).dropLast(1)) state.animateScrollToItem((item - 1).coerceAtLeast(0))
    }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            val meta = listOfNotNull(
                t.language.takeIf { it.isNotBlank() }?.let { stringResource(R.string.transcript_language, languageName(context, it)) },
                t.model.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                if (meta.isNotEmpty()) SectionLabel(meta)
                Text(stringResource(R.string.transcript_hint), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
            }
        }
        itemsIndexed(t.segments, key = { i, _ -> i }) { i, s ->
            Line(s, current = i == active, enabled = canPlay) { Player.playFrom(context, r, c.recordings.file(r), s.startMs) }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun Line(s: Segment, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (current) CtrlColors.Raised else CtrlColors.Background)
            .padding(horizontal = Gutter, vertical = 8.dp),
    ) {
        Text(
            formatDuration(s.startMs),
            style = CtrlType.Data,
            color = if (current) CtrlColors.AccentText else CtrlColors.Muted,
            modifier = Modifier.widthIn(min = 56.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(s.text, style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text, modifier = Modifier.weight(1f))
    }
}
