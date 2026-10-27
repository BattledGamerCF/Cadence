# Ideas queue

Features to build later (not started). Added 2026-10-27, after the Apple Hardware features (beta.23).

| # | Idea | Notes |
|---|---|---|
| 1 | Neural denoiser for the path tracer | A Photo Mode path-traced photo clean in about 1 s instead of 7 |
| 2 | ReSTIR lights | Every torch and lava pool casting ray-traced shadows |
| 3 | Neural radiance cache | A small network on the Neural Engine learning the world's lighting live |
| 4 | Mesh shaders for far terrain | Far terrain drawn on the GPU: render distance limited by memory, not the CPU |
| 5 | Spatial audio around corners | Sound that bends around corners and through doors (World Reverb's rays) |
| 6 | Neural Engine frame interpolation | Apple's own (VideoToolbox frame interpolation) as a second Frame Generation mode |
| 7 | Live colour grading from the biome | A tiny network grading the picture per biome |
| 8 | Head-tracked camera lean | AirPods motion leaning the camera |

## Rule for these

Features that aren't about performance (head tracking, HDR, colour grading and the like) each get their own setting,
grouped with the visual and comfort options rather than the performance ones.
