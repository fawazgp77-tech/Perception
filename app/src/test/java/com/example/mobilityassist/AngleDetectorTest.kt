package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for angle classification logic.
 *
 * We test AngleClassifier directly — it is a pure Kotlin object with no
 * Android Context or SensorManager dependency, so these run on the JVM.
 *
 * AngleDetector's sensor registration, smoothing, and persistence logic
 * require a physical device and are covered by the device checklist.
 *
 * ── Coordinate convention ────────────────────────────────────────────────────
 * pitch > 0          : phone face tilting forward, camera pointing at floor
 * pitch = 0          : phone face pointing at horizon (camera pointing up)
 * pitch in [-60, -20]: TARGET RANGE (camera pointing usefully forward/down)
 * pitch < -60        : TOO_LOW (camera angled too far at floor)
 * roll < 0           : device tilting left
 * roll > 0           : device tilting right
 */
class AngleDetectorTest {

    // -----------------------------------------------------------------------
    // GOOD state — within both pitch and roll bounds
    // -----------------------------------------------------------------------

    @Test fun goodAngleAtCenterOfTargetRange() {
        // Pitch -40° is well within [-60, -20]; roll 0° — unambiguously GOOD
        assertEquals(AngleState.GOOD, AngleClassifier.classify(-40f, 0f))
    }

    @Test fun goodAngleAtMinPitchBoundary() {
        // Pitch exactly at PITCH_TARGET_MIN_DEG — within range before dead band
        assertEquals(AngleState.GOOD, AngleClassifier.classify(AngleDetector.PITCH_TARGET_MIN_DEG, 0f))
    }

    @Test fun goodAngleAtMaxPitchBoundary() {
        // Pitch exactly at PITCH_TARGET_MAX_DEG — within range before dead band
        assertEquals(AngleState.GOOD, AngleClassifier.classify(AngleDetector.PITCH_TARGET_MAX_DEG, 0f))
    }

    @Test fun goodAngleAtMaxRollBoundary() {
        // Roll exactly at ROLL_ACCEPTABLE_DEG — within range before dead band
        assertEquals(AngleState.GOOD,
            AngleClassifier.classify(-40f, AngleDetector.ROLL_ACCEPTABLE_DEG))
    }

    @Test fun goodAngleWithSmallNegativeRoll() {
        // Roll -15° — within ±20° acceptable range
        assertEquals(AngleState.GOOD, AngleClassifier.classify(-40f, -15f))
    }

    // -----------------------------------------------------------------------
    // TOO_HIGH — camera pointing too far upward
    // -----------------------------------------------------------------------

    @Test fun tooHighWhenPitchAboveMaxPlusDeadBand() {
        // Pitch -20 + 5 = -15; anything > -15 is TOO_HIGH
        val justOutside = AngleDetector.PITCH_TARGET_MAX_DEG + AngleDetector.DEAD_BAND_DEG + 0.1f
        assertEquals(AngleState.TOO_HIGH, AngleClassifier.classify(justOutside, 0f))
    }

    @Test fun tooHighAtZeroPitch() {
        // Pitch 0° — phone face at horizon, camera pointing straight up → TOO_HIGH
        assertEquals(AngleState.TOO_HIGH, AngleClassifier.classify(0f, 0f))
    }

    @Test fun tooHighAtPositivePitch() {
        // Pitch +90° — phone face pointing downward, rear camera pointing up → TOO_HIGH
        assertEquals(AngleState.TOO_HIGH, AngleClassifier.classify(90f, 0f))
    }

    @Test fun stillGoodAtExactDeadBandBoundaryHigh() {
        // Exactly at PITCH_TARGET_MAX_DEG + DEAD_BAND_DEG — NOT strictly greater → GOOD
        val boundary = AngleDetector.PITCH_TARGET_MAX_DEG + AngleDetector.DEAD_BAND_DEG
        assertEquals(AngleState.GOOD, AngleClassifier.classify(boundary, 0f))
    }

    // -----------------------------------------------------------------------
    // TOO_LOW — camera pointing at the floor
    // -----------------------------------------------------------------------

    @Test fun tooLowWhenPitchBelowMinMinusDeadBand() {
        // Pitch -60 - 5 = -65; anything < -65 is TOO_LOW
        val justOutside = AngleDetector.PITCH_TARGET_MIN_DEG - AngleDetector.DEAD_BAND_DEG - 0.1f
        assertEquals(AngleState.TOO_LOW, AngleClassifier.classify(justOutside, 0f))
    }

    @Test fun tooLowAtNegative90Pitch() {
        // Pitch -90° — phone face pointing up, rear camera pointing at floor → TOO_LOW
        assertEquals(AngleState.TOO_LOW, AngleClassifier.classify(-90f, 0f))
    }

    @Test fun stillGoodAtExactDeadBandBoundaryLow() {
        val boundary = AngleDetector.PITCH_TARGET_MIN_DEG - AngleDetector.DEAD_BAND_DEG
        assertEquals(AngleState.GOOD, AngleClassifier.classify(boundary, 0f))
    }

    // -----------------------------------------------------------------------
    // TILTED_LEFT — roll too negative
    // -----------------------------------------------------------------------

    @Test fun tiltedLeftWhenRollExceedsNegativeThreshold() {
        val extremeLeft = -(AngleDetector.ROLL_ACCEPTABLE_DEG + AngleDetector.DEAD_BAND_DEG + 1f)
        assertEquals(AngleState.TILTED_LEFT, AngleClassifier.classify(-40f, extremeLeft))
    }

    @Test fun tiltedLeftAtExtremeRoll() {
        assertEquals(AngleState.TILTED_LEFT, AngleClassifier.classify(-40f, -90f))
    }

    @Test fun stillGoodAtExactNegativeRollDeadBand() {
        val boundary = -(AngleDetector.ROLL_ACCEPTABLE_DEG + AngleDetector.DEAD_BAND_DEG)
        assertEquals(AngleState.GOOD, AngleClassifier.classify(-40f, boundary))
    }

    // -----------------------------------------------------------------------
    // TILTED_RIGHT — roll too positive
    // -----------------------------------------------------------------------

    @Test fun tiltedRightWhenRollExceedsPositiveThreshold() {
        val extremeRight = AngleDetector.ROLL_ACCEPTABLE_DEG + AngleDetector.DEAD_BAND_DEG + 1f
        assertEquals(AngleState.TILTED_RIGHT, AngleClassifier.classify(-40f, extremeRight))
    }

    @Test fun tiltedRightAtExtremeRoll() {
        assertEquals(AngleState.TILTED_RIGHT, AngleClassifier.classify(-40f, 90f))
    }

    @Test fun stillGoodAtExactPositiveRollDeadBand() {
        val boundary = AngleDetector.ROLL_ACCEPTABLE_DEG + AngleDetector.DEAD_BAND_DEG
        assertEquals(AngleState.GOOD, AngleClassifier.classify(-40f, boundary))
    }

    // -----------------------------------------------------------------------
    // Priority: roll is evaluated before pitch
    // -----------------------------------------------------------------------

    @Test fun rollTakesPriorityOverBadPitch() {
        // Both pitch and roll are out of range — roll wins
        val badPitch = AngleDetector.PITCH_TARGET_MAX_DEG + AngleDetector.DEAD_BAND_DEG + 5f
        val badRoll  = AngleDetector.ROLL_ACCEPTABLE_DEG + AngleDetector.DEAD_BAND_DEG + 5f
        assertEquals(AngleState.TILTED_RIGHT, AngleClassifier.classify(badPitch, badRoll))
    }

    @Test fun negativeRollTakesPriorityOverBadPitch() {
        val badPitch = -(AngleDetector.PITCH_TARGET_MIN_DEG + AngleDetector.DEAD_BAND_DEG + 5f)
        val badRoll  = -(AngleDetector.ROLL_ACCEPTABLE_DEG + AngleDetector.DEAD_BAND_DEG + 5f)
        assertEquals(AngleState.TILTED_LEFT, AngleClassifier.classify(badPitch, badRoll))
    }

    // -----------------------------------------------------------------------
    // Guidance text
    // -----------------------------------------------------------------------

    @Test fun guidanceTextForAllStates() {
        assertEquals("Tilt phone down.",       AngleClassifier.guidance(AngleState.TOO_HIGH))
        assertEquals("Tilt phone upright.",    AngleClassifier.guidance(AngleState.TOO_LOW))
        assertEquals("Tilt phone right.",      AngleClassifier.guidance(AngleState.TILTED_LEFT))
        assertEquals("Tilt phone left.",       AngleClassifier.guidance(AngleState.TILTED_RIGHT))
        assertEquals("Phone angle looks good.",AngleClassifier.guidance(AngleState.GOOD))
        assertNull(AngleClassifier.guidance(AngleState.ADJUSTING))
        assertNull(AngleClassifier.guidance(AngleState.UNAVAILABLE))
    }

    // -----------------------------------------------------------------------
    // Dead band — values just inside vs. just outside the threshold
    // -----------------------------------------------------------------------

    @Test fun deadBandCreatesHysteresis_pitchHigh() {
        val threshold = AngleDetector.PITCH_TARGET_MAX_DEG
        val deadBand  = AngleDetector.DEAD_BAND_DEG
        // Inside range: GOOD
        assertEquals(AngleState.GOOD,     AngleClassifier.classify(threshold - 1f, 0f))
        // At boundary: GOOD (not strictly greater)
        assertEquals(AngleState.GOOD,     AngleClassifier.classify(threshold + deadBand, 0f))
        // Just outside: TOO_HIGH
        assertEquals(AngleState.TOO_HIGH, AngleClassifier.classify(threshold + deadBand + 0.01f, 0f))
    }

    @Test fun deadBandCreatesHysteresis_pitchLow() {
        val threshold = AngleDetector.PITCH_TARGET_MIN_DEG
        val deadBand  = AngleDetector.DEAD_BAND_DEG
        assertEquals(AngleState.GOOD,    AngleClassifier.classify(threshold + 1f, 0f))
        assertEquals(AngleState.GOOD,    AngleClassifier.classify(threshold - deadBand, 0f))
        assertEquals(AngleState.TOO_LOW, AngleClassifier.classify(threshold - deadBand - 0.01f, 0f))
    }

    @Test fun deadBandCreatesHysteresis_rollRight() {
        val threshold = AngleDetector.ROLL_ACCEPTABLE_DEG
        val deadBand  = AngleDetector.DEAD_BAND_DEG
        assertEquals(AngleState.GOOD,         AngleClassifier.classify(-40f, threshold - 1f))
        assertEquals(AngleState.GOOD,         AngleClassifier.classify(-40f, threshold + deadBand))
        assertEquals(AngleState.TILTED_RIGHT,  AngleClassifier.classify(-40f, threshold + deadBand + 0.01f))
    }
}
