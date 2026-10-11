# Phase 4 implementation audit and phone checks

## What the code now does

| Reported issue | Root cause found | Change |
|---|---|---|
| Angle warnings too frequent | Narrow pitch/roll range, 3-reading gate, per-direction speech keys, spoken GOOD state | Wider range, 6-reading persistence, one 20-second angle cooldown, no GOOD speech |
| Bright/partial covers missed | Occlusion required a nearly black, uniform whole frame | 4×4 sampled image tiles compare texture and brightness with a clear baseline; sustained full or 2×2 partial covers can trigger a lens warning |
| Repeated obstacle speech | 2-second warning cooldown | 6 seconds for ordinary warnings, 4 seconds for STOP, 15 seconds for tentative wall reminders; secondary speech yields to recent obstacles |
| Only seven objects | MediaPipe category allowlist | Removed allowlist; all classes trained into the bundled EfficientDet-Lite0 model are eligible |
| Wall missed | COCO object detection does not include a wall class | Added a bundled ML Kit image labeler for the central crop, sampled about every 1.5 seconds; two confident Wall labels generate “Possible wall ahead.” |
| No start or destination flow | App launched directly into CameraX | Start menu, speech switch, spoken/typed destination, local Home address, and Google Maps walking-route preview |

## Safety and capability limits

- “Possible wall ahead” is **image-level classification**. It does not measure distance or establish whether a wall blocks the route. No STOP warning is generated solely from a Wall label.
- The EfficientDet model recognizes its trained categories, not arbitrary unseen obstacles. A single RGB camera and these models cannot guarantee detection of every obstacle, drop-off, glass panel, pole, or approaching vehicle. No warning does not mean the path is clear.
- Tile-based lens-cover detection needs an unobstructed baseline. A cover already present when the camera starts, a small cover below one 2×2 tile patch, or a low-texture scene may be missed or confused with an obstruction. Phone tests must tune thresholds.
- Google Maps owns the walking route, ETA, turn instructions, and final Start action. The app reads back the destination before opening Maps. Camera analysis stops while Maps is foregrounded. Neither the app nor this handoff offers verified road-crossing or live traffic safety alerts.
- Voice destination entry uses an installed Android recognition service; it may need network access. Text entry remains available. The camera models are bundled and work offline.

## Code audit findings

1. The Phase 3 torch listener announced success without checking the `enableTorch()` future. It now checks the result, reports an error on failure, blocks duplicate requests while one is pending, and ignores stale session callbacks.
2. The frame analyzer was described as worker-only but reset and torch methods were also called on the main thread. Its mutable methods are now synchronized. Torch state is volatile.
3. STOP remains immediate relative to nonurgent warnings. A recent obstacle warning now prevents a camera-obstruction message from flushing it; ordinary secondary messages wait.
4. `ImageProxy` remains closed in the analyzer's `finally` block. The optional wall labeler copies a sparse frame before asynchronous inference and releases its bitmap on completion.
5. The Google Maps handoff uses an encoded `api=1` walking URL without `dir_action=navigate`, so the user can inspect destination and time in Maps before starting.

## Automated verification

Run `gradlew.bat testDebugUnitTest assembleDebug lintDebug`. Tests cover angle tolerance and silence on GOOD, warning cooldown and STOP priority, bright/full/partial cover and recovery, wall-label confidence, category-agnostic decisions, and encoded walking-route URL. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Supervised phone checklist

Record phone model, Android version, TTS engine, lighting, and Pass/Fail for each item. Stay stationary with a sighted helper and usual mobility aid.

1. Launch app. Confirm the menu appears, speech is enabled, and camera permission is requested only after starting camera assistance.
2. Hold phone at normal hand angles and gently tilt. Confirm no GOOD speech and no repeated angle prompts within 20 seconds.
3. Show a chair or bottle close to center. Confirm a box and obstacle warning; check that warnings do not repeat rapidly.
4. Show a wall centered ahead, then a non-wall scene. Record whether the tentative wall warning appears, and any false positives.
5. Start with a clear, textured view. Cover the whole lens with a black hand, then a bright hand. Repeat with only the upper/side portion covered. Record detection and recovery time.
6. Dim and restore light. Confirm torch follows the scene and never announces ON if hardware activation fails.
7. Pause, resume, rotate, background, and return. Confirm no stale warning, stuck torch, or crash.
8. Speak a destination, inspect its readback, and open Maps. Confirm the route is walking, the ETA is shown in Maps, and tapping Start there begins Maps guidance.
9. Save Home and reopen the app. Confirm Home persists and its walking preview opens.
10. Repeat camera checks in airplane mode. Test voice entry separately; it may need a recognition service connection.

Do not claim the app is safe for independent travel until these checks, false-positive/false-negative cases, and longer sustained runs have been measured on target phones.

Sources: [ML Kit bundled image labeling](https://developers.google.com/ml-kit/vision/image-labeling/android), [ML Kit default labels](https://developers.google.com/ml-kit/vision/image-labeling/label-map), [Google Maps directions URLs](https://developers.google.com/maps/documentation/urls/get-started).
