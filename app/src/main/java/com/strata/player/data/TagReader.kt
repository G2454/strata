package com.strata.player.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Reads and caches per-file tag info. All functions block: call them off the main thread. */
class TagReader(private val context: Context) {

    private val cache = ConcurrentHashMap<Long, TagInfo>()

    fun cached(id: Long): TagInfo? = cache[id]

    fun read(track: Track): TagInfo {
        cache[track.id]?.let { return it }
        var info = try {
            context.contentResolver.openInputStream(track.uri)?.use { TagParser.parse(it) } ?: TagInfo(null)
        } catch (e: Exception) {
            TagInfo(null)
        }
        if (info.tech == null) info = info.copy(tech = extractorTech(track))
        cache[track.id] = info
        return info
    }

    /** Stream info only (sample rate / bits / channels). Cheap: reads a file header, never the whole tag. */
    fun readTech(track: Track): Tech? {
        cache[track.id]?.tech?.let { return it }
        val fromHeader = try {
            context.contentResolver.openInputStream(track.uri)?.use { TagParser.parse(it, techOnly = true).tech }
        } catch (e: Exception) {
            null
        }
        return fromHeader ?: extractorTech(track)
    }

    /** Lyrics: embedded first, then a sidecar .lrc next to the file (readable on most devices). */
    fun lyrics(track: Track): List<LyricLine>? {
        val embedded = read(track).lyrics
        if (embedded != null) return Lrc.parse(embedded).takeIf { it.isNotEmpty() }
        val base = track.path.substringBeforeLast('.', track.path)
        for (ext in listOf(".lrc", ".LRC", ".txt")) {
            try {
                val f = File(base + ext)
                if (f.canRead()) return Lrc.parse(f.readText()).takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun extractorTech(track: Track): Tech? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, track.uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val sr = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                val bits = when {
                    f.containsKey("bits-per-sample") -> f.getInteger("bits-per-sample")
                    f.containsKey(MediaFormat.KEY_PCM_ENCODING) -> when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                        android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                        else -> if (track.lossless) 16 else 0
                    }
                    track.lossless -> 16
                    else -> 0
                }
                return Tech(sr, bits, ch)
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }
}
