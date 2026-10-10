# Phase 2 physical-device checklist

Use a physical Android phone with a rear camera, an offline English TTS voice, a sighted spotter, and the person's normal mobility aid. Test in a controlled area. Record phone model, Android version, date, APK SHA-256, lighting, and actual results. The app does not establish a clear route.

Install `app/build/outputs/apk/debug/app-debug.apk` after completing the preserved [Phase 1 test](DEVICE_TEST_CHECKLIST.md). The current build is Phase 2; the [Phase 1 source archive](releases/phase1/README.md) is available for a separate rebuild.

| # | Action | Expected result | Result / notes |
|---|---|---|---|
| 1 | Launch, grant camera permission, inspect portrait and landscape preview | Rear preview and bounding boxes align; status is understandable | Pending |
| 2 | Hold a detected object on the left, then the right | “Obstacle on left.”, then “Obstacle on right.” after stable detections; no steering instruction | Pending |
| 3 | Present detections on both sides | “Obstacles on both sides.”; no claim that center is clear | Pending |
| 4 | Move a wide, near object so its box center is left/right but the box substantially covers center | “Obstacle ahead.” or urgent STOP according to box area | Pending |
| 5 | Briefly flash a nonurgent detection for one analyzed frame, then remove it | No spoken warning from that isolated frame | Pending |
| 6 | Hold a stable nonurgent detection | Warning follows at least two matching analyzed frames; repeats at least two seconds apart | Pending |
| 7 | After a nonurgent warning, present a very-near center detection | STOP speaks without waiting for two frames, interrupts lower-priority speech, and gives the stronger pulse pattern | Pending |
| 8 | Pause, background, rotate, resume, and switch scenes | No stale warning; confirmation starts fresh for the resumed camera session | Pending |
| 9 | Repeat in airplane mode after offline voice setup | Camera, detection, speech, and vibration still work | Pending |
| 10 | Run for 30 minutes and use TalkBack | Record latency, analyzed fps, heat, battery, crashes, and whether the controls and status are usable | Pending |

Record missed hazards and false alarms, especially stairs, drop-offs, glass, poles, low light, and objects outside the seven-class allowlist. A physical test is necessary before claiming that the new guidance improves safety.
