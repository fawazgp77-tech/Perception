package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Test

class WalkingGuidanceTest {
    private val start = GeoPosition(20.0, 77.0)
    private val turn = GeoPosition(20.0, 77.001)
    private val end = GeoPosition(20.0, 77.002)

    @Test fun approachingTurnIsAnnouncedOnce() {
        val guide = WalkingGuidance(listOf(start, turn, end), listOf(RouteTurn(turn, "Turn right")), end)
        assertNull(guide.update(start, 5f))
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
        val cue = guide.update(GeoPosition(20.0, 77.00095), 40f)
        assertEquals("uncertain", cue?.key)
    }

    @Test fun offRouteNeedsTwoAccurateFixes() {
        val guide = WalkingGuidance(listOf(start, end), emptyList(), end)
        val away = GeoPosition(20.001, 77.0)
        assertNull(guide.update(away, 5f))
        assertEquals("reroute", guide.update(away, 5f)?.key)
    }

    @Test fun inaccurateFixBreaksOffRouteStreak() {
        val guide = WalkingGuidance(listOf(start, end), emptyList(), end)
        val away = GeoPosition(20.001, 77.0)
        assertNull(guide.update(away, 5f))
        assertEquals("uncertain", guide.update(away, 30f)?.key)
        assertNull(guide.update(away, 5f))
        assertEquals("reroute", guide.update(away, 5f)?.key)
    }

    @Test fun arrivalRequiresTwoCloseFixes() {
        val guide = WalkingGuidance(listOf(start, end), emptyList(), end)
        assertNull(guide.update(end, 5f))
        assertEquals("arrival", guide.update(end, 5f)?.key)
    }
}
