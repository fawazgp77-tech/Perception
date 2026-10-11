# Mobility Assist — path and depth prototype

> **Important safety notice:** Mobility Assist is a supervised prototype aid — not a validated mobility device. It cannot detect all hazards. Always use a cane, guide dog, or human assistance. A warning-free frame does not mean the path is safe.

The current build adds on-device walkable-surface segmentation and an optional ARCore Depth scanner to the Phase 4 app. The regular CameraX scanner works without ARCore. Camera analysis remains on-device; Maps and speech recognition may need internet access. The Maps route runs in Google Maps while camera assistance is paused.

See [`docs/PHASE_4_AUDIT.md`](docs/PHASE_4_AUDIT.md) for the code audit, implementation limits, and phone test plan.

See [`docs/PHASE_3_IMPLEMENTATION_PLAN.md`](docs/PHASE_3_IMPLEMENTATION_PLAN.md) for the architecture and design, [`docs/PHASE_3_CHECKLIST.md`](docs/PHASE_3_CHECKLIST.md) for the device test checklist, and [`VERIFICATION.md`](VERIFICATION.md) for the current build and test status.

Earlier phases: [`PHASE2_PLAN.md`](PHASE2_PLAN.md) · [`PHASE2_TEST_CHECKLIST.md`](PHASE2_TEST_CHECKLIST.md) · [Phase 1 source archive](releases/phase1/README.md) · [Phase 1 device checklist](DEVICE_TEST_CHECKLIST.md).

---

## Features

### Phase 1 / 2 (existing)

- Rear camera → on-device EfficientDet-Lite0 INT8 inference via MediaPipe Tasks Vision.
- Original Phase 2 seven-class allowlist and five-tier guidance are retained in the Phase 2 archive; the current build uses every class trained into EfficientDet.
- Two-frame confirmation gate; longer warning cooldowns; urgent STOP interrupts lower-priority speech.
- Spoken warnings (offline TTS) + distinct vibration patterns.
- Colored bounding-box overlay with LEFT / CENTER / RIGHT zone guides.
- Rolling p95 / FPS diagnostics.
- Full lifecycle safety: pause/resume, rotation, permission recovery, backpressure.

### Phase 3 (new)

#### Automatic flashlight
The rear torch is automatically activated when sustained darkness is detected (mean luminance < 60 over 8 consecutive frames) and deactivated when brightness recovers (mean > 90 over 6 frames). Hysteresis prevents flickering. An exposure-settling delay avoids reacting to the camera auto-exposure adjustment that follows a torch toggle.

Spoken feedback: "Too dark. Turning on flashlight." / "Flashlight is on." / "Lighting improved. Turning off flashlight." On devices without flash hardware the app announces "Flashlight unavailable on this device." and shows `FLASHLIGHT: N/A`.

#### Phone-angle guidance
Uses `TYPE_ROTATION_VECTOR` (no permission required) to estimate pitch and roll. Current tolerant range: pitch −75° to −10°, roll ±35°, with an 8° dead band and 6-reading persistence gate. Direction-specific guidance remains available, but "phone angle looks good" is no longer spoken. Angle reminders share a 20-second cooldown.

Thresholds are engineering starting values — tune on hardware before demo sign-off.

#### Camera obstruction detection
Detects a dark full-lens cover or a sustained loss of texture and brightness change across image tiles. This can flag bright and partial covers after an unobstructed baseline has been observed. Recovery to `CAMERA: CLEAR` requires 8 consecutive clear frames. It remains a heuristic and requires phone testing for false positives.

### Phase 4 additions

- The start menu opens camera assistance, controls spoken guidance (enabled by default), accepts a typed or spoken destination, and saves a Home address locally.
- The walking-route button reads back the destination and opens a Google Maps **walking preview**. Google Maps displays travel time and requests the final Start action. This app does not calculate an ETA or speak turns itself.
- The bundled EfficientDet model now reports all of its trained COCO classes instead of only seven. This expands coverage but cannot detect every possible obstacle.
- The earlier ML Kit Wall image labeler has been replaced by pixel-level segmentation. It does not provide distance or collision certainty.
- Nonurgent obstacle speech has a 6-second cooldown; STOP has a 4-second cooldown; tentative wall reminders have a 15-second cooldown. Camera and angle messages yield to recent obstacle warnings.

This is image-quality-based obstruction detection, not semantic hand recognition.

### Path and depth prototype

- A bundled 2.3 MB quantized DeepLabV3+ ADE20K model marks floor, sidewalk, and wall pixels about once per second. The cyan overlay shows **classified walkable surface**, not a certified safe route.
- A broad wall across the center must persist across two segmentation results before the app says “Path ahead may be blocked.” It does not warn merely because an image looks plain or has no visible path; blur, darkness, sky, and a covered lens are ambiguous.
- **Try depth-enhanced assistance** opens an optional ARCore camera mode. On phones with ARCore Depth support, it combines the same object detector and segmentation model with center-view depth evidence. If Depth is unsupported or unavailable, it returns to the regular camera menu.
- ARCore requires Google Play Services for AR and may prompt to install or update it. The regular scanner has no ARCore requirement. Depth may be unavailable temporarily when tracking, lighting, or phone motion is insufficient.
- Both modes use the same quiet warning gate. Depth and segmentation are experimental; test with a sighted helper before using them while walking.

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

Expected: **84 tests, 0 failures** (including path and depth policy tests).
Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`.

### Install on device

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and use Run (▶).

---

## Usage

1. Launch the app. Spoken camera guidance is enabled by default in the start menu.
2. Tap **Start camera assistance** and grant camera permission. Hold the rear camera toward the scene ahead. A cyan mask shows classified floor or sidewalk; “Path ahead may be blocked” is deliberately tentative.
3. On a compatible phone, tap **Try depth-enhanced assistance** from the menu to test ARCore Depth. It uses a separate camera screen; Back returns to the menu. On an unsupported phone, use regular camera assistance.
4. The regular scanner's status row shows **LIGHTING**, **ANGLE**, and **CAMERA**. Use **Pause assistance** / **Resume assistance** or **Back to menu** as needed.
5. For a walking route, type or speak a destination, review its readback, then open Google Maps. Check the destination and ETA in Maps before tapping its **Start** button.
6. Enter an address and tap **Save entered address as Home** to use the Home route button later.

For offline camera assistance, install an offline English TTS voice. Google Maps routing and some installed speech-recognition services require connectivity; typed destination entry remains available.

---

## Model

EfficientDet-Lite0 INT8 (320×320, version 1) bundled at:

```
app/src/main/assets/efficientdet_lite0.tflite
```

SHA-256: `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb`

If missing, download from the [official MediaPipe model page](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector) and place it at that exact path. Do not rename a different model — MediaPipe requires compatible output tensors and metadata.

The bundled path model is `app/src/main/assets/path_segmentation_ade20k.tflite` (SHA-256 `A4169300913D0B5D96001104D4E2CD6F3CE76A3E386D2CACB3DB9BF11FD264DB`). It is the quantized output-stride-16 model in [MediaTek's Zenodo record](https://zenodo.org/records/3932677), licensed Apache-2.0. See [`docs/PATH_DEPTH_TEST_PLAN.md`](docs/PATH_DEPTH_TEST_PLAN.md) for model provenance, limitations, and phone tests.

---

## Pipeline

1. `MainActivity` binds CameraX Preview + ImageAnalysis to a shared `ViewPort`.
2. Analysis requests 640×480 RGBA frames with `STRATEGY_KEEP_ONLY_LATEST`. Old pending frames are dropped.
3. **Phase 3 new:** `FrameAnalyzer` samples the frame buffer for brightness and obstruction before inference. Mean luminance (green channel proxy) and pixel variance are computed over ~1,600 sampled pixels.
4. `ObjectDetectorHelper` converts the frame to a bitmap, crops to the viewport, rotates upright, and runs synchronous MediaPipe inference.
5. `DecisionEngine` classifies detections into warnings using area/region/overlap rules. `PathSegmenter` samples the visible crop for a floor/sidewalk/wall mask; `PathPolicy` checks the center and applies temporal confirmation.
6. **Phase 3 new:** `AngleDetector` receives `TYPE_ROTATION_VECTOR` sensor events on the main thread and classifies pitch/roll into an `AngleState`.
7. `SpeechHapticManager` speaks obstacle warnings via `warn(Warning)` (QUEUE_FLUSH, existing gate). Phase 3 messages use the new `speak()` method with a separate `Phase3SpeechGate` — obstacle warnings are never blocked by Phase 3 speech.
8. **Phase 3 new:** `MainActivity.handleFrameAnalysis()` drives torch control via `Camera.cameraControl.enableTorch()`, obstruction speech, and the compact status row UI.
9. Optional `ArCoreDepthActivity` owns its own ARCore camera session, samples depth in two center bands, and combines it with path and object results. It does not run simultaneously with CameraX.

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
| `PathSegmentation.kt` | Quantized semantic model and center-path policy |
| `ArCoreDepthActivity.kt` / `DepthPolicy.kt` | Optional ARCore camera and depth evidence |
| `WalkingRouteUrl.kt` | Encoded Google Maps walking-preview URL |
| `DecisionEngineTest.kt` | JVM unit tests — obstacle rules and warning cooldown |
| `PipelineMetricsTest.kt` | 1 JVM unit test — diagnostics (unchanged) |
| `Phase3SpeechTest.kt` | 11 JVM unit tests — `Phase3SpeechGate` |
| `FrameAnalyzerTest.kt` | JVM tests — brightness and obstruction state machines |
| `AngleDetectorTest.kt` | JVM tests — angle classification and guidance |

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
- Phase 4: automated checks pass; physical-device validation pending. See the [Phase 4 audit](docs/PHASE_4_AUDIT.md).

Known limitations:
- The models do not guarantee detection of stairs, drop-offs, glass, poles, puddles, or arbitrary unknown obstacles. A wall mask is tentative scene classification; ARCore depth is optional and can be unavailable or wrong.
- Box area is a rough proximity proxy — it is not depth or time-to-collision.
- Angle guidance is sensor-only (image-based framing deferred).
- Obstruction detection uses image quality signals, not hand recognition.
- Flashlight and angle thresholds are engineering estimates — tune on hardware before demo sign-off.
- No warning does not mean the path is clear. Always use your usual mobility aid.

Implementation references: [CameraX](https://developer.android.com/media/camera/camerax/analyze) · [MediaPipe Object Detector](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android) · [Android Sensors](https://developer.android.com/guide/topics/sensors/sensors_overview)
