# Project State

## Current Phase

Phase 0 + Phase 1 are code-complete. Real-device auxiliary-camera validation has now produced the first vendor-filtered compatibility path on `experiment-snapcam-aux-identity`.

The project is still validating camera routing before the full Phase 2 camera UI is merged.

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
- DeviceCameraProfile and sanitized JSON export.
- Diagnostics UI and real-device reports.

### Auxiliary compatibility work validated on hardware

- Normal `com.omnicam.app` identity on the tested POCO M2 Pro exposes only rear main + front.
- Experimental `org.codeaurora.snapcam` identity exposes eight Camera2 IDs on that same ROM.
- The raw graph includes more Camera2 routes than physical lenses, proving raw Camera2 ID count cannot be shown as lens count.
- Direct Camera2 preview testing confirms the four rear routes `0`, `20`, `21`, and `22` open on the tested device; raw routes `61` and `100` do not open as independent direct cameras.
- Camera `61` is a logical multi-camera route containing physical members `0` and `20`.
- Camera `100` is treated as a duplicate/alternate vendor route rather than a separate physical lens.

### Useful-camera resolver

- Added `ValuableCameraResolver` to convert raw HAL routes into user-facing photographic lens routes.
- Excludes logical aggregators, depth-only/non-photo routes, and strong optical-metadata duplicate aliases.
- No POCO/Snapdragon numeric ID table is used.
- Recognizes both independently listed camera devices and physical cameras that must be routed through a logical camera.
- Default rear ordering is optical (wide-to-long focal length), not numeric camera ID order.
- Unit tests include a Qualcomm/vendor-alias-style graph and a standards-based logical/physical multi-camera graph.

### User lens layout

- Normal diagnostics now show resolved useful cameras by default; raw HAL routes are behind an explicit developer control.
- Users can enable/disable useful rear lenses.
- Users can reorder useful rear lenses.
- At least one rear lens remains enabled.
- User layout persists through AndroidX DataStore.
- Reset-to-default capability-derived ordering is available.

### Camera routing

- Direct Camera2 auxiliary preview path bypasses CameraX filtering.
- Standard Camera2 physical-via-logical preview routing is implemented with physical output configuration for API 28+ devices.
- Enumerated, useful, and successfully routable are intentionally separate states.

## In Progress

- Validate the new useful-camera resolver and lens layout on the POCO M2 Pro.
- Validate physical-via-logical routing on a device where auxiliary sensors are not independently listed.
- Expand real Snapdragon OEM/ROM coverage before calling the vendor compatibility path broadly proven.

## Known Issues / Intentional Limits

- The `org.codeaurora.snapcam` application identity remains an isolated experiment and is not the production OmniCam package decision.
- Package allowlisting behavior varies by OEM/ROM; a Snapdragon SoC alone does not guarantee Snapcam-identity auxiliary exposure.
- A metadata-distinct route can still fail session configuration; actual session validation remains the final routing authority.
- OEM-hidden/system cameras remain inaccessible unless the vendor exposes a permitted route.
- Custom HDR/Night/denoise/super-resolution and final production photo capture are not implemented yet.

## Architecture Decisions

- One production codebase; no Snapdragon/MediaTek/Exynos APK flavors.
- Capability-driven camera graph, never fixed numeric IDs.
- Raw Camera2 route count is not physical lens count.
- User-facing lens list is resolved from photographic value + optical uniqueness + routing capability.
- Logical aggregators are infrastructure, not user lenses.
- Strong vendor aliases are hidden by default.
- Physical members behind logical cameras are valid user lenses when they can be routed using standard Camera2 physical outputs.
- User enable/disable/order is a preference layer on top of capability resolution, not a replacement for capability detection.

## Tests / CI

Hosted CI continues to run:

- Gradle wrapper verification.
- camera-capability unit tests.
- Android lint.
- debug APK assembly.
- APK artifact publication.

## Next Phase

1. Install the useful-lens build on the tested POCO.
2. Confirm the default rear list resolves to four useful routes rather than six raw rear Camera2 IDs.
3. Verify enable/disable persists after restart.
4. Verify reorder persists after restart.
5. Test the same build on additional Snapdragon OEM/ROM combinations.
6. Test standards-based physical-via-logical routing on modern logical multi-camera hardware.
7. Promote the resolver/preferences architecture into the production camera flow before building the final iOS-style lens bar.
