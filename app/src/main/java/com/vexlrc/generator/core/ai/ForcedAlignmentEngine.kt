package com.vexlrc.generator.core.ai

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import java.util.Locale

data class AlignedWord(val text: String, val startMs: Long, val endMs: Long)

object ForcedAlignmentEngine {
    fun align(context: Context, modelFile: File, vocabFile: File, pcm16k: FloatArray, transcript: String): List<AlignedWord> {
        require(pcm16k.size >= 400) { "Audio is too short for forced alignment" }
        require(transcript.isNotBlank()) { "Enter the lyrics before alignment" }
        val vocabJson = JSONObject(vocabFile.readText())
        val vocab = mutableMapOf<String, Int>()
        val keys = vocabJson.keys()
        while (keys.hasNext()) { val key = keys.next(); vocab[key] = vocabJson.getInt(key) }
        val labels = mutableListOf<Int>()
        val languageLocale = if (modelFile.name.startsWith("tr-")) Locale.forLanguageTag("tr") else Locale.US
        val chars = transcript.lowercase(languageLocale).map { ch ->
            val token = if (ch.isWhitespace()) "|" else ch.toString()
            val id = vocab[token] ?: error("Aligner vocabulary does not contain '$ch'")
            token to id
        }
        chars.forEach { labels += it.second }
        require(labels.isNotEmpty()) { "Transcript has no alignable characters" }
        val mean = pcm16k.average().toFloat()
        var variance = 0.0
        pcm16k.forEach { variance += (it - mean) * (it - mean) }
        val std = sqrt(variance / pcm16k.size).toFloat().coerceAtLeast(1e-5f)
        val normalized = FloatArray(pcm16k.size) { (pcm16k[it] - mean) / std }
        val blank = 0
        val states = IntArray(labels.size * 2 + 1) { index -> if (index % 2 == 0) blank else labels[index / 2] }
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        }
        env.createSession(modelFile.absolutePath, options).use { session ->
            val inputBuffer = ByteBuffer.allocateDirect(normalized.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(normalized); rewind() }
            val input = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, normalized.size.toLong()))
            input.use {
                session.run(mapOf(session.inputNames.first() to input)).use { result ->
                    val output = result[session.outputNames.first()] as OnnxTensor
                    output.use {
                        val shape = output.info.shape
                        require(shape.size == 3 && shape[0] == 1L) { "Unexpected aligner output shape" }
                        val frames = shape[1].toInt()
                        val classes = shape[2].toInt()
                        require(frames >= states.size && classes > 1) { "Transcript is too long for the selected audio" }
                        val logits = output.floatBuffer
                        val previous = DoubleArray(states.size) { Double.NEGATIVE_INFINITY }
                        val next = DoubleArray(states.size)
                        val back = ByteArray(frames * states.size)
                        previous[0] = score(logits, 0, blank, classes)
                        if (states.size > 1) previous[1] = score(logits, 0, states[1], classes)
                        for (t in 1 until frames) {
                            for (s in states.indices) {
                                var best = previous[s]
                                var from = s
                                if (s > 0 && previous[s - 1] > best) { best = previous[s - 1]; from = s - 1 }
                                if (s > 1 && states[s] != blank && states[s] != states[s - 2] && previous[s - 2] > best) {
                                    best = previous[s - 2]; from = s - 2
                                }
                                next[s] = best + score(logits, t, states[s], classes)
                                back[t * states.size + s] = (from - s).toByte()
                            }
                            for (s in states.indices) previous[s] = next[s]
                        }
                        var state = states.lastIndex
                        if (states.size > 1 && previous[state - 1] > previous[state]) state--
                        require(previous[state].isFinite()) { "Could not align transcript. Check lyrics and language." }
                        val path = IntArray(frames)
                        for (t in frames - 1 downTo 0) {
                            path[t] = state
                            if (t > 0) state += back[t * states.size + state].toInt()
                        }
                        val spans = Array(labels.size) { intArrayOf(-1, -1) }
                        for (t in path.indices) {
                            val s = path[t]
                            if (s % 2 == 1) {
                                val c = s / 2
                                if (spans[c][0] < 0) spans[c][0] = t
                                spans[c][1] = t
                            }
                        }
                        val words = mutableListOf<AlignedWord>()
                        val current = StringBuilder()
                        var first = -1
                        var last = -1
                        fun flush() {
                            if (current.isNotEmpty() && first >= 0) {
                                words += AlignedWord(current.toString(), first * 20L, (last + 1) * 20L)
                            }
                            current.setLength(0); first = -1; last = -1
                        }
                        chars.forEachIndexed { index, pair ->
                            if (pair.first == "|") flush()
                            else {
                                val span = spans[index]
                                if (span[0] >= 0) {
                                    if (first < 0) first = span[0]
                                    last = maxOf(last, span[1])
                                }
                                current.append(pair.first)
                            }
                        }
                        flush()
                        require(words.isNotEmpty()) { "No words could be aligned" }
                        return words
                    }
                }
            }
        }
    }

    private fun score(buffer: FloatBuffer, frame: Int, token: Int, classes: Int): Double =
        buffer.get(frame * classes + token).toDouble()
}
