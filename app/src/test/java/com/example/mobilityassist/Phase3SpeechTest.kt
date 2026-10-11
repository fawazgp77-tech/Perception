package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Phase3SpeechTest {

    private lateinit var gate: Phase3SpeechGate

    @Before fun setup() { gate = Phase3SpeechGate() }

    @Test fun allowsFirstMessage() {
        assertTrue(gate.allow("flashlight_on", 8_000L, 0L))
    }

    @Test fun suppressesRepeatWithinCooldown() {
        assertTrue(gate.allow("flashlight_on", 8_000L, 0L))
        assertFalse(gate.allow("flashlight_on", 8_000L, 7_999L))
    }

    @Test fun allowsAfterCooldownExpires() {
        assertTrue(gate.allow("flashlight_on", 8_000L, 0L))
        assertTrue(gate.allow("flashlight_on", 8_000L, 8_000L))
    }

    @Test fun exactlyAtCooldownBoundaryIsAllowed() {
        assertTrue(gate.allow("key", 4_000L, 1_000L))
        // 1000 + 4000 = 5000 — exactly at boundary, allowed
        assertTrue(gate.allow("key", 4_000L, 5_000L))
    }

    @Test fun differentKeysAreIndependent() {
        assertTrue(gate.allow("flashlight_on", 8_000L, 0L))
        // A different key is completely unaffected by flashlight_on cooldown
        assertTrue(gate.allow("angle_tilt_up", 4_000L, 100L))
        // flashlight_on still suppressed
        assertFalse(gate.allow("flashlight_on", 8_000L, 200L))
        // angle_tilt_up still suppressed by its own cooldown
        assertFalse(gate.allow("angle_tilt_up", 4_000L, 300L))
    }

    @Test fun resetClearsAllCooldowns() {
        assertTrue(gate.allow("obstruction", 5_000L, 0L))
        assertFalse(gate.allow("obstruction", 5_000L, 1_000L))
        gate.reset()
        assertTrue(gate.allow("obstruction", 5_000L, 1_001L))
    }

    @Test fun resetAllowsMultipleKeys() {
        gate.allow("key_a", 10_000L, 0L)
        gate.allow("key_b", 10_000L, 0L)
        gate.reset()
        assertTrue(gate.allow("key_a", 10_000L, 100L))
        assertTrue(gate.allow("key_b", 10_000L, 100L))
    }

    @Test fun phase3GateDoesNotShareStateWithWarningGate() {
        // WarningGate and Phase3SpeechGate are separate instances —
        // each manages its own independent cooldown map.
        val obstacleGate = WarningGate(cooldownMs = 2_000L)
        val p3Gate = Phase3SpeechGate()

        // Obstacle gate fires
        assertTrue(obstacleGate.allow(Warning.OBSTACLE_LEFT, 0L))
        // Phase 3 gate completely unaffected by obstacle gate state
        assertTrue(p3Gate.allow("angle_guidance", 4_000L, 100L))
        // Obstacle gate still suppresses its own duplicate within cooldown
        assertFalse(obstacleGate.allow(Warning.OBSTACLE_LEFT, 500L))
        // Phase 3 gate suppresses its own key, unaffected by obstacle gate
        assertFalse(p3Gate.allow("angle_guidance", 4_000L, 600L))
        // Phase 3 key expires independently
        assertTrue(p3Gate.allow("angle_guidance", 4_000L, 4_100L))
        // Obstacle gate expires independently
        assertTrue(obstacleGate.allow(Warning.OBSTACLE_LEFT, 2_000L))
    }

    @Test fun shortCooldownKeyIndependentOfLongCooldownKey() {
        val shortMs = 2_000L
        val longMs = 10_000L
        assertTrue(gate.allow("restore", shortMs, 0L))
        assertTrue(gate.allow("flashlight_off", longMs, 0L))

        // Short key expires
        assertTrue(gate.allow("restore", shortMs, 2_000L))
        // Long key still suppressed
        assertFalse(gate.allow("flashlight_off", longMs, 5_000L))
        // Long key eventually expires
        assertTrue(gate.allow("flashlight_off", longMs, 10_000L))
    }

    @Test fun zeroCooldownAlwaysAllows() {
        assertTrue(gate.allow("instant", 0L, 0L))
        assertTrue(gate.allow("instant", 0L, 0L))
        assertTrue(gate.allow("instant", 0L, 1L))
    }

    @Test fun multipleDistinctObstructionAndAngleKeysAreIndependent() {
        // Simulate realistic usage: obstruction fires, then angle fires,
        // then obstruction fires again — only the per-key cooldown applies.
        assertTrue(gate.allow("occlusion_blocked", 5_000L, 0L))
        assertTrue(gate.allow("angle_TOO_HIGH", 4_000L, 100L))
        assertFalse(gate.allow("occlusion_blocked", 5_000L, 2_000L))
        assertFalse(gate.allow("angle_TOO_HIGH", 4_000L, 2_000L))
        assertTrue(gate.allow("angle_TOO_HIGH", 4_000L, 4_100L))
        assertTrue(gate.allow("occlusion_blocked", 5_000L, 5_000L))
    }
}
