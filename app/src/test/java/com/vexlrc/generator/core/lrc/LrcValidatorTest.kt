package com.vexlrc.generator.core.lrc

import org.junit.Assert.assertTrue
import org.junit.Test

class LrcValidatorTest {
    @Test fun acceptsOrderedWordsWithinDuration() {
        val lines = listOf(LyricLine(1000, listOf(LyricWord("hello", 1000), LyricWord("world", 1500))))
        assertTrue(LrcValidator.validate(lines, 3000).isEmpty())
    }

    @Test fun reportsOutOfRangeAndUnorderedWords() {
        val lines = listOf(
            LyricLine(1000, listOf(
                LyricWord("early", 500),
                LyricWord("late", 4000),
                LyricWord("middle", 3000)
            ))
        )
        val issues = LrcValidator.validate(lines, 3000)
        assertTrue(issues.any { it.message.contains("beyond audio duration") })
        assertTrue(issues.any { it.message.contains("not ordered") })
        assertTrue(issues.any { it.message.contains("before its lyric line") })
    }
}
