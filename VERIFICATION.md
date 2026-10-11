# Verification — Mobility Assist

## Route matching and possible-stairs preview (2026-10-11)

- `clean testDebugUnitTest assembleDebug lintDebug` completed successfully with JDK 17 and Android SDK 35. All 107 JVM tests passed; lint found 0 errors and 28 warnings.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, versionCode 12, versionName `5.1-route-stairs-preview`, 126,786,523 bytes, SHA-256 `84B1E224E5EFCA620ED8AF07B519B56A99A65EC4626FDD8E7A72E37C49014233`.
- Tests cover route projection, larger off-route tolerance, route-line turn inference, straight guidance, and repeated horizontal-edge stair candidates. The Mappls config task parsed the current pair successfully.
- Phone verification is still required for spoken turn timing, GPS map matching, rerouting, stair misses, and false possible-stairs alerts. This version does not classify ascending versus descending steps or count them.

---

## Live walking guidance update (2026-10-11)

- `testDebugUnitTest assembleDebug lintDebug` completed successfully with JDK 17 and Android SDK 35; 101 JVM tests passed with 0 failures and 0 errors. Lint reported 0 errors and 26 warnings.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, versionCode 11, versionName `5.1-live-guidance`, 126,902,047 bytes, SHA-256 `BDDA1FC5949491300D831948A95935115E988065A59D2BB2DA844DF22CBA4E71`. Android `apksigner verify` passed.
- The Mappls Gradle task parsed the current matching config pair, `app1791717620514i383691408.a.conf` and `.a.olf`. Runtime Mappls service access still requires phone testing.
- New tests cover far-obstacle filtering, connected-floor lateral guidance, scan recovery after two fresh path masks, floor visibility, and consecutive accurate off-route fixes. See the phone checklist in `README.md`; spoken timing, GPS behavior and real-world obstacle decisions remain unverified on a physical device.

---

For the current path/depth build, see [docs/PATH_DEPTH_TEST_PLAN.md](docs/PATH_DEPTH_TEST_PLAN.md). Earlier phase records below remain historical.

---

## Phase 1 — Build and automated testing (2026-10-08)

- **Build:** `build.ps1 testDebugUnitTest assembleDebug lintDebug` completed with `BUILD SUCCESSFUL` using the project-local Temurin JDK 17, Android SDK Platform 35, and Build Tools 35.
- **Unit tests:** 5 tests, 0 failures or errors: 4 `DecisionEngineTest` cases and 1 `PipelineMetricsTest` case.
- **Android Lint:** 0 errors, 8 warnings. Report: `app/build/reports/lint-results-debug.html`.
- **APK:** `app/build/outputs/apk/debug/app-debug.apk`, 54,334,486 bytes. SHA-256: `13ae5989c1036cec0ca248af20443e9b35df854a41e97dd44c9fcec3d49d162e`. `apksigner verify` passed. `aapt dump permissions` shows camera, vibration, and an AndroidX internal receiver permission; no Internet or network-state permission packaged.
- **Phase 1 source archive:** preserved at `releases/phase1/phase1-source.zip`.

---

## Phase 1 — Physical device testing

Phase 1 and Phase 2 checklists were manually completed on physical phones by the project owner. Individual item-level results are partially recorded:

**Items recorded as pass** (`releases/phase1/DEVICE_TEST_CHECKLIST.md`):
- Item 1: Fresh launch, grant camera permission — **pass**
- Item 2: Deny permission, retry — **PASS**
- Item 3: Permanently deny permission — **pass**
- Item 4: Person in left/center/right thirds — **pass**
- Item 5: Car, bus, truck, bicycle, motorcycle, dog — **Pass**
- Item 7: Medium center detection — **pass**
- Item 8: Large center detection (STOP) — **pass**
- Item 9: Left-only, right-only, both-side detections — **pass**

**Items still Pending** (no result recorded):
- Item 6: Nine-position box alignment + rotation
- Item 10: 10-second hold / frame backlog check
- Item 11: Nonurgent then STOP interruption
- Item 12: Pause/resume/Home/rotation/lock
- Item 13: Airplane mode offline operation
- Item 14: TalkBack/headphones/audio
- Item 15: 30-minute sustained run

**Supporting evidence:** Individual timestamps, device models, and Android version numbers were not recorded in the repository. The above pass/fail entries are the complete available record.

---

## Phase 2 — Build and automated testing (2026-10-09)

- **Build:** `build.ps1 testDebugUnitTest assembleDebug lintDebug` completed with `BUILD SUCCESSFUL`.
- **Unit tests:** 7 tests, 0 failures or errors: 6 `DecisionEngineTest` cases and 1 `PipelineMetricsTest` case. New tests cover wide-box center overlap and two-frame confirmation.
- **Android Lint:** 0 errors, 8 warnings. Report: `app/build/reports/lint-results-debug.html`.
- **APK:** `app/build/outputs/apk/debug/app-debug.apk`, 54,361,598 bytes, versionCode 2, versionName `2.0-demo`. SHA-256: `d8b137c10d8baf7f147d430dfec4b17981c89f3656fd905880c924e629bccf04`. `apksigner verify` passed. No Internet or network-state permission packaged.

---

## Phase 2 — Physical device testing

All 10 items in `PHASE2_TEST_CHECKLIST.md` remain **Pending**. No physical-device test has been recorded for Phase 2.

Outstanding Phase 2 items include:
- Camera timestamp clock-source verification (diagnostic candidate only until verified)
- Controlled-scene evaluation (10 examples per class across three regions and two lighting conditions)
- Two-frame confirmation and wide-box center-overlap behaviour on a real scene
- Threshold tuning based on measured device results
- Formal demo sign-off

---

## Phase 3 — Implementation status (2026-10-11)

### Code complete

| File | Status |
|---|---|
| `app/src/main/java/com/example/mobilityassist/Phase3State.kt` | Created |
| `app/src/main/java/com/example/mobilityassist/FrameAnalyzer.kt` | Created |
| `app/src/main/java/com/example/mobilityassist/AngleDetector.kt` | Created |
| `app/src/main/java/com/example/mobilityassist/SpeechHapticManager.kt` | Extended (`speak()`, `Phase3SpeechGate`, `SpeechPriority`); `warn()` unchanged |
| `app/src/main/java/com/example/mobilityassist/MainActivity.kt` | Extended (Camera ref, FrameAnalyzer, AngleDetector, torch control, Phase 3 UI) |
| `app/src/main/res/layout/activity_main.xml` | Updated (Phase 3 compact status row) |
| `app/src/main/res/values/strings.xml` | Updated (Phase 3 strings added) |
| `app/src/test/java/com/example/mobilityassist/Phase3SpeechTest.kt` | Created (11 tests) |
| `app/src/test/java/com/example/mobilityassist/FrameAnalyzerTest.kt` | Created (22 tests) |
| `app/src/test/java/com/example/mobilityassist/AngleDetectorTest.kt` | Created (24 tests) |
| `docs/PHASE_3_IMPLEMENTATION_PLAN.md` | Created |
| `docs/PHASE_3_CHECKLIST.md` | Created |
| `docs/PHASE_3_TEST_REPORT.md` | Created |
| `phase_3plan.md` | Created (root copy) |

Untouched files (Phase 3 policy): `DecisionEngine.kt`, `ObjectDetectorHelper.kt`, `OverlayView.kt`, `PipelineMetrics.kt`, `DecisionEngineTest.kt`, `PipelineMetricsTest.kt`, `AndroidManifest.xml`, `build.gradle.kts`.

### Automated test results (Phase 3 build) — 2026-10-11

**BUILD SUCCESSFUL** — `gradlew.bat testDebugUnitTest assembleDebug` completed with JDK 21 (Android Studio JBR) and Android SDK Platform 35 / Build Tools 35.

- **Unit tests: 69 tests, 0 failures, 0 errors.**
  - `DecisionEngineTest`: 6 tests — all pass (existing, no regression)
  - `PipelineMetricsTest`: 1 test — pass (existing, no regression)
  - `Phase3SpeechTest`: 11 tests — all pass (new)
  - `FrameAnalyzerTest`: 27 tests — all pass (new; 2 tests required a fix to `alternatingBuffer` helper after discovering that stride-8 sampling of a pixel-alternating checkerboard pattern produced zero variance; corrected to use sample-grid coordinates)
  - `AngleDetectorTest`: 24 tests — all pass (new)
- **APK:** `app/build/outputs/apk/debug/app-debug.apk`, **54,113,465 bytes (51.61 MB)**, generated 2026-10-11.
- **Note on test count:** Final count is 69, not 64 as estimated, because `FrameAnalyzerTest` grew to 27 tests during implementation.
- **Build environment:** JDK 21.0.6 (Android Studio JBR), Android SDK Platform 35, Build Tools 35.0.0, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21.
- **`gradle.properties` updated** with `org.gradle.java.home` pointing to Android Studio JBR to avoid Java 8 system JVM conflict.
- **`local.properties` updated** with correct `sdk.dir` path.

### Phase 3 physical-device testing

**NOT TESTED.** See `docs/PHASE_3_CHECKLIST.md` Sections 2–8 and `docs/PHASE_3_TEST_REPORT.md` for the full test matrix and session record template.

---

## Outstanding items across all phases

| Item | Phase | Priority | Status |
|---|---|---|---|
| Phase 3 build and automated tests | 3 | P0 | Pending (task 13) |
| Phase 3 device test — flashlight matrix | 3 | P0 | Pending — requires physical device |
| Phase 3 device test — angle guidance matrix | 3 | P0 | Pending — requires physical device |
| Phase 3 device test — obstruction matrix | 3 | P0 | Pending — requires physical device |
| Phase 3 speech priority matrix | 3 | P0 | Pending — requires physical device |
| Phase 3 performance baseline | 3 | P1 | Pending — requires physical device |
| Phase 2 controlled-scene evaluation | 2 | P0 | Carry-over — requires physical device |
| Phase 2 full device checklist | 2 | P0 | Carry-over — all 10 items pending |
| Phase 1 remaining items (6, 10–15) | 1 | P1 | Carry-over — requires physical device |
| Camera timestamp clock-source verification | 1/2 | P1 | Carry-over |
| Second-device coverage | 3 | P2 | Pending primary device completion |
| Formal demo sign-off | 3 | P0 | Blocked on all above |

**No phase has been formally demo-approved.** Physical device testing and a dated test record are required before any supervised demo may be authorised.

---

## v5.1 camera + in-app Mappls walking navigation (2026-10-11)

The v5.2 camera change was reverted to the v5.1 guidance implementation before adding navigation. The Mappls Gradle plugin parsed the locally supplied `.a.conf` and `.a.olf` files. The navigation build uses Mappls SDK 9.0.3 for the in-app map and its walking routing/search services. The license files and generated asset are ignored by Git and are not part of the public source commit.

Automated verification: `testDebugUnitTest assembleDebug lintDebug` passed with **93 JVM tests, 0 failures**, an assembled debug APK, and **0 lint errors**. The lint report has advisory warnings from existing dependencies and logging style. The final APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Runtime verification remains **pending**. No Android device was connected when `adb devices` was checked. The current Mappls license's package/signature match and API entitlements, GPS accuracy, route lookup, map attribution, turn timing, simultaneous camera/map performance, and speech priority must be checked on the intended phone. The short procedure is in `README.md` under “Phone test checklist.” A pedestrian route does not certify safe crossings, and the camera model cannot detect all hazards.

## v5.1 map/wall recovery build (2026-10-11)

- `testDebugUnitTest assembleDebug lintDebug` completed successfully: 96 JVM tests, 0 failures, 0 lint errors. Tests include broad-surface obstruction rules, ambiguity gating, and Mappls failure categories. These do not measure camera/model accuracy or prove Mappls runtime entitlement.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 127,343,019 bytes, versionCode 9, versionName `5.1-map-wall-recovery`, SHA-256 `796310B4F1CC336FCF144AEA5EED1BB4F61AD21BEFF3AAE807B1A235EF3CF4FB`. The packaged manifest includes camera, fine/coarse location and internet; the local Mappls configuration asset is present. Do not publish this APK without reviewing the license terms.
- Phone validation remains pending: `adb devices` found no attached device. Specifically verify fresh-install permission prompts; precise versus approximate grants; location acquisition indoors/outdoors; Mappls map tiles, suggestions, place details and walking route access with the installed APK's signing fingerprint; route preview/marker motion; camera-map coexistence; and wall warning latency/false alarms in the controlled scenarios listed in `README.md`.

## Mappls setup diagnostics (2026-10-11)

The supplied `.a.conf` and `.a.olf` pair in `app/` matches the user's copied source pair byte for byte, and a clean build confirms the Mappls Gradle plugin parsed both. Mappls runtime authorization and service provisioning still require Console/device validation. The previously optional project-local debug key was absent, while `build.ps1` would have created it on a future build; debug signing now consistently uses the machine's Android default keystore. `signingReport` shows `C:\Users\fawaz\.android\debug.keystore` with SHA-256 `79:38:B4:82:0D:28:E6:60:03:21:1E:22:5F:9F:67:61:C7:E0:00:95:2B:35:92:3B:BE:C0:D7:24:E9:17:5C:42`. This is different from the older MobilityAssist project's local debug key. The app now reports the Mappls operation and SDK code in service/access messages.

`clean testDebugUnitTest assembleDebug lintDebug` passed: 96 JVM tests, no failures, no lint errors. APK: `app/build/outputs/apk/debug/app-debug.apk`, versionCode 10, versionName `5.1-mappls-diagnostics`, 126,786,535 bytes, SHA-256 `1437D5630A68CDFE20EF9B340C6D0B8D4E86F29229FAE072A20ADAE5CB0BC472`. No phone is attached, so the Mappls Console's registered package/fingerprint, enabled methods, billing/quota state, and actual requests remain unverified.
