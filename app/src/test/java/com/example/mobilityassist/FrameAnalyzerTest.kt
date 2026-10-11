package com.example.mobilityassist

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class FrameAnalyzerTest {

    private lateinit var analyzer: FrameAnalyzer

    @Before fun setup() { analyzer = FrameAnalyzer() }

    // -----------------------------------------------------------------------
    // Buffer helpers
    // -----------------------------------------------------------------------

    /**
     * Create a flat RGBA_8888 ByteBuffer where every pixel has the given green value.
     * rowStride == width * 4 (no padding) unless overridden.
     */
    private fun flatBuffer(
        width: Int, height: Int, greenValue: Int, rowStride: Int = width * 4
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(rowStride * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val base = row * rowStride + col * 4
                buf.put(base, 100.toByte())               // R
                buf.put(base + 1, greenValue.toByte())    // G — luminance proxy
                buf.put(base + 2, 50.toByte())            // B
                buf.put(base + 3, 255.toByte())           // A
            }
        }
        buf.rewind()
        return buf
    }

    /**
     * Create a buffer where G values vary based on (row/STRIDE + col/STRIDE) % 2.
     * This ensures variance is visible even under stride-8 sampling, because
     * sampled pixels at multiples of SAMPLE_STRIDE see alternating lo/hi values.
     * e.g. sample at row=0,col=0 → (0/8 + 0/8)%2=0 → loGreen
     *      sample at row=0,col=8 → (0/8 + 1)%2=1   → hiGreen
     *      sample at row=8,col=0 → (1 + 0/8)%2=1   → hiGreen
     */
    private fun alternatingBuffer(
        width: Int, height: Int, loGreen: Int, hiGreen: Int, rowStride: Int = width * 4
    ): ByteBuffer {
        val stride = FrameAnalyzer.SAMPLE_STRIDE
        val buf = ByteBuffer.allocate(rowStride * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val base = row * rowStride + col * 4
                // Use sample-grid coordinates so stride-8 sampler sees alternation
                val g = if (((row / stride) + (col / stride)) % 2 == 0) loGreen else hiGreen
                buf.put(base, 5.toByte())
                buf.put(base + 1, g.toByte())
                buf.put(base + 2, 5.toByte())
                buf.put(base + 3, 255.toByte())
            }
        }
        buf.rewind()
        return buf
    }

    private fun analyze(buf: ByteBuffer, torch: Boolean = false, w: Int = 64, h: Int = 48): FrameAnalysis? {
        buf.rewind()
        return analyzer.analyze(buf, w, h, w * 4, torch)
    }

    private fun coveredCorner(width: Int = 64, height: Int = 64, tileRows: Int = 2,
                              tileCols: Int = 2): ByteBuffer {
        val buf = alternatingBuffer(width, height, 40, 200)
        for (row in 0 until height * tileRows / FrameAnalyzer.TILE_GRID) {
            for (col in 0 until width * tileCols / FrameAnalyzer.TILE_GRID) {
                buf.put(row * width * 4 + col * 4 + 1, 240.toByte())
            }
        }
        return buf
    }

    @Test fun brightFullLensCoverIsDetectedAfterTexturedBaseline() {
        analyze(alternatingBuffer(64, 64, 40, 200), w = 64, h = 64)
        val cover = flatBuffer(64, 64, 240)
        var result: FrameAnalysis? = null
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES) {
            result = analyze(cover, w = 64, h = 64)
        }
        assertEquals(OcclusionState.OBSTRUCTED, result!!.occlusionState)
    }

    @Test fun sustainedBrightPartialCornerCoverIsDetected() {
        analyze(alternatingBuffer(64, 64, 40, 200), w = 64, h = 64)
        val cover = coveredCorner()
        var result: FrameAnalysis? = null
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES) {
            result = analyze(cover, w = 64, h = 64)
        }
        assertEquals(OcclusionState.OBSTRUCTED, result!!.occlusionState)
        val clear = alternatingBuffer(64, 64, 40, 200)
        repeat(FrameAnalyzer.OCCLUSION_RECOVERY_FRAMES) {
            result = analyze(clear, w = 64, h = 64)
        }
        assertEquals(OcclusionState.CLEAR, result!!.occlusionState)
    }

    @Test fun oneSmallUniformTileDoesNotClaimCameraIsBlocked() {
        analyze(alternatingBuffer(64, 64, 40, 200), w = 64, h = 64)
        val cover = coveredCorner(tileRows = 1, tileCols = 1)
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES + 2) {
            assertEquals(OcclusionState.CLEAR, analyze(cover, w = 64, h = 64)!!.occlusionState)
        }
    }

    // -----------------------------------------------------------------------
    // Lighting state machine
    // -----------------------------------------------------------------------

    @Test fun singleDarkFrameDoesNotActivateFlashlight() {
        val buf = flatBuffer(64, 48, FrameAnalyzer.DARK_ENTER_THRESHOLD - 10)
        val result = analyze(buf)
        assertNotNull(result)
        assertEquals(LightingState.NORMAL, result!!.lightingState)
    }

    @Test fun persistentDarkFramesEnterLowLightState() {
        val buf = flatBuffer(64, 48, FrameAnalyzer.DARK_ENTER_THRESHOLD - 10)
        var result: FrameAnalysis? = null
        repeat(FrameAnalyzer.DARK_ENTER_FRAMES) { result = analyze(buf) }
        assertEquals(LightingState.LOW_LIGHT, result!!.lightingState)
    }

    @Test fun oneDarkFrameShortOfThresholdDoesNotTransition() {
        val buf = flatBuffer(64, 48, FrameAnalyzer.DARK_ENTER_THRESHOLD - 10)
        var result: FrameAnalysis? = null
        repeat(FrameAnalyzer.DARK_ENTER_FRAMES - 1) { result = analyze(buf) }
        assertEquals(LightingState.NORMAL, result!!.lightingState)
    }

    @Test fun singleBrightFrameDoesNotExitLowLight() {
        // Enter LOW_LIGHT first
        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.DARK_ENTER_THRESHOLD - 10)
        repeat(FrameAnalyzer.DARK_ENTER_FRAMES) { analyze(darkBuf) }

        // One bright frame must not exit
        val brightBuf = flatBuffer(64, 48, FrameAnalyzer.DARK_EXIT_THRESHOLD + 20)
        val result = analyze(brightBuf, torch = true)
        // Still in a non-NORMAL lighting state
        assertNotEquals(LightingState.NORMAL, result!!.lightingState)
        assertNotEquals(LightingState.FLASHLIGHT_OFF, result.lightingState)
    }

    @Test fun persistentBrightFramesExitToFlashlightOff() {
        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.DARK_ENTER_THRESHOLD - 10)
        repeat(FrameAnalyzer.DARK_ENTER_FRAMES) { analyze(darkBuf) }

        val brightBuf = flatBuffer(64, 48, FrameAnalyzer.DARK_EXIT_THRESHOLD + 20)
        var result: FrameAnalysis? = null
        repeat(FrameAnalyzer.DARK_EXIT_FRAMES) { result = analyze(brightBuf, torch = true) }
        assertEquals(LightingState.FLASHLIGHT_OFF, result!!.lightingState)
    }

    @Test fun brightSceneStaysNormalThroughManyFrames() {
        val buf = flatBuffer(64, 48, 180)
        var result: FrameAnalysis? = null
        repeat(30) { result = analyze(buf) }
        assertEquals(LightingState.NORMAL, result!!.lightingState)
    }

    @Test fun resetClearsLightingState() {
        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.DARK_ENTER_THRESHOLD - 10)
        repeat(FrameAnalyzer.DARK_ENTER_FRAMES) { analyze(darkBuf) }
        assertEquals(LightingState.LOW_LIGHT, analyzer.getCurrentLightingState())

        analyzer.reset()
        assertEquals(LightingState.NORMAL, analyzer.getCurrentLightingState())
    }

    @Test fun exposureSettlingSkipsEvaluation() {
        analyzer.onTorchStateChanged() // starts settle counter

        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.DARK_ENTER_THRESHOLD - 10)
        // Submit EXPOSURE_SETTLE_FRAMES - 1 dark frames; all should be skipped
        repeat(FrameAnalyzer.EXPOSURE_SETTLE_FRAMES - 1) { analyze(darkBuf, torch = true) }

        // State must not have changed — settling in progress
        assertEquals(LightingState.NORMAL, analyzer.getCurrentLightingState())
    }

    @Test fun exposureSettlingEndsAndEvaluationResumes() {
        analyzer.onTorchStateChanged()

        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.DARK_ENTER_THRESHOLD - 10)
        // Consume settle frames
        repeat(FrameAnalyzer.EXPOSURE_SETTLE_FRAMES) { analyze(darkBuf, torch = true) }
        // Now submit enough dark frames to trigger state change
        repeat(FrameAnalyzer.DARK_ENTER_FRAMES) { analyze(darkBuf, torch = true) }
        assertEquals(LightingState.LOW_LIGHT, analyzer.getCurrentLightingState())
    }

    @Test fun setLightingStateDirectlyAndCountersReset() {
        analyzer.setLightingState(LightingState.FLASHLIGHT_ON)
        assertEquals(LightingState.FLASHLIGHT_ON, analyzer.getCurrentLightingState())

        // After setLightingState, brightness counters are reset —
        // a single bright frame won't immediately exit the state
        val brightBuf = flatBuffer(64, 48, FrameAnalyzer.DARK_EXIT_THRESHOLD + 20)
        val result = analyze(brightBuf, torch = true)
        // FLASHLIGHT_ON + one bright frame → still in FLASHLIGHT_ON (needs DARK_EXIT_FRAMES)
        assertEquals(LightingState.FLASHLIGHT_ON, result!!.lightingState)
    }

    // -----------------------------------------------------------------------
    // Obstruction state machine
    // -----------------------------------------------------------------------

    @Test fun clearImageDoesNotTriggerObstruction() {
        // Bright, varied image — clearly not obstructed
        val buf = alternatingBuffer(64, 48, 100, 200)
        repeat(30) { analyze(buf) }
        assertEquals(OcclusionState.CLEAR, analyzer.getCurrentOcclusionState())
    }

    @Test fun persistentDarkLowVarianceReachesPossiblyObstructed() {
        val buf = flatBuffer(64, 48, FrameAnalyzer.OCCLUSION_DARK_THRESHOLD - 5)
        var result: FrameAnalysis? = null
        repeat(FrameAnalyzer.OCCLUSION_POSSIBLE_FRAMES) { result = analyze(buf) }
        assertEquals(OcclusionState.POSSIBLY_OBSTRUCTED, result!!.occlusionState)
    }

    @Test fun persistentDarkLowVarianceReachesObstructed() {
        val buf = flatBuffer(64, 48, FrameAnalyzer.OCCLUSION_DARK_THRESHOLD - 5)
        var result: FrameAnalysis? = null
        // Need OCCLUSION_CONFIRMED_FRAMES total candidates
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES + 2) { result = analyze(buf) }
        assertEquals(OcclusionState.OBSTRUCTED, result!!.occlusionState)
    }

    @Test fun singleOcclusionFrameDoesNotTrigger() {
        val buf = flatBuffer(64, 48, FrameAnalyzer.OCCLUSION_DARK_THRESHOLD - 5)
        val result = analyze(buf)
        assertNotEquals(OcclusionState.OBSTRUCTED, result!!.occlusionState)
        assertNotEquals(OcclusionState.POSSIBLY_OBSTRUCTED, result.occlusionState)
    }

    @Test fun darkButTexturedSceneDoesNotTriggerObstruction() {
        // Low mean (dark), but stride-8-sampler-visible alternation → variance well above threshold.
        // alternatingBuffer() uses sample-grid coordinates so the stride-8 sampler sees both lo and hi.
        // loGreen=0, hiGreen=30 → mean ≈ 15 (< OCCLUSION_DARK_THRESHOLD=20),
        // variance ≈ (0-15)²*0.5 + (30-15)²*0.5 = 225 >> OCCLUSION_VARIANCE_THRESHOLD=5
        val buf = alternatingBuffer(64, 48, loGreen = 0, hiGreen = 30)
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES + 5) { analyze(buf) }
        assertNotEquals(OcclusionState.OBSTRUCTED, analyzer.getCurrentOcclusionState())
    }

    @Test fun darknessAloneIsNotObstruction() {
        // Dark mean but with stride-8-sampler-visible variance — just a dark environment.
        // alternatingBuffer with loGreen=5, hiGreen=35:
        // mean ≈ 20 (borderline), but variance ≈ (5-20)²*0.5 + (35-20)²*0.5 = 225
        // which is >> OCCLUSION_VARIANCE_THRESHOLD=5, so obstruction must NOT trigger.
        val buf = alternatingBuffer(64, 48, loGreen = 5, hiGreen = 35)
        repeat(30) { analyze(buf) }
        assertNotEquals(OcclusionState.OBSTRUCTED, analyzer.getCurrentOcclusionState())
    }

    @Test fun singleClearFrameDuringPossiblyObstructedResetsToClean() {
        // Reach POSSIBLY_OBSTRUCTED
        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.OCCLUSION_DARK_THRESHOLD - 5)
        repeat(FrameAnalyzer.OCCLUSION_POSSIBLE_FRAMES) { analyze(darkBuf) }
        assertEquals(OcclusionState.POSSIBLY_OBSTRUCTED, analyzer.getCurrentOcclusionState())

        // One clear frame resets back to CLEAR
        val clearBuf = alternatingBuffer(64, 48, 80, 160)
        analyze(clearBuf)
        assertEquals(OcclusionState.CLEAR, analyzer.getCurrentOcclusionState())
    }

    @Test fun recoveryFromObstruction() {
        // Reach OBSTRUCTED
        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.OCCLUSION_DARK_THRESHOLD - 5)
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES + 2) { analyze(darkBuf) }
        assertEquals(OcclusionState.OBSTRUCTED, analyzer.getCurrentOcclusionState())

        // Submit clear frames — should reach CLEAR after OCCLUSION_RECOVERY_FRAMES
        val clearBuf = alternatingBuffer(64, 48, 80, 160)
        var result: FrameAnalysis? = null
        repeat(FrameAnalyzer.OCCLUSION_RECOVERY_FRAMES + 2) { result = analyze(clearBuf) }
        assertEquals(OcclusionState.CLEAR, result!!.occlusionState)
    }

    @Test fun obstructionReturnsDuringRecoveryGoesBackToObstructed() {
        // Reach OBSTRUCTED → start recovery → obstruction returns
        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.OCCLUSION_DARK_THRESHOLD - 5)
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES + 2) { analyze(darkBuf) }
        assertEquals(OcclusionState.OBSTRUCTED, analyzer.getCurrentOcclusionState())

        // Begin recovery with clear frames (but not enough to complete)
        val clearBuf = alternatingBuffer(64, 48, 80, 160)
        repeat(FrameAnalyzer.OCCLUSION_RECOVERY_FRAMES / 2) { analyze(clearBuf) }
        assertEquals(OcclusionState.RECOVERING, analyzer.getCurrentOcclusionState())

        // Obstruction returns → immediately back to OBSTRUCTED
        analyze(darkBuf)
        assertEquals(OcclusionState.OBSTRUCTED, analyzer.getCurrentOcclusionState())
    }

    @Test fun recoveryRequiresEnoughClearFrames() {
        // Reach OBSTRUCTED
        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.OCCLUSION_DARK_THRESHOLD - 5)
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES + 2) { analyze(darkBuf) }

        // Only one frame short of recovery count — should still be RECOVERING
        val clearBuf = alternatingBuffer(64, 48, 80, 160)
        repeat(FrameAnalyzer.OCCLUSION_RECOVERY_FRAMES - 1) { analyze(clearBuf) }
        assertEquals(OcclusionState.RECOVERING, analyzer.getCurrentOcclusionState())
    }

    @Test fun resetClearsOcclusionState() {
        val darkBuf = flatBuffer(64, 48, FrameAnalyzer.OCCLUSION_DARK_THRESHOLD - 5)
        repeat(FrameAnalyzer.OCCLUSION_CONFIRMED_FRAMES + 2) { analyze(darkBuf) }
        assertEquals(OcclusionState.OBSTRUCTED, analyzer.getCurrentOcclusionState())

        analyzer.reset()
        assertEquals(OcclusionState.CLEAR, analyzer.getCurrentOcclusionState())
    }

    // -----------------------------------------------------------------------
    // Buffer and dimension validation
    // -----------------------------------------------------------------------

    @Test fun invalidWidthReturnsNull() {
        val buf = flatBuffer(64, 48, 100)
        assertNull(analyzer.analyze(buf, 0, 48, 64 * 4, false))
    }

    @Test fun invalidHeightReturnsNull() {
        val buf = flatBuffer(64, 48, 100)
        assertNull(analyzer.analyze(buf, 64, 0, 64 * 4, false))
    }

    @Test fun invalidRowStrideReturnsNull() {
        val buf = flatBuffer(64, 48, 100)
        assertNull(analyzer.analyze(buf, 64, 48, 0, false))
    }

    @Test fun emptyBufferReturnsNull() {
        val empty = ByteBuffer.allocate(0)
        assertNull(analyzer.analyze(empty, 64, 48, 64 * 4, false))
    }

    @Test fun rowStrideWithPaddingHandledCorrectly() {
        // rowStride > width * 4 (16 bytes of row padding)
        val width = 32; val height = 24; val padding = 16
        val rowStride = width * 4 + padding
        val buf = ByteBuffer.allocate(rowStride * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val base = row * rowStride + col * 4
                buf.put(base, 100.toByte())
                buf.put(base + 1, 150.toByte()) // G = 150 — bright
                buf.put(base + 2, 50.toByte())
                buf.put(base + 3, 255.toByte())
            }
            // padding bytes remain 0 — must not be counted as real pixels
        }
        buf.rewind()
        val result = analyzer.analyze(buf, width, height, rowStride, false)
        assertNotNull(result)
        // Mean should be near 150 — well above dark threshold
        assertTrue("Expected mean > ${FrameAnalyzer.DARK_EXIT_THRESHOLD}, got ${result!!.meanLuminance}",
            result.meanLuminance > FrameAnalyzer.DARK_EXIT_THRESHOLD)
    }

    @Test fun meanLuminanceMatchesExpectedGreenValue() {
        val greenValue = 120
        val buf = flatBuffer(64, 48, greenValue)
        val result = analyze(buf)
        assertNotNull(result)
        // Mean should be very close to greenValue (within rounding of integer math)
        assertEquals(greenValue.toFloat(), result!!.meanLuminance, 1.0f)
    }
}
