package com.vexlrc.generator.core.lrc

data class LrcIssue(val lineIndex: Int, val message: String)

object LrcValidator {
    fun validate(lines: List<LyricLine>, audioDurationMs: Long? = null): List<LrcIssue> {
        val issues = mutableListOf<LrcIssue>()
        lines.forEachIndexed { index, line ->
            if (line.startMs < 0L) issues += LrcIssue(index, "Line timestamp is negative")
            if (audioDurationMs != null && line.startMs > audioDurationMs) {
                issues += LrcIssue(index, "Line timestamp is beyond audio duration")
            }
            if (line.words.isEmpty()) issues += LrcIssue(index, "Line has no words")
            var previous = -1L
            line.words.forEach { word ->
                if (word.text.isBlank()) issues += LrcIssue(index, "Empty word")
                if (word.startMs < 0L) issues += LrcIssue(index, "Word timestamp is negative")
                if (word.startMs < previous) issues += LrcIssue(index, "Word timestamps are not ordered")
                if (audioDurationMs != null && word.startMs > audioDurationMs) {
                    issues += LrcIssue(index, "Word timestamp is beyond audio duration")
                }
                previous = word.startMs
            }
            if (line.words.isNotEmpty() && line.words.first().startMs < line.startMs) {
                issues += LrcIssue(index, "First word starts before its lyric line")
            }
        }
        return issues
    }
}
