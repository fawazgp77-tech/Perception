# Preserved Phase 1 release

[`phase1-source.zip`](phase1-source.zip) preserves the original Phase 1 source project, including the model asset. The original locally saved development APK was 54,334,486 bytes (SHA-256 `13ae5989c1036cec0ca248af20443e9b35df854a41e97dd44c9fcec3d49d162e`); APK binaries are not committed to Git.

When a physical phone is available, extract the source zip into a separate directory, build it with Android Studio, then install its `app/build/outputs/apk/debug/app-debug.apk`. Enable an offline English TTS voice first. Follow the [Phase 1 physical-device checklist](../../DEVICE_TEST_CHECKLIST.md) and record actual results. The Phase 1 warnings say “Keep right” and “Keep left”; those were changed to descriptive warnings in Phase 2.

For an independent Phase 1 rebuild, extract the source zip into a separate directory, open it in Android Studio, and build there. Rebuilding in the main project directory would produce Phase 2 code.
