package com.strata.player.data

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Minimal, allocation-light tag reader for the things Android's MediaStore does not expose:
 * FLAC STREAMINFO (sample rate / bit depth), Vorbis comments and ID3v2 frames
 * (ReplayGain, lyrics), and the MPEG frame header for MP3 stream info.
 */
object TagParser {

    private const val MAX_TEXT_BLOCK = 2 * 1024 * 1024

    /**
     * Full parse: stream info, ReplayGain, lyrics. Embedded pictures are skipped, never loaded.
     * With [techOnly] it stops as soon as the stream info is known (a few hundred bytes for FLAC,
     * one header skip for MP3), which is what the background library scan uses.
     */
    fun parse(input: InputStream, techOnly: Boolean = false): TagInfo {
        val d = DataInputStream(input.buffered(16 * 1024))
        val magic = ByteArray(4)
        return try {
            d.readFully(magic)
            when {
                magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() && magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte() -> parseFlac(d, techOnly)
                magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() -> parseId3(d, magic[3].toInt() and 0xFF, techOnly)
                (magic[0].toInt() and 0xFF) == 0xFF && (magic[1].toInt() and 0xE0) == 0xE0 -> TagInfo(tech = mpegTech(magic, 0), tagType = "None")
                else -> TagInfo(tech = null)
            }
        } catch (e: Exception) {
            TagInfo(tech = null)
        }
    }

    // ---------------------------------------------------------------- FLAC

    private fun parseFlac(d: DataInputStream, techOnly: Boolean): TagInfo {
        var tech: Tech? = null
        var total = 0L
        var picture = false
        val comments = HashMap<String, String>()
        while (true) {
            val h = d.readUnsignedByte()
            val last = (h and 0x80) != 0
            val type = h and 0x7F
            val len = (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 8) or d.readUnsignedByte()
            when {
                type == 0 && len >= 18 -> {
                    val b = ByteArray(len)
                    d.readFully(b)
                    val sr = (b.u(10) shl 12) or (b.u(11) shl 4) or (b.u(12) ushr 4)
                    val ch = ((b.u(12) ushr 1) and 0x7) + 1
                    val bps = (((b.u(12) and 1) shl 4) or (b.u(13) ushr 4)) + 1
                    total = ((b.u(13) and 0xF).toLong() shl 32) or (b.u(14).toLong() shl 24) or
                        (b.u(15).toLong() shl 16) or (b.u(16).toLong() shl 8) or b.u(17).toLong()
                    tech = Tech(sr, bps, ch)
                    if (techOnly) return TagInfo(tech = tech, totalSamples = total, tagType = "Vorbis comments")
                }
                type == 4 && len <= MAX_TEXT_BLOCK -> {
                    val b = ByteArray(len)
                    d.readFully(b)
                    parseVorbisComments(b, comments)
                }
                else -> {
                    if (type == 6) picture = true
                    skipFully(d, len.toLong())
                }
            }
            if (last) break
        }
        return build(tech, total, comments, "Vorbis comments", picture)
    }

    fun parseVorbisComments(b: ByteArray, out: MutableMap<String, String>) {
        var p = 0
        fun le32(): Int {
            val v = b.u(p) or (b.u(p + 1) shl 8) or (b.u(p + 2) shl 16) or (b.u(p + 3) shl 24)
            p += 4
            return v
        }
        if (b.size < 8) return
        val vendor = le32()
        if (vendor < 0 || p + vendor > b.size) return
        p += vendor
        if (p + 4 > b.size) return
        val count = le32()
        for (i in 0 until count) {
            if (p + 4 > b.size) return
            val len = le32()
            if (len < 0 || p + len > b.size) return
            val s = String(b, p, len, Charsets.UTF_8)
            p += len
            val eq = s.indexOf('=')
            if (eq > 0) {
                val key = s.substring(0, eq).uppercase()
                val value = s.substring(eq + 1)
                // Multiple values of one key are joined, like foobar2000 shows them.
                out[key] = out[key]?.let { "$it; $value" } ?: value
            }
        }
    }

    // ---------------------------------------------------------------- ID3v2 / MP3

    private fun parseId3(d: DataInputStream, version: Int, techOnly: Boolean): TagInfo {
        d.readUnsignedByte() // revision
        val flags = d.readUnsignedByte()
        val sizeBytes = ByteArray(4)
        d.readFully(sizeBytes)
        val size = syncsafe(sizeBytes, 0)
        val comments = HashMap<String, String>()
        var picture = false
        var lyrics: String? = null
        if (techOnly || (version != 3 && version != 4)) {
            skipFully(d, size.toLong())
        } else {
            // Stream frame by frame: text frames we care about are read, everything else
            // (cover art can be several MB) is skipped without allocating.
            var remaining = size
            if ((flags and 0x40) != 0 && remaining >= 4) {
                val ext = ByteArray(4)
                d.readFully(ext)
                remaining -= 4
                val extRest = if (version == 3) be32(ext, 0) else syncsafe(ext, 0) - 4
                if (extRest in 0..remaining) { skipFully(d, extRest.toLong()); remaining -= extRest }
            }
            val hdr = ByteArray(10)
            while (remaining >= 10) {
                d.readFully(hdr)
                remaining -= 10
                if (hdr[0].toInt() == 0) break
                val id = String(hdr, 0, 4, Charsets.ISO_8859_1)
                val fsize = if (version == 4) syncsafe(hdr, 4) else be32(hdr, 4)
                val fflags = (hdr.u(8) shl 8) or hdr.u(9)
                if (fsize <= 0 || fsize > remaining) break
                if ((id == "TXXX" || id == "USLT") && fsize <= 512 * 1024) {
                    val body = ByteArray(fsize)
                    d.readFully(body)
                    val start = if (version == 4 && (fflags and 0x0001) != 0) 4 else 0 // data length indicator
                    val end = fsize
                    if (id == "TXXX" && end - start > 1) {
                        val enc = body.u(start)
                        val descEnd = terminator(enc, body, start + 1, end)
                        val desc = decode(enc, body, start + 1, descEnd)
                        val valueStart = (descEnd + termLen(enc)).coerceAtMost(end)
                        comments[desc.uppercase()] = decode(enc, body, valueStart, end)
                    } else if (id == "USLT" && end - start > 4) {
                        val enc = body.u(start)
                        val descEnd = terminator(enc, body, start + 4, end)
                        val textStart = (descEnd + termLen(enc)).coerceAtMost(end)
                        lyrics = decode(enc, body, textStart, end)
                    }
                } else {
                    if (id == "APIC") picture = true
                    skipFully(d, fsize.toLong())
                }
                remaining -= fsize
            }
            if (remaining > 0) skipFully(d, remaining.toLong())
        }
        // MPEG frame header follows the tag (allow some padding/junk).
        var tech: Tech? = null
        val probe = ByteArray(4 * 1024)
        val n = readUpTo(d, probe)
        var i = 0
        while (i + 4 <= n) {
            if ((probe[i].toInt() and 0xFF) == 0xFF && (probe[i + 1].toInt() and 0xE0) == 0xE0) {
                tech = mpegTech(probe, i)
                if (tech != null) break
            }
            i++
        }
        if (lyrics != null) comments["LYRICS"] = lyrics!!
        return build(tech, 0, comments, if (version == 4) "ID3v2.4" else "ID3v2.3", picture)
    }

    private fun mpegTech(b: ByteArray, at: Int): Tech? {
        if (at + 4 > b.size) return null
        val b1 = b.u(at + 1)
        val b2 = b.u(at + 2)
        val b3 = b.u(at + 3)
        val ver = (b1 ushr 3) and 3
        val layer = (b1 ushr 1) and 3
        val srIdx = (b2 ushr 2) and 3
        val brIdx = (b2 ushr 4) and 0xF
        if (ver == 1 || layer == 0 || srIdx == 3 || brIdx == 0xF || brIdx == 0) return null
        val base = intArrayOf(44100, 48000, 32000)[srIdx]
        val sr = when (ver) {
            3 -> base
            2 -> base / 2
            else -> base / 4
        }
        val ch = if ((b3 ushr 6) == 3) 1 else 2
        return Tech(sr, 0, ch)
    }

    // ---------------------------------------------------------------- helpers

    private fun build(tech: Tech?, total: Long, c: Map<String, String>, type: String, picture: Boolean): TagInfo {
        fun num(key: String): Float? = c[key]?.let { Regex("[-+]?\\d+(?:[.,]\\d+)?").find(it.replace('−', '-'))?.value?.replace(',', '.')?.toFloatOrNull() }
        val lyrics = c["LYRICS"] ?: c["UNSYNCEDLYRICS"] ?: c["UNSYNCED LYRICS"]
        return TagInfo(
            tech = tech,
            totalSamples = total,
            rgTrackGain = num("REPLAYGAIN_TRACK_GAIN"),
            rgTrackPeak = num("REPLAYGAIN_TRACK_PEAK"),
            rgAlbumGain = num("REPLAYGAIN_ALBUM_GAIN"),
            rgAlbumPeak = num("REPLAYGAIN_ALBUM_PEAK"),
            lyrics = lyrics?.takeIf { it.isNotBlank() },
            tagType = type,
            hasPicture = picture,
        )
    }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun termLen(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    private fun terminator(enc: Int, b: ByteArray, from: Int, to: Int): Int {
        if (enc == 1 || enc == 2) {
            var i = from
            while (i + 1 < to) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i
                i += 2
            }
            return to
        }
        for (i in from until to) if (b[i].toInt() == 0) return i
        return to
    }

    private fun decode(enc: Int, b: ByteArray, from: Int, to: Int): String {
        if (to <= from) return ""
        val cs: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(b, from, to - from, cs).trimEnd('\u0000')
    }

    private fun skipFully(d: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = d.skip(left)
            if (s <= 0) {
                if (d.read() < 0) throw EOFException()
                left--
            } else {
                left -= s
            }
        }
    }

    private fun readUpTo(d: DataInputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = d.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}

/** A parsed lyrics file: synced lines carry timestamps, plain lyrics have time = -1. */
data class LyricLine(val timeMs: Long, val text: String)

object Lrc {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(text: String): List<LyricLine> {
        val synced = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        var offset = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[offset:", ignoreCase = true)) {
                offset = line.substringAfter(':').substringBefore(']').trim().toLongOrNull() ?: 0L
                continue
            }
            val matches = stamp.findAll(line).toList()
            if (matches.isEmpty()) {
                if (!(line.startsWith("[") && line.endsWith("]"))) plain += LyricLine(-1, line)
                continue
            }
            val content = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                synced += LyricLine((min * 60_000 + sec * 1000 + frac - offset).coerceAtLeast(0), content)
            }
        }
        return if (synced.isNotEmpty()) synced.sortedBy { it.timeMs } else plain.dropWhile { it.text.isEmpty() }
    }
}
