# Computational Pipeline

No custom computational image processing is implemented yet.

Planned high-level pipeline:

```text
Capture Frames
  ↓
Frame Selection
  ↓
Registration / Motion Analysis
  ↓
HDR / Exposure Fusion
  ↓
Temporal + Spatial Denoise
  ↓
Color / White Balance
  ↓
Tone Mapping
  ↓
Optional Semantic Processing
  ↓
Optional Multi-frame Super Resolution
  ↓
Detail / Sharpening
  ↓
Encoding
```

Every processing stage must remain modular, measurable, configurable where appropriate, and honestly labeled.
