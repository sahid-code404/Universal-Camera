# Project State

## Current Phase

Phase 0 + Phase 1 implementation is **code-complete** on `phase-0-1-foundation`.

Physical-device validation has reproduced a vendor-specific auxiliary-camera exposure behavior on Xiaomi POCO M2 Pro. A separate experiment branch now has a CI-green CameraX live-preview + in-memory capture test for the newly exposed IDs. Real-device session validation is the remaining gate before the production Phase 2/3 routing architecture is finalized.

## Completed

### Phase 0 — Project Foundation

- Real Android Gradle project.
- Gradle 9.5 wrapper committed, including wrapper JAR and launch scripts.
- Kotlin/Android project configured for Android 9+ (`minSdk 28`).
- Android 17 / API 37 compile and target configuration.
- Jetpack Compose app shell.
- Navigation foundation.
- Explicit application dependency container with constructor-injected scanner boundary.
- GitHub Actions CI.
- Unit-test, lint, debug-APK assembly, and APK artifact steps.
- Architecture ADRs for manual Phase 1 DI and physical-camera discovery semantics.

### Phase 1 — Universal Camera Discovery

- Public `CameraManager.cameraIdList` enumeration.
- `CameraCharacteristics` extraction.
- Logical multi-camera detection.
- Physical camera ID discovery.
- Distinction between directly listed/openability-candidate camera IDs and physical members disclosed only by a logical camera.
- Safe physical-camera characteristic lookup with graceful failure.
- 35mm-equivalent focal-length estimation from public focal-length and sensor-size metadata.
- Confidence-based lens classification without hard-coded camera IDs.
- `DeviceCameraProfile`, `CameraDescriptor`, `LogicalCameraGroup`, and related capability models.
- RAW/manual/burst/reprocessing/depth/OIS/flash/zoom/ISO/exposure/AE/FPS/output-format metadata collection.
- Camera permission UX.
- Compose diagnostics UI.
- Sanitized JSON device-report export.
- Synthetic budget, logical-triple-camera, and RAW/manual device fixtures.
- Lens classification and capability-fixture unit tests.
- Camera1 cross-check and caller-identity exposure diagnostics.

### Real-device finding — POCO M2 Pro

Normal OmniCam identity (`com.omnicam.app`) exposes only 2 Camera2 IDs, 2 Camera1 devices, and no logical multi-camera group on the tested API 36 ROM.

An isolated Snapcam-compatible client identity (`org.codeaurora.snapcam`) on the same phone exposes 8 Camera2 IDs, 6 Camera1 devices, and logical camera `61` with physical members `0` and `20`. Camera `21` reports ~15.6 mm equivalent focal length and is classified ULTRA_WIDE.

This confirms that caller/client identity materially changes camera visibility on this tested ROM. It does **not** imply the same behavior across Xiaomi, Qualcomm, or Android devices generally.

### Live auxiliary validation build

The isolated experiment now includes:

- CameraX `PreviewView` live preview.
- direct Camera2-ID selection through CameraX Camera2 interop.
- buttons for every enumerated rear Camera2 ID.
- requested-vs-actual camera ID reporting.
- in-memory `ImageCapture` probe that closes the frame without saving media.
- per-ID bind/capture failure reporting.

Hosted CI for the live-lens build passes unit tests, Android lint, APK assembly, and artifact upload.

## In Progress

- Run the live-lens APK on POCO M2 Pro and validate preview + capture for IDs `21`, `22`, `20`, `0`, `100`, and `61`.
- Determine which enumerated POCO IDs are truly session/capture-capable and what optical role each usable ID represents.

## Known Issues / Intentional Limits

- An enumerated ID is not considered usable until an actual preview/session/capture succeeds on hardware.
- Camera `22` is not yet labeled as macro; metadata alone is insufficient.
- The Snapcam-compatible application ID is an isolated compatibility experiment and is **not** accepted as OmniCam's production identity.
- OEM-hidden/system cameras remain inaccessible when the vendor stack does not expose them to the active caller identity.
- Production still-photo UI and storage capture are not implemented yet.
- OEM Camera Extensions and custom computational photography are intentionally deferred.
- HEIF/Ultra HDR output policy is not implemented yet.

## Device-Specific Findings

See `docs/DEVICE_COMPATIBILITY.md`.

## Architecture Decisions

- Single production application across SoCs; no chipset product flavors.
- Hardware capability is the source of truth.
- Hybrid CameraX + Camera2 direction.
- Camera2 powers low-level capability discovery.
- Auxiliary cameras are supported only when exposed to the active client through the Android/vendor camera stack.
- Android 9 / API 28 is the minimum baseline.
- Physical logical-camera membership, enumeration visibility, and actual session usability are separate concepts.
- Vendor caller-identity filtering is now a first-class compatibility concern, but production identity strategy remains unresolved pending broader testing.
- Manual dependency injection is sufficient for current work; revisit DI framework choice when the graph grows.

## Tests / CI

The base Phase 0/1 hosted pipeline has passed Gradle wrapper validation, unit tests, Android lint, debug APK assembly, and artifact publication.

The isolated Snapcam identity enumeration experiment is green, and the CameraX live-lens/session experiment is also green: unit tests, lint, APK assembly, and artifact publication all pass.

## Next Phase

1. Run the live lens test on POCO M2 Pro.
2. Attempt preview + in-memory capture on each exposed rear Camera2 ID.
3. Record successful/failed IDs and visually identify their true lens roles.
4. Add validated routing rules to the compatibility model without hard-coding assumptions globally.
5. Test a second OEM/device class to avoid overfitting the architecture to POCO/Xiaomi behavior.
6. Begin the production Phase 2 CameraX preview/still-capture path and Phase 3 auxiliary routing with the validated compatibility strategies.
