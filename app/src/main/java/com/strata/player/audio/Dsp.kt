package com.strata.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Immutable snapshot of everything the DSP needs. Swapped atomically from the UI thread. */
data class DspParams(
    val eqOn: Boolean = false,
    val bandsDb: FloatArray = FloatArray(10),
    val preampDb: Float = 0f,
    val gainDb: Float = 0f,          // ReplayGain + RG preamp, already resolved for the current track
    val clipGuardPeak: Float = 0f,   // >0: keep gain * peak <= 1 (ReplayGain "prevent clipping")
    val widen: Boolean = false,
    val widenAmount: Float = 0.35f,
    val mono: Boolean = false,
    val balance: Float = 0f,         // -1 .. 1
    val limiter: Boolean = true,
    val chain: List<String> = listOf("rg", "eq", "widen", "mono", "balance", "limit"),
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Pure-Kotlin float DSP core. Works on interleaved float frames in [-1, 1].
 * Kept free of Android/Media3 types so it can be unit-tested on the JVM.
 */
class DspCore {
    @Volatile var params: DspParams = DspParams()

    private var sampleRate = 44100
    private var channels = 2
    private var applied: DspParams? = null

    // Biquad state: [band][channel]
    private val b0 = DoubleArray(10); private val b1 = DoubleArray(10); private val b2 = DoubleArray(10)
    private val a1 = DoubleArray(10); private val a2 = DoubleArray(10)
    private val active = BooleanArray(10)
    private var z1 = Array(10) { DoubleArray(8) }
    private var z2 = Array(10) { DoubleArray(8) }

    private var gainCur = 1.0
    private var gainTarget = 1.0
    private var preampLin = 1.0
    private var limGain = 1.0
    private val limCeiling = 0.977 // -0.2 dBFS
    private var limRelease = 0.0

    private val stageOrder = IntArray(6)
    private var stageCount = 0

    // Visualizer tap: interleaved stereo ring buffer.
    val tapSize = 8192
    val tap = FloatArray(tapSize * 2)
    @Volatile var tapWrite = 0
    @Volatile var tapRate = 44100

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels.coerceIn(1, 8)
        tapRate = sampleRate
        applied = null
        reset()
    }

    fun reset() {
        z1 = Array(10) { DoubleArray(8) }
        z2 = Array(10) { DoubleArray(8) }
        limGain = 1.0
        gainCur = gainTarget
    }

    private fun refresh() {
        val p = params
        if (p === applied) return
        applied = p
        // EQ coefficients (RBJ peaking, ~1 octave bandwidth).
        val q = 1.41
        for (i in 0 until 10) {
            val f0 = FREQS[i].toDouble()
            val db = if (p.eqOn) p.bandsDb.getOrElse(i) { 0f }.toDouble() else 0.0
            if (abs(db) < 0.05 || f0 >= sampleRate * 0.45) {
                active[i] = false
                continue
            }
            active[i] = true
            val a = 10.0.pow(db / 40.0)
            val w0 = 2.0 * PI * f0 / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cw = cos(w0)
            val a0 = 1.0 + alpha / a
            b0[i] = (1.0 + alpha * a) / a0
            b1[i] = (-2.0 * cw) / a0
            b2[i] = (1.0 - alpha * a) / a0
            a1[i] = (-2.0 * cw) / a0
            a2[i] = (1.0 - alpha / a) / a0
        }
        preampLin = if (p.eqOn) dbToLin(p.preampDb) else 1.0
        var g = dbToLin(p.gainDb)
        if (p.clipGuardPeak > 0f && g * p.clipGuardPeak > 1.0) g = 1.0 / p.clipGuardPeak
        gainTarget = g
        limRelease = 1.0 - Math.exp(-1.0 / (0.25 * sampleRate)) // 250 ms release
        stageCount = 0
        for (name in p.chain) {
            val idx = STAGES.indexOf(name)
            if (idx >= 0 && stageCount < stageOrder.size) stageOrder[stageCount++] = idx
        }
    }

    /** Processes [frames] interleaved frames in place. */
    fun process(buf: FloatArray, frames: Int) {
        refresh()
        val p = applied ?: return
        val ch = channels
        val gainStep = 1.0 / (0.05 * sampleRate) // ~50 ms gain ramp
        var w = tapWrite
        for (f in 0 until frames) {
            val base = f * ch
            for (s in 0 until stageCount) {
                when (stageOrder[s]) {
                    0 -> { // ReplayGain
                        if (gainCur != gainTarget) {
                            gainCur += (gainTarget - gainCur).coerceIn(-gainStep, gainStep)
                        }
                        if (gainCur != 1.0) for (c in 0 until ch) buf[base + c] = (buf[base + c] * gainCur).toFloat()
                    }
                    1 -> if (p.eqOn) { // Equalizer
                        for (c in 0 until ch) {
                            var x = buf[base + c] * preampLin
                            if (c < 8) {
                                for (i in 0 until 10) {
                                    if (!active[i]) continue
                                    val y = b0[i] * x + z1[i][c]
                                    z1[i][c] = b1[i] * x - a1[i] * y + z2[i][c]
                                    z2[i][c] = b2[i] * x - a2[i] * y
                                    x = y
                                }
                            }
                            buf[base + c] = x.toFloat()
                        }
                    }
                    2 -> if (p.widen && ch == 2) { // Mid/side widening
                        val l = buf[base]; val r = buf[base + 1]
                        val m = (l + r) * 0.5f
                        val sd = (l - r) * 0.5f * (1f + p.widenAmount)
                        buf[base] = m + sd; buf[base + 1] = m - sd
                    }
                    3 -> if (p.mono && ch >= 2) {
                        val m = (buf[base] + buf[base + 1]) * 0.5f
                        buf[base] = m; buf[base + 1] = m
                    }
                    4 -> if (p.balance != 0f && ch >= 2) {
                        buf[base] *= (1f - p.balance).coerceAtMost(1f)
                        buf[base + 1] *= (1f + p.balance).coerceAtMost(1f)
                    }
                    5 -> if (p.limiter) { // Linked peak limiter, instant attack
                        var peak = 0f
                        for (c in 0 until ch) { val a = abs(buf[base + c]); if (a > peak) peak = a }
                        val need = if (peak * limGain > limCeiling) limCeiling / peak else 1.0
                        limGain = if (need < limGain) need else limGain + (1.0 - limGain) * limRelease
                        if (limGain < 0.9999) for (c in 0 until ch) buf[base + c] = (buf[base + c] * limGain).toFloat()
                    }
                }
            }
            // Hard clip as a last resort.
            for (c in 0 until ch) {
                val v = buf[base + c]
                if (v > 1f) buf[base + c] = 1f else if (v < -1f) buf[base + c] = -1f
            }
            tap[w * 2] = buf[base]
            tap[w * 2 + 1] = if (ch > 1) buf[base + 1] else buf[base]
            w++
            if (w >= tapSize) w = 0
        }
        tapWrite = w
    }

    companion object {
        val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val STAGES = listOf("rg", "eq", "widen", "mono", "balance", "limit")
        fun dbToLin(db: Float): Double = 10.0.pow(db / 20.0)
    }
}

/** Radix-2 FFT spectrum + meters computed from the DSP tap. */
class Analyzer(private val n: Int = 2048) {
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
    private val smooth = FloatArray(64)

    /**
     * Fills [bands] with 0..1 bar heights (log-spaced 30 Hz–16 kHz) and returns
     * [peakL, peakR] in 0..1 linear for the latest [n] frames of the tap.
     * [latencyFrames] reads that far behind the write head, to line up with what you hear.
     */
    fun analyze(core: DspCore, bands: FloatArray, scope: FloatArray, latencyFrames: Int): FloatArray {
        val size = core.tapSize
        val tap = core.tap
        val end = (core.tapWrite - latencyFrames).mod(size)
        var pl = 0f
        var pr = 0f
        for (i in 0 until n) {
            val idx = (end - n + i).mod(size)
            val l = tap[idx * 2]
            val r = tap[idx * 2 + 1]
            if (abs(l) > pl) pl = abs(l)
            if (abs(r) > pr) pr = abs(r)
            re[i] = ((l + r) * 0.5) * window[i]
            im[i] = 0.0
        }
        // Oscilloscope: last scope.size samples, decimated by 2.
        for (i in scope.indices) {
            val idx = (end - scope.size * 2 + i * 2).mod(size)
            scope[i] = (tap[idx * 2] + tap[idx * 2 + 1]) * 0.5f
        }
        fft(re, im)
        val rate = core.tapRate.coerceAtLeast(8000)
        val count = bands.size
        val fLo = 30.0
        val fHi = minOf(16000.0, rate / 2.0)
        for (b in 0 until count) {
            val f1 = fLo * (fHi / fLo).pow(b.toDouble() / count)
            val f2 = fLo * (fHi / fLo).pow((b + 1).toDouble() / count)
            val k1 = (f1 * n / rate).toInt().coerceIn(1, n / 2 - 1)
            val k2 = (f2 * n / rate).toInt().coerceIn(k1 + 1, n / 2)
            var m = 0.0
            for (k in k1 until k2) {
                val mag = re[k] * re[k] + im[k] * im[k]
                if (mag > m) m = mag
            }
            val db = 10.0 * Math.log10(m / (n * n / 16.0) + 1e-12) // ~0 dB for a full-scale sine
            val v = ((db + 66.0) / 66.0).coerceIn(0.0, 1.0).toFloat()
            val prev = if (b < smooth.size) smooth[b] else 0f
            val out = if (v > prev) v else prev * 0.82f + v * 0.18f
            if (b < smooth.size) smooth[b] = out
            bands[b] = out
        }
        return floatArrayOf(pl, pr)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
