# Mobility Assist implementation plan

This is the historical Phase 1 plan. The saved Phase 1 source is in [releases/phase1](releases/phase1/README.md). See [PHASE2_PLAN.md](PHASE2_PLAN.md) for the current implementation; Phase 2 changed the side-warning wording discussed below.

**Scope:** Finish and validate Phase 1: a fully offline Android app that detects the seven requested object classes through the rear camera and gives speech and vibration warnings. This plan covers a supervised prototype, not independent navigation. Maps, GPS, route guidance, cloud inference, accounts, and a backend are outside this phase.

**Baseline, 8 October 2026:** The Kotlin project already builds a signed debug APK. `testDebugUnitTest` has 4 passing tests; `lintDebug` has 0 errors and 22 warnings. CameraX preview and `ImageAnalysis`, `STRATEGY_KEEP_ONLY_LATEST`, a bundled EfficientDet-Lite0 INT8 model, bounding boxes, LEFT/CENTER/RIGHT rules, a two-second warning gate, TextToSpeech, and haptics are implemented. The packaged manifest has no Internet permission. See [VERIFICATION.md](VERIFICATION.md) for exact build evidence. **No physical Android device was connected**, so camera alignment, real detections, spoken output, vibration, and sustained latency remain unverified.

**Progress, 8 October 2026:** M2 now has rolling analysis/conversion/inference diagnostics and a timestamp sanity check; M3 has clearer camera-permission recovery, a stable TalkBack-visible status, and resource-backed UI text. The updated APK builds and the expanded unit tests pass. M1, device-dependent parts of M2/M3, M4, and M5 remain pending because the user will test with a phone later. Use [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md) for that run; current build evidence is in [VERIFICATION.md](VERIFICATION.md).

## Definition of done for Phase 1

A supervised demo is ready when a physical device can install the APK, show a correctly aligned rear preview and boxes, detect the seven classes in representative scenes, deliver the specified warning within the measured latency budget, and survive permission, pause/resume, rotation, and background transitions without stale warnings or crashes. Speech and detection must work in airplane mode after the offline voice is installed. The device test record must contain observed results, not only build status. The app must clearly state that box size is a proximity heuristic and that a direction warning does not prove a route is clear.

## Work order

| Milestone | Work and primary files | Evidence / exit criterion | Dependency |
|---|---|---|---|
| **M0 — Build baseline** (done) | Keep Gradle, model asset, manifest, APK packaging, `build.ps1`, and unit tests reproducible. | `testDebugUnitTest`, `assembleDebug`, `lintDebug`, APK signature and permission inspection pass. | None |
| **M1 — First device run** (P0) | Install on an Android 8+ phone with a rear camera. Test permission grant/denial, portrait and landscape preview, model load, labels, boxes, offline TTS, normal/STOP vibration, pause/resume, Home, and rotation. Log device model, Android version, test date, pass/fail, screenshots or short local clips where useful, and defects in `VERIFICATION.md`. | Every core flow passes on at least one physical phone; each failure has a reproducible case. Airplane-mode test passes after offline voice setup. | Device access, offline English voice |
| **M2 — Correctness and low latency** (P0) | In `MainActivity.kt` and `ObjectDetectorHelper.kt`, verify the analysis crop/rotation mapping against `PreviewView` at all four orientations and edge positions. Record capture-to-overlay and capture-to-warning time using frame timestamps. Profile conversion, inference, UI dispatch, memory, and thermal behavior; optimize only measured bottlenecks. Keep the single worker and latest-frame policy unless data justifies a change. | Boxes remain visually aligned near center and edges; no `ImageProxy` leak or frame backlog; p95 warning latency is under **750 ms** and analyzed frame rate is at least **5 fps** on the chosen reference phone in good light. If hardware misses these provisional budgets, record actual results and revise the budget or implementation before the demo gate. | M1 |
| **M3 — Warning quality and accessibility** (P0) | In `DecisionEngine.kt` and `SpeechHapticManager.kt`, exercise priority and cooldown on real sequences, validate offline TTS voice availability, prevent feedback from fighting TalkBack, and make permission/error/retry states speakable. Test the large pause control with TalkBack and Accessibility Scanner. Replace hardcoded UI strings with Android resources once wording is settled. | STOP can interrupt a nonurgent warning; duplicate warning speech stays at least 2 seconds apart; no contradictory left/right command; TalkBack can discover and operate pause/resume and hear failures without frame-by-frame chatter. | M1, M2 |
| **M4 — Controlled scene evaluation** (P0) | Build a small, consented, local-only test set or structured live-scene checklist for person, car, bus, truck, bicycle, motorcycle, and dog across left/center/right; include negative scenes, partial occlusion, different object sizes, and low light. Record ground-truth class and region, detections, missed hazards, false alarms, and warning timing. Tune score/area thresholds only from these observations. | A results table exists per class and condition. No severe center hazard in the controlled set is silently missed without a documented model limitation and a decision on whether the demo scope must narrow. Threshold choices are recorded with data; no distance claim is made from box area. | M2, M3 |
| **M5 — Supervised demo gate** (P0) | Review open defects, freeze the model and thresholds, rerun build/tests/lint, inspect permissions and signature, and repeat the device checklist. Use a safe indoor or controlled outdoor course with a sighted spotter and the user's normal mobility aid. | A dated test record names the device/APK hash, lists passed scenarios and known failures, and explicitly approves only a **supervised prototype**. Any unresolved crash, stale STOP, silent speech failure, or misleading direction prompt blocks the demo. | M1–M4 |

The provisional latency and frame-rate numbers are engineering targets for the reference phone, not measured performance or a safety certification. Start with one mid-range device; repeat M1–M4 on a second device before claiming broad Android support.

## Detailed tasks

### 1. Device and pipeline validation

- Confirm CameraX sends rear-camera frames to `ImageAnalysis` on the worker and drops old frames with `STRATEGY_KEEP_ONLY_LATEST`. Keep `ImageProxy.close()` in `finally` and test an injected inference failure to show the camera recovers or fails visibly. [CameraX documents the latest-frame behavior and the need to close frames](https://developer.android.com/media/camera/camerax/analyze).
- Check the crop/rotation path with a marked object moved to nine preview locations. Compare the overlay box with the actual preview in portrait, reverse portrait, and both landscape orientations where the device supports them. Use CameraX coordinate transformation guidance if the current normalized crop proves inaccurate. [CameraX documents analysis-to-preview coordinate mapping](https://developer.android.com/media/camera/camerax/transform-output).
- Confirm the model reports each expected COCO label, including `motorcycle`, and that confidence filtering does not hide obvious controlled examples. Record actual image size chosen by CameraX; `640×480` is a resolution preference, not a guarantee.
- Add lightweight debug-only metrics: frame timestamp, conversion duration, inference duration, main-thread dispatch, warning start, dropped/stale result count, and exception count. Keep the log local; do not save camera frames by default.

### 2. Warning policy

- Use the current rule order as the initial demo specification: very-near center → STOP; near center → AHEAD; single side → opposite-side guidance; both sides → AHEAD. Keep the two-second per-warning cooldown and global quiet period, with urgent STOP allowed to preempt a lower-priority warning.
- Test boundary cases with recorded detections: a box centered exactly on a third boundary, multiple classes competing, rapid left/right alternation, brief disappearance/reappearance, a very large object near an edge, and consecutive STOP frames.
- Treat the Phase 1 “Keep right/left” wording as **unverified steering advice**. A single camera and seven-class detector cannot establish that the suggested side is clear. Phase 2 changed these to descriptive “Obstacle on left/right” warnings; the Phase 1 wording remains only in the archived build for comparison.
- If TTS is unavailable, keep haptics and a clear, accessible error state. Test audio volume, Bluetooth/headphones, another app playing sound, and TalkBack; choose an audio-focus policy after observing actual device behavior.

### 3. Reliability and accessibility

- Test fresh install, permission denial, permanent denial (“don’t ask again”), permission revoked in Settings, no rear camera, model initialization failure, missing offline voice, app backgrounding, rotation, screen lock, and repeated pause/resume. `MainActivity.kt` should guide a permanently denied user to app settings rather than repeatedly requesting a permission dialog that cannot appear.
- Make the essential controls and status readable through TalkBack without announcing every analysis frame. Validate target size and focus order with TalkBack and Accessibility Scanner, then fix issues. Android recommends manual assistive-technology testing because static checks cannot capture every runtime interaction. [Android accessibility testing guide](https://developer.android.com/guide/topics/ui/accessibility/testing).
- Run a 30-minute on-device session and record crashes/ANRs, battery change, average/p95 inference and warning latency, screen brightness, and thermal throttling. Repeat after background/resume. Keep all processing on-device and recheck the packaged manifest for network permissions.

### 4. Evaluation record and release decision

Create a table for each scenario with: device/build, lighting, object/class, true region, detector class/score, box area, selected rule, speech heard, vibration felt, time to warning, and outcome. Include a negative scene for false alarms. Review misses and false guidance before lowering thresholds; lower thresholds can increase false alarms and warning fatigue. Save only measurements and consented test media locally.

For the initial controlled set, aim for at least **10 distinct examples per class** spanning the three regions and at least two lighting conditions, plus **20 negative scenes**. Repeat each example enough times to observe intermittent misses. These counts are a practical prototype baseline; they do not support a safety or general accuracy claim. Report detection and warning success by class and condition, including the denominator and every critical miss.

The model is a lightweight [EfficientDet-Lite0 object detector trained on COCO classes](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector). Its class list does not cover every mobility hazard. This app does not detect curb drop-offs, stairs, holes, glass, or safe walking space. No-detection frames must never be described as a clear path. A user should continue using a cane, guide dog, or sighted assistance throughout testing.

## Near-term execution checklist

1. Obtain one physical Android phone and install the current [debug APK](app/build/outputs/apk/debug/app-debug.apk).
2. Run M1 and write observed results into `VERIFICATION.md`; capture precise reproduction steps for any failure.
3. Fix M1 defects before tuning warning thresholds.
4. Instrument and run M2; use its measurements to choose optimization work.
5. Complete M3 and M4, then hold the M5 supervised demo review.

**Planning estimate:** roughly 2–3 focused development weeks after device access, with scene collection and hardware variability as the largest uncertainties. This is a planning range, not an elapsed-time guarantee.
