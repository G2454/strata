package com.strata.player

import android.net.Uri
import com.strata.player.data.ArtistSplitter
import com.strata.player.data.LibraryIndex
import com.strata.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Eminem feat. Rihanna" must count for both Eminem and Rihanna, without breaking band names apart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtistSplitTest {

    private var nextId = 1L
    private fun t(artist: String, title: String = "Song $nextId", albumArtist: String = artist) = Track(
        id = nextId, uri = Uri.parse("content://media/external/audio/media/$nextId"),
        title = title, artist = artist, album = "Album $nextId", albumId = 100 + nextId, albumArtist = albumArtist,
        genre = "Hip Hop", composer = "", year = 2010, trackNo = 1, discNo = 1, durationMs = 200_000,
        path = "/storage/emulated/0/Music/${nextId}.mp3", folder = "/storage/emulated/0/Music", sizeBytes = 1,
        dateAddedSec = 1, mime = "audio/mpeg", bitrateBps = 320_000,
    ).also { nextId++ }

    /** A library where these artists are credited on their own at least once. */
    private val solo = listOf(
        t("Eminem"), t("Rihanna"), t("Dr. Dre"), t("The Weeknd"), t("Kali Uchis"),
        t("Simon & Garfunkel"), t("AC/DC"), t("Unknown A & Unknown B"),
    )

    private fun splitter(extra: List<Track> = emptyList(), keep: List<String> = emptyList()) =
        ArtistSplitter(true, keep).also { it.learn(solo + extra) }

    private fun check(credit: String, vararg expected: String, s: ArtistSplitter = splitter()) =
        assertEquals("split of “$credit”", expected.toList(), s.split(credit))

    @Test fun featKeywordsAlwaysSplit() {
        check("Eminem feat. Rihanna", "Eminem", "Rihanna")
        check("Eminem ft. Rihanna", "Eminem", "Rihanna")
        check("Eminem featuring Rihanna, Nate Dogg", "Eminem", "Rihanna", "Nate Dogg")
        check("Eminem Feat Rihanna", "Eminem", "Rihanna")
        check("Eminem (feat. Rihanna)", "Eminem", "Rihanna")
        check("Eminem; Rihanna", "Eminem", "Rihanna")
        check("Eminem / Rihanna", "Eminem", "Rihanna")
        check("Eminem\u0000Rihanna", "Eminem", "Rihanna")
    }

    @Test fun weakSeparatorsSplitWhenSomeoneIsKnown() {
        check("Eminem & Dr. Dre", "Eminem", "Dr. Dre")
        check("Eminem, Dr. Dre & 50 Cent", "Eminem", "Dr. Dre", "50 Cent")
        check("Eminem x Juice WRLD", "Eminem", "Juice WRLD")
        check("Eminem + Someone New", "Eminem", "Someone New")
        check("Eminem and Rihanna", "Eminem", "Rihanna")
        check("Eminem vs. Dr. Dre", "Eminem", "Dr. Dre")
        check("Eminem & The Weeknd", "Eminem", "The Weeknd")
    }

    @Test fun bandNamesStayTogether() {
        check("Simon & Garfunkel", "Simon & Garfunkel")
        check("AC/DC", "AC/DC")
        check("Unknown A & Unknown B", "Unknown A & Unknown B")
        check("Earth, Wind & Fire", "Earth, Wind & Fire")
        check("Florence + the Machine", "Florence + the Machine")
        check("Tyler, The Creator & Kali Uchis", "Tyler, The Creator", "Kali Uchis")
        check("Joe Strummer & the Mescaleros", "Joe Strummer & the Mescaleros")
        check("Simon & Garfunkel feat. Rihanna", "Simon & Garfunkel", "Rihanna")
    }

    @Test fun userKeepListAndDisabling() {
        check("Eminem & Rihanna", "Eminem & Rihanna", s = splitter(keep = listOf("eminem & rihanna")))
        val off = ArtistSplitter.DISABLED
        off.learn(solo)
        assertEquals(listOf("Eminem feat. Rihanna"), off.artistsOf(t("Eminem feat. Rihanna")))
    }

    @Test fun guestsInTitlesCount() {
        val s = splitter()
        assertEquals(listOf("Eminem", "Rihanna"), s.artistsOf(t("Eminem", "Love the Way You Lie (feat. Rihanna)")))
        assertEquals(listOf("Eminem", "Rihanna"), s.artistsOf(t("Eminem", "The Monster [ft. Rihanna]")))
        assertEquals(listOf("Eminem", "Dr. Dre", "Someone New"), s.artistsOf(t("Eminem", "Song (with Dr. Dre & Someone New)")))
        assertEquals(listOf("Eminem", "Rihanna"), s.artistsOf(t("Eminem", "Song feat. Rihanna")))
        // Words that merely contain "feat"/"with" are not guests.
        assertEquals(listOf("Eminem"), s.artistsOf(t("Eminem", "Defeat with Grace")))
    }

    @Test fun spellingFollowsTheLibrary() {
        check("EMINEM & rihanna", "Eminem", "Rihanna")
    }

    @Test fun indexPutsCollaborationsOnEveryArtistPage() {
        val extra = listOf(
            t("Eminem feat. Rihanna", "Love the Way You Lie"),
            t("Eminem & Dr. Dre", "Forgot About Dre"),
            t("Eminem", "The Monster (feat. Rihanna)"),
            t("Rihanna, Eminem & Brand New Person", "Numb"),
        )
        val all = solo + extra
        val idx = LibraryIndex(all)
        fun titles(a: String) = idx.tracksOfArtist(a).map { it.title }.sorted()
        assertEquals(listOf("Forgot About Dre", "Love the Way You Lie", "Numb", "Song 1", "The Monster (feat. Rihanna)"), titles("Eminem"))
        assertEquals(listOf("Love the Way You Lie", "Numb", "Song 2", "The Monster (feat. Rihanna)"), titles("Rihanna"))
        assertEquals(listOf("Forgot About Dre", "Song 3"), titles("Dr. Dre"))
        assertEquals(listOf("Numb"), titles("Brand New Person"))
        assertEquals(titles("Eminem"), idx.tracksOfArtist("eminem").map { it.title }.sorted())
        val names = idx.artists.map { it.first }
        assertEquals(names.distinct(), names)
        assertTrue(names.toString(), "Eminem feat. Rihanna" !in names)
        assertTrue(names.toString(), "Simon & Garfunkel" in names && "AC/DC" in names)

        val raw = LibraryIndex(all, ArtistSplitter.DISABLED)
        assertTrue(raw.artists.any { it.first == "Eminem feat. Rihanna" })
        assertEquals(1, raw.tracksOfArtist("Rihanna").size)
    }
}
