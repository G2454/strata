package com.strata.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists user data, settings, last session and the tech-info cache as small JSON files. */
class UserStore(context: Context, private val scope: CoroutineScope) {

    companion object {
        /** Bump when the library snapshot layout changes; an old snapshot is then ignored. */
        const val LIBRARY_VERSION = 1
        const val BACKUP_FORMAT = "strata-backup"
    }

    private val dir = File(context.filesDir, "strata").apply { mkdirs() }
    private val jobs = HashMap<String, Job>()

    fun loadUser(): UserData = readJson("user.json")?.let(::userFrom) ?: UserData(
        playlists = listOf(Playlist("auto-hires", "Hi-res, well rated", query = "bits>=24 rating>=4")),
    )

    fun loadSettings(): Settings = readJson("settings.json")?.let(::settingsFrom) ?: Settings()
    /** The list being played (session.json, written when it changes) plus where in it (now.json, tiny, written often). */
    fun loadSession(): Session {
        val base = readJson("session.json")?.let(::sessionFrom) ?: Session()
        val now = readJson("now.json") ?: return base
        return base.copy(currentId = now.optLong("currentId", base.currentId), positionMs = now.optLong("positionMs", base.positionMs))
    }
    fun loadTech(): Map<Long, Tech> = readJson("tech.json")?.let { o ->
        val m = HashMap<Long, Tech>()
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            m[k.toLong()] = Tech(a.getInt(0), a.getInt(1), a.getInt(2))
        }
        m
    } ?: emptyMap()

    fun saveUser(u: UserData) = saveLater("user.json") { userTo(u) }
    fun saveSettings(s: Settings) = saveLater("settings.json") { settingsTo(s) }
    private var savedCtx: List<Long>? = null
    private var savedCtxName: String? = null

    /**
     * Saves where playback is. The play list itself (possibly thousands of ids) is only rewritten when it
     * changes; the current track and position go to a few-byte file.
     */
    @Synchronized
    fun saveSession(s: Session) {
        if (s.ctx !== savedCtx || s.ctxName != savedCtxName) {
            savedCtx = s.ctx
            savedCtxName = s.ctxName
            saveLater("session.json", 1500) { sessionTo(s) }
        }
        saveLater("now.json", 500) { JSONObject().put("currentId", s.currentId).put("positionMs", s.positionMs) }
    }

    /** How many times [saveLater] actually wrote each file (for tests). */
    @androidx.annotation.VisibleForTesting
    val writes = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun saveTech(t: Map<Long, Tech>) = saveLater("tech.json", 3000) {
        JSONObject().apply { t.forEach { (k, v) -> put(k.toString(), JSONArray(listOf(v.sampleRate, v.bits, v.channels))) } }
    }

    /** Saved queues plus which one is playing. */
    fun loadQueues(): Pair<List<SavedQueue>, String?> = readJson("queues.json")?.let(::queuesFrom) ?: (emptyList<SavedQueue>() to null)

    private fun queuesFrom(o: JSONObject): Pair<List<SavedQueue>, String?> {
        val a = o.optJSONArray("queues") ?: JSONArray()
        val list = List(a.length()) {
            val q = a.getJSONObject(it)
            SavedQueue(
                id = q.getString("id"), name = q.getString("name"), ids = longs(q.optJSONArray("ids")),
                index = q.optInt("index", 0), positionMs = q.optLong("positionMs", 0), updatedAt = q.optLong("updatedAt", 0),
                auto = q.optBoolean("auto", false),
            )
        }
        return list to (if (o.has("active")) o.getString("active") else null)
    }

    private fun queuesTo(queues: List<SavedQueue>, active: String?) = JSONObject().apply {
        put("queues", JSONArray().apply {
            queues.forEach { q ->
                put(JSONObject().apply {
                    put("id", q.id); put("name", q.name); put("ids", JSONArray(q.ids)); put("index", q.index)
                    put("positionMs", q.positionMs); put("updatedAt", q.updatedAt); put("auto", q.auto)
                })
            }
        })
        if (active != null) put("active", active)
    }

    fun saveQueues(queues: List<SavedQueue>, active: String?) = saveLater("queues.json") { queuesTo(queues, active) }

    // ------------------------------------------------------------------ backup file

    /** Everything worth keeping, as one JSON text: what Settings → "Export a backup" saves. */
    fun backupToJson(b: Backup): String = JSONObject().apply {
        put("format", BACKUP_FORMAT); put("version", 1); put("createdAt", b.createdAt)
        put("user", userTo(b.user))
        put("settings", settingsTo(b.settings))
        put("queues", queuesTo(b.queues, null))
        put("positions", JSONObject().apply { b.positions.forEach { (k, v) -> put(k.toString(), v) } })
        // Track ids are only stable on one phone, so each one also carries what identifies the file elsewhere.
        put("tracks", JSONObject().apply {
            b.tracks.forEach { (id, r) -> put(id.toString(), JSONArray(listOf(r.path, r.title, r.artist, r.album, r.durationMs))) }
        })
    }.toString()

    /** Reads a backup file; null when it isn't one. */
    fun backupFromJson(text: String): Backup? = try {
        val o = JSONObject(text)
        if (o.optString("format") != BACKUP_FORMAT) null else {
            val refs = HashMap<Long, TrackRef>()
            o.optJSONObject("tracks")?.let { t ->
                t.keys().forEach { k ->
                    val a = t.getJSONArray(k)
                    refs[k.toLong()] = TrackRef(a.getString(0), a.getString(1), a.getString(2), a.getString(3), a.optLong(4))
                }
            }
            Backup(
                user = userFrom(o.getJSONObject("user")),
                settings = o.optJSONObject("settings")?.let(::settingsFrom) ?: Settings(),
                queues = o.optJSONObject("queues")?.let { queuesFrom(it).first } ?: emptyList(),
                positions = longMap(o.optJSONObject("positions")),
                tracks = refs,
                createdAt = o.optLong("createdAt"),
            )
        }
    } catch (_: Exception) {
        null
    }

    /** Remembered positions of long files, by track id. */
    fun loadPositions(): Map<Long, Long> = longMap(readJson("positions.json"))
    fun savePositions(p: Map<Long, Long>) = saveLater("positions.json", 1000) {
        JSONObject().apply { p.forEach { (k, v) -> put(k.toString(), v) } }
    }

    fun waveFile(id: Long): File = File(File(dir, "waves").apply { mkdirs() }, "$id.bin")

    // ------------------------------------------------------------------ library snapshot

    /**
     * The last MediaStore scan, so a cold start can show the library at once while a fresh scan runs.
     * A compact binary file: for 10,000 tracks it reads in a fraction of the time the MediaStore query takes.
     */
    fun saveLibrary(tracks: List<Track>) {
        scope.launch(Dispatchers.IO) {
            try {
                val tmp = File(dir, "library.bin.tmp")
                java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { o ->
                    o.writeInt(LIBRARY_VERSION)
                    o.writeInt(tracks.size)
                    for (t in tracks) {
                        o.writeLong(t.id); o.writeUTF(t.title); o.writeUTF(t.artist); o.writeUTF(t.album); o.writeLong(t.albumId)
                        o.writeUTF(t.albumArtist); o.writeUTF(t.genre); o.writeUTF(t.composer); o.writeInt(t.year); o.writeInt(t.trackNo)
                        o.writeInt(t.discNo); o.writeLong(t.durationMs); o.writeUTF(t.path); o.writeLong(t.sizeBytes)
                        o.writeLong(t.dateAddedSec); o.writeUTF(t.mime); o.writeInt(t.bitrateBps)
                    }
                }
                tmp.renameTo(File(dir, "library.bin"))
                writes.merge("library.bin", 1, Int::plus)
            } catch (_: Exception) {
            }
        }
    }

    /** Reads the snapshot written by [saveLibrary]; empty if there is none or it's from another version. */
    fun loadLibrary(): List<Track> = try {
        val f = File(dir, "library.bin")
        if (!f.exists()) emptyList() else java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream(), 64 * 1024)).use { i ->
            if (i.readInt() != LIBRARY_VERSION) return@use emptyList()
            val n = i.readInt()
            if (n !in 0..1_000_000) return@use emptyList()
            val out = ArrayList<Track>(n)
            repeat(n) {
                val id = i.readLong()
                val title = i.readUTF(); val artist = i.readUTF(); val album = i.readUTF(); val albumId = i.readLong()
                val albumArtist = i.readUTF(); val genre = i.readUTF(); val composer = i.readUTF(); val year = i.readInt()
                val trackNo = i.readInt(); val discNo = i.readInt(); val dur = i.readLong(); val path = i.readUTF()
                val size = i.readLong(); val added = i.readLong(); val mime = i.readUTF(); val bitrate = i.readInt()
                out += Track(
                    id = id, uri = MediaScanner.uriOf(id), title = title, artist = artist, album = album, albumId = albumId,
                    albumArtist = albumArtist, genre = genre, composer = composer, year = year, trackNo = trackNo, discNo = discNo,
                    durationMs = dur, path = path, folder = path.substringBeforeLast('/', ""), sizeBytes = size,
                    dateAddedSec = added, mime = mime, bitrateBps = bitrate,
                )
            }
            out
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------

    private fun readJson(name: String): JSONObject? = try {
        val f = File(dir, name)
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun saveLater(name: String, delayMs: Long = 400, build: () -> JSONObject) {
        jobs[name]?.cancel()
        jobs[name] = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            try {
                val json = build().toString()
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(json)
                tmp.renameTo(File(dir, name))
                writes.merge(name, 1, Int::plus)
            } catch (_: Exception) {
            }
        }
    }

    private fun longs(a: JSONArray?): List<Long> = if (a == null) emptyList() else List(a.length()) { a.getLong(it) }
    private fun longMap(o: JSONObject?): Map<Long, Long> {
        if (o == null) return emptyMap()
        val m = HashMap<Long, Long>()
        o.keys().forEach { m[it.toLong()] = o.getLong(it) }
        return m
    }

    private fun intMap(o: JSONObject?): Map<Long, Int> {
        if (o == null) return emptyMap()
        val m = HashMap<Long, Int>()
        o.keys().forEach { m[it.toLong()] = o.getInt(it) }
        return m
    }

    private fun userFrom(o: JSONObject): UserData {
        val pls = o.optJSONArray("playlists")
        val ov = o.optJSONObject("overrides")
        val overrides = HashMap<Long, Map<String, String>>()
        ov?.keys()?.forEach { k ->
            val inner = ov.getJSONObject(k)
            val m = HashMap<String, String>()
            inner.keys().forEach { m[it] = inner.getString(it) }
            overrides[k.toLong()] = m
        }
        val rec = o.optJSONArray("recent")
        return UserData(
            favorites = longs(o.optJSONArray("favorites")).toSet(),
            ratings = intMap(o.optJSONObject("ratings")),
            plays = intMap(o.optJSONObject("plays")),
            firstPlayed = longMap(o.optJSONObject("firstPlayed")),
            lastPlayed = longMap(o.optJSONObject("lastPlayed")),
            history = longs(o.optJSONArray("history")),
            playlists = if (pls == null) emptyList() else List(pls.length()) {
                val p = pls.getJSONObject(it)
                Playlist(p.getString("id"), p.getString("name"), longs(p.optJSONArray("ids")), if (p.has("query")) p.getString("query") else null)
            },
            overrides = overrides,
            recent = if (rec == null) emptyList() else List(rec.length()) { rec.getString(it) },
        )
    }

    private fun userTo(u: UserData) = JSONObject().apply {
        put("favorites", JSONArray(u.favorites.toList()))
        put("ratings", JSONObject().apply { u.ratings.forEach { (k, v) -> put(k.toString(), v) } })
        put("plays", JSONObject().apply { u.plays.forEach { (k, v) -> put(k.toString(), v) } })
        put("firstPlayed", JSONObject().apply { u.firstPlayed.forEach { (k, v) -> put(k.toString(), v) } })
        put("lastPlayed", JSONObject().apply { u.lastPlayed.forEach { (k, v) -> put(k.toString(), v) } })
        put("history", JSONArray(u.history))
        put("playlists", JSONArray().apply {
            u.playlists.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("ids", JSONArray(p.ids))
                    if (p.query != null) put("query", p.query)
                })
            }
        })
        put("overrides", JSONObject().apply {
            u.overrides.forEach { (k, m) -> put(k.toString(), JSONObject().apply { m.forEach { (a, b) -> put(a, b) } }) }
        })
        put("recent", JSONArray(u.recent))
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun settingsFrom(o: JSONObject): Settings {
        val d = Settings()
        val bands = o.optJSONArray("bands")
        val chain = o.optJSONArray("chain")
        val chainList = if (chain == null) d.chain else List(chain.length()) { chain.getString(it) }.filter { it in DSP_STAGES }
        return Settings(
            theme = enumOf(o.optString("theme"), d.theme),
            colorFromArt = o.optBoolean("colorFromArt", d.colorFromArt),
            columns = o.optBoolean("columns", d.columns),
            sort = enumOf(o.optString("sort"), d.sort),
            eqOn = o.optBoolean("eqOn", d.eqOn),
            preset = o.optString("preset", d.preset),
            bands = if (bands != null && bands.length() == 10) List(10) { bands.getDouble(it).toFloat() } else d.bands,
            preamp = o.optDouble("preamp", 0.0).toFloat(),
            rgMode = enumOf(o.optString("rgMode"), d.rgMode),
            rgPreamp = o.optDouble("rgPreamp", 0.0).toFloat(),
            rgPreampNoInfo = o.optDouble("rgPreampNoInfo", 0.0).toFloat(),
            rgPreventClip = o.optBoolean("rgPreventClip", d.rgPreventClip),
            fadeSec = o.optInt("fadeSec", d.fadeSec),
            skipSilence = o.optBoolean("skipSilence", d.skipSilence),
            speed = o.optDouble("speed", 1.0).toFloat(),
            keepPitch = o.optBoolean("keepPitch", d.keepPitch),
            balance = o.optDouble("balance", 0.0).toFloat(),
            mono = o.optBoolean("mono", d.mono),
            widen = o.optBoolean("widen", d.widen),
            limiter = o.optBoolean("limiter", d.limiter),
            chain = chainList + DSP_STAGES.filter { it !in chainList },
            order = enumOf(o.optString("order"), d.order),
            ignoreShort = o.optBoolean("ignoreShort", d.ignoreShort),
            pauseOnDisconnect = o.optBoolean("pauseOnDisconnect", d.pauseOnDisconnect),
            splitArtists = o.optBoolean("splitArtists", d.splitArtists),
            keepTogether = o.optString("keepTogether", d.keepTogether),
            resumeOnWired = o.optBoolean("resumeOnWired", d.resumeOnWired),
            resumeOnBluetooth = o.optBoolean("resumeOnBluetooth", d.resumeOnBluetooth),
            rememberLongPositions = o.optBoolean("rememberLongPositions", d.rememberLongPositions),
            keepPlayingAfterClose = o.optBoolean("keepPlayingAfterClose", d.keepPlayingAfterClose),
            excludedFolders = o.optJSONArray("excludedFolders")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: d.excludedFolders,
        )
    }

    private fun settingsTo(s: Settings) = JSONObject().apply {
        put("theme", s.theme.name); put("colorFromArt", s.colorFromArt); put("columns", s.columns); put("sort", s.sort.name)
        put("eqOn", s.eqOn); put("preset", s.preset); put("bands", JSONArray(s.bands.map { it.toDouble() })); put("preamp", s.preamp.toDouble())
        put("rgMode", s.rgMode.name); put("rgPreamp", s.rgPreamp.toDouble()); put("rgPreampNoInfo", s.rgPreampNoInfo.toDouble())
        put("rgPreventClip", s.rgPreventClip); put("fadeSec", s.fadeSec); put("skipSilence", s.skipSilence)
        put("speed", s.speed.toDouble()); put("keepPitch", s.keepPitch); put("balance", s.balance.toDouble())
        put("mono", s.mono); put("widen", s.widen); put("limiter", s.limiter); put("chain", JSONArray(s.chain))
        put("order", s.order.name); put("ignoreShort", s.ignoreShort); put("pauseOnDisconnect", s.pauseOnDisconnect)
        put("splitArtists", s.splitArtists); put("keepTogether", s.keepTogether)
        put("resumeOnWired", s.resumeOnWired); put("resumeOnBluetooth", s.resumeOnBluetooth)
        put("rememberLongPositions", s.rememberLongPositions); put("excludedFolders", JSONArray(s.excludedFolders.toList()))
        put("keepPlayingAfterClose", s.keepPlayingAfterClose)
    }

    private fun sessionFrom(o: JSONObject) = Session(
        ctx = longs(o.optJSONArray("ctx")),
        ctxName = o.optString("ctxName", "All tracks"),
        currentId = o.optLong("currentId", -1),
        positionMs = o.optLong("positionMs", 0),
    )

    private fun sessionTo(s: Session) = JSONObject().apply {
        put("ctx", JSONArray(s.ctx)); put("ctxName", s.ctxName); put("currentId", s.currentId); put("positionMs", s.positionMs)
    }
}
