package com.vexlrc.generator.core.ai

import org.json.JSONObject

data class WhisperTokenTiming(val text: String, val startMs: Long, val endMs: Long)
data class WhisperSegmentTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val tokens: List<WhisperTokenTiming>
)
fun interface WhisperProgressListener { fun onProgress(progress: Int) }
fun interface WhisperStageListener { fun onStage(stage: String) }

data class WhisperTranscription(val segments: List<WhisperSegmentTiming>) {
    val fullText: String get() = segments.joinToString(" ") { it.text.trim() }.trim()
}

object WhisperNative {
    init {
        System.loadLibrary("vexwhisper")
    }

    external fun transcribe(
        modelPath: String,
        audio16kMono: FloatArray,
        language: String,
        threads: Int,
        progressListener: WhisperProgressListener,
        stageListener: WhisperStageListener
    ): String

    fun parseResult(raw: String): WhisperTranscription {
        val root = JSONObject(raw)
        if (root.has("error")) error(root.getString("error"))
        val array = root.getJSONArray("segments")
        val segments = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val tokenArray = item.optJSONArray("tokens")
            val tokens = if (tokenArray == null) emptyList() else
                (0 until tokenArray.length()).map { tokenIndex ->
                    val token = tokenArray.getJSONObject(tokenIndex)
                    WhisperTokenTiming(
                        token.optString("text"),
                        token.optLong("startMs").coerceAtLeast(0L),
                        token.optLong("endMs").coerceAtLeast(0L)
                    )
                }
            WhisperSegmentTiming(
                item.optString("text"),
                item.optLong("startMs").coerceAtLeast(0L),
                item.optLong("endMs").coerceAtLeast(0L),
                tokens
            )
        }
        return WhisperTranscription(segments)
    }
}
