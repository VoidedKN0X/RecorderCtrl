package com.vknxworks.recorderctrl.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.data.Recording
import com.vknxworks.recorderctrl.data.Recordings
import com.vknxworks.recorderctrl.ui.lock.AppLock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** "4:05" or "1:02:03". */
fun formatDuration(ms: Long, tenths: Boolean = false): String {
    val total = ms.coerceAtLeast(0)
    val s = total / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    return if (tenths) base + ".%d".format((total % 1000) / 100) else base
}

fun sizeText(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** "Today 14:05", "Yesterday 09:12", "3 Nov 14:05", "3 Nov 2025". */
fun whenText(context: Context, millis: Long): String {
    val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault()))
    return when (t.toLocalDate()) {
        today -> context.getString(R.string.today_at, time)
        today.minusDays(1) -> context.getString(R.string.yesterday_at, time)
        else -> if (t.year == today.year) t.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) + " $time"
        else t.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }
}

fun openAppSettings(context: Context) {
    AppLock.expectReturn()
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Starts another app; that trip doesn't lock RecorderCtrl. False when no app can do it. */
fun Context.launch(intent: Intent): Boolean = try {
    AppLock.expectReturn()
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** A read-only link to one recording for another app, named after the recording. */
fun shareUri(context: Context, r: Recording, file: File): Uri {
    val ext = r.file.substringAfterLast('.', "m4a")
    return FileProvider.getUriForFile(context, context.packageName + ".files", file, Recordings.safeName(r.name) + "." + ext)
}

/** Android's share sheet with the recording; the receiving app gets read access to that one file only. */
fun share(context: Context, r: Recording, file: File): Boolean {
    val uri = shareUri(context, r, file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(r.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(r.name, uri)
    return context.launch(Intent.createChooser(send, context.getString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
