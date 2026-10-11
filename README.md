# Perception — voice-led navigation and camera assistance

Perception is an Android prototype for blind users. The regular scanner keeps the v5.1 camera guidance and possible-stairs cue. It runs object detection, optional path segmentation, speech and vibration. Walking mode keeps the scanner open while showing a live Mappls map and speaking pedestrian turns. Camera obstacle warnings interrupt map guidance.

> This is not a validated mobility device. Camera models and GPS can miss hazards or place the user on the wrong side of a road. Use a cane, guide dog, or sighted assistance and independently verify crossings. No warning does not mean the path is safe.

## Run

Requirements: Android Studio/JDK 17, Android SDK 35, Android 8.0+ phone with rear camera and preferably GPS, an English Text-to-Speech voice, and internet for Mappls search, map tiles, and routing. Perception prefers an offline English TTS voice and falls back to an installed English voice when needed. Camera analysis itself is on-device and works without internet. ARCore Depth is optional and separate from regular camera assistance.

Mappls uses the two license/config files downloaded for the registered Android app. In the [Mappls Console](https://auth.mappls.com/), that app must use package `com.example.mobilityassist` and the SHA-256 of the signing certificate used for the APK. Download its matching `<appId>.a.conf` and `<appId>.a.olf` and place both in the **`app/` module root** beside `app/build.gradle.kts`. Keep only one matching pair there. They and the generated `app/src/main/assets/mappls-conf.txt` are Git-ignored; do not publish an APK containing your Mappls license without checking its terms. If your phone install uses another signing key, regenerate the Mappls files for that key. Mappls search and walking routing must be enabled for the app in the Console. Mappls publishes per-account daily usage quotas; "free" is subject to the account plan and is not unlimited.

The same Mappls app must also have map tiles, Autosuggest, Place Details, reverse geocoding, and pedestrian routing provisioned. A successful Gradle build does not prove that the installed APK can use those services.

### Verify Mappls authorization

The debug APK uses Android's standard debug keystore for the computer that builds it. Both `build.ps1` and Android Studio now use that same signing choice. To view this computer's SHA-256 fingerprint, run:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android | Select-String 'SHA256:'
```

In the Mappls Console, select the **Android app** whose app ID matches the prefix of the two `.a.conf`/`.a.olf` files. Check package name `com.example.mobilityassist` and that exact signing fingerprint. A different computer or a release APK has a different fingerprint. Confirm that the app has access to Map SDK/vector maps, Autosuggest, Place Details, Reverse Geocoding, and Directions/walking routing. Mappls SDK code **4** means “Method not Provisioned”; the app displays the operation (`map`, `suggest`, `place`, or `route`) and code beside the error. Code **10** is a connection/DNS failure; codes **1–3, 5–8** indicate configuration or authentication problems. See the [official Mappls SDK error reference](https://developer.mappls.com/documentation/sdk/android/docs/v2.0.0/SDK-Error-code/).

After any Console change, download a fresh matching file pair into `app/`, remove any obsolete pair with a different app ID, and build with `.\build.ps1 clean assembleDebug`. Reinstall the new APK. Do not paste the license files, private keys, or tokens into an issue or chat. A blank usage chart does not prove the app never attempted a call: SDK code 4 can reject a method before an HTTP request is counted.

Build:

```powershell
.\build.ps1 testDebugUnitTest assembleDebug lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Or open the project in Android Studio and Run. On a fresh install, Android asks for Camera, precise/approximate Location, and Microphone access. An update with permission already granted will not show a new prompt. Approximate-only or denied location still permits camera assistance. The app starts location tracking automatically; it never asks the user to choose an origin. The menu reports permission, disabled Location, acquisition, and accuracy states; use **Try current location again** after changing Settings.

## Voice-first use

After the Perception splash, the app asks you to say **Start Camera Assistance** or **Enter Destination**. These are also the two large menu buttons. The Camera Assistance button opens the existing scanner; check **Try depth-enhanced assistance** below it first if the phone supports ARCore Depth. **Navigate** expands the existing destination controls without changing what their buttons do.

The launcher, menu, and splash use the supplied Perception logo. Its source is `design/perception-logo.svg`; Android displays the rendered `app/src/main/res/drawable-nodpi/perception_logo.png` to preserve the SVG gradients, glow, and lettering.

For voice navigation, say **Enter Destination**, then a place or address. Perception speaks the first Mappls suggestion using its short name and locality. Say **yes** to select it or **no** to hear the next suggestion. When the walking route and current location are ready, the app reads the short destination and estimated time. Say **yes** again to begin camera-plus-map guidance, or **no** to cancel. You can also type and tap through the same flow. A fresh precise location is required; after a weak fix the voice flow waits and resumes when a usable fix arrives.

While Perception is visible, it restarts listening after each recognition result and pauses listening while the app speaks, to reduce feedback. Say **microphone off** to stop listening. Turning it back on requires tapping the large microphone control in the menu or the small **Mic off** control on the camera screen: a switched-off microphone cannot hear “microphone on.” The microphone stops when the app leaves the foreground. Android's speech recognizer is session-based and is not guaranteed to capture every command, especially in noise; buttons and TalkBack remain available. On-device recognition is used on Android 12+ when the phone provides it. Otherwise Android's recognition service is used with offline preference, but the service may use internet. Mappls navigation itself requires internet.

## Use walking navigation

1. Type at least three letters or use **Speak destination**. Choose one of the Mappls place suggestions; the menu map previews the selected place. Saved Home also opens this selection flow.
2. Once current location shows a fresh fix, tap **Enter Destination**. The map previews the pedestrian route before the confirmation. If the fix is weak, retry outdoors; search remains usable while waiting.
3. Review the origin, selected destination and estimated walking time. Confirm only if they are correct.
4. The camera scanner opens with a small live map in the upper right. Tap **Expand map** to put the camera in the upper half and map in the lower half. Tap **Small map** to return.
5. With a good fix, the app speaks an approaching turn, a separate "turn now" cue, and occasional straight-ahead reminders. Mappls maneuvers are used where present; geometric route bends provide a fallback. Four clearly off-route fixes trigger a walking reroute. Camera warnings take priority. **Back to menu** ends navigation. **Pause assistance** pauses both camera and spoken route guidance.

The app does not claim to identify safe crossings or detect vehicle traffic from the map. Pedestrian routes do not include live traffic avoidance. GPS accuracy changes with buildings and weather. The marker is projected onto the walking route only when the GPS fix is within its uncertainty corridor; this is an approximate display position, not proof of the street or side of the road. Turn-now speech requires reported accuracy of 18 meters or better. From 18–40 meters accuracy, the app gives a less precise turn-nearby cue. It requests a new route after four clearly displaced fixes, with a 10-second retry limit.

## Pipeline

CameraX Preview and ImageAnalysis share a viewport. ImageAnalysis uses 640×480 RGBA, `STRATEGY_KEEP_ONLY_LATEST`, and a single worker thread. `ObjectDetectorHelper` runs bundled EfficientDet-Lite0 via MediaPipe. `PathSegmenter` samples a quantized semantic model on the same worker. `DecisionEngine` and `GuidanceStabilizer` select warnings, `OverlayView` draws boxes/zones, and `SpeechHapticManager.warn()` speaks with `QUEUE_FLUSH` plus vibration. Every `ImageProxy` closes in `finally`.

`StairPatternDetector` samples the image less than twice a second and looks for repeated, wide horizontal step-like edges. Two independent positive samples produce a **“Possible stairs ahead. Stop and check”** warning with a 20-second repeat cooldown. This is an experimental cue for either ascending or descending steps, not a verified stair classifier. The bundled object and path models have no stair class, and a single RGB frame cannot reliably determine ascent versus descent or count steps. Floor tiles, crosswalk stripes, and shadows can look similar; the app therefore does not speak a direction or step count. A dedicated validated stair model and supervised phone testing are needed before making those claims.

For navigation, `PreciseLocationTracker` requests a current fused fix on phones with Google Play services and falls back to Android GPS/network providers. `MapplsWalkingRoutes` lists place suggestions, resolves the selected place, and requests a walking route. It distinguishes missing routes from connection, access, quota and service errors. `InAppWalkingMap` shows a menu preview, then the route and moving marker in the small or half-screen view. `WalkingGuidance` measures progress along the walking polyline, uses Mappls steps where available, infers larger bends when steps are missing, and gives straight/off-route cues. A recent camera warning delays turn-now speech until the next location fix, and a new camera warning interrupts map speech.

Bundled models:

- `app/src/main/assets/efficientdet_lite0.tflite` — object detection.
- `app/src/main/assets/path_segmentation_ade20k.tflite` — tentative floor/sidewalk/wall segmentation. A broad building-like or plain unknown surface with no visible path can now trigger a cautious “possible obstruction” alert after two fresh samples, provided camera-quality checks pass. This does not identify a wall or prove the route is blocked.

Mappls integration uses the [Android SDK](https://developer.mappls.com/documentation/sdk/android/), [Geocoding/Reverse Geocoding](https://developer.mappls.com/documentation/sdk/android/docs/v2.0.2/Search-Api/), and [walking Routing API](https://developer.mappls.com/documentation/sdk/android/docs/v2.0.2/Routing-API/). The app uses no backend and never opens an external Maps app.

## Phone test checklist

Use a sighted helper in a controlled, low-risk place; do not test on a live road.

- [ ] On a fresh install, grant Microphone as well as Camera and precise Location. After the splash, hear the two menu options. Say “Start Camera Assistance” and verify the scanner opens.
- [ ] Return to menu. Say “Enter Destination,” then an ambiguous place. Say “no” for the first result and verify the second is spoken; say “yes,” then confirm the route by saying “yes.” Verify camera and map both remain visible.
- [ ] Say “microphone off” while the camera is open. Verify the button changes to **Mic off** and voice commands stop. Tap it to restore listening. Background the app and verify listening stops until it is visible again.
- [ ] Test with no network or no offline recognizer; verify the buttons and typed destination still work. Test with a noisy room and TalkBack enabled.
- [ ] Check the olive/cream menu, splash animation, preview proportions, separate translucent FPS box, side-by-side pause/menu buttons, and map expand/collapse on the actual phone size.
- [ ] Verify the supplied Perception logo appears in the phone launcher, splash, and opening menu. Check that every app button, including place suggestions, route confirmation, and optional depth assistance, has an olive background and readable cream text.

- [ ] On fresh install, Camera and precise Location prompts appear. Denying Location leaves camera assistance functional.
- [ ] With permissions granted and Location enabled, current coordinates and an accuracy estimate appear. Stale or poor fixes prevent route start.
- [ ] Type an ambiguous place and verify multiple selectable suggestions. Spoken destination input must also show suggestions. Choose one and verify the selected pin, route preview, origin and ETA inside the app.
- [ ] Test no data, incorrect Mappls signing/config, exhausted quota, and an unreachable pedestrian destination where possible; each should show a specific error rather than the same generic route message.
- [ ] Confirm and verify camera boxes/warnings continue while the map shows the route and a moving current-position marker.
- [ ] Expand/collapse the map and verify the camera stays in the upper half, bounding boxes still align, and the Mappls attribution is visible.
- [ ] Near a known pedestrian turn, verify one timely map cue; present an obstacle and verify camera warning takes priority.
- [ ] At an accurately located turn, hear an approaching instruction around 50 m and a separate “turn now” instruction. Walk off the route in a controlled area and verify the app announces and draws a replacement route; confirm it does not do so on one poor GPS fix.
- [ ] On a straight walking route, hear an initial “continue straight” prompt and occasional reminders. At a known left and right bend, verify speech still works when Mappls supplies no usable maneuver modifier.
- [ ] Repeat a route on a road where GPS previously placed the marker beside the route. Check the shown accuracy, approximate matched marker, and that short lateral GPS drift does not trigger rerouting. A clearly different path should eventually trigger a new route.
- [ ] With a sighted helper, point the camera toward ascending and descending staircases from a safe distance; record whether the cautious possible-stairs warning appears. Also test flat tiles, crosswalk stripes, railings, and shadows for false alerts. Do not use this experimental cue to decide whether or how to climb stairs.
- [ ] With a nearby center obstacle, verify a left/right move is spoken only when visible floor connects to that side. With neither side confirmed, hear “Stop” and a request to turn the phone and scan. Far boxes should stay silent.
- [ ] Point the camera too high so the near floor disappears: hear a correction at most once every five seconds. A good angle should not be praised aloud.
- [ ] Confirm a destination with a long address: the dialog retains the full details while speech reads only the short destination name and ETA.
- [ ] Disable data or location mid-route; verify there is no confident turn speech on an inaccurate fix. Re-enable and verify recovery.
- [ ] Leave the route, return to menu, background/foreground, rotate, and revoke permissions; verify no crash or stale spoken directions.
- [ ] With a sighted helper, compare a close plain wall, building-like surface, open corridor, sky, covered lens, and partial occlusion. Time the first cautious obstruction alert and note misses/false alerts. Do not infer that a silent or “open” result is safe.

Unit tests cover obstacle decisions, frame/angle/path policy, and walking guidance state. **Physical walking navigation has not yet been validated.** See [VERIFICATION.md](VERIFICATION.md) for automated results and remaining device checks.
