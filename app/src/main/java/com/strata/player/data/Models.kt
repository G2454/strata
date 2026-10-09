package com.strata.player.data

import android.content.ContentUris
import android.net.Uri

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val genre: String,
    val composer: String,
    val year: Int,
    val trackNo: Int,
    val discNo: Int,
    val durationMs: Long,
    val path: String,
    val folder: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val mime: String,
    val bitrateBps: Int,
) {
    val codec: String get() = codecOf(mime, path)
    val lossless: Boolean get() = codec in LOSSLESS
    val fileName: String get() = path.substringAfterLast('/')
    val artUri: Uri get() = albumArtUri(albumId)

    companion object {
        val LOSSLESS = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "DSD")
    }
}

fun albumArtUri(albumId: Long): Uri =
    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

fun codecOf(mime: String, path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when {
        ext == "flac" || mime.contains("flac") -> "FLAC"
        ext == "mp3" || mime == "audio/mpeg" -> "MP3"
        ext == "opus" -> "Opus"
        ext == "ogg" || ext == "oga" -> if (mime.contains("opus")) "Opus" else "Vorbis"
        ext == "wav" || mime.contains("wav") -> "WAV"
        ext == "aif" || ext == "aiff" -> "AIFF"
        ext == "ape" -> "APE"
        ext == "wv" -> "WV"
        ext == "dsf" || ext == "dff" -> "DSD"
        ext == "m4a" || ext == "mp4" || ext == "aac" || mime.contains("mp4") || mime.contains("aac") -> if (mime.contains("alac")) "ALAC" else "AAC"
        else -> ext.uppercase().ifEmpty { "AUDIO" }
    }
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val trackIds: List<Long>,
    val durationMs: Long,
    val folder: String,
)

/** Technical stream info, read from the file header. */
data class Tech(val sampleRate: Int, val bits: Int, val channels: Int)

data class TagInfo(
    val tech: Tech?,
    val totalSamples: Long = 0,
    val rgTrackGain: Float? = null,
    val rgTrackPeak: Float? = null,
    val rgAlbumGain: Float? = null,
    val rgAlbumPeak: Float? = null,
    val lyrics: String? = null,
    val tagType: String = "",
    val hasPicture: Boolean = false,
)

data class Playlist(
    val id: String,
    val name: String,
    val ids: List<Long> = emptyList(),
    /** When set, this is an auto playlist filled by the query. */
    val query: String? = null,
) {
    val auto: Boolean get() = query != null
}

data class UserData(
    val favorites: Set<Long> = emptySet(),
    val ratings: Map<Long, Int> = emptyMap(),
    val plays: Map<Long, Int> = emptyMap(),
    val firstPlayed: Map<Long, Long> = emptyMap(),
    val lastPlayed: Map<Long, Long> = emptyMap(),
    val history: List<Long> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val overrides: Map<Long, Map<String, String>> = emptyMap(),
    val recent: List<String> = emptyList(),
)

enum class Order(val label: String, val desc: String) {
    DEFAULT("Default", "Play the list once, in order"),
    REPEAT_PLAYLIST("Repeat playlist", "Start over at the end"),
    REPEAT_TRACK("Repeat track", "Loop the current track"),
    RANDOM("Random", "Pick any track each time, forever"),
    SHUFFLE_TRACKS("Shuffle tracks", "Every track once, mixed up"),
    SHUFFLE_ALBUMS("Shuffle albums", "Albums in random order, tracks in order"),
    SHUFFLE_FOLDERS("Shuffle folders", "Folders in random order, files in order");

    val isShuffle: Boolean get() = this == RANDOM || this == SHUFFLE_TRACKS || this == SHUFFLE_ALBUMS || this == SHUFFLE_FOLDERS
    val isRepeat: Boolean get() = this == REPEAT_PLAYLIST || this == REPEAT_TRACK
}

enum class RgMode(val label: String) { OFF("Off"), TRACK("Track"), ALBUM("Album"), SMART("Smart") }

enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

enum class SortKey(val label: String) { TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), ADDED("Date added"), PLAYS("Most played"), LENGTH("Length") }

val DSP_STAGES = listOf("rg", "eq", "widen", "mono", "balance", "limit")

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val colorFromArt: Boolean = true,
    val columns: Boolean = false,
    val sort: SortKey = SortKey.TITLE,
    val eqOn: Boolean = true,
    val preset: String = "Flat",
    val bands: List<Float> = List(10) { 0f },
    val preamp: Float = 0f,
    val rgMode: RgMode = RgMode.SMART,
    val rgPreamp: Float = 0f,
    val rgPreampNoInfo: Float = 0f,
    val rgPreventClip: Boolean = true,
    val fadeSec: Int = 0,
    val skipSilence: Boolean = false,
    val speed: Float = 1f,
    val keepPitch: Boolean = true,
    val balance: Float = 0f,
    val mono: Boolean = false,
    val widen: Boolean = false,
    val limiter: Boolean = true,
    val chain: List<String> = DSP_STAGES,
    val order: Order = Order.DEFAULT,
    val ignoreShort: Boolean = true,
    val pauseOnDisconnect: Boolean = true,
    /** Give every performer of "A feat. B" / "A & B" their own artist page entry. */
    val splitArtists: Boolean = true,
    /** Artist names never to split, separated by ";" or new lines. */
    val keepTogether: String = "",
)

fun Settings.artistSplitter(): ArtistSplitter =
    if (!splitArtists) ArtistSplitter.DISABLED
    else ArtistSplitter(true, keepTogether.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() })

data class Session(
    val ctx: List<Long> = emptyList(),
    val ctxName: String = "All tracks",
    val currentId: Long = -1,
    val positionMs: Long = 0,
)

val EQ_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
val EQ_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

val EQ_PRESETS: Map<String, List<Float>> = linkedMapOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Rock" to listOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 3f, 4f, 4f),
    "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
    "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
    "Classical" to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
    "Bass boost" to listOf(7f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Vocal" to listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
    "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
    "Loudness" to listOf(6f, 4f, 1f, 0f, -1f, 0f, 0f, 1f, 3f, 5f),
)
