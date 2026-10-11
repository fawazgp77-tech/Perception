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

    private fun sideCorridor(left: Boolean): PathAssessment {
        val labels = scene()
        for (y in 23 until 30) for (x in 0 until 30) labels[y * 30 + x] = 4
        val xs = if (left) 0 until 10 else 20 until 30
        for (y in 0 until 23) for (x in xs) labels[y * 30 + x] = 4
        return PathPolicy.assess(labels, 30, 30)
    }

    @Test fun connectedSideCorridorGivesDirection() {
        assertEquals(Region.LEFT, sideCorridor(left = true).clearerSide)
        assertEquals(Region.RIGHT, sideCorridor(left = false).clearerSide)
        assertNull(PathPolicy.assess(scene(), 30, 30).clearerSide)
    }

    @Test fun disconnectedPatchOfFloorIsNotARecommendedRoute() {
        val labels = scene()
        for (y in 23 until 30) for (x in 0 until 30) labels[y * 30 + x] = 4
        for (y in 0 until 21) for (x in 0 until 10) labels[y * 30 + x] = 4
        val result = PathPolicy.assess(labels, 30, 30)
        assertTrue(result.blocked)
        assertNull(result.clearerSide)
    }

    @Test fun blockedViewThenTurnToOpenPathAnnouncesOnce() {
        val tracker = PathGuidanceTracker()
        val blocked = sideCorridor(left = true)
        assertEquals(Warning.PATH_LEFT, tracker.observe(blocked).guidance)
        val open = PathPolicy.assess(scene(id = 4), 30, 30)
        assertTrue(open.forwardClear)
        assertEquals(Warning.PATH_CLEAR, tracker.observe(open).guidance)
        assertNull(tracker.observe(open).guidance)
    }

    @Test fun ambiguousSceneNeverAnnouncesOpenPathOrSide() {
        val tracker = PathGuidanceTracker()
        tracker.observe(PathPolicy.assess(scene(), 30, 30))
        val unknown = PathPolicy.assess(scene(id = 0), 30, 30)
        assertNull(unknown.clearerSide)
        assertFalse(unknown.forwardClear)
        repeat(4) { assertNull(tracker.observe(unknown).guidance) }
    }

    @Test fun narrowForwardOpeningNeedsTwoNewResults() {
        val tracker = PathGuidanceTracker()
        assertEquals(Warning.PATH_BLOCKED,
            tracker.observe(PathPolicy.assess(scene(), 30, 30)).guidance)
        val labels = scene(id = 0)
        for (y in 0 until 30) for (x in 10 until 12) labels[y * 30 + x] = 4
        val narrow = PathPolicy.assess(labels, 30, 30)
        assertTrue(narrow.forwardClear)
        assertFalse(narrow.strongForwardClear)
        assertNull(tracker.observe(narrow).guidance)
        assertEquals(Warning.PATH_CLEAR, tracker.observe(narrow).guidance)
    }

    @Test fun weakerBarrierRequiresTwoFreshModelSamples() {
        val tracker = PathGuidanceTracker()
        val labels = scene(id = 0)
        for (y in 23 until 30) for (x in 0 until 30) labels[y * 30 + x] = 4
        for (y in 12 until 23) for (x in 10 until 14) labels[y * 30 + x] = 1
        val sample = PathPolicy.assess(labels, 30, 30)
        assertTrue(sample.blocked)
        assertTrue(sample.wallFraction < .50f)
        assertNull(tracker.observe(sample).guidance)
        assertNotNull(tracker.observe(sample).guidance)
        assertTrue(PathSegmenter.FAST_SAMPLE_INTERVAL_MS < 1_000L)
    }

    @Test fun arModeRetainsPreviousWallThreshold() {
        val labels = scene(id = 0)
        for (y in 12 until 23) for (x in 10 until 15) labels[y * 30 + x] = 1
        assertTrue(PathPolicy.assess(labels, 30, 30, responsive = true).blocked)
        assertFalse(PathPolicy.assess(labels, 30, 30, responsive = false).blocked)
    }

    @Test fun aKnownSideObstaclePreventsDirectionalAdvice() {
        val sample = PathGuidanceTracker().observe(sideCorridor(left = true))
        val person = Obstacle("person", .9f, .05f, .1f, .3f, .9f)
        assertEquals(Warning.PATH_BLOCKED,
            CameraGuidancePlanner.decide(listOf(person), sample, true))
        assertNull(CameraGuidancePlanner.decide(emptyList(), sample, false))
    }

    @Test fun centerObstacleUsesVisibleSideCorridor() {
        val path = sideCorridor(left = false)
        val near = Obstacle("person", .9f, .32f, .1f, .68f, .55f)
        val veryNear = near.copy(top = .05f, bottom = .9f)
        assertEquals(Warning.AHEAD_RIGHT,
            CameraGuidancePlanner.decide(listOf(near), path, true))
        assertEquals(Warning.STOP_RIGHT,
            CameraGuidancePlanner.decide(listOf(veryNear), path, true))
        assertEquals(Warning.AHEAD,
            CameraGuidancePlanner.decide(listOf(near), path, false))
        assertEquals(Warning.AHEAD_LEFT,
            CameraGuidancePlanner.decide(listOf(near), PathPolicy.assess(scene(id = 4), 30, 30), true))
        val sideDog = Obstacle("dog", .9f, .7f, .1f, .96f, .9f)
        assertEquals(Warning.AHEAD,
            CameraGuidancePlanner.decide(listOf(near, sideDog), path, true))
    }

    @Test fun distantPersonCanSupportPartialSidePathButNearbyPersonBlocksIt() {
        val labels = scene(id = 0)
        for (y in 0 until 23) for (x in 10 until 20) labels[y * 30 + x] = 1
        for (y in 23 until 30) for (x in 10 until 20) labels[y * 30 + x] = 4
        for (y in 16 until 30) for (x in 0 until 2) labels[y * 30 + x] = 4
        val path = PathGuidanceTracker().observe(PathPolicy.assess(labels, 30, 30))
        val farPerson = Obstacle("person", .9f, .03f, .2f, .17f, .65f)
        assertEquals(Warning.PATH_BLOCKED,
            CameraGuidancePlanner.decide(emptyList(), path, true))
        assertEquals(Warning.PATH_LEFT,
            CameraGuidancePlanner.decide(listOf(farPerson), path, true))
        val nearPerson = farPerson.copy(right = .3f, bottom = .9f)
        assertEquals(Warning.PATH_BLOCKED,
            CameraGuidancePlanner.decide(listOf(nearPerson), path, true))
        val narrowNearPerson = Obstacle("person", .9f, .03f, .05f, .14f, .9f)
        assertEquals(Warning.PATH_BLOCKED,
            CameraGuidancePlanner.decide(listOf(narrowNearPerson), path, true))
        val tallThinPerson = narrowNearPerson.copy(right = .10f)
        assertEquals(Proximity.FAR, tallThinPerson.proximity)
        assertEquals(Warning.PATH_BLOCKED,
            CameraGuidancePlanner.decide(listOf(tallThinPerson), path, true))
    }
}
