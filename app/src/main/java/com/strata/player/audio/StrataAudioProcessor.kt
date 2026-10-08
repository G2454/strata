@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.strata.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bridges Media3's PCM pipeline to [DspCore]. Accepts 16-bit and float PCM, outputs the same encoding. */
class StrataAudioProcessor(val core: DspCore) : BaseAudioProcessor() {

    private var scratch = FloatArray(0)
    private var encoding = C.ENCODING_PCM_16BIT
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        core.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val samples = bytes / bytesPerSample
        val frames = samples / channels
        if (scratch.size < samples) scratch = FloatArray(samples)
        val s = scratch
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until samples) s[i] = input.getFloat()
        } else {
            for (i in 0 until samples) s[i] = input.getShort() / 32768f
        }
        // Consume any trailing partial sample bytes.
        input.position(input.limit())

        core.process(s, frames)

        val out = replaceOutputBuffer(samples * bytesPerSample)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until samples) out.putFloat(s[i])
        } else {
            for (i in 0 until samples) {
                val v = (s[i] * 32767f).toInt()
                out.putShort(v.coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
    }

    override fun onFlush() {
        core.reset()
    }

    override fun onReset() {
        scratch = FloatArray(0)
    }
}
