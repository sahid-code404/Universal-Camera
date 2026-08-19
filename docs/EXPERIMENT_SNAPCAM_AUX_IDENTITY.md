# Snapcam Auxiliary-Camera Identity Experiment

This branch is a temporary diagnostic experiment. It must not be merged into OmniCam production as-is.

## Confirmed device result

On the tested Xiaomi POCO M2 Pro, changing the client application identity from `com.omnicam.app` to `org.codeaurora.snapcam` changed Camera2 exposure from two IDs to eight IDs. This confirms package-sensitive vendor camera exposure on that device/ROM.

The additional graph contains both useful photographic routes and non-user-facing routes. Raw Camera2 ID count therefore must never be treated as physical lens count.

Direct Camera2 testing on the device found:

- `0`, `20`, `21`, `22`: direct rear preview routes work,
- `61`: logical camera containing physical members `0` and `20`; not a separate physical lens,
- `100`: duplicate/alternate vendor route; independent direct open fails,
- front graph also exposes duplicate-style routes under the experimental identity.

These IDs are observations for this device only. They are **not** encoded as a compatibility table in production logic.

## Generalized useful-lens resolver

`ValuableCameraResolver` converts the raw camera graph into user-facing photographic routes.

It:

- excludes logical aggregate camera IDs from the normal lens bar,
- excludes depth-only/non-photographic routes,
- retains distinct directly listed photographic Camera2 devices,
- recognizes physical cameras disclosed only behind a logical camera,
- collapses strong optical-metadata duplicates as likely vendor aliases,
- prefers more useful/direct/member routes when duplicate aliases exist,
- sorts default rear lenses by optical focal length,
- does not depend on POCO model names, Snapdragon model names, or fixed IDs.

This architecture can operate on other Snapdragon devices **when their camera routes are exposed to the application**, but package filtering behavior remains OEM/ROM-specific.

## User lens layout

Normal UI now shows only resolved useful routes. Raw routes remain behind developer diagnostics.

For useful rear lenses the user can:

- enable/disable individual lenses,
- reorder the lens bar,
- reset to capability-derived defaults.

Preferences persist with AndroidX DataStore.

## Standard logical/physical routing

Useful physical cameras that are not independently listed are no longer discarded. The Camera2 compatibility controller can open their parent logical camera and assign the preview output to the requested physical camera using a physical `OutputConfiguration`.

This gives the architecture two public routing paths:

1. direct Camera2 device ID,
2. physical camera via a logical Camera2 device.

Both still require real session validation on each device.

## Three separate states

OmniCam deliberately distinguishes:

1. **enumerated** — a raw Camera2 route exists,
2. **useful** — the resolver identifies a distinct photographic lens route,
3. **routable** — a real preview/capture session succeeds.

This prevents virtual/logical/alias routes from becoming fake lens buttons.

## Installation conflict

An already installed GCam/Snapcam build using the exact same application ID will usually conflict because it is signed with a different certificate. The user may need to temporarily uninstall that app before installing this diagnostic APK, then reinstall it afterward.
