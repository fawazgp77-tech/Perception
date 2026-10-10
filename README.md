# Mobility Assist — Phase 2 demo

See [PHASE2_PLAN.md](PHASE2_PLAN.md) for the current guidance changes and [PHASE2_TEST_CHECKLIST.md](PHASE2_TEST_CHECKLIST.md) for the first Phase 2 phone run. The [Phase 1 source archive](releases/phase1/README.md) and [device checklist](DEVICE_TEST_CHECKLIST.md) are included for the later Phase 1 test. Debug APKs are generated locally and are not committed to Git.

Kotlin Android demo: rear camera → on-device obstacle detection → bounding boxes → spoken and vibration warnings. No backend, maps, location permissions, GPS, image storage, or runtime network permission.

## Run

1. Open this `MobilityAssist` directory in Android Studio. Use its bundled JDK 17 (or select a JDK 17 Gradle JVM).
2. Install Android SDK Platform 35 and Build Tools 35 through SDK Manager and let Gradle sync. The project pins AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, CameraX 1.4.1, and MediaPipe Tasks Vision 0.10.21. Internet is needed for initial build dependencies only.
3. Connect a physical Android 8.0/API 26 or newer device with a rear camera. Enable USB debugging, select the `app` configuration, and Run. An emulator can exercise UI but is not sufficient for camera alignment, vibration, or latency validation.
4. Grant camera permission. Detection starts automatically. Hold the rear camera facing the scene. Use **Pause assistance** / **Resume assistance** to stop/start the camera and feedback. Backgrounding the app also stops processing and feedback.
5. Install an **offline English Text-to-Speech voice** in the device's Android TTS settings before testing offline. Raise media volume. The app selects only a voice reporting that it does not require a network; if none is available, it reports that speech is unavailable and keeps haptics active. Reopen the app after installing a voice.

Command line, once JDK 17 and the Android SDK are configured:

```powershell
.\build.ps1
```

`build.ps1` uses a project-local JDK/SDK when present and creates a local debug signing key. It also works with installed JDK/SDK settings when the project-local toolchain is absent. Run `./build.ps1 assembleDebug lintDebug` for specific Gradle tasks. On macOS/Linux: `chmod +x gradlew && ./gradlew testDebugUnitTest assembleDebug`. Android Studio can also build and run the project normally.
Android Studio creates `local.properties` with the local SDK path. Do not commit it.
The signed debug APK is written to [`app/build/outputs/apk/debug/app-debug.apk`](app/build/outputs/apk/debug/app-debug.apk). Install it with Android Studio, copy it to an Android device, or run `adb install -r app/build/outputs/apk/debug/app-debug.apk` while a device with USB debugging is connected. It is a debug build for testing, not a store release.

## Model

The pretrained **EfficientDet-Lite0 INT8 (320×320), version 1** model is already bundled at:

```text
app/src/main/assets/efficientdet_lite0.tflite
```

Expected filename: **`efficientdet_lite0.tflite`**, exactly. If missing, download the [official model](https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite) and put it at that path. Do not rename a different arbitrary TFLite model: MediaPipe Object Detector requires compatible output tensors and metadata. Labels are embedded; no separate labels file is needed.

Bundled model SHA-256: `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb`.
See Google's [model documentation and model links](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector) for provenance and upstream usage terms.

## Pipeline

1. `MainActivity` binds CameraX Preview and ImageAnalysis to the lifecycle and a shared `ViewPort`. The rear-camera preview uses `FILL_CENTER`.
2. Analysis requests 640×480 RGBA frames (actual supported resolution can differ) with `STRATEGY_KEEP_ONLY_LATEST`. CameraX drops old pending frames while the single analysis worker is busy.
3. `ObjectDetectorHelper` uses CameraX `toBitmap()` for stride-aware conversion, crops to the shared visible viewport, and rotates the bitmap upright. Detection boxes are normalized to that visible image, so overlay positions and left/center/right rules match the preview in either orientation. Cropping precedes rotation.
4. MediaPipe runs synchronous IMAGE inference on the worker, with the default CPU delegate. Synchronous inference deliberately keeps ownership and backpressure simple: one inference at a time, no asynchronous frame queue. Model initialization and shutdown also run on this worker. Confidence threshold is 0.45 and results are capped at 10.
5. The allowlist is `person`, `car`, `bus`, `truck`, `bicycle`, `motorcycle`, and `dog`. Boxes are clipped to image bounds, invalid boxes discarded, and each box center determines its horizontal third.
6. `DecisionEngine` uses box area / visible image area: **<8% FAR**, **8% to <25% NEAR**, **≥25% VERY_NEAR**. These are adjustable demo thresholds, not measured distances.
7. The highest-priority rule becomes one warning:

| Condition | Warning |
|---|---|
| Very near center object, or very near wide object occupying center | “Stop. Obstacle ahead.” |
| Near center object | “Obstacle ahead.” |
| Left-side obstacle | “Obstacle on left.” |
| Right-side obstacle | “Obstacle on right.” |
| Obstacles on both sides, without a higher-priority center warning | “Obstacles on both sides.” |
| Only far center objects, or no detections | No warning |

Center rules take priority. A near or very-near box whose center lies to one side can still count as a center hazard if enough of the box overlaps the middle third. Side warnings apply at all sizes. Nonurgent warnings need the same result in two successive analyzed frames within 1.2 seconds; STOP is immediate. The warnings describe detections and never establish a clear route.

8. `SpeechHapticManager` uses a monotonic two-second cooldown per warning plus a global two-second quiet period. A new urgent STOP bypasses the global interval but never its own cooldown. Speech uses `QUEUE_FLUSH`; STOP interrupts lower-priority speech. Normal haptics are 100 ms at amplitude 100; STOP is two 300 ms pulses at amplitude 255, separated by 100 ms. Devices without amplitude control still distinguish the pulse patterns.
9. Every analyzer callback closes its `ImageProxy` in `finally`, including skipped/error frames. MPImages and bitmaps are released after synchronous inference. UI updates use the main thread, and a generation token rejects stale results after pause, backgrounding, rebinding, or errors. Detector disposal is serialized after in-flight inference. A rolling debug line shows analyzed fps, p95 analysis-to-display time, and average conversion/inference time; it is hidden from TalkBack and updates at most once per second.

## Files

- `app/src/main/AndroidManifest.xml`: camera/vibration permissions, TTS service visibility.
- `app/build.gradle.kts`: Android, CameraX and MediaPipe dependencies.
- `app/src/main/res/layout/activity_main.xml`: preview, overlay, status, large pause button.
- `app/src/main/java/com/example/mobilityassist/MainActivity.kt`: permissions, lifecycle, pipeline.
- `ObjectDetectorHelper.kt`: model loading and inference.
- `DecisionEngine.kt`: proximity, region, center overlap, priority, temporal confirmation, cooldown.
- `SpeechHapticManager.kt`: offline TTS and haptics.
- `OverlayView.kt`: region guides, colored bounding boxes and labels.
- `app/src/test/java/com/example/mobilityassist/DecisionEngineTest.kt`: rule and cooldown tests.

## Validation and limitations

This is an assistance prototype, not a validated mobility aid. Continue using a cane, guide dog, or human assistance. The model can miss stairs, drop-offs, glass, poles, low-light obstacles, and classes outside the allowlist. Box area depends on object dimensions, cropping and angle; it is not depth or time to collision. No detections does not mean the path is clear. The new center-overlap and two-frame thresholds are unvalidated heuristics.

Before supervised demo use, test on a physical device:

- Rear preview and boxes align in portrait and landscape, including objects near each edge.
- Person/car/dog detections produce correct thirds; medium and large center boxes take priority.
- Nonurgent warnings occur after two matching analyzed frames, repeated warnings are spaced by at least two seconds, and STOP immediately interrupts a recent nonurgent warning.
- Conflicting side objects produce no directional command; an empty scene clears boxes.
- Pause, Home, rotation and resume do not produce stale speech or leave the camera active in the background.
- Permission denial/retry, missing model, unavailable offline voice and inference errors show actionable status.
- Speech and detection work in airplane mode after setup; STOP vibration is distinguishable from normal vibration.
- Observe the rolling diagnostic times and thermal behavior over a longer run. Analysis-to-display begins when CameraX invokes the analyzer; it is not full camera-to-speech latency. A camera-timestamp-based value is logged only as a diagnostic candidate until its clock is validated on that phone.

Unit tests cover region boundaries, size thresholds, wide center overlap, empty scenes, conflicting sides, center priority, temporal confirmation, cooldown boundaries and urgent preemption. Build/device verification status is recorded in [VERIFICATION.md](VERIFICATION.md).

Implementation references: [CameraX analysis](https://developer.android.com/media/camera/camerax/analyze), [MediaPipe Android Object Detector](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android).
