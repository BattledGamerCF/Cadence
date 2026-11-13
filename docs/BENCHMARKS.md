# Benchmarks

## Test setup

Measurements were taken on the smallest Apple Silicon GPU Cadence is tested on: a **MacBook Air (M2, 2022), 8-core GPU,
8 GB**, macOS 27, fanless. Unless noted:

- Fullscreen on the Air's own screen with Retina on (2940 x 1846 pixels).
- **Render distance 16 and simulation distance 16**.
- Cadence 0.4.0-beta.24 to beta.27 (2026-10-28). Ranges are runs on different nights.
- No frame cap.
- Heat Control off, so every run starts equal.
- Each row is its own launch of the game, with 150 seconds for the Mac to cool down between launches. A hot Air loses up to
  half its speed.
- The same world is restored before every launch.

Metrics:

- **Benchmark**: Video Settings > Benchmark. It runs for 30 seconds with the view turning once all the way round, and reports
  average fps and 1% low.
- **Power**: the whole Mac's draw (macOS's own system power reading, display included), averaged over 20-40 seconds while
  the view turns.

Numbers from a laptop move by a few percent from run to run. Differences under ~5% are not meaningful.

## Without shaders

| Setup | Average fps | 1% low |
|---|---|---|
| Fullscreen, Retina on (2940 x 1846) | 506-618 | 291-327 |
| Fullscreen, Max FPS preset (Retina off, 1470 x 923, fast leaves, no clouds) | 1,303-1,365 | 592-619 |
| Retina on, with Far Terrain (Medium, 1,024 chunks) | 494-510 | 281-294 |
| Retina on, macOS Game Mode off (`cadence.gameMode=false`) | 607 | 324 |

Game Mode makes no difference to these numbers on a Mac with nothing else running. It gives the game priority over
background work and lower Bluetooth latency, which matters when something else is busy.

## Shader packs

With **Auto resolution** (the default), Cadence lowers a pack's resolution until it holds 60 fps. Packs therefore differ
more in the resolution they settle at than in fps. The table is fullscreen Retina, uncapped. Packs are otherwise held at
the display's 60.

| Pack | Average fps | 1% low | Auto settled at |
|---|---|---|---|
| BSL 10.1.8 | 63-68 | 52-55 | 55-60% |
| BSL, with Far Terrain | 61 | 52 | 55% |
| Complementary Reimagined 5.9.3 | 60-62 | 45-47 | 50% |
| MakeUp UltraFast 9.5g | 60 (held at 60) | 56 | 100% |
| BSL at a fixed 100% (no Auto) | 33 | 28 | |
| BSL at a fixed 50%, MetalFX upscaler | 74-77 | 55-63 | |
| BSL at a fixed 50%, Neural Engine upscaler | 80 | 54 | |

The Neural Engine upscaler does the upscale beside the GPU, in two tiles of 768 x 924 that take 2.2 ms each on the M2's
Neural Engine. This frees about 1 ms of GPU time a frame. The world on screen is then one frame older than the hand and HUD.

## Far Terrain on a small GPU

Far Terrain is the costliest of Cadence's visual features while moving. When the view is still, the last frame's work is
reused. When it moves, Far Terrain chooses again what to draw. The table below is from the 8-core M2, with Far Distance
1,024 chunks at Auto quality (Medium).

| | Without Far Terrain | With Far Terrain |
|---|---|---|
| Unshaded, turning (benchmark) | 603 fps, 1.66 ms GPU | 494-504 fps, 2.02 ms GPU |
| Unshaded, flying east at 10 blocks a second | 601 fps | 588 fps |
| BSL, Auto resolution | 64 fps at 60% | 61 fps at 55% |
| BSL, fixed 50% | | 67 fps |

Far Terrain costs about 0.4 ms a frame while turning. It cost 0.9 ms before this round's change: the far terrain's
selection is now made for a slightly wider view and reused for up to three frames while the view stays inside it. With a
shader pack it costs about one step (5%) of resolution. Flying over new land, the far terrain generated in the background
kept up with no missing tiles. On GPUs of 6 cores or fewer, Auto quality picks Low.

## Where a frame's time goes

### Without shaders

Max FPS preset, render distance 16, 1470 x 923. The frame takes 0.73 ms (about 1,365 fps) and is limited by the GPU.

| | Frame time |
|---|---|
| Everything but the terrain (the processor's frame: game tick, entities, HUD, the frame's setup) | 0.29 ms |
| ... plus choosing what terrain to draw (the per-frame visibility test, on the GPU) | 0.41 ms |
| ... plus drawing it | 0.73 ms |

Per frame, Cadence tests 19,000-36,000 blocks of terrain against the depth of the frame before. It draws 60,000-110,000
faces of the ~290,000 in view. Without that occlusion test the same frame takes 1.44 ms (687 fps).

A frame of 3,000 fps (0.33 ms) would need the terrain at less than a tenth of its GPU time and the processor's frame cut by
a third. This is not reachable on this Mac by tuning. Merging faces with Smooth Lighting off removes only 25% of them,
because light levels differ block to block and stone, dirt and grass use randomly rotated textures.

A fanless MacBook Air also slows to about half speed after two minutes at full load. Sustained frame rates are therefore
lower than these 30-second runs.

### BSL at 50% world resolution

Fullscreen Retina, with a 14-16 ms GPU frame. Each part was measured by switching it off and on every 2 seconds in one
launch, so the Mac's heat affects both states alike.

| Part of the frame | GPU time |
|---|---|
| Water | 3.1 ms |
| Terrain | 1.9 ms |
| Shadow map (drawn every 2nd frame) | 1.0 ms |
| Entities | under 0.1 ms |
| The pack's full-screen lighting and effect passes (the rest) | about 9 ms |

A shader pack's frame is limited by its pixels, not its geometry. The same view at full resolution takes 39.7 ms against
16.1 ms at 50%, so Auto resolution and the upscalers are the main levers with a pack.

Cadence's part of the geometry work is already reduced:

- Chunk sections hidden behind others are skipped.
- Faces turned away from the camera are skipped (and, in the shadow map, faces turned away from the sun).
- Far shadows are drawn as simple shapes.

## Stalls

A frame over 50 ms in a world is logged with its cause (the stall recorder, `[cadence] stall:` lines). The table shows the
longest frame when switching a shader pack on, with fullscreen Retina, render distance 16, and each pack loaded before in
earlier runs.

| | Before 0.4.0-beta.26 | Now |
|---|---|---|
| BSL | 0.37-0.51 s | 0.21-0.26 s |
| Complementary Reimagined | 1.2-4.2 s | 0.16-0.17 s |
| Either, while flying over new land with Far Terrain | 2.0-2.2 s | 0.4-0.5 s |

The remaining time is the game rebuilding its chunk meshes for the pack, and Metal making ~130 pipelines. The pipelines take
about 150 ms of compiler time over 4 threads. macOS's shader cache makes them quick after the first time.

## On a server

These runs used a second client joining a LAN world over a real connection and flying over new land (render distance 10,
60 s). The same machine runs the host, so the numbers are relative.

| | Minecraft | Cadence |
|---|---|---|
| Decryption (online-mode servers), through Netty | 51-82 MB/s | 570-700 MB/s (the Mac's AES instructions, 8 bytes at a time) |
| Decompression of a packet, through Netty | 35-40 µs | 19-22 µs (the system's zlib) |
| Network thread CPU over the flight | 898 ms | 817 ms |
| Where sunlight stops in a new chunk (Cadence Logic `skySources`) | 43-46 µs | 26-33 µs |
| Keep Server Chunks: a dropped chunk encoded on the render thread | 2% of its time, one 73 ms frame | off the render thread |

While flying, a server costs the render thread (its Java time) the following:

- Handling packets: 11% (new chunks 3.4%, the light engine 3.4%).
- Ticking entities: 10%.

The network thread itself is barely busy (under 1%) on an M2. The native network path therefore lowers CPU use but does not
change fps. The frame rate stayed at the test's 120 fps cap either way, with the slowest 1% of frames at 10-12 ms.

## Measured and not adopted

| Idea | Measured | Verdict |
|---|---|---|
| Merging coplanar terrain faces | Only 4.4% of solid faces could merge without changing the picture. Half have different light at their corners, and a third have different shading or tint | Not worth the cost |
| Mesh shaders for near terrain | 326 fps, GPU 3.07 ms, against 1,390 fps and 0.72 ms for Cadence's renderer (Max FPS preset, render distance 16) | Slower |
| Skipping the in-place depth clears before the hand and the HUD | 1,336 vs 1,333 and 1,363 vs 1,360 fps | No difference |
| Apple's MetalFX frame interpolation | 28 ms a frame at the screen's resolution on an M2 | Much slower than Cadence's Frame Generation |
| Occlusion test off at the Max FPS resolution | 687 vs 1,303 fps | The test is what makes render distance 16 fast |
| Fast math for shader packs' shaders (vertex and pixel) | BSL 74 vs 70 fps (within noise) | Packs may rely on exact infinities and NaN, so math is kept exact |
| Two Neural Engine upscaler sessions in parallel | 4.48-4.50 ms vs 4.50-4.62 ms for two tiles | The Neural Engine is already fully busy |
| Computing a pack's per-frame values (BSL's sun, up and east directions) once per pixel instead of passing them from every vertex | 81 vs 79, 73 vs 76 and 68 vs 72 fps (BSL, 50%) | No difference. The vertex stage is not what limits a pack |
| Ultra: a "dome" of past frames (every direction) to fill the edge a turn uncovers, instead of stretching it | The dome kept the hand from the frames it was built from and showed a seam where it met the frame | Worse than the stretch; turning already asks for a real frame every 2° |
| Memoryless render targets for pack buffers written and never read | 0 such buffers in BSL, Complementary, Cadence Lite, Photon, Sildur's Vibrant and MakeUp | Nothing to save |
| Reusing a pack's shadow map for 4 frames while the camera, sun and nearby mobs are still | Never still in a normal world (mobs within 64 blocks always move); same fps | No gain |
| Pacing frames on a steady clock instead of in bursts | Cadence Lite at 60: 7.85 vs 7.61 W; BSL at 30: 6.8 vs 7.0 W | Within noise |
| Ultra: not gathering entities for generated frames | BSL 5.56-5.60 W against 5.16-5.30 W gathering them | Used more power |
| Drawing a pack's shadow map every 3rd frame instead of every 2nd | Every frame costs 1.15 ms more than every 2nd, so every 3rd would save about 0.4 ms (3%) | Not worth the shadows lagging behind movement |

## Power: Battery Saver

Whole Mac's draw at render distance 16, fullscreen Retina, on an M2 Air.

| Setup | Without shaders | BSL |
|---|---|---|
| No frame cap | 24 W | |
| 60 fps, every frame rendered | 5.0-5.2 W | 21.3-21.5 W |
| 30 fps, every frame rendered | 3.9-4.4 W | 9.7 W (60% resolution) |
| Battery Saver up to 1.1.0-beta.1 (30 rendered, 60 shown, 50% resolution) | | 10.9-12.4 W, turning |
| **Battery Saver now** | **5.2-5.7 W, a true 60** | **5.4 W still (30 shown), 6.9 W turning (60 shown)** |

Without shaders, rendering a frame costs no more than generating one, so Battery Saver renders a true 60: the same power,
sharper, one frame less delay.

With a shader pack, Battery Saver uses Ultra: a frame is rendered when the view turns or moves, at least one a second and at
most two a second for moving mobs, and the rest are generated; 30 are shown while the camera is still. With mobs allowed to
ask for a frame each time they moved (Ultra's usual rule), a few animals nearby kept BSL at 19 rendered a second and 7.9 W.

## Frame Generation: Smooth at a low cap

Smooth draws its generated frame inside the next real frame's work. BSL fullscreen on Retina, 60% resolution, 30 fps cap:

| Mode | Rendered a second | Whole Mac |
|---|---|---|
| Off | 30 | 9.7 W |
| Responsive | 30 (60 shown) | 11.2 W |
| Smooth | 30, falling to 12 within a minute | 6.1 W at 12 |
| Smooth, 1920 x 1080 window | 27 | 7.3 W |

When Smooth renders under 80% of its rate for 3 s it now runs as Responsive (a steady 30 in the same test), then tries Smooth
again after a minute, and after twice as long each time it falls back again.

## Power: Frame Generation Ultra

Ultra shows 60 frames a second and renders as few as one. Each other frame is the newest rendered frame reprojected to where
the camera is now. More frames are rendered when the view turns, the player moves or acts, or a mob nearby moves; the
thresholds below are set by hand for the test (`ultra=FLOOR:TURN:MOVE:ENTITY` self-test step, 0 = that trigger off).

Fullscreen Retina, render distance 16, BSL at 60% world resolution, view still unless noted. Whole Mac (`watts=30`) and
GPU alone (IOReport "GPU Energy" counter), 30 s each:

| Setup | Rendered / shown a second | Whole Mac | GPU |
|---|---|---|---|
| BSL, every frame rendered | 54 / 54 | 23.6 W | 9.8 W |
| BSL, Ultra, 30 rendered | 20 / 60 | 9.0 W | 1.9 W |
| BSL, Ultra, 10 rendered | 9 / 54 | 6.4 W | 0.9 W |
| BSL, Ultra, 5 rendered | 5 / 60 | 6.7 W | 1.1 W |
| BSL, Ultra, 2 rendered | 2 / 60 | 5.7 W | 0.7 W |
| **BSL, Ultra, 1 rendered** | **1 / 60** | **5.4 W** | **0.5 W** |
| BSL, Ultra, 1 rendered every 3 s | 0.33 / 60 | 4.7 W | 0.35 W |
| BSL, Ultra, 1 rendered every 10 s | 0.1 / 60 | 4.6 W | 0.33 W |
| **BSL, Ultra, 1 rendered, 30 shown** | **1 / 30** | **4.3 W** | **0.33 W** |
| Without shaders, every frame rendered | 60 / 60 | 4.7 W | 0.53 W |
| Without shaders, Ultra, 10 rendered | 9 / 60 | 4.5 W | 0.40 W |
| Without shaders, Ultra, 1 rendered | 1 / 60 | 4.3 W | 0.37 W |
| Without shaders, Ultra, 1 rendered every 10 s | 0.1 / 60 | 4.3 W | 0.33 W |
| Without shaders, Ultra, 1 rendered, 30 shown | 1 / 30 | 3.7 W | 0.19 W |
| Pause menu, without shaders | | 3.9 W | 0.57 W |
| BSL, turning 20°/s, every frame rendered | 60 / 60 | 23.8 W | 9.6 W |
| BSL, turning 20°/s, Ultra with its usual triggers | 16 / 60 | 7.7 W | 1.1 W |
| BSL, turning 20°/s, Ultra, 1 rendered | 1 / 60 | 5.8 W | 0.5 W |

- A BSL frame costs the GPU about 180 mJ; a generated frame about 5.6 mJ, 32 times less. Without shaders a rendered frame
  costs about 9 mJ and a generated one about 6 mJ, so there Ultra saves little.
- A single BSL frame rendered on its own, once a second, costs the whole Mac about 1 J (5.35 W at one a second, 4.71 W at
  one every 3 s), three times what a frame costs when the pack renders 54 a second (about 0.36 J): the chip wakes up for it.
  At one every 10 s BSL draws within 0.3 W of the game without shaders.
- A generated frame costs the whole Mac about 36 mJ (60 shown against 30 shown, both with BSL): mostly the processor running
  the game loop and drawing the HUD, not the GPU. Showing 30 instead of 60 is the next largest saving after rendering less.
- No point was found where generating frames costs more than rendering them. The savings flatten out below one rendered frame
  every 3 s: what is left is the screen, the processor running the game at 60, and the GPU drawing the HUD.
- At 10 rendered a second BSL showed 54: its frames take 19 ms of GPU time, longer than a 60 Hz frame, so each rendered
  frame delays the one shown after it.
- The cost is in the picture: at 1 rendered a second, mobs, water and animated textures move once a second, and when
  turning, the side the old frame never saw is filled from its nearest edge until the next frame is rendered.
- In the 5-rendered run mobs nearby triggered more real frames than in the 10-rendered run, so it drew a little more.
- With a pack, a generated frame no longer chooses which chunk sections cast shadows (Java Flight Recorder: level extraction
  on the render thread halved, 48 to 24 samples in 30 s). Whole-Mac power stayed within run-to-run noise (5.2-5.8 W).

### Away from the keyboard

After a minute without input the picture is held (Ultra with no rendered frames, 30 shown) and memory is given back.
Fullscreen Retina, render distance 16, self-test step `afk=on`:

| Setup | Whole Mac | GPU | Game's memory (footprint) |
|---|---|---|---|
| BSL at 60% | 3.9 W (23.6 W playing) | 0.15 W | 2,609 -> 1,922 MB: pack targets -189 MB, picture compressed -41 MB, Java heap 1,065 -> 596 MB committed |
| Without shaders | 3.65 W (4.7 W playing) | 0.16 W | 1,963 -> 1,627 MB: Java heap 872 -> 536 MB committed |

Without shaders, the GPU memory rose 16 MB: Frame Generation wasn't running before, so the held picture's snapshot is new.

## Neural Engine features

| Feature | Before | Now |
|---|---|---|
| Style Filter (a painting's style over the world) | 38 ms a picture, ~26 a second, 870 fps* | 10 ms, 60 a second, 995 fps* |
| Neural Engine upscaler | Not available with shader packs; at most 1280-wide pictures; a ~1 s stop at every size change | Shader packs, any size (tiles), started in the background |
| Smooth Textures | 1,254 sprites upscaled in 167-182 ms at load; +0.2 ms a frame* | Unchanged |

\* 1920 x 1080 window, render distance 6, no shader pack (base ~1,250 fps).

## How to reproduce

`tools/bench-round.sh` runs a list of configurations through the self-test harness (`tools/cadence-test.sh`). Each
configuration runs in its own launch, with a cool-down between launches. See CONTRIBUTING.md for the harness.
