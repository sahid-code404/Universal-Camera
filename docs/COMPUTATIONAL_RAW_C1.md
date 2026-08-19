# OmniCam Computational RAW Engine — Phase C1

Status: experimental hardware-validation path. Do not treat C1 as the final computational-photo pipeline.

## Goal

C1 proves that OmniCam can acquire and fuse synchronized full-resolution Camera2 `RAW_SENSOR` frames without routing them through JPEG/YUV first.

The current lab performs:

1. Direct RAW-capable valuable-lens selection.
2. AE/AF/AWB live preview.
3. Freeze of the latest preview ISO, exposure, focus distance and AWB state for capture.
4. Ordered Camera2 RAW burst capture (6, 8 or 12 frames).
5. Exact `Image.timestamp` ↔ `CaptureResult.SENSOR_TIMESTAMP` pairing.
6. File-backed staging of 16-bit Bayer planes so a complete burst is not retained on the managed heap.
7. Conservative same-CFA global alignment using even-pixel translation search.
8. Exposure normalization, motion/outlier rejection and weighted Bayer-domain fusion.
9. A merged 16-bit Bayer DNG written with reference-frame Camera2 metadata.

## Presets

- QUALITY: six equal-exposure RAW frames for noise reduction.
- HDR: eight RAW frames with -2 EV / -1 EV highlight-protection frames plus base-exposure frames.
- MAX: twelve RAW frames with additional short/base exposures. It spends more time and storage for a stronger C1 merge.

These names describe acquisition/fusion policies only. C1 does not claim multi-frame super-resolution, local optical flow, a floating-point DNG, or a finished custom HEIF image yet.

## Why file-backed RAW staging

A 12 MP `RAW_SENSOR` frame occupies roughly 24 MB at 16 bits per sample. Twelve such frames would require roughly 288 MB before alignment buffers or application overhead. C1 immediately packs each frame to a temporary contiguous RAW16 file in the app cache and closes the camera `Image`; fusion then reads file-backed mapped buffers. This avoids retaining the whole burst as Java/Kotlin arrays and reduces managed-heap pressure.

## Current alignment limitation

C1 searches a small global translation window and only considers even-pixel offsets so the 2×2 Bayer CFA phase is not swapped. Local motion is rejected by comparing exposure-normalized same-CFA samples against the reference frame.

This is intentionally conservative. C2/C3 should add:

- multi-scale tile/local alignment;
- sub-pixel motion estimation;
- robust per-tile confidence and deghost masks;
- joint demosaic/reconstruction;
- temporal noise modeling;
- multi-frame super-resolution;
- custom camera color pipeline and local HDR tone mapping;
- HEIF/Ultra HDR output;
- optional custom linear/floating computational DNG writer.

## Safety boundary

The normal OmniCam photo controller is unchanged. C1 is exposed through the separate `OmniCam C-RAW Lab` launcher during hardware validation. Once C1 is stable across real devices, the computational engine can be integrated into the main Photo UI behind capability gates.
