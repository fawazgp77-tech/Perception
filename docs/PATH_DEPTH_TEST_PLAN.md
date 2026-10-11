# Path segmentation and optional ARCore Depth

This is an experimental aid, not a validated mobility device. The camera can miss obstacles, drop-offs, glass, and roads. Keep using a cane, guide dog, or sighted assistance. Never infer that silence means a clear path.

## What changed

- The regular CameraX scanner now samples the quantized DeepLabV3+ segmentation model no faster than every 650 ms on its analysis worker. A cyan overlay marks pixels classified as floor or sidewalk. A strongly classified central wall can trigger a warning from one model result; weaker evidence still needs two distinct results. Actual latency depends on inference speed on the phone. The existing object detector still takes priority for central obstacles.
- When a blocked forward view has a connected floor/sidewalk corridor on just one side, it may say “Turn left/right to check for a way through.” It does not recommend a side with a known nearby detected object. When neither side has sufficient evidence, it gives only the blocked-path warning and waits. After a blocked warning, two new model results showing a forward path can produce one “Path ahead appears open” message. These visual estimates do not verify a safe walking route.
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
2. Point at an indoor wall with visible floor at different distances. Note whether the mask marks wall versus floor and time the first blocked-path warning. A strong wall should be announced from the first model result; a less certain one should need two. Report the measured delay and phone model.
3. With a sighted helper, frame a wall ahead and a genuinely open-looking corridor on only the left, then only the right. Check that the app suggests the corresponding turn, and that a detected nearby person/object on that side suppresses the directional suggestion. The spoken direction asks you to **check**, not to walk blindly into it.
4. Frame a wall with no visible side route, and then an unclear or covered view. It should not invent a left/right route or say the path is open. Turn toward an unobstructed corridor; it should announce the newly visible forward path once, then stay quiet instead of repeatedly saying the angle or path is good.
5. Point through an open doorway and along a corridor. The app should not repeatedly claim a wall across the open path.
6. Test pavement, sidewalk, road edge, grass, stairs, glass, and a blank white wall in bright and dim lighting. Record misses and false alerts; do not rely on the result to cross a road or approach a drop-off.
7. Cover the lens fully and partially, then tilt the phone. In the regular scanner, path warnings should be suppressed when the view is blocked or angle unsuitable.
8. On an ARCore Depth-supported phone, tap **Try depth-enhanced assistance**. Accept any Google Play Services for AR installation request. Check that its rear-camera preview appears, then repeat wall, corridor, and doorway tests. Move the phone gently; depth can be unavailable when tracking has too little motion or light.
9. On a phone without ARCore Depth, the depth screen should exit with a message, and regular camera assistance should still work.
10. With a sighted helper stationary, compare speaking rate and battery/heat over five minutes in each mode. Report device model, Android version, approximate FPS, and any blank preview or crash.

Physical-device accuracy, preview alignment, latency, and ARCore camera behavior remain unverified until this checklist is run.
