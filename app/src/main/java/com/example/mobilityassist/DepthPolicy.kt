package com.example.mobilityassist

/** Center-band depth evidence. Zero or sparse depth must never imply a clear path. */
data class DepthObservation(
    val upperMedianMm: Int,
    val lowerMedianMm: Int,
    val validFraction: Float
)

object DepthPolicy {
    fun suggestsBarrier(observation: DepthObservation?, path: PathAssessment?): Boolean {
        if (observation == null || observation.validFraction < 0.40f) return false
        val upper = observation.upperMedianMm
        val lower = observation.lowerMedianMm
        if (upper !in 300..2_200 || lower !in 300..2_200) return false
        // A broad vertical surface tends to have similar depth in two view bands;
        // ground depth typically gets much closer toward the bottom of the image.
        if (kotlin.math.abs(upper - lower) > 450) return false
        // Do not override clear walkable segmentation solely with a close floor estimate.
        return path == null || (!path.pathContinues && path.pathFraction < 0.30f)
    }
}
