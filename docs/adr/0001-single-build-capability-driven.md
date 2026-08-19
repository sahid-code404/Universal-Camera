# ADR 0001 — Single Build, Capability-Driven Behavior

## Status
Accepted

## Decision
OmniCam uses one production application/codebase across Android SoCs. Runtime camera and media capabilities, not chipset names, determine feature availability.

## Consequences
- No Snapdragon/MediaTek/Exynos/Tensor product flavors.
- Device-specific quirks/tuning are declarative and isolated.
- Generic fallback behavior is mandatory.
