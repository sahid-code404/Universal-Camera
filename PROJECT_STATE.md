# Project State

## Current Phase

Phase 0 + Phase 1 are code-complete and validated on the first real hardware target. Phase 2 camera routing/capture is active on `phase-2-camera-ui-updater`; HEIF, multi-aspect preview, RAW/DNG, and first manual Pro controls are now implemented and require physical-device validation.

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
- Rear/front switching.
- Tap-to-focus with AF/AE metering region support where exposed.
- Exposure compensation control where exposed.
- Pinch zoom plus zoom slider.
- Flash Off / Auto / On / Torch where flash is available.
- Aspect ratios: 1:1, 3:2, 4:3, 16:9, and 2:1.
- Viewfinder aspect/layout and TextureView transforms avoid non-uniform stretching.
- Camera lifecycle release/rebind prevents frozen preview after leaving for Gallery and returning.
- Latest-capture thumbnail opens the captured item in an external gallery/viewer.
- Camera diagnostics and lens manager remain accessible from the camera UI.

### Photo formats

- JPEG still capture and MediaStore saving to `DCIM/OmniCam`.
- Native Camera2 HEIC is preferred when the active route/session exposes `ImageFormat.HEIC`.
- If native HEIC is absent, OmniCam captures `YUV_420_888` and encodes a real HEIF with AndroidX `HeifWriter` / the device HEVC encoder.
- JPEG remains the final compatibility fallback for HEIF.
- Software-HEIF YUV packing uses bulk row copies where the plane layout permits it.
- HEIF/DNG file finalization now runs outside the shutter-critical path: the camera returns to an available shutter after the frame is safely acquired while encode/publish finishes in the background.
- Compression quality remains user-selectable for HEIF/JPEG.

### RAW / DNG

- Added `PhotoOutputFormat.DNG`.
- RAW DNG is offered only when the selected valuable route advertises RAW, exposes `RAW_SENSOR` output, and is independently openable as a Camera2 device.
- DNG capture uses the real RAW `Image` plus `TotalCaptureResult` through Android `DngCreator`.
- Unsupported routes fall back rather than pretending a processed image is RAW.
- DNG uses native sensor dimensions rather than the processed-photo aspect crop.

### Pro controls

- Added capability-gated manual sensor mode.
- Manual ISO uses the lens-reported sensitivity range.
- Manual shutter uses the lens-reported exposure-time range with logarithmic UI control.
- Manual focus uses lens focus distance in diopters when the lens exposes it; infinity is represented by 0 diopters.
- Manual mode disables AE/AF and applies sensor ISO/exposure/frame-duration requests.
- Auto white balance remains enabled in this first Pro implementation.
- Flash Auto/On are not used while AE is disabled; Torch remains possible where supported.

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

The Phase 2.3 code path has completed a green hosted CI run:

- Gradle wrapper verification: passing.
- camera-capability unit tests: passing.
- Android lint: passing.
- debug APK assembly: passing.
- update APK packaging: passing.
- artifact publication: passing.

## In Progress

- Measure real-device HEIF shutter latency separately from background HEVC completion time.
- Validate back-to-back HEIF captures while one or more files are still finalizing.
- Validate DNG capture/readability on each RAW-capable independently openable lens.
- Validate manual ISO/shutter/focus behavior and range boundaries on real hardware.
- Validate portrait/landscape orientation for HEIF/JPEG and RAW editor interpretation for DNG.
- Validate physical-via-logical capture on a standards-based modern multi-camera device.
- Expand Snapdragon/OEM coverage before treating vendor-filtered auxiliary access as broadly proven.

## Known Issues / Intentional Limits

- The `org.codeaurora.snapcam` application identity is still a temporary compatibility identity for the vendor-filtered hardware test path, not the final OmniCam production package decision.
- The first APK signed with the stable development key may require uninstalling an older experiment signed by an ephemeral debug key; later development builds update in place.
- Android still displays its normal package-install confirmation during sideloaded updates.
- Package allowlisting behavior varies by OEM/ROM; a Snapdragon SoC alone does not guarantee Snapcam-identity auxiliary exposure.
- A metadata-distinct route can still fail Camera2 session configuration; actual session creation remains the final authority.
- RAW through physical-via-logical-only routes is not enabled yet because DNG metadata/result association needs separate physical-result validation.
- Software HEIF still takes device-dependent time to finish HEVC encoding, but that work no longer blocks the shutter for the full save duration.
- Manual white balance, focus peaking, histogram/zebras, video, Ultra HDR, OEM Extensions, custom HDR, Night, denoise, and super-resolution remain later work.

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
- A requested file format is distinct from the actual pipeline; unsupported formats must report/fallback rather than masquerade as another format.
- Development signing and production signing are intentionally separate security domains.

## Next Phase

1. Physical-test Phase 2.3 HEIF latency, DNG, and Pro controls.
2. Add manual white balance, histogram/zebras, and focus peaking after manual sensor behavior is validated.
3. Add robust burst/bracketing primitives needed by custom HDR/Night.
4. Add video with H.264/HEVC capability-driven profiles.
5. Add Ultra HDR / gain-map support where Android/device capabilities permit it.
6. Begin computational HDR/Night/denoise only after the capture primitives are stable.
