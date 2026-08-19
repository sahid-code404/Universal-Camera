# Phase 1 Physical-Device Test

This test validates OmniCam's camera-discovery foundation before preview/capture development begins.

## What this build does

The Phase 1 APK does **not** take photos yet. It requests camera permission, scans camera hardware exposed through public Android APIs, classifies available lenses from optical metadata, shows diagnostics, and exports a sanitized JSON capability report.

## Install

Use either the `omnicam-phase1-debug` GitHub Actions artifact or build locally:

```bash
./gradlew :app:assembleDebug
```

Then install with ADB if desired:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Test procedure

1. Launch OmniCam.
2. Allow camera permission.
3. Wait for the diagnostics scan to complete.
4. Note the number of directly listed public camera IDs.
5. Note any logical-camera groups and their physical members.
6. Check the displayed lens classifications and estimated 35mm-equivalent focal lengths.
7. Compare the result with the phone's physical rear/front camera hardware, remembering that an OEM may intentionally hide sensors from third-party apps.
8. Tap **Rescan** and verify the result remains stable.
9. Tap **Export JSON** and save `omnicam-device-report.json`.
10. Keep the report with the exact phone model and Android version used for the test.

## What to report

For each phone, capture:

- Manufacturer and exact model.
- Android version/API level.
- Screenshot of the diagnostics screen if convenient.
- `omnicam-device-report.json`.
- Which lenses the stock camera offers (for comparison only).
- Any lens OmniCam classifies incorrectly.
- Any crash, scan failure, permission issue, or inconsistent rescan.

## Expected limitations

- A sensor visible to the stock OEM camera may not be public to third-party apps.
- A physical ID inside a logical-camera group may be usable only through a logical session and may not be independently openable.
- Some vendors expose incomplete optical metadata; those cameras should remain `UNKNOWN` rather than being guessed with high confidence.

## Gate to Phase 2

Phase 2 may begin after the scanner is validated on at least a small hardware mix and any severe discovery bugs are fixed. Ideally include different OEMs and at least one device with a logical multi-camera rear system.
