# Minimal renderer

Video Settings > Cadence: Speed > **Minimal Renderer** (called Render Max FPS before 0.4.0-beta.26) draws Minecraft at the
highest frame rate this Mac can render while the game stays playable. It is Cadence's attempt at the Minecraft FPS record on
Hone's list (21,632 fps). It follows that list's rules:

- a playable resolution
- the hardware and method disclosed
- the world and entities rendered
- every frame really rendered (interpolated frames are counted apart)

## What it draws

- Every frame is one GPU compute dispatch (`record.metal`, `record_trace`), with no render pass. The dispatch ray-traces the
  client's loaded blocks in a 64-block window around the camera (`RecordWorld`, kept up to date from the game's own section
  and light updates). It renders at 320 x 180 (16:9), with no upscaling.
- **1x1 textures**: each block face is its texture's average colour times its tint (grass, leaves and water by biome).
- Shading follows vanilla's flat lighting. It uses the sky and block light of the block a face looks into, darkened for the
  time of day with vanilla's lightmap curve and the Brightness option, times the face's direction shade.
- Crossed plants are drawn as their two quads. Smaller shapes (slabs, carpets...) are drawn as their box, and fluids as cubes.
- Entities are lit boxes. The targeted block's outline and break progress, sky, fog and night are also drawn.
- The HUD (`RecordHud`) is drawn by the CPU when it changes. It shows the hotbar, health, food, XP, air, hurt flash, water
  and lava tint, crosshair and chat, plus an F3 block (version, fps, position, facing, resolution, GPU time, best).
- On any screen (inventory, chat, pause, death), the record frame is drawn into the game's own target. The game draws its HUD
  and the screen over it, so the world stays in view.
- **Frame interpolation** happens in the same dispatch. The frame halfway between two rendered ones is made by reprojection
  with their depth. The counter shows frames shown and, of them, rendered.
- Frames are committed 4 to a command buffer, each with its own dispatch and image.
- Input is polled at up to 1 kHz.
- Frames past one a display refresh are not presented, as with VSync off.

`-Dcadence.maxfps.blank=true` brings back the old black-screen mode. It is not record-legal.

## Results

Measured on an M2 MacBook Air, 8 GB, macOS 27, at 320 x 180 with interpolation on.

| View | Rendered fps | Shown fps |
|---|---|---|
| Sky, from a pillar above the trees (~60 blocks up), looking up | 65,000-66,000 | 130,000-132,000 |
| 30 degrees up from that pillar (sky and the land to the horizon) | 30,500 | 61,000 |
| Looking straight down into a forest | 16,000 | 32,000 |
| Level, in a forest | 11,700-14,900 | 23,000-30,000 |

- The GPU is the limit with terrain in view. The CPU's own frame is ~15 us, which caps sky views at ~66,000.
- A hot M2 Air runs its GPU at about half speed. Start record attempts cool, and compare changes only within one run.
- Earlier "looking down: 25,300" figures (0.4.0-beta.15/16) were wrong. At exactly +-90 degrees of pitch the camera's sideways
  vector had no length, and every pixel showed one colour. This was fixed in beta.17. Every number above is checked against
  its screenshot.

## A record attempt

1. Let the Mac cool (lid open, nothing else running, ~20 minutes idle), and plug it in.
2. In a world, go to Video Settings > Cadence: Extras > Minimal Renderer and set it to On. Leave the menu.
3. Stand on a pillar above everything around (~60 blocks up). Look up (~80 degrees, or 30 degrees for a view with the land in
   it) and stand still for ~5 s.
4. The F3 text shows "N fps (M rendered)" and "Best". Record both with a screenshot or video. Only rendered frames count
   against the record. State in the submission what the view is.

Disclosure for Hone: MacBook Air M2 (8-core GPU), 8 GB, macOS 27; Minecraft 26.3, Fabric, Cadence (native Metal backend)
with the Minimal Renderer. Every frame is one GPU compute pass ray-tracing the loaded world (2 chunks each way) at 320 x 180.
Each block face is its texture's average colour with vanilla's flat lighting, entities are boxes, and the HUD is drawn by the
CPU when it changes. There is no upscaling, frame interpolation is counted separately, and 4 frames are committed to a
command buffer.

## How it was made fast

All of these were measured and kept.

- Entities: boxes behind the eye are dropped and boxes crossing the near plane are clipped. The CPU bins entity rectangles
  into 8 x 8 pixel tiles, so a pixel tests only the boxes touching its tile.
- Voxel storage: one 32-bit word per voxel.
  - Air holds its light.
  - A full block holds its top and side colours (RGB555).
  - Any other block holds its palette entry and light.
  - A full block's face is lit by the air word the ray just crossed, so a hit reads nothing more.
- Height-field cells (the highest block per 8 x 8 columns) let rays above the terrain cross a cell in one step (sky views 3.5x).
- The voxel walk runs in two loops. The inner one crosses air only, with one load and one test per voxel. Full blocks and
  shapes stop it and are tested outside. Each voxel's word is loaded a step ahead. Together these give 10-45% with terrain
  in view, pixel-identical.
- 4 frames per command buffer (+16%).
- The HUD is drawn by the CPU into an overlay (9 us -> 1 us).
- Linear voxel layout (+5%).

Tried and dropped (slower or no gain):

- a brick distance field
- see-through glass and water (register pressure: this kernel's speed follows its register count)
- batched voxel loads
- thread coarsening
- a column-top table
- Metal fences to overlap frames (a fence wait costs ~25 us)
- the grid as a tiled 3D texture

## Measuring without the game

The self-test step `recorddump=name` writes the current record world to `metal/run/screenshots/dump-name/`, so the
record kernel can be timed outside the game. The measurements above were made with a standalone Metal bench run on such
dumps, with variants alternated in rounds so the Mac's heat drifts across all of them, and compared pixel for pixel
before a variant was kept. The bench is a development tool and is not part of this repository.
