package com.strata.player

import android.content.Intent
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.strata.player.data.Natural
import com.strata.player.data.Order
import com.strata.player.data.Track
import com.strata.player.ui.childDirs
import com.strata.player.ui.commonRoot
import com.strata.player.widget.StrataWidgets
import com.strata.player.widget.WidgetActionReceiver
import com.strata.player.widget.WidgetState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Logic behind the quality-of-life features: sorting, queue editing, saved queues, sleep timer, folders, widgets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QolLogicTest {

    private lateinit var model: AppModel
    private val music = "/storage/emulated/0/Music"
    private val voice = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202544"

    private fun tr(id: Long, title: String, album: String, sec: Int = 200, folder: String = "$music/$album") = Track(
        id = id, uri = Uri.parse("content://media/external/audio/media/$id"),
        title = title, artist = "Artist $album", album = album, albumId = 100L + album.length, albumArtist = "Artist $album",
        genre = "Pop", composer = "", year = 2020, trackNo = id.toInt(), discNo = 1, durationMs = sec * 1000L,
        path = "$folder/$title.flac", folder = folder, sizeBytes = 1, dateAddedSec = id, mime = "audio/flac", bitrateBps = 0,
    )

    private val library = listOf(
        tr(1, "One", "Alpha"), tr(2, "Two", "Alpha"), tr(3, "Three", "Alpha"),
        tr(4, "Four", "Beta"), tr(5, "Five", "Beta"),
        tr(6, "Long Mix", "Mixes", sec = 45 * 60),
        tr(7, "PTT-20240101", "Voice", sec = 40, folder = voice),
    )

    @Before
    fun setUp() {
        model = ApplicationProvider.getApplicationContext<StrataApp>().model
        model.installLibraryForTest(library)
    }

    @After
    fun tearDown() = model.shutdownForTest()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The library index is rebuilt on a background thread; wait for it to land on the main thread. */
    private fun waitFor(what: String, cond: () -> Boolean) {
        repeat(250) {
            idle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for: $what")
    }
    private val e get() = model.engine
    private fun ids() = e.timelineIds()

    // ------------------------------------------------------------------ natural sort

    @Test
    fun naturalSortPutsNumbersInOrder() {
        val sorted = listOf("Track 10", "track 2", "Track 1", "Track 02b", "Album", "album 9").sortedWith(Natural)
        assertEquals(listOf("Album", "album 9", "Track 1", "track 2", "Track 02b", "Track 10"), sorted)
        assertTrue(Natural.compare("Disc 2 - 10", "Disc 2 - 9") > 0)
        assertTrue(Natural.compare("a", "B") < 0)
        assertEquals(0, Natural.compare("Same", "Same"))
    }

    @Test
    fun libraryListsUseNaturalOrder() {
        model.installLibraryForTest(listOf(tr(1, "Song 10", "X"), tr(2, "Song 9", "X"), tr(3, "song 1", "X")))
        assertEquals(listOf("song 1", "Song 9", "Song 10"), model.sortedTracks(com.strata.player.data.SortKey.TITLE).map { it.title })
    }

    // ------------------------------------------------------------------ queue editing

    @Test
    fun moveRemoveUndoAndPlayNext() {
        model.play(listOf(1L, 2L, 3L, 4L, 5L), 1, "Test"); idle()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids())

        e.move(4, 1); idle()                       // drag Five up to right after the current track
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), ids())
        e.move(0, 3)                                // the current track never moves
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), ids())

        model.removeFromQueueWithUndo(2); idle()   // swipe Two away…
        assertEquals(listOf(1L, 5L, 3L, 4L), ids())
        assertEquals("Undo", model.toastAction?.first)
        model.toastAction!!.second()                // …and undo
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), ids())

        e.moveToNext(4); idle()                     // swipe Four right: plays next, as a queued item
        assertEquals(listOf(1L, 4L, 5L, 2L, 3L), ids())
        assertEquals(1, e.queuedItemsAfterCurrent().size)
        e.moveToNext(3)                             // a second one goes right after the current, ahead of the first
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), ids())
        assertEquals(2, e.queuedItemsAfterCurrent().size)

        e.jumpTo(3); idle()
        assertEquals(5L, e.currentId)
        assertEquals(listOf(1L, 2L, 4L), e.history().map { it.trackId })
    }

    // ------------------------------------------------------------------ saved queues

    @Test
    fun savedQueuesKeepTheirPlaceAndCanBeFilledWhileAnotherPlays() {
        model.play(listOf(1L, 2L, 3L), 2, "Alpha"); idle()
        e.seekTo(30_000); idle()

        // Build tonight's queue while Alpha keeps playing.
        val tonight = model.createQueue("Tonight", listOf(4L))
        model.addToSavedQueue(tonight, listOf(5L, 4L))
        assertEquals(listOf(4L, 5L, 4L), model.queues.first { it.id == tonight }.ids)
        assertEquals(2L, e.currentId)

        // Switch: the ad-hoc Alpha queue is kept automatically, with its track and position.
        model.switchQueue(tonight); idle()
        assertEquals(tonight, model.activeQueueId)
        assertEquals(listOf(4L, 5L, 4L), ids())
        val auto = model.queues.first { it.auto }
        assertEquals(listOf(1L, 2L, 3L), auto.ids)
        assertEquals(1, auto.index)
        assertTrue(auto.positionMs >= 29_000)

        // Move on inside Tonight, go back to Alpha, then return: Tonight resumes where it was.
        e.jumpTo(1); idle()
        model.switchQueue(auto.id); idle()
        assertEquals(2L, e.currentId)
        assertEquals(1, model.queues.first { it.id == tonight }.index)
        model.switchQueue(tonight); idle()
        assertEquals(5L, e.currentId)

        // Starting an album leaves the saved queue alone.
        model.play(listOf(4L, 5L), 4, "Beta"); idle()
        assertNull(model.activeQueueId)
        assertEquals(listOf(4L, 5L, 4L), model.queues.first { it.id == tonight }.ids)

        // Delete with undo.
        model.deleteQueue(tonight)
        assertTrue(model.queues.none { it.id == tonight })
        model.toastAction!!.second()
        assertNotNull(model.queues.firstOrNull { it.id == tonight })

        // Saving the playing queue makes it the active one.
        val saved = model.saveCurrentAsQueue("Beta for later")!!
        assertEquals(saved, model.activeQueueId)
        assertEquals(listOf(4L, 5L), model.queues.first { it.id == saved }.ids)
    }

    // ------------------------------------------------------------------ sleep timer

    @Test
    fun sleepTimerFadesExtendsAndCountsSongs() {
        model.play(listOf(1L, 2L, 3L, 4L, 5L), 1, "Test"); idle()

        e.setSleepMinutes(1)
        assertEquals(1f, e.sleepFade())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45))
        val f = e.sleepFade()
        assertTrue("fade $f", f in 0.4f..0.6f)

        e.addSleepMinutes(15)
        assertTrue((e.sleepRemainingMs() ?: 0) > 15 * 60_000L)
        assertEquals(1f, e.sleepFade())

        e.setSleepAfterTracks(3)
        assertNull(e.sleepAtMs)
        assertEquals(3, e.sleepTracksLeft)
        e.countDownSleepTracks()
        assertEquals(2, e.sleepTracksLeft)
        assertFalse(e.player.pauseAtEndOfMediaItems)
        e.countDownSleepTracks()                     // the last song: pause at its end
        assertNull(e.sleepTracksLeft)
        assertTrue(e.sleepEndOfTrack)
        assertTrue(e.player.pauseAtEndOfMediaItems)

        e.setSleepMinutes(null)
        assertFalse(e.sleepEndOfTrack)
        assertFalse(e.player.pauseAtEndOfMediaItems)
    }

    // ------------------------------------------------------------------ headphones

    @Test
    fun resumesOnHeadphonesButOnlyOnBluetoothWhenAsked() {
        model.play(listOf(1L, 2L), 1, "Test"); idle()
        e.player.pause(); idle()
        assertFalse(e.player.playWhenReady)

        e.onOutputsConnected(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)); idle()
        assertFalse("Bluetooth resume is off by default", e.player.playWhenReady)

        e.onOutputsConnected(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)); idle()
        assertTrue(e.player.playWhenReady)

        e.player.pause(); idle()
        model.updateSettings { it.copy(resumeOnBluetooth = true) }
        e.onOutputsConnected(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)); idle()
        assertTrue(e.player.playWhenReady)

        e.player.pause(); idle()
        model.updateSettings { it.copy(resumeOnWired = false, resumeOnBluetooth = false) }
        e.onOutputsConnected(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)); idle()
        assertFalse(e.player.playWhenReady)
    }

    // ------------------------------------------------------------------ long files

    @Test
    fun longFilesRememberTheirPosition() {
        model.play(listOf(6L, 1L), 6, "Mixes"); idle()
        e.seekTo(20 * 60_000L); idle()
        e.jumpTo(1); idle()                          // leave the mix 20 minutes in
        assertEquals(20 * 60_000L, e.savedPosition(6))
        assertNull("short tracks are not remembered", e.savedPosition(1))

        e.jumpTo(0); idle()                          // come back: it continues where it was
        assertTrue("pos ${e.player.currentPosition}", e.player.currentPosition >= 20 * 60_000L - 1000)

        model.updateSettings { it.copy(rememberLongPositions = false) }
        e.seekTo(30 * 60_000L); idle()
        e.jumpTo(1); idle()
        assertEquals("turned off: nothing new is saved", 20 * 60_000L, e.savedPosition(6))
    }

    // ------------------------------------------------------------------ lost memories, random, folders

    @Test
    fun lostMemoriesAreOftenPlayedButLongAgo() {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        model.updateUser { u ->
            u.copy(
                plays = mapOf(1L to 12, 2L to 5, 3L to 2, 4L to 9),
                lastPlayed = mapOf(1L to now - 400 * day, 2L to now - 200 * day, 3L to now - 500 * day, 4L to now - 3 * day),
            )
        }
        assertEquals(listOf(1L, 2L), model.lostMemories(now))   // 3 played too rarely, 4 too recently
        assertEquals(listOf(1L, 2L), model.playlistIds("lost"))
        assertTrue(SMART_LISTS.any { it.id == "lost" })
    }

    @Test
    fun longPressShufflePlaysOneRandomTrackKeepingTheOrder() {
        val all = library.map { it.id }
        model.playRandom(all, "All tracks"); idle()
        assertTrue(e.currentId in all)
        assertEquals(Order.DEFAULT, model.settings.order)
        assertEquals(all, ids())
    }

    @Test
    fun foldersCanBeLeftOutAndJunkIsDetected() {
        assertTrue(model.index.byId.containsKey(7))
        assertEquals(listOf("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp"), model.junkFolders())

        model.hideJunkFolders()
        waitFor("voice note hidden") { !model.index.byId.containsKey(7) }
        assertTrue(model.isFolderExcluded(voice))
        assertEquals(7, model.scannedFolders.values.sum())   // still listed, so it can be switched back on

        model.setFolderIncluded("$music/Beta", false)
        waitFor("Beta hidden") { !model.index.byId.containsKey(4) }
        assertTrue(model.index.byId.containsKey(1))
        model.setFolderIncluded("$music/Beta", true)
        waitFor("Beta back") { model.index.byId.containsKey(4) }

        model.setFolderIncluded("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp", true)
        waitFor("voice note back") { model.index.byId.containsKey(7) }
        assertFalse(isJunkFolderName("Calling You"))
        assertTrue(isJunkFolderName("Call Recordings"))
    }

    @Test
    fun folderTreeHelpers() {
        val folders = mapOf("$music/Alpha" to 3, "$music/Beta" to 2, "$music/Beta/Bonus" to 1, voice to 1)
        val root = commonRoot(folders.keys)
        assertEquals("/storage/emulated/0", root)
        val top = childDirs(folders, root)
        assertEquals(listOf("Android", "Music"), top.map { it.name })
        assertEquals(6, top.first { it.name == "Music" }.count)
        val inMusic = childDirs(folders, "$music")
        assertEquals(listOf("Alpha", "Beta"), inMusic.map { it.name })
        assertTrue(inMusic.first { it.name == "Beta" }.hasChildren)
        assertFalse(inMusic.first { it.name == "Alpha" }.hasChildren)
        assertEquals("$music", commonRoot(listOf("$music/Alpha")))
    }

    // ------------------------------------------------------------------ widgets

    @Test
    fun playerWidgetShowsTheTrackAndButtonsWork() {
        val ctx = ApplicationProvider.getApplicationContext<StrataApp>()

        model.play(listOf(1L, 2L, 3L), 1, "Test"); idle()
        model.publishWidget()
        val state = StrataWidgets.readState(ctx)!!
        assertEquals("One", state.title)
        assertEquals(1L, state.trackId)

        // Both layouts inflate and show the track.
        for (large in listOf(true, false)) {
            val view = StrataWidgets.buildPlayer(ctx, state, StrataWidgets.generated(state.albumId, 96), large).apply(ctx, FrameLayout(ctx))
            assertEquals("One", view.findViewById<TextView>(R.id.w_title).text.toString())
            assertEquals(if (state.playing) "Pause" else "Play", view.findViewById<View>(R.id.w_play).contentDescription)
        }
        val empty = StrataWidgets.buildPlayer(ctx, null, null, large = true).apply(ctx, FrameLayout(ctx))
        assertEquals("Strata", empty.findViewById<TextView>(R.id.w_title).text.toString())

        WidgetActionReceiver().onReceive(ctx, Intent(StrataWidgets.ACTION_NEXT)); idle()
        assertEquals(2L, e.currentId)
        assertEquals("Two", StrataWidgets.readState(ctx)!!.title)
        WidgetActionReceiver().onReceive(ctx, Intent(StrataWidgets.ACTION_FAV)); idle()
        assertTrue(model.isFav(2))
        assertTrue(StrataWidgets.readState(ctx)!!.favorite)
        WidgetActionReceiver().onReceive(ctx, Intent(StrataWidgets.ACTION_PREV)); idle()
        assertEquals(1L, e.currentId)

        // The "Play from" launcher has its four tiles.
        val launcher = StrataWidgets.buildPlayFrom(ctx).apply(ctx, FrameLayout(ctx))
        assertEquals(4, StrataWidgets.TILES.size)
        StrataWidgets.TILES.forEach { t -> assertEquals(t.title, launcher.findViewById<TextView>(t.titleId).text.toString()) }
    }

    @Test
    fun widgetPlayFromStartsPlayback() {
        model.toggleFav(4); model.toggleFav(5)
        var ok = false
        model.startFromWidget(StrataWidgets.ACTION_PLAY_FROM, "fav") { ok = it }; idle()
        assertTrue(ok)
        assertEquals(listOf(4L, 5L), ids())
        assertEquals("Favorites", e.ctxName)

        model.startFromWidget(StrataWidgets.ACTION_PLAY_FROM, "shuffle") { ok = it }; idle()
        assertEquals(library.size, ids().size)
        assertTrue(model.settings.order.isShuffle)
    }
}
