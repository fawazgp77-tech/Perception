package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Test

class PipelineMetricsTest {
    @Test fun rollingLatencyAndTimestampSanity() {
        val metrics = PipelineMetrics(maxSamples = 4)
        var snapshot: PipelineSnapshot? = null
        for (i in 0..3) {
            val start = 10_000_000_000L + i * 1_000_000_000L
            snapshot = metrics.record(start - 5_000_000L, start,
                start + (10L + i * 10L) * 1_000_000L, 2, 5)
        }
        assertNotNull(snapshot)
        assertEquals(40L, snapshot!!.p95AnalysisToDisplayMs)
        assertEquals(45L, snapshot.candidateCameraToDisplayMs)
        assertTrue(snapshot.analyzedFps in 0.9..1.1)

        metrics.clear()
        val afterReset = metrics.record(1L, 20_000_000_000L, 20_010_000_000L, 1, 3)
        assertNull(afterReset!!.candidateCameraToDisplayMs)
        assertEquals(0.0, afterReset.analyzedFps, 0.0)
    }
}
