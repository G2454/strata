@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.strata.player.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.strata.player.MainActivity
import com.strata.player.R
import com.strata.player.StrataApp
import com.strata.player.widget.StrataWidgets

/** Hosts the media session so playback survives in the background with notification + lock-screen controls. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var shownFav: Boolean? = null

    /** The heart next to the transport buttons in the notification and on the lock screen. */
    private fun favButton(fav: Boolean): CommandButton = CommandButton.Builder()
        .setDisplayName(if (fav) "Remove from favorites" else "Add to favorites")
        .setIconResId(if (fav) R.drawable.ic_w_heart_fill else R.drawable.ic_w_heart)
        .setSessionCommand(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
        .build()

    override fun onCreate() {
        super.onCreate()
        val app = application as StrataApp
        val model = app.model
        val fav0 = model.engine.currentId >= 0 && model.isFav(model.engine.currentId)
        shownFav = fav0
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, app.model.engine.player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Controllers (notification, lock screen, Bluetooth, the app itself) only see the current
                // item, not the whole playlist. Shipping a several-thousand-item timeline to every controller
                // on each change is what makes big libraries feel slow.
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_GET_TIMELINE)
                        .build()
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(commands)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CMD_FAVORITE) {
                        val id = model.engine.currentId
                        if (id >= 0) model.toggleFav(id)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
        session?.setCustomLayout(listOf(favButton(fav0)))
        // Keep the heart in the notification in step with the app (toggled here, in the app or on a widget).
        model.onNowPlayingChanged = { _, fav ->
            if (fav != shownFav) {
                shownFav = fav
                session?.setCustomLayout(listOf(favButton(fav)))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Widget buttons that start playback arrive here (the system lets a widget tap start a foreground service).
     * A placeholder notification satisfies the foreground deadline right away; Media3 replaces it with the
     * real media notification (same id and channel) as soon as playback starts.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == StrataWidgets.ACTION_PLAY || action == StrataWidgets.ACTION_PLAY_FROM) {
            goForeground()
            val app = application as StrataApp
            app.model.startFromWidget(action, intent.getStringExtra(StrataWidgets.EXTRA_KEY)) { ok ->
                if (!ok) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    app.model.flash("Open Strata once to load your library")
                    stopSelf()
                } else {
                    // If playback never got going (unreadable file), don't leave the placeholder stuck in the foreground.
                    android.os.Handler(mainLooper).postDelayed({
                        if (!app.model.engine.isPlaying) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                    }, 10_000)
                }
            }
            super.onStartCommand(intent, flags, startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(NotificationChannel(channel, "Now playing", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_strata)
            .setContentTitle("Strata")
            .setContentText("Starting playback…")
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, n, type)
    }

    /** Swiping Strata away from recent apps stops the music, unless "Keep playing after closing" is on. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val keep = (application as StrataApp).model.onAppClosed()
        val p = session?.player
        if (!keep || p == null || p.mediaItemCount == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // The player is shared with the UI and outlives the service; only the session is released.
        (application as StrataApp).model.onNowPlayingChanged = null
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val CMD_FAVORITE = "com.strata.player.FAVORITE"
    }
}
