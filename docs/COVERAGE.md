# What Cadence covers

Every part of Minecraft that runs while you play, and what Cadence does there. "Vanilla" means Minecraft's own code runs
unchanged. Shares are measured on an M2 MacBook Air (8 GB) with Java Flight Recorder, in a flight over new land with mobs
around (2026-10-23), so they say where time goes in ordinary play.

## Drawing: all of it on Metal

With Cadence's Metal renderer on (the default on Apple Silicon), OpenGL is never used. Minecraft draws through its own GPU
abstraction (renderpearl), and Cadence implements that abstraction on Apple's Metal: devices, buffers, textures, render passes,
pipelines and presentation (`dev.cadence.metal`). Minecraft's shaders are translated from GLSL to Metal's shading language
when the game loads them (cached on disk), or replaced by hand-written Metal versions (`resources/cadence/native/`). Shader
packs are translated the same way.

| Part | What Cadence does |
|---|---|
| Terrain | Its own terrain renderer: chunk meshes in one GPU arena, culled on the GPU (frustum, occlusion with a depth pyramid, hidden faces), meshing caches (block states, light, tints), Smart Leaves |
| Far terrain | Its own Distant Horizons-style far terrain, generated from the world's generator or from explored land, drawn by shader packs that support Distant Horizons |
| Entities and block entities | Culling of what can't be seen, faster model and item vertex writing, dynamic lights |
| Particles | Culling, cheaper movement and lighting |
| Sky, clouds, weather | Clouds drawn from only the faces that can be on screen |
| Text and GUI | Faster text layout (bidirectional text, glyph matrices), crisp text at any resolution, a compact Unicode font, lazily made HUD and panorama targets |
| Shader packs | Run on Metal (Iris's format, compute, images, buffer objects, Distant Horizons programs), shadows reused every other frame, shadow casters that can't cast skipped; Iris's public API answered for other mods |
| Resolution | Auto resolution, World Resolution, Retina off, MetalFX upscaling |
| Frame timing | Exact frame caps, even pacing, refresh-locked presents on ProMotion, Heat Control, On Battery, In Background, Render Max FPS |

## Running the game

| Part | What Cadence does | Still vanilla |
|---|---|---|
| Chunk meshing | Caches, a dynamic compile queue, released compile memory | |
| World generation | Pooled noise buffers, cached structure palettes, structure placement, chunk map work in parallel, worker priorities | Surface rules (~30% of chunk generation) and aquifers: speeding them up exactly would mean rewriting rule trees that data packs can define |
| Chunks | Light storage maps, block storage, points of interest, chunk loading slots, region file writes | Light propagation (2-4% of its threads) |
| Entities | Brains (AI), villager sensors, path type caches, entity section lookups, ticking by distance, collisions with air answered at once (collisions were 11% of the server tick) | Natural spawning (~12% of the server tick when exploring): its cost is spread over many small lookups |
| Block entities | Ticking only what needs a tick | |
| Threads | Each thread on the right kind of core (render and server on performance cores, workers below) | |
| Memory | A garbage-collection policy for games, heap and native memory trimming, compacted chunk heaps | |
| Startup | Cached block and bootstrap data, every block state's cache built in parallel (the same values), the crash-report warm-up and the narrator off the main thread, classes preloaded in the background, a 0.4 s loading fade | |
| Java | Startup settings tuned for Minecraft, the optional Vivace Java build | |
| Multiplayer | Keep Server Chunks: chunks a server forgets kept within your render distance (the server's shorter view distance no longer limits it); far terrain from explored land | Packet handling |
| Networking, sound | | Vanilla (under 2% of any thread) |

## Other mods

Where another mod hooks code Cadence replaces, Cadence runs Minecraft's own code there for it and keeps everything else
(docs/COMPATIBILITY.md). Mods that call OpenGL directly need an addon written against Cadence's API (docs/API.md).
