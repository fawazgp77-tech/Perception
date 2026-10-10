# Phase 2 — safer obstacle guidance

Phase 2 builds on the preserved Phase 1 camera and detection pipeline. Its goal is to make warnings less likely to imply that an unobserved route is safe. It remains a supervised, on-device prototype; it does not detect every hazard or provide navigation.

## Implemented

1. Replace “Keep right” and “Keep left” with “Obstacle on left” and “Obstacle on right.” If both sides contain detections, say “Obstacles on both sides.” Center warnings retain priority.
2. Treat a near or very-near wide object as a center hazard when it overlaps the center third by at least 10% of the image width and at least 25% of its own box width, even when the box center falls to one side. These values are provisional heuristics.
3. Require two matching analyzed frames no more than 1.2 seconds apart before issuing any nonurgent warning. Clear confirmation after a blank frame, a changed warning, STOP, or a camera-session change. A center STOP remains immediate.
4. Keep the existing two-second speech cooldown, urgent two-pulse vibration, fully local inference, camera lifecycle handling, and debug performance readout. The screen states that silence does not mean a clear path.

## Acceptance and remaining work

The automated gate is a successful Android build, unit tests for the new policy and existing cooldown, lint with no errors, APK signature verification, and confirmation that the packaged app has no network permission. [VERIFICATION.md](VERIFICATION.md) records those results.

Physical-device validation is still pending by the user's choice. First build the archived [Phase 1 source](releases/phase1/README.md) and run the [Phase 1 checklist](DEVICE_TEST_CHECKLIST.md). Then install the current Phase 2 APK and use the [Phase 2 checklist](PHASE2_TEST_CHECKLIST.md) to compare spoken wording, center overlap, confirmation behavior, STOP priority, box alignment, latency, and offline operation. Do not infer measured safety or performance from the unit tests.
