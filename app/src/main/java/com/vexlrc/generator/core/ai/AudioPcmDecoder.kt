package com.vexlrc.generator.core.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.floor

/** Decodes a selected audio URI to 16 kHz mono float PCM for whisper.cpp. */
object AudioPcmDecoder {
    private const val TARGET_RATE = 16_000
    private const val MAX_AUDIO_MS = 20 * 60 * 1000L

    fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = candidate
                    break
                }
            }
            require(track >= 0 && format != null) { "No audio track found" }
            val sourceDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L
            require(sourceDuration <= 0L || sourceDuration <= MAX_AUDIO_MS) { "Audio is longer than 20 minutes; split it into shorter sections" }
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val mono = ArrayList<Float>()
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytesPerSample = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> 2
                            }
                            val frameBytes = channels * bytesPerSample
                            while (buffer.remaining() >= frameBytes) {
                                var sum = 0f
                                repeat(channels) {
                                    sum += when (encoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                                        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                                        else -> buffer.short.toFloat() / 32768f
                                    }
                                }
                                mono.add((sum / channels).coerceIn(-1f, 1f))
                                if (mono.size.toLong() * 1000L / rate > MAX_AUDIO_MS) {
                                    error("Decoded audio exceeds the 20-minute safety limit")
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            require(mono.isNotEmpty()) { "No decoded audio samples" }
            if (rate == TARGET_RATE) return mono.toFloatArray()
            val outputCount = floor(mono.size.toDouble() * TARGET_RATE / rate).toInt().coerceAtLeast(1)
            return FloatArray(outputCount) { index ->
                val sourcePosition = index.toDouble() * rate / TARGET_RATE
                val left = sourcePosition.toInt().coerceIn(0, mono.lastIndex)
                val right = (left + 1).coerceAtMost(mono.lastIndex)
                val fraction = (sourcePosition - left).toFloat()
                mono[left] * (1f - fraction) + mono[right] * fraction
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
