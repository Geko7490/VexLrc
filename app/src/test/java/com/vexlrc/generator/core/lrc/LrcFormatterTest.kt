package com.vexlrc.generator.core.lrc

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcFormatterTest {
    @Test
    fun formatsStandardLrcLine() {
        val result = LrcFormatter.standard(
            listOf(LyricLine(12_450, listOf(LyricWord("Hello", 12_450), LyricWord("world", 12_800))))
        )
        assertEquals("[00:12.45]Hello world", result)
    }

    @Test
    fun formatsEnhancedLrcWordTimestamps() {
        val result = LrcFormatter.enhanced(
            listOf(LyricLine(12_450, listOf(LyricWord("Hello", 12_450), LyricWord("world", 12_800))))
        )
        assertEquals("[00:12.45]<00:12.45>Hello <00:12.80>world", result)
    }

    @Test
    fun clampsNegativeTimestampsToZero() {
        assertEquals("00:00.00", LrcFormatter.timestamp(-100))
    }
}
