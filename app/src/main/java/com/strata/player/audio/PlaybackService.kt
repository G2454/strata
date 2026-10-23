@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.strata.player.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.strata.player.MainActivity
import com.strata.player.R
import com.strata.player.StrataApp
import com.strata.player.widget.StrataWidgets

/** Hosts the media session so playback survives in the background with notification + lock-screen controls. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as StrataApp
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
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(commands)
                        .build()
                }
            })
            .build()
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // The player is shared with the UI and outlives the service; only the session is released.
        session?.release()
        session = null
        super.onDestroy()
    }
}
