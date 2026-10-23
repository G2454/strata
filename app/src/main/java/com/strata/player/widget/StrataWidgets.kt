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
)

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
        )
    }

    /** Coalesces bursts of player events into one widget refresh. */
    private fun scheduleUpdate(context: Context) {
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { updateAll(context) }
        pending = r
        main.postDelayed(r, 250)
    }

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val players = mgr.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
        val launchers = mgr.getAppWidgetIds(ComponentName(context, PlayFromWidgetProvider::class.java))
        if (players.isNotEmpty()) updatePlayers(context, mgr, players)
        launchers.forEach { mgr.updateAppWidget(it, buildPlayFrom(context)) }
    }

    fun updatePlayers(context: Context, mgr: AppWidgetManager, ids: IntArray, async: Boolean = true) {
        val state = readState(context)
        if (!async) {
            val art = art(context, state)
            ids.forEach { id -> mgr.updateAppWidget(id, buildPlayerForWidget(context, mgr, id, state, art)) }
            return
        }
        // Artwork is decoded off the main thread; the widgets are updated back on it.
        worker.execute {
            val art = art(context, state)
            main.post { ids.forEach { id -> mgr.updateAppWidget(id, buildPlayerForWidget(context, mgr, id, state, art)) } }
        }
    }

    // ------------------------------------------------------------------ layouts

    private fun buildPlayerForWidget(context: Context, mgr: AppWidgetManager, id: Int, state: WidgetState?, art: Bitmap): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: the launcher picks the layout that fits as the widget is resized.
            return RemoteViews(
                mapOf(
                    SizeF(180f, 40f) to buildPlayer(context, state, art, large = false),
                    SizeF(250f, 130f) to buildPlayer(context, state, art, large = true),
                ),
            )
        }
        val opts: Bundle = mgr.getAppWidgetOptions(id)
        val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        return buildPlayer(context, state, art, large = h >= 130)
    }

    fun buildPlayer(context: Context, state: WidgetState?, art: Bitmap?, large: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, if (large) R.layout.widget_player_large else R.layout.widget_player_small)
        v.setOnClickPendingIntent(R.id.w_root, openApp(context))
        if (art != null) v.setImageViewBitmap(R.id.w_art, art)
        if (state == null) {
            v.setTextViewText(R.id.w_title, "Strata")
            v.setTextViewText(R.id.w_sub, "Tap play to shuffle your library")
        } else {
            v.setTextViewText(R.id.w_title, state.title)
            v.setTextViewText(R.id.w_sub, if (large) "${state.artist} · ${state.album}" else state.artist)
        }
        val playing = state?.playing == true
        v.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        v.setContentDescription(R.id.w_play, if (playing) "Pause" else "Play")
        v.setOnClickPendingIntent(R.id.w_play, if (playing) action(context, ACTION_PAUSE) else startPlayback(context, ACTION_PLAY, null))
        v.setOnClickPendingIntent(R.id.w_next, action(context, ACTION_NEXT))
        if (large) {
            v.setOnClickPendingIntent(R.id.w_prev, action(context, ACTION_PREV))
            v.setImageViewResource(R.id.w_fav, if (state?.favorite == true) R.drawable.ic_w_heart_fill else R.drawable.ic_w_heart)
            v.setContentDescription(R.id.w_fav, if (state?.favorite == true) "Remove from favorites" else "Add to favorites")
            v.setOnClickPendingIntent(R.id.w_fav, action(context, ACTION_FAV))
            v.setViewVisibility(R.id.w_fav, if (state == null) View.INVISIBLE else View.VISIBLE)
        }
        return v
    }

    fun buildPlayFrom(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_playfrom)
        v.setOnClickPendingIntent(R.id.w_root, openApp(context))
        TILES.forEach { t ->
            v.setImageViewResource(t.iconId, t.icon)
            v.setTextViewText(t.titleId, t.title)
            v.setTextViewText(t.subId, t.sub)
            v.setOnClickPendingIntent(t.root, startPlayback(context, ACTION_PLAY_FROM, t.key))
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
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, StrataWidgets.buildPlayFrom(context)) }
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
