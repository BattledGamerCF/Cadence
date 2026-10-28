# Minimal Renderer

Video Settings > Cadence: Speed > **Minimal Renderer** (called Render Max FPS before 0.4.0-beta.26) draws Minecraft at the highest frame rate this Mac can render it while
the game stays playable. It is Cadence's run at the Minecraft FPS record on Hone's list (21,632 fps), and it follows that
list's rules: a playable resolution, the hardware and method disclosed, the world and entities rendered, every frame really
rendered (interpolated frames are counted apart).

## What it draws

- Every frame is one GPU compute dispatch (`record.metal`, `record_trace`), no render pass: the client's loaded blocks in a
  64-block window around the camera (`RecordWorld`, kept up to date from the game's own section and light updates),
  ray-traced at 320 x 180 (16:9), no upscaling.
- **1x1 textures**: each block face is its texture's average colour times its tint (grass, leaves, water by biome).
- **Shading as vanilla's flat lighting**: the sky and block light of the block a face looks into, darkened for the time of
  day, vanilla's lightmap curve and the Brightness option, times the face's direction shade.
- Crossed plants as their two quads, smaller shapes (slabs, carpets...) as their box, fluids as cubes; entities as lit
  boxes; the targeted block's outline and break progress; sky and fog, night.
- The HUD (`RecordHud`, drawn by the CPU when it changes): hotbar, health, food, XP, air, hurt flash, water and lava tint,
  crosshair, chat, and an F3 block (version, fps, position, facing, resolution, GPU time, best).
- Any screen (inventory, chat, pause, death): the record frame is drawn into the game's own target and the game draws its
  HUD and the screen over it, so the world stays in view.
- **Frame interpolation** in the same dispatch: the frame halfway between two rendered ones, by reprojection with their
  depth. The counter shows frames shown and, of them, rendered.
- Frames are committed 4 to a command buffer (each its own dispatch and image), input is polled at up to 1 kHz, frames past
  one a display refresh aren't presented (as with VSync off).

`-Dcadence.maxfps.blank=true` brings back the old black-screen mode (not record-legal).

## Results (M2 MacBook Air, 8 GB, macOS 27, 320 x 180, interpolation on)

| View | rendered fps | shown fps |
|---|---|---|
| sky, from a pillar above the trees (~60 blocks up), looking up | 65,000-66,000 | 130,000-132,000 |
| 30 degrees up from that pillar (sky and the land to the horizon) | 30,500 | 61,000 |
| looking straight down into a forest | 16,000 | 32,000 |
| level, in a forest | 11,700-14,900 | 23,000-30,000 |

- The GPU is the limit with terrain in view; the CPU's own frame is ~15 us (that caps sky views at ~66,000).
- A hot M2 Air runs its GPU at about half speed: start record attempts cool, and compare changes only within one run.
- Earlier "looking down: 25,300" figures (0.4.0-beta.15/16) were wrong: at exactly +-90 degrees of pitch the camera's sideways
  vector had no length and every pixel showed one colour. Fixed in beta.17; every number above is checked against its
  screenshot.

## A record attempt

1. Let the Mac cool (lid open, nothing else running, ~20 minutes idle), plugged in.
2. In a world: Video Settings > Cadence: Extras > Minimal Renderer: On. Leave the menu.
3. Stand on a pillar above everything around (~60 blocks up). Look up (~80 degrees, or 30 degrees for a view with the land
   in it), stand still ~5 s.
4. The F3 text shows "N fps (M rendered)" and "Best": record both with a screenshot or video. Rendered frames alone count
   against the record; say in the submission what the view is.

Disclosure for Hone: MacBook Air M2 (8-core GPU), 8 GB, macOS 27; Minecraft 26.3, Fabric, Cadence (native Metal backend)
with the Minimal Renderer: every frame one GPU compute pass ray-tracing the loaded world (2 chunks each way) at 320 x 180, each
block face its texture's average colour with vanilla's flat lighting, entities as boxes, the HUD drawn by the CPU when it
changes; no upscaling; frame interpolation counted separately; 4 frames committed to a command buffer.

## How it was made fast (measured, kept)

- Entities: boxes behind the eye dropped, boxes crossing the near plane clipped; the CPU bins entity rectangles into 8 x 8
  pixel tiles so a pixel tests only the boxes touching its tile.
- One 32-bit word a voxel: air holds its light; a full block its top and side colours (RGB555); any other block its palette
  entry and light. A full block's face is lit by the air word the ray just crossed, so a hit reads nothing more.
- Height-field cells (the highest block per 8 x 8 columns) let rays above the terrain cross a cell a step (sky views 3.5x).
- The voxel walk in two loops: the inner one crosses air only (one load and one test a voxel); full blocks and shapes stop
  it and are tested outside. Each voxel's word is loaded a step ahead. Together 10-45% with terrain in view, pixel-identical.
- 4 frames per command buffer (+16%); the HUD drawn by the CPU into an overlay (9 us -> 1 us); linear voxel layout (+5%).
- Tried and dropped (slower or no gain): a brick distance field, see-through glass and water (register pressure: this
  kernel's speed follows its register count), batched voxel loads, thread coarsening, a column-top table, Metal fences to
  overlap frames (a fence wait costs ~25 us), the grid as a tiled 3D texture.

## Measuring without the game

The self-test step `recorddump=name` writes the current record world to `metal/run/screenshots/dump-name/`;
`tools/record_bench.m` times `record_trace` on it, with variants by `-D` macro in `record.metal` (`RB_NO_TRACE`, `RB_NO_HUD`,
`RB_NO_ENT`, `RB_NO_CELLS`, `RB_STEPS`, ...) alternated in rounds so the Mac's heat drifts across all of them; `RB_PNG=path`
writes a frame (compare variants pixel for pixel before keeping one).

    clang -fobjc-arc -O2 -framework Metal -framework Foundation -framework ImageIO -framework CoreGraphics tools/record_bench.m -o /tmp/record_bench
    /tmp/record_bench metal/run/screenshots/dump-name metal/src/main/resources/cadence/record/record.metal 2000 4 - RB_NO_HUD
