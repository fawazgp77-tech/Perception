package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Test

class PathPolicyTest {
    private fun scene(width: Int = 30, height: Int = 30, id: Int = 1) =
        IntArray(width * height) { id }

    @Test fun centeredWallProducesBlockedCandidate() {
        val result = PathPolicy.assess(scene(), 30, 30)
        assertTrue(result.blocked)
        assertTrue(result.wallFraction > .9f)
    }

    @Test fun continuousFloorAndSidewalkAreNotBlocked() {
        val floor = PathPolicy.assess(scene(id = 4), 30, 30)
        assertFalse(floor.blocked)
        assertTrue(floor.pathContinues)
        assertFalse(PathPolicy.assess(scene(id = 12), 30, 30).blocked)
    }

    @Test fun missingPathAloneDoesNotClaimWall() {
        assertFalse(PathPolicy.assess(scene(id = 2), 30, 30).blocked)
        assertFalse(PathPolicy.assess(scene(id = 0), 30, 30).blocked)
    }

    @Test fun wallOutsideCenterDoesNotBlockForwardPath() {
        val labels = scene(id = 4)
        for (y in 0 until 30) for (x in 0 until 8) labels[y * 30 + x] = 1
        assertFalse(PathPolicy.assess(labels, 30, 30).blocked)
    }

    @Test fun wallAcrossCenterWithFloorAtFeetIsStillBlocked() {
        val labels = scene(id = 1)
        for (y in 23 until 30) for (x in 0 until 30) labels[y * 30 + x] = 4
        assertTrue(PathPolicy.assess(labels, 30, 30).blocked)
    }

    @Test fun sparseDepthOrFloorSlopeDoesNotSuggestBarrier() {
        val uncertain = PathPolicy.assess(scene(id = 0), 30, 30)
        assertFalse(DepthPolicy.suggestsBarrier(DepthObservation(1_200, 1_180, .2f), uncertain))
        assertFalse(DepthPolicy.suggestsBarrier(DepthObservation(1_900, 850, .9f), uncertain))
    }

    @Test fun closeFlatDepthAddsEvidenceWhenNoPathVisible() {
        val uncertain = PathPolicy.assess(scene(id = 0), 30, 30)
        assertTrue(DepthPolicy.suggestsBarrier(DepthObservation(1_100, 1_250, .8f), uncertain))
        val floor = PathPolicy.assess(scene(id = 4), 30, 30)
        assertFalse(DepthPolicy.suggestsBarrier(DepthObservation(1_100, 1_250, .8f), floor))
    }
}
