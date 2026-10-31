package com.vexlrc.generator.core.waveform

/**
 * Converts decoded PCM samples into a compact peak envelope suitable for drawing.
 * The caller is responsible for decoding audio; this class never fabricates samples.
 */
object WaveformEnvelope {
    fun fromPcm(samples: ShortArray, bucketCount: Int): List<Float> {
        if (samples.isEmpty() || bucketCount <= 0) return emptyList()
        val count = minOf(bucketCount, samples.size)
        val result = ArrayList<Float>(count)
        for (bucket in 0 until count) {
            val start = bucket * samples.size / count
            val end = maxOf(start + 1, (bucket + 1) * samples.size / count)
            var peak = 0
            for (i in start until minOf(end, samples.size)) {
                peak = maxOf(peak, kotlin.math.abs(samples[i].toInt()))
            }
            result += (peak / 32768f).coerceIn(0f, 1f)
        }
        return result
    }

    fun resample(peaks: List<Float>, targetCount: Int): List<Float> {
        if (peaks.isEmpty() || targetCount <= 0) return emptyList()
        if (peaks.size == targetCount) return peaks.map { it.coerceIn(0f, 1f) }
        return List(targetCount) { index ->
            val start = index * peaks.size / targetCount
            val end = maxOf(start + 1, (index + 1) * peaks.size / targetCount)
            peaks.subList(start.coerceIn(0, peaks.lastIndex), end.coerceIn(1, peaks.size))
                .maxOf { it.coerceIn(0f, 1f) }
        }
    }
}
