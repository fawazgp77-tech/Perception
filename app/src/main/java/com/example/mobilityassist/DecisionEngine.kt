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
}

enum class Warning(val text: String, val urgent: Boolean = false) {
    STOP("Stop. Obstacle ahead.", true),
    AHEAD("Obstacle ahead."),
    POSSIBLE_WALL("Possible wall ahead."),
    POSSIBLE_OBSTRUCTION("Possible obstruction ahead. Stop and check."),
    PATH_BLOCKED("Path ahead may be blocked."),
    PATH_LEFT("Path ahead may be blocked. Turn left to check for a way through."),
    PATH_RIGHT("Path ahead may be blocked. Turn right to check for a way through."),
    PATH_CLEAR("Path ahead appears open. Continue carefully."),
    OBSTACLE_LEFT("Obstacle on left."),
    OBSTACLE_RIGHT("Obstacle on right."),
    BOTH_SIDES("Obstacles on both sides.")
}

class DecisionEngine {
    fun decide(objects: List<Obstacle>): Warning? {
        val center = objects.filter { it.blocksCenter }
        if (center.any { it.proximity == Proximity.VERY_NEAR }) return Warning.STOP
        if (center.any { it.proximity == Proximity.NEAR }) return Warning.AHEAD
        val sides = objects.filterNot { it.blocksCenter }
        // Describe occupied regions without claiming that another route is clear.
        if (sides.any { it.region == Region.LEFT } && sides.any { it.region == Region.RIGHT }) {
            return Warning.BOTH_SIDES
        }
        return when (sides.maxByOrNull { it.area }?.region) {
            Region.LEFT -> Warning.OBSTACLE_LEFT
            Region.RIGHT -> Warning.OBSTACLE_RIGHT
            else -> null
        }
    }
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
        return if (objects.any { it.region == side && it.proximity != Proximity.FAR })
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
        times[warning] = nowMs
        lastTime = nowMs
        lastWarning = warning
        return true
    }
    fun reset() { times.clear(); lastTime = null; lastWarning = null }
}
