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

### Results so far

| Test | Found | Fixed |
|---|---|---|
| Rapid pack switching (12 switches, 3 s apart) | No crash; memory returns to ~330 MB at every "off" (no leak); a 1.6-1.8 GB short peak on direct pack-to-pack switches while the old pack is released | - |
| Dimensions, render distance live, leave and rejoin (with and without a pack) | Nothing broken; the stall tracer mistook world loading for a stall | Tracer ignores loading screens |
| Every pack's Shader Pack Settings clicked and reloaded (12 packs) | All reload cleanly ("White World" in Sildur's is the pack's own white mode); VanillaEX's 3-column layout put spacers in the wrong places | Each page's own column count honoured |
| Every Cadence button cycled, fullscreen, GUI scale, F3+T, spectator, glowing mob, simulated heat and memory pressure, Quick Setup | Nothing broken (glowing outline draws with BSL; Cooling holds the right rate in every mode) | (test tool: click matched a status line) |
| Pack switching while flying | Hand-over works mid-flight; memory back to 338 MB each time | - |
| **Damaged config files** | **A malformed line in a config crashed the launch** ("A mod crashed on startup"); a deleted pack's chat message showed the full disk path; an unreadable Resolution fell back to a fixed 100% | Tolerant loader everywhere (12 call sites), plain message, Auto fallback |
| Every sharpening mode (Apple, Smooth, Fast) at 70% with and without BSL | All pictures correct (the old lazy-clear bug lived in this path) | - |
| Pack Speed-ups check (BSL, Complementary, no pack) | Works: SAFE verdicts match its picture comparisons; refuses without a pack | - |
| Busy scene (320 mobs) with BSL, Complementary, Insanity, off | No errors; the slower frame rate after the packs is the scene's own drift (474 -> 380 fps over 70 s with no pack at all) | - |
| Window resized repeatedly with BSL (and World Resolution 70%) | No errors, pictures correct | - |

### Known, not fixed tonight

- After a pack has been on, Cadence's own terrain keeps its 64 MB vertex arena reserved (it has no full shutdown).
- Turning a pack on or changing its settings recompiles its shaders on the render thread: a 0.5-3 s pause, as in Iris.
- A compile that started just before a pack switch can finish after it; the switch's full rebuild redoes it, so at most a
  chunk can be missing for a moment.
- Multiplayer servers weren't tested (no local server without downloading one).
