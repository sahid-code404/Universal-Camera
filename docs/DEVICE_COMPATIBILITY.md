# Device Compatibility

No physical devices have been validated yet.

Only add a device after installing a real debug build and checking its exported Phase 1 capability report.

| Device | Android | Public rear cameras/lenses | Public front cameras/lenses | Logical groups | RAW | Manual | Known issues | Report/commit |
|---|---:|---|---|---:|---|---|---|---|
| _TBD_ | | | | | | | | |

## Validation rules

- `CameraManager.cameraIdList` entries are recorded as directly listed public camera IDs.
- Physical IDs disclosed by a logical camera are recorded separately.
- A disclosed physical ID is **not** automatically marked independently openable.
- Do not add an OEM-hidden/system camera as supported merely because the stock camera app can use it.
- Do not infer lens roles from numeric camera IDs.
- Compare the diagnostic result with the stock camera only to identify potential missing public lenses; stock-app access is not evidence that a third-party app can access the same sensor.

See `PHASE1_DEVICE_TEST.md` for the test procedure.
