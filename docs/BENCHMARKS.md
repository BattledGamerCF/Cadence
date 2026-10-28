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
