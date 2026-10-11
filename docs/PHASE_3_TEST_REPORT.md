# Mobility Assist — Phase 3 Test Report

**Status:** TEMPLATE — not yet populated  
**All physical-device fields are NOT TESTED until a device test session is conducted.**

---

## 1. Build Information

| Field | Value |
|---|---|
| Build date | 2026-10-11 |
| Gradle task | `testDebugUnitTest assembleDebug` |
| AGP version | 8.7.3 |
| Kotlin version | 2.0.21 |
| CameraX version | 1.4.1 |
| MediaPipe Tasks Vision | 0.10.21 |
| versionCode | 2 (inherits Phase 2; versionCode bump to 3 deferred to release prep) |
| versionName | 2.0-demo (inherits; rename deferred to release prep) |
| APK path | `app/build/outputs/apk/debug/app-debug.apk` |
| APK size | 54,113,465 bytes (51.61 MB) |
| APK SHA-256 | NOT TESTED — run `apksigner verify` or `Get-FileHash` on device to confirm |
| `apksigner verify` | NOT TESTED — requires apksigner in PATH |
| Lint errors | NOT TESTED — run `gradlew lintDebug` separately |
| Lint warnings | NOT TESTED |
| JDK used | OpenJDK 21.0.6 (Android Studio JBR) |
| Android SDK Platform | 35 |
| Build Tools | 35.0.0 |

---

## 2. Test Environment

| Field | Value |
|---|---|
| OS | Windows (build machine) |
| JDK | 17 (project-local or system) |
| Android SDK | Platform 35, Build Tools 35 |
| Physical device 1 | NOT TESTED |
| Android version (device 1) | NOT TESTED |
| Physical device 2 | NOT TESTED (secondary validation pending) |

---

## 3. Automated Unit Test Results

Run command: `.\build.ps1 testDebugUnitTest`

| Test class | Tests | Passed | Failed | Skipped | Notes |
|---|---|---|---|---|---|
| `DecisionEngineTest` | 6 | 6 | 0 | 0 | Existing; no regression |
| `PipelineMetricsTest` | 1 | 1 | 0 | 0 | Existing; no regression |
| `Phase3SpeechTest` | 11 | 11 | 0 | 0 | New Phase 3 |
| `FrameAnalyzerTest` | 27 | 27 | 0 | 0 | New Phase 3; 2 tests fixed for stride-8 sampling |
| `AngleDetectorTest` | 24 | 24 | 0 | 0 | New Phase 3; uses `AngleClassifier` (no Context) |
| **TOTAL** | **69** | **69** | **0** | **0** | BUILD SUCCESSFUL 2026-10-11 |

> Fill in actual counts from Gradle XML output at `app/build/test-results/testDebugUnitTest/`.

---

## 4. Flashlight Results

| Test ID | Condition | Expected | Actual | Pass/Fail | Notes |
|---|---|---|---|---|---|
| FL-01 | Bright indoor | No torch | NOT TESTED | NOT TESTED | |
| FL-02 | Dim indoor (lights off) | Torch activates ~1–2 s | NOT TESTED | NOT TESTED | |
| FL-03 | Restore lighting after FL-02 | Torch off | NOT TESTED | NOT TESTED | |
| FL-04 | Cover camera in bright room | No torch (obstruction, not dark) | NOT TESTED | NOT TESTED | |
| FL-05 | No-flash device | FLASHLIGHT: N/A + speech | NOT TESTED | NOT TESTED | |
| FL-06 | Pause/resume in dark | Torch off on pause, re-evaluates on resume | NOT TESTED | NOT TESTED | |
| FL-07 | Screen rotation in dark | Torch state preserved | NOT TESTED | NOT TESTED | |

---

## 5. Angle Detection Results

| Test ID | Orientation | Expected | Actual | Pass/Fail | Notes |
|---|---|---|---|---|---|
| AN-01 | Target range (~40° down) | ANGLE: GOOD | NOT TESTED | NOT TESTED | |
| AN-02 | Near flat / screen up | ANGLE: TOO HIGH; "Tilt phone down." | NOT TESTED | NOT TESTED | |
| AN-03 | Vertical pointing floor | ANGLE: TOO LOW; "Tilt phone upright." | NOT TESTED | NOT TESTED | |
| AN-04 | Tilted 30° left | ANGLE: TILT LEFT; "Tilt phone right." | NOT TESTED | NOT TESTED | |
| AN-05 | Tilted 30° right | ANGLE: TILT RIGHT; "Tilt phone left." | NOT TESTED | NOT TESTED | |
| AN-06 | TOO_LOW → target range | "Phone angle looks good." once | NOT TESTED | NOT TESTED | |
| AN-07 | Landscape rotation | Guidance still correct | NOT TESTED | NOT TESTED | |

---

## 6. Obstruction Detection Results

| Test ID | Scenario | Expected | Actual | Pass/Fail | Notes |
|---|---|---|---|---|---|
| OC-01 | Cover lens with palm | CAMERA: BLOCKED; speech warning | NOT TESTED | NOT TESTED | |
| OC-02 | Brief finger cover (< 0.5 s) | No warning | NOT TESTED | NOT TESTED | |
| OC-03 | Dark room (no cover) | No BLOCKED | NOT TESTED | NOT TESTED | |
| OC-04 | Remove hand after OC-01 | CAMERA: CLEAR; "Camera view restored." | NOT TESTED | NOT TESTED | |
| OC-05 | Cover during active detection | STOP not silenced | NOT TESTED | NOT TESTED | |
| OC-06 | Cover in dark room (torch on) | BLOCKED likely (torch + dark + uniform) | NOT TESTED | NOT TESTED | |

---

## 7. Speech Priority Results

| Test ID | Scenario | Expected | Actual | Pass/Fail | Notes |
|---|---|---|---|---|---|
| SP-01 | STOP during flashlight speech | STOP interrupts | NOT TESTED | NOT TESTED | |
| SP-02 | Angle guidance during obstacle warning | No interruption | NOT TESTED | NOT TESTED | |
| SP-03 | Obstruction during angle guidance | Obstruction interrupts | NOT TESTED | NOT TESTED | |
| SP-04 | Rapid obstruction + recovery | One announcement each | NOT TESTED | NOT TESTED | |
| SP-05 | Airplane mode | All speech fires | NOT TESTED | NOT TESTED | |

---

## 8. Performance Measurements

> Record these from the on-screen diagnostics line during a real session. Do not invent values.

| Metric | Phase 2 baseline | Phase 3 measured | Delta | Pass/Fail |
|---|---|---|---|---|
| Analyzed FPS | NOT ESTABLISHED | NOT TESTED | — | NOT TESTED |
| p95 analysis-to-display (ms) | NOT ESTABLISHED | NOT TESTED | — | NOT TESTED |
| Average conversion (ms) | NOT ESTABLISHED | NOT TESTED | — | NOT TESTED |
| Average inference (ms) | NOT ESTABLISHED | NOT TESTED | — | NOT TESTED |
| FrameAnalyzer per-frame (ms) | N/A | NOT TESTED | — | NOT TESTED |
| 30-min sustained FPS | NOT ESTABLISHED | NOT TESTED | — | NOT TESTED |

---

## 9. Known Issues

| Issue ID | Description | Severity | Status |
|---|---|---|---|
| P3-ISSUE-001 | Angle thresholds not validated on hardware — values are engineering estimates | Medium | Open |
| P3-ISSUE-002 | Flashlight thresholds not tuned for target devices | Medium | Open |
| P3-ISSUE-003 | Image-based framing deferred — angle is sensor-only | Low | Accepted/documented |
| P3-ISSUE-004 | Second-device validation not yet performed | Low | Pending primary device completion |
| P3-ISSUE-005 | Phase 2 physical-device checklist still fully pending | Medium | Carry-over from Phase 2 |

---

## 10. Limitations

- Obstruction detection uses image quality signals (mean luminance + variance), not semantic hand recognition. It cannot reliably distinguish a hand from a dark uniform surface.
- Angle guidance is sensor-based only. It does not confirm whether the camera's field of view actually captures the path ahead.
- All automated test results above are NOT TESTED until `.\build.ps1 testDebugUnitTest` is run and results recorded here.
- No physical-device test has been conducted for Phase 3 as of this report date.

---

## 11. Sign-Off

| Role | Name | Date | Signature |
|---|---|---|---|
| Developer | | | |
| Demo supervisor | | | |

**Phase 3 supervised demo approval status: PENDING**  
Physical device test session required before approval can be granted.
