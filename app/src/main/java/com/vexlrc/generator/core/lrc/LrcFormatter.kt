package com.vexlrc.generator.core.lrc

data class LyricWord(
    val text: String,
    val startMs: Long
)

data class LyricLine(
    val startMs: Long,
    val words: List<LyricWord>
)

object LrcFormatter {
    fun standard(lines: List<LyricLine>): String =
        lines.sortedBy { it.startMs }.joinToString("\n") { line ->
            "[${timestamp(line.startMs)}]${line.words.joinToString(" ") { it.text }}"
        }

    fun enhanced(lines: List<LyricLine>): String =
        lines.sortedBy { it.startMs }.joinToString("\n") { line ->
            val words = line.words.sortedBy { it.startMs }
            "[${timestamp(line.startMs)}]" + words.joinToString(" ") { word ->
                "<${timestamp(word.startMs)}>${word.text}"
            }
        }

    fun timestamp(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1_000
        val centiseconds = (safe % 1_000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, centiseconds)
    }
}
