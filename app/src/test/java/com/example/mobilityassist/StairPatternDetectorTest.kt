package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Test

class StairPatternDetectorTest {
    private val width = 160
    private val height = 120

    @Test fun repeatedWideHorizontalEdgesAreOnlyAStairCandidate() {
        val image = IntArray(width * height) { i -> if ((i / width / 9) % 2 == 0) 50 else 190 }
        val result = StairPatternPolicy.analyze(image, width, height)
        assertTrue(result.likelyStairs)
        assertTrue(result.visibleEdges >= 3)
    }

    @Test fun plainOrVerticalTextureDoesNotLookLikeSteps() {
        assertFalse(StairPatternPolicy.analyze(IntArray(width * height) { 100 }, width, height).likelyStairs)
        val vertical = IntArray(width * height) { i -> if ((i % width / 8) % 2 == 0) 30 else 200 }
        assertFalse(StairPatternPolicy.analyze(vertical, width, height).likelyStairs)
    }

    @Test fun twoFreshCandidatesRequiredAndAFlatFrameResets() {
        val tracker = StairPatternTracker()
        val candidate = StairObservation(5, true)
        assertNull(tracker.observe(candidate))
        assertNull(tracker.observe(null))
        assertEquals(Warning.POSSIBLE_STAIRS, tracker.observe(candidate))
        assertNull(tracker.observe(StairObservation(0, false)))
        assertNull(tracker.observe(candidate))
    }
}
