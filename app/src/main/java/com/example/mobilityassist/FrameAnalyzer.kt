package com.example.mobilityassist

import java.nio.ByteBuffer

/**
 * Lightweight brightness and obstruction estimator.
 *
 * Designed to run on the CameraX worker thread, operating on raw RGBA_8888 pixel
 * data from ImageProxy.planes[0].buffer before the ImageProxy is closed.
 *
 * All Android framework dependencies are injected as plain types so the core
 * logic is fully testable under plain JVM unit tests with synthetic buffers.
 *
 * ── Luminance proxy ──────────────────────────────────────────────────────────
 * For RGBA_8888, each pixel is 4 bytes: R(0), G(1), B(2), A(3).
 * We read the green channel (byte offset 1) as a luminance proxy.
 * Green contributes ~59% of perceived luminance; using it alone introduces
 * at most ~10% error vs. the full Rec.601 formula while avoiding a floating-
 * point multiply per pixel. This is a brightness heuristic, not a colorimetric
 * measurement.
 *
 * ── Row stride ───────────────────────────────────────────────────────────────
 * The plane buffer rowStride may be larger than width * pixelStride on some
 * devices (padding bytes at end of rows). The caller must supply the actual
 * rowStride from ImageProxy.planes[0].rowStride.
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 * All mutable state is worker-thread-only. Call reset() when the camera session
 * restarts or the analyzer stops. setLightingState() and onTorchStateChanged()
 * are also called from the worker thread (via the FrameAnalysis result handler).
 */
class FrameAnalyzer {

    // -----------------------------------------------------------------------
    // Configurable thresholds — documented in PHASE_3_IMPLEMENTATION_PLAN.md
    // -----------------------------------------------------------------------
    companion object {
        /** Mean luminance (0–255) below which a frame is considered dark. */
        const val DARK_ENTER_THRESHOLD = 60

        /**
         * Mean luminance above which a frame is considered bright enough to
         * exit dark/torch mode. Higher than DARK_ENTER_THRESHOLD → hysteresis.
         */
        const val DARK_EXIT_THRESHOLD = 90

        /** Consecutive dark frames required before entering LOW_LIGHT state. */
        const val DARK_ENTER_FRAMES = 8

        /** Consecutive bright frames required before exiting LOW_LIGHT/FLASHLIGHT_ON. */
        const val DARK_EXIT_FRAMES = 6

        /**
         * Frames to skip evaluation after a torch state change.
         * Allows camera auto-exposure to settle before new brightness is evaluated.
         */
        const val EXPOSURE_SETTLE_FRAMES = 15

        /**
         * Mean luminance threshold below which a frame is a candidate for
         * obstruction — combined with variance check to avoid false positives
         * in legitimately dark (but textured) scenes.
         */
        const val OCCLUSION_DARK_THRESHOLD = 20

        /**
         * Pixel variance below which a frame is considered near-uniform.
         * A dark scene with structural content (e.g. lit window in distance)
         * will have variance > this value → not flagged as obstruction.
         */
        const val OCCLUSION_VARIANCE_THRESHOLD = 5.0f

        /** Consecutive occlusion-candidate frames to reach POSSIBLY_OBSTRUCTED. */
        const val OCCLUSION_POSSIBLE_FRAMES = 3

        /** Consecutive occlusion-candidate frames to reach OBSTRUCTED. */
        const val OCCLUSION_CONFIRMED_FRAMES = 10

        /** Consecutive clear frames required to recover from OBSTRUCTED/RECOVERING. */
        const val OCCLUSION_RECOVERY_FRAMES = 8

        /** Sample every Nth pixel per row and column. N=8 → ~1,600 samples at 640×480. */
        const val SAMPLE_STRIDE = 8
    }

    // -----------------------------------------------------------------------
    // Mutable state — worker thread only
    // -----------------------------------------------------------------------
    private var darkFrameCount = 0
    private var brightFrameCount = 0
    private var occlusionCandidateCount = 0
    private var clearFrameCount = 0
    private var settleFramesRemaining = 0

    private var currentLightingState = LightingState.NORMAL
    private var currentOcclusionState = OcclusionState.CLEAR

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Notify the analyzer that the torch state just changed.
     * Resets the exposure-settling counter so the camera auto-exposure
     * adjustment that follows a torch toggle is not misread as a brightness
     * change.
     */
    fun onTorchStateChanged() {
        settleFramesRemaining = EXPOSURE_SETTLE_FRAMES
    }

    /**
     * Reset all state. Call when the camera session restarts, the app is
     * paused/resumed, or analysis is stopped.
     */
    fun reset() {
        darkFrameCount = 0
        brightFrameCount = 0
        occlusionCandidateCount = 0
        clearFrameCount = 0
        settleFramesRemaining = 0
        currentLightingState = LightingState.NORMAL
        currentOcclusionState = OcclusionState.CLEAR
    }

    /**
     * Analyze a single camera frame.
     *
     * @param buffer     Raw pixel bytes from ImageProxy.planes[0].buffer (RGBA_8888).
     *                   Position must be at 0 (rewound) or the caller's current position.
     *                   This method does NOT modify the buffer position.
     * @param width      Frame width in pixels.
     * @param height     Frame height in pixels.
     * @param rowStride  Byte stride between rows (from ImageProxy.planes[0].rowStride).
     *                   May be > width * 4 due to row padding.
     * @param torchIsOn  Whether the flashlight is currently on. Provided for context
     *                   (dark + torch on → stronger obstruction signal) but the current
     *                   state machine uses it only for documentation; the caller uses
     *                   the returned states to drive torch control.
     * @return [FrameAnalysis] with updated states, or null if the buffer is invalid.
     */
    fun analyze(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        torchIsOn: Boolean
    ): FrameAnalysis? {
        if (width <= 0 || height <= 0 || rowStride <= 0) return null
        val capacity = buffer.limit()
        if (capacity == 0) return null

        // ── Exposure settling ──────────────────────────────────────────────
        if (settleFramesRemaining > 0) {
            settleFramesRemaining--
            // Return current state with a neutral luminance placeholder.
            // This prevents the auto-exposure transient from driving torch changes.
            return FrameAnalysis(
                meanLuminance = 128f,
                variance = 100f,
                lightingState = currentLightingState,
                occlusionState = currentOcclusionState
            )
        }

        // ── Pixel sampling ─────────────────────────────────────────────────
        // Step through rows by SAMPLE_STRIDE. Within each row, step through
        // columns by SAMPLE_STRIDE. Read the green byte (offset 1 in RGBA group).
        // rowStride accounts for any padding bytes at end of rows.
        // pixelStride for RGBA_8888 is always 4.
        val pixelStride = 4
        var sum = 0L
        var sumSq = 0L
        var count = 0

        var rowByteStart = 0
        var row = 0
        while (row < height) {
            var col = 0
            while (col < width) {
                val byteIndex = rowByteStart + col * pixelStride + 1 // green channel
                if (byteIndex < capacity) {
                    val g = (buffer.get(byteIndex).toInt() and 0xFF).toLong()
                    sum += g
                    sumSq += g * g
                    count++
                }
                col += SAMPLE_STRIDE
            }
            row += SAMPLE_STRIDE
            rowByteStart += rowStride * SAMPLE_STRIDE
        }

        if (count == 0) return null

        val mean = sum.toFloat() / count
        // Variance = E[x²] - E[x]²
        val variance = (sumSq.toFloat() / count) - (mean * mean)

        // ── Lighting state machine ─────────────────────────────────────────
        updateLightingState(mean)

        // ── Obstruction state machine ──────────────────────────────────────
        // Obstruction requires BOTH signals simultaneously:
        //   1. Very dark (mean < OCCLUSION_DARK_THRESHOLD)
        //   2. Near-uniform (variance < OCCLUSION_VARIANCE_THRESHOLD)
        // A dark-but-textured scene (night with varying lights) has low mean
        // but normal variance → NOT classified as obstruction.
        updateOcclusionState(mean, variance)

        return FrameAnalysis(
            meanLuminance = mean,
            variance = variance,
            lightingState = currentLightingState,
            occlusionState = currentOcclusionState
        )
    }

    private fun updateLightingState(mean: Float) {
        val isDark = mean < DARK_ENTER_THRESHOLD
        val isBright = mean > DARK_EXIT_THRESHOLD

        when (currentLightingState) {
            LightingState.NORMAL, LightingState.FLASHLIGHT_OFF -> {
                if (isDark) {
                    darkFrameCount++
                    brightFrameCount = 0
                    if (darkFrameCount >= DARK_ENTER_FRAMES) {
                        currentLightingState = LightingState.LOW_LIGHT
                        darkFrameCount = 0
                    }
                } else {
                    brightFrameCount++
                    darkFrameCount = 0
                }
            }
            LightingState.LOW_LIGHT, LightingState.FLASHLIGHT_ON -> {
                if (isBright) {
                    brightFrameCount++
                    darkFrameCount = 0
                    if (brightFrameCount >= DARK_EXIT_FRAMES) {
                        currentLightingState = LightingState.FLASHLIGHT_OFF
                        brightFrameCount = 0
                    }
                } else {
                    darkFrameCount++
                    brightFrameCount = 0
                }
            }
            // Hardware error or absent flash — no state transitions from analysis
            LightingState.UNAVAILABLE, LightingState.ERROR -> { /* no-op */ }
        }
    }

    private fun updateOcclusionState(mean: Float, variance: Float) {
        val isCandidate = mean < OCCLUSION_DARK_THRESHOLD && variance < OCCLUSION_VARIANCE_THRESHOLD

        when (currentOcclusionState) {
            OcclusionState.CLEAR -> {
                if (isCandidate) {
                    occlusionCandidateCount++
                    clearFrameCount = 0
                    if (occlusionCandidateCount >= OCCLUSION_POSSIBLE_FRAMES) {
                        currentOcclusionState = OcclusionState.POSSIBLY_OBSTRUCTED
                    }
                } else {
                    occlusionCandidateCount = 0
                }
            }
            OcclusionState.POSSIBLY_OBSTRUCTED -> {
                if (isCandidate) {
                    occlusionCandidateCount++
                    if (occlusionCandidateCount >= OCCLUSION_CONFIRMED_FRAMES) {
                        currentOcclusionState = OcclusionState.OBSTRUCTED
                        occlusionCandidateCount = 0
                    }
                } else {
                    // Any single clear frame resets back to CLEAR to avoid
                    // announcing an obstruction that was only a transient.
                    occlusionCandidateCount = 0
                    clearFrameCount = 0
                    currentOcclusionState = OcclusionState.CLEAR
                }
            }
            OcclusionState.OBSTRUCTED -> {
                if (!isCandidate) {
                    // Start recovery — require OCCLUSION_RECOVERY_FRAMES clear frames.
                    currentOcclusionState = OcclusionState.RECOVERING
                    clearFrameCount = 1
                    occlusionCandidateCount = 0
                }
                // If still candidate, remain OBSTRUCTED — speech gate prevents looping.
            }
            OcclusionState.RECOVERING -> {
                if (!isCandidate) {
                    clearFrameCount++
                    if (clearFrameCount >= OCCLUSION_RECOVERY_FRAMES) {
                        currentOcclusionState = OcclusionState.CLEAR
                        clearFrameCount = 0
                    }
                } else {
                    // Obstruction returned during recovery — go straight back to OBSTRUCTED.
                    currentOcclusionState = OcclusionState.OBSTRUCTED
                    clearFrameCount = 0
                    occlusionCandidateCount = 0
                }
            }
            OcclusionState.UNAVAILABLE -> { /* Camera not active */ }
        }
    }

    /**
     * Directly set the lighting state — used by MainActivity to reflect the
     * confirmed torch hardware outcome (e.g. FLASHLIGHT_ON after enableTorch
     * future completes, or ERROR on failure).
     */
    fun setLightingState(state: LightingState) {
        currentLightingState = state
        // Reset brightness counters so the new state is evaluated fresh.
        darkFrameCount = 0
        brightFrameCount = 0
    }

    fun getCurrentLightingState(): LightingState = currentLightingState
    fun getCurrentOcclusionState(): OcclusionState = currentOcclusionState
}
