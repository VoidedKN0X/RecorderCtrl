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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun cancel() {
        if (RecorderService.state.value.active) RecorderService.send(this, RecorderService.ACTION_DISCARD)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun done(r: Recording) {
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

private class CloseNavigator(nav: androidx.navigation.NavHostController, private val onExit: () -> Unit) : Navigator(nav) {
    override fun back() = onExit()
}
