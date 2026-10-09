package com.strata.player

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.strata.player.data.Order
import com.strata.player.data.RgMode
import com.strata.player.data.SortKey
import com.strata.player.data.ThemeMode
import com.strata.player.data.Track
import com.strata.player.ui.StrataRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real Compose UI on Robolectric: taps every button, switch, slider and sheet,
 * and checks the app state each one is supposed to change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class UiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var model: AppModel

    private fun tr(id: Long, title: String, artist: String, album: String, albumId: Long, genre: String, n: Int, ext: String, kbps: Int = 0) =
        Track(
            id = id,
            uri = Uri.parse("content://media/external/audio/media/$id"),
            title = title, artist = artist, album = album, albumId = albumId, albumArtist = artist,
            genre = genre, composer = "$artist Composer", year = 2024, trackNo = n, discNo = 1,
            durationMs = 200_000L + id * 1000, path = "/storage/emulated/0/Music/$artist/$album/$n - $title.$ext",
            folder = "/storage/emulated/0/Music/$artist/$album", sizeBytes = 8_000_000, dateAddedSec = 1_700_000_000 + id,
            mime = if (ext == "mp3") "audio/mpeg" else "audio/flac", bitrateBps = kbps * 1000,
        )

    private val library = listOf(
        tr(1, "Lanterns", "Mira Solano", "Tidal Hours", 10, "Dream Pop", 1, "flac"),
        tr(2, "Saltlight", "Mira Solano", "Tidal Hours", 10, "Dream Pop", 2, "flac"),
        tr(3, "Harbor Song", "Mira Solano", "Tidal Hours", 10, "Dream Pop", 3, "flac"),
        tr(4, "Brass Weather", "Odette Rue", "Copper and Salt", 20, "Jazz", 1, "flac"),
        tr(5, "Blue Hour Waltz", "Odette Rue", "Copper and Salt", 20, "Jazz", 2, "flac"),
        tr(6, "Grid City", "The Halyards", "Neon Cartography", 30, "Synthwave", 1, "mp3", 320),
        tr(7, "Coordinates", "The Halyards", "Neon Cartography", 30, "Synthwave", 2, "mp3", 320),
    )

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<StrataApp>()
        model = app.model
        model.installLibraryForTest(library)
        rule.setContent { StrataRoot(model) {} }
        rule.waitForIdle()
    }

    // ------------------------------------------------------------------ helpers

    private fun inTag(tag: String) = hasAnyAncestor(hasTestTag(tag))

    private fun node(m: SemanticsMatcher): SemanticsNodeInteraction = rule.onAllNodes(m).onFirst()

    private fun SemanticsNodeInteraction.tap() {
        try { performScrollTo() } catch (_: Throwable) { }
        performClick()
        rule.waitForIdle()
    }

    private fun tapText(text: String, scope: String? = null) =
        node(if (scope != null) hasText(text) and inTag(scope) else hasText(text)).tap()

    private fun tapDesc(desc: String, scope: String? = null) =
        node(if (scope != null) hasContentDescription(desc) and inTag(scope) else hasContentDescription(desc)).tap()

    private fun idle(block: () -> Unit) { rule.runOnIdle(block); rule.waitForIdle() }

    private fun startPlaying(id: Long = 2) {
        idle { model.play(library.map { it.id }, id, "All tracks") }
    }

    // ------------------------------------------------------------------ library

    @Test
    fun libraryTabsSortAndColumns() {
        node(hasText("Library")).assertExists()
        node(hasText("Saltlight")).assertExists()

        tapText("Albums"); assertEquals(LibTab.ALBUMS, model.libTab); node(hasText("Tidal Hours")).assertExists()
        tapText("Artists"); assertEquals(LibTab.ARTISTS, model.libTab); node(hasText("Odette Rue")).assertExists()
        tapText("Genres"); assertEquals(LibTab.GENRES, model.libTab); node(hasText("Synthwave")).assertExists()
        tapText("Folders"); assertEquals(LibTab.FOLDERS, model.libTab); node(hasText("Neon Cartography")).assertExists()
        tapText("Composers"); assertEquals(LibTab.COMPOSERS, model.libTab); node(hasText("Odette Rue Composer")).assertExists()
        tapText("Tracks"); assertEquals(LibTab.TRACKS, model.libTab)

        tapText("Title"); assertEquals(SortKey.ARTIST, model.settings.sort)
        tapText("Artist"); assertEquals(SortKey.ALBUM, model.settings.sort)

        tapDesc("Toggle column view"); assertTrue(model.settings.columns)
        rule.onNode(hasText("FORMAT")).assertExists()
        tapDesc("Toggle column view"); assertFalse(model.settings.columns)
    }

    @Test
    fun openAlbumArtistGenreFolderComposerPages() {
        tapText("Albums"); tapText("Copper and Salt")
        assertEquals(Detail.AlbumD(20), model.details.last())
        tapText("Play", "detail")
        assertEquals(4L, model.engine.currentId)
        assertEquals("Copper and Salt", model.engine.ctxName)
        tapText("Shuffle", "detail")
        assertTrue(model.settings.order.isShuffle)
        tapDesc("Back", "detail"); assertTrue(model.details.isEmpty())

        tapText("Artists"); tapText("The Halyards"); assertEquals(Detail.ArtistD("The Halyards"), model.details.last())
        tapDesc("Back", "detail")
        tapText("Genres"); tapText("Jazz"); assertEquals(Detail.GenreD("Jazz"), model.details.last())
        tapDesc("Back", "detail")
        tapText("Folders"); tapText("Tidal Hours"); assertTrue(model.details.last() is Detail.FolderD)
        tapDesc("Back", "detail")
        tapText("Composers"); tapText("Mira Solano Composer"); assertEquals(Detail.ComposerD("Mira Solano Composer"), model.details.last())
        tapDesc("Back", "detail"); assertTrue(model.details.isEmpty())
    }

    @Test
    fun tapTrackPlaysItAndMiniPlayerWorks() {
        tapText("Saltlight")
        assertEquals(2L, model.engine.currentId)
        rule.onNode(hasTestTag("miniplayer")).assertExists()
        assertTrue(model.engine.player.playWhenReady)

        idle { model.engine.player.pause() }
        assertFalse(model.engine.playIntent)
        tapDesc("Play", "miniplayer")
        assertTrue(model.engine.player.playWhenReady)

        val cur = model.engine.currentId
        tapDesc("Next track", "miniplayer")
        assertNotEquals(cur, model.engine.currentId)

        node(hasText(model.track(model.engine.currentId)!!.title) and inTag("miniplayer")).tap()
        assertTrue(model.nowOpen)
        rule.onNode(hasTestTag("nowplaying")).assertExists()
    }

    // ------------------------------------------------------------------ now playing

    @Test
    fun nowPlayingEveryControl() {
        startPlaying(2)
        idle { model.nowOpen = true }
        rule.onNode(hasTestTag("nowplaying")).assertExists()

        tapText("Lyrics", "nowplaying"); assertEquals(NpView.LYRICS, model.npView)
        tapText("Info", "nowplaying"); assertEquals(NpView.INFO, model.npView)
        node(hasText("Codec") and inTag("nowplaying")).assertExists()
        tapText("Artwork", "nowplaying"); assertEquals(NpView.ART, model.npView)

        // The visualizer animates every frame, so drive the clock by hand while it is on screen.
        rule.mainClock.autoAdvance = false
        node(hasText("Visualizer") and inTag("nowplaying")).performClick()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(NpView.VIZ, model.npView)
        node(hasText("SPECTRUM · 32 BANDS")).assertExists()
        node(hasText("Artwork") and inTag("nowplaying")).performClick()
        rule.mainClock.advanceTimeBy(2000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(NpView.ART, model.npView)

        tapDesc("Add to favorites", "nowplaying"); assertTrue(model.isFav(2))
        tapDesc("Remove from favorites", "nowplaying"); assertFalse(model.isFav(2))

        tapDesc("Shuffle: Default", "nowplaying"); assertEquals(Order.SHUFFLE_TRACKS, model.settings.order)
        tapDesc("Repeat: Shuffle tracks", "nowplaying"); assertEquals(Order.REPEAT_PLAYLIST, model.settings.order)
        tapDesc("Repeat: Repeat playlist", "nowplaying"); assertEquals(Order.REPEAT_TRACK, model.settings.order)
        tapDesc("Repeat: Repeat track", "nowplaying"); assertEquals(Order.DEFAULT, model.settings.order)

        tapText("Order: Default ▾", "nowplaying"); assertEquals(Sheet.OrderSheet, model.sheet)
        tapText("Shuffle albums", "sheet"); assertEquals(Order.SHUFFLE_ALBUMS, model.settings.order); assertNull(model.sheet)
        idle { model.updateSettings { it.copy(order = Order.DEFAULT) } }

        val a = model.engine.currentId
        tapDesc("Next track", "nowplaying"); assertNotEquals(a, model.engine.currentId)
        val b = model.engine.currentId
        tapDesc("Previous track", "nowplaying"); assertNotEquals(b, model.engine.currentId)

        idle { model.engine.player.pause() }
        tapDesc("Play", "nowplaying")
        assertTrue(model.engine.player.playWhenReady)

        tapDesc("More options", "nowplaying"); assertTrue(model.sheet is Sheet.TrackMenu)
        idle { model.sheet = null }
        tapText("Output", "nowplaying"); assertEquals(Sheet.Output, model.sheet); idle { model.sheet = null }
        tapText("Sleep", "nowplaying"); assertEquals(Sheet.Sleep, model.sheet); idle { model.sheet = null }
        tapText("1.0×", "nowplaying"); assertEquals(Sheet.Speed, model.sheet); idle { model.sheet = null }
        tapText("Queue", "nowplaying"); assertEquals(Sheet.Queue, model.sheet); idle { model.sheet = null }

        tapText("Sound", "nowplaying"); assertEquals(Tab.SOUND, model.tab); assertFalse(model.nowOpen)

        idle { model.nowOpen = true }
        tapDesc("Close now playing", "nowplaying"); assertFalse(model.nowOpen)
    }

    @Test
    fun overlaysDoNotLeakTapsToScreenBelow() {
        startPlaying(2)
        idle { model.nowOpen = true }
        // "Saltlight" rows of the library sit underneath the player; tapping the player's empty area must not play them.
        val cur = model.engine.currentId
        rule.onNode(hasTestTag("nowplaying")).performTouchInput { click(Offset(width / 2f, height * 0.3f)) }
        rule.waitForIdle()
        assertEquals(cur, model.engine.currentId)
        assertTrue(model.nowOpen)
    }

    // ------------------------------------------------------------------ track menu

    @Test
    fun trackMenuEveryAction() {
        startPlaying(1)
        tapDesc("More options for Saltlight"); assertEquals(Sheet.TrackMenu(2), model.sheet)
        tapText("Play next", "sheet")
        assertNull(model.sheet)
        assertEquals(1, model.engine.queuedItemsAfterCurrent().size)

        tapDesc("More options for Harbor Song")
        tapText("Add to playback queue", "sheet")
        assertEquals(2, model.engine.queuedItemsAfterCurrent().size)

        tapDesc("More options for Saltlight")
        tapDesc("Rate 4 stars", "sheet")
        assertEquals(4, model.rating(model.track(2)!!))
        idle { model.sheet = null }

        tapDesc("More options for Saltlight")
        tapText("Add to playlist…", "sheet"); assertEquals(Sheet.AddToPlaylist(2), model.sheet)
        tapText("New playlist", "sheet"); assertTrue(model.sheet is Sheet.NewPlaylist)
        node(hasSetTextAction() and inTag("sheet")).performTextInput("Road trip")
        tapText("Create", "sheet")
        val pl = model.user.playlists.firstOrNull { it.name == "Road trip" }
        assertNotNull(pl); assertEquals(listOf(2L), pl!!.ids)

        tapDesc("More options for Saltlight")
        tapText("Add to playlist…", "sheet")
        tapText("Road trip", "sheet")
        assertEquals(listOf(2L), model.user.playlists.first { it.name == "Road trip" }.ids) // no duplicate

        tapDesc("More options for Saltlight")
        tapText("Go to album", "sheet"); assertEquals(Detail.AlbumD(10), model.details.last())
        tapDesc("Back", "detail")

        tapDesc("More options for Saltlight")
        tapText("Go to artist", "sheet"); assertEquals(Detail.ArtistD("Mira Solano"), model.details.last())
        tapDesc("Back", "detail")

        tapDesc("More options for Saltlight")
        tapText("Properties", "sheet"); assertEquals(2L, model.propsId); assertEquals(1, model.propsTab)
        node(hasText("File name") and inTag("properties")).assertExists()
        tapText("Statistics", "properties"); assertEquals(2, model.propsTab)
        tapText("Metadata", "properties"); assertEquals(0, model.propsTab)
        node(hasSetTextAction() and inTag("properties")).performTextReplacement("Saltlight (Live)")
        tapText("Save", "properties")
        rule.waitUntil(5000) { model.track(2)?.title == "Saltlight (Live)" }
        tapDesc("Back", "properties"); assertNull(model.propsId)

        tapDesc("More options for Brass Weather")
        tapText("Edit tags", "sheet"); assertEquals(4L, model.propsId); assertEquals(0, model.propsTab)
        tapDesc("Back", "properties")
    }

    @Test
    fun playlistTrackMenuMoveAndRemove() {
        val id = model.createPlaylist("Mix", null, 1)
        idle { model.addToPlaylist(id, 2); model.addToPlaylist(id, 3) }
        idle { model.open(Detail.PlaylistD(id)) }
        tapDesc("More options for Lanterns", "detail")
        tapText("Move down in playlist", "sheet")
        assertEquals(listOf(2L, 1L, 3L), model.user.playlists.first { it.id == id }.ids)
        idle { model.sheet = null }
        tapDesc("More options for Harbor Song", "detail")
        tapText("Remove from playlist", "sheet")
        assertEquals(listOf(2L, 1L), model.user.playlists.first { it.id == id }.ids)
    }

    // ------------------------------------------------------------------ sheets

    @Test
    fun sleepSpeedOrderOutputQueueSheets() {
        startPlaying(1)
        idle { model.sheet = Sheet.Sleep }
        tapText("15 minutes", "sheet")
        assertEquals(15, model.engine.sleepChoice); assertNotNull(model.engine.sleepAtMs)
        idle { model.sheet = Sheet.Sleep }
        tapText("End of current track", "sheet"); assertTrue(model.engine.sleepEndOfTrack)
        idle { model.sheet = Sheet.Sleep }
        tapText("Off", "sheet"); assertNull(model.engine.sleepAtMs)

        idle { model.sheet = Sheet.Speed }
        tapText("1.25×", "sheet"); assertEquals(1.25f, model.settings.speed)
        tapText("Keep pitch", "sheet"); assertFalse(model.settings.keepPitch)

        idle { model.sheet = Sheet.OrderSheet }
        tapText("Random", "sheet"); assertEquals(Order.RANDOM, model.settings.order)

        idle { model.sheet = Sheet.Output }
        tapText("System default", "sheet"); assertEquals(0, model.engine.preferredDeviceId)

        idle { model.engine.addToQueue(model.track(5)!!); model.engine.addToQueue(model.track(6)!!) }
        idle { model.sheet = Sheet.Queue }
        node(hasText("Blue Hour Waltz") and inTag("sheet")).assertExists()
        tapDesc("Remove Blue Hour Waltz from queue", "sheet")
        assertEquals(1, model.engine.queuedItemsAfterCurrent().size)
        tapText("Clear queue", "sheet")
        assertEquals(0, model.engine.queuedItemsAfterCurrent().size)
    }

    // ------------------------------------------------------------------ settings

    @Test
    fun settingsEveryControl() {
        tapDesc("Settings"); assertTrue(model.settingsOpen)
        tapText("Light", "settings"); assertEquals(ThemeMode.LIGHT, model.settings.theme)
        tapText("Dark", "settings"); assertEquals(ThemeMode.DARK, model.settings.theme)
        tapText("System", "settings"); assertEquals(ThemeMode.SYSTEM, model.settings.theme)
        tapText("Columns", "settings"); assertTrue(model.settings.columns)
        tapText("Comfortable", "settings"); assertFalse(model.settings.columns)
        tapText("Color from album art", "settings"); assertFalse(model.settings.colorFromArt)
        tapText("Color from album art", "settings"); assertTrue(model.settings.colorFromArt)
        tapText("Pause on disconnect", "settings"); assertFalse(model.settings.pauseOnDisconnect)
        tapDesc("Back", "settings"); assertFalse(model.settingsOpen)
    }

    // ------------------------------------------------------------------ sound

    @Test
    fun soundScreenEveryControl() {
        node(hasTestTag("nav-sound")).tap(); assertEquals(Tab.SOUND, model.tab)

        val eq = model.settings.eqOn
        node(hasTestTag("eq-switch")).tap(); assertNotEquals(eq, model.settings.eqOn)
        node(hasTestTag("eq-switch")).tap(); assertEquals(eq, model.settings.eqOn)

        tapText("Rock"); assertEquals("Rock", model.settings.preset); assertEquals(5f, model.settings.bands[0])
        tapText("Reset to flat"); assertEquals("Flat", model.settings.preset); assertTrue(model.settings.bands.all { it == 0f })

        node(hasContentDescription("Equalizer preamp")).apply { performScrollTo() }
            .performTouchInput { click(Offset(width * 0.95f, centerY)) }
        rule.waitForIdle(); assertTrue("preamp ${model.settings.preamp}", model.settings.preamp > 6f)
        node(hasContentDescription("Equalizer preamp")).performTouchInput { swipe(Offset(width * 0.9f, centerY), Offset(width * 0.1f, centerY), 300) }
        rule.waitForIdle(); assertTrue("preamp ${model.settings.preamp}", model.settings.preamp < -6f)

        node(hasTestTag("band-1k")).apply { performScrollTo() }.performTouchInput { click(Offset(centerX, height * 0.15f)) }
        rule.waitForIdle(); assertTrue("band ${model.settings.bands[5]}", model.settings.bands[5] > 5f); assertEquals("Custom", model.settings.preset)
        node(hasTestTag("band-31")).performTouchInput { swipe(Offset(centerX, height * 0.2f), Offset(centerX, height * 0.85f), 300) }
        rule.waitForIdle(); assertTrue("band ${model.settings.bands[0]}", model.settings.bands[0] < -5f)

        tapText("Stereo widening"); assertTrue(model.settings.widen)
        tapText("Advanced limiter"); assertFalse(model.settings.limiter)
        tapText("Mono downmix"); assertTrue(model.settings.mono)
        node(hasContentDescription("Balance")).apply { performScrollTo() }.performTouchInput { click(Offset(width * 0.1f, centerY)) }
        rule.waitForIdle(); assertTrue(model.settings.balance < -0.5f)

        tapText("Track"); assertEquals(RgMode.TRACK, model.settings.rgMode)
        tapText("Off"); assertEquals(RgMode.OFF, model.settings.rgMode)
        node(hasContentDescription("Preamp with ReplayGain")).apply { performScrollTo() }.performTouchInput { click(Offset(width * 0.9f, centerY)) }
        rule.waitForIdle(); assertTrue(model.settings.rgPreamp > 5f)
        tapText("Prevent clipping"); assertFalse(model.settings.rgPreventClip)

        tapText("Skip silence"); assertTrue(model.settings.skipSilence)
        node(hasContentDescription("Fade between tracks")).apply { performScrollTo() }.performTouchInput { click(Offset(width * 0.9f, centerY)) }
        rule.waitForIdle(); assertTrue(model.settings.fadeSec >= 8)
        tapText("1.5"); assertEquals(1.5f, model.settings.speed)
        tapText("Keep pitch"); assertFalse(model.settings.keepPitch)

        val chain = model.settings.chain
        rule.onAllNodes(hasContentDescription("Move later")).onFirst().tap()
        assertNotEquals(chain, model.settings.chain)

        tapText("Change"); assertEquals(Sheet.Output, model.sheet)
    }

    // ------------------------------------------------------------------ search

    @Test
    fun searchTypingChipsAndResults() {
        node(hasTestTag("nav-search")).tap(); assertEquals(Tab.SEARCH, model.tab)
        node(hasSetTextAction()).performTextInput("jazz")
        rule.waitForIdle()
        assertEquals("jazz", model.query)
        node(hasText("Brass Weather")).assertExists()
        tapText("Brass Weather")
        assertEquals(4L, model.engine.currentId)
        assertTrue(model.user.recent.contains("jazz"))

        tapDesc("Clear search"); assertEquals("", model.query)
        tapText("bits>=24"); assertEquals("bits>=24", model.query)
        tapDesc("Clear search")
        tapText("Jazz"); assertEquals(Detail.GenreD("Jazz"), model.details.last())
    }

    // ------------------------------------------------------------------ playlists

    @Test
    fun playlistsCreateRenameDeleteAndSmartLists() {
        node(hasTestTag("nav-playlists")).tap(); assertEquals(Tab.PLAYLISTS, model.tab)

        tapText("Favorites"); assertEquals(Detail.PlaylistD("fav"), model.details.last())
        tapDesc("Back", "detail")
        tapText("Recently added"); assertEquals(Detail.PlaylistD("added"), model.details.last())
        node(hasText("Play") and inTag("detail")).tap()
        assertNotEquals(-1L, model.engine.currentId)
        tapDesc("Back", "detail")

        tapText("New"); assertEquals(Sheet.NewPlaylist(null, false), model.sheet)
        node(hasSetTextAction() and inTag("sheet")).performTextInput("Evening")
        tapText("Create", "sheet")
        val p = model.user.playlists.first { it.name == "Evening" }
        assertEquals(Detail.PlaylistD(p.id), model.details.last())

        tapDesc("Playlist options", "detail"); assertEquals(Sheet.PlaylistMenu(p.id), model.sheet)
        node(hasSetTextAction() and inTag("sheet")).performTextReplacement("Late evening")
        tapText("Save", "sheet")
        assertEquals("Late evening", model.user.playlists.first { it.id == p.id }.name)

        tapDesc("Playlist options", "detail")
        tapText("Delete playlist", "sheet")
        tapText("Tap again to delete “Late evening”", "sheet")
        assertTrue(model.user.playlists.none { it.id == p.id })

        tapText("New auto playlist from a query")
        val fields = rule.onAllNodes(hasSetTextAction() and inTag("sheet"))
        fields[1].performTextInput("genre:jazz")
        tapText("Create", "sheet")
        val auto = model.user.playlists.last()
        assertEquals("genre:jazz", auto.query)
        assertEquals(listOf(4L, 5L), model.playlistIds(auto.id).sorted())
    }

    @Test
    fun bottomNavigationSwitchesTabs() {
        node(hasTestTag("nav-playlists")).tap(); assertEquals(Tab.PLAYLISTS, model.tab)
        node(hasTestTag("nav-search")).tap(); assertEquals(Tab.SEARCH, model.tab)
        node(hasTestTag("nav-sound")).tap(); assertEquals(Tab.SOUND, model.tab)
        node(hasTestTag("nav-library")).tap(); assertEquals(Tab.LIBRARY, model.tab)
        tapDesc("Search"); assertEquals(Tab.SEARCH, model.tab)
    }
}
