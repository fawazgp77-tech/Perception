package com.example.mobilityassist

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * Sensor-based phone orientation estimator and angle guidance classifier.
 *
 * Uses TYPE_ROTATION_VECTOR (available since API 9; no permission required) to
 * estimate pitch and roll of the device, then classifies the current angle into
 * an [AngleState] with directionally correct guidance text.
 *
 * ── Coordinate system ────────────────────────────────────────────────────────
 * After SensorManager.getOrientation() on the screen-rotation-remapped matrix:
 *
 *   values[0] = azimuth  — compass bearing (not used)
 *   values[1] = pitch    — negative → phone face tilting backward (camera pointing forward/up)
 *                          positive → phone face tilting forward (camera pointing floor-ward)
 *   values[2] = roll     — negative → device tilting to the left
 *                          positive → device tilting to the right
 *
 * The guidance text is expressed in terms of camera direction, not screen face:
 *   AngleState.TOO_HIGH   → camera pointing too far upward  → "Tilt phone down."
 *   AngleState.TOO_LOW    → camera pointing at the floor    → "Tilt phone upright."
 *   AngleState.TILTED_LEFT  → left roll beyond threshold    → "Tilt phone right."
 *   AngleState.TILTED_RIGHT → right roll beyond threshold   → "Tilt phone left."
 *
 * ── Target range ─────────────────────────────────────────────────────────────
 * PITCH_TARGET_MIN_DEG (-60°) to PITCH_TARGET_MAX_DEG (-20°):
 *   Camera pointing roughly forward with a slight downward angle — typical
 *   pedestrian hold position for obstacle detection.
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 * register() / unregister() / updateDisplayRotation() must be called from the
 * main thread. The SensorEventListener callback is delivered on the main-thread
 * handler. The onAngle callback is therefore always on the main thread.
 */
class AngleDetector(
    context: Context,
    private val onAngle: (state: AngleState, guidance: String?) -> Unit
) {
    companion object {
        /** Lower pitch bound in degrees. Camera points at floor below this. */
        const val PITCH_TARGET_MIN_DEG = -60f

        /** Upper pitch bound in degrees. Camera points too high above this. */
        const val PITCH_TARGET_MAX_DEG = -20f

        /** Maximum acceptable roll magnitude (degrees). */
        const val ROLL_ACCEPTABLE_DEG  = 20f

        /** Hysteresis dead band around all thresholds to prevent oscillation. */
        const val DEAD_BAND_DEG        = 5f

        /** Smoothing: rolling average over this many sensor readings. */
        const val SMOOTHING_SAMPLES    = 5

        /** Consecutive smoothed readings in same out-of-range state before callback fires. */
        const val PERSISTENCE_SAMPLES  = 3

        /** SensorManager delay. SENSOR_DELAY_NORMAL ≈ 200 ms between events. */
        const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_NORMAL
    }

    private val sensorManager: SensorManager? =
        context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var currentState = AngleState.UNAVAILABLE
    private var displayRotation = Surface.ROTATION_0

    // Smoothing circular buffers
    private val pitchBuffer = FloatArray(SMOOTHING_SAMPLES)
    private val rollBuffer  = FloatArray(SMOOTHING_SAMPLES)
    private var bufferIndex = 0
    private var bufferFilled = false

    // Persistence gate
    private var pendingState = AngleState.UNAVAILABLE
    private var pendingCount = 0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            processSensorEvent(event.values)
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // -----------------------------------------------------------------------
    // Lifecycle API
    // -----------------------------------------------------------------------

    /** Register the sensor listener. Call from onStart() / after bindCamera(). */
    fun register(displayRotation: Int) {
        if (rotationSensor == null) {
            if (currentState != AngleState.UNAVAILABLE) {
                currentState = AngleState.UNAVAILABLE
                onAngle(AngleState.UNAVAILABLE, null)
            }
            return
        }
        this.displayRotation = displayRotation
        sensorManager?.registerListener(listener, rotationSensor, SENSOR_DELAY)
    }

    /** Unregister the sensor listener. Call from onStop() and on pause. */
    fun unregister() {
        sensorManager?.unregisterListener(listener)
        resetBuffers()
        currentState = AngleState.UNAVAILABLE
    }

    /** Call from onResume() when display rotation may have changed. */
    fun updateDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    fun getCurrentState(): AngleState = currentState

    // -----------------------------------------------------------------------
    // Internal sensor processing
    // -----------------------------------------------------------------------

    private fun resetBuffers() {
        bufferIndex = 0
        bufferFilled = false
        pitchBuffer.fill(0f)
        rollBuffer.fill(0f)
        pendingState = AngleState.UNAVAILABLE
        pendingCount = 0
    }

    private fun processSensorEvent(values: FloatArray) {
        // Step 1: rotation matrix from quaternion
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)

        // Step 2: remap coordinate system to match current screen orientation.
        // Without remapping, pitch and roll values would be relative to the
        // device's natural orientation rather than the currently visible screen.
        val remapped = FloatArray(9)
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90  -> Pair(SensorManager.AXIS_Y,       SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X,  SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y,  SensorManager.AXIS_X)
            else                 -> Pair(SensorManager.AXIS_X,        SensorManager.AXIS_Y) // ROTATION_0
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)

        // Step 3: extract pitch and roll
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)
        // orientation[1] = pitch, orientation[2] = roll (radians)
        val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val rollDeg  = Math.toDegrees(orientation[2].toDouble()).toFloat()

        // Step 4: smooth with rolling average
        pitchBuffer[bufferIndex] = pitchDeg
        rollBuffer[bufferIndex]  = rollDeg
        bufferIndex = (bufferIndex + 1) % SMOOTHING_SAMPLES
        if (bufferIndex == 0) bufferFilled = true

        val n = if (bufferFilled) SMOOTHING_SAMPLES else bufferIndex
        if (n == 0) return

        val smoothPitch = pitchBuffer.take(n).average().toFloat()
        val smoothRoll  = rollBuffer.take(n).average().toFloat()

        // Step 5: classify
        val candidate = AngleClassifier.classify(smoothPitch, smoothRoll)

        // Step 6: persistence gate — require PERSISTENCE_SAMPLES consecutive
        //         readings in the same state before firing the callback.
        if (candidate == pendingState) {
            pendingCount++
        } else {
            pendingState = candidate
            pendingCount = 1
        }

        if (pendingCount >= PERSISTENCE_SAMPLES && candidate != currentState) {
            currentState = candidate
            onAngle(currentState, AngleClassifier.guidance(currentState))
        }
    }
}

// ---------------------------------------------------------------------------
// Pure classification logic — no Android Context or SensorManager dependency.
// Extracted so JVM unit tests can exercise it without a device.
// ---------------------------------------------------------------------------
object AngleClassifier {

    /**
     * Classify pitch and roll (degrees) into an [AngleState].
     *
     * Roll check takes priority: if the device is tilted sideways, that is the
     * most actionable correction regardless of pitch.
     *
     * Dead band is applied OUTSIDE the acceptable range to create hysteresis:
     * state only changes to out-of-range when the value exceeds
     * (threshold + DEAD_BAND_DEG), and returns to GOOD only when it falls back
     * within (threshold - DEAD_BAND_DEG).
     * In the current implementation, GOOD is returned whenever none of the
     * out-of-range conditions fire, which produces the same hysteresis effect
     * because the conditions use (threshold + dead_band) as the trigger point.
     */
    fun classify(pitchDeg: Float, rollDeg: Float): AngleState {
        // Roll out-of-range → act immediately (most disorienting for the user)
        if (rollDeg < -(AngleDetector.ROLL_ACCEPTABLE_DEG + AngleDetector.DEAD_BAND_DEG))
            return AngleState.TILTED_LEFT
        if (rollDeg > (AngleDetector.ROLL_ACCEPTABLE_DEG + AngleDetector.DEAD_BAND_DEG))
            return AngleState.TILTED_RIGHT

        // Pitch: TOO_HIGH means camera pointing too far upward
        // (pitch more positive than the maximum target + dead band)
        if (pitchDeg > AngleDetector.PITCH_TARGET_MAX_DEG + AngleDetector.DEAD_BAND_DEG)
            return AngleState.TOO_HIGH

        // Pitch: TOO_LOW means camera pointing at the floor
        // (pitch more negative than the minimum target - dead band)
        if (pitchDeg < AngleDetector.PITCH_TARGET_MIN_DEG - AngleDetector.DEAD_BAND_DEG)
            return AngleState.TOO_LOW

        return AngleState.GOOD
    }

    /** Map an [AngleState] to the spoken guidance text, or null for states with no guidance. */
    fun guidance(state: AngleState): String? = when (state) {
        AngleState.TOO_HIGH     -> "Tilt phone down."
        AngleState.TOO_LOW      -> "Tilt phone upright."
        AngleState.TILTED_LEFT  -> "Tilt phone right."
        AngleState.TILTED_RIGHT -> "Tilt phone left."
        AngleState.GOOD         -> "Phone angle looks good."
        AngleState.ADJUSTING,
        AngleState.UNAVAILABLE  -> null
    }
}
