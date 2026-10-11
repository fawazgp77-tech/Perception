package com.example.mobilityassist

import kotlin.math.*

data class GeoPosition(val latitude: Double, val longitude: Double)
data class RouteTurn(val position: GeoPosition, val instruction: String)
data class NavigationCue(val key: String, val text: String)

/** GPS route progress is deliberately conservative: no turn instruction on an uncertain fix. */
class WalkingGuidance(
    private val shape: List<GeoPosition>,
    private val turns: List<RouteTurn>,
    private val destination: GeoPosition
) {
    private var nextTurn = 0
    private var offRouteFixes = 0
    private var arrivalFixes = 0
    private val spoken = mutableSetOf<String>()
    private var pendingNow: Pair<Int, NavigationCue>? = null

    fun update(position: GeoPosition, accuracyMeters: Float): NavigationCue? {
        if (accuracyMeters > 25f) return NavigationCue("uncertain", "Location accuracy is low. Turn guidance is paused.")

        val toDestination = distanceMeters(position, destination)
        if (toDestination <= 18.0 && accuracyMeters <= 15f) arrivalFixes++ else arrivalFixes = 0
        if (arrivalFixes >= 2) return unspoken("arrival", "Near destination. Check the entrance and surroundings.")

        if (shape.size >= 2 && distanceToRoute(position, shape) > max(45.0, accuracyMeters * 2.0))
            offRouteFixes++ else offRouteFixes = 0
        if (offRouteFixes >= 3) return NavigationCue("reroute", "You may be off the walking route. Updating directions.")

        pendingNow?.let { (i, cue) ->
            if (distanceMeters(position, turns[i].position) <= 25.0)
                return unspoken(cue.key, cue.text)
            pendingNow = null
        }
        if (nextTurn < turns.size && distanceMeters(position, turns[nextTurn].position) <= 12.0) {
            val i = nextTurn++
            val cue = NavigationCue("turn_now_$i", turns[i].instruction)
            pendingNow = i to cue
            return cue
        }
        if (nextTurn < turns.size) {
            val turn = turns[nextTurn]
            val distance = distanceMeters(position, turn.position)
            if (distance <= 55.0) {
                val rounded = ((distance / 10.0).roundToInt() * 10).coerceAtLeast(10)
                return unspoken("turn_soon_$nextTurn", "In about $rounded meters, ${turn.instruction.replaceFirstChar { it.lowercase() }}")
            }
        }
        return null
    }

    fun markSpoken(cue: NavigationCue) {
        spoken.add(cue.key)
        if (pendingNow?.second?.key == cue.key) pendingNow = null
    }

    fun isOffRoute(): Boolean = offRouteFixes >= 3

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

        fun distanceToRoute(position: GeoPosition, points: List<GeoPosition>): Double {
            if (points.isEmpty()) return Double.POSITIVE_INFINITY
            if (points.size == 1) return distanceMeters(position, points.first())
            val scale = 111_320.0
            val cosLat = cos(Math.toRadians(position.latitude))
            return points.zipWithNext().minOf { (a, b) ->
                val ax = (a.longitude - position.longitude) * scale * cosLat
                val ay = (a.latitude - position.latitude) * scale
                val bx = (b.longitude - position.longitude) * scale * cosLat
                val by = (b.latitude - position.latitude) * scale
                val dx = bx - ax
                val dy = by - ay
                val t = if (dx * dx + dy * dy < 1e-9) 0.0
                    else (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                hypot(ax + t * dx, ay + t * dy)
            }
        }
    }
}
