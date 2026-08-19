# Phase 2 Physical-Device Test

This checklist validates OmniCam's first real camera experience and the development update channel.

## One-time signing migration

Older experimental APKs may have been signed with an ephemeral GitHub Actions debug certificate. The new Phase 2 development channel uses one stable development-only certificate.

If Android reports that the Phase 2 APK cannot update the currently installed experiment because signatures differ:

1. Uninstall the old OmniCam/Snapcam experiment once.
2. Install the Phase 2 development APK.
3. Do not uninstall it for normal later development updates.

Future APKs produced by the same development channel should share the same signature and can update in place. The development certificate is intentionally public and must never be used for production distribution.

## Camera launch

1. Launch OmniCam.
2. Grant Camera permission and, on Android 9 only, legacy storage permission if requested.
3. Confirm the live preview appears without opening diagnostics first.
4. Background and foreground the app and verify the camera recovers.

## Useful lenses

1. Confirm only useful photographic lenses appear on the normal lens bar.
2. Switch through every enabled rear lens.
3. Confirm each lens shows the expected field of view.
4. Flip to the front camera and back.
5. Open Settings > Manage lenses.
6. Disable one lens and verify it disappears from the camera bar.
7. Re-enable it.
8. Reorder lenses and restart the app; verify the order persists.

## Still capture

For every useful rear lens and at least one front lens:

1. Tap the shutter.
2. Confirm no camera-session error appears.
3. Confirm a JPEG is created.
4. Confirm the latest-photo thumbnail updates.
5. Open Gallery/Photos and verify the image is stored in the OmniCam/DCIM area.
6. Inspect image sharpness and field of view to ensure the selected lens actually produced the frame.

## Orientation

Capture the same scene in portrait, landscape left, and landscape right. Verify each saved JPEG opens upright in a normal gallery.

## Focus and exposure

1. Tap a near subject and confirm the focus indicator appears.
2. Tap a distant subject and verify focus changes where the sensor supports AF.
3. Move the EV control toward negative values and verify preview/capture darkens.
4. Move toward positive values and verify it brightens.
5. Return EV to 0.

## Zoom

1. Pinch to zoom on the active lens.
2. Verify zoom is smooth and bounded by the camera's reported range.
3. Test the zoom slider.
4. Switch lenses after zooming and verify the new lens starts from a safe/default zoom.

## Flash

On a rear camera that exposes flash, test Off, Auto, On, and Torch, then return to Off. Verify unsupported lenses do not present a working flash control.

## Aspect ratio

Capture 4:3 and 16:9. Verify both outputs have the expected aspect ratio and preview is not stretched.

## Development updater

1. Install the provided baseline Phase 2 APK.
2. Launch OmniCam with internet access.
3. When a newer `dev-latest` exists, verify an Update available control appears.
4. Tap it to download.
5. Tap Install update.
6. Android may ask to allow installs from OmniCam the first time; enable that permission and retry Install update.
7. Confirm Android offers an update to the existing app rather than requiring uninstall.
8. Install and relaunch.
9. Verify lens preferences remain present after the update.

## Report back

Report the exact lens that failed, any error text, whether preview or only capture failed, orientation problems, focus/EV/zoom behavior, flash behavior, and whether the development update installed over the existing app.

## Gate after Phase 2

Do not begin heavy computational photography until basic preview, capture, saved-media orientation, lens routing, and update-over-install behavior are reliable on real devices.
