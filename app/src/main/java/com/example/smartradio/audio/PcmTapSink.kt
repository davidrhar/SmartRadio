package com.example.smartradio.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implements TeeAudioProcessor.AudioBufferSink so we can listen in on the
 * decoded PCM audio ExoPlayer is about to play, without altering it.
 * Audio arrives as 16-bit PCM at whatever the stream's sample rate/channel
 * count is; we downmix to mono and hand off fixed-size windows sized for
 * YAMNet (15600 samples @ 16 kHz ≈ 0.975s) to the classifier.
 */
@UnstableApi
class PcmTapSink(
    private val onWindowReady: (FloatArray, sampleRateHz: Int) -> Unit
) : TeeAudioProcessor.AudioBufferSink {

    private var sampleRateHz = 16000
    private var channelCount = 1
    private var pcmEncoding = 2 // 16-bit

    private val windowSamples = 15600
    private var pending = FloatArray(0)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.pcmEncoding = encoding
        pending = FloatArray(0)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val monoAtSourceRate = toMonoFloat(buffer)
        val samples = resampleTo16k(monoAtSourceRate, sampleRateHz)
        var combined = FloatArray(pending.size + samples.size)
        pending.copyInto(combined)
        samples.copyInto(combined, pending.size)

        var offset = 0
        while (combined.size - offset >= windowSamples) {
            val window = combined.copyOfRange(offset, offset + windowSamples)
            onWindowReady(window, sampleRateHz)
            offset += windowSamples
        }
        pending = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else FloatArray(0)
    }

    private fun resampleTo16k(input: FloatArray, sourceRateHz: Int): FloatArray {
        if (sourceRateHz == 16000 || input.isEmpty()) return input
        val ratio = 16000.0 / sourceRateHz
        val outLength = (input.size * ratio).toInt()
        val out = FloatArray(outLength)
        for (i in out.indices) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceIn(0, input.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    private fun toMonoFloat(buffer: ByteBuffer): FloatArray {
        val bb = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val shortsRemaining = bb.remaining() / 2
        val frames = shortsRemaining / channelCount
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channelCount) {
                sum += bb.short.toInt()
            }
            out[i] = (sum.toFloat() / channelCount) / 32768f
        }
        return out
    }
}
