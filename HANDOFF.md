# Engineering Handoff

## What this repository is

This repository is the initial source-tree scaffold for OmniCam. It intentionally contains no fake camera implementation and no placeholder user-visible features.

## Immediate objective

Implement **Phase 0 + Phase 1** only.

### Phase 0 — Project Foundation

- Create the real Gradle Android project.
- Pin current stable Android/Compose/CameraX dependencies.
- Kotlin + Jetpack Compose.
- Dependency injection.
- Navigation.
- CI, lint, tests, formatting.
- Preserve the module boundaries where useful; merge modules if a boundary would only add architecture overhead.

### Phase 1 — Universal Camera Discovery

Implement:

- CameraManager enumeration.
- CameraCharacteristics extraction.
- Logical camera discovery.
- Physical camera discovery.
- Lens-role classification from optical characteristics, not camera IDs.
- Capability domain models.
- DeviceCameraProfile.
- Camera diagnostics screen.
- Sanitized JSON capability export.
- Fake device profiles and unit tests.

## Non-negotiable rules

1. One app, no Snapdragon/MediaTek/Exynos/Tensor product flavors.
2. Hardware capability is the source of truth.
3. Never hardcode camera IDs as lens roles.
4. Never claim access to an OEM-hidden/system camera.
5. No root, hidden API bypasses, or signature-permission hacks.
6. No fake HDR/Night/Super Resolution switches before those engines exist.
7. CameraX for reliable high-level camera flows; Camera2 when low-level access is actually required.
8. Runtime capability checks drive UI and capture strategy.
9. Every advanced feature needs graceful fallback behavior.
10. Update `PROJECT_STATE.md` after each meaningful milestone.

## Minimum platform direction

Initial baseline: Android 9 / API 28 or newer.

Reason: the public logical/physical multi-camera APIs needed for serious auxiliary-lens support start at API 28.

Target/compile SDK and dependency versions should be pinned to stable versions available when implementation starts.

## Definition of done for Phase 1

The app must compile and run, request camera permission, enumerate all cameras publicly visible to the app, detect logical/physical relationships, normalize capabilities, classify lenses with confidence, show diagnostics, export a sanitized JSON report, and pass tests/lint.
