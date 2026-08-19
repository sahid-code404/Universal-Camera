# ADR 0002 — Hybrid CameraX + Camera2

## Status
Accepted

## Decision
Use CameraX for high-level reliable lifecycle/preview/capture/video/extension flows and Camera2 for advanced capability inspection and low-level controls where necessary.

## Consequences
The application needs a clean camera-domain abstraction so UI/features do not become tightly coupled to either backend.
