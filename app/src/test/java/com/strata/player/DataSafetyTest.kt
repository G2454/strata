package com.strata.player

import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.strata.player.data.CrashLog
import com.strata.player.data.Order
import com.strata.player.data.Track
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

/** Backups, undo for playlist edits, the notification heart and the crash report. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataSafetyTest {

    private lateinit var model: AppModel
    private val ctx get() = ApplicationProvider.getApplicationContext<StrataApp>()
    private val music = "/storage/emulated/0/Music"

    private fun tr(id: Long, title: String, album: String) = Track(
        id = id, uri = Uri.parse("content://media/external/audio/media/$id"),
        title = title, artist = "Artist $album", album = album, albumId = 100L + album.length, albumArtist = "Artist $album",
        genre = "Pop", composer = "", year = 2020, trackNo = id.toInt(), discNo = 1, durationMs = 200_000L,
        path = "$music/$album/$title.flac", folder = "$music/$album", sizeBytes = 1, dateAddedSec = id, mime = "audio/flac", bitrateBps = 0,
    )

    private val library = listOf(tr(1, "One", "Alpha"), tr(2, "Two", "Alpha"), tr(3, "Three", "Alpha"), tr(4, "Four", "Beta"), tr(5, "Five", "Beta"))

    @Before
    fun setUp() {
        model = ctx.model
        model.installLibraryForTest(library)
    }

    @After
    fun tearDown() = model.shutdownForTest()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ------------------------------------------------------------------ backup & restore

    @Test
    fun aBackupRestoresOnANewPhoneWhereEveryTrackHasANewId() {
        model.toggleFav(2); model.toggleFav(4)
        model.setRating(3, 5)
        model.updateUser { it.copy(plays = mapOf(1L to 7, 5L to 2), history = listOf(5L, 1L)) }
        val pid = model.createPlaylist("Road trip", null, listOf(4L, 1L, 3L))
        model.updateSettings { it.copy(order = Order.REPEAT_PLAYLIST, resumeOnBluetooth = true) }
        model.createQueue("Later", listOf(5L, 2L))
        val text = model.exportBackup()
        assertTrue(text.contains("\"format\":\"strata-backup\""))

        // "New phone": the same files, but Android numbered them differently, and nothing is saved yet.
        val renumbered = library.map { it.copy(id = it.id + 1000, uri = Uri.parse("content://media/external/audio/media/${it.id + 1000}")) }
        model.installLibraryForTest(renumbered)
        model.updateUser { com.strata.player.data.UserData() }
        model.updateSettings { com.strata.player.data.Settings() }

        assertTrue(model.importBackup(text)); idle()
        assertEquals(setOf(1002L, 1004L), model.user.favorites)
        assertEquals(5, model.user.ratings[1003L])
        assertEquals(7, model.user.plays[1001L])
        assertEquals(listOf(1005L, 1001L), model.user.history)
        assertEquals(listOf(1004L, 1001L, 1003L), model.user.playlists.first { it.id == pid }.ids)
        assertEquals(listOf(1005L, 1002L), model.queues.first { it.name == "Later" }.ids)
        assertEquals(Order.REPEAT_PLAYLIST, model.settings.order)
        assertTrue(model.settings.resumeOnBluetooth)
        assertNull("restoring doesn't take over what's playing", model.activeQueueId)
        assertNotNull("the toast offers Undo", model.toastAction)

        // Undo puts everything back as it was just before.
        model.toastAction!!.second(); idle()
        assertTrue(model.user.favorites.isEmpty())
        assertTrue(model.user.playlists.none { it.id == pid })
        assertEquals(Order.DEFAULT, model.settings.order)
    }

    @Test
    fun somethingThatIsNotABackupIsRefused() {
        model.toggleFav(1)
        assertFalse(model.importBackup("{\"hello\": 1}"))
        assertFalse(model.importBackup("not json at all"))
        assertEquals("That file isn't a Strata backup", model.toast)
        assertEquals(setOf(1L), model.user.favorites)
    }

    @Test
    fun aBackupOnTheSamePhoneKeepsIdsAndTagEdits() {
        model.toggleFav(5)
        model.updateUser { it.copy(overrides = mapOf(2L to mapOf("title" to "Two (live)"))) }
        val text = model.exportBackup()
        model.updateUser { com.strata.player.data.UserData() }
        assertTrue(model.importBackup(text)); idle()
        assertEquals(setOf(5L), model.user.favorites)
        assertEquals("Two (live)", model.user.overrides[2L]?.get("title"))
    }

    // ------------------------------------------------------------------ playlists

    @Test
    fun removingFromAPlaylistCanBeUndone() {
        val pid = model.createPlaylist("Mix", null, listOf(1L, 2L, 3L, 4L))
        model.removeFromPlaylistWithUndo(pid, listOf(2L, 4L))
        assertEquals(listOf(1L, 3L), model.user.playlists.first { it.id == pid }.ids)
        assertEquals("Removed 2 tracks from “Mix”", model.toast)
        val undo = model.toastAction!!.second
        // Something added in the meantime stays; the removed tracks come back in their old places.
        model.addToPlaylist(pid, listOf(5L))
        undo()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), model.user.playlists.first { it.id == pid }.ids)

        // One track, as from the track menu.
        val pid2 = model.createPlaylist("Two", null, listOf(1L, 2L, 3L))
        model.removeFromPlaylistWithUndo(pid2, listOf(2L))
        assertEquals("Removed “Two” from “Two”", model.toast)
        model.toastAction!!.second()
        assertEquals(listOf(1L, 2L, 3L), model.user.playlists.first { it.id == pid2 }.ids)
    }

    @Test
    fun selectionRemovalFromAPlaylistOffersUndo() {
        val pid = model.createPlaylist("Mix", null, listOf(1L, 2L, 3L))
        model.startSelection(listOf(1L, 2L, 3L), 1L, pid)
        model.toggleSelected(listOf(1L, 2L, 3L), 3L)
        model.removeSelectedFromPlaylist()
        assertEquals(listOf(2L), model.user.playlists.first { it.id == pid }.ids)
        assertFalse(model.selecting)
        model.toastAction!!.second()
        assertEquals(listOf(1L, 2L, 3L), model.user.playlists.first { it.id == pid }.ids)
    }

    @Test
    fun movingByDragUsesTheTracksNotThePositions() {
        val pid = model.createPlaylist("Mix", null, listOf(1L, 2L, 3L, 4L))
        model.movePlaylistTrack(pid, 2L, 4L)
        assertEquals(listOf(1L, 3L, 4L, 2L), model.user.playlists.first { it.id == pid }.ids)
        model.movePlaylistTrack(pid, 2L, 1L)
        assertEquals(listOf(2L, 1L, 3L, 4L), model.user.playlists.first { it.id == pid }.ids)
        model.movePlaylistTrack(pid, 9L, 1L) // not in the playlist: nothing happens
        assertEquals(listOf(2L, 1L, 3L, 4L), model.user.playlists.first { it.id == pid }.ids)
    }

    // ------------------------------------------------------------------ notification heart

    @Test
    fun theNotificationHeartIsToldAboutChanges() {
        val seen = ArrayList<Pair<Long, Boolean>>()
        model.onNowPlayingChanged = { id, fav -> seen += id to fav }
        model.play(listOf(1L, 2L), 1, "Test"); idle()
        seen.clear()
        model.toggleFav(1)
        assertEquals(1L to true, seen.last())
        model.toggleFav(2) // not the current track: nothing to update
        assertEquals(1L to true, seen.last())
        model.engine.next(); idle()
        assertEquals(2L to true, seen.last())
        model.toggleFav(2)
        assertEquals(2L to false, seen.last())
        model.onNowPlayingChanged = null
    }

    // ------------------------------------------------------------------ crash report

    @Test
    fun aCrashIsKeptForSettingsToCopy() {
        CrashLog.clear(ctx)
        assertNull(CrashLog.read(ctx))
        CrashLog.record(ctx, "main", IllegalStateException("queue index out of range"), now = 0L)
        val report = CrashLog.read(ctx)!!
        assertTrue(report.startsWith("Strata crash report"))
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("IllegalStateException: queue index out of range"))
        assertTrue(report.contains("at com.strata.player.DataSafetyTest"))
        CrashLog.clear(ctx)
        assertNull(CrashLog.read(ctx))
    }
}
