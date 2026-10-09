package com.strata.player.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore

/** Reads the device's music collection from MediaStore. */
object MediaScanner {

    fun scan(context: Context, ignoreShort: Boolean): List<Track> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.COMPOSER,
        )
        if (Build.VERSION.SDK_INT >= 30) {
            cols += listOf("genre", "bitrate", "album_artist", "disc_number")
        }
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            if (ignoreShort) append(" AND ${MediaStore.Audio.Media.DURATION} >= 30000")
        }
        val out = ArrayList<Track>()
        context.contentResolver.query(collection, cols.toTypedArray(), selection, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iComposer = c.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
            val iGenre = c.getColumnIndex("genre")
            val iBitrate = c.getColumnIndex("bitrate")
            val iAlbumArtist = c.getColumnIndex("album_artist")
            val iDisc = c.getColumnIndex("disc_number")
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val path = c.str(iData)
                val rawTrack = c.getInt(iTrack)
                val artist = c.str(iArtist).cleanUnknown("Unknown artist")
                val disc = if (iDisc >= 0) c.str(iDisc).substringBefore('/').toIntOrNull() ?: 0 else 0
                out += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    title = c.str(iTitle).ifBlank { path.substringAfterLast('/').substringBeforeLast('.') },
                    artist = artist,
                    album = c.str(iAlbum).cleanUnknown("Unknown album"),
                    albumId = c.getLong(iAlbumId),
                    albumArtist = (if (iAlbumArtist >= 0) c.str(iAlbumArtist) else "").ifBlank { artist },
                    genre = (if (iGenre >= 0) c.str(iGenre) else "").ifBlank { "Unknown genre" },
                    composer = c.str(iComposer).ifBlank { "Unknown composer" },
                    year = c.getInt(iYear),
                    trackNo = if (rawTrack >= 1000) rawTrack % 1000 else rawTrack,
                    discNo = if (disc > 0) disc else if (rawTrack >= 1000) rawTrack / 1000 else 1,
                    durationMs = c.getLong(iDur),
                    path = path,
                    folder = path.substringBeforeLast('/', ""),
                    sizeBytes = c.getLong(iSize),
                    dateAddedSec = c.getLong(iAdded),
                    mime = c.str(iMime),
                    bitrateBps = if (iBitrate >= 0) c.getInt(iBitrate) else 0,
                )
            }
        }
        return out
    }

    private fun Cursor.str(i: Int): String = if (i < 0 || isNull(i)) "" else getString(i) ?: ""

    private fun String.cleanUnknown(fallback: String): String =
        if (isBlank() || this == "<unknown>") fallback else this
}

/** Groupings computed once per scan. */
class LibraryIndex(val tracks: List<Track>, splitter: ArtistSplitter = ArtistSplitter()) {
    val byId: Map<Long, Track> = tracks.associateBy { it.id }

    /** Every performer of each track: "Eminem feat. Rihanna" counts for both Eminem and Rihanna. */
    val artistsById: Map<Long, List<String>> = run {
        splitter.learn(tracks)
        tracks.associate { it.id to splitter.artistsOf(it) }
    }

    fun artistsOf(t: Track): List<String> = artistsById[t.id] ?: listOf(t.artist)

    val albums: List<Album> = tracks.groupBy { it.albumId }.map { (id, ts) ->
        val sorted = ts.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title }))
        Album(
            id = id,
            title = sorted.first().album,
            artist = albumArtistOf(sorted),
            year = sorted.maxOf { it.year },
            trackIds = sorted.map { it.id },
            durationMs = sorted.sumOf { it.durationMs },
            folder = sorted.first().folder,
        )
    }.sortedBy { it.title.lowercase() }

    val albumById: Map<Long, Album> = albums.associateBy { it.id }

    /** One entry per performer, each with every track they appear on (alone or as a guest). */
    val artists: List<Pair<String, List<Track>>> = run {
        val m = LinkedHashMap<String, MutableList<Track>>()
        val names = HashMap<String, String>()
        for (t in tracks) for (a in artistsOf(t)) {
            val key = a.lowercase()
            val name = names.getOrPut(key) { a }
            m.getOrPut(name) { ArrayList() } += t
        }
        m.toList().sortedBy { it.first.lowercase() }
    }

    val tracksByArtist: Map<String, List<Track>> = artists.toMap()

    /** Tracks of an artist page; accepts any spelling of the name. */
    fun tracksOfArtist(name: String): List<Track> =
        tracksByArtist[name] ?: artists.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second ?: emptyList()
    val genres: List<Pair<String, List<Track>>> = tracks.groupBy { it.genre }.toList().sortedBy { it.first.lowercase() }
    val folders: List<Pair<String, List<Track>>> = tracks.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() }
    val composers: List<Pair<String, List<Track>>> = tracks.groupBy { it.composer }.toList().sortedBy { it.first.lowercase() }

    val totalDurationMs: Long = tracks.sumOf { it.durationMs }
    val totalBytes: Long = tracks.sumOf { it.sizeBytes }

    /** Track order inside an album, then album, then disc. */
    fun albumOrdered(ts: List<Track>): List<Track> =
        ts.sortedWith(compareBy({ it.album.lowercase() }, { it.albumId }, { it.discNo }, { it.trackNo }))

    private fun albumArtistOf(ts: List<Track>): String {
        val aa = ts.map { it.albumArtist }.distinct()
        return if (aa.size == 1) aa[0] else "Various artists"
    }

    companion object {
        val EMPTY = LibraryIndex(emptyList())
    }
}
