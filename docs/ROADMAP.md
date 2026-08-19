# Roadmap

See `MASTER_BUILD_PROMPT.md` for the full engineering specification.

## Phase 0 — Project foundation

**Implementation: complete.**

Includes the real Gradle/Android project, wrapper, Compose foundation, navigation, CI, lint, tests, and architecture boundaries needed by the first feature.

## Phase 1 — Universal camera discovery

**Implementation: complete. Physical-device validation: pending.**

Implemented:

- Camera permission flow
- Universal public-camera enumeration
- Logical/physical camera graph
- Directly-listed vs logical-member distinction
- Optical lens classification
- Normalized capability model
- Diagnostics screen
- Sanitized JSON export
- Unit tests and synthetic device fixtures

### Phase 1 validation gate

Do not begin image-processing algorithms or claim auxiliary-lens capture support until discovery has been validated on real devices.

Use `docs/PHASE1_DEVICE_TEST.md` and add results to `docs/DEVICE_COMPATIBILITY.md`.

## Phase 2 — Basic camera experience

After Phase 1 hardware validation:

- CameraX preview
- lifecycle-safe camera open/close
- reliable still capture
- MediaStore saving
- tap-to-focus
- exposure compensation
- pinch zoom
- orientation handling
- simple iOS-inspired camera UI
- latest-capture thumbnail

No fake HDR/Night/Super Resolution controls.

## Phase 3 — Auxiliary lenses

After the basic camera path is reliable:

- capability-driven lens selector
- logical/physical session strategy
- public physical-camera routing where supported
- continuous zoom
- crossover strategy
- active physical-camera diagnostics
- graceful handling of restricted lenses

Later phases for formats/Pro, video, OEM Extensions, custom HDR, Night, denoise, super-resolution, semantic processing, portrait, advanced modes, tuning, and performance remain defined in `MASTER_BUILD_PROMPT.md`.
