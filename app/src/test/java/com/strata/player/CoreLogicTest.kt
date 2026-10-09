package com.strata.player

import android.net.Uri
import com.strata.player.audio.*
import com.strata.player.data.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.*

private val failures = ArrayList<String>()
private fun check(name: String, ok: Boolean, info: Any? = null) {
    println((if (ok) "PASS " else "FAIL ") + name + (if (info != null) "  [$info]" else ""))
    if (!ok) failures += name + (if (info != null) " [$info]" else "")
}

private fun le32(o: ByteArrayOutputStream, v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }

private fun flac(): ByteArray {
    val o = ByteArrayOutputStream()
    o.write("fLaC".toByteArray())
    // STREAMINFO: 96000 Hz, 2 ch, 24 bits, total samples 12345678
    val si = ByteArray(34)
    val sr = 96000; val ch = 2 - 1; val bps = 24 - 1; val total = 12345678L
    si[10] = (sr shr 12).toByte(); si[11] = (sr shr 4).toByte()
    si[12] = (((sr and 0xF) shl 4) or (ch shl 1) or (bps shr 4)).toByte()
    si[13] = (((bps and 0xF) shl 4) or ((total shr 32).toInt() and 0xF)).toByte()
    si[14] = (total shr 24).toByte(); si[15] = (total shr 16).toByte(); si[16] = (total shr 8).toByte(); si[17] = total.toByte()
    o.write(0); o.write(0); o.write(0); o.write(34); o.write(si)
    // PADDING block
    o.write(1); o.write(0); o.write(0); o.write(10); o.write(ByteArray(10))
    // VORBIS_COMMENT (last)
    val vc = ByteArrayOutputStream()
    val vendor = "test".toByteArray(); le32(vc, vendor.size); vc.write(vendor)
    val cs = listOf("TITLE=Lanterns", "REPLAYGAIN_TRACK_GAIN=-6.21 dB", "REPLAYGAIN_ALBUM_GAIN=-7.40 dB", "REPLAYGAIN_TRACK_PEAK=0.987654", "LYRICS=[00:12.50]Hello\n[00:19]World")
    le32(vc, cs.size); cs.forEach { val b = it.toByteArray(); le32(vc, b.size); vc.write(b) }
    val vb = vc.toByteArray()
    o.write(0x84); o.write(vb.size shr 16); o.write(vb.size shr 8); o.write(vb.size and 255); o.write(vb)
    return o.toByteArray()
}

private fun id3(): ByteArray {
    val body = ByteArrayOutputStream()
    fun frame(id: String, data: ByteArray) { body.write(id.toByteArray()); val s = data.size; body.write(byteArrayOf((s shr 24).toByte(), (s shr 16).toByte(), (s shr 8).toByte(), s.toByte(), 0, 0)); body.write(data) }
    frame("APIC", ByteArray(300_000) { 7 })  // big cover art must be skipped, not parsed
    frame("TXXX", byteArrayOf(3) + "REPLAYGAIN_TRACK_GAIN".toByteArray() + byteArrayOf(0) + "-3.50 dB".toByteArray())
    // UTF-16 with BOM USLT
    val txt = "Line one".toByteArray(Charsets.UTF_16)
    frame("USLT", byteArrayOf(1) + "eng".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + txt)
    body.write(ByteArray(20)) // padding
    val b = body.toByteArray()
    val o = ByteArrayOutputStream()
    o.write("ID3".toByteArray()); o.write(3); o.write(0); o.write(0)
    val s = b.size; o.write(byteArrayOf(((s shr 21) and 0x7F).toByte(), ((s shr 14) and 0x7F).toByte(), ((s shr 7) and 0x7F).toByte(), (s and 0x7F).toByte()))
    o.write(b)
    // MPEG1 Layer3 128kbps 48000 Hz joint stereo: FF FB 94 44 ? -> sr idx 1 (48000)
    o.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x94.toByte(), 0x44)); o.write(ByteArray(400))
    return o.toByteArray()
}

/** Tag parsing, lyrics, query language and DSP maths (36+ checks). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreLogicTest {
@Test
fun allCoreChecks() {
    failures.clear()
    val f = TagParser.parse(ByteArrayInputStream(flac()))
    check("flac rate", f.tech?.sampleRate == 96000, f.tech)
    check("flac bits", f.tech?.bits == 24)
    check("flac ch", f.tech?.channels == 2)
    check("flac total", f.totalSamples == 12345678L, f.totalSamples)
    check("flac rg track", f.rgTrackGain == -6.21f, f.rgTrackGain)
    check("flac rg album", f.rgAlbumGain == -7.40f)
    check("flac peak", abs((f.rgTrackPeak ?: 0f) - 0.987654f) < 1e-6)
    check("flac lyrics", f.lyrics?.contains("Hello") == true)
    val m = TagParser.parse(ByteArrayInputStream(id3()))
    check("id3 rg", m.rgTrackGain == -3.5f, m.rgTrackGain)
    check("id3 lyrics utf16", m.lyrics == "Line one", m.lyrics)
    check("mp3 rate", m.tech?.sampleRate == 48000, m.tech)
    val ft = TagParser.parse(ByteArrayInputStream(flac()), techOnly = true)
    check("flac techOnly", ft.tech?.sampleRate == 96000 && ft.tech?.bits == 24 && ft.rgTrackGain == null)
    val mt = TagParser.parse(ByteArrayInputStream(id3()), techOnly = true)
    check("mp3 techOnly", mt.tech?.sampleRate == 48000, mt.tech)
    check("id3 picture flag", m.hasPicture)
    check("garbage safe", TagParser.parse(ByteArrayInputStream(ByteArray(3))).tech == null)

    val l = Lrc.parse("[ti:x]\n[00:12.50]Hello\n[00:05][00:30.1]Chorus\n\n[01:02.345]End")
    check("lrc count", l.size == 4, l)
    check("lrc order", l.map { it.timeMs } == listOf(5000L, 12500L, 30100L, 62345L), l.map { it.timeMs })
    check("lrc plain", Lrc.parse("just\ntext").all { it.timeMs == -1L })

    // Query
    val uri = Uri.parse("content://media/external/audio/media/1")
    fun tr(id: Long, title: String, artist: String, genre: String, path: String, year: Int, br: Int, dur: Long) = Track(id, uri, title, artist, "Alb", 1, artist, genre, "Comp", year, 1, 1, dur, path, "/m", 1, 0, "", br)
    val a = tr(1, "Brass Weather", "Odette Rue", "Jazz", "/m/a.flac", 2021, 0, 378000)
    val b = tr(2, "Grid City", "The Halyards", "Synthwave", "/m/b.mp3", 2023, 320000, 284000)
    val fields = object : Query.Fields {
        override fun rating(t: Track) = if (t.id == 1L) 5 else 2
        override fun plays(t: Track) = if (t.id == 1L) 12 else 0
        override fun tech(t: Track) = if (t.id == 1L) Tech(96000, 24, 2) else Tech(44100, 0, 2)
        override fun favorite(t: Track) = t.id == 2L
    }
    fun q(s: String) = listOf(a, b).filter { Query.parse(s).matches(it, fields) }.map { it.id }
    check("q plain", q("brass") == listOf(1L))
    check("q field", q("genre:jazz|synth") == listOf(1L, 2L))
    check("q bits", q("bits>=24") == listOf(1L))
    check("q format", q("format:flac") == listOf(1L))
    check("q rate exact", q("rate:96") == listOf(1L), q("rate:96"))
    check("q rate 44", q("rate:44") == listOf(2L), q("rate:44"))
    check("q rating plays", q("rating>=4 plays>10") == listOf(1L))
    check("q neg", q("-genre:jazz") == listOf(2L))
    check("q phrase", q("\"grid city\"") == listOf(2L))
    check("q kbps", q("kbps>=320") == listOf(2L))
    check("q length", q("length>360") == listOf(1L))
    check("q fav", q("fav:1") == listOf(2L))
    check("q year", q("year:2023") == listOf(2L))

    // DSP: EQ +6 dB at 1 kHz on a 1 kHz sine should raise RMS ~2x; flat stays the same.
    fun rmsAfter(p: DspParams, freq: Double, amp: Float = 0.25f): Double {
        val core = DspCore(); core.configure(48000, 2); core.params = p
        val n = 48000
        val buf = FloatArray(n * 2) { val i = it / 2; (amp * sin(2 * PI * freq * i / 48000)).toFloat() }
        core.process(buf, n)
        var s = 0.0; for (i in n until n * 2) s += buf[i] * buf[i].toDouble()   // second half (settled)
        return sqrt(s / n)
    }
    val base = rmsAfter(DspParams(limiter = false), 1000.0)
    val bands = FloatArray(10); bands[5] = 6f
    val boosted = rmsAfter(DspParams(eqOn = true, bandsDb = bands, limiter = false), 1000.0)
    val gainDb = 20 * log10(boosted / base)
    check("eq +6dB at 1k", abs(gainDb - 6.0) < 0.5, "%.2f dB".format(gainDb))
    val off = rmsAfter(DspParams(eqOn = true, bandsDb = bands, limiter = false), 125.0) / rmsAfter(DspParams(limiter = false), 125.0)
    check("eq leaves 125Hz alone", abs(20 * log10(off)) < 0.6, "%.2f dB".format(20 * log10(off)))
    val rg = rmsAfter(DspParams(gainDb = -6f, limiter = false), 1000.0) / base
    check("rg -6dB", abs(20 * log10(rg) + 6.0) < 0.1, "%.2f".format(20 * log10(rg)))
    // Limiter: +12 dB on a loud sine must stay under ceiling
    run {
        val core = DspCore(); core.configure(48000, 2); core.params = DspParams(gainDb = 12f, limiter = true)
        val n = 48000; val buf = FloatArray(n * 2) { val i = it / 2; (0.8f * sin(2 * PI * 440.0 * i / 48000)).toFloat() }
        core.process(buf, n); val mx = buf.maxOf { abs(it) }
        check("limiter ceiling", mx <= 0.98f, mx)
    }
    // Clip guard: +10 dB with peak 0.9 -> gain limited to 1/0.9
    val cg = rmsAfter(DspParams(gainDb = 10f, clipGuardPeak = 0.9f, limiter = false), 1000.0, 0.1f) / rmsAfter(DspParams(limiter = false), 1000.0, 0.1f)
    check("clip guard", abs(cg - 1 / 0.9) < 0.02, cg)
    // Balance hard left silences right channel
    run {
        val core = DspCore(); core.configure(48000, 2); core.params = DspParams(balance = -1f, limiter = false)
        val buf = FloatArray(200) { 0.5f }; core.process(buf, 100)
        check("balance left", buf[1] == 0f && buf[0] == 0.5f)
    }
    // Analyzer: 1 kHz sine lights a band near 1 kHz the most
    run {
        val core = DspCore(); core.configure(48000, 2); core.params = DspParams(limiter = false)
        val n = 8192; val buf = FloatArray(n * 2) { val i = it / 2; (0.5f * sin(2 * PI * 1000.0 * i / 48000)).toFloat() }
        core.process(buf, n)
        val bands32 = FloatArray(32); val scope = FloatArray(128)
        val pk = Analyzer().analyze(core, bands32, scope, 0)
        val top = bands32.indices.maxByOrNull { bands32[it] }!!
        val fLo = 30.0 * (16000.0 / 30.0).pow(top / 32.0); val fHi = 30.0 * (16000.0 / 30.0).pow((top + 1) / 32.0)
        check("analyzer peak band", 1000.0 in (fLo * 0.8)..(fHi * 1.2), "band $top = %.0f-%.0f Hz".format(fLo, fHi))
        check("analyzer peak meter", abs(pk[0] - 0.5f) < 0.01, pk.toList())
    }
    assertEquals("Failed checks: " + failures.joinToString("; "), 0, failures.size)
}
}
