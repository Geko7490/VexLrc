package com.vexlrc.generator.core.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformEnvelopeTest {
    @Test fun extractsPeakPerBucket() {
        val result = WaveformEnvelope.fromPcm(shortArrayOf(0, 1000, -2000, 0, 4000, 0), 3)
        assertEquals(3, result.size)
        assertEquals(1000 / 32768f, result[0], 0.0001f)
        assertEquals(2000 / 32768f, result[1], 0.0001f)
        assertEquals(4000 / 32768f, result[2], 0.0001f)
    }

    @Test fun handlesEmptyAndInvalidBucketCount() {
        assertTrue(WaveformEnvelope.fromPcm(shortArrayOf(), 10).isEmpty())
        assertTrue(WaveformEnvelope.fromPcm(shortArrayOf(1), 0).isEmpty())
    }
}
