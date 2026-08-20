# Preview selection

Hardware test result on target device:

1. CameraX PreviewView PERFORMANCE (CX SURF) — best
2. CameraX PreviewView COMPATIBLE (CX TEX) — best / near-best
3. Camera2 SurfaceView PRIVATE — good image quality but incorrectly cropped
4. Camera2 TextureView PRIVATE — good image quality but rotation transform incorrect

Implementation direction: keep the Camera2 RAW/DNG capture pipeline, but replace the bitmap/JPEG viewfinder with AndroidX Camera Viewfinder using an external SurfaceView-first surface and TextureView fallback. This gives the same low-latency compositor path as the winning CX SURF mode while allowing Camera2 RAW_SENSOR in the same capture session. The viewfinder library will own aspect, rotation and mirroring transforms.