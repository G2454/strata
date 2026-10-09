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

    private val dir = File(context.filesDir, "strata").apply { mkdirs() }
    private val jobs = HashMap<String, Job>()

    fun loadUser(): UserData = readJson("user.json")?.let(::userFrom) ?: UserData(
        playlists = listOf(Playlist("auto-hires", "Hi-res, well rated", query = "bits>=24 rating>=4")),
    )

    fun loadSettings(): Settings = readJson("settings.json")?.let(::settingsFrom) ?: Settings()
    fun loadSession(): Session = readJson("session.json")?.let(::sessionFrom) ?: Session()
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
    fun saveSession(s: Session) = saveLater("session.json", 1500) { sessionTo(s) }
    fun saveTech(t: Map<Long, Tech>) = saveLater("tech.json", 3000) {
        JSONObject().apply { t.forEach { (k, v) -> put(k.toString(), JSONArray(listOf(v.sampleRate, v.bits, v.channels))) } }
    }

    fun waveFile(id: Long): File = File(File(dir, "waves").apply { mkdirs() }, "$id.bin")

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
