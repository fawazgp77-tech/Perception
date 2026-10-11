# Mobility Assist — v5.1 camera with in-app walking map

Mobility Assist is an Android prototype for blind users. The regular scanner uses the **v5.1 camera guidance** (v5.2 was reverted). It runs object detection, optional path segmentation, speech and vibration. The new walking mode keeps that same scanner open while showing a live Mappls map and speaking pedestrian turns. Camera obstacle warnings interrupt map guidance.

> This is not a validated mobility device. Camera models and GPS can miss hazards or place the user on the wrong side of a road. Use a cane, guide dog, or sighted assistance and independently verify crossings. No warning does not mean the path is safe.

## Run

Requirements: Android Studio/JDK 17, Android SDK 35, Android 8.0+ phone with rear camera and preferably GPS, an offline English Text-to-Speech voice, and internet for Mappls search, map tiles, and routing. Camera analysis itself is on-device and works without internet. ARCore Depth is optional and separate from regular camera assistance.

Mappls uses the two license/config files supplied for this project. In the [Mappls Console](https://auth.mappls.com/), the Android app must be registered with application ID `com.example.mobilityassist` and the SHA-256 of the signing certificate used for the APK. Download its `<appId>.a.conf` and `<appId>.a.olf` and place both in the **`app/` module root** beside `app/build.gradle.kts`. The files supplied in `app1791711491182i1807861861/` can be copied there locally. They and the generated `app/src/main/assets/mappls-conf.txt` are Git-ignored; do not publish an APK containing your Mappls license without checking its terms. If your phone install uses another signing key, regenerate the Mappls files for that key. Mappls search and walking routing must be enabled for the app in the Console. Mappls publishes per-account daily usage quotas; "free" is subject to the account plan and is not unlimited.

The same Mappls app must also have map tiles, Autosuggest, Place Details, reverse geocoding, and pedestrian routing provisioned. A successful Gradle build does not prove that the installed APK can use those services.

Build:

```powershell
.\build.ps1 testDebugUnitTest assembleDebug lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Or open the project in Android Studio and Run. On a fresh install, Android asks for Camera and precise/approximate Location access. An update with permission already granted will not show a new prompt. Approximate-only or denied location still permits camera assistance. The menu reports permission, disabled Location, acquisition, and accuracy states; use **Try current location again** after changing Settings.

## Use walking navigation

1. Type at least three letters or use **Speak destination**. Choose one of the Mappls place suggestions; the menu map previews the selected place. Saved Home also opens this selection flow.
2. Once current location shows a fresh fix, tap **Enter Destination**. The map previews the pedestrian route before the confirmation. If the fix is weak, retry outdoors; search remains usable while waiting.
3. Review the origin, selected destination and estimated walking time. Confirm only if they are correct.
4. The camera scanner opens with a small live map in the upper right. Tap **Expand map** to put the camera in the upper half and map in the lower half. Tap **Small map** to return.
5. Map turns are announced only with a recent location fix; obstacle warnings use the existing camera speech/vibration path and take precedence. **Back to menu** ends navigation. **Pause assistance** pauses both camera and spoken route guidance.

The app does not claim to identify safe crossings or detect vehicle traffic from the map. Pedestrian routes do not include live traffic avoidance. GPS accuracy changes with buildings and weather. The route is recalculated after repeated off-route fixes, but map instructions pause when accuracy is poor.

## Pipeline

CameraX Preview and ImageAnalysis share a viewport. ImageAnalysis uses 640×480 RGBA, `STRATEGY_KEEP_ONLY_LATEST`, and a single worker thread. `ObjectDetectorHelper` runs bundled EfficientDet-Lite0 via MediaPipe. `PathSegmenter` samples a quantized semantic model on the same worker. `DecisionEngine` and `GuidanceStabilizer` select warnings, `OverlayView` draws boxes/zones, and `SpeechHapticManager.warn()` speaks with `QUEUE_FLUSH` plus vibration. Every `ImageProxy` closes in `finally`.

For navigation, `PreciseLocationTracker` requests a current fused fix on phones with Google Play services and falls back to Android GPS/network providers. `MapplsWalkingRoutes` lists place suggestions, resolves the selected place, and requests a walking route. It distinguishes missing routes from connection, access, quota and service errors. `InAppWalkingMap` shows a menu preview, then the route and moving marker in the small or half-screen view. `WalkingGuidance` issues turn and off-route cues from Mappls pedestrian steps. Map speech uses the lower-priority `speak()` gate; a recent camera warning suppresses it, and a new camera warning interrupts it.

Bundled models:

- `app/src/main/assets/efficientdet_lite0.tflite` — object detection.
- `app/src/main/assets/path_segmentation_ade20k.tflite` — tentative floor/sidewalk/wall segmentation. A broad building-like or plain unknown surface with no visible path can now trigger a cautious “possible obstruction” alert after two fresh samples, provided camera-quality checks pass. This does not identify a wall or prove the route is blocked.

Mappls integration uses the [Android SDK](https://developer.mappls.com/documentation/sdk/android/), [Geocoding/Reverse Geocoding](https://developer.mappls.com/documentation/sdk/android/docs/v2.0.2/Search-Api/), and [walking Routing API](https://developer.mappls.com/documentation/sdk/android/docs/v2.0.2/Routing-API/). The app uses no backend and never opens an external Maps app.

## Phone test checklist

Use a sighted helper in a controlled, low-risk place; do not test on a live road.

- [ ] On fresh install, Camera and precise Location prompts appear. Denying Location leaves camera assistance functional.
- [ ] With permissions granted and Location enabled, current coordinates and an accuracy estimate appear. Stale or poor fixes prevent route start.
- [ ] Type an ambiguous place and verify multiple selectable suggestions. Spoken destination input must also show suggestions. Choose one and verify the selected pin, route preview, origin and ETA inside the app.
- [ ] Test no data, incorrect Mappls signing/config, exhausted quota, and an unreachable pedestrian destination where possible; each should show a specific error rather than the same generic route message.
- [ ] Confirm and verify camera boxes/warnings continue while the map shows the route and a moving current-position marker.
- [ ] Expand/collapse the map and verify the camera stays in the upper half, bounding boxes still align, and the Mappls attribution is visible.
- [ ] Near a known pedestrian turn, verify one timely map cue; present an obstacle and verify camera warning takes priority.
- [ ] Disable data or location mid-route; verify there is no confident turn speech on an inaccurate fix. Re-enable and verify recovery.
- [ ] Leave the route, return to menu, background/foreground, rotate, and revoke permissions; verify no crash or stale spoken directions.
- [ ] With a sighted helper, compare a close plain wall, building-like surface, open corridor, sky, covered lens, and partial occlusion. Time the first cautious obstruction alert and note misses/false alerts. Do not infer that a silent or “open” result is safe.

Unit tests cover obstacle decisions, frame/angle/path policy, and walking guidance state. **Physical walking navigation has not yet been validated.** See [VERIFICATION.md](VERIFICATION.md) for automated results and remaining device checks.
