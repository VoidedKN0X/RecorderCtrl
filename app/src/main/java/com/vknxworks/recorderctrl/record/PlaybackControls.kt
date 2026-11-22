package com.vknxworks.recorderctrl.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.vknxworks.recorderctrl.MainActivity
import com.vknxworks.recorderctrl.R
import com.vknxworks.recorderctrl.container
import com.vknxworks.recorderctrl.ui.lock.AppLock

/**
 * Playback controls in the notification (and in Android's media controls, through a media session): play or
 * pause, 10 seconds back or forward, and stop by swiping it away. Shown while a recording is loaded in the player.
 * While the app lock is locked, these controls can pause and stop but never start playback.
 */
object PlaybackControls {
    private const val CHANNEL = "playback"
    private const val NOTIFICATION_ID = 4
    const val ACTION_TOGGLE = "com.vknxworks.recorderctrl.PLAYBACK_TOGGLE"
    const val ACTION_BACK = "com.vknxworks.recorderctrl.PLAYBACK_BACK"
    const val ACTION_FORWARD = "com.vknxworks.recorderctrl.PLAYBACK_FORWARD"
    const val ACTION_STOP = "com.vknxworks.recorderctrl.PLAYBACK_STOP"
    private const val SKIP_MS = 10_000L

    private var session: MediaSession? = null

    /** Whether playback may start from outside the app's screens (not while the app lock is locked). */
    fun mayPlay(context: Context): Boolean = !context.container.vault.enabled || AppLock.unlocked

    fun handle(context: Context, action: String?) {
        when (action) {
            ACTION_TOGGLE -> if (Player.state.value.playing) Player.pause() else if (mayPlay(context)) Player.resumeLoaded(context)
            ACTION_BACK -> Player.skip(-SKIP_MS)
            ACTION_FORWARD -> Player.skip(SKIP_MS)
            ACTION_STOP -> Player.stop()
        }
    }

    /** Shows (or updates) the controls for the loaded recording, or removes them when nothing is loaded. */
    fun publish(context: Context, st: PlayState, title: String) {
        val app = context.applicationContext
        if (st.id == null) {
            clear(app)
            return
        }
        val s = session ?: runCatching {
            MediaSession(app, "RecorderCtrl").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = handle(app, if (Player.state.value.playing) null else ACTION_TOGGLE)
                    override fun onPause() = Player.pause()
                    override fun onStop() = Player.stop()
                    override fun onSeekTo(pos: Long) = Player.seek(pos)
                    override fun onRewind() = Player.skip(-SKIP_MS)
                    override fun onFastForward() = Player.skip(SKIP_MS)
                    override fun onCustomAction(action: String, extras: android.os.Bundle?) = handle(app, action)
                })
            }
        }.getOrNull()?.also { session = it } ?: return
        runCatching {
            s.setMetadata(
                MediaMetadata.Builder()
                    // Android's media player on the lock screen (and apps with notification access) read the
                    // session, not the notification's private/public versions: only the generic title goes here.
                    // The recording's name stays in the notification's own (private) title.
                    .putString(MediaMetadata.METADATA_KEY_TITLE, app.getString(R.string.play_notif_public))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, st.durationMs)
                    .build(),
            )
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD,
                    )
                    .setState(if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, st.positionMs, if (st.playing) st.speed else 0f)
                    // Android's media controls show custom actions in the "previous" and "next" places.
                    .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_BACK, app.getString(R.string.play_back_10), R.drawable.ic_play_rewind).build())
                    .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_FORWARD, app.getString(R.string.play_forward_10), R.drawable.ic_play_forward).build())
                    .build(),
            )
            s.isActive = true
        }
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.ch_playback), NotificationManager.IMPORTANCE_LOW).apply {
                    description = app.getString(R.string.ch_playback_desc)
                    setShowBadge(false)
                },
            )
            nm.notify(NOTIFICATION_ID, notification(app, s, st, title))
        }
    }

    private fun notification(app: Context, s: MediaSession, st: PlayState, title: String): Notification {
        fun pi(action: String, req: Int) = PendingIntent.getBroadcast(
            app, req, Intent(app, PlaybackReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun act(icon: Int, label: Int, action: String, req: Int) =
            Notification.Action.Builder(Icon.createWithResource(app, icon), app.getString(label), pi(action, req)).build()
        val open = PendingIntent.getActivity(
            app, 10, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_RECORDING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // On the lock screen only "Playing a recording" shows, not its name.
        val public = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_play_play)
            .setContentTitle(app.getString(R.string.play_notif_public))
            .build()
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_play_play)
            .setContentTitle(title)
            .setContentText(app.getString(if (st.playing) R.string.play_notif_playing else R.string.play_notif_paused))
            .setContentIntent(open)
            .setDeleteIntent(pi(ACTION_STOP, 14))
            .setOngoing(st.playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .addAction(act(R.drawable.ic_play_rewind, R.string.play_back_10, ACTION_BACK, 11))
            .addAction(
                if (st.playing) act(R.drawable.ic_play_pause, R.string.pause, ACTION_TOGGLE, 12)
                else act(R.drawable.ic_play_play, R.string.play, ACTION_TOGGLE, 12),
            )
            .addAction(act(R.drawable.ic_play_forward, R.string.play_forward_10, ACTION_FORWARD, 13))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    fun clear(context: Context) {
        runCatching { context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        session?.let { s ->
            runCatching { s.isActive = false }
            runCatching { s.release() }
        }
        session = null
    }
}

/** The playback notification's buttons. Not exported: only the notification (through RecorderCtrl's own intents) reaches it. */
class PlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaybackControls.handle(context, intent.action)
    }
}
