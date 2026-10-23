package com.strata.player

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.up
import androidx.test.core.app.ApplicationProvider
import com.strata.player.data.Order
import com.strata.player.data.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the quality-of-life features through the real UI: gestures, the queue, sleep timer, queues, folders, lost memories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-xxhdpi")
class QolUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var model: AppModel
    private val music = "/storage/emulated/0/Music"
    private val voice = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202544"

    private fun tr(id: Long, title: String, album: String, sec: Int = 200, folder: String = "$music/$album") = Track(
        id = id, uri = Uri.parse("content://media/external/audio/media/$id"),
        title = title, artist = "Artist $album", album = album, albumId = 100L + id / 4, albumArtist = "Artist $album",
        genre = "Pop", composer = "", year = 2020, trackNo = id.toInt(), discNo = 1, durationMs = sec * 1000L,
        path = "$folder/$title.flac", folder = folder, sizeBytes = 1, dateAddedSec = id, mime = "audio/flac", bitrateBps = 0,
    )

    // Title order = id order.
    private val library = listOf(
        tr(1, "Alder", "Alpha"), tr(2, "Birch", "Alpha"), tr(3, "Cedar", "Alpha"),
        tr(4, "Dogwood", "Beta"), tr(5, "Elm", "Beta"), tr(6, "Fir", "Beta"),
        tr(7, "Long Mix", "Mixes", sec = 45 * 60),
        tr(8, "PTT-0001", "Voice", sec = 40, folder = voice),
    )

    @Before
    fun setUp() {
        model = ApplicationProvider.getApplicationContext<StrataApp>().model
        model.installLibraryForTest(library)
        rule.launchStrata(model)
    }

    @After
    fun tearDown() = model.shutdownForTest()

    private val e get() = model.engine
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
    private fun idle(block: () -> Unit) { rule.runOnIdle(block); rule.waitForIdle() }
    private fun playAll(start: Long = 1, ids: List<Long> = (1L..6L).toList()) = idle { model.play(ids, start, "Test") }
    private fun row(title: String, scope: String) = node(hasText(title) and inTag(scope))

    // ------------------------------------------------------------------ gestures

    @Test
    fun miniPlayerSwipesSkipAndOpenThePlayer() {
        playAll(2)
        val mini = rule.onNode(hasTestTag("miniplayer"))
        // Start inside the card (the strip around it is padding).
        mini.performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        rule.waitForIdle()
        assertEquals("swipe left = next", 3L, e.currentId)

        mini.performTouchInput { swipeRight(startX = width * 0.1f, endX = width * 0.9f) }
        rule.waitForIdle()
        assertEquals("swipe right = previous", 2L, e.currentId)
        assertFalse(model.nowOpen)

        mini.performTouchInput { swipe(Offset(centerX, height - 4f), Offset(centerX, -height * 2f), 250) }
        rule.waitForIdle()
        assertTrue("swipe up opens the player", model.nowOpen)
    }

    @Test
    fun coverDoubleTapSeeksAndSwipesSkipOrOpenTheQueue() {
        playAll(2)
        idle { model.nowOpen = true; model.npView = NpView.ART }
        val art = rule.onNode(hasTestTag("np-art"))

        art.performTouchInput { doubleClick(Offset(width * 0.8f, centerY)) }
        rule.waitForIdle()
        assertTrue("+30 s, at ${e.player.currentPosition}", e.player.currentPosition in 29_000L..31_000L)
        art.performTouchInput { doubleClick(Offset(width * 0.2f, centerY)) }
        rule.waitForIdle()
        assertTrue("−30 s, at ${e.player.currentPosition}", e.player.currentPosition < 1_000L)

        art.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(3L, e.currentId)

        art.performTouchInput { swipe(Offset(centerX, height * 0.8f), Offset(centerX, height * 0.1f), 250) }
        rule.waitForIdle()
        assertEquals("swipe up opens the queue", Sheet.Queue, model.sheet)
    }

    @Test
    fun longPressShufflePlaysOneRandomTrackAndStarsRateOnThePlayer() {
        node(hasText("Shuffle all")).performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(e.currentId in library.map { it.id })
        assertEquals("the playback order is left alone", Order.DEFAULT, model.settings.order)
        assertEquals(library.map { it.id }, e.timelineIds())

        idle { model.nowOpen = true }
        val cur = e.currentId
        tapDesc("Rate 4 stars", "nowplaying")
        assertEquals(4, model.rating(model.track(cur)!!))
        tapDesc("Rate 4 stars", "nowplaying")
        assertEquals("tapping the same star clears it", 0, model.rating(model.track(cur)!!))

        node(hasContentDescription("Shuffle: Default") and inTag("nowplaying")).performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(Order.DEFAULT, model.settings.order)
        assertTrue(e.currentId in library.map { it.id })
    }

    // ------------------------------------------------------------------ queue

    @Test
    fun queueDragSwipeUndoAndSave() {
        playAll(1)
        idle { model.sheet = Sheet.Queue }
        rule.onNode(hasTestTag("queue")).assertExists()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), e.timelineIds())

        // Drag Cedar's handle two rows down.
        val rowPx = 58f * 3f
        node(hasContentDescription("Reorder Cedar") and inTag("sheet")).performTouchInput {
            down(center)
            moveBy(Offset(0f, 30f))
            moveBy(Offset(0f, rowPx))
            moveBy(Offset(0f, rowPx - 30f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L, 6L), e.timelineIds())

        // Swipe right: play next.
        row("Fir", "sheet").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(6L, e.timelineIds()[1])
        assertEquals(1, e.queuedItemsAfterCurrent().size)

        // Swipe left: removed, with undo.
        row("Birch", "sheet").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertFalse(2L in e.timelineIds())
        tapText("Undo", "toast")
        assertTrue(2L in e.timelineIds())

        // Save as playlist.
        val before = e.timelineIds()
        tapDesc("Save queue as playlist", "sheet")
        val s = model.sheet
        assertTrue(s is Sheet.NewPlaylist)
        assertEquals(before.distinct(), (s as Sheet.NewPlaylist).withTracks)
        node(hasSetTextAction() and inTag("sheet")).performTextInput("From the queue")
        tapText("Create", "sheet")
        assertEquals(before.distinct(), model.user.playlists.first { it.name == "From the queue" }.ids)
    }

    @Test
    fun savedQueuesAreBuiltWhileAnotherPlaysAndKeepTheirPlace() {
        playAll(2, listOf(1L, 2L, 3L))
        idle { model.sheet = Sheet.Queue }
        tapDesc("Saved queues", "sheet"); assertEquals(Sheet.Queues, model.sheet)
        tapText("New", "sheet"); assertEquals(Sheet.NewQueue(emptyList()), model.sheet)
        node(hasSetTextAction() and inTag("sheet")).performTextInput("Tonight")
        tapText("Create queue", "sheet")
        val tonight = model.queues.single { it.name == "Tonight" }.id
        assertNull(model.sheet)

        // From a track menu…
        tapDesc("More options for Elm")
        tapText("Add to another queue…", "sheet")
        tapText("Tonight", "sheet")
        assertEquals(listOf(5L), model.queues.first { it.id == tonight }.ids)

        // …and from a multi-selection.
        node(hasText("Fir")).performTouchInput { longClick() }; rule.waitForIdle()
        tapText("Dogwood")
        tapText("Queues", "selectionbar")
        tapText("Tonight", "sheet")
        assertEquals(listOf(5L, 4L, 6L), model.queues.first { it.id == tonight }.ids)
        assertFalse(model.selecting)
        assertEquals("still playing the first queue", 2L, e.currentId)

        // Switch to Tonight; the album that was playing becomes an automatic queue.
        idle { model.sheet = Sheet.Queues }
        tapText("Tonight", "sheet")
        assertEquals(tonight, model.activeQueueId)
        assertEquals(listOf(5L, 4L, 6L), e.timelineIds())
        val auto = model.queues.first { it.auto }
        assertEquals(listOf(1L, 2L, 3L), auto.ids)
        assertEquals(1, auto.index)

        // Back to the first one: it resumes on its track.
        idle { model.sheet = Sheet.Queues }
        node(hasText("PLAYING") and inTag("sheet")).assertExists()
        tapText("Test", "sheet")
        assertEquals(2L, e.currentId)

        // Delete with undo.
        idle { model.sheet = Sheet.Queues }
        tapDesc("Delete queue Tonight", "sheet")
        assertTrue(model.queues.none { it.id == tonight })
        tapText("Undo", "toast")
        assertNotNull(model.queues.firstOrNull { it.id == tonight })
    }

    // ------------------------------------------------------------------ sleep timer

    @Test
    fun sleepTimerSongsAndAddFifteen() {
        playAll(1)
        idle { model.sheet = Sheet.Sleep }
        tapText("After 3 songs", "sheet")
        assertEquals(3, e.sleepTracksLeft)
        assertNull(model.sheet)

        idle { model.sheet = Sheet.Sleep }
        tapText("30 minutes", "sheet")
        assertNull(e.sleepTracksLeft)
        idle { model.sheet = Sheet.Sleep }
        tapText("Add 15 minutes", "sheet")
        assertTrue((e.sleepRemainingMs() ?: 0L) > 44 * 60_000L)

        tapText("Off", "sheet")
        assertNull(e.sleepAtMs)
    }

    // ------------------------------------------------------------------ settings, folders, lost memories

    @Test
    fun settingsFoldersAndHeadphoneSwitches() {
        tapDesc("Settings")
        tapText("Resume on headphones", "settings"); assertFalse(model.settings.resumeOnWired)
        tapText("Resume on Bluetooth", "settings"); assertTrue(model.settings.resumeOnBluetooth)
        tapText("Remember position in long files", "settings"); assertFalse(model.settings.rememberLongPositions)

        assertTrue(model.index.byId.containsKey(8))
        tapText("Folders", "settings"); assertEquals(Sheet.Folders, model.sheet)
        tapText("Hide voice notes & recordings", "sheet")
        rule.waitUntil(5000) { !model.index.byId.containsKey(8) }
        assertEquals(setOf("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp"), model.settings.excludedFolders)

        tapText("Music", "sheet")
        tapDesc("Leave out Beta", "sheet")
        rule.waitUntil(5000) { !model.index.byId.containsKey(4) }
        assertTrue(model.index.byId.containsKey(1))
        tapDesc("Include Beta", "sheet")
        rule.waitUntil(5000) { model.index.byId.containsKey(4) }
    }

    @Test
    fun lostMemoriesListsLongForgottenFavourites() {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        idle {
            model.updateUser { u ->
                u.copy(
                    plays = mapOf(1L to 12, 2L to 7, 3L to 1, 4L to 20),
                    lastPlayed = mapOf(1L to now - 300 * day, 2L to now - 400 * day, 3L to now - 400 * day, 4L to now - 2 * day),
                )
            }
        }
        node(hasTestTag("nav-playlists")).tap()
        tapText("Lost memories")
        assertEquals(Detail.PlaylistD("lost"), model.details.last())
        row("Alder", "detail").assertExists()
        row("Birch", "detail").assertExists()
        rule.onAllNodes(hasText("Cedar") and inTag("detail")).assertCountEquals(0)
        rule.onAllNodes(hasText("Dogwood") and inTag("detail")).assertCountEquals(0)
    }
}
