# Render Max FPS / Record Mode (10-23 night)

Goal: beat the Minecraft FPS record on Hone's list (21,632 fps, NotroDan) with frames really rendered, and show twice that
with frame interpolation, counted apart. The rules (hone.gg/fps-records/minecraft):

- R01 a playable resolution ("if major parts of game functionality don't work at your resolution, increase it");
- R02 say what was used and how (this file, the F3 text on screen);
- R03 no mods that make the game unplayable by removing large chunks of code or not rendering blocks or entities;
- R04 don't trick the FPS counter: every frame really rendered.

## What it is

**Render Max FPS** (Video Settings > Cadence: Extras) turns it on; everything else is automatic:

- every frame one compute dispatch (`record.metal`, `record_trace`), no render pass (a render pass alone costs the M2's GPU
  ~53 us, tools/gpu_frame_floor.m);
- the client's loaded blocks in a 64 x H x 64 voxel window around the camera (2 chunks each way: the game's smallest render
  distance), `RecordWorld`, kept up to date from the game's own section updates (block and light changes);
- **1x1 textures**: each block face is its texture's average colour times its tint (grass, leaves, water by biome);
- **shading as vanilla's flat lighting** (Smooth Lighting off): per face the light of the block it faces (sky << 4 | block, from
  the game's light engine), darkened for the time of day (`Level.getSkyDarken`), vanilla's lightmap curve and the Brightness
  option (a 16-entry table the CPU fills), times the face's direction shade (top 1.0, bottom 0.5, x 0.6, z 0.8);
- crossed plants as their two diagonal quads, smaller shapes (slabs, carpets, ...) as their box, fluids as cubes, seen through
  from inside;
- entities as lit boxes; the targeted block's outline and break progress; sky and fog darkening at night;
- the HUD the most basic way: hotbar (each item its colour, the selected slot outlined), health, food, XP, air underwater, hurt
  flash, water/lava view tint, crosshair, an F3 block (version, fps, XYZ, facing, resolution, GPU time, best) and the last
  chat lines (10 s);
- any screen (pause, inventory, chat, death) draws the game as usual;
- 320 x 180 (16:9 of the window), no upscaling (`RenderScale` returns 1 and no MetalFX in Max FPS), no frame limit or vsync
  (frames past one a refresh aren't presented), 16 frames in flight, input polled at 1 kHz;
- **frame interpolation** in the same dispatch: the frame halfway between the last two (reprojection by depth; with the camera
  still, their blend). The counter shows frames shown and, of them, rendered.

`-Dcadence.maxfps.blank=true` brings back the old black-screen curiosity (not record-legal).

## Measuring without the game

`tools/record_bench.m` times `record_trace` on a world dumped from the game: the self-test step `recorddump=name` writes
`metal/run/screenshots/dump-name/` (frame, grid, palette, occupancy, light, entities, text). Variants by `-D` macros in
record.metal (today: `RB_NO_TRACE`, `RB_NO_HUD`, `RB_NO_LIGHT`, `RB_NO_ENT`, `RB_NO_CELLS`, `RB_STEPS`, `RB_STOP`, `RB_FLOOR`;
earlier experiments' macros are gone with them), alternated in rounds so the Mac's heat drifts across all of them; `RB_PNG` writes the frame.

    clang -fobjc-arc -O2 -framework Metal -framework Foundation -framework ImageIO -framework CoreGraphics tools/record_bench.m -o /tmp/record_bench
    /tmp/record_bench metal/run/screenshots/dump-north metal/src/main/resources/cadence/record/record.metal 2000 3 - RB_NO_HUD

It reproduces the game's GPU time per frame within a few percent.

## Findings (the M2 Air, hot: GPU clocks about half of cool)

- A hot M2 Air runs the GPU at about half speed: the same view measured 61 us/frame at 21:34 and 140 us at 22:15. Only A/Bs
  within one run mean anything; record attempts start cool.
- The record frame's own CPU time is ~8 us; the rest of the frame interval was waiting on the GPU.
- **Entities behind the eye got a whole-screen rectangle**, so every pixel ray-tested them: 5 of 17 in one view, 28 us of
  35. Now: boxes wholly behind are dropped, boxes crossing the near plane are clipped at it (their rectangle is the part in
  front), only a box the eye is inside covers the screen.
- **Empty-space skipping didn't pay** in a 64-block window: the brick distance field and the per-brick loop cost more than
  they saved (a plain voxel walk was 25-30% faster), and rays ran to the window's edge although fog is total at 28 blocks.
  Now: a plain Amanatides & Woo walk, branch-free steps, ended where fog is total. The distance field (and its CPU upkeep) is
  gone.
- Not it: memory latency per step (caching a brick layer's occupancy bits in registers was slower), the number of steps
  before the surface (starting rays at the last frame's depth changed nothing: a ray takes ~10 steps), threadgroup shape.
- Hit shading: a 16-byte palette entry (tint pre-multiplied on the CPU) instead of 64, shading after the walk.
- Lighting as a 16-entry table; HUD text rejects pixels outside its block before any division.
- Interpolation with the camera still is the two frames' blend (2 reads instead of 5 and a reprojection): ~1.5 us.

- **Entity tiles**: even small rectangles cost every pixel a test per entity (12 entities: ~18 us). The CPU now bins them into
  8 x 8-pixel tiles (a 32-bit mask per tile, behind the boxes in their buffer): a pixel tests only those touching its tile.
- **The HUD drawn by the CPU when it changes** (`RecordHud`: F3 text and chat at most 4 times a second, hotbar and bars when a
  value changes) into an RGBA overlay the frame blends with one read a pixel: ~9 us -> ~1 us.
- **Frames batched per command buffer**: a command buffer costs ~20 us of its own on the M2 (an empty-kernel frame: 34k/s one
  per command buffer, 80k/s four per). Record frames are committed 4 to a command buffer (`-Dcadence.record.batch`); each is
  still its own dispatch rendering its own image, and a frame that presents commits at once. In-game interleaved A/B:
  10.9k -> 12.6k rendered (+16%).
- **Linear voxels** (x fastest, then z, then y) instead of 8^3 bricks: the occupancy bits are 196 KB, cached whole; the
  address is shifts and ors: 5% faster.
- Height-field cells (highest block per 8 x 8 columns) let a ray above the terrain cross a cell in one step, in a loop of its
  own before the voxel walk: checks inside the walk cost ground rays more than they saved.
- Tried and dropped: caching a brick layer's occupancy bits in registers (slower), starting rays at the last frame's depth
  (no change), threadgroup shapes (no change).

| North view, hot GPU (offline bench) | us/frame |
|---|---|
| start of the night's pass | 145 |
| entities behind the eye dropped, plain fog-capped walk, compact palette | 95 |
| entity tiles, HUD overlay | 74 |

In game (moderately warm Mac, north view, 320 x 180, interpolation on): 12.6k rendered / 25.2k shown, GPU 72 us a frame.

## Review (docs/dev-log/RECORD-REVIEW.md) and what came of it

A read-only Sonnet review found, among others: the record frame was presented over every screen (pause, inventory, death
showed a frozen world); the record image was presented **upside down** (the game's targets are stored bottom row first and
the present flips them; the self-test's snapshot read the texture raw, so it looked right); F5 filled the screen with the
player's box; the window reached only 16 blocks in +x/+z; off/on kept a stale world; the level leaked after leaving it; tile
masks were zeroed under in-flight frames. All fixed (commit 14d3ede); third person now pulls the eye back as vanilla does.
Also: every other present shows the interpolated frame (it was made but never shown).

Tried and dropped: see-through glass, ice and water. Every variant (in the walk, a second walk after the hit, packed state)
cost every ray 7-15% (register pressure: this kernel's speed follows its register count), so water and glass are opaque, in
their average colour.

## Where it stands (cool M2 MacBook Air, 8 GB, macOS 27.0, 320 x 180, interpolation on)

(The "looking straight down" row first written here, 25,300 rendered, was a flat-colour frame: see the 10-25 correction below.)

| View | rendered fps | shown fps | GPU us/frame |
|---|---|---|---|
| forest, looking 60 degrees down | 14,500 | 29,000 | 64 (bench) |
| forest, looking south / north | 15,200-16,100 | 30,400-32,300 | 56-59 |
| 160 x 90 (not playable: the CPU's limit) | 51,000-65,000 | | 13-17 |

- The CPU's own frame is ~15 us (4 us in Record, ~11 in the game's loop): at 320 x 180 the GPU is the limit, and the
  trace's cost follows the slowest ray in each 32-pixel SIMD group (~8 voxel steps a ray in a forest, ~2 looking down).
- The M2 Air's GPU throttles within a minute or two of full load (the same view 61 -> 140 us/frame hot). The counter's
  "Best" (a 1-second window) keeps the peak.

## A record attempt

1. Let the Mac cool (lid open, nothing else running, ~20 minutes idle); plugged in.
2. In a world: Video Settings > Cadence: Extras > Render Max FPS: On. Leave the menu.
3. Look at the view you want to show, stand still, wait ~5 s (the recipe that follows the 10-25 correction is at the end).
4. The F3 text shows "N fps (M rendered)" and "Best": record both and a screenshot/video. Rendered frames alone count against
   the record; the interpolated frames are counted apart.

Disclosure for Hone (R02): MacBook Air M2 (8-core GPU), 8 GB, macOS 27.0; Minecraft 26.3, Fabric, Cadence (native Metal
backend) with Render Max FPS: every frame one GPU compute pass ray-tracing the loaded world (2 chunks each way) at 320 x 180,
each block face its texture's average colour (1x1 textures) with vanilla's flat lighting, entities as boxes, the HUD drawn
by the CPU when it changes; no upscaling; frame interpolation counted separately; frames past one a refresh aren't presented
(as with VSync off), and 4 frames are committed to a command buffer (each its own dispatch and image).

## 10-24 night: 66k rendered, the world under screens

- **Screens**: Max FPS no longer hands the world to the game's own renderer when a screen opens (its world had gone stale:
  the glitch with the inventory and chat). The record frame keeps being made and is drawn into the game's target
  (`mcrm_draw`), and the game draws the screen and its own HUD over it. The game's clear of its target is deferred to its
  next pass (the GUI's) by the backend, which wiped the frame: dropped first (`dropPendingClear`).
- **One word a voxel**: air holds its light, a full block its top and side colours (RGB555), any other block its palette
  entry and its own light. A full block's face is lit by the air word the ray just came through, so a hit reads nothing
  more. (Occupancy bits and the light buffer are gone.)
- **Height-field cells** (highest block per 8 x 8 columns) in a loop of their own before the voxel walk, and the window's
  top from them: rays above the terrain cross a cell a step. Sky views 3.5x faster (30 degrees up: 5.4k -> 33k rendered),
  ground views the same. (Tried before with the camera under a tree, where it never engaged.)
- **Fences out**: the frames were made untracked and ordered by Metal fences so they could overlap; the bench, made faithful
  to the fence waits (`RB_FENCE`), showed a fence wait costing ~25 us: 36k vs 65k rendered for a sky frame. Back to Metal's
  own tracking (5-slot ring, the in-between frame from N-2 and N-3).
- Tried and dropped: batched voxel loads (4 at a time: 40% slower, registers), a column-top table (no gain), thread
  coarsening (2 pixels a thread: worse), skipping the HUD's empty rows (14% worse: the load issued late), depth only while
  moving and a one-read in-between frame (no gain), a hybrid of occupancy bits and words (worse).

The table first written here (sky 66,200, looking down 24,000-27,000) was measured at exactly +-90 degrees of pitch, where
the camera was broken: see the correction below.

## 10-25 correction: the camera at +-90 degrees, and the honest numbers

The bug hunt of 10-24 (BUG-HUNT-10-24.md, BH3-01) found that the record camera's right vector was made from the forward vector,
`normalize(forward x up)`, which has no length at a pitch of exactly +-90 degrees. Vanilla clamps the pitch to exactly that
when you look all the way up or down, and the self-test's poses were `tp ... 0 90` and `tp ... 0 -90`. At those poses every
pixel got the same ray, so the frame was one flat colour (the block below, or the sky). **Every "looking down" number above
(25,300 and 24,000-27,000 rendered) was a flat-colour frame, and so was the first sky number.** The right vector is now made
from the yaw (`(-cos yaw, 0, -sin yaw)`), which is exact at every pitch.

Measured again (1025-004317, M2 Air warm after the night's runs, 320 x 180, interpolation on; screenshots checked: every
frame is the real view):

| View (in game) | rendered fps | shown fps | GPU us/frame |
|---|---|---|---|
| straight up from a pillar 60 blocks above the trees (the whole frame is sky, every ray traced to the window's edge) | 64,000-66,500 | 128,000-133,000 | 13-16 |
| 80 degrees up from the pillar (sky) | 50,800-54,900 | 101,500-109,700 | 16-17 |
| 30 degrees up from the pillar (sky and the treetops to the horizon) | 21,000-22,400 | 42,000-44,800 | 42-70 |
| straight down / 85 degrees down in the forest (now the real canopy) | 13,200-13,400 | 26,400-26,900 | 68-160 |

- Above 40,000 rendered: the sky views from the pillar (the frames the game itself shows there: open sky). Views with terrain
  in them stay under 25,000 rendered on this Mac; the honest ground number is ~13,000, not 25,000.
- The GPU time per frame swings 2x between readings of the same view (the M2 Air's clocks under sustained load).

### The walk in two loops (10-25)

The voxel walk's loop held the tests of every shape (crossed plants, boxes, fluids): their registers were held across every
step. Now the inner loop crosses air only (one load, one test a voxel); the voxel that stops it is either a full block (the
hit) or a shape, tested outside the loop, and the walk goes on past it when the ray misses; one budget of 96 voxels for both
(with a budget per loop, rays past 8 plants in a row gave up: a few sky pixels at the horizon). The image is the same, pixel
for pixel, in 7 dumped views (tools/record_bench.m, RB_PNG).

| Offline bench (same run, variants alternated) | before us/frame | after |
|---|---|---|
| forest, south | 76.5 | 58.9 |
| forest, 60 degrees down | 68.4 | 58.7 |
| forest, north | 60.3 | 53.7 |
| under the canopy, 15 degrees up | 58.9 | 61.4 |
| north, open | 26.0 | 27.3 |
| pillar sky | 14.5 | 14.2 |

Tried with it and dropped: the next voxel worked out while the load is in flight (1-4%, within the noise).

In game (1025-011556, the same poses as above):

| View | rendered fps | shown fps |
|---|---|---|
| 80 degrees up from the pillar (sky) | 65,900 | 131,900 |
| 30 degrees up from the pillar (sky and treetops) | 30,700 (was 21,000-22,400) | 61,300 |
| straight down into the forest | 14,800 (was 13,200) | 29,700 |
| 60 degrees down | 15,200 | 30,500 |
| forest, level, south / north | 14,000 / 11,200-11,500 | 28,000 / 22,400-23,000 |

The record attempt (corrected): stand on a pillar above everything around (any block, ~60 blocks up), look up (~80 degrees or
straight up), stand still ~5 s, read the counter and "Best". Say in the submission that the view is the sky. With terrain in
view, 30 degrees up from the pillar: ~30,000 rendered, above the record (21,632) with the land in the picture.
