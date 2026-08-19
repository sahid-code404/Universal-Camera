# Device Compatibility

Physical-device validation has started. Results below describe what the tested ROM exposes to a normal third-party application through public Android camera APIs; they are not a list of every physical sensor installed in the phone.

| Device | Android | Public rear cameras/lenses | Public front cameras/lenses | Logical groups | RAW | Manual | Known issues | Report/commit |
|---|---:|---|---|---:|---|---|---|---|
| Xiaomi POCO M2 Pro | API 36 (ROM unspecified) | Camera2 ID `0`: WIDE, ~25.6 mm eq | Camera2 ID `1`: FRONT, ~28.1 mm eq | 0 | rear + front | rear + front | Initial Camera2 scan exposes only main rear + front. No physical/logical aux IDs reported. Camera1/concurrent public-API cross-check added after this report and still needs a second device scan. | User Phase 1 report, 2026-08-19; aux-probe follow-up on `phase-0-1-foundation` |

## Validation rules

- `CameraManager.cameraIdList` entries are recorded as directly listed public camera IDs.
- Physical IDs disclosed by a logical camera are recorded separately.
- A disclosed physical ID is **not** automatically marked independently openable.
- Do not add an OEM-hidden/system camera as supported merely because the stock camera app can use it.
- Do not infer lens roles from numeric camera IDs.
- Compare the diagnostic result with the stock camera only to identify potential missing public lenses; stock-app access is not evidence that a third-party app can access the same sensor.
- For devices where Camera2 exposes only the primary rear/front cameras, also record the legacy Camera1 device count before concluding that no alternate public API exposes an auxiliary camera.

See `PHASE1_DEVICE_TEST.md` for the test procedure.
