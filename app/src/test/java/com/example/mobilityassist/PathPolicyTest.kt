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

    @Test fun broadBuildingLikeSurfaceGivesCautiousWarningAfterTwoFreshSamples() {
        val sample = PathPolicy.assess(scene(id = 2), 30, 30)
        assertTrue(sample.broadSurface)
        val tracker = PathGuidanceTracker()
        assertNull(tracker.observe(sample).guidance)
        assertEquals(Warning.POSSIBLE_OBSTRUCTION, tracker.observe(sample).guidance)
        assertNull(sample.clearerSide)
    }

    @Test fun unknownUniformMaskNeedsSeparatePlainFrameEvidence() {
        assertFalse(PathPolicy.assess(scene(id = 0), 30, 30).broadSurface)
        assertTrue(PathPolicy.assess(scene(id = 0), 30, 30, plainFrame = true).broadSurface)
        assertFalse(PathPolicy.assess(scene(id = 4), 30, 30, plainFrame = true).broadSurface)
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
        assertNull(tracker.observe(open).guidance)
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

    @Test fun weakerBarrierRequiresTwoFreshModelSamples() {
        val tracker = PathGuidanceTracker()
        val labels = scene(id = 4)
        for (y in 12 until 23) for (x in 10 until 16) labels[y * 30 + x] = 1
        val sample = PathPolicy.assess(labels, 30, 30)
        assertTrue(sample.blocked)
        assertTrue(sample.wallFraction < .75f)
        assertNull(tracker.observe(sample).guidance)
        assertNotNull(tracker.observe(sample).guidance)
        assertTrue(PathSegmenter.FAST_SAMPLE_INTERVAL_MS < 1_000L)
    }

    @Test fun aKnownSideObstaclePreventsDirectionalAdvice() {
        val sample = PathGuidanceTracker().observe(sideCorridor(left = true))
        val person = Obstacle("person", .9f, .05f, .1f, .3f, .9f)
        assertEquals(Warning.PATH_BLOCKED,
            PathWarningSelector.select(sample, listOf(person), true))
        assertNull(PathWarningSelector.select(sample, emptyList(), false))
    }
}
