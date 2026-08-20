# ADR 0005: Distinguish listed cameras from logical physical members

## Status
Accepted.

## Decision
The capability model records whether a camera ID appears directly in `CameraManager.cameraIdList` and separately records physical IDs exposed through logical multi-camera characteristics.

## Rationale
A physical camera ID disclosed as a member of a logical camera is useful for auxiliary-lens discovery but does not automatically mean a normal third-party application may open that ID independently. Treating both cases as equivalent would create false aux-camera claims.

## Consequences
- Diagnostics may show physical members that are not directly listed.
- UI must not promise independent capture from such members until Phase 3 validates a public session strategy.
- OEM-hidden/system cameras are not bypassed.
