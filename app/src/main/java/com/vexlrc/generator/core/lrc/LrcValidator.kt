package com.vexlrc.generator.core.lrc

data class LrcIssue(val lineIndex: Int, val message: String)

object LrcValidator {
    fun validate(lines: List<LyricLine>, audioDurationMs: Long? = null): List<LrcIssue> {
        val issues = mutableListOf<LrcIssue>()
        var previousLineStart = -1L
        lines.forEachIndexed { index, line ->
            if (line.startMs < 0L) issues += LrcIssue(index, "Line timestamp is negative")
            if (line.startMs < previousLineStart) issues += LrcIssue(index, "Line timestamps are not ordered")
            if (audioDurationMs != null && line.startMs > audioDurationMs) {
                issues += LrcIssue(index, "Line timestamp is beyond audio duration")
            }
            if (line.words.isEmpty()) issues += LrcIssue(index, "Line has no words")
            var previousWordStart = -1L
            line.words.forEach { word ->
                if (word.text.isBlank()) issues += LrcIssue(index, "Empty word")
                if (word.startMs < 0L) issues += LrcIssue(index, "Word timestamp is negative")
                if (word.startMs < previousWordStart) issues += LrcIssue(index, "Word timestamps are not ordered")
                if (word.startMs < line.startMs) issues += LrcIssue(index, "Word starts before its lyric line")
                if (audioDurationMs != null && word.startMs > audioDurationMs) {
                    issues += LrcIssue(index, "Word timestamp is beyond audio duration")
                }
                previousWordStart = word.startMs
            }
            previousLineStart = line.startMs
        }
        return issues
    }
}
