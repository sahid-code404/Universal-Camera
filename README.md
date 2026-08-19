# OmniCam

OmniCam is a planned universal computational camera for Android.

## Product direction

- One Android application / one codebase across Snapdragon, MediaTek, Tensor, Exynos, Unisoc, and other Android-compatible devices.
- Capability-driven runtime adaptation rather than chipset-specific builds.
- Access to every auxiliary camera that the device exposes to normal third-party apps through public Android camera APIs.
- iOS-inspired simplicity and polish without copying Apple assets or proprietary UI.
- Pixel-inspired computational-photography principles implemented independently.
- Deep user-configurable controls for HDR, denoise, sharpening, tone mapping, upscaling, formats, RAW, video, and manual capture.
- Hybrid CameraX + Camera2 architecture.
- No root, hidden-API bypasses, private OEM APIs, or fake features.

## Current status

**Repository scaffold only. No camera functionality is implemented yet.**

The first engineering milestone is **Phase 0 + Phase 1: project foundation and universal camera capability discovery**.

Read these files before making changes:

1. `HANDOFF.md`
2. `MASTER_BUILD_PROMPT.md`
3. `PROJECT_STATE.md`
4. `docs/ROADMAP.md`
5. `docs/adr/`

## Important product constraint

OmniCam can only access cameras exposed to third-party applications by Android/OEM camera APIs. A physical sensor hidden behind privileged/system-only permissions cannot be promised or bypassed.
