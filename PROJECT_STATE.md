# Project State

## Current Phase

Phase 0 + Phase 1 are code-complete and validated on the first real hardware target. Phase 2 is now **code-complete on `phase-2-camera-ui-updater`** and is awaiting physical-device camera-quality/UX validation.

## Completed

### Phase 0 — Project Foundation

- Real Android Gradle project and committed Gradle wrapper.
- Android 9+ (`minSdk 28`), Android 17/API 37 compile + target.
- Jetpack Compose/navigation foundation.
- GitHub Actions unit-test, lint, APK assembly, and artifact publication.

### Phase 1 — Universal Camera Discovery

- Camera2 public ID enumeration and CameraCharacteristics extraction.
- Logical/physical camera graph discovery.
- Camera1/concurrent-camera cross-checks.
- Optical lens classification without hard-coded camera IDs.
- `DeviceCameraProfile` and sanitized JSON export.
- Diagnostics UI and real-device reports.

### Auxiliary compatibility work validated on hardware

- Normal `com.omnicam.app` identity on the tested POCO M2 Pro exposes only rear main + front.
- Experimental `org.codeaurora.snapcam` identity exposes eight Camera2 IDs on that same ROM.
- Raw Camera2 route count is not treated as physical lens count.
- Direct Camera2 preview testing confirms useful rear routes `0`, `20`, `21`, and `22` open on the tested device.
- Raw routes `61` and `100` do not open as independent direct cameras.
- Camera `61` is a logical multi-camera route containing physical members `0` and `20`.
- Camera `100` is treated as a duplicate/alternate vendor route rather than a separate physical lens.

### Useful-camera resolver

- `ValuableCameraResolver` converts raw HAL routes into user-facing photographic lens routes.
- Excludes logical aggregators, depth-only/non-photo routes, and strong optical-metadata duplicate aliases.
- No POCO/Snapdragon numeric ID table is used.
- Supports independently listed Camera2 devices and standard physical-via-logical routes.
- Default lens ordering is optical/capability-derived rather than numeric ID order.
- Unit tests cover vendor-alias and standards-based logical/physical multi-camera graphs.

### User lens layout

- User-facing UI uses resolved useful lenses rather than every raw HAL ID.
- Users can enable/disable useful rear/front lenses.
- Users can reorder lenses.
- At least one lens per populated facing remains enabled.
- Lens layout persists with AndroidX DataStore.
- Reset-to-default capability-derived ordering is available.

### Phase 2 — Camera Experience

- Full-screen live Camera2 viewfinder.
- Useful-lens selector integrated into the main camera UI.
- Exact direct Camera2 routing for vendor-exposed auxiliary cameras.
- Standard physical-via-logical output routing for compliant logical multi-camera devices.
- JPEG still capture using a preview + ImageReader Camera2 session.
- JPEG saving through MediaStore to `DCIM/OmniCam` on modern Android.
- Rear/front switching.
- Tap-to-focus with AF/AE metering region support where exposed.
- Exposure compensation control where exposed.
- Pinch zoom plus zoom slider.
- Flash Off / Auto / On / Torch where flash is available.
- 4:3 and 16:9 capture selection.
- Latest-capture thumbnail.
- Orientation metadata handling for JPEG capture.
- Camera diagnostics and lens manager remain accessible from the camera UI.

### Development update channel

- Added a stable **development-only** signing certificate shared by CI hardware-test builds.
- The development signing certificate is intentionally public and must never be used for production/Play release signing.
- CI assigns monotonically increasing development version codes.
- Successful pushes to `phase-2-camera-ui-updater` publish a `dev-latest` prerelease APK.
- OmniCam checks the public `dev-latest` channel at startup.
- When a newer development version exists, OmniCam can download it and hand it to Android's package installer.
- Android's unknown-app-source permission is requested only when the user chooses to install a downloaded development update.
- Production updater/signing policy remains a later release-engineering decision.

## CI Status

The Phase 2 code path has completed a green hosted CI run:

- Gradle wrapper verification: passing.
- camera-capability unit tests: passing.
- Android lint: passing.
- debug APK assembly: passing.
- update APK packaging: passing.
- artifact publication: passing.

## In Progress

- Physical-device validation of Phase 2 preview/capture behavior.
- Validate JPEG orientation on portrait/landscape captures.
- Validate focus, EV, zoom, flash, lens switching, and MediaStore output on real hardware.
- Validate development APK update-over-install behavior.
- Validate physical-via-logical capture on a standards-based modern multi-camera device.
- Expand Snapdragon/OEM coverage before treating vendor-filtered auxiliary access as broadly proven.

## Known Issues / Intentional Limits

- The `org.codeaurora.snapcam` application identity is still a temporary compatibility identity for the vendor-filtered hardware test path, not the final OmniCam production package decision.
- The first APK signed with the new stable development key may require uninstalling an older experiment that was signed by an ephemeral debug key. After that migration, later development builds should update in place.
- Android still displays its normal package-install confirmation during sideloaded updates.
- Package allowlisting behavior varies by OEM/ROM; a Snapdragon SoC alone does not guarantee Snapcam-identity auxiliary exposure.
- A metadata-distinct route can still fail Camera2 session configuration; actual session creation remains the final authority.
- OEM-hidden/system cameras remain inaccessible unless the vendor exposes a permitted route.
- Video, HEIF/Ultra HDR, RAW production flow, Pro mode, OEM Extensions, custom HDR, Night, denoise, and super-resolution are later phases.

## Architecture Decisions

- One production codebase; no Snapdragon/MediaTek/Exynos APK flavors.
- Capability-driven camera graph, never fixed numeric IDs.
- Raw Camera2 route count is not physical lens count.
- User-facing lens list is resolved from photographic value + optical uniqueness + routing capability.
- Logical aggregators are infrastructure, not user lenses.
- Strong vendor aliases are hidden by default.
- Physical members behind logical cameras are valid user lenses when they can be routed using standard Camera2 physical outputs.
- User enable/disable/order is a preference layer on top of capability resolution.
- Direct Camera2 remains available when CameraX filters otherwise usable vendor auxiliary IDs.
- Development signing and production signing are intentionally separate security domains.

## Next Phase

1. Install the Phase 2 stable-development-signed APK on physical hardware.
2. Complete `docs/PHASE2_DEVICE_TEST.md`.
3. Verify the immediate `dev-latest` update path.
4. Fix any real-device capture/orientation/session issues found.
5. Validate on additional OEM/SoC combinations.
6. Begin formats/Pro/video work only after basic photo capture is trustworthy.
