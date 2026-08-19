# Snapcam Auxiliary-Camera Identity Experiment

This branch is a temporary diagnostic experiment. It must not be merged into OmniCam production as-is.

## Confirmed device result

On the tested Xiaomi POCO M2 Pro, changing the client application identity from `com.omnicam.app` to `org.codeaurora.snapcam` changed Camera2 exposure from two IDs to eight IDs. That confirms package-sensitive vendor camera exposure on this device/ROM.

The additional Camera2 graph contains both useful physical photographic routes and non-user-facing routes such as a logical aggregator and metadata-duplicate aliases. Raw Camera2 ID count therefore must never be treated as physical lens count.

## Generalized useful-lens resolver

The experiment now uses `ValuableCameraResolver` instead of a POCO camera-ID table.

The resolver:

- excludes logical aggregate camera IDs from the normal lens bar,
- excludes depth-only/non-photographic routes,
- retains directly listed photographic Camera2 devices,
- understands physical cameras that must later be routed through a logical camera,
- collapses strong optical-metadata duplicates as vendor aliases,
- prefers direct/member routes over duplicate aliases,
- sorts default rear lenses from wider to longer focal length,
- never relies on hard-coded IDs such as `21`, `22`, `20`, or `0`.

This makes the lens-resolution logic applicable to other Snapdragon/vendor camera graphs when those routes are exposed to the app. It does **not** guarantee that every Snapdragon ROM uses the same package allowlist or exposes all sensors.

## User lens layout

The useful rear-lens list now has persistent user configuration:

- enable/disable each useful lens,
- reorder lenses,
- reset to capability-derived defaults.

Preferences are stored with AndroidX DataStore. Raw logical/alias IDs remain visible only in diagnostics for debugging.

## Routing boundary

Three concepts remain separate:

1. a Camera2 ID is enumerated,
2. the resolver considers it a useful photographic route,
3. a preview/capture session actually succeeds.

The current hardware probe directly opens `DIRECT_CAMERA_DEVICE` routes through Camera2. `PHYSICAL_VIA_LOGICAL` routing remains a later session-level implementation task for devices that expose physical members only behind a logical camera.

## Installation conflict

An already installed GCam/Snapcam build using the exact same application ID will usually conflict because it is signed with a different certificate. The user may need to temporarily uninstall that app before installing this diagnostic APK, then reinstall it afterward.
