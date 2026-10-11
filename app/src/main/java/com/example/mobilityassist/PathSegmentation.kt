package com.example.mobilityassist

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A sparse, offline ADE20K scene mask. Owned by the camera analysis worker. */
class PathSegmenter(
    context: Context,
    private val intervalMs: Long = 1_000L,
    private val immediateStrongWall: Boolean = false
) : AutoCloseable {
    private val model = context.assets.openFd(MODEL).use { fd ->
        fd.createInputStream().channel.map(
            java.nio.channels.FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.length
        )
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    private var lastRunMs = 0L
    private var lastResult: PathAssessment? = null
    private val guidance = PathGuidanceTracker(immediateStrongWall)

    init {
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, SIZE, SIZE, 3)))
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, SIZE, SIZE)))
    }

    /** Bitmap must already match the visible upright camera view; caller retains ownership. */
    fun maybeAnalyzeBitmap(bitmap: Bitmap): PathAssessment? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRunMs < intervalMs) return cachedResult(now)
        lastRunMs = now
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        try {
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            input.rewind()
            for (color in pixels) {
                input.put((color shr 16).toByte())
                input.put((color shr 8).toByte())
                input.put(color.toByte())
            }
            input.rewind()
            output.rewind()
            interpreter.run(input, output)
            val mask = IntArray(GRID * GRID)
            for (y in 0 until GRID) for (x in 0 until GRID) {
                val sourceX = x * SIZE / GRID + SIZE / GRID / 2
                val sourceY = y * SIZE / GRID + SIZE / GRID / 2
                mask[y * GRID + x] = output.getInt((sourceY * SIZE + sourceX) * 4)
            }
            val assessment = PathPolicy.assess(mask, GRID, GRID, immediateStrongWall)
            lastResult = guidance.observe(assessment)
            return lastResult
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    override fun close() = interpreter.close()

    fun reset() {
        lastRunMs = 0L
        lastResult = null
        guidance.reset()
    }

    private fun cachedResult(now: Long) = lastResult
        ?.takeIf { now - lastRunMs < 2_000L }
        ?.copy(guidance = null)

    companion object {
        const val MODEL = "path_segmentation_ade20k.tflite"
        const val SIZE = 512
        const val GRID = 64
        const val FAST_SAMPLE_INTERVAL_MS = 350L
    }
}

data class PathAssessment(
    val blocked: Boolean,
    val walkableMask: BooleanArray,
    val width: Int,
    val height: Int,
    val wallFraction: Float,
    val pathFraction: Float,
    val pathContinues: Boolean,
    val forwardClear: Boolean,
    val strongForwardClear: Boolean,
    val leftSide: SidePathEvidence,
    val rightSide: SidePathEvidence,
    val clearerSide: Region?,
    val guidance: Warning? = null
)

data class SidePathEvidence(
    val reachableFraction: Float,
    val wallFraction: Float,
    val footingFraction: Float
)

/** Consumes only new model results, so cached CameraX frames cannot confirm a route. */
class PathGuidanceTracker(private val immediateStrongWall: Boolean = true) {
    private var blockedStreak = 0
    private var clearStreak = 0
    private var blockedSeen = false
    private var sideStreak = 0
    private var pendingSide: Region? = null

    fun observe(sample: PathAssessment): PathAssessment {
        blockedStreak = if (sample.blocked) blockedStreak + 1 else 0
        val confirmedBlock = sample.blocked &&
            (blockedStreak >= 2 || (immediateStrongWall && sample.wallFraction >= 0.50f))
        if (confirmedBlock) blockedSeen = true
        val side = if (confirmedBlock) sample.clearerSide else null
        sideStreak = if (side != null && side == pendingSide) sideStreak + 1 else if (side != null) 1 else 0
        pendingSide = side
        clearStreak = if (sample.forwardClear) clearStreak + 1 else 0
        val recovered = blockedSeen &&
            (clearStreak >= 2 || (immediateStrongWall && sample.strongForwardClear))
        if (recovered) blockedSeen = false
        val warning = when {
            recovered -> Warning.PATH_CLEAR
            confirmedBlock && (sideStreak >= 2 || sample.wallFraction >= 0.50f) &&
                side == Region.LEFT -> Warning.PATH_LEFT
            confirmedBlock && (sideStreak >= 2 || sample.wallFraction >= 0.50f) &&
                side == Region.RIGHT -> Warning.PATH_RIGHT
            confirmedBlock -> Warning.PATH_BLOCKED
            else -> null
        }
        return sample.copy(blocked = confirmedBlock, guidance = warning)
    }

    fun reset() {
        blockedStreak = 0
        clearStreak = 0
        blockedSeen = false
        sideStreak = 0
        pendingSide = null
    }
}

/** ADE20K's top-31 class IDs in this model: 1 wall, 4 floor, 12 sidewalk. */
object PathPolicy {
    private fun walkable(id: Int) = id == 4 || id == 12

    private fun sideEvidence(
        labels: IntArray, mask: BooleanArray, width: Int, height: Int, region: Region
    ): SidePathEvidence {
        val lane = if (region == Region.LEFT) 0 else 2
        val startX = lane * width / 3
        val endX = (lane + 1) * width / 3
        val sampleStartY = height * 7 / 20
        val sampleEndY = height * 7 / 10
        val seen = BooleanArray(labels.size)
        val queue = IntArray(labels.size)
        var head = 0
        var tail = 0
        for (y in height * 3 / 4 until height) for (x in startX until endX) {
            val i = y * width + x
            if (mask[i] && !seen[i]) { seen[i] = true; queue[tail++] = i }
        }
        var reachable = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            if (y in sampleStartY..sampleEndY) reachable++
            fun add(next: Int) {
                if (mask[next] && !seen[next]) { seen[next] = true; queue[tail++] = next }
            }
            if (x > startX) add(i - 1)
            if (x + 1 < endX) add(i + 1)
            if (y > 0) add(i - width)
            if (y + 1 < height) add(i + width)
        }
        var walls = 0
        var wallCells = 0
        var footing = 0
        var footCells = 0
        for (y in 0 until height) for (x in startX until endX) {
            val i = y * width + x
            if (y in height * 2 / 5 until height * 3 / 4) {
                wallCells++
                if (labels[i] == 1) walls++
            }
            if (y >= height * 3 / 4) {
                footCells++
                if (mask[i]) footing++
            }
        }
        val sampleCells = (sampleEndY - sampleStartY + 1) * (endX - startX)
        return SidePathEvidence(
            reachable.toFloat() / sampleCells.coerceAtLeast(1),
            walls.toFloat() / wallCells.coerceAtLeast(1),
            footing.toFloat() / footCells.coerceAtLeast(1)
        )
    }

    fun assess(labels: IntArray, width: Int, height: Int, responsive: Boolean = true): PathAssessment {
        require(width > 0 && height > 0 && labels.size == width * height)
        val walkableMask = BooleanArray(labels.size) { walkable(labels[it]) }
        val seen = BooleanArray(labels.size)
        val queue = IntArray(labels.size)
        var head = 0
        var tail = 0
        for (y in height * 3 / 4 until height) for (x in width / 3 until width * 2 / 3) {
            val index = y * width + x
            if (walkableMask[index] && !seen[index]) {
                seen[index] = true
                queue[tail++] = index
            }
        }
        var pathContinues = false
        val reachableCounts = IntArray(3)
        val sampleRows = (height * 7 / 20)..(height * 7 / 10)
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (y in sampleRows) reachableCounts[(x * 3 / width).coerceAtMost(2)]++
            if (y <= height * 3 / 5 && x in width / 3 until width * 2 / 3) {
                pathContinues = true
            }
            fun add(next: Int) {
                if (walkableMask[next] && !seen[next]) {
                    seen[next] = true
                    queue[tail++] = next
                }
            }
            if (x > 0) add(index - 1)
            if (x + 1 < width) add(index + 1)
            if (y > 0) add(index - width)
            if (y + 1 < height) add(index + width)
        }
        var bottomWalkable = 0
        var bottomCount = 0
        var midWalkable = 0
        var midWall = 0
        var midCount = 0
        for (y in 0 until height) for (x in width / 3 until width * 2 / 3) {
            val id = labels[y * width + x]
            if (y >= height * 3 / 4) {
                bottomCount++
                if (walkable(id)) bottomWalkable++
            }
            if (y in height * 2 / 5 until height * 3 / 4) {
                midCount++
                if (walkable(id)) midWalkable++
                if (id == 1) midWall++
            }
        }
        val bottomFraction = bottomWalkable.toFloat() / bottomCount.coerceAtLeast(1)
        val midPathFraction = midWalkable.toFloat() / midCount.coerceAtLeast(1)
        val wallFraction = midWall.toFloat() / midCount.coerceAtLeast(1)
        val laneCells = (sampleRows.last - sampleRows.first + 1) * width / 3f
        val centerReachable = reachableCounts[1] / laneCells
        val forwardClear = pathContinues && centerReachable >= 0.15f &&
            bottomFraction >= 0.18f && wallFraction < 0.25f
        val strongForwardClear = forwardClear && centerReachable >= 0.32f &&
            bottomFraction >= 0.25f && wallFraction < 0.12f
        // A named wall spanning the center or a previously visible path ending at one.
        // Absence of a path alone is ambiguous (sky, blur, covered lens, bad angle).
        val blocked = if (responsive) {
            wallFraction >= 0.50f ||
                (bottomFraction >= 0.18f && !pathContinues && wallFraction >= 0.25f)
        } else {
            wallFraction >= 0.55f ||
                (bottomFraction >= 0.20f && !pathContinues && wallFraction >= 0.30f)
        }
        val leftSide = sideEvidence(labels, walkableMask, width, height, Region.LEFT)
        val rightSide = sideEvidence(labels, walkableMask, width, height, Region.RIGHT)
        // Only suggest a side with a connected, visible walkable corridor. An
        // unlabeled or merely plain-looking image cannot establish a route.
        val clearerSide = if (blocked) when {
            leftSide.reachableFraction >= 0.15f && leftSide.wallFraction < 0.35f &&
                leftSide.reachableFraction >= rightSide.reachableFraction * 1.35f -> Region.LEFT
            rightSide.reachableFraction >= 0.15f && rightSide.wallFraction < 0.35f &&
                rightSide.reachableFraction >= leftSide.reachableFraction * 1.35f -> Region.RIGHT
            else -> null
        } else null
        return PathAssessment(blocked, walkableMask, width, height, wallFraction,
            midPathFraction, pathContinues, forwardClear, strongForwardClear,
            leftSide, rightSide, clearerSide)
    }
}
