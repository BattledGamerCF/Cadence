# Benchmarks

Measured on the smallest Apple Silicon GPU Cadence is tested on: a **MacBook Air (M2, 2022), 8-core GPU, 8 GB**, macOS 27,
fanless. Unless noted: fullscreen on the Air's own screen with Retina on (2940 x 1846 pixels), **render distance 16 and
simulation distance 16**, Cadence 0.4.0 (after beta.24), no frame cap, Heat Control off so every run starts equal. Each row is
its own launch of the game, with 150 seconds for the Mac to cool down between launches (a hot Air loses up to half its speed).
The same world is restored before every launch.

- **Benchmark**: Video Settings > Benchmark: 30 seconds, the view turning once all the way round. Average fps and 1% low.
- **Power**: the whole Mac's draw (macOS's own system power reading, display included), averaged over 20-40 seconds, while
  the view turns.

Numbers from a laptop move by a few percent from run to run; differences under ~5% aren't meaningful.

## Without shaders

| Setup | Average fps | 1% low |
|---|---|---|
| Fullscreen, Retina on (2940 x 1846) | 506-603 | 291-327 |
| Fullscreen, Max FPS preset (Retina off, 1470 x 923, fast leaves, no clouds) | 1,345 | 614 |
| Retina on, with Far Terrain (Medium, 1,024 chunks) | 494-504 | 281-294 |

## Shader packs

With **Auto resolution** (the default), Cadence lowers a pack's resolution until it holds 60 fps, so packs differ in the
resolution they settle at more than in fps. Fullscreen Retina, uncapped (packs are otherwise held at the display's 60):

| Pack | Average fps | 1% low | Auto settled at |
|---|---|---|---|
| BSL 10.1.8 | 64 | 54 | 60% |
| BSL, with Far Terrain | 61 | 52 | 55% |
| Complementary Reimagined 5.9.3 | 62 | 47 | 50% |
| MakeUp UltraFast 9.5g | 60 (held at 60) | 56 | 100% |
| BSL at a fixed 100% (no Auto) | 33 | 28 | |
| BSL at a fixed 50%, MetalFX upscaler | 74 | 55 | |
| BSL at a fixed 50%, Neural Engine upscaler | 80 | 54 | |

The Neural Engine upscaler does the upscale beside the GPU (two tiles of 768 x 924, 2.2 ms each on the M2's Neural Engine),
which frees about 1 ms of GPU a frame; the world on screen is then one frame older than the hand and HUD.

## Far Terrain on a small GPU

Far Terrain is the costliest of Cadence's visual features while moving: standing still, its last frame's work is reused;
moving, it chooses again what to draw. On the 8-core M2, with Far Distance 1,024 chunks at Auto quality (Medium):

| | Without Far Terrain | With Far Terrain |
|---|---|---|
| Unshaded, turning (benchmark) | 603 fps, 1.66 ms GPU | 494-504 fps, 2.02 ms GPU |
| Unshaded, flying east at 10 blocks a second | 601 fps | 588 fps |
| BSL, Auto resolution | 64 fps at 60% | 61 fps at 55% |
| BSL, fixed 50% | | 67 fps |

So it costs about 0.4 ms a frame while turning (0.9 ms before this round's change: the far terrain's selection is now made
for a slightly wider view and reused for up to three frames while the view stays inside it), and with a shader pack about one
step (5%) of resolution. Flying over new land, the far terrain generated in the background kept up with no missing tiles.
On GPUs of 6 cores or fewer, Auto quality picks Low.

## Where a frame's time goes

**Without shaders, Max FPS preset, render distance 16** (1470 x 923, about 1,365 fps, a 0.73 ms frame, limited by the GPU):

| | Frame time |
|---|---|
| Everything but the terrain (the processor's frame: game tick, entities, HUD, the frame's setup) | 0.29 ms |
| ... plus choosing what terrain to draw (the per-frame visibility test, on the GPU) | 0.41 ms |
| ... plus drawing it | 0.73 ms |

Per frame Cadence tests 19,000-36,000 blocks of terrain against the depth of the frame before and draws 60,000-110,000
faces of the ~290,000 in view; without that occlusion test the same frame takes 1.44 ms (687 fps). A frame of 3,000 fps
(0.33 ms) would need the terrain at less than a tenth of its GPU time and the processor's frame cut by a third: not
reachable on this Mac by tuning (merging faces with Smooth Lighting off removes only 25% of them, since light levels differ
block to block and stone, dirt and grass use randomly rotated textures). A fanless MacBook Air also slows to about half
speed after two minutes at full load, so sustained frame rates are lower than these 30-second runs.

**BSL at 50% world resolution** (a ~13 ms GPU frame), by pass:

| Pass | Vertex | Pixels |
|---|---|---|
| Terrain (2,725 chunk-section draws) | 5.3 ms | 0.75 ms |
| Shadow map | 2.4 ms | 1.95 ms |
| Water | 1.8 ms | 3.0 ms |
| Deferred lighting (2 passes) | | 1.8 ms |
| Composite passes (6) | | 2.9 ms |

Cadence's part of a pack's frame is mostly the vertex work: the chunk sections hidden behind others are skipped
(-1.4 ms), the faces turned away from the camera are skipped (-2.0 ms), far shadows are drawn as simple shapes (-1.4 ms);
the pixel passes are the pack's own shaders.

## Stalls

A frame over 50 ms in a world is logged with its cause (the stall recorder, `[cadence] stall:` lines). Switching a shader
pack on, the longest frame (fullscreen Retina, render distance 16, each pack loaded before in earlier runs):

| | Before 0.4.0-beta.26 | Now |
|---|---|---|
| BSL | 0.37-0.51 s | 0.21-0.26 s |
| Complementary Reimagined | 1.2-4.2 s | 0.16-0.17 s |
| Either, while flying over new land with Far Terrain | 2.0-2.2 s | 0.4-0.5 s |

What remains of it is the game rebuilding its chunk meshes for the pack and Metal making ~130 pipelines (about 150 ms of
compiler time over 4 threads: macOS's shader cache makes them quick after the first time).

## Measured and not adopted

| Idea | Measured | |
|---|---|---|
| Merging coplanar terrain faces | Only 4.4% of solid faces could merge without changing the picture: half have different light at their corners, a third different shading or tint | Not worth the cost |
| Mesh shaders for near terrain | 326 fps, GPU 3.07 ms, against 1,390 fps and 0.72 ms for Cadence's renderer (Max FPS preset, render distance 16) | Slower |
| Skipping the in-place depth clears before the hand and the HUD | 1,336 vs 1,333 and 1,363 vs 1,360 fps | No difference |
| Apple's MetalFX frame interpolation | 28 ms a frame at the screen's resolution on an M2 | Much slower than Cadence's Frame Generation |
| Occlusion test off at the Max FPS resolution | 687 vs 1,303 fps | The test is what makes render distance 16 fast |
| Fast math for shader packs' shaders | BSL 74 vs 70 fps (within noise) | Packs may rely on exact infinities and NaN; kept exact |
| Two Neural Engine upscaler sessions in parallel | 4.48-4.50 ms vs 4.50-4.62 ms for two tiles | The Neural Engine is already fully busy |

## Power: Battery Saver

The whole Mac's draw at render distance 16, fullscreen Retina, turning:

| Setup | Without shaders | BSL |
|---|---|---|
| No frame cap | 24 W | |
| 60 fps, every frame rendered | 5.0-5.2 W | 21.3-21.5 W |
| 30 fps, every frame rendered | 3.9-4.4 W | |
| Battery Saver before (30 rendered, 30 generated, Auto resolution) | 5.1 W | 14.4-15.5 W |
| **Battery Saver now** | **5.2-5.7 W, a true 60** | **10.9-12.4 W, a steady 30 rendered shown as 60, 50% resolution** |

Without shaders, rendering a frame costs no more than generating one, so Battery Saver now renders a true 60 (the same power,
sharper, a frame less delay). With a shader pack, a frame is expensive: Battery Saver renders 30, shows 60, and holds the world
at 50% resolution or below, about half of what the pack draws at 60 fps. Its frames are generated the Responsive way: the
Smooth way drew 9.4-10.6 W, but at Battery Saver's low clocks its frames slipped to 27-29 rendered (56-58 shown), while
Responsive held a steady 30 (60 shown) for about 1 W more. (A warm Mac draws more for the same work: the higher ends of these
ranges are runs right after others.)

## Neural Engine features

| Feature | Before | Now |
|---|---|---|
| Style Filter (a painting's style over the world) | 38 ms a picture, ~26 a second, 870 fps* | 10 ms, 60 a second, 995 fps* |
| Neural Engine upscaler | not with shader packs; at most 1280-wide pictures; a ~1 s stop at every size change | shader packs, any size (tiles), started in the background |
| Smooth Textures | 1,254 sprites upscaled in 167-182 ms at load; +0.2 ms a frame* | unchanged |

\* 1920 x 1080 window, render distance 6, no shader pack (base ~1,250 fps).

## How to reproduce

`tools/bench-round.sh` runs a list of configurations, each in its own launch with a cool-down between, through the self-test
harness (`tools/cadence-test.sh`). See CONTRIBUTING.md for the harness.
