# Mobility Assist — v5.1 camera with in-app walking map

Mobility Assist is an Android prototype for blind users. The regular scanner uses the **v5.1 camera guidance** (v5.2 was reverted). It runs object detection, optional path segmentation, speech and vibration. The new walking mode keeps that same scanner open while showing a live Mappls map and speaking pedestrian turns. Camera obstacle warnings interrupt map guidance.

> This is not a validated mobility device. Camera models and GPS can miss hazards or place the user on the wrong side of a road. Use a cane, guide dog, or sighted assistance and independently verify crossings. No warning does not mean the path is safe.

## Run

Requirements: Android Studio/JDK 17, Android SDK 35, Android 8.0+ phone with rear camera and preferably GPS, an offline English Text-to-Speech voice, and internet for Mappls search, map tiles, and routing. Camera analysis itself is on-device and works without internet. ARCore Depth is optional and separate from regular camera assistance.

Mappls uses the two license/config files supplied for this project. In the [Mappls Console](https://auth.mappls.com/), the Android app must be registered with application ID `com.example.mobilityassist` and the SHA-256 of the signing certificate used for the APK. Download its `<appId>.a.conf` and `<appId>.a.olf` and place both in the **`app/` module root** beside `app/build.gradle.kts`. The files supplied in `app1791711491182i1807861861/` can be copied there locally. They and the generated `app/src/main/assets/mappls-conf.txt` are Git-ignored; do not publish an APK containing your Mappls license without checking its terms. If your phone install uses another signing key, regenerate the Mappls files for that key. Mappls search and walking routing must be enabled for the app in the Console. Mappls publishes per-account daily usage quotas; "free" is subject to the account plan and is not unlimited.

Build:

```powershell
.\build.ps1 testDebugUnitTest assembleDebug lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Or open the project in Android Studio and Run. The app asks for Camera and precise Location permission on launch. Approximate-only or denied location still permits camera assistance, but walking guidance needs a fresh accurate fix. Turn on device Location and test outdoors.

## Use walking navigation

1. Wait for **Current location** to show coordinates and its ± accuracy estimate.
2. Type an address or use **Speak destination**; tap **Enter Destination**. Saved Home also uses this flow.
3. Review the Mappls-resolved origin, destination and estimated walking time. Confirm only if they are correct.
4. The camera scanner opens with a small live map in the upper right. Tap **Expand map** to put the camera in the upper half and map in the lower half. Tap **Small map** to return.
5. Map turns are announced only with a recent location fix; obstacle warnings use the existing camera speech/vibration path and take precedence. **Back to menu** ends navigation. **Pause assistance** pauses both camera and spoken route guidance.

The app does not claim to identify safe crossings or detect vehicle traffic from the map. Pedestrian routes do not include live traffic avoidance. GPS accuracy changes with buildings and weather. The route is recalculated after repeated off-route fixes, but map instructions pause when accuracy is poor.

## Pipeline

CameraX Preview and ImageAnalysis share a viewport. ImageAnalysis uses 640×480 RGBA, `STRATEGY_KEEP_ONLY_LATEST`, and a single worker thread. `ObjectDetectorHelper` runs bundled EfficientDet-Lite0 via MediaPipe. `PathSegmenter` samples a quantized semantic model on the same worker. `DecisionEngine` and `GuidanceStabilizer` select warnings, `OverlayView` draws boxes/zones, and `SpeechHapticManager.warn()` speaks with `QUEUE_FLUSH` plus vibration. Every `ImageProxy` closes in `finally`.

For navigation, `PreciseLocationTracker` receives foreground GPS/network fixes. `MapplsWalkingRoutes` resolves the entered address and requests a walking route. The confirmation includes the current position/address and walking ETA. `InAppWalkingMap` draws the route and updates the user's marker. `WalkingGuidance` issues turn and off-route cues from Mappls pedestrian steps. Map speech uses the lower-priority `speak()` gate; a recent camera warning suppresses it, and a new camera warning interrupts it.

Bundled models:

- `app/src/main/assets/efficientdet_lite0.tflite` — object detection.
- `app/src/main/assets/path_segmentation_ade20k.tflite` — tentative floor/sidewalk/wall segmentation; wall reliability work is deferred.

Mappls integration uses the [Android SDK](https://developer.mappls.com/documentation/sdk/android/), [Geocoding/Reverse Geocoding](https://developer.mappls.com/documentation/sdk/android/docs/v2.0.2/Search-Api/), and [walking Routing API](https://developer.mappls.com/documentation/sdk/android/docs/v2.0.2/Routing-API/). The app uses no backend and never opens an external Maps app.

## Phone test checklist

Use a sighted helper in a controlled, low-risk place; do not test on a live road.

- [ ] On fresh install, Camera and precise Location prompts appear. Denying Location leaves camera assistance functional.
- [ ] With permissions granted and Location enabled, current coordinates and an accuracy estimate appear. Stale or poor fixes prevent route start.
- [ ] Enter an address and verify the **resolved** destination, origin and ETA in the confirmation. Cancel must leave the menu unchanged.
- [ ] Confirm and verify camera boxes/warnings continue while the map shows the route and a moving current-position marker.
- [ ] Expand/collapse the map and verify the camera stays in the upper half, bounding boxes still align, and the Mappls attribution is visible.
- [ ] Near a known pedestrian turn, verify one timely map cue; present an obstacle and verify camera warning takes priority.
- [ ] Disable data or location mid-route; verify there is no confident turn speech on an inaccurate fix. Re-enable and verify recovery.
- [ ] Leave the route, return to menu, background/foreground, rotate, and revoke permissions; verify no crash or stale spoken directions.

Unit tests cover obstacle decisions, frame/angle/path policy, and walking guidance state. **Physical walking navigation has not yet been validated.** See [VERIFICATION.md](VERIFICATION.md) for automated results and remaining device checks.
