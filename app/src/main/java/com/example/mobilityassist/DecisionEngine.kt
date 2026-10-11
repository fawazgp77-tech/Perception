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
    STOP_LEFT("Stop. Obstacle ahead. Check left before moving.", true),
    STOP_RIGHT("Stop. Obstacle ahead. Check right before moving.", true),
    AHEAD("Obstacle ahead."),
    AHEAD_LEFT("Obstacle ahead. Check left for a way through."),
    AHEAD_RIGHT("Obstacle ahead. Check right for a way through."),
    POSSIBLE_WALL("Possible wall ahead."),
    PATH_BLOCKED("Path ahead may be blocked."),
    PATH_LEFT("Path ahead may be blocked. Turn left to check for a way through."),
    PATH_RIGHT("Path ahead may be blocked. Turn right to check for a way through."),
    PATH_CLEAR("Path straight ahead appears open. Continue carefully."),
    OBSTACLE_LEFT("Obstacle on left. Check right."),
    OBSTACLE_RIGHT("Obstacle on right. Check left."),
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

/** Combines scene surfaces and object boxes before deciding where to check. */
object CameraGuidancePlanner {
    private val objectEngine = DecisionEngine()

    fun decide(objects: List<Obstacle>, path: PathAssessment?, viewReliable: Boolean): Warning? {
        val objectWarning = objectEngine.decide(objects)
        val side = if (viewReliable && path != null) chooseSide(path, objects) else null
        when (objectWarning) {
            Warning.STOP -> return when (side) {
                Region.LEFT -> Warning.STOP_LEFT
                Region.RIGHT -> Warning.STOP_RIGHT
                else -> Warning.STOP
            }
            Warning.AHEAD -> return when (side) {
                Region.LEFT -> Warning.AHEAD_LEFT
                Region.RIGHT -> Warning.AHEAD_RIGHT
                else -> Warning.AHEAD
            }
            else -> Unit
        }
        if (viewReliable && path?.guidance in listOf(
                Warning.PATH_BLOCKED, Warning.PATH_LEFT, Warning.PATH_RIGHT
            )) return when (side) {
            Region.LEFT -> Warning.PATH_LEFT
            Region.RIGHT -> Warning.PATH_RIGHT
            else -> Warning.PATH_BLOCKED
        }
        if (viewReliable && path?.guidance == Warning.PATH_CLEAR) return Warning.PATH_CLEAR
        if (objectWarning == Warning.OBSTACLE_LEFT &&
            laneKnownBlocked(Region.RIGHT, path, objects)) return Warning.BOTH_SIDES
        if (objectWarning == Warning.OBSTACLE_RIGHT &&
            laneKnownBlocked(Region.LEFT, path, objects)) return Warning.BOTH_SIDES
        return objectWarning
    }

    private fun chooseSide(path: PathAssessment, objects: List<Obstacle>): Region? {
        fun score(side: Region): Float? {
            val evidence = if (side == Region.LEFT) path.leftSide else path.rightSide
            if (evidence.wallFraction >= 0.38f || evidence.footingFraction < 0.12f ||
                laneHasNearObject(side, objects)) return null
            val personCue = objects.any {
                it.label.equals("person", ignoreCase = true) && it.region == side &&
                    it.proximity == Proximity.FAR && it.bottom <= 0.75f && it.score >= 0.45f
            }
            val enoughGround = evidence.reachableFraction >= 0.14f &&
                evidence.footingFraction >= 0.18f
            val partialGroundWithPerson = personCue && !enoughGround &&
                evidence.reachableFraction >= 0.05f
            if (!enoughGround && !partialGroundWithPerson) return null
            return evidence.reachableFraction - evidence.wallFraction * 0.25f +
                if (partialGroundWithPerson) 0.04f else 0f
        }
        val left = score(Region.LEFT)
        val right = score(Region.RIGHT)
        if (left == null) return if (right != null) Region.RIGHT else null
        if (right == null) return Region.LEFT
        if (left - right >= 0.05f) return Region.LEFT
        if (right - left >= 0.05f) return Region.RIGHT
        // When two routes look alike, prefer the side farther from the center box.
        val center = objects.filter { it.blocksCenter }.maxByOrNull { it.area }
        val centerX = center?.let { (it.left + it.right) / 2f } ?: return null
        return when {
            centerX < 0.45f -> Region.RIGHT
            centerX > 0.55f -> Region.LEFT
            path.leftSide.reachableFraction >= 0.20f &&
                path.rightSide.reachableFraction >= 0.20f -> Region.LEFT
            else -> null
        }
    }

    private fun laneKnownBlocked(side: Region, path: PathAssessment?, objects: List<Obstacle>) =
        laneHasNearObject(side, objects) ||
            (if (side == Region.LEFT) path?.leftSide else path?.rightSide)
                ?.wallFraction?.let { it >= 0.50f } == true

    private fun laneHasNearObject(side: Region, objects: List<Obstacle>): Boolean {
        val laneStart = if (side == Region.LEFT) 0f else 2f / 3f
        val laneEnd = if (side == Region.LEFT) 1f / 3f else 1f
        return objects.any {
            val visuallyNear = it.proximity != Proximity.FAR ||
                (it.bottom >= 0.82f && it.bottom - it.top >= 0.45f)
            visuallyNear && it.bottom >= 0.45f &&
                (it.region == side ||
                    (minOf(it.right, laneEnd) - maxOf(it.left, laneStart)) >= 0.16f)
        }
    }
}

/** Confirm nonurgent guidance across successive analyzed frames. STOP is immediate. */
class GuidanceStabilizer(private val requiredFrames: Int = 2, private val maxGapMs: Long = 1_200) {
    private var pending: Warning? = null
    private var streak = 0
    private var lastObservedMs: Long? = null

    fun observe(candidate: Warning?, nowMs: Long): Warning? {
        if (candidate?.urgent == true) {
            reset()
            return candidate
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
    private var lastUrgentTime: Long? = null
    private var lastUrgentWarning: Warning? = null
    fun allow(warning: Warning, nowMs: Long): Boolean {
        val pathBarrier = warning == Warning.PATH_BLOCKED ||
            warning == Warning.PATH_LEFT || warning == Warning.PATH_RIGHT
        val newBlockAfterRecovery = lastWarning == Warning.PATH_CLEAR &&
            pathBarrier &&
            lastTime?.let { nowMs - it >= 2_000L } == true
        val perWarningCooldown = when (warning) {
            Warning.STOP, Warning.STOP_LEFT, Warning.STOP_RIGHT -> 4_000L
            Warning.PATH_CLEAR -> 2_000L
            Warning.POSSIBLE_WALL, Warning.PATH_BLOCKED, Warning.PATH_LEFT,
            Warning.PATH_RIGHT -> 15_000L
            else -> cooldownMs
        }
        if (warning.urgent && lastUrgentTime?.let { nowMs - it < 4_000L } == true) {
            val upgrade = lastUrgentWarning == Warning.STOP &&
                (warning == Warning.STOP_LEFT || warning == Warning.STOP_RIGHT) &&
                lastUrgentTime?.let { nowMs - it >= 300L } == true
            if (!upgrade) return false
        }
        if (!newBlockAfterRecovery &&
            times[warning]?.let { nowMs - it < perWarningCooldown } == true) return false
        // A newly found route after a blocked view should be announced once promptly.
        val directionUpgrade = when (lastWarning) {
            Warning.PATH_BLOCKED -> warning == Warning.PATH_LEFT || warning == Warning.PATH_RIGHT
            Warning.AHEAD -> warning == Warning.AHEAD_LEFT || warning == Warning.AHEAD_RIGHT
            Warning.STOP -> warning == Warning.STOP_LEFT || warning == Warning.STOP_RIGHT
            else -> false
        }
        val pathAfterOther = pathBarrier &&
            lastWarning != Warning.PATH_BLOCKED &&
            lastWarning != Warning.PATH_LEFT && lastWarning != Warning.PATH_RIGHT &&
            lastWarning?.urgent != true
        if (!warning.urgent && warning != Warning.PATH_CLEAR &&
            !directionUpgrade && !newBlockAfterRecovery && !pathAfterOther &&
            lastTime?.let { nowMs - it < cooldownMs } == true) return false
        times[warning] = nowMs
        lastTime = nowMs
        lastWarning = warning
        if (warning.urgent) {
            lastUrgentTime = nowMs
            lastUrgentWarning = warning
        }
        return true
    }
    fun reset() {
        times.clear(); lastTime = null; lastWarning = null
        lastUrgentTime = null; lastUrgentWarning = null
    }
}
