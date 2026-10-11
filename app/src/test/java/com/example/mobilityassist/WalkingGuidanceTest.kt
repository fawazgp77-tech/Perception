package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Test

class WalkingGuidanceTest {
    private val start = GeoPosition(20.0, 77.0)
    private val turn = GeoPosition(20.0, 77.001)
    private val end = GeoPosition(20.0, 77.002)

    @Test fun approachingTurnIsAnnouncedOnce() {
        val guide = WalkingGuidance(listOf(start, turn, end), listOf(RouteTurn(turn, "Turn right")), end)
        val straight = guide.update(start, 5f)
        assertEquals("straight_0", straight?.key)
        guide.markSpoken(straight!!)
        val soon = guide.update(GeoPosition(20.0, 77.0006), 5f)
        assertNotNull(soon)
        assertTrue(soon!!.text.contains("Turn right", ignoreCase = true))
        guide.markSpoken(soon)
        assertNull(guide.update(GeoPosition(20.0, 77.0006), 5f))
        val now = guide.update(GeoPosition(20.0, 77.00095), 5f)
        assertEquals("Turn right now.", now?.text)
        assertEquals(now, guide.update(GeoPosition(20.0, 77.00096), 5f))
        guide.markSpoken(now!!)
        assertNull(guide.update(GeoPosition(20.0, 77.00096), 5f))
    }

    @Test fun uncertainFixSuppressesTurns() {
        val guide = WalkingGuidance(listOf(start, turn, end), listOf(RouteTurn(turn, "Turn left")), end)
        val cue = guide.update(GeoPosition(20.0, 77.00095), 45f)
        assertEquals("uncertain", cue?.key)
    }

    @Test fun offRouteNeedsFourClearlySeparatedFixes() {
        val guide = WalkingGuidance(listOf(start, end), emptyList(), end)
        val away = GeoPosition(20.001, 77.0)
        assertNull(guide.update(away, 5f))
        assertNull(guide.update(away, 5f))
        assertNull(guide.update(away, 5f))
        assertEquals("reroute", guide.update(away, 5f)?.key)
    }

    @Test fun inaccurateFixBreaksOffRouteStreak() {
        val guide = WalkingGuidance(listOf(start, end), emptyList(), end)
        val away = GeoPosition(20.001, 77.0)
        assertNull(guide.update(away, 5f))
        assertEquals("uncertain", guide.update(away, 50f)?.key)
        assertNull(guide.update(away, 5f))
        assertNull(guide.update(away, 5f))
        assertNull(guide.update(away, 5f))
        assertEquals("reroute", guide.update(away, 5f)?.key)
    }

    @Test fun arrivalRequiresTwoCloseFixes() {
        val guide = WalkingGuidance(listOf(start, end), emptyList(), end)
        assertNotEquals("arrival", guide.update(end, 5f)?.key)
        assertEquals("arrival", guide.update(end, 5f)?.key)
    }

    @Test fun gpsOffsetOnSameRoadDoesNotRerouteAndCanBeSnappedForDisplay() {
        val guide = WalkingGuidance(listOf(start, end), emptyList(), end)
        val nearby = GeoPosition(20.0003, 77.0007) // About 33 m from route centerline.
        repeat(6) { assertNotEquals("reroute", guide.update(nearby, 12f)?.key) }
        assertTrue(guide.lastMatch!!.distanceMeters in 25.0..40.0)
        assertEquals(20.0, guide.lastMatch!!.point.latitude, 0.00001)
    }

    @Test fun uncertainMapMatchDoesNotGiveAConfidentStraightCue() {
        val guide = WalkingGuidance(listOf(start, end), emptyList(), end)
        val displaced = GeoPosition(20.00043, 77.0007) // Roughly 48 m off the line.
        assertEquals("route_uncertain", guide.update(displaced, 10f)?.key)
        assertFalse(guide.isOffRoute())
    }

    @Test fun geometryProvidesLeftTurnWhenApiStepsAreMissing() {
        val corner = GeoPosition(20.0, 77.001)
        val north = GeoPosition(20.001, 77.001)
        val turns = RouteGeometry.inferredTurns(listOf(start, corner, north))
        assertEquals(1, turns.size)
        assertEquals("Turn left", turns.single().instruction)
        val guide = WalkingGuidance(listOf(start, corner, north), turns, north)
        val initial = guide.update(start, 8f)
        if (initial != null) guide.markSpoken(initial)
        assertTrue(guide.update(GeoPosition(20.0, 77.0006), 8f)!!.text.contains("Turn left"))
    }
}
