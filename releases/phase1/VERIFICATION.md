# Verification — 2026-10-08

- **Build:** `build.ps1 testDebugUnitTest assembleDebug lintDebug` completed with `BUILD SUCCESSFUL` using the project-local Temurin JDK 17, Android SDK Platform 35, and Build Tools 35.
- **Unit tests:** 5 tests, 0 failures or errors: 4 `DecisionEngineTest` cases and 1 `PipelineMetricsTest` case.
- **Android Lint:** 0 errors, 8 warnings. The remaining warnings concern the pinned target/dependency versions. Report: `app/build/reports/lint-results-debug.html`.
- **APK:** [`app/build/outputs/apk/debug/app-debug.apk`](app/build/outputs/apk/debug/app-debug.apk), 54,334,486 bytes. SHA-256: `13ae5989c1036cec0ca248af20443e9b35df854a41e97dd44c9fcec3d49d162e`. `apksigner verify` passed. `aapt dump permissions` shows camera, vibration, and an AndroidX internal receiver permission; no Internet or network-state permission is packaged.
- **Implemented since the baseline:** rolling analyzed-fps and timing diagnostics; a timestamp sanity check for diagnostic camera-to-display estimates; a direct path to app settings after permanent camera denial; stable scanning status; resource-backed UI text; and a device-test worksheet.
- **Device validation pending:** `adb devices -l` reports no connected device. At the user's request, physical testing will happen later. Camera preview and box alignment, live inference, speech, vibration, TalkBack behavior, sustained latency, and the controlled-scene evaluation are **not verified**. The current signed APK is a development build, not a validated mobility aid.

Next device step: run [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md) on a phone with a rear camera and an offline English TTS voice, record results, and fix observed failures before the supervised demo gate in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).
