package com.strata.player

import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.strata.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Artist pages with featured artists, multi-select and the fast playlist builders, driven through the real UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class ArtistsAndSelectionUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var model: AppModel

    private fun tr(id: Long, title: String, artist: String, album: String) = Track(
        id = id, uri = Uri.parse("content://media/external/audio/media/$id"),
        title = title, artist = artist, album = album, albumId = 100 + id, albumArtist = artist,
        genre = "Hip Hop", composer = "", year = 2010, trackNo = 1, discNo = 1,
        durationMs = 200_000L + id * 1000, path = "/storage/emulated/0/Music/$album/$title.mp3",
        folder = "/storage/emulated/0/Music/$album", sizeBytes = 8_000_000, dateAddedSec = 1_700_000_000 + id,
        mime = "audio/mpeg", bitrateBps = 320_000,
    )

    // Title order: Diamonds(3), Forgot About Dre(4), Lose Yourself(1), Love the Way You Lie(2),
    // Mrs. Robinson(6), The Monster (feat. Rihanna)(5), Thunderstruck(7)
    private val library = listOf(
        tr(1, "Lose Yourself", "Eminem", "8 Mile"),
        tr(2, "Love the Way You Lie", "Eminem feat. Rihanna", "Recovery"),
        tr(3, "Diamonds", "Rihanna", "Unapologetic"),
        tr(4, "Forgot About Dre", "Eminem & Dr. Dre", "2001"),
        tr(5, "The Monster (feat. Rihanna)", "Eminem", "MMLP2"),
        tr(6, "Mrs. Robinson", "Simon & Garfunkel", "Bookends"),
        tr(7, "Thunderstruck", "AC/DC", "The Razors Edge"),
    )

    @Before
    fun setUp() {
        model = ApplicationProvider.getApplicationContext<StrataApp>().model
        model.installLibraryForTest(library)
        rule.launchStrata(model)
    }

    @After
    fun tearDown() = model.shutdownForTest()

    private fun inTag(tag: String) = hasAnyAncestor(hasTestTag(tag))
    private fun node(m: SemanticsMatcher): SemanticsNodeInteraction = rule.onAllNodes(m).onFirst()
    private fun SemanticsNodeInteraction.tap() {
        try { performScrollTo() } catch (_: Throwable) { }
        performClick()
        rule.waitForIdle()
    }
    private fun tapText(text: String, scope: String? = null) = node(if (scope != null) hasText(text) and inTag(scope) else hasText(text)).tap()
    private fun tapDesc(desc: String, scope: String? = null) =
        node(if (scope != null) hasContentDescription(desc) and inTag(scope) else hasContentDescription(desc)).tap()
    private fun longPress(text: String, scope: String? = null) {
        node(if (scope != null) hasText(text) and inTag(scope) else hasText(text)).apply {
            try { performScrollTo() } catch (_: Throwable) { }
        }.performTouchInput { longClick() }
        rule.waitForIdle()
    }
    private fun idle(block: () -> Unit) { rule.runOnIdle(block); rule.waitForIdle() }
    private fun ids(name: String) = model.user.playlists.first { it.name == name }.ids

    // ------------------------------------------------------------------ artists

    @Test
    fun featuredArtistsShowUpOnEveryArtistPage() {
        assertEquals(listOf(1L, 2L, 4L, 5L), model.index.tracksOfArtist("Eminem").map { it.id }.sorted())
        assertEquals(listOf(2L, 3L, 5L), model.index.tracksOfArtist("Rihanna").map { it.id }.sorted())
        // The query language sees featured artists too, so smart playlists like artist:rihanna work.
        assertEquals(listOf(2L, 3L, 5L), model.search("artist:rihanna").map { it.id }.sorted())

        tapText("Artists")
        val rows = model.index.artists.map { it.first }
        assertEquals(listOf("AC/DC", "Dr. Dre", "Eminem", "Rihanna", "Simon & Garfunkel"), rows)
        rule.onAllNodes(hasText("Eminem feat. Rihanna")).assertCountEquals(0)
        node(hasText("4 albums · 4 tracks")).assertExists()

        tapText("Eminem"); assertEquals(Detail.ArtistD("Eminem"), model.details.last())
        for (title in listOf("Lose Yourself", "Love the Way You Lie", "Forgot About Dre", "The Monster (feat. Rihanna)")) {
            node(hasText(title) and inTag("detail")).assertExists()
        }
        rule.onAllNodes(hasText("Diamonds") and inTag("detail")).assertCountEquals(0)
        tapDesc("Back", "detail")

        tapText("Rihanna"); assertEquals(Detail.ArtistD("Rihanna"), model.details.last())
        node(hasText("Love the Way You Lie") and inTag("detail")).assertExists()
        node(hasText("The Monster (feat. Rihanna)") and inTag("detail")).assertExists()
        node(hasText("Diamonds") and inTag("detail")).assertExists()
        node(hasText("featured on 2 tracks", substring = true) and inTag("detail")).assertExists()

        // The track menu offers each performer.
        tapDesc("More options for Love the Way You Lie", "detail")
        node(hasText("Go to Eminem") and inTag("sheet")).assertExists()
        tapText("Go to Rihanna", "sheet")
        assertNull(model.sheet)
        assertEquals(Detail.ArtistD("Rihanna"), model.details.last())
        idle { model.details.clear() }

        tapText("Dr. Dre"); node(hasText("Forgot About Dre") and inTag("detail")).assertExists()
        tapDesc("Back", "detail")

        // Search lists every performer of the matching tracks.
        node(hasTestTag("nav-search")).tap()
        node(hasSetTextAction()).performTextInput("monster")
        rule.waitForIdle()
        node(hasText("Rihanna")).assertExists()
        node(hasText("Eminem")).assertExists()
    }

    @Test
    fun separatingArtistsCanBeTurnedOffAndBandsKeptTogether() {
        tapDesc("Settings"); assertTrue(model.settingsOpen)
        tapText("Separate featured artists", "settings")
        assertFalse(model.settings.splitArtists)
        rule.waitUntil(5000) { model.index.artists.any { it.first == "Eminem feat. Rihanna" } }
        assertEquals(1, model.index.tracksOfArtist("Rihanna").size)

        tapText("Separate featured artists", "settings")
        rule.waitUntil(5000) { model.index.tracksOfArtist("Rihanna").size == 3 }

        // A user rule keeps "Eminem & Dr. Dre" as one artist.
        node(hasSetTextAction() and inTag("settings")).apply { performScrollTo() }.performTextInput("Eminem & Dr. Dre")
        rule.waitForIdle()
        tapText("Save", "settings")
        assertEquals("Eminem & Dr. Dre", model.settings.keepTogether)
        rule.waitUntil(5000) { model.index.artists.any { it.first == "Eminem & Dr. Dre" } }
        assertTrue(model.index.tracksOfArtist("Dr. Dre").isEmpty())
        tapDesc("Back", "settings")
    }

    // ------------------------------------------------------------------ multi-select

    @Test
    fun longPressSelectsManyTracksAndCreatesAPlaylist() {
        longPress("Lose Yourself")
        assertTrue(model.selecting)
        assertEquals(listOf(1L), model.selected)
        rule.onNode(hasTestTag("selectionbar")).assertExists()
        rule.onAllNodes(hasTestTag("nav-library")).assertCountEquals(0)

        tapText("Diamonds"); tapText("Thunderstruck")
        assertEquals(listOf(3L, 1L, 7L), model.selected)        // list order, not tap order
        node(hasText("3 selected")).assertExists()
        assertEquals(-1L, model.engine.currentId)               // taps select, they don't play

        // Long-press while selecting picks the whole range from the last touched row.
        longPress("Mrs. Robinson")
        assertEquals(listOf(3L, 1L, 6L, 5L, 7L), model.selected)
        tapText("Mrs. Robinson")
        assertEquals(listOf(3L, 1L, 5L, 7L), model.selected)

        tapText("Playlist", "selectionbar")
        assertEquals(Sheet.AddToPlaylist(listOf(3L, 1L, 5L, 7L)), model.sheet)
        tapText("New playlist", "sheet")
        assertEquals(Sheet.NewPlaylist(listOf(3L, 1L, 5L, 7L), false), model.sheet)
        node(hasText("4 tracks will be added.") and inTag("sheet")).assertExists()
        node(hasSetTextAction() and inTag("sheet")).performTextInput("Workout")
        tapText("Create", "sheet")
        assertEquals(listOf(3L, 1L, 5L, 7L), ids("Workout"))
        assertFalse(model.selecting)
        assertNull(model.sheet)
        assertNull(model.pickerFor)
        rule.onNode(hasTestTag("nav-library")).assertExists()

        // Add more to the same playlist: duplicates are skipped.
        longPress("Lose Yourself"); tapText("Forgot About Dre")
        tapText("Playlist", "selectionbar")
        node(hasText("1 of these already in", substring = true) and inTag("sheet")).assertExists()
        tapText("Workout", "sheet")
        assertEquals(listOf(3L, 1L, 5L, 7L, 4L), ids("Workout"))
        assertFalse(model.selecting)
    }

    @Test
    fun selectAllQueueAndPlayNext() {
        idle { model.play(listOf(1L, 2L), 1L, "Test") }

        tapDesc("Select tracks")
        assertTrue(model.selecting); assertTrue(model.selected.isEmpty())
        node(hasText("Tap tracks to select")).assertExists()
        tapText("Queue", "selectionbar")                       // nothing selected: no-op
        assertEquals(0, model.engine.queuedItemsAfterCurrent().size)

        tapText("Select all", "selectionbar")
        assertEquals(7, model.selected.size)
        tapText("Queue", "selectionbar")
        assertEquals(7, model.engine.queuedItemsAfterCurrent().size)
        assertFalse(model.selecting)

        longPress("Diamonds"); tapText("Thunderstruck")
        tapText("Play next", "selectionbar")
        val e = model.engine
        assertEquals(9, e.queuedItemsAfterCurrent().size)
        assertTrue(e.player.getMediaItemAt(e.player.currentMediaItemIndex + 1).mediaId.startsWith("q:3:"))
        assertTrue(e.player.getMediaItemAt(e.player.currentMediaItemIndex + 2).mediaId.startsWith("q:7:"))

        longPress("Mrs. Robinson"); tapText("Lose Yourself")
        tapText("Play", "selectionbar")
        assertEquals(1L, e.currentId)                           // list order: Lose Yourself comes first
        assertEquals("Selection", e.ctxName)

        longPress("Mrs. Robinson")
        tapDesc("Cancel selection")
        assertFalse(model.selecting)
        longPress("Mrs. Robinson")
        idle { model.back() }
        assertFalse(model.selecting)
    }

    @Test
    fun playlistPageAddSongsSelectRemoveAndAddWholeArtist() {
        val pid = model.createPlaylist("Mix", null, listOf(1L, 2L, 3L, 4L))
        idle { model.open(Detail.PlaylistD(pid)) }

        tapDesc("Select tracks", "detail")
        tapText("Lose Yourself", "detail"); tapText("Diamonds", "detail")
        tapText("Remove", "selectionbar")
        assertEquals(listOf(2L, 4L), ids("Mix"))
        assertFalse(model.selecting)

        // Selecting on a non-playlist page offers no "Remove".
        idle { model.open(Detail.ArtistD("Eminem")) }
        longPress("Lose Yourself", "detail")
        rule.onAllNodes(hasText("Remove") and inTag("selectionbar")).assertCountEquals(0)
        tapDesc("Cancel selection")

        // Whole artist in one tap; tracks already in the playlist are skipped.
        tapDesc("Add all to playlist", "detail")
        assertEquals(4, (model.sheet as Sheet.AddToPlaylist).ids.size)
        tapText("Mix", "sheet")
        assertEquals(listOf(2L, 4L), ids("Mix").take(2))
        assertEquals(listOf(1L, 2L, 4L, 5L), ids("Mix").sorted())
        tapDesc("Back", "detail")

        // "Add songs" opens the picker; songs already in the playlist can't be picked twice.
        tapText("Add songs", "detail")
        assertEquals(pid, model.pickerFor)
        node(hasText("Already in playlist", substring = true) and inTag("picker")).assertExists()
        tapText("Lose Yourself", "picker")                     // already in: ignored
        node(hasText("Done") and inTag("picker")).assertExists()
        tapText("Thunderstruck", "picker"); tapText("Diamonds", "picker")
        tapText("Add 2 songs", "picker")
        assertNull(model.pickerFor)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 7L), ids("Mix").sorted())

        tapText("Add songs", "detail")
        tapDesc("Close picker", "picker")
        assertNull(model.pickerFor)
    }
}
