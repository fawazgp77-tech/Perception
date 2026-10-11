# Mobility Assist — Phase 3 Test Checklist

**Version:** 3.0  
**Date created:** 2026-10-11  
**App version:** 3.0-demo (versionCode 3)

Result conventions:
- `PASS` — tested and passed with evidence.
- `FAIL` — tested and failed (link to defect).
- `BLOCKED` — cannot be completed due to a documented dependency.
- `NOT TESTED` — no valid test has been performed yet.
- `NOT APPLICABLE` — genuinely not applicable; reason given.

---

## Section 1 — Architecture and Implementation

| # | Item | How to verify | Result |
|---|---|---|---|
| 1.1 | Project audit completed and documented | `docs/PHASE_3_IMPLEMENTATION_PLAN.md` exists and covers all six source files | PASS |
| 1.2 | Existing pipeline documented accurately | Plan section 2 matches actual source files and confirms `frame` stays open after `detect()` | PASS |
| 1.3 | Phase 3 plan reviewed before coding | Plan written before any source file was modified | PASS |
| 1.4 | `Phase3State.kt` created with all required enums | File exists; `LightingState`, `AngleState`, `OcclusionState`, `Phase3UiState`, `FrameAnalysis` all present | PASS |
| 1.5 | `FrameAnalyzer.kt` created | File exists in `com.example.mobilityassist` package | PASS |
| 1.6 | `AngleDetector.kt` + `AngleClassifier` created | Both present in same file; `AngleClassifier` has no Android imports | PASS |
| 1.7 | `SpeechHapticManager.kt` extended without changing `warn()` | `warn(Warning)` body is byte-for-byte identical to Phase 2; `speak()` and `Phase3SpeechGate` added | PASS |
| 1.8 | `MainActivity.kt` stores `Camera` reference | `camera` field declared `@Volatile private var camera: Camera?`; assigned in `bindCamera()` | PASS |
| 1.9 | `camera` nulled in `onStop()` | `camera = null` present in `onStop()` | PASS |
| 1.10 | `frame.close()` still in `finally` block | Analyzer lambda `finally { frame.close() }` — unchanged | PASS |
| 1.11 | Phase 3 pixel sampling happens before `detect()` returns and before `frame.close()` | `frameAnalyzer.analyze(...)` called before `detector.detect(frame)` in worker lambda | PASS |
| 1.12 | `runOnUiThread` used for all UI updates and torch calls | All `handleFrameAnalysis`, `activateTorch`, `deactivateTorch`, `updatePhase3Ui` run on UI thread | PASS |
| 1.13 | Generation token checked before every UI update | `if (active && !destroyed && token == generation.get())` guards all `runOnUiThread` blocks | PASS |
| 1.14 | `DecisionEngine.kt` unchanged | File not modified; diff is empty | PASS |
| 1.15 | `ObjectDetectorHelper.kt` unchanged | File not modified; diff is empty | PASS |
| 1.16 | `OverlayView.kt` unchanged | File not modified; diff is empty | PASS |
| 1.17 | `PipelineMetrics.kt` unchanged | File not modified; diff is empty | PASS |
| 1.18 | No new Android runtime permissions added | `AndroidManifest.xml` unchanged; `aapt dump permissions` output shows only CAMERA and VIBRATE | NOT TESTED — requires build verification |
| 1.19 | No network dependency introduced | Manifest `INTERNET` and `ACCESS_NETWORK_STATE` still removed via `tools:node="remove"` | PASS (code review) |

---

## Section 2 — Automatic Flashlight

| # | Item | How to verify | Result |
|---|---|---|---|
| 2.1 | Flash availability checked after `bindToLifecycle()` | `camera.cameraInfo.hasFlashUnit()` called; result stored in `hasFlash` | PASS |
| 2.2 | `UNAVAILABLE` state shown when device has no flash | Cover `hasFlash = false` path; `LightingState.UNAVAILABLE` set; "Flashlight unavailable on this device." spoken | NOT TESTED — requires device without flash or mock |
| 2.3 | Darkness detection implemented (green-channel sampling) | `FrameAnalyzer.analyze()` exists; reads `plane.buffer` with stride-aware green channel | PASS |
| 2.4 | Hysteresis thresholds implemented | `DARK_ENTER_THRESHOLD=60`, `DARK_EXIT_THRESHOLD=90`, `DARK_ENTER_FRAMES=8`, `DARK_EXIT_FRAMES=6` in `FrameAnalyzer` | PASS |
| 2.5 | Single dark frame does not activate flashlight | `FrameAnalyzerTest.singleDarkFrameDoesNotActivateFlashlight` — AUTOMATED | NOT TESTED — run task 13 |
| 2.6 | Persistent dark frames enter `LOW_LIGHT` | `FrameAnalyzerTest.persistentDarkFramesEnterLowLightState` — AUTOMATED | NOT TESTED — run task 13 |
| 2.7 | Single bright frame does not exit dark mode | `FrameAnalyzerTest.singleBrightFrameDoesNotExitLowLight` — AUTOMATED | NOT TESTED — run task 13 |
| 2.8 | Persistent bright frames exit to `FLASHLIGHT_OFF` | `FrameAnalyzerTest.persistentBrightFramesExitToFlashlightOff` — AUTOMATED | NOT TESTED — run task 13 |
| 2.9 | Exposure settling skips evaluation after torch toggle | `FrameAnalyzerTest.exposureSettlingSkipsEvaluation` — AUTOMATED | NOT TESTED — run task 13 |
| 2.10 | `enableTorch(true)` called from main thread only | `activateTorch()` is a private main-thread method; torch future listener uses `getMainExecutor` | PASS (code review) |
| 2.11 | `enableTorch` failure sets `LightingState.ERROR` | `activateTorch()` sets ERROR when `camera` is null; `ListenableFuture` listener present | PASS (code review) |
| 2.12 | Torch turned off in `onStop()`, `fail()`, and pause | `safeTorchOff()` called in all three paths | PASS (code review) |
| 2.13 | `safeTorchOff()` swallows exceptions | `try/catch` wraps `enableTorch(false)` call | PASS |
| 2.14 | Flashlight activation spoken: "Too dark. Turning on flashlight." | `speech_low_light` string resource; called via `feedback.speak(IMPORTANT, "lighting_low", 8000)` | PASS (code review) |
| 2.15 | "Flashlight is on." spoken after torch confirmed | Spoken in `activateTorch()` future listener with key `flashlight_on` | PASS (code review) |
| 2.16 | "Lighting improved. Turning off flashlight." spoken | `speech_flashlight_off` via `deactivateTorch()` | PASS (code review) |
| 2.17 | On-screen status shows correct state | `updatePhase3Ui()` maps all 6 `LightingState` values to text and colour | PASS (code review) |
| 2.18 | Flashlight activates in a dark room | **PHYSICAL DEVICE REQUIRED** — cover camera, confirm torch activates after ~8 analyzed frames | NOT TESTED |
| 2.19 | Flashlight deactivates when lights restored | **PHYSICAL DEVICE REQUIRED** — restore lighting, confirm torch off after ~6 analyzed frames | NOT TESTED |
| 2.20 | No rapid flickering around threshold boundary | **PHYSICAL DEVICE REQUIRED** — observe hysteresis in dim conditions at the threshold boundary | NOT TESTED |

---

## Section 3 — Phone Angle Guidance

| # | Item | How to verify | Result |
|---|---|---|---|
| 3.1 | Sensor availability checked at startup | `rotationSensor = sensorManager?.getDefaultSensor(TYPE_ROTATION_VECTOR)` — null → UNAVAILABLE | PASS |
| 3.2 | Coordinate system documented | `AngleDetector.kt` header documents pitch/roll convention in full | PASS |
| 3.3 | Target range configurable via constants | `PITCH_TARGET_MIN_DEG`, `PITCH_TARGET_MAX_DEG`, `ROLL_ACCEPTABLE_DEG`, `DEAD_BAND_DEG` in companion | PASS |
| 3.4 | Display rotation remapping implemented | `SensorManager.remapCoordinateSystem()` called in `processSensorEvent()` using `displayRotation` | PASS |
| 3.5 | Sensors unregistered in `onStop()` and pause | `angleDetector.unregister()` present in both code paths | PASS |
| 3.6 | Smoothing buffer implemented | `pitchBuffer`/`rollBuffer` of size `SMOOTHING_SAMPLES=5`; rolling average computed | PASS |
| 3.7 | Persistence gate implemented | `pendingCount >= PERSISTENCE_SAMPLES` required before state change fires callback | PASS |
| 3.8 | `AngleClassifier.classify()` — GOOD within range | `AngleDetectorTest.goodAngleAtCenterOfTargetRange` — AUTOMATED | NOT TESTED — run task 13 |
| 3.9 | `AngleClassifier.classify()` — TOO_HIGH | `AngleDetectorTest.tooHighWhenPitchAboveMaxPlusDeadBand` — AUTOMATED | NOT TESTED — run task 13 |
| 3.10 | `AngleClassifier.classify()` — TOO_LOW | `AngleDetectorTest.tooLowWhenPitchBelowMinMinusDeadBand` — AUTOMATED | NOT TESTED — run task 13 |
| 3.11 | `AngleClassifier.classify()` — TILTED_LEFT | `AngleDetectorTest.tiltedLeftWhenRollExceedsNegativeThreshold` — AUTOMATED | NOT TESTED — run task 13 |
| 3.12 | `AngleClassifier.classify()` — TILTED_RIGHT | `AngleDetectorTest.tiltedRightWhenRollExceedsPositiveThreshold` — AUTOMATED | NOT TESTED — run task 13 |
| 3.13 | Roll takes priority over pitch | `AngleDetectorTest.rollTakesPriorityOverBadPitch` — AUTOMATED | NOT TESTED — run task 13 |
| 3.14 | Dead band prevents oscillation | `AngleDetectorTest.deadBandCreatesHysteresis_pitchHigh` etc. — AUTOMATED | NOT TESTED — run task 13 |
| 3.15 | Guidance text correct for all states | `AngleDetectorTest.guidanceTextForAllStates` — AUTOMATED | NOT TESTED — run task 13 |
| 3.16 | "Tilt phone upright." spoken when TOO_LOW | Key `angle_TOO_LOW`, cooldown 4 s, INFORMATIONAL priority | PASS (code review) |
| 3.17 | "Tilt phone down." spoken when TOO_HIGH | Key `angle_TOO_HIGH`, cooldown 4 s | PASS (code review) |
| 3.18 | "Tilt phone left/right." spoken for roll | Keys `angle_TILTED_LEFT` / `angle_TILTED_RIGHT` | PASS (code review) |
| 3.19 | "Phone angle looks good." spoken once | Guidance text `"Phone angle looks good."` returned by `AngleClassifier.guidance(GOOD)` | PASS (code review) |
| 3.20 | Angle guidance does not interrupt STOP | `SpeechPriority.INFORMATIONAL` → `QUEUE_ADD`; STOP uses `QUEUE_FLUSH` via separate gate | PASS (code review) |
| 3.21 | On-screen angle status updates correctly | `updatePhase3Ui()` maps all 7 `AngleState` values | PASS (code review) |
| 3.22 | `ANGLE: ––` shown when sensor absent | `AngleState.UNAVAILABLE` → `status_angle_unavailable` | PASS (code review) |
| 3.23 | Sensor unavailable — no crash | `rotationSensor == null` path in `register()` sets UNAVAILABLE; no exception | PASS (code review) |
| 3.24 | Angle guidance works on physical device | **PHYSICAL DEVICE REQUIRED** — hold at various angles; confirm spoken and visual feedback | NOT TESTED |
| 3.25 | Correct direction guidance validated on device | **PHYSICAL DEVICE REQUIRED** — "Tilt phone upright." must fire when camera points at floor | NOT TESTED |
| 3.26 | Rotation (portrait ↔ landscape) handled without stale state | **PHYSICAL DEVICE REQUIRED** — rotate device; confirm `updateDisplayRotation()` fires; guidance remains correct | NOT TESTED |

---

## Section 4 — Camera Obstruction Detection

| # | Item | How to verify | Result |
|---|---|---|---|
| 4.1 | Obstruction estimator uses mean AND variance | `isCandidate = mean < 20 && variance < 5.0f` — both required | PASS |
| 4.2 | Darkness alone does not trigger obstruction | `FrameAnalyzerTest.darknessAloneIsNotObstruction` — AUTOMATED | NOT TESTED — run task 13 |
| 4.3 | Dark+low-variance persists to OBSTRUCTED | `FrameAnalyzerTest.persistentDarkLowVarianceReachesObstructed` — AUTOMATED | NOT TESTED — run task 13 |
| 4.4 | Dark-but-textured scene not obstructed | `FrameAnalyzerTest.darkButTexturedSceneDoesNotTriggerObstruction` — AUTOMATED | NOT TESTED — run task 13 |
| 4.5 | Single occlusion frame does not trigger | `FrameAnalyzerTest.singleOcclusionFrameDoesNotTrigger` — AUTOMATED | NOT TESTED — run task 13 |
| 4.6 | Recovery from OBSTRUCTED to CLEAR | `FrameAnalyzerTest.recoveryFromObstruction` — AUTOMATED | NOT TESTED — run task 13 |
| 4.7 | Obstruction returning during recovery → OBSTRUCTED | `FrameAnalyzerTest.obstructionReturnsDuringRecoveryGoesBackToObstructed` — AUTOMATED | NOT TESTED — run task 13 |
| 4.8 | "Camera covered. Please move your hand." spoken | `speech_camera_covered`, `SpeechPriority.URGENT`, `QUEUE_FLUSH`, cooldown 5 s | PASS (code review) |
| 4.9 | Obstruction warning interrupts non-obstacle speech | `URGENT` → `QUEUE_FLUSH`; interrupts any ongoing `QUEUE_ADD` speech | PASS (code review) |
| 4.10 | Obstruction warning does not loop continuously | `Phase3SpeechGate` suppresses repeats within 5 s; state-change guard prevents re-announce | PASS (code review) |
| 4.11 | "Camera view restored." spoken once on recovery | Only fired when transitioning from OBSTRUCTED/RECOVERING → CLEAR | PASS (code review) |
| 4.12 | Obstruction warning cannot block a STOP | STOP uses existing `WarningGate` + `QUEUE_FLUSH`; completely separate from `Phase3SpeechGate` | PASS (code review) |
| 4.13 | On-screen `CAMERA: BLOCKED` shown in red | `OcclusionState.OBSTRUCTED` → `0xFFEF9A9A` colour | PASS (code review) |
| 4.14 | On-screen `CAMERA: CHECK VIEW` (amber) for POSSIBLY_OBSTRUCTED | `OcclusionState.POSSIBLY_OBSTRUCTED` → amber | PASS (code review) |
| 4.15 | Hand-cover test on physical device | **PHYSICAL DEVICE REQUIRED** — cover rear lens; observe BLOCKED status and speech warning | NOT TESTED |
| 4.16 | Dark room false-positive test | **PHYSICAL DEVICE REQUIRED** — enter genuinely dark room; confirm no spurious obstruction | NOT TESTED |
| 4.17 | Recovery test on physical device | **PHYSICAL DEVICE REQUIRED** — uncover lens; confirm RECOVERING → CLEAR + restoration speech | NOT TESTED |
| 4.18 | Startup false-positive not triggered | `OcclusionState.UNAVAILABLE` until camera binds; then `CLEAR` set | PASS (code review) |

---

## Section 5 — Integration and Shared Voice Policy

| # | Item | How to verify | Result |
|---|---|---|---|
| 5.1 | Obstacle `warn(Warning)` path unchanged | `warn()` body identical to Phase 2; tested by existing `DecisionEngineTest` | NOT TESTED — run task 13 |
| 5.2 | Obstacle gate independent of Phase 3 gate | `gate = WarningGate()` and `phase3Gate = Phase3SpeechGate()` are separate instances | PASS |
| 5.3 | `Phase3SpeechGate` isolation test | `Phase3SpeechTest.phase3GateDoesNotShareStateWithWarningGate` — AUTOMATED | NOT TESTED — run task 13 |
| 5.4 | STOP interrupts all ongoing speech | `QUEUE_FLUSH` in `warn(Warning.STOP)` cancels any `QUEUE_ADD` speech | PASS (code review) |
| 5.5 | STOP not delayed by flashlight announcement | Flashlight uses `QUEUE_ADD`; STOP's `QUEUE_FLUSH` preempts it | PASS (code review) |
| 5.6 | Repeated Phase 3 messages throttled | `Phase3SpeechGate` per-key cooldown prevents repeat within configured window | PASS |
| 5.7 | Obstruction URGENT uses `QUEUE_FLUSH` | `SpeechPriority.URGENT` → `QUEUE_FLUSH` in `speak()` | PASS |
| 5.8 | Flashlight/angle use `QUEUE_ADD` | `SpeechPriority.IMPORTANT`/`INFORMATIONAL` → `QUEUE_ADD` | PASS |
| 5.9 | `feedback.stop()` resets both gates | `SpeechHapticManager.stop()` calls `gate.reset()` and `phase3Gate.reset()` | PASS |
| 5.10 | No Phase 3 speech after `onStop()` | `feedback.stop()` called in `onStop()`; generation token prevents stale `runOnUiThread` callbacks | PASS (code review) |
| 5.11 | TalkBack: status row cells have `contentDescription` | `lightingStatus`, `angleStatus`, `cameraStatus` all set `contentDescription` in `updatePhase3Ui()` | PASS (code review) |
| 5.12 | Status row hidden from TalkBack noise | Individual cells have descriptive `contentDescription`; outer `LinearLayout` not announced redundantly | PASS (code review) |
| 5.13 | STOP priority preserved under concurrent Phase 3 activity | **PHYSICAL DEVICE REQUIRED** — trigger STOP while flashlight/angle speech is in progress | NOT TESTED |
| 5.14 | No stale speech after pause/resume | `feedback.stop()` on pause; gates reset on resume via `bindCamera()` | PASS (code review) |
| 5.15 | Obstruction warning during obstacle detection | **PHYSICAL DEVICE REQUIRED** — cover camera while obstacle is present; STOP must still fire | NOT TESTED |

---

## Section 6 — Build and Regression

| # | Item | How to verify | Result |
|---|---|---|---|
| 6.1 | Debug build succeeds | `.\build.ps1 assembleDebug` exits 0; APK present at expected path | PASS — `BUILD SUCCESSFUL` 2026-10-11; APK at `app/build/outputs/apk/debug/app-debug.apk`, 54,113,465 bytes |
| 6.2 | Existing 7 unit tests pass | `.\build.ps1 testDebugUnitTest` — 0 failures; tests: DecisionEngineTest ×6, PipelineMetricsTest ×1 | PASS — 7/7 pass, 0 failures |
| 6.3 | Phase3SpeechTest passes (11 tests) | `testDebugUnitTest` output includes Phase3SpeechTest with 0 failures | PASS — 11/11 pass |
| 6.4 | FrameAnalyzerTest passes (22 tests) | `testDebugUnitTest` output includes FrameAnalyzerTest with 0 failures | PASS — 27/27 pass (27 tests after fix; alternatingBuffer helper corrected for stride-8 sampling) |
| 6.5 | AngleDetectorTest passes (24 tests) | `testDebugUnitTest` output includes AngleDetectorTest with 0 failures | PASS — 24/24 pass |
| 6.6 | No new unnecessary permissions | `aapt dump permissions` on APK shows only CAMERA, VIBRATE, and AndroidX receiver | NOT TESTED — requires aapt in PATH |
| 6.7 | No unexpected network dependency | `aapt dump permissions` shows no INTERNET or ACCESS_NETWORK_STATE | NOT TESTED — requires aapt in PATH |
| 6.8 | Lint run with 0 errors | `.\build.ps1 lintDebug` exits 0 | NOT TESTED — run `gradlew lintDebug` separately |
| 6.9 | APK generated at expected path | `app/build/outputs/apk/debug/app-debug.apk` exists | PASS — 54,113,465 bytes, 2026-10-11 |
| 6.10 | APK installs on physical phone | `adb install -r app/build/outputs/apk/debug/app-debug.apk` succeeds | NOT TESTED — requires device |

---

## Section 7 — Phase 3 Device Evaluation

### 7.1 Session record template

| Field | Value |
|---|---|
| Date and tester | |
| Phone model / Android version | |
| APK SHA-256 | |
| Offline TTS engine and voice | |
| Camera reported resolution | |
| Test location and lighting | |
| Build variant | debug |

### 7.2 Flashlight matrix

| Test ID | Lighting condition | Expected | Actual | Pass/Fail |
|---|---|---|---|---|
| FL-01 | Bright indoor — overhead lights on | No torch; LIGHTING: NORMAL | | NOT TESTED |
| FL-02 | Dim indoor — lights off, curtains drawn | Torch activates after ~1–2 s; speech plays | | NOT TESTED |
| FL-03 | Restore lights after FL-02 | Torch off after ~1 s; speech plays | | NOT TESTED |
| FL-04 | Cover camera in bright room | No torch (obstruction detected, not darkness) | | NOT TESTED |
| FL-05 | Device with no flash (if available) | FLASHLIGHT: N/A displayed; speech announces | | NOT TESTED |
| FL-06 | Pause and resume in dark room | Torch off on pause; re-evaluates on resume | | NOT TESTED |
| FL-07 | Screen rotation in dark room | Torch state preserved through rotation | | NOT TESTED |

### 7.3 Angle guidance matrix

| Test ID | Phone orientation | Expected guidance | Actual | Pass/Fail |
|---|---|---|---|---|
| AN-01 | Held at ~40° downward from horizontal (target) | ANGLE: GOOD; no spoken guidance | | NOT TESTED |
| AN-02 | Held nearly flat / screen facing up | ANGLE: TOO HIGH; "Tilt phone down." | | NOT TESTED |
| AN-03 | Held vertically pointing at floor | ANGLE: TOO LOW; "Tilt phone upright." | | NOT TESTED |
| AN-04 | Tilted 30° to the left | ANGLE: TILT LEFT; "Tilt phone right." | | NOT TESTED |
| AN-05 | Tilted 30° to the right | ANGLE: TILT RIGHT; "Tilt phone left." | | NOT TESTED |
| AN-06 | Move from TOO_LOW to target range | "Phone angle looks good." spoken once | | NOT TESTED |
| AN-07 | Landscape rotation | Angle guidance still directionally correct | | NOT TESTED |

### 7.4 Obstruction matrix

| Test ID | Scenario | Expected | Actual | Pass/Fail |
|---|---|---|---|---|
| OC-01 | Completely cover rear lens with palm | CAMERA: BLOCKED; "Camera covered. Please move your hand." | | NOT TESTED |
| OC-02 | Single finger briefly covers lens for < 0.5 s | No warning (below persistence threshold) | | NOT TESTED |
| OC-03 | Dark room without covering lens | No BLOCKED status (variance too high) | | NOT TESTED |
| OC-04 | Remove hand after OC-01 | CAMERA: CLEAR; "Camera view restored." | | NOT TESTED |
| OC-05 | Cover lens while obstacle detection is active | STOP not silenced; both warnings fire | | NOT TESTED |
| OC-06 | Cover lens in already-dark room (torch on) | BLOCKED more likely (torch + darkness + low variance) | | NOT TESTED |

### 7.5 Speech priority matrix

| Test ID | Scenario | Expected | Actual | Pass/Fail |
|---|---|---|---|---|
| SP-01 | STOP while flashlight announcement speaking | STOP interrupts immediately (QUEUE_FLUSH) | | NOT TESTED |
| SP-02 | Angle guidance while obstacle warning active | Angle QUEUE_ADD does not cut obstacle speech | | NOT TESTED |
| SP-03 | Obstruction while angle guidance speaking | Obstruction URGENT QUEUE_FLUSH interrupts angle | | NOT TESTED |
| SP-04 | Rapid obstruction + recovery | Single announcement each; no loop | | NOT TESTED |
| SP-05 | Phase 3 messages in airplane mode | TTS still works; all speech fires as expected | | NOT TESTED |

---

## Section 8 — Performance and Sustained Use

| # | Item | Target | Measured | Pass/Fail |
|---|---|---|---|---|
| 8.1 | Analyzed FPS (Phase 3 added) | ≥ Phase 2 baseline ±10% | NOT TESTED | NOT TESTED |
| 8.2 | p95 analysis-to-display (Phase 3 added) | ≤ Phase 2 baseline + 10% | NOT TESTED | NOT TESTED |
| 8.3 | `FrameAnalyzer` per-frame cost | < 2 ms | NOT TESTED | NOT TESTED |
| 8.4 | Sustained 30-min run | No crash, ANR, or memory growth | NOT TESTED | NOT TESTED |
| 8.5 | Battery draw (30 min, torch on) | Record %; no thermal throttle | NOT TESTED | NOT TESTED |

---

## Section 9 — Known Limitations

1. **Image framing for angle**: Phase 3 uses sensor-only angle estimation. Image-based framing (upper vs. lower luminance comparison) is deferred until performance is measured on hardware. The angle indicator is labeled as sensor-based only.
2. **Obstruction vs. darkness**: When the flashlight is OFF, a very dark uniform scene (e.g., inside a bag) may reach `POSSIBLY_OBSTRUCTED` before the flashlight activates. The two detectors are coordinated: the flashlight activates first, and if darkness persists after `EXPOSURE_SETTLE_FRAMES`, obstruction is escalated.
3. **Obstruction detection ≠ hand recognition**: The estimator uses image quality signals (mean + variance), not semantic hand recognition. False positives are possible with lens smearing, thick fog, or pressing the phone face-down.
4. **Angle thresholds are unvalidated**: `PITCH_TARGET_MIN_DEG=-60°`, `PITCH_TARGET_MAX_DEG=-20°` are starting values. They must be evaluated on a physical device with a real pedestrian walking gait before demo sign-off.
5. **Flash threshold tuning required**: `DARK_ENTER_THRESHOLD=60` was chosen conservatively. Camera-to-camera variation in auto-exposure behavior means this value may need adjustment on specific devices.
6. **No second-device coverage**: Phase 3 has not been tested on a second device. `BLOCKED` until primary device validation is complete.

---

## Demo gate

**Phase 3 supervised demo status: NOT READY.**  
Sections 6 (build) and 7 (device evaluation) must be completed on a physical phone and all PASS/FAIL outcomes recorded before a supervised demo may be approved.

---

## Quick phone checklist (about 10 minutes)

For the newer Phase 4 build, use [the updated phone checklist](PHASE_4_AUDIT.md#supervised-phone-checklist). The steps below describe the historical Phase 3 APK.

Use this shorter checklist for a first pass. Test while **standing still in a safe room with a sighted helper**; do not use the app to decide whether it is safe to walk. Install the locally built Phase 3 APK (`app/build/outputs/apk/debug/mobility-assist-phase3-debug.apk`, or `app-debug.apk` in the same folder), turn up media volume, and enable an offline English text-to-speech voice. Mark each item **Pass**, **Fail**, or **Not available** and write down what happened when it fails.

Phone / Android version: __________  Tester / date: __________  TTS voice: __________

| # | What to do on the phone | What should happen | Result / notes |
|---|---|---|---|
| 1 | Open the app and allow camera access. | The rear-camera picture appears and the screen shows that it is scanning; no crash. |PASS |
| 2 | In a bright room, point the camera at a person, bicycle, or parked vehicle. | A labeled box appears around a supported object. A warning may speak when it is near enough. |PASS |
| 3 | Keep a large supported object in the center of the picture. | The app says “Stop. Obstacle ahead.” and gives a stronger vibration. | PASS|
| 4 | Hold the phone at different angles: roughly forward, toward the ceiling, then toward the floor. | The **ANGLE** status changes and spoken tilt guidance matches the movement. |SLIGHT PASS |
| 5 | If the phone has a rear flash, dim the room for a few seconds, then turn the lights back on. | The torch turns on in sustained darkness and turns off after lighting recovers. **LIGHTING** and speech follow the change. |SLIGHT PASS |
| 6 | Cover the rear camera lens fully with a palm for a few seconds, then uncover it. | **CAMERA: BLOCKED** and a spoken warning appear; the app reports recovery after uncovering. | |
| 7 | Tap **Pause assistance**, then **Resume assistance**. | Camera and warnings stop while paused; the torch turns off. Scanning resumes without a crash. |PASS |
| 8 | Enable airplane mode and repeat one object and one lens-cover test. | Camera analysis and spoken warnings still work offline. |PASS |
| 9 | Rotate the phone and briefly send the app to the background, then return. | Preview and status recover; no stuck torch, crash, or repeated speech loop. | |
| 10 | Leave the app scanning for 5 minutes in a safe, stationary setup. | No freeze, overheating warning, or continuous repeated announcements. | |

If a check fails, note the phone model, lighting, the exact action, and what the app said or displayed. Use Section 7 above for a more detailed follow-up test.
