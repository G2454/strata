package com.strata.player

import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.strata.player.audio.Engine
import com.strata.player.data.Query
import com.strata.player.data.Session
import com.strata.player.data.Tech
import com.strata.player.data.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The battery and speed work: fewer disk writes and wakeups, cheaper search, the library snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EfficiencyTest {

    private lateinit var model: AppModel
    private val music = "/storage/emulated/0/Music"

    private fun tr(id: Long, title: String, album: String, sec: Int = 200, mime: String = "audio/flac", ext: String = "flac") = Track(
        id = id, uri = Uri.parse("content://media/external/audio/media/$id"),
        title = title, artist = "Artist $album", album = album, albumId = 100L + album.length, albumArtist = "Artist $album",
        genre = "Pop", composer = "", year = 2020, trackNo = id.toInt(), discNo = 1, durationMs = sec * 1000L,
        path = "$music/$album/$title.$ext", folder = "$music/$album", sizeBytes = 1, dateAddedSec = id, mime = mime, bitrateBps = 0,
    )

    private val library = listOf(
        tr(1, "One", "Alpha"), tr(2, "Two", "Alpha"), tr(3, "Three", "Alpha"),
        tr(4, "Four", "Beta", mime = "audio/mpeg", ext = "mp3"), tr(5, "Five", "Beta", mime = "audio/mpeg", ext = "mp3"),
    )

    @Before
    fun setUp() {
        model = ApplicationProvider.getApplicationContext<StrataApp>().model
        model.installLibraryForTest(library)
    }

    @After
    fun tearDown() = model.shutdownForTest()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Saves happen on a background thread after a short delay. */
    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun writes(name: String) = model.store.writes[name] ?: 0

    // ------------------------------------------------------------------ disk writes

    @Test
    fun thePlayListIsWrittenOnceAndThePositionGoesToATinyFile() {
        val store = model.store
        val ctx = (1L..5L).toList()
        store.saveSession(Session(ctx, "All tracks", 1, 0))
        store.saveSession(Session(ctx, "All tracks", 2, 5_000))
        store.saveSession(Session(ctx, "All tracks", 2, 9_000))
        waitFor("both files written") { writes("session.json") == 1 && writes("now.json") >= 1 }
        Thread.sleep(600); idle()
        assertEquals(Session(ctx, "All tracks", 2, 9_000), store.loadSession())

        // Same list, new position: only the small file is rewritten.
        val now = writes("now.json")
        store.saveSession(Session(ctx, "All tracks", 3, 1_000))
        waitFor("position saved") { writes("now.json") > now }
        Thread.sleep(1_800); idle()
        assertEquals("the play list is not rewritten for a position change", 1, writes("session.json"))
        assertEquals(Session(ctx, "All tracks", 3, 1_000), store.loadSession())

        // A new list is written again.
        store.saveSession(Session(listOf(4L, 5L), "Beta", 4, 0))
        waitFor("new list saved") { writes("session.json") == 2 }
        Thread.sleep(600); idle()
        assertEquals(Session(listOf(4L, 5L), "Beta", 4, 0), store.loadSession())
    }

    @Test
    fun theActiveQueueIsOnlyRewrittenWhenItsOrderOrTrackChanges() {
        val q = model.createQueue("Tonight", listOf(1L, 2L, 3L))
        model.switchQueue(q); idle()
        model.snapshotActiveQueue(force = true)
        val before = model.queues
        // Still on the same track: nothing to save (the position is in the session file).
        model.snapshotActiveQueue(force = false)
        model.snapshotActiveQueue(force = false)
        assertSame(before, model.queues)

        model.engine.next(); idle()
        model.snapshotActiveQueue(force = false)
        assertEquals(1, model.queues.first { it.id == q }.index)
        assertEquals(listOf(1L, 2L, 3L), model.queues.first { it.id == q }.ids)
    }

    // ------------------------------------------------------------------ fewer wakeups

    @Test
    fun theTickerOnlyRunsFastWhileAFadeCanHappen() {
        fun tick(
            playing: Boolean = true, pos: Long = 60_000, dur: Long = 240_000, fade: Long = 0,
            sleep: Long? = null, endFade: Boolean = false, speed: Float = 1f, volume: Float = 1f,
        ) = Engine.tickIntervalFor(playing, pos, dur, fade, sleep, endFade, speed, volume)

        val slow = Engine.TICK_SLOW_MS
        val fast = Engine.TICK_FAST_MS
        assertEquals("plain playback: once a second", slow, tick())
        assertEquals("paused", slow, tick(playing = false, sleep = 10_000))
        // Fade between tracks: fast near the start and the end of a track only.
        assertEquals(fast, tick(fade = 4_000, pos = 2_000))
        assertEquals(slow, tick(fade = 4_000, pos = 60_000))
        assertEquals(fast, tick(fade = 4_000, pos = 235_500))
        assertEquals("looks further ahead at 2x", fast, tick(fade = 4_000, pos = 234_500, speed = 2f))
        // Sleep timer: fast in its last 30 seconds.
        assertEquals(slow, tick(sleep = 5 * 60_000L))
        assertEquals(fast, tick(sleep = 25_000))
        assertEquals(fast, tick(endFade = true, pos = 232_000))
        assertEquals(slow, tick(endFade = true, pos = 100_000))
        assertEquals("a volume left below 1 gets restored quickly", fast, tick(volume = 0.4f))
        assertEquals("unknown duration", slow, tick(dur = -1))
    }

    @Test
    fun onlyARealFlickCountsAsOne() {
        val m = com.strata.player.ui.FlingMeter()
        // A slow 150 px drag over ~0.9 s.
        var x = 0f
        for (k in 0..56) { m.add(1_000L + k * 16, x); x -= 150f / 56 }
        assertTrue("slow: ${m.velocity()}", kotlin.math.abs(m.velocity()) < 300f)
        // 180 px in 60 ms.
        m.reset(); x = 0f
        for (k in 0..4) { m.add(1_000L + k * 15, x); x -= 45f }
        assertTrue("flick: ${m.velocity()}", m.velocity() < -2_500f)
        // Samples with no usable timing never look like a flick.
        m.reset()
        for (k in 0..20) m.add(5_000L, -k * 10f)
        assertEquals(0f, m.velocity(), 0f)
        m.reset(); m.add(1L, 0f)
        assertEquals(0f, m.velocity(), 0f)
    }

    // ------------------------------------------------------------------ search

    @Test
    fun searchUsesPrecomputedTextAndKnowsWhatItDependsOn() {
        assertEquals(listOf(4L, 5L), model.search("mp3").map { it.id })
        assertEquals(listOf(1L, 2L, 3L), model.search("flac alpha").map { it.id })
        assertEquals(listOf(2L), model.search("artist:alpha title:two|nothing").map { it.id })
        assertEquals("the precomputed text is what plain words match", Query.searchText(library[0]), model.index.searchText[1L])
        assertTrue(model.index.searchText.values.all { it == it.lowercase() })

        assertFalse(Query.parse("alpha genre:pop").usesUserData)
        assertTrue(Query.parse("rating>=4").usesUserData)
        assertTrue(Query.parse("-fav:1").usesUserData)
        assertTrue(Query.parse("bits>=24").usesTech)
        assertFalse(Query.parse("plays>3").usesTech)

        // Values a search reads are taken from one snapshot.
        model.updateUser { it.copy(ratings = mapOf(3L to 5)) }
        assertEquals(listOf(3L), model.search("rating>=4").map { it.id })
        model.installLibraryForTest(library, mapOf(1L to Tech(96_000, 24, 2)))
        assertEquals(listOf(1L), model.search("bits>=24").map { it.id })
    }

    @Test
    fun smartListCountsMatchTheListsWithoutBuildingThem() {
        model.toggleFav(2); model.toggleFav(5); model.toggleFav(99) // 99 is not in the library
        model.updateUser { it.copy(plays = mapOf(1L to 3, 4L to 1, 77L to 9), history = listOf(4L, 1L, 77L)) }
        for (sl in SMART_LISTS) assertEquals(sl.id, model.playlistIds(sl.id).size, model.smartListCount(sl.id))
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), model.playlistIds("added"))
    }

    // ------------------------------------------------------------------ startup

    @Test
    fun theLibrarySnapshotRoundTrips() {
        val store = model.store
        store.saveLibrary(library)
        waitFor("snapshot written") { writes("library.bin") == 1 }
        assertEquals(library, store.loadLibrary())
        assertEquals("FLAC", store.loadLibrary()[0].codec)

        // A damaged or foreign file is ignored instead of crashing the start.
        val f = File(ApplicationProvider.getApplicationContext<StrataApp>().filesDir, "strata/library.bin")
        f.writeBytes(byteArrayOf(0, 0, 0, 99, 1, 2))
        assertTrue(store.loadLibrary().isEmpty())
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store.loadLibrary().isEmpty())
    }

    @Test
    fun formatInfoLoadsInTheBackgroundAndIsKept() {
        val ctx = ApplicationProvider.getApplicationContext<StrataApp>()
        model.store.saveTech(mapOf(1L to Tech(96_000, 24, 2)))
        waitFor("tech.json written") { writes("tech.json") == 1 }
        // A fresh start (what a cold launch does) picks it up without blocking.
        val fresh = AppModel(ctx)
        try {
            waitFor("loaded") { fresh.tech[1L] == Tech(96_000, 24, 2) }
        } finally {
            fresh.shutdownForTest()
        }
    }

    @Test
    fun codecIsWorkedOutOncePerTrack() {
        val t = library[3]
        assertEquals("MP3", t.codec)
        assertFalse(t.lossless)
        assertTrue(library[0].lossless)
        assertEquals("a copy with a new path gets its own codec", "FLAC", t.copy(path = "/x/y.flac", mime = "").codec)
    }
}
