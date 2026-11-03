package com.strata.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import com.strata.player.MainActivity
import com.strata.player.R
import com.strata.player.StrataApp
import com.strata.player.audio.PlaybackService
import java.util.concurrent.Executors

/** What the widgets show. Saved in SharedPreferences so widgets render even when the app isn't running. */
data class WidgetState(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val uri: String,
    val playing: Boolean,
    val favorite: Boolean,
    /** Where playback was at [atElapsed] (SystemClock.elapsedRealtime), so the widget's clock can run by itself. */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val atElapsed: Long = 0L,
    /** The clock is only shown running at normal speed (the launcher ticks it at 1×). */
    val normalSpeed: Boolean = true,
    /** Key of the "Play from" tile that started what's playing, if any. */
    val fromTile: String? = null,
) {
    /** True when the elapsed-time Chronometer can tick on its own: playing, and the stored base still makes sense. */
    fun clockRuns(now: Long): Boolean =
        playing && normalSpeed && atElapsed in 1..now && (durationMs <= 0 || positionMs + (now - atElapsed) < durationMs + 2_000)

    /** Elapsed position right now (frozen when paused). */
    fun positionAt(now: Long): Long = if (clockRuns(now)) positionMs + (now - atElapsed) else positionMs
}

/** Home-screen widgets: an adaptive "Now playing" widget and a "Play from" launcher. */
object StrataWidgets {

    const val ACTION_PLAY = "com.strata.player.widget.PLAY"
    const val ACTION_PAUSE = "com.strata.player.widget.PAUSE"
    const val ACTION_NEXT = "com.strata.player.widget.NEXT"
    const val ACTION_PREV = "com.strata.player.widget.PREV"
    const val ACTION_FAV = "com.strata.player.widget.FAV"
    const val ACTION_PLAY_FROM = "com.strata.player.widget.PLAY_FROM"
    const val EXTRA_KEY = "key"
    const val EXTRA_OPEN_PLAYER = "open_player"

    /** The "Play from" tiles: key, label, subtitle, icon. Keys match AppModel.playFrom. */
    val TILES = listOf(
        Tile("shuffle", "Shuffle all", "Whole library", R.drawable.ic_w_shuffle, R.id.w_tile1, R.id.w_tile1_icon, R.id.w_tile1_title, R.id.w_tile1_sub),
        Tile("fav", "Favorites", "Your hearted tracks", R.drawable.ic_w_heart_fill, R.id.w_tile2, R.id.w_tile2_icon, R.id.w_tile2_title, R.id.w_tile2_sub),
        Tile("added", "Recently added", "Newest files", R.drawable.ic_w_added, R.id.w_tile3, R.id.w_tile3_icon, R.id.w_tile3_title, R.id.w_tile3_sub),
        Tile("most", "Most played", "Your top tracks", R.drawable.ic_w_bars, R.id.w_tile4, R.id.w_tile4_icon, R.id.w_tile4_title, R.id.w_tile4_sub),
    )

    data class Tile(val key: String, val title: String, val sub: String, val icon: Int, val root: Int, val iconId: Int, val titleId: Int, val subId: Int)

    private const val PREFS = "strata_widget"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "strata-widget").apply { isDaemon = true } }
    private var pending: Runnable? = null
    private var artCache: Pair<Long, Bitmap>? = null

    // ------------------------------------------------------------------ state

    fun publish(context: Context, state: WidgetState?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (state == null) {
            p.putBoolean("playing", false)
        } else {
            p.putLong("trackId", state.trackId).putString("title", state.title).putString("artist", state.artist)
                .putString("album", state.album).putLong("albumId", state.albumId).putString("uri", state.uri)
                .putBoolean("playing", state.playing).putBoolean("favorite", state.favorite)
                .putLong("positionMs", state.positionMs).putLong("durationMs", state.durationMs).putLong("atElapsed", state.atElapsed)
                .putBoolean("normalSpeed", state.normalSpeed).putString("fromTile", state.fromTile)
        }
        p.apply()
        scheduleUpdate(context.applicationContext)
    }

    fun readState(context: Context): WidgetState? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getLong("trackId", -1L)
        if (id < 0) return null
        return WidgetState(
            id, p.getString("title", "") ?: "", p.getString("artist", "") ?: "", p.getString("album", "") ?: "",
            p.getLong("albumId", 0L), p.getString("uri", "") ?: "", p.getBoolean("playing", false), p.getBoolean("favorite", false),
            positionMs = p.getLong("positionMs", 0L), durationMs = p.getLong("durationMs", 0L), atElapsed = p.getLong("atElapsed", 0L),
            normalSpeed = p.getBoolean("normalSpeed", true), fromTile = p.getString("fromTile", null),
        )
    }

    /** Coalesces bursts of player events into one widget refresh. */
    private fun scheduleUpdate(context: Context) {
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { updateAll(context) }
        pending = r
        main.postDelayed(r, 150)
    }

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val players = mgr.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
        val launchers = mgr.getAppWidgetIds(ComponentName(context, PlayFromWidgetProvider::class.java))
        if (players.isNotEmpty()) updatePlayers(context, mgr, players)
        if (launchers.isNotEmpty()) {
            val state = liveState(context)
            launchers.forEach { mgr.updateAppWidget(it, buildPlayFrom(context, state)) }
        }
    }

    /**
     * The saved state, corrected against the player in this process: after the app was killed, a widget update
     * starts a fresh process whose player isn't playing, whatever the saved state says.
     */
    fun liveState(context: Context): WidgetState? {
        val s = readState(context) ?: return null
        val playing = (context.applicationContext as? StrataApp)?.model?.engine?.playIntent ?: s.playing
        return if (playing == s.playing) s else s.copy(playing = playing)
    }

    /**
     * Which of the two info slots shows the current track. It flips on every track change, so the launcher
     * crossfades from the old track (still in the other slot) to the new one.
     */
    fun slotFor(context: Context, state: WidgetState?): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = p.getLong("slotTrack", Long.MIN_VALUE)
        var slot = p.getInt("slot", 0)
        val id = state?.trackId ?: -1L
        if (id != shown) {
            slot = if (shown == Long.MIN_VALUE) 0 else 1 - slot
            p.edit().putLong("slotTrack", id).putInt("slot", slot).apply()
        }
        return slot
    }

    fun updatePlayers(context: Context, mgr: AppWidgetManager, ids: IntArray, async: Boolean = true) {
        val state = liveState(context)
        val slot = slotFor(context, state)
        if (!async) {
            val art = art(context, state)
            ids.forEach { id -> mgr.updateAppWidget(id, buildPlayerForWidget(context, mgr, id, state, art, slot)) }
            return
        }
        // Artwork is decoded off the main thread; the widgets are updated back on it.
        worker.execute {
            val art = art(context, state)
            main.post { ids.forEach { id -> mgr.updateAppWidget(id, buildPlayerForWidget(context, mgr, id, state, art, slot)) } }
        }
    }

    // ------------------------------------------------------------------ layouts

    private fun buildPlayerForWidget(context: Context, mgr: AppWidgetManager, id: Int, state: WidgetState?, art: Bitmap, slot: Int): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: the launcher picks the layout that fits as the widget is resized.
            return RemoteViews(
                mapOf(
                    SizeF(180f, 40f) to buildPlayer(context, state, art, large = false, slot = slot),
                    SizeF(250f, 130f) to buildPlayer(context, state, art, large = true, slot = slot),
                ),
            )
        }
        val opts: Bundle = mgr.getAppWidgetOptions(id)
        val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        return buildPlayer(context, state, art, large = h >= 130, slot = slot)
    }

    private val ART_IDS = intArrayOf(R.id.w_art0, R.id.w_art1)
    private val TITLE_IDS = intArrayOf(R.id.w_title0, R.id.w_title1)
    private val SUB_IDS = intArrayOf(R.id.w_sub0, R.id.w_sub1)

    /** Text views of the slot showing [slot] (for tests and previews). */
    fun titleId(slot: Int) = TITLE_IDS[slot]
    fun subId(slot: Int) = SUB_IDS[slot]

    /**
     * Builds the player widget. Only the views of [slot] get the track; the other slot keeps the previous track,
     * so when the launcher reapplies this to the existing widget it crossfades between them. Nothing here
     * schedules work: the elapsed time is a Chronometer, ticked by the launcher only while it's on screen.
     */
    fun buildPlayer(context: Context, state: WidgetState?, art: Bitmap?, large: Boolean, slot: Int = 0, now: Long = SystemClock.elapsedRealtime()): RemoteViews {
        val v = RemoteViews(context.packageName, if (large) R.layout.widget_player_large else R.layout.widget_player_small)
        v.setOnClickPendingIntent(R.id.w_root, openApp(context))
        val s = slot.coerceIn(0, 1)
        v.setDisplayedChild(R.id.w_info, s)
        if (art != null) v.setImageViewBitmap(ART_IDS[s], art)
        if (state == null) {
            v.setTextViewText(TITLE_IDS[s], "Strata")
            v.setTextViewText(SUB_IDS[s], "Tap play to shuffle your library")
        } else {
            v.setTextViewText(TITLE_IDS[s], state.title)
            v.setTextViewText(SUB_IDS[s], if (large) "${state.artist} · ${state.album}" else state.artist)
        }
        val playing = state?.playing == true
        v.setDisplayedChild(R.id.w_play, if (playing) 1 else 0)
        v.setContentDescription(R.id.w_play, if (playing) "Pause" else "Play")
        v.setOnClickPendingIntent(R.id.w_play, if (playing) action(context, ACTION_PAUSE) else startPlayback(context, ACTION_PLAY, null))
        v.setOnClickPendingIntent(R.id.w_next, action(context, ACTION_NEXT))
        if (large) {
            v.setOnClickPendingIntent(R.id.w_prev, action(context, ACTION_PREV))
            v.setDisplayedChild(R.id.w_fav, if (state?.favorite == true) 1 else 0)
            v.setContentDescription(R.id.w_fav, if (state?.favorite == true) "Remove from favorites" else "Add to favorites")
            v.setOnClickPendingIntent(R.id.w_fav, action(context, ACTION_FAV))
            v.setViewVisibility(R.id.w_fav, if (state == null) View.INVISIBLE else View.VISIBLE)
            applyClock(v, state, now)
        }
        return v
    }

    /** "1:23 / 4:56". Running: a Chronometer based in the past. Paused: plain text, so it can't drift. */
    private fun applyClock(v: RemoteViews, state: WidgetState?, now: Long) {
        if (state == null || state.durationMs <= 0) {
            v.setViewVisibility(R.id.w_time_row, View.INVISIBLE)
            v.setChronometer(R.id.w_elapsed, now, null, false)
            return
        }
        v.setViewVisibility(R.id.w_time_row, View.VISIBLE)
        v.setTextViewText(R.id.w_total, " / " + clock(state.durationMs))
        if (state.clockRuns(now)) {
            v.setViewVisibility(R.id.w_elapsed, View.VISIBLE)
            v.setViewVisibility(R.id.w_pos, View.GONE)
            v.setChronometer(R.id.w_elapsed, now - state.positionAt(now), null, true)
        } else {
            v.setChronometer(R.id.w_elapsed, now - state.positionMs, null, false)
            v.setViewVisibility(R.id.w_elapsed, View.GONE)
            v.setViewVisibility(R.id.w_pos, View.VISIBLE)
            v.setTextViewText(R.id.w_pos, clock(state.positionMs))
        }
    }

    /** Same format as the Chronometer: m:ss, or h:mm:ss past an hour. */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun buildPlayFrom(context: Context, state: WidgetState? = null): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_playfrom)
        v.setOnClickPendingIntent(R.id.w_root, openApp(context))
        val active = state?.fromTile?.takeIf { state.playing }
        TILES.forEach { t ->
            val on = t.key == active
            v.setImageViewResource(t.iconId, if (on) R.drawable.ic_w_pause else t.icon)
            v.setTextViewText(t.titleId, t.title)
            v.setTextViewText(t.subId, if (on) "Playing now" else t.sub)
            v.setInt(t.root, "setBackgroundResource", if (on) R.drawable.widget_tile_on_bg else R.drawable.widget_tile_bg)
            // The playing tile pauses; the others start their list.
            v.setOnClickPendingIntent(t.root, if (on) action(context, ACTION_PAUSE) else startPlayback(context, ACTION_PLAY_FROM, t.key))
        }
        return v
    }

    // ------------------------------------------------------------------ intents

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 100,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        flags(),
    )

    /** Pause / next / previous / favorite act on the running player through a broadcast. */
    fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, WidgetActionReceiver::class.java).setAction(action),
        flags(),
    )

    /** Anything that starts playback goes through the playback service, which may need to come to the foreground. */
    fun startPlayback(context: Context, action: String, key: String?): PendingIntent = PendingIntent.getForegroundService(
        context, (action + (key ?: "")).hashCode(),
        Intent(context, PlaybackService::class.java).setAction(action).also { if (key != null) it.putExtra(EXTRA_KEY, key) },
        flags(),
    )

    // ------------------------------------------------------------------ artwork

    private fun art(context: Context, state: WidgetState?): Bitmap {
        val albumId = state?.albumId ?: 0L
        artCache?.let { (a, b) -> if (a == albumId && !b.isRecycled) return b }
        val px = (context.resources.displayMetrics.density * 96).toInt().coerceIn(96, 300)
        val loaded: Bitmap? = if (state == null || Build.VERSION.SDK_INT < 29) null else try {
            context.contentResolver.loadThumbnail(Uri.parse(state.uri), Size(px, px), null)
        } catch (e: Exception) {
            null
        }
        val square = loaded?.let { centerCrop(it, px) } ?: generated(albumId, px)
        val out = rounded(square, px * 0.17f)
        artCache = albumId to out
        return out
    }

    private fun centerCrop(src: Bitmap, px: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(src, x, y, side, side), px, px, true)
    }

    private fun rounded(src: Bitmap, r: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, paint)
        return out
    }

    /** Same idea as the app's generated covers: a sun over the sea, colored from the album id. */
    fun generated(seed: Long, px: Int): Bitmap {
        val h = (((seed * 2654435761L) ushr 8).mod(360L)).toFloat()
        fun hsl(hh: Float, s: Float, l: Float) = ColorUtils.HSLToColor(floatArrayOf(((hh % 360f) + 360f) % 360f, s, l))
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = hsl(h + 180f, 0.45f, 0.14f); c.drawRect(0f, 0f, px.toFloat(), px.toFloat(), p)
        p.color = hsl(h, 0.75f, 0.68f); c.drawRect(0f, 0f, px.toFloat(), px * 0.58f, p)
        p.color = hsl(h + 30f, 0.55f, 0.32f); c.drawRect(0f, px * 0.58f, px.toFloat(), px.toFloat(), p)
        p.color = hsl(h + 20f, 0.9f, 0.86f); c.drawCircle(px * 0.68f, px * 0.34f, px * 0.15f, p)
        return bmp
    }
}

/** The adaptive "Now playing" widget (4×1 strip up to 4×2 with cover and controls). */
class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        StrataWidgets.updatePlayers(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        StrataWidgets.updatePlayers(context, appWidgetManager, intArrayOf(appWidgetId))
    }
}

/** Four one-tap starts: shuffle all, favorites, recently added, most played. */
class PlayFromWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = StrataWidgets.liveState(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, StrataWidgets.buildPlayFrom(context, state)) }
    }
}

/** Widget buttons that act on the player that is already loaded: pause, next, previous, favorite. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val model = (context.applicationContext as StrataApp).model
        val e = model.engine
        when (intent.action) {
            StrataWidgets.ACTION_PAUSE -> if (e.playIntent) e.togglePlay()
            StrataWidgets.ACTION_NEXT -> e.next()
            StrataWidgets.ACTION_PREV -> e.previous()
            StrataWidgets.ACTION_FAV -> if (e.currentId >= 0) model.toggleFav(e.currentId)
        }
        model.publishWidget()
    }
}
