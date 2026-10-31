package com.vexlrc.generator.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Vocals-only Demucs inference. Input is mono duplicated to stereo for lyric transcription. */
class DemucsVocalSeparator(context: android.content.Context) {
    private val outputDirectory = File(context.cacheDir, "demucs").apply { mkdirs() }
    private val sampleRate = 44100
    private val chunkSize = 343980
    private val overlap = chunkSize / 4
    private val stride = chunkSize - overlap

    suspend fun separate(mono16k: FloatArray, model: File, threads: Int = 4, onProgress: suspend (Int) -> Unit = {}): File {
        require(mono16k.isNotEmpty()) { "No audio samples to separate" }
        require(mono16k.size <= 16_000 * 5 * 60) { "Demucs is limited to 5 minutes per run on mobile" }
        val source = resample(mono16k, 16000, sampleRate)
        val total = source.size
        onProgress(0)
        val left = FloatArray(total)
        val right = FloatArray(total)
        val weights = FloatArray(total)
        val chunk = FloatArray(chunkSize * 2)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
        }
        env.createSession(model.absolutePath, opts).use { session ->
            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()
            var offset = 0
            while (offset < total) {
                val length = minOf(chunkSize, total - offset)
                chunk.fill(0f)
                for (i in 0 until length) {
                    val sample = source[offset + i]
                    chunk[i] = sample
                    chunk[chunkSize + i] = sample
                }
                val inputBuffer = ByteBuffer.allocateDirect(chunk.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(chunk); rewind() }
                val tensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 2, chunkSize.toLong()))
                tensor.use {
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val output = result[outputName] as OnnxTensor
                        output.use {
                            val shape = output.info.shape
                            require(shape.contentEquals(longArrayOf(1, 4, 2, chunkSize.toLong()))) {
                                "Unexpected Demucs output shape"
                            }
                            val data = output.floatBuffer
                            val vocalsOffset = 3 * 2 * chunkSize
                            for (i in 0 until length) {
                                val weight = when {
                                    offset == 0 && offset + length >= total -> 1f
                                    offset == 0 && i >= chunkSize - overlap -> (chunkSize - 1 - i).toFloat() / overlap
                                    offset == 0 -> 1f
                                    offset + length >= total && i < overlap -> i.toFloat() / overlap
                                    offset + length >= total -> 1f
                                    i < overlap -> i.toFloat() / overlap
                                    i >= chunkSize - overlap -> (chunkSize - 1 - i).toFloat() / overlap
                                    else -> 1f
                                }.coerceAtLeast(0.001f)
                                val index = offset + i
                                left[index] += data.get(vocalsOffset + i) * weight
                                right[index] += data.get(vocalsOffset + chunkSize + i) * weight
                                weights[index] += weight
                            }
                        }
                    }
                }
                offset += stride
                onProgress(((offset.toDouble() / total.coerceAtLeast(1)) * 100.0).toInt().coerceIn(0, 100))
            }
        }
        val output = File(outputDirectory, "vocal-${System.currentTimeMillis()}.wav")
        writeWav(output, left, right, weights)
        return output
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        val count = floor(input.size.toDouble() * to / from).toInt().coerceAtLeast(1)
        return FloatArray(count) { i ->
            val p = i.toDouble() * from / to
            val a = p.toInt().coerceIn(0, input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val f = (p - a).toFloat()
            input[a] * (1f - f) + input[b] * f
        }
    }

    private fun writeWav(file: File, left: FloatArray, right: FloatArray, weights: FloatArray) {
        val dataBytes = left.size * 4
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF"); writeInt(raf, 36 + dataBytes); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); writeInt(raf, 16); writeShort(raf, 1); writeShort(raf, 2)
            writeInt(raf, sampleRate); writeInt(raf, sampleRate * 4); writeShort(raf, 4); writeShort(raf, 16)
            raf.writeBytes("data"); writeInt(raf, dataBytes)
            for (i in left.indices) {
                val w = weights[i].coerceAtLeast(1e-6f)
                writeShort(raf, ((left[i] / w).coerceIn(-1f, 1f) * 32767).toInt())
                writeShort(raf, ((right[i] / w).coerceIn(-1f, 1f) * 32767).toInt())
            }
        }
    }
    private fun writeInt(raf: RandomAccessFile, value: Int) {
        raf.write(value and 255); raf.write(value shr 8 and 255); raf.write(value shr 16 and 255); raf.write(value shr 24 and 255)
    }
    private fun writeShort(raf: RandomAccessFile, value: Int) {
        raf.write(value and 255); raf.write(value shr 8 and 255)
    }
}
