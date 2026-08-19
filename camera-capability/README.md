# camera-capability

Primary Phase 1 module.

Planned responsibilities:

- CameraManager enumeration
- CameraCharacteristics normalization
- logical/physical camera graph
- lens optical classification + confidence
- capture/video/manual/output capability models
- DeviceCameraProfile construction
- capability cache/schema
- fake profiles for tests

No camera ID should be assumed to correspond to a particular lens role.
