# OmniCam — Master Build Specification

## Mission

Build a production-quality universal Android computational camera that combines:

- a simple, highly responsive iOS-inspired camera experience,
- independently implemented Pixel-inspired computational-photography principles,
- professional manual controls,
- deep user configurability,
- serious auxiliary-lens support,
- one adaptive application build across Android device families.

This is not an Apple Camera clone and must not copy Apple assets, source code, or branding. It must not copy Google's proprietary Pixel camera algorithms, models, binaries, assets, or source code.

## Universal build requirement

There must be one source tree and one production application across Snapdragon, MediaTek, Exynos, Tensor, Unisoc, and other Android-compatible SoCs.

Do not introduce chipset-specific APK/product flavors.

Runtime capability discovery is the source of truth.

## Platform direction

- Kotlin
- Jetpack Compose
- CameraX for high-level reliable flows
- Camera2 for low-level advanced access
- Coroutines + Flow
- DataStore
- Room where appropriate
- MediaStore for media storage
- NDK/C++ only for processing kernels justified by profiling
- Vulkan/GPU compute only when justified, with safe fallback
- minSdk initially API 28
- target/compile against the current stable Android SDK at implementation time

## Camera architecture

```text
Android Camera Framework
        ↓
Camera HAL
        ↓
CameraX / Camera2
        ↓
Capability Discovery
        ↓
DeviceCameraProfile
        ↓
CaptureStrategyResolver
        ↓
ProcessingStrategyResolver
        ↓
Device-Adaptive UI
```

Never drive behavior from chipset marketing names when public capabilities can answer the question.

## Auxiliary-camera requirements

Enumerate every camera visible through public Android APIs and inspect logical/physical relationships.

Attempt to identify:

- ultrawide
- main/wide
- telephoto
- long telephoto/periscope
- macro
- monochrome
- front cameras
- depth/auxiliary cameras where photographically useful

Never assume camera IDs map to specific lenses.

Classify lenses using focal length, sensor dimensions, field of view, facing, logical grouping, zoom range, and other exposed optical metadata. Store classification confidence.

If an OEM hides a physical sensor from third-party apps, do not bypass the restriction. Diagnostics should clearly report that only publicly exposed cameras can be used.

## Capability discovery

Build normalized models for camera/lens characteristics including where available:

- camera ID
- lens facing
- logical camera membership
- physical camera IDs
- focal lengths
- sensor dimensions
- pixel/active array sizes
- aperture
- minimum focus distance
- OIS/EIS
- flash
- RAW/YUV/JPEG/HEIF/Ultra HDR
- manual sensor controls
- burst/reprocessing
- zoom ratios
- hardware level
- FPS/high-speed configurations
- output sizes
- dynamic range
- stabilization
- noise reduction/edge/tonemap modes
- face/depth capabilities
- exposure/sensitivity ranges
- AF/AE/AWB modes
- camera extensions

Create a normalized `DeviceCameraProfile` that drives the rest of the app.

## Lens switching and zoom

Build a LensSelectionEngine and continuous ZoomController.

Lens shortcut values are generated from actual hardware rather than hardcoded globally.

Prefer OEM logical-camera zoom/fusion when it performs well. Support direct physical-camera targeting where public APIs, stream combinations, and device behavior permit it.

## User experience

Default camera UI should be simple, clean, fast, and one-hand friendly.

Main eventual modes:

- Slo-mo
- Video
- Photo
- Portrait
- Night
- Pro

Do not display unfinished modes.

Advanced controls live in secondary settings rather than cluttering the main viewfinder.

## Image output

Capability-aware eventual support:

- JPEG
- Ultra HDR JPEG
- HEIF/HEIC
- RAW/DNG
- RAW + JPEG
- RAW + processed output

Preferred-format setting should support HEIF with configurable JPEG fallback when HEIF is unavailable.

## Computational processing

Support three conceptual strategies:

- AUTO
- OEM
- CUSTOM

AUTO chooses the best available path using device capability, scene, latency, thermal state, and user preferences.

CUSTOM processing is independently implemented and modular:

```text
Sensor Frames
  ↓
Frame Selection
  ↓
Metadata Normalization
  ↓
Motion Estimation / Alignment
  ↓
Ghost Detection
  ↓
HDR / Exposure Fusion
  ↓
Temporal + Spatial Denoise
  ↓
RAW/Demosaic path where needed
  ↓
White Balance + Color Transform
  ↓
Local Tone Mapping
  ↓
Optional Semantic Adjustments
  ↓
Optional Multi-frame Super Resolution
  ↓
Sharpening / Texture
  ↓
Output Encoding
```

No UI control may exist unless the feature actually works.

## Eventual configurable controls

Where meaningful and implemented:

- processing engine
- quality mode
- HDR Off/Auto/On
- HDR strength
- highlight recovery
- shadow recovery
- local contrast
- deghosting
- denoise
- temporal denoise
- sharpening
- texture/microcontrast
- upscaling
- true multi-frame super resolution
- color profile
- saturation/vibrance
- warmth/tint
- face processing
- output format
- format fallback
- JPEG quality
- RAW
- Ultra HDR
- resolution
- video resolution/FPS/codec/dynamic range/stabilization
- ISO
- shutter speed
- focus
- white balance
- AE/AF lock
- histogram
- zebra
- focus peaking
- grids/level

Do not expose unsupported controls.

## Feature combinations

Never assume individually available features work simultaneously.

Resolve combinations such as resolution + FPS + HDR + stabilization + codec dynamically and expose supported/unsupported/conditional UI states.

## Performance

Priorities:

1. camera reliability
2. no lost media
3. correct auxiliary-lens behavior
4. responsive preview/shutter
5. image quality
6. multi-device compatibility
7. graceful fallback
8. thermal/battery behavior
9. customization
10. polish

Use bounded frame queues, deterministic resource closing, memory safeguards, thermal adaptation, structured concurrency, and profiling.

## Module direction

```text
app/
core/
core-ui/
core-model/
camera-api/
camera-camerax/
camera-camera2/
camera-capability/
camera-extensions/
camera-video/
processing-api/
processing-core/
processing-native/
processing-hdr/
processing-denoise/
processing-superres/
processing-color/
storage/
settings/
feature-camera/
feature-settings/
feature-gallery/
feature-pro/
benchmark/
testing/
```

These are intended boundaries, not mandatory architecture theatre. Merge a module when it adds no meaningful separation.

## Roadmap

### Phase 0 — Project Foundation
Gradle project, Compose, DI, navigation, module boundaries, CI, lint, tests, docs.

### Phase 1 — Universal Camera Discovery
CameraManager enumeration, CameraCharacteristics, logical/physical cameras, lens classification, DeviceCameraProfile, diagnostics, sanitized JSON export, fake device-profile tests.

### Phase 2 — Basic Camera Experience
Preview, lifecycle, photo capture, MediaStore, focus, exposure compensation, pinch zoom, rotation, simple UI, thumbnail.

### Phase 3 — Auxiliary Lenses
Dynamic lens selector, logical/physical strategies, continuous zoom, crossover, active physical camera diagnostics, graceful restrictions.

### Phase 4 — Formats + Pro Capture
JPEG/Ultra HDR/RAW/HEIF strategy, fallbacks, resolution, manual ISO/shutter/focus/WB, histogram, zebra, focus peaking, grid, level.

### Phase 5 — Video
Resolution/FPS/codec/audio/stabilization/dynamic-range capability combinations and slow motion.

### Phase 6 — OEM Computational Features
Camera extensions and OEM processing path.

### Phase 7 — Custom Processing Foundation
Frame buffers, processing recipes, CPU/GPU/native abstraction, benchmarks.

### Phase 8 — Custom HDR
Multi-frame burst, alignment, motion handling, merge, deghosting, tone mapping, configurable HDR controls.

### Phase 9 — Night + Multi-frame Denoise
Adaptive exposure, temporal/spatial denoise, motion-aware merging, stability handling.

### Phase 10 — Super Resolution
Real multi-frame SR; never relabel simple interpolation as SR.

### Phase 11 — Semantic Processing
Careful subject/face/sky-aware processing.

### Phase 12 — Portrait
OEM bokeh first, then ML/depth fallback.

### Phase 13 — Motion/Timelapse/Advanced Modes
Motion Photo, timelapse, burst/best shot, long exposure.

### Phase 14 — Astro
Stability-aware long-sequence capture and alignment.

### Phase 15 — Device Tuning
Capability/device profiles and isolated quirks; still one app build.

### Phase 16 — Performance + Polish
Startup, latency, memory, GPU, thermals, transitions, accessibility, stability.

## First implementation task

Implement Phase 0 + Phase 1 only.

At completion provide:

1. implementation summary
2. project tree
3. architecture decisions
4. files changed
5. build result
6. test result
7. lint result
8. known limitations
9. exact updated PROJECT_STATE
10. recommended Phase 2 steps

Do not stop at planning. Do not mark unimplemented infrastructure as a completed camera feature.
