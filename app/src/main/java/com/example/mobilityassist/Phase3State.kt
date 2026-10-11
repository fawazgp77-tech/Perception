package com.example.mobilityassist

// ---------------------------------------------------------------------------
// Lighting / flashlight states
// ---------------------------------------------------------------------------
enum class LightingState {
    NORMAL,         // Sufficient ambient light; flashlight off
    LOW_LIGHT,      // Darkness detected; flashlight activating
    FLASHLIGHT_ON,  // Flashlight confirmed on
    FLASHLIGHT_OFF, // Flashlight confirmed off (was on, now sufficient light)
    UNAVAILABLE,    // Device has no flash hardware
    ERROR           // enableTorch() call failed
}

// ---------------------------------------------------------------------------
// Phone angle states
// ---------------------------------------------------------------------------
enum class AngleState {
    GOOD,           // Within target pitch and roll range
    TOO_HIGH,       // Pitch too high — camera pointing too far upward
    TOO_LOW,        // Pitch too low — camera pointing at floor
    TILTED_LEFT,    // Roll negative beyond threshold
    TILTED_RIGHT,   // Roll positive beyond threshold
    ADJUSTING,      // Transitioning — sensor data unstable
    UNAVAILABLE     // Sensor absent or unregistered
}

// ---------------------------------------------------------------------------
// Camera obstruction states
// ---------------------------------------------------------------------------
enum class OcclusionState {
    CLEAR,                // Image quality normal
    POSSIBLY_OBSTRUCTED,  // 3–9 consecutive dark+low-variance frames
    OBSTRUCTED,           // ≥10 consecutive dark+low-variance frames
    RECOVERING,           // Returning to clear; requires 8 successive clean frames
    UNAVAILABLE           // Camera not active
}

// ---------------------------------------------------------------------------
// Aggregated Phase 3 UI snapshot — passed to main thread for display
// ---------------------------------------------------------------------------
data class Phase3UiState(
    val lighting: LightingState = LightingState.NORMAL,
    val angle: AngleState = AngleState.UNAVAILABLE,
    val occlusion: OcclusionState = OcclusionState.UNAVAILABLE
)

// ---------------------------------------------------------------------------
// Events emitted by FrameAnalyzer (worker thread → main thread via runOnUiThread)
// ---------------------------------------------------------------------------
data class FrameAnalysis(
    val meanLuminance: Float,     // 0–255 estimated mean luminance (green channel proxy)
    val variance: Float,          // Pixel variance of sampled green values
    val lightingState: LightingState,
    val occlusionState: OcclusionState,
    val exposureSettling: Boolean = false
)
