package com.vexlrc.generator.core.waveform

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Decodes the selected audio track with Android's platform codec and reduces it
 * to a bounded peak envelope. It never stores the full decoded audio in memory.
 */
object WaveformAudioDecoder {
    fun decode(context: Context, uri: Uri, bucketCount: Int = 1400): List<Float> {
        require(bucketCount > 0) { "bucketCount must be positive" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = index
                    inputFormat = format
                    break
                }
            }
            require(track >= 0 && inputFormat != null) { "No audio track found" }
            extractor.selectTrack(track)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else 0L
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val expectedFrames = if (durationUs > 0L) {
                (durationUs * sampleRate / 1_000_000L).coerceAtLeast(1L)
            } else 0L
            val peaks = FloatArray(bucketCount)
            var frameCursor = 0L
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        encoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
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
                                var framePeak = 0f
                                repeat(channels) {
                                    val sample = when (encoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> abs(buffer.float).coerceIn(0f, 1f)
                                        AudioFormat.ENCODING_PCM_8BIT -> abs((buffer.get().toInt() and 0xFF) - 128) / 128f
                                        else -> abs(buffer.short.toInt()) / 32768f
                                    }
                                    framePeak = maxOf(framePeak, sample)
                                }
                                val bucket = if (expectedFrames > 0L) {
                                    ((frameCursor * bucketCount) / expectedFrames).toInt().coerceIn(0, bucketCount - 1)
                                } else {
                                    (frameCursor / 2048L).toInt().coerceIn(0, bucketCount - 1)
                                }
                                if (framePeak > peaks[bucket]) peaks[bucket] = framePeak
                                frameCursor++
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            return peaks.toList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
