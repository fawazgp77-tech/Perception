package com.example.mobilityassist

import kotlin.math.*

data class GeoPosition(val latitude: Double, val longitude: Double)
data class RouteTurn(val position: GeoPosition, val instruction: String)
data class NavigationCue(val key: String, val text: String)
data class RouteMatch(val point: GeoPosition, val distanceMeters: Double, val progressMeters: Double)

/** Matches a GPS fix to the walking polyline without claiming lane-level position. */
object RouteGeometry {
    fun match(position: GeoPosition, points: List<GeoPosition>): RouteMatch? {
        if (points.isEmpty()) return null
        if (points.size == 1) return RouteMatch(points[0], WalkingGuidance.distanceMeters(position, points[0]), 0.0)
        val scale = 111_320.0
        val cosLat = cos(Math.toRadians(position.latitude)).coerceAtLeast(0.01)
        var best: RouteMatch? = null
        var traveled = 0.0
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.longitude - position.longitude) * scale * cosLat
            val ay = (a.latitude - position.latitude) * scale
            val bx = (b.longitude - position.longitude) * scale * cosLat
            val by = (b.latitude - position.latitude) * scale
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared < 1e-9) 0.0
                else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(ax + t * dx, ay + t * dy)
            val length = distanceMeters(a, b)
            if (best == null || distance < best.distanceMeters) {
                best = RouteMatch(
                    GeoPosition(a.latitude + (b.latitude - a.latitude) * t,
                        a.longitude + (b.longitude - a.longitude) * t),
                    distance, traveled + length * t)
            }
            traveled += length
        }
        return best
    }

    /** Used when Mappls omits maneuver modifiers or step objects. */
    fun inferredTurns(points: List<GeoPosition>): List<RouteTurn> {
        if (points.size < 3) return emptyList()
        val lengths = DoubleArray(points.size)
        for (i in 1 until points.size) lengths[i] = lengths[i - 1] + distanceMeters(points[i - 1], points[i])
        val result = mutableListOf<RouteTurn>()
        for (i in 1 until points.lastIndex) {
            if (lengths[i] < 18.0 || lengths.last() - lengths[i] < 12.0) continue
            val before = (i - 1 downTo 0).firstOrNull { lengths[i] - lengths[it] >= 18.0 } ?: continue
            val after = (i + 1..points.lastIndex).firstOrNull { lengths[it] - lengths[i] >= 18.0 } ?: continue
            val inBearing = bearing(points[before], points[i])
            val outBearing = bearing(points[i], points[after])
            val change = ((outBearing - inBearing + 540.0) % 360.0) - 180.0
            if (abs(change) < 35.0 || abs(change) > 155.0) continue
            if (result.lastOrNull()?.let { distanceMeters(it.position, points[i]) < 25.0 } == true) continue
            val instruction = when {
                change <= -60.0 -> "Turn left"
                change < 0.0 -> "Take a slight left"
                change >= 60.0 -> "Turn right"
                else -> "Take a slight right"
            }
            result += RouteTurn(points[i], instruction)
        }
        return result
    }

    private fun bearing(a: GeoPosition, b: GeoPosition): Double {
        val phi1 = Math.toRadians(a.latitude)
        val phi2 = Math.toRadians(b.latitude)
        val delta = Math.toRadians(b.longitude - a.longitude)
        return Math.toDegrees(atan2(sin(delta) * cos(phi2),
            cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(delta)))
    }

    private fun distanceMeters(a: GeoPosition, b: GeoPosition) = WalkingGuidance.distanceMeters(a, b)
}

/** Route cues use along-route distance and repeated accurate fixes. */
class WalkingGuidance(
    private val shape: List<GeoPosition>,
    private val turns: List<RouteTurn>,
    private val destination: GeoPosition
) {
    private val orderedTurns = turns.mapNotNull { turn ->
        RouteGeometry.match(turn.position, shape)?.let { turn to it.progressMeters }
    }.sortedBy { it.second }
    private var nextTurn = 0
    private var offRouteFixes = 0
    private var arrivalFixes = 0
    private var lastProgress = 0.0
    private var progressInitialized = false
    private var lastStraightBucket = -1
    private val spoken = mutableSetOf<String>()
    private var pendingNow: Pair<Int, NavigationCue>? = null
    var lastMatch: RouteMatch? = null
        private set

    fun update(position: GeoPosition, accuracyMeters: Float): NavigationCue? {
        if (!accuracyMeters.isFinite() || accuracyMeters > 40f) {
            offRouteFixes = 0
            arrivalFixes = 0
            lastMatch = null
            return NavigationCue("uncertain", "Location accuracy is low. Check your surroundings; directions are paused.")
        }
        val match = RouteGeometry.match(position, shape)
        lastMatch = match
        val routeDistance = match?.distanceMeters ?: Double.POSITIVE_INFINITY
        val offRouteLimit = max(60.0, accuracyMeters * 2.5)
        offRouteFixes = if (routeDistance > offRouteLimit) offRouteFixes + 1 else 0
        if (offRouteFixes >= 4) return NavigationCue("reroute", "You may be off route. Updating walking directions.")
        if (routeDistance > offRouteLimit) return null
        if (routeDistance > max(35.0, accuracyMeters * 1.5))
            return NavigationCue("route_uncertain", "Cannot confirm your position on the walking route. Check your surroundings.")

        // Do not let one noisy fix move progress backwards or skip far ahead.
        if (match != null) {
            if (!progressInitialized) {
                lastProgress = match.progressMeters
                progressInitialized = true
            } else if (match.progressMeters <= lastProgress + 80.0) {
                lastProgress = max(lastProgress, match.progressMeters)
            }
        }
        val toDestination = distanceMeters(position, destination)
        arrivalFixes = if (toDestination <= max(15.0, accuracyMeters.toDouble()) &&
            accuracyMeters <= 20f) arrivalFixes + 1 else 0
        if (arrivalFixes >= 2) return unspoken("arrival", "Near your destination. Check the entrance and surroundings.")

        pendingNow?.let { (i, cue) ->
            if (orderedTurns[i].second - lastProgress >= -20.0) return unspoken(cue.key, cue.text)
            pendingNow = null
        }
        while (nextTurn < orderedTurns.size && orderedTurns[nextTurn].second < lastProgress - 25.0)
            nextTurn++
        if (nextTurn < orderedTurns.size) {
            val (turn, progress) = orderedTurns[nextTurn]
            val remaining = progress - lastProgress
            if (remaining <= 14.0 && accuracyMeters <= 18f) {
                val i = nextTurn++
                val cue = NavigationCue("turn_now_$i", "${turn.instruction} now.")
                pendingNow = i to cue
                return cue
            }
            if (remaining <= 28.0 && accuracyMeters > 18f)
                return unspoken("turn_near_$nextTurn", "${turn.instruction} at the next junction. Check the map position.")
            if (remaining in 15.0..65.0) {
                val rounded = ((remaining / 10.0).roundToInt() * 10).coerceAtLeast(10)
                return unspoken("turn_soon_$nextTurn", "${turn.instruction} in about $rounded meters.")
            }
        }
        val bucket = (lastProgress / 120.0).toInt()
        if (lastStraightBucket != bucket && (nextTurn >= orderedTurns.size ||
            orderedTurns[nextTurn].second - lastProgress > 75.0)) {
            return unspoken("straight_$bucket", "Continue straight on the walking route.")
        }
        return null
    }

    fun markSpoken(cue: NavigationCue) {
        spoken.add(cue.key)
        if (cue.key.startsWith("straight_"))
            lastStraightBucket = cue.key.substringAfter("straight_").toIntOrNull() ?: lastStraightBucket
        if (pendingNow?.second?.key == cue.key) pendingNow = null
    }

    fun isOffRoute(): Boolean = offRouteFixes >= 4

    private fun unspoken(key: String, text: String): NavigationCue? =
        if (key in spoken) null else NavigationCue(key, text)

    companion object {
        fun distanceMeters(a: GeoPosition, b: GeoPosition): Double {
            val phi1 = Math.toRadians(a.latitude)
            val phi2 = Math.toRadians(b.latitude)
            val dPhi = phi2 - phi1
            val dLambda = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
            return 12_742_000.0 * asin(min(1.0, sqrt(h)))
        }

        fun distanceToRoute(position: GeoPosition, points: List<GeoPosition>): Double =
            RouteGeometry.match(position, points)?.distanceMeters ?: Double.POSITIVE_INFINITY
    }
}
