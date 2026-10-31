package com.vexlrc.generator.core.project

data class LrcProject(
    val id: String,
    val name: String,
    val audioUri: String?,
    val durationMs: Long,
    val updatedAtMs: Long,
    val lyricsJson: String
)
