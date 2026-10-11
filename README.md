# Mobility Assist — Phase 3 demo

> **Important safety notice:** Mobility Assist is a supervised prototype aid — not a validated mobility device. It cannot detect all hazards. Always use a cane, guide dog, or human assistance. A warning-free frame does not mean the path is safe.

Phase 3 adds automatic flashlight control, phone-angle guidance, and camera-obstruction detection on top of the Phase 2 offline obstacle-detection pipeline.

See [`docs/PHASE_3_IMPLEMENTATION_PLAN.md`](docs/PHASE_3_IMPLEMENTATION_PLAN.md) for the architecture and design, [`docs/PHASE_3_CHECKLIST.md`](docs/PHASE_3_CHECKLIST.md) for the device test checklist, and [`VERIFICATION.md`](VERIFICATION.md) for the current build and test status.

Earlier phases: [`PHASE2_PLAN.md`](PHASE2_PLAN.md) · [`PHASE2_TEST_CHECKLIST.md`](PHASE2_TEST_CHECKLIST.md) · [Phase 1 source archive](releases/phase1/README.md) · [Phase 1 device checklist](DEVICE_TEST_CHECKLIST.md).

---

## Features

### Phase 1 / 2 (existing)

- Rear camera → on-device EfficientDet-Lite0 INT8 inference via MediaPipe Tasks Vision.
- Seven-class allowlist: person, car, bus, truck, bicycle, motorcycle, dog.
- Five-tier warning hierarchy: `STOP → Ahead → Both sides → Left → Right`.
- Two-frame confirmation gate; two-second speech cooldown; urgent STOP interrupts all.
- Spoken warnings (offline TTS) + distinct vibration patterns.
- Colored bounding-box overlay with LEFT / CENTER / RIGHT zone guides.
- Rolling p95 / FPS diagnostics.
- Full lifecycle safety: pause/resume, rotation, permission recovery, backpressure.

### Phase 3 (new)

#### Automatic flashlight
The rear torch is automatically activated when sustained darkness is detected (mean luminance < 60 over 8 consecutive frames) and deactivated when brightness recovers (mean > 90 over 6 frames). Hysteresis prevents flickering. An exposure-settling delay avoids reacting to the camera auto-exposure adjustment that follows a torch toggle.

Spoken feedback: "Too dark. Turning on flashlight." / "Flashlight is on." / "Lighting improved. Turning off flashlight." On devices without flash hardware the app announces "Flashlight unavailable on this device." and shows `FLASHLIGHT: N/A`.

#### Phone-angle guidance
Uses `TYPE_ROTATION_VECTOR` (no permission required) to estimate pitch and roll. Target range: pitch −60° to −20° (camera pointing usefully forward/down), roll ±20°. A 5° dead band, 5-sample rolling average, and 3-reading persistence gate prevent oscillation. Direction-specific spoken guidance: "Tilt phone upright." / "Tilt phone down." / "Tilt phone left." / "Tilt phone right." / "Phone angle looks good."

Thresholds are engineering starting values — tune on hardware before demo sign-off.

#### Camera obstruction detection
Detects when the lens is covered by analysing mean luminance and pixel variance in sampled camera frames. Obstruction requires **both** signals: very dark (mean < 20) **and** near-uniform (variance < 5). A dark-but-textured scene (e.g. night with lights) has high variance and is not flagged. After 10 consecutive candidate frames: "Camera covered. Please move your hand." Recovery to `CAMERA: CLEAR` requires 8 consecutive clear frames.

This is image-quality-based obstruction detection, not semantic hand recognition.

---

## Build and install

### Prerequisites

- Android Studio with bundled JDK 17, or a system JDK 17.
- Android SDK Platform 35 and Build Tools 35 (SDK Manager).
- Physical Android device — API 26 (Android 8.0) or newer, rear camera.
- Offline English TTS voice installed in Android Settings → General management → Language → Text-to-speech.

### Build

```powershell
.\build.ps1
```

`build.ps1` runs `testDebugUnitTest assembleDebug` by default. Pass additional tasks as arguments:

```powershell
.\build.ps1 testDebugUnitTest assembleDebug lintDebug
```

On macOS/Linux:

```bash
chmod +x gradlew && ./gradlew testDebugUnitTest assembleDebug
```

The signed debug APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Run unit tests only

```powershell
.\build.ps1 testDebugUnitTest
```

Expected: **64 tests, 0 failures** (7 existing + 57 new Phase 3 tests).  
Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`.

### Install on device

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and use Run (▶).

---

## Usage

1. Launch the app. Grant camera permission when prompted.
2. The app briefly shows a Phase 3 onboarding message.
3. Point the rear camera at the scene ahead. Hold the phone at a comfortable forward-facing angle.
4. The status row shows three indicators: **LIGHTING**, **ANGLE**, and **CAMERA**.
5. Spoken warnings are provided for obstacles, angle guidance, flashlight changes, and camera obstruction.
6. Use **Pause assistance** / **Resume assistance** to stop and restart the camera and feedback.

To use offline: install an offline English TTS voice, then enable airplane mode. The app will function without any network connection.

---

## Model

EfficientDet-Lite0 INT8 (320×320, version 1) bundled at:

```
app/src/main/assets/efficientdet_lite0.tflite
```

SHA-256: `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb`

If missing, download from the [official MediaPipe model page](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector) and place it at that exact path. Do not rename a different model — MediaPipe requires compatible output tensors and metadata.

---

## Pipeline

1. `MainActivity` binds CameraX Preview + ImageAnalysis to a shared `ViewPort`.
2. Analysis requests 640×480 RGBA frames with `STRATEGY_KEEP_ONLY_LATEST`. Old pending frames are dropped.
3. **Phase 3 new:** `FrameAnalyzer` samples the frame buffer for brightness and obstruction before inference. Mean luminance (green channel proxy) and pixel variance are computed over ~1,600 sampled pixels.
4. `ObjectDetectorHelper` converts the frame to a bitmap, crops to the viewport, rotates upright, and runs synchronous MediaPipe inference.
5. `DecisionEngine` classifies detections into warnings using area/region/overlap rules.
6. **Phase 3 new:** `AngleDetector` receives `TYPE_ROTATION_VECTOR` sensor events on the main thread and classifies pitch/roll into an `AngleState`.
7. `SpeechHapticManager` speaks obstacle warnings via `warn(Warning)` (QUEUE_FLUSH, existing gate). Phase 3 messages use the new `speak()` method with a separate `Phase3SpeechGate` — obstacle warnings are never blocked by Phase 3 speech.
8. **Phase 3 new:** `MainActivity.handleFrameAnalysis()` drives torch control via `Camera.cameraControl.enableTorch()`, obstruction speech, and the compact status row UI.

---

## Files

| File | Purpose |
|---|---|
| `AndroidManifest.xml` | Camera/vibration permissions. No INTERNET permission. |
| `app/build.gradle.kts` | AGP 8.7.3, CameraX 1.4.1, MediaPipe 0.10.21, minSdk 26 |
| `activity_main.xml` | Preview, overlay, status, Phase 3 status row, button |
| `MainActivity.kt` | Permissions, lifecycle, camera pipeline, Phase 3 orchestration |
| `ObjectDetectorHelper.kt` | Model loading and synchronous inference |
| `DecisionEngine.kt` | Proximity/region rules, `GuidanceStabilizer`, `WarningGate` |
| `SpeechHapticManager.kt` | Offline TTS, vibration, `Phase3SpeechGate`, `speak()` overload |
| `OverlayView.kt` | Bounding boxes, zone guides |
| `PipelineMetrics.kt` | Rolling p95/FPS diagnostics |
| `Phase3State.kt` | State enums: `LightingState`, `AngleState`, `OcclusionState` |
| `FrameAnalyzer.kt` | Brightness estimation + obstruction detection |
| `AngleDetector.kt` | Sensor orientation estimation + `AngleClassifier` |
| `DecisionEngineTest.kt` | 6 JVM unit tests — obstacle rules (unchanged) |
| `PipelineMetricsTest.kt` | 1 JVM unit test — diagnostics (unchanged) |
| `Phase3SpeechTest.kt` | 11 JVM unit tests — `Phase3SpeechGate` |
| `FrameAnalyzerTest.kt` | 22 JVM unit tests — brightness and obstruction state machines |
| `AngleDetectorTest.kt` | 24 JVM unit tests — `AngleClassifier` classification and guidance |

---

## Permissions

| Permission | Reason |
|---|---|
| `CAMERA` | Required for rear camera access |
| `VIBRATE` | Required for haptic feedback |

No location, network, storage, or microphone permissions are used or declared.

---

## Validation status and limitations

See [`VERIFICATION.md`](VERIFICATION.md) for the full status table.

- Phase 1: items 1–5, 7–9 passed on physical hardware. Items 6, 10–15 pending.
- Phase 2: all 10 device-checklist items pending.
- Phase 3: code complete; automated tests and physical-device validation pending.

Known limitations:
- The model does not detect stairs, drop-offs, glass, poles, puddles, or objects outside the seven-class allowlist.
- Box area is a rough proximity proxy — it is not depth or time-to-collision.
- Angle guidance is sensor-only (image-based framing deferred).
- Obstruction detection uses image quality signals, not hand recognition.
- Flashlight and angle thresholds are engineering estimates — tune on hardware before demo sign-off.
- No warning does not mean the path is clear. Always use your usual mobility aid.

Implementation references: [CameraX](https://developer.android.com/media/camera/camerax/analyze) · [MediaPipe Object Detector](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android) · [Android Sensors](https://developer.android.com/guide/topics/sensors/sensors_overview)
