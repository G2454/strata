package com.strata.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.strata.player.audio.Engine
import com.strata.player.data.Album
import com.strata.player.data.LibraryIndex
import com.strata.player.data.artistSplitter
import com.strata.player.data.LyricLine
import com.strata.player.data.MediaScanner
import com.strata.player.data.Playlist
import com.strata.player.data.Query
import com.strata.player.data.Session
import com.strata.player.data.Settings
import com.strata.player.data.SortKey
import com.strata.player.data.TagReader
import com.strata.player.data.Tech
import com.strata.player.data.Track
import com.strata.player.data.UserData
import com.strata.player.data.UserStore
import com.strata.player.data.albumArtUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { LIBRARY, PLAYLISTS, SEARCH, SOUND }
enum class LibTab(val label: String) { TRACKS("Tracks"), ALBUMS("Albums"), ARTISTS("Artists"), GENRES("Genres"), FOLDERS("Folders"), COMPOSERS("Composers") }
enum class NpView(val label: String) { ART("Artwork"), LYRICS("Lyrics"), VIZ("Visualizer"), INFO("Info") }

sealed class Detail {
    data class AlbumD(val id: Long) : Detail()
    data class ArtistD(val name: String) : Detail()
    data class GenreD(val name: String) : Detail()
    data class FolderD(val path: String) : Detail()
    data class ComposerD(val name: String) : Detail()
    data class PlaylistD(val id: String) : Detail()
}

sealed class Sheet {
    data class TrackMenu(val id: Long, val fromPlaylist: String? = null) : Sheet()
    /** Add one or many tracks (from multi-select or a whole album) to a playlist. */
    data class AddToPlaylist(val ids: List<Long>) : Sheet()
    data class NewPlaylist(val withTracks: List<Long>, val auto: Boolean) : Sheet()
    data class PlaylistMenu(val id: String) : Sheet()
    data object Queue : Sheet()
    data object Sleep : Sheet()
    data object Speed : Sheet()
    data object OrderSheet : Sheet()
    data object Output : Sheet()
}

data class SmartList(val id: String, val name: String)

val SMART_LISTS = listOf(
    SmartList("fav", "Favorites"),
    SmartList("most", "Most played"),
    SmartList("recent", "Recently played"),
    SmartList("added", "Recently added"),
)

/** App-wide state holder: library, user data, settings, navigation and actions. */
class AppModel(private val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = UserStore(app, scope)
    val tags = TagReader(app)
    val engine = Engine(app, scope, tags)

    // ---------------------------------------------------------------- data state
    var hasPermission by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var index by mutableStateOf(LibraryIndex.EMPTY); private set
    var user by mutableStateOf(store.loadUser()); private set
    var settings by mutableStateOf(store.loadSettings()); private set
    var tech by mutableStateOf(store.loadTech()); private set
    val accents = mutableStateMapOf<Long, Pair<Color, Color>>()
    private val accentRequested = HashSet<Long>()

    private var rawTracks: List<Track> = emptyList()
    private var restored = false
    private var techJob: Job? = null
    private var toastJob: Job? = null

    // ---------------------------------------------------------------- navigation state
    var tab by mutableStateOf(Tab.LIBRARY)
    var libTab by mutableStateOf(LibTab.TRACKS)
    val details = mutableStateListOf<Detail>()
    var nowOpen by mutableStateOf(false)
    var npView by mutableStateOf(NpView.ART)
    var sheet by mutableStateOf<Sheet?>(null)
    var propsId by mutableStateOf<Long?>(null)
    var propsTab by mutableStateOf(0)
    var settingsOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var toast by mutableStateOf<String?>(null); private set

    /** Playlist id the "Add songs" picker is filling, or null when the picker is closed. */
    var pickerFor by mutableStateOf<String?>(null)

    // ---------------------------------------------------------------- multi-select
    /** Selected track ids, in the order of the list they were picked from. */
    var selected by mutableStateOf<List<Long>>(emptyList()); private set
    var selecting by mutableStateOf(false); private set
    /** The editable playlist the selection was made in, so "Remove" can be offered. */
    var selectionPlaylist by mutableStateOf<String?>(null); private set
    private var selectionScope: List<Long> = emptyList()
    private var anchor: Long? = null

    init {
        engine.lookup = { index.byId[it] }
        engine.onTrackStarted = ::countPlay
        engine.onTick = { pos ->
            store.saveSession(Session(engine.context, engine.ctxName, engine.currentId, pos))
        }
        engine.onMessage = ::flash
        engine.applySettings(settings)
    }

    // ---------------------------------------------------------------- library

    fun onPermission(granted: Boolean) {
        val was = hasPermission
        hasPermission = granted
        if (granted && (!was || index.tracks.isEmpty())) rescan()
    }

    fun rescan(announce: Boolean = false) {
        if (loading) return
        loading = true
        scope.launch {
            val before = rawTracks.size
            val ts = withContext(Dispatchers.IO) {
                try { MediaScanner.scan(app, settings.ignoreShort) } catch (e: Exception) { emptyList() }
            }
            rawTracks = ts
            rebuildIndexNow()
            loading = false
            if (!restored) restoreSession()
            scanTech()
            if (announce) flash("Library rescanned · ${ts.size} tracks (${signed(ts.size - before)})")
        }
    }

    private fun signed(n: Int) = if (n > 0) "+$n" else "$n"

    /** Builds the library index (grouping and sorting thousands of tracks) off the main thread. */
    private suspend fun rebuildIndexNow() {
        val ov = user.overrides
        val raw = rawTracks
        val splitter = settings.artistSplitter()
        index = withContext(Dispatchers.Default) { LibraryIndex(applyOverrides(raw, ov), splitter) }
    }

    private fun rebuildIndex() {
        scope.launch { rebuildIndexNow() }
    }

    private fun applyOverrides(raw: List<Track>, ov: Map<Long, Map<String, String>>): List<Track> =
        if (ov.isEmpty()) raw else raw.map { t ->
            val o = ov[t.id] ?: return@map t
            t.copy(
                title = o["title"] ?: t.title,
                artist = o["artist"] ?: t.artist,
                album = o["album"] ?: t.album,
                albumArtist = o["albumArtist"] ?: t.albumArtist,
                genre = o["genre"] ?: t.genre,
                composer = o["composer"] ?: t.composer,
                year = o["year"]?.toIntOrNull() ?: t.year,
                trackNo = o["trackNo"]?.toIntOrNull() ?: t.trackNo,
                discNo = o["discNo"]?.toIntOrNull() ?: t.discNo,
            )
        }

    private fun restoreSession() {
        restored = true
        val s = store.loadSession()
        if (s.currentId >= 0 && index.byId.containsKey(s.currentId)) {
            val ctx = s.ctx.filter { index.byId.containsKey(it) }.ifEmpty { listOf(s.currentId) }
            engine.playContext(ctx, s.currentId, s.ctxName, s.positionMs, play = false)
        }
    }

    /**
     * Reads sample rate / bit depth for the format badges in the background.
     * Header-only reads on a low-priority thread; progress is saved as it goes, so a scan
     * interrupted by closing the app resumes where it stopped instead of starting over.
     */
    private fun scanTech() {
        techJob?.cancel()
        val tracks = index.tracks
        techJob = scope.launch(com.strata.player.data.Background.scan) {
            val known = HashMap(tech)
            var dirty = 0
            for (t in tracks) {
                if (known.containsKey(t.id)) continue
                known[t.id] = tags.readTech(t) ?: Tech(0, if (t.lossless) 16 else 0, 2)
                if (++dirty % 300 == 0) {
                    val snap = HashMap(known)
                    withContext(Dispatchers.Main) { tech = snap }
                    store.saveTech(snap)
                }
            }
            if (dirty > 0) {
                val snap = HashMap(known)
                withContext(Dispatchers.Main) { tech = snap }
                store.saveTech(snap)
            }
        }
    }

    /** Test hook: installs a fixed library without touching MediaStore. */
    @androidx.annotation.VisibleForTesting
    fun installLibraryForTest(tracks: List<Track>) {
        restored = true
        rawTracks = tracks
        index = LibraryIndex(tracks, settings.artistSplitter())
        hasPermission = true
    }

    fun track(id: Long): Track? = index.byId[id]
    fun album(id: Long): Album? = index.albumById[id]

    // ---------------------------------------------------------------- user data & settings

    fun updateUser(f: (UserData) -> UserData) {
        user = f(user)
        store.saveUser(user)
    }

    fun updateSettings(f: (Settings) -> Settings) {
        val old = settings
        settings = f(settings)
        store.saveSettings(settings)
        engine.applySettings(settings)
        if (old.ignoreShort != settings.ignoreShort) rescan()
        else if (old.splitArtists != settings.splitArtists || old.keepTogether != settings.keepTogether) {
            if (restored || rawTracks.isNotEmpty()) rebuildIndex()
        }
    }

    private fun countPlay(id: Long) {
        val now = System.currentTimeMillis()
        updateUser { u ->
            u.copy(
                plays = u.plays + (id to ((u.plays[id] ?: 0) + 1)),
                lastPlayed = u.lastPlayed + (id to now),
                firstPlayed = if (u.firstPlayed.containsKey(id)) u.firstPlayed else u.firstPlayed + (id to now),
                history = (listOf(id) + u.history.filter { it != id }).take(60),
            )
        }
    }

    fun isFav(id: Long) = id in user.favorites
    fun toggleFav(id: Long) = updateUser { u -> u.copy(favorites = if (id in u.favorites) u.favorites - id else u.favorites + id) }
    fun rating(t: Track): Int = user.ratings[t.id] ?: 0
    fun setRating(id: Long, r: Int) = updateUser { u -> u.copy(ratings = if (r <= 0) u.ratings - id else u.ratings + (id to r)) }
    fun plays(t: Track): Int = user.plays[t.id] ?: 0

    fun saveOverrides(id: Long, values: Map<String, String>) {
        val t = rawTracks.firstOrNull { it.id == id } ?: return
        val base = mapOf(
            "title" to t.title, "artist" to t.artist, "album" to t.album, "albumArtist" to t.albumArtist,
            "genre" to t.genre, "composer" to t.composer, "year" to t.year.toString(),
            "trackNo" to t.trackNo.toString(), "discNo" to t.discNo.toString(),
        )
        val diff = values.filter { (k, v) -> base[k] != v }
        updateUser { u -> u.copy(overrides = if (diff.isEmpty()) u.overrides - id else u.overrides + (id to diff)) }
        rebuildIndex()
        flash("Saved in your Strata library")
    }

    fun originalTrack(id: Long): Track? = rawTracks.firstOrNull { it.id == id }

    val queryFields = object : Query.Fields {
        override fun rating(t: Track) = this@AppModel.rating(t)
        override fun plays(t: Track) = this@AppModel.plays(t)
        override fun tech(t: Track): Tech? = this@AppModel.tech[t.id]
        override fun favorite(t: Track) = isFav(t.id)
        override fun artists(t: Track) = index.artistsOf(t)
    }

    fun search(q: String): List<Track> {
        val query = Query.parse(q)
        if (query.isEmpty) return emptyList()
        return index.tracks.filter { query.matches(it, queryFields) }
    }

    fun sortedTracks(key: SortKey): List<Track> = when (key) {
        SortKey.TITLE -> index.tracks.sortedBy { it.title.lowercase() }
        SortKey.ARTIST -> index.tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.discNo }, { it.trackNo }))
        SortKey.ALBUM -> index.albumOrdered(index.tracks)
        SortKey.ADDED -> index.tracks.sortedByDescending { it.dateAddedSec }
        SortKey.PLAYS -> index.tracks.sortedByDescending { plays(it) }
        SortKey.LENGTH -> index.tracks.sortedByDescending { it.durationMs }
    }

    // ---------------------------------------------------------------- playlists

    fun playlistName(id: String): String = SMART_LISTS.firstOrNull { it.id == id }?.name ?: user.playlists.firstOrNull { it.id == id }?.name ?: "Playlist"

    fun playlistIds(id: String): List<Long> {
        val by = index.byId
        return when (id) {
            "fav" -> index.albumOrdered(index.tracks.filter { it.id in user.favorites }).map { it.id }
            "most" -> index.tracks.filter { plays(it) > 0 }.sortedByDescending { plays(it) }.take(30).map { it.id }
            "recent" -> user.history.filter { by.containsKey(it) }
            "added" -> index.tracks.sortedByDescending { it.dateAddedSec }.take(30).map { it.id }
            else -> {
                val p = user.playlists.firstOrNull { it.id == id } ?: return emptyList()
                if (p.query != null) index.albumOrdered(search(p.query)).map { it.id } else p.ids.filter { by.containsKey(it) }
            }
        }
    }

    fun createPlaylist(name: String, query: String?, withTracks: List<Long> = emptyList()): String {
        var id = "p" + System.currentTimeMillis()
        while (user.playlists.any { it.id == id }) id += "x"
        val clean = name.trim().ifEmpty { "New playlist" }
        val ids = withTracks.distinct()
        updateUser { u -> u.copy(playlists = u.playlists + Playlist(id, clean, ids, query?.trim()?.ifEmpty { null })) }
        flash(
            when {
                ids.size == 1 -> "Added to “$clean”"
                ids.size > 1 -> "Created “$clean” with ${ids.size} tracks"
                else -> "Created “$clean”"
            },
        )
        return id
    }

    fun addToPlaylist(pid: String, tid: Long) = addToPlaylist(pid, listOf(tid))

    /** Adds tracks that are not in the playlist yet, keeping their order. Returns how many were added. */
    fun addToPlaylist(pid: String, tids: List<Long>): Int {
        val p = user.playlists.firstOrNull { it.id == pid } ?: return 0
        val fresh = tids.distinct().filter { it !in p.ids }
        if (fresh.isEmpty()) {
            flash(if (tids.size == 1) "Already in “${p.name}”" else "All already in “${p.name}”")
            return 0
        }
        updateUser { u -> u.copy(playlists = u.playlists.map { if (it.id == pid) it.copy(ids = it.ids + fresh) else it }) }
        val skipped = tids.distinct().size - fresh.size
        flash(
            if (fresh.size == 1 && skipped == 0) "Added to “${p.name}”"
            else "Added ${com.strata.player.ui.Fmt.plural(fresh.size, "track")} to “${p.name}”" + if (skipped > 0) " · $skipped already there" else "",
        )
        return fresh.size
    }

    fun removeFromPlaylist(pid: String, tid: Long) = removeFromPlaylist(pid, listOf(tid))

    fun removeFromPlaylist(pid: String, tids: Collection<Long>) {
        val set = tids.toSet()
        updateUser { u -> u.copy(playlists = u.playlists.map { if (it.id == pid) it.copy(ids = it.ids.filter { id -> id !in set }) else it }) }
    }

    fun movePlaylistItem(pid: String, from: Int, to: Int) = updateUser { u ->
        u.copy(playlists = u.playlists.map {
            if (it.id != pid || from !in it.ids.indices || to !in it.ids.indices) it
            else it.copy(ids = it.ids.toMutableList().apply { add(to, removeAt(from)) })
        })
    }

    fun renamePlaylist(pid: String, name: String, query: String?) = updateUser { u ->
        u.copy(playlists = u.playlists.map { if (it.id == pid) it.copy(name = name.trim().ifEmpty { it.name }, query = if (it.query != null) (query ?: it.query) else null) else it })
    }

    fun deletePlaylist(pid: String) {
        val name = playlistName(pid)
        updateUser { u -> u.copy(playlists = u.playlists.filter { it.id != pid }) }
        details.removeAll { it is Detail.PlaylistD && it.id == pid }
        flash("Deleted “$name”")
    }

    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        updateUser { u -> u.copy(recent = (listOf(t) + u.recent.filter { it != t }).take(8)) }
    }

    // ---------------------------------------------------------------- playback helpers

    fun play(ids: List<Long>, start: Long, name: String) {
        engine.playContext(ids, start, name)
        if (tab == Tab.SEARCH) addRecentSearch(query)
    }

    fun shuffle(ids: List<Long>, name: String) {
        if (ids.isEmpty()) return
        if (!settings.order.isShuffle) updateSettings { it.copy(order = com.strata.player.data.Order.SHUFFLE_TRACKS) }
        engine.playContext(ids, ids.random(), name)
    }

    fun lyrics(t: Track, onResult: (List<LyricLine>?) -> Unit) {
        scope.launch {
            val l = withContext(Dispatchers.IO) { tags.lyrics(t) }
            onResult(l)
        }
    }

    // ---------------------------------------------------------------- album colours

    fun accentFor(t: Track?): Pair<Color, Color>? {
        if (t == null) return null
        accents[t.albumId]?.let { return it }
        val key = t.albumId
        // Called from composition: never write state here, just start one background lookup per album.
        if (!accentRequested.add(key)) return fallbackAccent(key)
        scope.launch(com.strata.player.data.Background.art) {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) app.contentResolver.loadThumbnail(t.uri, Size(160, 160), null)
                else app.contentResolver.openInputStream(albumArtUri(t.albumId))?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            var pair = fallbackAccent(key)
            if (bmp != null) {
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
                if (sw != null) pair = derive(sw.rgb)
            }
            withContext(Dispatchers.Main) { accents[key] = pair }
        }
        return fallbackAccent(key)
    }

    private fun derive(rgb: Int): Pair<Color, Color> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        val s = hsl[1].coerceIn(0.45f, 0.85f)
        val light = ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.72f))
        val deep = ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.34f))
        return Color(light) to Color(deep)
    }

    fun fallbackAccent(seed: Long): Pair<Color, Color> {
        val hue = ((seed * 2654435761L) ushr 8).mod(360L).toFloat()
        val light = ColorUtils.HSLToColor(floatArrayOf(hue, 0.7f, 0.72f))
        val deep = ColorUtils.HSLToColor(floatArrayOf(hue, 0.7f, 0.34f))
        return Color(light) to Color(deep)
    }

    // ---------------------------------------------------------------- UI helpers

    fun flash(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = scope.launch { delay(2600); toast = null }
    }

    fun open(d: Detail) {
        clearSelection()
        details.add(d)
    }

    fun goTo(d: Detail) {
        clearSelection()
        sheet = null
        nowOpen = false
        if (tab == Tab.SOUND) tab = Tab.LIBRARY
        details.add(d)
    }

    fun selectTab(t: Tab) {
        clearSelection()
        tab = t
        details.clear()
    }

    // ---------------------------------------------------------------- multi-select actions

    /** The "Select" button: enter selection mode with nothing picked yet. */
    fun beginSelecting(scope: List<Long>, playlist: String? = null) {
        selectionScope = scope
        selectionPlaylist = playlist
        selected = emptyList()
        anchor = null
        selecting = true
    }

    /** Long-press on a row: start selecting in the list [scope] (or extend the selection as a range). */
    fun startSelection(scope: List<Long>, id: Long, playlist: String? = null) {
        if (selecting && selectionScope == scope) { selectRange(scope, id); return }
        selectionScope = scope
        selectionPlaylist = playlist
        selected = listOf(id)
        anchor = id
        selecting = true
    }

    fun toggleSelected(scope: List<Long>, id: Long) {
        selectionScope = scope
        val set = selected.toMutableSet()
        if (!set.add(id)) set.remove(id)
        selected = scope.filter { it in set } + set.filter { it !in scope }
        anchor = id
    }

    /** Selects everything between the last touched row and [id]. */
    fun selectRange(scope: List<Long>, id: Long) {
        selectionScope = scope
        val a = scope.indexOf(anchor ?: id).takeIf { it >= 0 } ?: scope.indexOf(id)
        val b = scope.indexOf(id)
        if (a < 0 || b < 0) { toggleSelected(scope, id); return }
        val range = scope.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
        val set = selected.toSet() + range
        selected = scope.filter { it in set }
        anchor = id
        selecting = true
    }

    fun selectAll() {
        selected = if (selected.size == selectionScope.size) emptyList() else selectionScope
    }

    fun isSelected(id: Long) = selecting && id in selected

    fun clearSelection() {
        if (!selecting && selected.isEmpty()) return
        selecting = false
        selected = emptyList()
        selectionPlaylist = null
        anchor = null
    }

    fun playSelected() {
        val ids = selected
        if (ids.isEmpty()) return
        play(ids, ids.first(), "Selection")
        clearSelection()
    }

    fun queueSelected(next: Boolean) {
        val ts = selected.mapNotNull { track(it) }
        if (ts.isEmpty()) return
        engine.enqueue(ts, next)
        flash(if (next) "${com.strata.player.ui.Fmt.plural(ts.size, "track")} play next" else "Added ${com.strata.player.ui.Fmt.plural(ts.size, "track")} to queue")
        clearSelection()
    }

    fun removeSelectedFromPlaylist() {
        val pid = selectionPlaylist ?: return
        val n = selected.size
        removeFromPlaylist(pid, selected)
        flash("Removed ${com.strata.player.ui.Fmt.plural(n, "track")}")
        clearSelection()
    }

    /** Returns true when something was closed. */
    fun back(): Boolean {
        when {
            sheet != null -> sheet = null
            pickerFor != null -> pickerFor = null
            selecting -> clearSelection()
            propsId != null -> propsId = null
            settingsOpen -> settingsOpen = false
            nowOpen -> nowOpen = false
            details.isNotEmpty() -> details.removeAt(details.lastIndex)
            tab != Tab.LIBRARY -> tab = Tab.LIBRARY
            else -> return false
        }
        return true
    }
}
