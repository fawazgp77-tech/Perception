package com.example.mobilityassist

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import kotlin.math.abs

data class StairObservation(val visibleEdges: Int, val likelyStairs: Boolean)

/** A cautious image-pattern cue. Horizontal edges are not a validated stair classifier. */
object StairPatternPolicy {
    fun analyze(gray: IntArray, width: Int, height: Int): StairObservation {
        if (width < 40 || height < 40 || gray.size != width * height)
            return StairObservation(0, false)
        val startX = width / 5
        val endX = width * 4 / 5
        val startY = height * 3 / 10
        val endY = height * 9 / 10
        val strength = FloatArray(height)
        for (y in startY + 2 until endY) {
            var supported = 0
            var sum = 0
            for (x in startX until endX) {
                val delta = abs(gray[y * width + x] - gray[(y - 2) * width + x])
                if (delta >= 28) supported++
                sum += delta
            }
            val columns = (endX - startX).coerceAtLeast(1)
            if (supported.toFloat() / columns >= 0.48f && sum / columns >= 29)
                strength[y] = sum.toFloat() / columns
        }
        val peaks = mutableListOf<Int>()
        for (y in startY + 4 until endY - 3) {
            if (strength[y] < 29f || strength[y] < strength[y - 1] || strength[y] < strength[y + 1])
                continue
            if (peaks.isEmpty() || y - peaks.last() >= height / 22) peaks += y
            else if (strength[y] > strength[peaks.last()]) peaks[peaks.lastIndex] = y
        }
        val spaced = peaks.zipWithNext().count { (a, b) -> b - a in height / 22..height / 4 }
        val likely = peaks.size >= 3 && spaced >= 2 &&
            peaks.last() - peaks.first() >= height / 5 && peaks.last() >= height * 2 / 3
        return StairObservation(peaks.size, likely)
    }
}

/** Runs sparingly on the existing CameraX analysis worker. */
class StairPatternDetector(private val intervalMs: Long = 850L) {
    private var lastRunMs = 0L
    private val gray = IntArray(160 * 120)

    fun maybeAnalyze(frame: ImageProxy): StairObservation? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRunMs < intervalMs) return null
        lastRunMs = now
        val raw = frame.toBitmap()
        var upright: Bitmap? = null
        try {
            val crop = frame.cropRect
            val rotate = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
            upright = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(), rotate, true)
            val scaled = Bitmap.createScaledBitmap(upright, 160, 120, true)
            try {
                val pixels = IntArray(gray.size)
                scaled.getPixels(pixels, 0, 160, 0, 0, 160, 120)
                for (i in pixels.indices) {
                    val color = pixels[i]
                    gray[i] = ((color shr 16 and 255) * 77 +
                        (color shr 8 and 255) * 150 + (color and 255) * 29) shr 8
                }
                return StairPatternPolicy.analyze(gray, 160, 120)
            } finally { if (scaled !== upright) scaled.recycle() }
        } finally {
            if (upright !== raw) upright?.recycle()
            raw.recycle()
        }
    }

    fun reset() { lastRunMs = 0L }
}

/** Requires repeat detections from independent image samples. */
class StairPatternTracker {
    private var consecutive = 0
    fun observe(sample: StairObservation?): Warning? {
        if (sample == null) return null
        consecutive = if (sample.likelyStairs) consecutive + 1 else 0
        return if (consecutive >= 2) Warning.POSSIBLE_STAIRS else null
    }
    fun reset() { consecutive = 0 }
}
