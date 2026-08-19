# Device Compatibility

Physical-device validation has started. Results below describe what the tested ROM exposes under the tested client identity. They are not automatically a list of every physical sensor installed in a phone, and a listed ID is not considered capture-capable until a preview/session test succeeds.

| Device | Android | Normal OmniCam identity | Snapcam-compatible identity experiment | Logical groups | Confirmed optical finding | Known issues / next gate | Report/commit |
|---|---:|---|---|---:|---|---|---|
| Xiaomi POCO M2 Pro | API 36 (ROM unspecified) | `com.omnicam.app`: Camera2 IDs `0` rear WIDE (~25.6 mm eq) and `1` FRONT (~28.1 mm eq); Camera1 count 2; no logical groups | `org.codeaurora.snapcam`: **8 Camera2 IDs**, Camera1 count **6**; rear IDs include `21`, `22`, `20`, `0`, `100`, `61`; front IDs include `1`, `101` | 1 under Snapcam identity: logical `61` with physical members `0`, `20` | Camera `21` reports ~15.6 mm eq / 1.65 mm native and is classified ULTRA_WIDE with high confidence | Caller package identity demonstrably changes camera exposure on this ROM. Opening/preview/capture validation of each exposed rear ID is pending; do not yet label camera `22` as macro solely from metadata. | User tests 2026-08-19; experiment branch `experiment-snapcam-aux-identity` |

## POCO M2 Pro finding

The same phone and ROM produced radically different camera enumeration results when only the application client identity changed:

```text
com.omnicam.app
Camera2: 2
Camera1: 2
logical groups: 0

org.codeaurora.snapcam
Camera2: 8
Camera1: 6
logical groups: 1
```

This is strong device-level evidence that the vendor camera stack filters auxiliary-camera visibility by caller/client identity on this ROM. This finding belongs in the compatibility layer; it must not be generalized to every Qualcomm, Xiaomi, or Android device.

The current next test is stricter than enumeration: bind CameraX preview and an in-memory ImageCapture probe to every exposed rear Camera2 ID. Only successful session/capture tests should promote an ID from **enumerated** to **usable**.

## Validation rules

- `CameraManager.cameraIdList` entries are recorded as directly listed camera IDs for the current caller identity.
- Physical IDs disclosed by a logical camera are recorded separately.
- A disclosed physical ID is **not** automatically marked independently openable.
- Do not add an OEM-hidden/system camera as supported merely because the stock camera app can use it.
- Do not infer lens roles from numeric camera IDs.
- Do not assume an enumerated auxiliary camera can create a valid preview/capture session until tested.
- Compare caller identities only in isolated compatibility experiments; do not silently ship a vendor package identity as OmniCam's production identity.
- For devices where Camera2 exposes only primary rear/front cameras, record Camera1 enumeration and logical/physical data before concluding that no alternate public route is present.

See `PHASE1_DEVICE_TEST.md` for the general discovery test procedure.
