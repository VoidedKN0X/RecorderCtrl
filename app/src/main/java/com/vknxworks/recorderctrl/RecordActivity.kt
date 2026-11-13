package com.vknxworks.recorderctrl

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import com.vknxworks.recorderctrl.data.Recording
import com.vknxworks.recorderctrl.record.RecorderService
import com.vknxworks.recorderctrl.ui.common.shareUri
import com.vknxworks.recorderctrl.ui.components.CtrlSquareButton
import com.vknxworks.recorderctrl.ui.components.TabTitle
import com.vknxworks.recorderctrl.ui.nav.LocalNavigator
import com.vknxworks.recorderctrl.ui.nav.Navigator
import com.vknxworks.recorderctrl.ui.record.RecordPanel

/**
 * "Record audio" for other apps (MediaStore.Audio.Media.RECORD_SOUND_ACTION): records, then returns the
 * recording to the app that asked with read access to that one file. The recording is also kept in RecorderCtrl.
 */
class RecordActivity : ComponentActivity() {

    /**
     * The file of a recording that was already running (started in RecorderCtrl itself) when this screen opened.
     * It isn't this screen's to throw away or hand to the other app: Cancel and Back leave it recording.
     */
    private var foreignPart: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        foreignPart = if (savedInstanceState != null) savedInstanceState.getString(KEY_FOREIGN)
        else RecorderService.state.value.takeIf { it.active }?.part?.name
        enableEdgeToEdge()
        setContent {
            CtrlWindow(this) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    val navigator = remember(navController) { CloseNavigator(navController) { cancel() } }
                    androidx.activity.compose.BackHandler { cancel() }
                    CompositionLocalProvider(LocalNavigator provides navigator) {
                        Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            TabTitle(
                                stringResource(R.string.record_for_app),
                                showProfile = false,
                                trailing = { CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.cancel), onClick = ::cancel) },
                            )
                            RecordPanel(onSaved = ::done, showQuality = true)
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FOREIGN, foreignPart)
    }

    /** A recording is running, and it was started from this screen (not one that was already running). */
    private fun ownRecordingActive(): Boolean {
        val s = RecorderService.state.value
        return s.active && s.part != null && s.part.name != foreignPart
    }

    private fun cancel() {
        // Never lose a recording: only discard one this screen started.
        if (ownRecordingActive()) RecorderService.send(this, RecorderService.ACTION_DISCARD)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun done(r: Recording) {
        // The recording that was already running was saved in RecorderCtrl; it isn't handed to the other app.
        if (foreignPart != null && r.file == foreignPart!!.removeSuffix(".part")) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val uri = runCatching { shareUri(this, r, container.recordings.file(r)) }.getOrNull()
        if (uri == null) {
            setResult(RESULT_CANCELED)
        } else {
            val data = Intent().setDataAndType(uri, r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            data.clipData = ClipData.newRawUri(r.name, uri)
            setResult(RESULT_OK, data)
        }
        finish()
    }
}

private const val KEY_FOREIGN = "foreign_part"

private class CloseNavigator(nav: androidx.navigation.NavHostController, private val onExit: () -> Unit) : Navigator(nav) {
    override fun back() = onExit()
}
