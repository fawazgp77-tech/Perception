package com.example.mobilityassist

import kotlin.math.ceil

data class PipelineSnapshot(
    val analyzedFps: Double,
    val p95AnalysisToDisplayMs: Long,
    val averageConversionMs: Long,
    val averageInferenceMs: Long,
    // Camera timestamps are not guaranteed to share the elapsedRealtime clock.
    // This is diagnostic only until the timestamp source is verified on-device.
    val candidateCameraToDisplayMs: Long?
)

/** Small rolling window; updated on the UI thread at most once per analyzed frame. */
class PipelineMetrics(private val maxSamples: Int = 120) {
    private data class Sample(
        val completedNs: Long,
        val analysisToDisplayMs: Long,
        val conversionMs: Long,
        val inferenceMs: Long,
        val candidateCameraToDisplayMs: Long?
    )

    private val samples = ArrayDeque<Sample>()
    private var lastPublishedNs = 0L

    fun record(
        cameraTimestampNs: Long,
        analysisStartNs: Long,
        displayedNs: Long,
        conversionMs: Long,
        inferenceMs: Long
    ): PipelineSnapshot? {
        val candidateCameraToDisplayMs = if (
            cameraTimestampNs > 0 && cameraTimestampNs <= analysisStartNs &&
            analysisStartNs - cameraTimestampNs <= 5_000_000_000L
        ) (displayedNs - cameraTimestampNs) / 1_000_000L else null
        samples.addLast(Sample(displayedNs, (displayedNs - analysisStartNs).coerceAtLeast(0) / 1_000_000L,
            conversionMs, inferenceMs, candidateCameraToDisplayMs))
        while (samples.size > maxSamples) samples.removeFirst()
        if (lastPublishedNs != 0L && displayedNs - lastPublishedNs < 1_000_000_000L) return null
        lastPublishedNs = displayedNs
        val durationNs = samples.last().completedNs - samples.first().completedNs
        val fps = if (samples.size < 2 || durationNs <= 0) 0.0
            else (samples.size - 1) * 1_000_000_000.0 / durationNs
        fun p95(values: List<Long>): Long? = if (values.isEmpty()) null else
            values.sorted()[(ceil(values.size * 0.95).toInt() - 1).coerceAtLeast(0)]
        return PipelineSnapshot(
            fps,
            p95(samples.map { it.analysisToDisplayMs }) ?: 0,
            samples.map { it.conversionMs }.average().toLong(),
            samples.map { it.inferenceMs }.average().toLong(),
            p95(samples.mapNotNull { it.candidateCameraToDisplayMs })
        )
    }

    fun clear() { samples.clear(); lastPublishedNs = 0L }
}
