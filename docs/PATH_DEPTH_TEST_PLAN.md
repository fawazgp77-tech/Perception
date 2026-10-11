# Path segmentation and optional ARCore Depth

This is an experimental aid, not a validated mobility device. The camera can miss obstacles, drop-offs, glass, and roads. Keep using a cane, guide dog, or sighted assistance. Never infer that silence means a clear path.

## What changed

- The regular CameraX scanner now checks the quantized DeepLabV3+ model no faster than every 350 ms, sharing its camera bitmap with the object detector. A cyan overlay marks pixels classified as floor or sidewalk. A central wall with strong model evidence can warn after one result; weaker evidence needs two distinct results. Actual latency depends on inference time on the phone.
- A detected center obstacle can now produce “Stop. Obstacle ahead. Check left/right before moving” or “Obstacle ahead. Check left/right for a way through” when a side corridor has supporting ground evidence and no nearby detected object. A side obstacle names the opposite direction to check. When both side corridors look similarly open, the app prefers the side farther from the center object; if the object is exactly centered and both sides have strong ground evidence, it suggests the left as a consistent tie-breaker.
- A distant person can add support to a *partially visible* side corridor; a nearby person blocks that side. A person alone is not proof of a safe route. When neither side has sufficient evidence, the app gives only the blocked-path warning. After a blocked warning, one strong new forward-path result can produce a cautious “Path straight ahead appears open” message; weaker evidence needs two. These visual estimates do not verify a safe walking route.
- The earlier ML Kit image-level Wall labeler was removed. A plain-looking image is not treated as a wall; camera occlusion and unsuitable phone angle suppress the regular scanner's path warning.
- **Try depth-enhanced assistance** starts an ARCore-owned camera session only on phones where Google Play Services for AR and `DepthMode.AUTOMATIC` are available. It also runs the object detector and path model, then checks whether two central depth bands support a broad nearby barrier. If ARCore or Depth cannot start, the app returns to the regular menu.
- ARCore Depth mode is separate from CameraX because both camera owners cannot simply be bound at the same time. The regular scanner's automatic torch and detailed lens-status row are not yet carried into the ARCore screen. ARCore mode should be tested in good lighting and with an unobstructed lens.

## Model provenance and format

- Source: [MediaTek, DeepLabV3Plus checkpoint/pb/TFLite model for both float and quantized model](https://zenodo.org/records/3932677), Zenodo DOI `10.5281/zenodo.3932677`, Apache-2.0 license.
- Bundled asset: `app/src/main/assets/path_segmentation_ade20k.tflite` from `output_stride_16/quant/train_aware/freeze_quant_ops16_32c_clean.tflite` inside the source archive.
- SHA-256: `A4169300913D0B5D96001104D4E2CD6F3CE76A3E386D2CACB3DB9BF11FD264DB`.
- Input: uint8 RGB `[1, 512, 512, 3]`. Output: int32 class IDs `[1, 512, 512]`. The model uses the top ADE20K scene classes, including wall (1), floor (4), and sidewalk (12). These are *surface labels*, not obstacle distances or traversability guarantees.
- The source record reports ~54.84 mIoU for its exported graph on its evaluation set. That score is **not** a measured mobility-safety result, nor has this app's phone accuracy been measured.

## Automated checks

Run `gradlew.bat testDebugUnitTest assembleDebug lintDebug`. The JVM tests cover a centered wall, walkable floor/sidewalk, a wall off to the side, ambiguity from no visible path, sparse depth, a sloped ground depth pattern, and a nearby flat-depth barrier. These tests verify decision rules, not model accuracy or ARCore device behavior.

## Phone checklist (use a sighted helper)

1. Install the new debug APK. Confirm regular **Start camera assistance** still shows preview, object boxes, and cyan surface pixels; Pause, Resume, and Back must work.
2. Point at an indoor wall with visible floor at different distances. Time the first blocked-path warning and the first open-path message after turning toward a clear corridor. Report the measured delays and phone model; a strong model result should no longer wait for a second inference.
3. With a sighted helper, place a person or object in the center while the left side alone has visible floor, then repeat with the right side alone. Verify that a near object produces “Obstacle ahead” plus the supported check direction, while a very near object produces “Stop” plus that direction. Put a second near object on the suggested side; it should stop recommending that side.
4. Frame a wall ahead with a distant person in a partly visible side passage, then have the person stand close on that side. A distant person may support “check left/right” only with some visible ground; the close person should block that recommendation. Do not follow a person as a navigation target.
5. Frame a wall with no visible side route, and then an unclear or covered view. It should not invent a left/right route or say the path is open. Turn toward an unobstructed corridor; it should announce the newly visible forward path once, then stay quiet.
6. Point through an open doorway and along a corridor. The app should not repeatedly claim a wall across the open path.
7. Test pavement, sidewalk, road edge, grass, stairs, glass, and a blank white wall in bright and dim lighting. Record misses and false alerts; do not rely on the result to cross a road or approach a drop-off.
8. Cover the lens fully and partially, then tilt the phone markedly up/down. In the regular scanner, path claims should be suppressed when the view is blocked or the angle is known to be unsuitable; slight roll or a settling sensor should not delay them.
9. On an ARCore Depth-supported phone, tap **Try depth-enhanced assistance**. Accept any Google Play Services for AR installation request. Check that its rear-camera preview appears, then repeat wall, corridor, and doorway tests. Move the phone gently; depth can be unavailable when tracking has too little motion or light.
10. On a phone without ARCore Depth, the depth screen should exit with a message, and regular camera assistance should still work.
11. With a sighted helper stationary, compare speaking rate and battery/heat over five minutes in each mode. Report device model, Android version, approximate FPS, and any blank preview or crash.

Physical-device accuracy, preview alignment, latency, and ARCore camera behavior remain unverified until this checklist is run.
