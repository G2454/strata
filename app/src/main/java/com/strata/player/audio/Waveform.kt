package com.strata.player.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Decodes a file once to build a real peak waveform for the seek bar. Results are cached on disk. */
object Waveform {
    const val BARS = 64
    private val memory = object : LinkedHashMap<Long, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FloatArray>?) = size > 120
    }

    fun cached(id: Long): FloatArray? = synchronized(memory) { memory[id] }

    suspend fun load(context: Context, id: Long, uri: Uri, cacheFile: File): FloatArray? = withContext(Dispatchers.Default) {
        cached(id)?.let { return@withContext it }
        if (cacheFile.exists()) {
            try {
                val bytes = cacheFile.readBytes()
                if (bytes.size == BARS) {
                    val w = FloatArray(BARS) { (bytes[it].toInt() and 0xFF) / 255f }
                    synchronized(memory) { memory[id] = w }
                    return@withContext w
                }
            } catch (_: Exception) {
            }
        }
        val decoded: FloatArray? = try { decode(context, uri) } catch (e: Exception) { null }
        val w = decoded ?: return@withContext null
        synchronized(memory) { memory[id] = w }
        try {
            cacheFile.writeBytes(ByteArray(BARS) { (w[it] * 255f).toInt().coerceIn(0, 255).toByte() })
        } catch (_: Exception) {
        }
        w
    }

    private suspend fun decode(context: Context, uri: Uri): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            ex.selectTrack(track)
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs <= 0) return null
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val peaks = FloatArray(BARS)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmFloat = false
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 5_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        val out = c.getOutputBuffer(outIdx)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val b = out.order(ByteOrder.nativeOrder())
                            val bar = ((info.presentationTimeUs.toDouble() / durUs) * BARS).toInt().coerceIn(0, BARS - 1)
                            var m = peaks[bar]
                            if (pcmFloat) {
                                val fb = b.asFloatBuffer()
                                var i = 0
                                val step = channels * 4
                                while (i < fb.limit()) { val v = abs(fb.get(i)); if (v > m) m = v; i += step }
                            } else {
                                val sb = b.asShortBuffer()
                                var i = 0
                                val step = channels * 4
                                while (i < sb.limit()) { val v = abs(sb.get(i) / 32768f); if (v > m) m = v; i += step }
                            }
                            peaks[bar] = m
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val max = peaks.maxOrNull()?.takeIf { it > 0f } ?: return null
            // Gentle curve so quiet passages stay visible.
            return FloatArray(BARS) { Math.sqrt((peaks[it] / max).toDouble()).toFloat().coerceIn(0.06f, 1f) }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
