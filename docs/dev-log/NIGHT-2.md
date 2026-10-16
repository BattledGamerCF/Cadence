# Night 2 (2026-10-16): more performance, then hunting for bugs

Same rules as the first night: every speed change is measured in one session (interleaved A/B, or alternating launches
over the same restored world), pictures are checked, and a change that doesn't clearly help is left out.

## Part 1: performance

### Flying over new terrain (the frame-rate cap while terrain generates)

`EdgeCap` (on in the default profile) caps the frame rate at twice the display's refresh rate while the integrated server
generates new terrain near the player, so world generation gets the CPU it needs. Measured on the M2 Air (flight at 20
blocks a second, the same restored world each run):

| | Render distance 12: fps / chunks loaded ahead (of 12) | Render distance 16: fps / chunks ahead (of 16) |
|---|---|---|
| Old rule (cap while any terrain generates near) | 119 / 11.8 | 119 / 15.7 |
| No cap | 408 / 11.8 | 326 / **9.0** (the edge goes empty) |
| **New rule** (cap only while a chunk 1 inside the render distance is missing) | **385-400 / 11.2-11.7** | **288 / 15.7** |
| (same, 2 inside) | 400 / 11.7 | 246 / 14.1 |

So the cap only switches on when terrain is actually behind: 2.4-3.4x the frame rate while flying, with the edge kept
as full as before. Runs where the Mac was swapping (the memory guard stopped one) were thrown out and re-run.

## Part 2: trying to break it

Test matrix (each a self-test plan; anything that crashes, errors, renders wrong or leaks is a bug):

- shader packs: switch on/off/between packs repeatedly, while flying, while chunks load, during a dimension change,
  right after joining; Shader Pack Settings for every pack (every page, every option clicked, sliders, reset)
- world: leave and rejoin a world with and without a pack; change render distance live; change GUI scale and window
  size with a pack; fullscreen toggle
- resources: F3+T (resource reload) with and without a pack; World Resolution changes; Auto under load
- stress: memory pressure and thermal states (simulated), the busy scene with a pack, glowing entities with a pack,
  spectator mode
- settings: every Cadence button cycled through every value; Quick Setup; presets
