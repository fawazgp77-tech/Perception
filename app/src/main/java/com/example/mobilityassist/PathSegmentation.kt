package com.example.mobilityassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A sparse, offline ADE20K scene mask. Owned by the CameraX analysis worker. */
class PathSegmenter(context: Context) : AutoCloseable {
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
    private var blockedStreak = 0

    init {
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, SIZE, SIZE, 3)))
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, SIZE, SIZE)))
    }

    /** Returns the last fresh assessment between inference calls; null when confidence is insufficient. */
    fun maybeAnalyze(frame: ImageProxy): PathAssessment? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRunMs < 1_000L) return lastResult?.takeIf { now - lastRunMs < 2_500L }
        val raw = frame.toBitmap()
        var upright: Bitmap? = null
        try {
            val crop = frame.cropRect
            val rotate = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
            upright = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(), rotate, true)
            return maybeAnalyzeBitmap(upright)
        } finally {
            if (upright !== raw) upright?.recycle()
            raw.recycle()
        }
    }

    /** Bitmap must already match the visible upright camera view; caller retains ownership. */
    fun maybeAnalyzeBitmap(bitmap: Bitmap): PathAssessment? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRunMs < 1_000L) return lastResult?.takeIf { now - lastRunMs < 2_500L }
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
            val assessment = PathPolicy.assess(mask, GRID, GRID)
            blockedStreak = if (assessment.blocked) blockedStreak + 1 else 0
            lastResult = assessment.copy(blocked = blockedStreak >= 2)
            return lastResult
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    override fun close() = interpreter.close()

    fun reset() {
        lastRunMs = 0L
        lastResult = null
        blockedStreak = 0
    }

    companion object {
        const val MODEL = "path_segmentation_ade20k.tflite"
        const val SIZE = 512
        const val GRID = 64
    }
}

data class PathAssessment(
    val blocked: Boolean,
    val walkableMask: BooleanArray,
    val width: Int,
    val height: Int,
    val wallFraction: Float,
    val pathFraction: Float,
    val pathContinues: Boolean
)

/** ADE20K's top-31 class IDs in this model: 1 wall, 4 floor, 12 sidewalk. */
object PathPolicy {
    private fun walkable(id: Int) = id == 4 || id == 12

    fun assess(labels: IntArray, width: Int, height: Int): PathAssessment {
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
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
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
        // A named wall spanning the center or a previously visible path ending at one.
        // Absence of a path alone is ambiguous (sky, blur, covered lens, bad angle).
        val blocked = wallFraction >= 0.55f ||
            (bottomFraction >= 0.20f && !pathContinues && wallFraction >= 0.30f)
        return PathAssessment(blocked, walkableMask, width, height, wallFraction,
            midPathFraction, pathContinues)
    }
}
