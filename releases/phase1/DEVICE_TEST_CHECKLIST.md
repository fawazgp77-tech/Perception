# Phase 1 physical-device checklist

Run this with a sighted spotter in a controlled area while using the person's normal mobility aid. Keep the camera pointed forward; this app does not establish a clear route. Record actual results in this file or in `VERIFICATION.md` before calling the prototype device-validated.

## Session record

| Field | Value |
|---|---|
| Date and tester | Pending |
| Phone model / Android version | Pending |
| APK SHA-256 | Pending |
| Offline English TTS engine and voice | Pending |
| Camera resolution and orientation | Pending |
| Test location and lighting | Pending |

Install the current `app/build/outputs/apk/debug/app-debug.apk` with Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Enable an offline English TTS voice first. Turn on TalkBack for the accessibility pass. Use airplane mode for the offline pass after installation.

## Core checks

Mark each row **pass**, **fail**, or **not tested** and add observed behavior, including a reproduction step for a failure.

| # | Action | Expected result | Result / notes |
|---|---|---|---|
| 1 | Fresh launch, grant camera permission | Rear preview starts; scanning status appears; no crash | pass |
| 2 | Deny permission, then retry | App explains why camera is needed; retry requests permission | PASS |
| 3 | Permanently deny permission | Button opens Android app settings; returning after a grant starts scanning | pass |
| 4 | Present a person in left, center, right thirds | Label and box follow the person; region changes at the visible third lines | pass |
| 5 | Repeat with car, bus, truck, bicycle, motorcycle, dog where available | Correct class label appears; record misses by class | Pass |
| 6 | Move a marked target to nine screen positions, then rotate | Box aligns with preview, including near edges, in supported orientations | Pending |
| 7 | Show a medium center detection | “Obstacle ahead.” with normal vibration | pass |
| 8 | Show a large center detection | “Stop. Obstacle ahead.” with stronger two-pulse vibration | pass |
| 9 | Show left-only, right-only, then both-side detections | “Keep right.”, “Keep left.”, then “Obstacle ahead.”; no contradictory direction | pass |
| 10 | Hold an object in view for 10 seconds | Same spoken warning is spaced by at least 2 seconds; no growing frame backlog | Pending |
| 11 | Trigger a nonurgent warning, then a center STOP | STOP interrupts promptly | Pending |
| 12 | Pause, resume, Home, reopen, rotate, lock/unlock | Camera and feedback stop while inactive; no stale warning on return | Pending |
| 13 | Airplane mode after offline voice setup | Detector and spoken warnings still work | Pending |
| 14 | Test with TalkBack, headphones/Bluetooth, and other audio | Pause/resume is discoverable; critical status is understandable; warnings are audible | Pending |
| 15 | Run for 30 minutes | Record analyzed fps, p95 analysis-to-display time, conversion/inference time, battery, heat, crashes/ANRs | Pending |

The diagnostic line is intentionally hidden from TalkBack. Camera-to-display time in debug logs is a **candidate** until the phone's camera timestamp clock is verified. The visible p95 metric starts when CameraX gives the analyzer a frame; it excludes time before that point.

## Controlled-scene record

Use at least 10 distinct examples for each of the seven classes across the three regions and at least two lighting conditions, plus 20 negative scenes. Record misses and false alarms rather than only successes. Do not use the small controlled set to claim general accuracy.

| Scene ID | Class / true region | Lighting | Detection / score / box area | Warning heard / vibration | Delay | Outcome / defect |
|---|---|---|---|---|---|---|
| Example | Person / center | Indoor bright | Person / 0.86 / 0.12 | “Obstacle ahead.” / normal | 420 ms | Replace with actual observation |

## Gate

**Supervised demo status: pending.** Do not mark pass until the above checks have been run on a physical phone and failures have been reviewed. A crash, stale STOP, silent speech failure, or misleading direction prompt blocks the demo.
