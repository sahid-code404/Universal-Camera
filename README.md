# OmniCam

OmniCam is an experimental universal computational-camera project for Android.

## Product direction

- One Android application / one codebase across Snapdragon, MediaTek, Tensor, Exynos, Unisoc, and other Android-compatible devices.
- Capability-driven runtime adaptation rather than chipset-specific builds.
- Access to auxiliary cameras that the device exposes to normal third-party apps through public Android camera APIs.
- iOS-inspired simplicity and polish without copying Apple assets or proprietary UI.
- Pixel-inspired computational-photography principles implemented independently.
- Deep user-configurable controls are planned for HDR, denoise, sharpening, tone mapping, upscaling, formats, RAW, video, and manual capture.
- Hybrid CameraX + Camera2 architecture.
- No root, hidden-API bypasses, private OEM APIs, or fake features.

## Current status

**Phase 0 + Phase 1 implementation is complete in the development branch and awaiting validation on physical Android devices.**

Implemented today:

- Real Android/Gradle project with a committed Gradle wrapper.
- Android 9 / API 28 minimum support and Android 17 / API 37 compile/target configuration.
- Jetpack Compose application shell and navigation foundation.
- Explicit dependency container for the Phase 1 scanner boundary.
- Camera permission flow.
- Camera2 `CameraManager` enumeration.
- Logical/physical camera graph discovery.
- A strict distinction between camera IDs directly listed by Android and physical members disclosed through logical-camera metadata.
- Optical lens-role classification using estimated 35mm-equivalent focal length instead of hard-coded camera IDs.
- Normalized `DeviceCameraProfile` domain model.
- Capability collection for RAW, manual sensor, burst, OIS, flash, reprocessing, depth, zoom, exposure, ISO, FPS ranges, formats, and output sizes where public metadata is available.
- Compose diagnostics screen.
- Sanitized JSON capability-report export.
- Synthetic multi-device fixtures and lens-classifier unit tests.
- GitHub Actions unit-test, lint, APK-build, and artifact pipeline.

Not implemented yet:

- Live camera preview/photo capture (Phase 2).
- Independent auxiliary-lens capture/session routing (Phase 3).
- Pro capture, HEIF/Ultra HDR policy, video, OEM Extensions, custom HDR/Night/denoise/super-resolution processing, or Pixel-like computational pipelines.

These are intentionally not exposed as fake user-facing features.

## Build

Requirements:

- JDK 17
- Android SDK platform 37 installed

From the repository root:

```bash
./gradlew :app:assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run the full local verification set with:

```bash
./gradlew :camera-capability:testDebugUnitTest lintDebug :app:assembleDebug
```

## Physical-device validation

Before Phase 2, install the Phase 1 debug APK on real phones and export `omnicam-device-report.json` from the diagnostics screen.

See `docs/PHASE1_DEVICE_TEST.md`.

## Engineering references

Read these before major changes:

1. `HANDOFF.md`
2. `MASTER_BUILD_PROMPT.md`
3. `PROJECT_STATE.md`
4. `docs/ROADMAP.md`
5. `docs/adr/`

## Important product constraint

OmniCam can only access cameras exposed to third-party applications by Android/OEM camera APIs. A physical sensor hidden behind privileged/system-only permissions cannot be promised or bypassed.
