package com.vknxworks.recorderctrl.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vknxworks.recorderctrl.BuildConfig
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.data.settings.BackupFormatException
import com.vknxworks.recorderctrl.data.settings.SettingsBackups
import com.vknxworks.recorderctrl.data.settings.RecorderBackup
import com.vknxworks.recorderctrl.ui.components.ConfirmDialog
import com.vknxworks.recorderctrl.ui.components.Group
import com.vknxworks.recorderctrl.ui.components.GroupRow
import com.vknxworks.recorderctrl.ui.lock.AppLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Export and import: RecorderCtrl's recording and speech-to-text settings as a JSON file in a place you pick. No
 * recordings, transcripts, model, PIN or privacy settings are in it, and an import never changes those.
 */
@Composable
fun BackupGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<RecorderBackup?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = try {
                val text = SettingsBackups.export(c.settings.settings.value, BuildConfig.VERSION_NAME, OffsetDateTime.now().toString())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IOException()
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            Toast.makeText(context, if (ok) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > SettingsBackups.MAX_BYTES) throw BackupFormatException(newer = false)
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: throw IOException()
                }
                pending = withContext(Dispatchers.Default) { SettingsBackups.parse(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupFormatException) {
                Toast.makeText(context, if (e.newer) R.string.backup_too_new else R.string.backup_not_valid, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(context, R.string.backup_read_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    Group(stringResource(R.string.backup_title), footer = stringResource(R.string.backup_footer)) {
        GroupRow(
            stringResource(R.string.backup_save),
            stringResource(R.string.backup_save_sub),
            Icons.Outlined.FileDownload,
            onClick = { AppLock.expectReturn(); save.launch("recorderctrl-settings-${LocalDate.now()}.json") },
        )
        GroupRow(
            stringResource(R.string.backup_restore),
            stringResource(R.string.backup_restore_sub),
            Icons.Outlined.FileUpload,
            onClick = { AppLock.expectReturn(); open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        )
    }

    pending?.let { b ->
        val result = remember(b) { SettingsBackups.apply(b, c.settings.settings.value) }
        val summary = listOf(
            stringResource(R.string.quality) + ": " + stringResource(result.quality.label) + (if (result.stereo) " · " + stringResource(R.string.stereo_short) else ""),
            stringResource(R.string.mic_processing) + ": " + stringResource(result.source.label),
            stringResource(R.string.mic_choice) + ": " + stringResource(result.mic.label),
        ).joinToString("\n")
        ConfirmDialog(
            stringResource(R.string.backup_restore_title),
            stringResource(R.string.backup_restore_body, b.createdAt?.take(10) ?: "?", summary),
            stringResource(R.string.backup_restore_confirm),
            onDismiss = { pending = null },
        ) {
            pending = null
            scope.launch {
                // Applied to the settings as they are at that moment, so a change made meanwhile isn't lost.
                var auto = false
                c.settings.update { SettingsBackups.apply(b, it).also { s -> auto = s.autoTranscribe } }
                if (auto) com.vknxworks.recorderctrl.transcribe.AutoTranscribe.schedule(context, on = true)
                else com.vknxworks.recorderctrl.transcribe.AutoTranscribe.cancel(context)
                Toast.makeText(context, R.string.backup_restored, Toast.LENGTH_LONG).show()
            }
        }
    }
}
