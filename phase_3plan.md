# Mobility Assist — Phase 3 Implementation Plan

**Version:** 3.0-plan  
**Date:** 2026-10-11  
**Status:** Approved — proceeding to implementation

---

## 1. Objective

Deliver a stable Phase 3 implementation containing three new features on top of the existing offline v2.0-demo codebase:

1. **Automatic flashlight control in darkness** — turn the rear torch on/off based on scene brightness.
2. **Intelligent phone-angle detection** — guide the user to hold the phone at a useful angle via spoken and visual feedback.
3. **Camera obstruction/hand-cover detection** — detect when the lens is covered and warn immediately.

All three features must work **offline**, must not introduce network dependencies, and must preserve the existing obstacle-detection pipeline, TTS system, and accessibility support.

---

## 2. Architecture Audit — Existing Codebase (v2.0-demo)

### 2.1 Source Files

| File | Purpose |
|---|---|
| `MainActivity.kt` | Single Activity: CameraX binding, lifecycle, generation token, UI updates |
| `ObjectDetectorHelper.kt` | MediaPipe IMAGE-mode inference; does NOT close `ImageProxy` |
| `DecisionEngine.kt` | Pure logic: `Obstacle`, `Warning`, `GuidanceStabilizer`, `WarningGate` |
| `SpeechHapticManager.kt` | Main-thread TTS + vibration; `warn(Warning)` is the only speech entry point |
| `OverlayView.kt` | Custom `View` drawing bounding boxes and zone guides |
| `PipelineMetrics.kt` | Rolling p95/fps diagnostics |

### 2.2 Camera Pipeline

- CameraX 1.4.1, `RGBA_8888` output, 640×480 preferred, `STRATEGY_KEEP_ONLY_LATEST`.
- Single worker executor. `ImageProxy.close()` is in `finally` — always executes.
- `ObjectDetectorHelper.detect()` calls `frame.toBitmap()` but never calls `frame.close()`. The frame remains open after `detect()` returns — Phase 3 pixel sampling is valid.
- `bindToLifecycle()` return value (`Camera`) was previously discarded. Phase 3 stores it for `enableTorch()`.

### 2.3 Speech System

- `SpeechHapticManager.warn(Warning)` uses the existing `WarningGate` (2-second per-warning + global cooldown).
- `WarningGate.lastTime` is per-instance. Phase 3 uses a **separate** `Phase3SpeechGate` so Phase 3 messages never consume the obstacle gate's global quiet period.

### 2.4 Android API Facts

- minSdk 26, targetSdk 34, compileSdk 35.
- `Sensor.TYPE_ROTATION_VECTOR` available since API 9 — no permission required.
- `CameraControl.enableTorch(Boolean)` safe from main thread after `bindToLifecycle()`. Does not disrupt Preview or ImageAnalysis.
- No new Android runtime permissions are needed for any Phase 3 feature.

### 2.5 Verification Status (reconciled)

- **Phase 1** (`releases/phase1/DEVICE_TEST_CHECKLIST.md`): Items 1–5, 7–9 recorded as pass on physical hardware. Items 6, 10–15 remain Pending.
- **Phase 2** (`PHASE2_TEST_CHECKLIST.md`): All 10 items Pending — no physical-device record exists.
- `VERIFICATION.md`: Accurately states "device validation pending."

---

## 3. Confirmed Design Decisions

| Topic | Decision |
|---|---|
| Speech extension | Add `speak(text, priority, cooldownKey, cooldownMs)` overload to `SpeechHapticManager`. `warn(Warning)` unchanged. |
| Phase 3 speech gate | Separate `Phase3SpeechGate` (independent cooldown map, no global quiet period) |
| Flashlight phrase | "Too dark. Turning on flashlight." |
| Angle phrases | "Tilt phone upright." / "Tilt phone left." / "Tilt phone right." / "Tilt phone down." / "Phone angle looks good." |
| Obstruction phrase | "Camera covered. Please move your hand." / "Camera view restored." |
| Angle approach | Sensors first (TYPE_ROTATION_VECTOR). Image-framing deferred until performance is measured. |
| UI | Compact icon+text status row (3 cells: lighting, angle, camera) between `speechStatus` and `diagnostics` |

---

## 4. New Components

| Component | File | Android deps? | Unit-testable? |
|---|---|---|---|
| State enums/data | `Phase3State.kt` | No | Yes |
| Brightness + obstruction estimator | `FrameAnalyzer.kt` | No (takes `ByteBuffer`) | Yes |
| Orientation estimator | `AngleDetector.kt` | Yes (SensorManager) | Partially — `AngleClassifier` object extracted |
| Phase 3 speech gate | `Phase3SpeechGate` (inside `SpeechHapticManager.kt`) | No | Yes |

---

## 5. File Modification Table

| File | Change | Reason |
|---|---|---|
| `SpeechHapticManager.kt` | Add `SpeechPriority` enum, `Phase3SpeechGate` class, `speak()` overload, `resetPhase3Gate()` | Phase 3 throttled speech without touching obstacle path |
| `MainActivity.kt` | Store `Camera`, wire `FrameAnalyzer`, wire `AngleDetector`, drive torch, Phase 3 speech, update UI | Central orchestration |
| `activity_main.xml` | Add 3-cell status row between `speechStatus` and `diagnostics` | Phase 3 UI |
| `strings.xml` | Add Phase 3 TTS phrases, status labels, onboarding text, updated title | No hardcoded strings |
| `VERIFICATION.md` | Reconcile Phase 1/2; add Phase 3 section | Accuracy |
| `README.md` | Phase 3 features, file list, updated instructions | Accuracy |
| `Phase3State.kt` | **New** | State definitions |
| `FrameAnalyzer.kt` | **New** | Brightness + obstruction logic |
| `AngleDetector.kt` | **New** | Sensor orientation + guidance |
| `FrameAnalyzerTest.kt` | **New** | Brightness/obstruction unit tests |
| `AngleDetectorTest.kt` | **New** | Angle classification unit tests |
| `Phase3SpeechTest.kt` | **New** | Speech gate unit tests |
| `docs/PHASE_3_IMPLEMENTATION_PLAN.md` | **New** | This document |
| `docs/PHASE_3_CHECKLIST.md` | **New** | Test checklist |
| `docs/PHASE_3_TEST_REPORT.md` | **New** | Test report template |
| `phase_3plan.md` | **New** | Root copy per user request |
| `DecisionEngine.kt` | **No change** | Obstacle logic untouched |
| `ObjectDetectorHelper.kt` | **No change** | Inference untouched |
| `OverlayView.kt` | **No change** | Bounding box display untouched |
| `PipelineMetrics.kt` | **No change** | Diagnostics untouched |
| `DecisionEngineTest.kt` | **No change** | Must continue to pass |
| `PipelineMetricsTest.kt` | **No change** | Must continue to pass |

---

## 6. Feature A — Automatic Flashlight

### 6.1 Brightness Estimation

- **Input:** `ImageProxy.planes[0].buffer` (RGBA_8888, rowStride-aware).
- **Luminance proxy:** Green channel (byte offset 1 in each RGBA group). Avoids float multiply per pixel; ~10% luminance error maximum.
- **Sampling:** Every 8th pixel per row and column → ~1,600 samples from 640×480. Target < 2 ms per frame.
- **Output:** `meanLuminance` (0–255 float), `variance` (float).

### 6.2 Thresholds (configurable, must be tuned on hardware)

| Constant | Value | Meaning |
|---|---|---|
| `DARK_ENTER_THRESHOLD` | 60 | Mean luminance below which a frame is dark |
| `DARK_EXIT_THRESHOLD` | 90 | Mean luminance above which dark mode exits |
| `DARK_ENTER_FRAMES` | 8 | Consecutive dark frames to enter LOW_LIGHT |
| `DARK_EXIT_FRAMES` | 6 | Consecutive bright frames to exit LOW_LIGHT |
| `EXPOSURE_SETTLE_FRAMES` | 15 | Frames skipped after torch toggle (auto-exposure) |

### 6.3 Flashlight Control

- `camera.cameraControl.enableTorch(Boolean)` called on main thread after `bindToLifecycle()`.
- `ListenableFuture` result checked — failure sets `LightingState.ERROR` and announces "Unable to activate flashlight."
- Flash availability checked via `camera.cameraInfo.hasFlashUnit()` immediately after binding.
- Torch turned off in `onStop()`, on pause, and on `fail()`.
- `camera` reference nulled in `onStop()`.

### 6.4 Voice Feedback

| Event | Text | Priority | Cooldown |
|---|---|---|---|
| Entering dark mode (before torch) | "Too dark. Turning on flashlight." | IMPORTANT | 8 s |
| Torch confirmed on | "Flashlight is on." | IMPORTANT | 8 s |
| Torch confirmed off | "Lighting improved. Turning off flashlight." | IMPORTANT | 8 s |
| No flash hardware | "Flashlight unavailable on this device." | INFORMATIONAL | 30 s |
| enableTorch failed | "Unable to activate flashlight." | IMPORTANT | 8 s |

---

## 7. Feature B — Phone Angle Guidance

### 7.1 Sensor

- `Sensor.TYPE_ROTATION_VECTOR` (API 9+, no permission).
- `SensorManager.getRotationMatrixFromVector()` → `remapCoordinateSystem()` for display rotation → `getOrientation()`.
- Registered in `onStart()` / `bindCamera()`, unregistered in `onStop()` and on pause.

### 7.2 Coordinate System (documented)

After `getOrientation()` on the remapped matrix:
- `values[1]` = **pitch** (radians). Negative = face tilting up (camera pointing forward). Positive = face tilting down (camera pointing floor-ward).
- `values[2]` = **roll** (radians). Negative = tilt left. Positive = tilt right.

### 7.3 Target Range

| Constant | Value | Meaning |
|---|---|---|
| `PITCH_TARGET_MIN_DEG` | −60° | Minimum pitch (below = camera pointing at floor) |
| `PITCH_TARGET_MAX_DEG` | −20° | Maximum pitch (above = camera pointing too high) |
| `ROLL_ACCEPTABLE_DEG` | ±20° | Maximum acceptable roll |
| `DEAD_BAND_DEG` | 5° | Hysteresis dead band around all thresholds |
| `SMOOTHING_SAMPLES` | 5 | Rolling average samples |
| `PERSISTENCE_SAMPLES` | 3 | Consecutive readings before state change |

### 7.4 State → Guidance Mapping

| State | Camera meaning | Spoken guidance |
|---|---|---|
| `TOO_HIGH` | Camera pointing too far up | "Tilt phone down." |
| `TOO_LOW` | Camera pointing at floor | "Tilt phone upright." |
| `TILTED_LEFT` | Roll < −(20+5)° | "Tilt phone right." |
| `TILTED_RIGHT` | Roll > +(20+5)° | "Tilt phone left." |
| `GOOD` | Within target range | "Phone angle looks good." (once) |

### 7.5 `AngleClassifier` object

Pure Kotlin object extracted from `AngleDetector` for JVM unit testing without Android Context. Both `AngleDetector` instance methods and `AngleDetectorTest` delegate to/use `AngleClassifier` directly.

### 7.6 Image Framing

Deferred. Sensor-only implementation ships in Phase 3. Image-framing heuristic (upper/lower region luminance comparison) is documented as a known limitation to be revisited after performance is measured on hardware.

---

## 8. Feature C — Camera Obstruction Detection

### 8.1 Detection Signals

Obstruction requires **both** signals simultaneously:
- `meanLuminance < OCCLUSION_DARK_THRESHOLD (20)` — very dark frame
- `variance < OCCLUSION_VARIANCE_THRESHOLD (5.0)` — near-uniform frame

A dark-but-textured scene (night environment with varied luminance) has low mean but normal variance → **not classified as obstruction**.

If flashlight is ON and the image is still dark with low variance → strong obstruction indicator (torch would illuminate a real dark scene).

### 8.2 State Machine

```
CLEAR
  → POSSIBLY_OBSTRUCTED  after OCCLUSION_POSSIBLE_FRAMES (3) consecutive candidates
  → OBSTRUCTED           after OCCLUSION_CONFIRMED_FRAMES (10) total candidates

OBSTRUCTED
  → RECOVERING           on first non-candidate frame

RECOVERING
  → CLEAR                after OCCLUSION_RECOVERY_FRAMES (8) consecutive clear frames
  → OBSTRUCTED           immediately if candidate returns during recovery
```

### 8.3 Voice Feedback

| Event | Text | Priority | Cooldown |
|---|---|---|---|
| Enter OBSTRUCTED | "Camera covered. Please move your hand." | URGENT | 5 s |
| Return to CLEAR | "Camera view restored." | ROUTINE | 10 s |

---

## 9. Voice Priority Policy

| Priority | Examples | Queue mode | Gate |
|---|---|---|---|
| 0 — CRITICAL (obstacle) | "Stop. Obstacle ahead." | QUEUE_FLUSH | Existing `WarningGate` |
| 1 — URGENT (obstruction) | "Camera covered. Please move your hand." | QUEUE_FLUSH | `Phase3SpeechGate`, 5 s |
| 2 — IMPORTANT (flashlight) | "Too dark. Turning on flashlight." | QUEUE_ADD | `Phase3SpeechGate`, 8 s |
| 3 — INFORMATIONAL (angle) | "Tilt phone upright." | QUEUE_ADD | `Phase3SpeechGate`, 4 s per key |
| 4 — ROUTINE (restoration) | "Phone angle looks good." | QUEUE_ADD | `Phase3SpeechGate`, 10 s |

**Critical invariant:** Phase 3 messages go through `Phase3SpeechGate`, never the obstacle `WarningGate`. An obstacle `STOP` still uses `QUEUE_FLUSH` and interrupts any ongoing Phase 3 QUEUE_ADD speech.

---

## 10. UI Design

### Compact Status Row (inserted between `speechStatus` and `diagnostics`)

```
[ ☀ LIGHTING: NORMAL ]  [ ✓ ANGLE: GOOD ]  [ 📷 CAMERA: CLEAR ]
```

Three equal-weight `TextView` cells in a horizontal `LinearLayout`. Colors:
- Normal/good: `#80CBC4` (teal)
- Warning/degraded: `#FFE082` (amber, matches existing `speechStatus`)
- Error/blocked: `#EF9A9A` (soft red)

Each `TextView` has `contentDescription` set to the label text for TalkBack compatibility. No new image drawables required — Unicode symbols used.

---

## 11. Camera Pipeline and Performance

- `FrameAnalyzer.analyze()` executes on the worker thread **before** `detect(frame)` is called. The `frame` is still open at that point.
- Green-channel sampling of ~1,600 pixels targets < 2 ms added latency per frame.
- `AngleDetector` sensor callbacks run on the main thread (registered with main looper) — zero cost on worker thread.
- `enableTorch()` is async (CameraX dispatches via its own thread) — zero cost on worker thread.
- Performance regression target: ≤ 10% increase in p95 analysis-to-display time. Measure before and after Stage 3 on physical hardware.

---

## 12. Lifecycle Table

| Event | Flashlight | Sensors | Phase 3 speech |
|---|---|---|---|
| `onStart` + `bindCamera` | Check flash availability after bind | Register sensors | Reset Phase 3 gates |
| `onStop` | `safeTorchOff()`, null `camera` | `unregister()` | Clear state, `resetPhase3Gate()` |
| Pause button | `safeTorchOff()` | `unregister()` | Reset state |
| Resume button | Re-evaluate via `bindCamera` | `register()` | — |
| `fail()` | `safeTorchOff()` | Not applicable | — |
| Screen rotation | `updateDisplayRotation()` called in `onResume` | `remapCoordinateSystem()` uses updated rotation | — |

---

## 13. Permission Requirements

No new Android runtime permissions:
- Flashlight: via CameraX `CameraControl` over the existing camera session (not a separate `FlashlightManager` or `FLASHLIGHT` permission).
- Sensors: `SensorManager` requires no permission.
- The existing `CAMERA` and `VIBRATE` declarations in `AndroidManifest.xml` are sufficient.

---

## 14. Testing Strategy

### JVM Unit Tests (new files)

| File | Tests |
|---|---|
| `Phase3SpeechTest.kt` | `Phase3SpeechGate` cooldown, independence from `WarningGate`, reset |
| `FrameAnalyzerTest.kt` | Dark frames, bright frames, hysteresis, obstruction persistence, dark ≠ obstruction, recovery, invalid buffer, row stride padding |
| `AngleDetectorTest.kt` | All 4 out-of-range directions, GOOD state, dead band, guidance text, extreme values — all via `AngleClassifier` (no Android Context) |

### Regression

All 7 existing tests (`DecisionEngineTest` × 6, `PipelineMetricsTest` × 1) must continue to pass unchanged.

### Physical Device

Documented in `docs/PHASE_3_CHECKLIST.md`.

---

## 15. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Camera auto-exposure destabilizes brightness thresholds after torch toggle | `EXPOSURE_SETTLE_FRAMES = 15` frames skipped after any torch state change |
| `enableTorch` fails silently on some devices | `ListenableFuture` result checked; failure sets `LightingState.ERROR` |
| Rotation vector sensor absent | Fall back to UNAVAILABLE state; silence angle guidance |
| Obstruction false-positives in dark scenes | Require mean < 20 AND variance < 5 simultaneously; not just dark |
| Phase 3 speech blocking obstacle warnings | Separate `Phase3SpeechGate` instance — completely independent of obstacle `WarningGate` |
| `FrameAnalyzer` adding measurable latency | Green-channel stride-aware sampling; measure p95 before and after; sub-sample to every 3rd frame if ≥ 10% regression |

---

## 16. Acceptance Criteria

1. All three features implemented; any genuine limitation explicitly documented.
2. Flashlight responds to sustained darkness / recovers on brightness, without rapid toggling.
3. Angle guidance gives directionally correct, actionable instructions.
4. Camera obstruction produces clear spoken + visual warnings; recovery is announced once.
5. Darkness alone does not trigger obstruction warning.
6. Existing object detector and overlay continue working.
7. Critical `STOP` warnings retain priority — never delayed by Phase 3 speech.
8. No new resource leaks or lifecycle crashes.
9. Automated tests cover all new state machines.
10. All 7 existing automated tests still pass.
11. Application builds successfully.
12. Phase 3 documentation and checklists present and accurate.
13. Unverified physical-device requirements explicitly marked NOT TESTED.

---

## 17. Rollback Strategy

Phase 3 new files (`Phase3State.kt`, `FrameAnalyzer.kt`, `AngleDetector.kt`) are purely additive. If any Phase 3 feature causes instability:
1. Delete the three new source files.
2. Revert `MainActivity.kt`, `SpeechHapticManager.kt`, `activity_main.xml`, `strings.xml` to their Phase 2 state (preserved in Git).
3. `DecisionEngine.kt`, `ObjectDetectorHelper.kt`, `OverlayView.kt`, `PipelineMetrics.kt` are untouched throughout Phase 3.

---

## 18. Implementation Milestones

| Milestone | Content |
|---|---|
| M3.1 — Shared Architecture | `Phase3State.kt`, `SpeechHapticManager` extension, `Phase3SpeechTest` |
| M3.2 — Flashlight | `FrameAnalyzer.kt` (brightness), `FrameAnalyzerTest`, `MainActivity` torch wiring |
| M3.3 — Angle | `AngleDetector.kt`, `AngleDetectorTest`, `MainActivity` sensor wiring |
| M3.4 — Obstruction | `FrameAnalyzer.kt` (obstruction), `FrameAnalyzerTest` extension, `MainActivity` obstruction events |
| M3.5 — UI | `activity_main.xml` + `strings.xml` Phase 3 status row |
| M3.6 — Integration + Build | All tests pass, APK generated, performance measured |
| M3.7 — Documentation | Checklist, test report, updated VERIFICATION.md and README.md |

**Code-complete ≠ demo-validated.** Final supervised demo approval requires physical-device testing and a dated test record.
