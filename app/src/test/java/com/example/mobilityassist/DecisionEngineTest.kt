package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Test

class DecisionEngineTest {
    private val engine = DecisionEngine()
    private fun center(height: Float) = Obstacle("person", .9f, .25f, 0f, .75f, height)
    private val left = Obstacle("dog", .9f, 0f, 0f, .2f, .8f)
    private val right = left.copy(left = .8f, right = 1f)

    @Test fun sizeThresholdsAndPriority() {
        assertEquals(Proximity.FAR, center(.1f).proximity)
        assertEquals(Proximity.NEAR, center(.16f).proximity)
        assertEquals(Proximity.VERY_NEAR, center(.5f).proximity)
        assertNull(engine.decide(listOf(center(.1f))))
        assertEquals(Warning.AHEAD, engine.decide(listOf(left, center(.2f))))
        assertEquals(Warning.STOP, engine.decide(listOf(left, right, center(.5f))))
    }
    @Test fun sideRulesAndEmptyScene() {
        assertNull(engine.decide(emptyList()))
        assertEquals(Warning.OBSTACLE_LEFT, engine.decide(listOf(left)))
        assertEquals(Warning.OBSTACLE_RIGHT, engine.decide(listOf(right)))
        assertEquals(Warning.BOTH_SIDES, engine.decide(listOf(left, right)))
    }
    @Test fun decisionRulesAlsoHandleNewCocoClasses() {
        val chair = Obstacle("chair", 0.8f, .25f, .1f, .75f, .8f)
        assertEquals(Warning.STOP, engine.decide(listOf(chair)))
    }
    @Test fun regionBoundaries() {
        assertEquals(Region.CENTER, left.copy(left = 1f / 3, right = 1f / 3).region)
        assertEquals(Region.RIGHT, left.copy(left = 2f / 3, right = 2f / 3).region)
    }

    @Test fun wideObjectOccupyingCenterIsTreatedAsAhead() {
        val wideCar = Obstacle("car", .9f, .05f, 0f, .56f, .8f)
        assertEquals(Region.LEFT, wideCar.region)
        assertTrue(wideCar.blocksCenter)
        assertEquals(Warning.STOP, engine.decide(listOf(wideCar)))
        assertFalse(left.blocksCenter)
    }
    @Test fun cooldownAndUrgentPreemption() {
        val gate = WarningGate()
        assertTrue(gate.allow(Warning.OBSTACLE_RIGHT, 0))
        assertFalse(gate.allow(Warning.OBSTACLE_LEFT, 100))
        assertTrue(gate.allow(Warning.STOP, 200))
        assertFalse(gate.allow(Warning.STOP, 4_199))
        assertTrue(gate.allow(Warning.STOP, 4_200))
        assertFalse(gate.allow(Warning.AHEAD, 4_300))
        gate.reset()
        assertTrue(gate.allow(Warning.AHEAD, 4_400))
    }

    @Test fun possibleWallReminderIsQuietButStopStillInterrupts() {
        val gate = WarningGate()
        assertTrue(gate.allow(Warning.POSSIBLE_WALL, 0L))
        assertFalse(gate.allow(Warning.POSSIBLE_WALL, 10_000L))
        assertTrue(gate.allow(Warning.STOP, 10_100L))
        assertTrue(gate.allow(Warning.POSSIBLE_WALL, 16_200L))
    }

    @Test fun nonurgentGuidanceNeedsFreshConfirmation() {
        val stabilizer = GuidanceStabilizer()
        assertNull(stabilizer.observe(Warning.OBSTACLE_LEFT, 0))
        assertEquals(Warning.OBSTACLE_LEFT, stabilizer.observe(Warning.OBSTACLE_LEFT, 200))
        assertNull(stabilizer.observe(Warning.OBSTACLE_RIGHT, 300))
        assertNull(stabilizer.observe(null, 400))
        assertNull(stabilizer.observe(Warning.OBSTACLE_RIGHT, 500))
        assertEquals(Warning.OBSTACLE_RIGHT, stabilizer.observe(Warning.OBSTACLE_RIGHT, 700))
        assertEquals(Warning.STOP, stabilizer.observe(Warning.STOP, 750))
        assertNull(stabilizer.observe(Warning.AHEAD, 800))
        assertEquals(Warning.AHEAD, stabilizer.observe(Warning.AHEAD, 900))
        stabilizer.reset()
        assertNull(stabilizer.observe(Warning.AHEAD, 1_000))
        assertNull(stabilizer.observe(Warning.AHEAD, 2_300)) // Stale observations do not confirm.
    }

    @Test fun directionCanFollowGenericBlockButDoesNotRepeatEveryFrame() {
        val gate = WarningGate()
        assertTrue(gate.allow(Warning.PATH_BLOCKED, 0L))
        assertTrue(gate.allow(Warning.PATH_LEFT, 700L))
        assertFalse(gate.allow(Warning.PATH_LEFT, 1_400L))
        assertTrue(gate.allow(Warning.PATH_CLEAR, 2_100L))
        assertFalse(gate.allow(Warning.PATH_CLEAR, 2_800L))
        assertFalse(gate.allow(Warning.PATH_BLOCKED, 3_000L))
        assertTrue(gate.allow(Warning.PATH_BLOCKED, 4_200L))
    }
}
