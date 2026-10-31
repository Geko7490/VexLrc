package com.vexlrc.generator.core.ai

enum class AiCapability { TRANSCRIPTION, FORCED_ALIGNMENT, VOCAL_SEPARATION }

enum class EngineAvailability { NOT_INSTALLED, READY, RUNNING, FAILED }

data class AiEngineStatus(
    val id: String,
    val displayName: String,
    val capability: AiCapability,
    val availability: EngineAvailability,
    val detail: String
)

/**
 * Contract for independently downloadable on-device engines.
 * Concrete native/runtime adapters are registered only after their model and ABI are verified.
 */
interface LocalAiEngine {
    val id: String
    val capability: AiCapability
    suspend fun isReady(): Boolean
}

data class TranscriptionSegment(val startMs: Long, val endMs: Long, val text: String)
data class AlignedWord(val text: String, val startMs: Long, val endMs: Long)

interface TranscriptionEngine : LocalAiEngine {
    suspend fun transcribe(audioPath: String, language: String): List<TranscriptionSegment>
}

interface ForcedAlignmentEngine : LocalAiEngine {
    suspend fun align(audioPath: String, transcript: List<TranscriptionSegment>, language: String): List<AlignedWord>
}

interface VocalSeparationEngine : LocalAiEngine {
    suspend fun separate(audioPath: String): Pair<String, String>
}
