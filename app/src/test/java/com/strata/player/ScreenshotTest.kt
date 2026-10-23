package com.strata.player

import android.net.Uri
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.strata.player.data.Tech
import com.strata.player.data.ThemeMode
import com.strata.player.data.Track
import com.strata.player.audio.Waveform
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders the real app with a sample library and saves PNG screenshots for the README.
 * Runs only with `./gradlew testDebugUnitTest -Pscreenshots`; images go to `out/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var model: AppModel
    private val outDir = File(System.getProperty("strata.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val musicDir: File = Files.createTempDirectory("strata-music").toFile()

    private fun tr(
        id: Long, title: String, artist: String, album: String, albumId: Long, genre: String,
        n: Int, year: Int, sec: Int, ext: String, kbps: Int = 0, albumArtist: String = artist,
    ) = Track(
        id = id, uri = Uri.parse("content://media/external/audio/media/$id"),
        title = title, artist = artist, album = album, albumId = albumId, albumArtist = albumArtist,
        genre = genre, composer = albumArtist, year = year, trackNo = n, discNo = 1, durationMs = sec * 1000L,
        path = "${musicDir.absolutePath}/$album/$title.$ext", folder = "${musicDir.absolutePath}/$album",
        sizeBytes = if (ext == "flac") sec * 290_000L else sec * 40_000L, dateAddedSec = 1_760_000_000L + id * 3600,
        mime = if (ext == "mp3") "audio/mpeg" else "audio/flac", bitrateBps = kbps * 1000,
    )

    // Album ids are picked so the generated covers use different styles.
    private val library = listOf(
        tr(1, "Lanterns", "Mira Solano", "Tidal Hours", 32, "Dream Pop", 1, 2024, 245, "flac"),
        tr(2, "Saltlight", "Mira Solano", "Tidal Hours", 32, "Dream Pop", 2, 2024, 231, "flac"),
        tr(3, "Harbor Song", "Mira Solano", "Tidal Hours", 32, "Dream Pop", 3, 2024, 267, "flac"),
        tr(4, "Undertow", "Mira Solano", "Tidal Hours", 32, "Dream Pop", 4, 2024, 298, "flac"),
        tr(5, "Brass Weather", "Odette Rue", "Copper and Salt", 4, "Jazz", 1, 2023, 312, "flac"),
        tr(6, "Blue Hour Waltz", "Odette Rue", "Copper and Salt", 4, "Jazz", 2, 2023, 274, "flac"),
        tr(7, "Paper Boats", "Odette Rue feat. Mira Solano", "Copper and Salt", 4, "Jazz", 3, 2023, 226, "flac", albumArtist = "Odette Rue"),
        tr(8, "Late Tram", "Odette Rue", "Copper and Salt", 4, "Jazz", 4, 2023, 341, "flac"),
        tr(9, "Grid City", "The Halyards", "Neon Cartography", 29, "Synthwave", 1, 2022, 218, "mp3", 320),
        tr(10, "Coordinates", "The Halyards", "Neon Cartography", 29, "Synthwave", 2, 2022, 236, "mp3", 320),
        tr(11, "Night Atlas", "The Halyards", "Neon Cartography", 29, "Synthwave", 3, 2022, 284, "mp3", 320),
        tr(12, "Moss", "Juniper Vale", "Field Recordings", 7, "Ambient", 1, 2025, 402, "flac"),
        tr(13, "Low Clouds", "Juniper Vale", "Field Recordings", 7, "Ambient", 2, 2025, 377, "flac"),
        tr(14, "River Glass", "Juniper Vale & The Halyards", "Field Recordings", 7, "Ambient", 3, 2025, 356, "flac", albumArtist = "Juniper Vale"),
        tr(15, "Ferry Lights", "Arlo Finch", "Night Ferries", 22, "Indie Folk", 1, 2021, 203, "mp3", 256),
        tr(16, "Quiet Harbour", "Arlo Finch", "Night Ferries", 22, "Indie Folk", 2, 2021, 249, "mp3", 256),
    )

    private val tech: Map<Long, Tech> = library.filter { it.mime == "audio/flac" }.associate { t ->
        t.id to when (t.albumId) {
            32L -> Tech(96_000, 24, 2)
            7L -> Tech(48_000, 24, 2)
            else -> Tech(44_100, 16, 2)
        }
    }

    @Before
    fun setUp() {
        // A sidecar .lrc for "Lanterns", so the lyrics view has something to show.
        File(musicDir, "Tidal Hours").mkdirs()
        File(musicDir, "Tidal Hours/Lanterns.lrc").writeText(
            listOf(
                "[00:58.00]Paper moons over the water",
                "[01:06.00]Every window holds a light",
                "[01:14.00]I keep a lantern for the harbor",
                "[01:22.00]In case you sail home tonight",
                "[01:31.00]Salt on the railings, salt on the morning",
                "[01:39.00]Tide pulling slowly at the shore",
                "[01:47.00]I keep a lantern for the harbor",
                "[01:55.00]And a key beneath the door",
                "[02:04.00]Paper moons over the water",
                "[02:12.00]Every window holds a light",
            ).joinToString("\n"),
        )
        model = ApplicationProvider.getApplicationContext<StrataApp>().model
        model.installLibraryForTest(library, tech)
        model.updateSettings { it.copy(theme = ThemeMode.DARK) }
        model.updateUser { u ->
            u.copy(
                favorites = setOf(1L, 6L, 7L, 12L),
                ratings = mapOf(1L to 5, 3L to 4, 6L to 5, 9L to 4, 12L to 4, 13L to 5),
                plays = mapOf(1L to 42, 2L to 18, 3L to 27, 6L to 31, 9L to 12, 12L to 22, 13L to 9, 7L to 15),
            )
        }
        // A plausible waveform for the seek bar (decoding real audio isn't possible here).
        for (t in library) {
            val bytes = ByteArray(Waveform.BARS) { i ->
                val v = 0.35 + 0.45 * abs(sin(i * 0.37 + t.id)) * (0.6 + 0.4 * abs(sin(i * 0.11)))
                (v.coerceIn(0.08, 1.0) * 255).toInt().toByte()
            }
            model.store.waveFile(t.id).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }
        rule.launchStrata(model)
    }

    @After
    fun tearDown() = model.shutdownForTest()

    private fun idle(block: () -> Unit) { rule.runOnIdle(block); rule.waitForIdle() }

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(outDir, "$name.png").absolutePath)
    }

    private val ids get() = model.sortedTracks(model.settings.sort).map { it.id }

    private fun playLanterns(atMs: Long = 102_000) {
        idle { model.play(ids, 1, "All tracks") }
        idle { model.engine.player.pause(); model.engine.seekTo(atMs) }
    }

    @Test
    fun library() {
        playLanterns()
        shot("01_library")
    }

    @Test
    fun albums() {
        playLanterns()
        idle { model.libTab = LibTab.ALBUMS }
        shot("02_albums")
    }

    @Test
    fun artistWithFeatures() {
        playLanterns()
        idle { model.open(Detail.ArtistD("Mira Solano")) }
        shot("03_artist")
    }

    @Test
    fun nowPlaying() {
        playLanterns()
        idle { model.nowOpen = true }
        shot("04_now_playing")
    }

    @Test
    fun lyrics() {
        playLanterns(75_000)
        idle { model.nowOpen = true; model.npView = NpView.LYRICS }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("I keep a lantern for the harbor")).fetchSemanticsNodes().isNotEmpty() }
        shot("05_lyrics")
    }

    @Test
    fun queue() {
        playLanterns()
        idle {
            model.engine.addToQueue(model.track(13)!!)
            model.engine.addToQueue(model.track(6)!!)
        }
        idle { model.nowOpen = true; model.sheet = Sheet.Queue }
        shot("06_queue")
    }

    @Test
    fun multiSelect() {
        playLanterns()
        val list = ids
        idle {
            model.startSelection(list, 6)
            model.toggleSelected(list, 13)
            model.toggleSelected(list, 7)
            model.toggleSelected(list, 12)
        }
        shot("07_multi_select")
    }

    @Test
    fun songPicker() {
        val pid = model.createPlaylist("Sunday morning", null, listOf(12L))
        idle { model.selectTab(Tab.PLAYLISTS); model.open(Detail.PlaylistD(pid)); model.pickerFor = pid }
        for (title in listOf("Blue Hour Waltz", "Harbor Song", "Low Clouds")) {
            rule.onAllNodes(hasText(title) and hasAnyAncestor(hasTestTag("picker"))).onFirst().performClick()
            rule.waitForIdle()
        }
        shot("08_song_picker")
    }

    @Test
    fun sound() {
        idle { model.updateSettings { it.copy(preset = "Rock", bands = com.strata.player.data.EQ_PRESETS.getValue("Rock")) } }
        idle { model.selectTab(Tab.SOUND) }
        shot("09_sound")
    }

    @Test
    fun search() {
        idle { model.selectTab(Tab.SEARCH); model.query = "bits>=24 rating>=4" }
        shot("10_search")
    }

    @Test
    fun lightTheme() {
        playLanterns()
        idle { model.updateSettings { it.copy(theme = ThemeMode.LIGHT) } }
        shot("11_library_light")
    }
}
