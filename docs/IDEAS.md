# Ideas queue

Added 2026-10-27, after the Apple Hardware features (beta.23); all eight built the same night (CHANGELOG, Unreleased).

| # | Idea | Built as | Result (M2, 1080p) |
|---|---|---|---|
| 1 | Neural denoiser for the path tracer | MetalFX's denoiser over the last 6 of 32 samples | 1.3 s per photo instead of 7.7 s |
| 2 | ReSTIR lights | Torch Shadows: a light grid + resampled importance sampling, 2 rays a frame, blended over frames | +0.9 ms in a torch-lit room; no temporal or spatial reservoir reuse (the grid's 15 lights per cell are all weighed exactly) |
| 3 | Neural radiance cache | Ray Tracing: Neural Lighting: hash grid + MLP trained on the GPU every frame | 3.3-3.8 ms vs Lighting's 2.7; the Neural Engine can't train live, so it runs on the GPU |
| 4 | Mesh shaders for far terrain | Far Terrain > Renderer: Mesh Shaders (mcopt's object/mesh stages, never drawn with before) | No measurable gain (the far terrain was already GPU-driven); Compute stays the default |
| 5 | Spatial audio around corners | Sound Around Corners: a search of the air around you, the way pulled tight | Doorway test correct; ~1.2 ms at most once a second |
| 6 | Neural Engine frame interpolation | Frame Generation: Neural (VideoToolbox's low-latency interpolator) | 3.9 ms at 720p beside the GPU; 15 ms at 1080p, so frames are interpolated at up to 720p |
| 7 | Live colour grading from the biome | Biome Colours: a 15-16-7 network trained on the Mac at first use | ~0.1 ms |
| 8 | Head-tracked camera lean | Head Tracking: Lean / Look from AirPods' motion | Tested with a made-up head only (no AirPods here) |

## Rule for these

Features that aren't about performance (head tracking, HDR, colour grading and the like) each get their own setting,
grouped with the visual and comfort options rather than the performance ones.
