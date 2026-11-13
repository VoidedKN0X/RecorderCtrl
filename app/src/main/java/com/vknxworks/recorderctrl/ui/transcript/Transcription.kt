package com.vknxworks.recorderctrl.ui.transcript

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.data.Recording
import com.vknxworks.recorderctrl.transcribe.ImportResult
import com.vknxworks.recorderctrl.transcribe.ModelInfo
import com.vknxworks.recorderctrl.transcribe.ModelProblem
import com.vknxworks.recorderctrl.transcribe.ModelSize
import com.vknxworks.recorderctrl.transcribe.ModelStore
import com.vknxworks.recorderctrl.transcribe.TranscribeService
import com.vknxworks.recorderctrl.transcribe.WhisperLib
import com.vknxworks.recorderctrl.ui.common.launch
import com.vknxworks.recorderctrl.ui.common.sizeText
import com.vknxworks.recorderctrl.ui.components.ChoiceSheet
import com.vknxworks.recorderctrl.ui.components.ConfirmDialog
import com.vknxworks.recorderctrl.ui.components.CtrlAlertDialog
import com.vknxworks.recorderctrl.ui.components.CtrlProgressBar
import com.vknxworks.recorderctrl.ui.components.Group
import com.vknxworks.recorderctrl.ui.components.GroupRow
import com.vknxworks.recorderctrl.ui.components.PickerField
import com.vknxworks.recorderctrl.ui.lock.AppLock
import com.vknxworks.recorderctrl.ui.theme.CtrlColors
import kotlinx.coroutines.launch
import java.util.Locale

/** "Base · Multilingual · q5_1". */
fun modelLabel(context: Context, m: ModelInfo): String {
    val size = context.getString(
        when (m.header.size) {
            ModelSize.TINY -> R.string.model_tiny
            ModelSize.BASE -> R.string.model_base
            ModelSize.SMALL -> R.string.model_small
            ModelSize.MEDIUM -> R.string.model_medium
            ModelSize.LARGE -> R.string.model_large
            ModelSize.TURBO -> R.string.model_turbo
            ModelSize.OTHER -> R.string.model_other
        },
    )
    val lang = context.getString(if (m.header.multilingual) R.string.model_multilingual else R.string.model_english)
    return listOf(size, lang, m.header.format).joinToString(" · ")
}

/** A whisper language code as a name in the phone's language ("nl" → "Dutch"); "auto" → "Detect automatically". */
fun languageName(context: Context, code: String): String {
    if (code.isBlank() || code == "auto") return context.getString(R.string.language_auto)
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
    return if (name.isBlank() || name == code) code else name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** The language choices: automatic first, then every language whisper knows, by name. */
fun languageOptions(context: Context): List<Pair<String, String>> =
    listOf("auto" to context.getString(R.string.language_auto)) +
        WhisperLib.languages.map { it to languageName(context, it) }.sortedBy { it.second.lowercase() }

private fun importError(context: Context, r: ImportResult): String? = when (r) {
    is ImportResult.Ok -> null
    ImportResult.NoSpace -> context.getString(R.string.model_error_space)
    ImportResult.Failed -> context.getString(R.string.model_error_failed)
    is ImportResult.Bad -> context.getString(
        when (r.problem) {
            ModelProblem.TOO_SMALL -> R.string.model_error_small
            ModelProblem.TOO_BIG -> R.string.model_error_big
            ModelProblem.NOT_GGML -> R.string.model_error_not_ggml
            ModelProblem.NOT_WHISPER -> R.string.model_error_not_whisper
        },
    )
}

/** Settings > Speech to text: the model (import, replace, delete, where to get one) and the language. */
@Composable
fun TranscriptionGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val model by c.models.model.collectAsStateWithLifecycle()
    val importing by c.models.importing.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val job by TranscribeService.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    val supported = remember { WhisperLib.supported }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        checking = true
        c.appScope.launch {
            val result = c.models.import(uri)
            checking = false
            val msg = importError(context, result) ?: context.getString(R.string.model_imported, (result as ImportResult.Ok).model.name)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Group(stringResource(R.string.group_transcription), footer = stringResource(if (supported) R.string.transcription_footer else R.string.model_unsupported)) {
        val m = model
        val progress = importing
        GroupRow(
            stringResource(R.string.model),
            when {
                progress != null -> stringResource(R.string.model_importing, (progress * 100).toInt())
                checking -> stringResource(R.string.model_checking)
                m != null -> modelLabel(context, m) + (if (m.header.size == ModelSize.LARGE || m.header.size == ModelSize.MEDIUM) "\n" + stringResource(R.string.model_big_warning) else "")
                else -> stringResource(R.string.model_none)
            },
            icon = Icons.Outlined.Memory,
            value = m?.let { sizeText(context, it.sizeBytes) },
        )
        if (progress != null) {
            Column(Modifier.fillMaxWidth().padding16()) { CtrlProgressBar(progress) }
        }
        if (m != null) {
            Text(m.name, style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.padding16())
        }
        GroupRow(
            stringResource(if (m == null) R.string.model_import else R.string.model_replace),
            stringResource(if (m == null) R.string.model_import_sub else R.string.model_replace_sub),
            icon = Icons.Outlined.FileOpen,
            showChevron = true,
            onClick = {
                if (progress == null && !checking && job == null) {
                    AppLock.expectReturn()
                    runCatching { picker.launch(arrayOf("application/octet-stream", "*/*")) }
                }
            },
        )
        GroupRow(
            stringResource(R.string.model_where),
            stringResource(R.string.model_where_sub),
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
            showChevron = true,
            onClick = { context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(ModelStore.MODELS_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )
        if (supported) {
            GroupRow(
                stringResource(R.string.transcribe_language),
                languageName(context, settings.transcribeLanguage),
                icon = Icons.Outlined.Translate,
                showChevron = true,
                onClick = { pickLanguage = true },
            )
        }
        if (m != null) {
            GroupRow(
                stringResource(R.string.model_delete),
                icon = Icons.Outlined.Delete,
                iconTint = CtrlColors.Error,
                titleColor = CtrlColors.Error,
                onClick = { if (job == null) deleting = true else Toast.makeText(context, context.getString(R.string.transcribe_busy), Toast.LENGTH_SHORT).show() },
            )
        }
    }

    if (pickLanguage) {
        ChoiceSheet(stringResource(R.string.transcribe_language), remember { languageOptions(context) }, settings.transcribeLanguage, onDismiss = { pickLanguage = false }) { code ->
            pickLanguage = false
            c.appScope.launch { c.settings.update { it.copy(transcribeLanguage = code) } }
        }
    }
    val m = model
    if (deleting && m != null) {
        ConfirmDialog(
            title = stringResource(R.string.model_delete_title),
            body = stringResource(R.string.model_delete_body, m.name, sizeText(context, m.sizeBytes)),
            confirm = stringResource(R.string.delete),
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                scope.launch { c.models.delete() }
            },
        )
    }
}

/** Lines up with the text of the row above (after its icon). */
private fun Modifier.padding16() = padding(start = 52.dp, end = 16.dp, bottom = 10.dp)

/**
 * Asks before transcribing [r]: which model runs, and the language (automatic or one language). Starts the
 * foreground service when confirmed.
 */
@Composable
fun TranscribeDialog(r: Recording, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val model by c.models.model.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(settings.transcribeLanguage) }
    var picking by remember { mutableStateOf(false) }
    val m = model
    val multilingual = m?.header?.multilingual == true
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcribe_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.transcribe_body, m?.let { modelLabel(context, it) } ?: "-"), style = MaterialTheme.typography.bodyMedium)
                if (multilingual) {
                    PickerField(stringResource(R.string.transcribe_language), languageName(context, language), onClick = { picking = true }, icon = Icons.Outlined.Language)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (language != settings.transcribeLanguage) c.appScope.launch { c.settings.update { it.copy(transcribeLanguage = language) } }
                TranscribeService.start(context, r, if (multilingual) language else "en")
            }, enabled = m != null) { Text(stringResource(R.string.transcribe)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
    if (picking) {
        ChoiceSheet(stringResource(R.string.transcribe_language), remember { languageOptions(context) }, language, onDismiss = { picking = false }) {
            language = it
            picking = false
        }
    }
}

/** Shows how the last transcription ended (once), as a short message. */
@Composable
fun TranscribeOutcomeToast() {
    val context = LocalContext.current
    val outcome by TranscribeService.outcome.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(outcome) {
        val o = outcome ?: return@LaunchedEffect
        TranscribeService.outcome.value = null
        val msg = when (o) {
            is com.vknxworks.recorderctrl.transcribe.TranscribeOutcome.Done -> context.getString(R.string.transcribe_done)
            is com.vknxworks.recorderctrl.transcribe.TranscribeOutcome.Cancelled -> context.getString(R.string.transcribe_cancelled)
            is com.vknxworks.recorderctrl.transcribe.TranscribeOutcome.Failed -> context.getString(TranscribeService.errorText(o.reason))
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
