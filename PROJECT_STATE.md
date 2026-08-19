# Project State

## Current Phase

Phase 0 + Phase 1 implementation is **code-complete** on `phase-0-1-foundation`.

Real-device validation is still required before beginning Phase 2.

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

## In Progress

- Physical-device validation of Phase 1 discovery results.

## Known Issues / Intentional Limits

- No physical Android device has been validated yet.
- A physical camera ID disclosed by a logical camera is not assumed to be independently openable.
- OEM-hidden/system cameras remain inaccessible by design.
- Camera preview and still capture are not part of Phase 1 and are not implemented yet.
- Auxiliary-lens capture/session routing is intentionally deferred to Phase 3 after discovery is validated.
- OEM Camera Extensions and custom computational photography are intentionally deferred.
- HEIF/Ultra HDR output policy is not implemented yet.

## Device-Specific Findings

None yet. Populate `docs/DEVICE_COMPATIBILITY.md` only from real-device reports/tests.

## Architecture Decisions

- Single production application across SoCs; no chipset product flavors.
- Hardware capability is the source of truth.
- Hybrid CameraX + Camera2 direction.
- Camera2 powers low-level capability discovery.
- Auxiliary cameras are supported only when exposed through public Android APIs.
- Android 9 / API 28 is the minimum baseline.
- Physical logical-camera membership and direct openability are separate concepts.
- Manual dependency injection is sufficient for Phase 1; revisit DI framework choice when the graph grows.

## Tests / CI

A full hosted CI run has successfully completed:

- Phase 1 unit tests: passing.
- Android lint: passing.
- Debug APK assembly: passing.
- APK artifact publication: passing.
- Gradle wrapper execution: passing.

Final Phase 0/1 documentation cleanup is followed by one final CI run before device handoff.

## Next Phase

1. Install the debug APK on physical Android devices.
2. Grant camera permission.
3. Verify every publicly visible camera/lens shown by diagnostics.
4. Export `omnicam-device-report.json`.
5. Add validated results to `docs/DEVICE_COMPATIBILITY.md`.
6. Fix discovery/classification issues found on hardware.
7. Begin Phase 2 — basic CameraX preview and reliable still capture — only after the discovery layer is trustworthy.
