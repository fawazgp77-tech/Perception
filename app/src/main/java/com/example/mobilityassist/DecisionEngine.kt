package com.example.mobilityassist

enum class Region { LEFT, CENTER, RIGHT }
enum class Proximity { FAR, NEAR, VERY_NEAR }

// Coordinates are normalized to the upright, visible camera crop.
data class Obstacle(val label: String, val score: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = ((right - left) * (bottom - top)).coerceIn(0f, 1f)
    val region: Region get() = when {
        (left + right) / 2 < 1f / 3 -> Region.LEFT
        (left + right) / 2 < 2f / 3 -> Region.CENTER
        else -> Region.RIGHT
    }
    // A wide box can occupy the forward third even when its center lies outside it.
    val blocksCenter: Boolean get() {
        if (region == Region.CENTER) return true
        val width = right - left
        if (width <= 0f || proximity == Proximity.FAR) return false
        val overlap = (minOf(right, 2f / 3) - maxOf(left, 1f / 3)).coerceAtLeast(0f)
        return overlap >= 0.1f && overlap / width >= 0.25f
    }
    val proximity: Proximity get() = when {
        area >= 0.25f -> Proximity.VERY_NEAR
        area >= 0.08f -> Proximity.NEAR
        else -> Proximity.FAR
    }
    fun occupiesSide(side: Region): Boolean {
        if (proximity == Proximity.FAR || side == Region.CENTER) return false
        val start = if (side == Region.LEFT) 0f else 2f / 3
        val end = if (side == Region.LEFT) 1f / 3 else 1f
        return (minOf(right, end) - maxOf(left, start)).coerceAtLeast(0f) >= 0.18f
    }
}

enum class Warning(val text: String, val urgent: Boolean = false) {
    STOP("Stop.", true),
    AHEAD("Stop. Check for a clear path.", true),
    MOVE_LEFT("Stop. Move left carefully.", true),
    MOVE_RIGHT("Stop. Move right carefully.", true),
    SCAN_LEFT("Stop. Turn the phone left to check for a clear path.", true),
    SCAN_RIGHT("Stop. Turn the phone right to check for a clear path.", true),
    SCAN_SIDES("Stop. Slowly turn the phone left or right to check for a clear path.", true),
    POSSIBLE_WALL("Stop. Check for a clear path."),
    POSSIBLE_OBSTRUCTION("Stop. Slowly turn the phone left or right to check for a clear path."),
    PATH_BLOCKED("Stop. Slowly turn the phone left or right to check for a clear path."),
    PATH_LEFT("Stop. Move left carefully."),
    PATH_RIGHT("Stop. Move right carefully."),
    PATH_CLEAR("Path ahead appears open. Continue carefully."),
    OBSTACLE_LEFT("Keep right."),
    OBSTACLE_RIGHT("Keep left."),
    BOTH_SIDES("Stop. Check the path carefully.")
}

class DecisionEngine {
    fun decide(objects: List<Obstacle>, path: PathAssessment? = null,
               viewReliable: Boolean = false): Warning? {
        val close = objects.filter { it.proximity != Proximity.FAR }
        if (close.isEmpty()) return null
        val leftOccupied = close.any { it.occupiesSide(Region.LEFT) }
        val rightOccupied = close.any { it.occupiesSide(Region.RIGHT) }
        if (close.any { it.blocksCenter }) {
            val leftOpen = viewReliable && !leftOccupied && path?.hasConnectedSide(Region.LEFT) == true
            val rightOpen = viewReliable && !rightOccupied && path?.hasConnectedSide(Region.RIGHT) == true
            return when {
                leftOpen && !rightOpen -> Warning.MOVE_LEFT
                rightOpen && !leftOpen -> Warning.MOVE_RIGHT
                leftOpen && rightOpen -> Warning.MOVE_LEFT
                !leftOccupied && rightOccupied -> Warning.SCAN_LEFT
                leftOccupied && !rightOccupied -> Warning.SCAN_RIGHT
                else -> Warning.SCAN_SIDES
            }
        }
        return when {
            leftOccupied && rightOccupied -> Warning.BOTH_SIDES
            leftOccupied -> Warning.OBSTACLE_LEFT
            rightOccupied -> Warning.OBSTACLE_RIGHT
            else -> null
        }
    }
}

/** Announces recovery only after two new, reliable floor-mask results. */
class ScanRecoveryTracker {
    private var waiting = false
    private var clearSamples = 0

    fun observe(candidate: Warning?, path: PathAssessment?, viewReliable: Boolean,
                objects: List<Obstacle>): Warning? {
        if (candidate == Warning.SCAN_LEFT || candidate == Warning.SCAN_RIGHT ||
            candidate == Warning.SCAN_SIDES) {
            waiting = true
            clearSamples = 0
            return candidate
        }
        if (candidate == Warning.MOVE_LEFT || candidate == Warning.MOVE_RIGHT ||
            candidate == Warning.PATH_CLEAR) {
            reset()
            return candidate
        }
        if (!waiting) return candidate
        if (!viewReliable || path == null || !path.fresh) return candidate
        clearSamples = if (path.forwardClear &&
            objects.none { it.proximity != Proximity.FAR && it.blocksCenter }) clearSamples + 1 else 0
        if (clearSamples < 2) return candidate
        reset()
        return Warning.PATH_CLEAR
    }

    fun reset() { waiting = false; clearSamples = 0 }
}

/** Only connected floor pixels support a lateral move instruction. */
private fun PathAssessment.hasConnectedSide(side: Region): Boolean {
    if (width <= 0 || height <= 0 || walkableMask.size != width * height) return false
    val seen = BooleanArray(walkableMask.size)
    val queue = IntArray(walkableMask.size)
    var head = 0
    var tail = 0
    for (y in height * 3 / 4 until height) for (x in width / 3 until width * 2 / 3) {
        val i = y * width + x
        if (walkableMask[i] && !seen[i]) { seen[i] = true; queue[tail++] = i }
    }
    var reached = 0
    while (head < tail) {
        val i = queue[head++]
        val x = i % width
        val y = i / width
        if (y in height * 2 / 5 until height * 3 / 4 &&
            (side == Region.LEFT && x < width / 3 || side == Region.RIGHT && x >= width * 2 / 3)) reached++
        fun visit(n: Int) { if (walkableMask[n] && !seen[n]) { seen[n] = true; queue[tail++] = n } }
        if (x > 0) visit(i - 1)
        if (x + 1 < width) visit(i + 1)
        if (y > 0) visit(i - width)
        if (y + 1 < height) visit(i + width)
    }
    return reached >= width * height / 35
}

/** A side recommendation requires both a connected ground mask and no known nearby box there. */
object PathWarningSelector {
    fun select(path: PathAssessment?, objects: List<Obstacle>, viewReliable: Boolean): Warning? {
        if (!viewReliable) return null
        val guidance = path?.guidance ?: return null
        val side = when (guidance) {
            Warning.PATH_LEFT -> Region.LEFT
            Warning.PATH_RIGHT -> Region.RIGHT
            else -> return guidance
        }
        return if (objects.any { it.occupiesSide(side) })
            Warning.PATH_BLOCKED else guidance
    }
}

/** Confirm nonurgent guidance across successive analyzed frames. STOP is immediate. */
class GuidanceStabilizer(private val requiredFrames: Int = 2, private val maxGapMs: Long = 1_200) {
    private var pending: Warning? = null
    private var streak = 0
    private var lastObservedMs: Long? = null

    fun observe(candidate: Warning?, nowMs: Long): Warning? {
        if (candidate == Warning.STOP) {
            reset()
            return Warning.STOP
        }
        if (candidate == null) {
            reset()
            return null
        }
        val gap = lastObservedMs?.let { nowMs - it }
        val allowedGapMs = if (candidate == Warning.PATH_BLOCKED) 2_500L else maxGapMs
        streak = if (candidate == pending && gap != null && gap in 0..allowedGapMs) streak + 1 else 1
        pending = candidate
        lastObservedMs = nowMs
        return if (streak >= requiredFrames) candidate else null
    }

    fun reset() { pending = null; streak = 0; lastObservedMs = null }
}

// Per-warning cooldown plus a global quiet period prevents alternating speech.
// A newly urgent STOP can interrupt a lower-priority warning immediately.
class WarningGate(private val cooldownMs: Long = 6_000) {
    private val times = mutableMapOf<Warning, Long>()
    private var lastTime: Long? = null
    private var lastWarning: Warning? = null
    fun allow(warning: Warning, nowMs: Long): Boolean {
        val pathBarrier = warning == Warning.PATH_BLOCKED || warning == Warning.POSSIBLE_OBSTRUCTION ||
            warning == Warning.PATH_LEFT || warning == Warning.PATH_RIGHT
        val newBlockAfterRecovery = lastWarning == Warning.PATH_CLEAR &&
            pathBarrier &&
            lastTime?.let { nowMs - it >= 2_000L } == true
        val perWarningCooldown = when (warning) {
            Warning.STOP -> 4_000L
            Warning.SCAN_LEFT, Warning.SCAN_RIGHT, Warning.SCAN_SIDES -> 10_000L
            Warning.POSSIBLE_WALL, Warning.POSSIBLE_OBSTRUCTION, Warning.PATH_BLOCKED, Warning.PATH_LEFT,
            Warning.PATH_RIGHT, Warning.PATH_CLEAR -> 15_000L
            else -> cooldownMs
        }
        if (!newBlockAfterRecovery &&
            times[warning]?.let { nowMs - it < perWarningCooldown } == true) return false
        // A newly found route after a blocked view should be announced once promptly.
        val directionAfterBlock = (warning == Warning.PATH_LEFT || warning == Warning.PATH_RIGHT) &&
            (lastWarning == Warning.PATH_BLOCKED || lastWarning == Warning.POSSIBLE_OBSTRUCTION)
        if (!warning.urgent && warning != Warning.PATH_CLEAR &&
            !directionAfterBlock && !newBlockAfterRecovery &&
            lastTime?.let { nowMs - it < cooldownMs } == true) return false
        if (warning.urgent && lastWarning?.urgent == true && lastWarning != warning &&
            lastTime?.let { nowMs - it < 4_000L } == true) return false
        times[warning] = nowMs
        lastTime = nowMs
        lastWarning = warning
        return true
    }
    fun reset() { times.clear(); lastTime = null; lastWarning = null }
}
