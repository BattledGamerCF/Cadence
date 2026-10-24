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
  ~53 us, tools/m4_floor.m);
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
record.metal (`RB_NO_TRACE`, `RB_NO_HUD`, `RB_NO_LIGHT`, `RB_NO_ENT`, `RB_STEPS`, `RB_SIMPLE_HIT`, `RB_MIN_TRACE`,
`RB_BRANCHY`), alternated in rounds so the Mac's heat drifts across all of them; `RB_PNG` writes the frame.

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

North view (dump-north, hot GPU): 145 us/frame at the start of the night's pass, 95 us after (entities still the old
whole-screen cow in that dump).
