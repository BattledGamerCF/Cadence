# Bug hunt 2026-10-23 (audit of 0.4.0-beta.14, no fixes made)

Repo `cadence-metal`, HEAD 62cc85d (0.4.0-beta.14 / shaders 0.3.13). Audit only: no source, config or commit was changed in the
repo (this report is the only new file). Scratch scripts, logs and copies live in the session scratchpad
`/private/tmp/claude-501/-Users-robelder-Documents-CLAUDEEEEE/78917cae-bfca-47cb-8449-27755a81ca0d/scratchpad/bughunt2/`
(written `scratchpad/bughunt2/` below; macOS may clear `/private/tmp` at a reboot, the result folders under `metal/run/` stay).
Game-run results referenced below are in `metal/run/results/<MMDD-HHMMSS>/` (dev client, `tools/cadence-test.sh`) or
`metal/run-compat/results/<MMDD-HHMMSS>/` (18-mod production set). JVM crash logs of my runs are kept in `scratchpad/bughunt2/crashes/`.

Severity classes: crash/hang/data loss, visual corruption, perf/memory, minor. "Confirmed" = reproduced here; "code" = read in the
source with a precise trigger but not run end to end.

> STATUS: COMPLETE (2026-10-23 00:45 to 05:50); FIXED the same day (see Fixes). 19 confirmed bugs (2 crash/hang/data loss, 5 visual corruption, 4 perf/memory, 8 minor), 10 open suspicions (S2-01 to S2-10), about 95 game launches
> on one MacBook Air M2 (8 GB, 60 Hz), the offline checks all green (below), the spot re-tests of BUG-HUNT-10-21's fixes all holding. Nothing in the repo was changed except this file; `metal/run/config`, `metal/run/options.txt` and `metal/run-compat/config` are back
> byte for byte. See "Not tested / limits" at the end.

## Summary table

| ID | Severity | Area | Bug | Confirmed |
|---|---|---|---|---|
| BH2-01 | crash/hang/data loss | Far terrain: mesh arena | `LodMesh.grow()` frees the OLD arena size after growing by half: the free list then covers 32+ MB past the end of the Metal buffer; the first allocation there is a native write past the buffer: JVM SIGSEGV/SIGBUS in `memCopy`. Quality: High (default Distance) dies 14 s after joining; any pack switch at Distance 1024 or 2048; the first pack switch (or after about a dozen) at the default Distance 512 | confirmed live (7 crashes) |
| BH2-02 | perf/memory | Far terrain: leaving a world | Nothing tears the far terrain down when the world is left: its ~190 MB of Metal buffers, the closed world's `ClientLevel` with 213 chunks and the closed integrated server's 3 `ServerLevel`s stay alive (and its 7 worker threads) at the title screen until the next world is joined; dirty tiles and beacons are not saved until then | confirmed live (A/B vs far terrain off) |
| BH2-03 | visual corruption | Far terrain + shader packs (translator) | A pack whose `dh_terrain` fragment shader declares `uniform mat4 dhProjection` (Mellow) loses the vertex stage's declaration of it: "'dhProjection' : undeclared identifier", "Far terrain not drawn with this pack", the whole far view is empty | confirmed live |
| BH2-04 | minor | Far terrain: beacons | A broken (or deactivated) beacon keeps its beam, drawn by the far terrain, for as long as its chunk stays loaded | confirmed live |
| BH2-05 | minor | Far terrain + shader packs | With a pack the Far Terrain page's Brightness / Saturation / Earth Curvature don't apply while the camera is still (the still-camera cull reuse key omits them) | confirmed live (A/B) |
| BH2-06 | visual corruption | Far terrain + shader packs (translator) | VanillaEX r1.2.0: its `dh_terrain` fragment shader reads `dhMaterialId`, which the DH adapter gives only to the vertex stage: "'dhMaterialId' : undeclared identifier", far terrain not drawn | confirmed live |
| BH2-07 | visual corruption | Far terrain + shader packs (DH contract) | Noble v1.9.9: `dh_hasTexture()` / `dh_sampleTexture()` (its DH_TEXTURES option, on by default) are not provided: its dh program fails, far terrain not drawn | confirmed live |
| BH2-08 | perf/memory | Frame cap | The "shader packs run at the display's rate on a 60 Hz display" cap (commit 77881a3) never applies: the check runs after the pack's frame ended (`ShaderRuntime.active` is already false); a pack on this 60 Hz Mac renders 110-260 fps | confirmed live |
| BH2-09 | minor | Cadence API | `Cadence.isMetal()` throws `IllegalStateException: Can't getDevice() before it was initialized` when called from the `cadence` entry point (`CadenceAddon.onCadence`, run at `Minecraft.<init>`): an addon that checks it first (the documented use) fails to start and none of its hooks register | confirmed live |
| BH2-10 | minor | Video Settings | While Render Max FPS is on, the Cadence status rows still say "Heat (Mac warm): holding ~120 fps" (and "Tuned automatically: cooling") although the mode ignores every cap | confirmed live |
| BH2-11 | minor | Cadence API (docs, diagnostics) | `docs/API.md` shows `"mixin:ModelPartCompileMixin"` as the step-aside example: no such mixin exists in Cadence; a `mixin:` name that matches nothing is ignored without a log line, so a typo (or that example) silently does nothing | confirmed (code + A1/A2 logs) |
| BH2-12 | minor | Far terrain: logging | `LodNativeNoise.counted` prints a debug line ("cadence-lod: nativeNoise volume calls by size ...") every 131,072 noise-volume calls in every build, flag or not: 62 lines in 2 minutes of the 18-mod production run (about one a second while the far terrain generates), and 6 shared atomic counters are bumped on every call from all generation threads | confirmed live (production set) |
| BH2-13 | perf/memory | Far terrain: dimension changes | Every dimension change that creates a far terrain (Overworld, End) leaks one `LodClip.texBuf` (12.6 MB at the default N 1024; `Lod.ensureWorld` queues four of the clip's five buffers for release, not `texBuf`): +24 MB of Metal memory per Overworld/End round trip, measured 371 -> 403 -> 427 MB over three, flat 173-190 MB with far terrain off | confirmed live (A/B) |
| BH2-14 | perf/memory | Far terrain: dimensions without it | In the Nether (no far terrain) the Overworld's far terrain buffers (~190 MB at N 1024, radius 512) are never released: they sit in `Lod.releaseLater`, which is only drained by `Lod.draw`, and `draw` returns before the drain when there is no far terrain field; the memory comes back only when a dimension with far terrain is drawn | confirmed live (A/B) |
| BH2-15 | crash/hang/data loss (far terrain cache) | Far terrain: explored-only land (servers, LAN guests, Explored Only) | `LodField.saveDirty` runs every 3000 frames and drops every dirty tile that has left the clipmap window by then without saving it: flying 2.7 km east at 30 blocks/s kept 60 of 144 level-0 tiles at 30 fps (the other 84 of the explored land forgotten) against all 144 at ~1000 fps | confirmed live (A/B) |
| BH2-16 | minor | Far Terrain page: look settings | The live path (`FarTerrainScreen.applyLook`) takes the config's Brightness / Saturation / Curvature with `Float.parseFloat`, no range check, while the launch path (`Profile.farTerrainSettings`) rejects anything but `[0-9]+(\.[0-9]+)?`: a hand-edited NaN / 1e30 / -5 starts fine and is applied raw the first time the page is opened or clicked (the far terrain turns uniformly blue-grey under BSL) while the buttons still say 100% | confirmed live |
| BH2-17 | visual corruption | Far terrain + shader packs (dimensions without far terrain) | With Far Terrain on, Photon v1.3b renders a black screen and superDuperVanilla a flat fog-coloured one (the world invisible) in the Nether (no far terrain there): the packs are compiled with `DISTANT_HORIZONS` defined in every dimension but the dh uniforms are left at zero where no far terrain runs, and the packs' own expressions divide by `dhRenderDistance` | confirmed live (A/B vs far terrain off) |
| BH2-18 | visual corruption | Far terrain + resource packs | The far terrain does not follow a resource pack change: tile colours are baked from the textures when a tile is generated and cached on disk, `LodColors`/`LodPalette` keep the old sprites' colours and atlas rectangles for the whole session, and nothing invalidates either on a resource reload or a different pack at the next launch | confirmed live (A/B, and across a restart) |
| BH2-19 | minor | Far Terrain page (UI) | Every option click rebuilds the whole page (`setScreen(new FarTerrainScreen(...))`): the list jumps back to the top (the bottom options need a scroll after every click on a small window / large GUI scale), and the Pre-generate buttons forget they were pressed (a second press queues every tile again) | confirmed live |

Counts by severity (confirmed): crash/hang/data loss: 2; visual corruption: 5; perf/memory: 4; minor: 8; total 19.

## Fixes (2026-10-23, after the audit)

Every confirmed bug and eight of the ten suspicions were fixed the same day; "Retest" is the in-game check of the fix
(`scratchpad/retest/`, runs R1-R10, plans from the repro steps above).

| ID | Fix | Where | Retest |
|---|---|---|---|
| BH2-01 | `grow()` frees exactly what it adds (`release(oldWords, words - oldWords)`); `allocate` stops at the arena's cap | `LodMesh` | R1 Quality High + two pack switches, R2 Distance 1024 + five switches: no crash (arena grew to 336 MB, 189 MB used) |
| BH2-02 | Leaving a world drops the far terrain at once (`WorldClosed` -> `LodPackFrame.worldClosed` -> `Lod.worldClosed`): tiles and beacons saved, workers stopped, the GPU waited for and every buffer released | `Lod`, `LodPackFrame`, `WorldClosed` | R3: 218 MB of Metal at the title screen (385 before; 196 with far terrain off) |
| BH2-03 | The adapter's uniforms count as used by the vertex stage, whichever stage the pack declares them in | `ShaderCompatibilityCompiler` | R2: compiles; V1: drawn |
| BH2-04 | A remembered beacon whose chunk is loaded but which the game no longer has (or whose beam is gone) is forgotten instead of drawn | `LodBeacons.submit` | V5/D2: the far beam shows from 349 blocks while the beacon stands, and is gone once it is broken (the first version of the fix also forgot standing beacons as their chunks unloaded: `ClientLevel.hasChunk` is always true; it now asks the chunk cache) |
| BH2-05 | The still-camera cull key includes brightness, saturation and curvature | `Lod` | V2: with BSL and a still camera the picture changes as the page closes (lower band 123 -> 108.5 at once, 108.3-109.4 after a turn; before: only after the turn); curvature the same |
| BH2-06 | The DH adapter passes `dhMaterialId` to the fragment stage as a flat varying when the fragment stage reads it (`VertexAdapter.fragmentValues`) | `ShaderCompatibilityCompiler`, `MinecraftVertexAdapter` | R2: compiles; V1: drawn |
| BH2-07 | `dh_hasTexture()` (false) and `dh_sampleTexture()` are defined for a pack that calls them without defining them (`VertexAdapter.functions`) | same | R2: compiles; V1: drawn |
| BH2-08 | The 60 Hz display cap tests "a pack is installed" (`ShaderRuntime.get() != null`) | `FramerateCapMixin` | R2: every pack at exactly 60 fps (600 frames in 10 s) with Heat Control off and Max Framerate unlimited (2,597 before) |
| BH2-09 | `Cadence.isMetal()` answers from the startup choice (`cadence.metal`) before the GPU device exists; the entry point's timing is documented | `Cadence`, `CadenceAddon`, `docs/API.md` | R2: `isMetal=true` at the entry point, all hooks registered |
| BH2-10 | While Render Max FPS is on, the status row says "Render Max FPS: nothing holds the frame rate back" instead of Heat Control's hold | `CadenceSettings` | code |
| BH2-11 | The doc's example is a real mixin (`mixin:BrainMixin`); a `mixin:` name Cadence doesn't have, and a non-string entry, are reported in the log; AFTER_HUD's identity matrices documented | `CompatLayers`, `docs/API.md`, `RenderContext` | code |
| BH2-12 | The noise-volume counters and their log line run only with `-Dcadence.lod.noiseStats` (or the generation benchmark) | `LodNativeNoise` | code |
| BH2-13 | All five clipmap buffers are released (`texBuf` too) | `Lod.dropWorld` | R3 (with BH2-14) |
| BH2-14 | Released buffers drain at every level frame, also in a dimension without far terrain | `Lod.begin` | R3: 199 MB of Metal in the Nether (the Overworld's ~190 MB stayed there before) |
| BH2-15 | A dirty tile is saved when it leaves the clipmap window (from the staged snapshot if it has one), the rest every 30 s and when the world is left | `LodField`, `LodClip` | R6/R7: 180 level-0 tiles saved at 30 fps and at ~1000 fps (76 and 180 before) |
| BH2-16 | Brightness 0.1-4, saturation 0-4 and curvature 0-5000 are clamped (and NaN replaced by the default) on both paths | `LodConfig` | code |
| BH2-17 | The DH macros are defined only in dimensions with far terrain (`DistantTerrain.drawsIn`); where there is none the dh uniforms get finite stand-ins (the render distance), and `dhModelView`, `dhModelViewInverse`, `dhPreviousModelView` are supplied | `PackPrograms`, `DistantTerrain`, `FrameUniforms` | R3: Photon and superDuperVanilla draw the Nether normally (black / all fog before) |
| BH2-18 | A resource reload clears the colour caches, rewrites the texture table and, under other packs, rebuilds a generated world's far terrain; each cached tile records the packs it was made under (format 11), and a generated world makes a tile made under other packs again (explored land keeps it) | `LodResources` (new), `LodColors`, `LodPalette`, `LodField`, `Lod` | R8: the pack turned on at runtime: rebuilt 3 s after the reload, colours as the control's (horizon band 79,105,46 vs 79,106,46; 61,91,39 before); R10: launched with the pack over a cache made without it: the pack's colours to the far hills |
| BH2-19 | Option buttons change their label in place; the Far Terrain switch rebuilds the page at its scroll position; Pre-generate skips tiles it has already queued | `FarTerrainScreen`, `LodField.pregenerate` | code |
| S2-01 | (likely cause) Pack frames use the far terrain only once every resident tile is meshed in the merge mode the pack needs (`LodMesh.modeSettled`); a colour-merged tile that overflows its scratch is meshed again without colours | `LodMesh`, `Lod` | with R1/R2 |
| S2-02 | The collision shortcut applies to the game's three air blocks only, not to every `isAir()` state | `CollisionAirMixin` | code |
| S2-03 | `waiting` summaries outside the level-0 window are dropped when the window moves | `LodField.forgetWaiting` | code |
| S2-05 | The status line reports pre-generation only while generation hasn't run out of memory | `Lod.status` | code |
| S2-07, S2-09 | The Far Terrain page and Video Settings say when the selected pack has no Distant Horizons support or its far terrain program failed | `FarTerrainScreen.packNote`, `ShaderRuntime.distantTerrainState`, `CadenceSettings` | code |
| S2-08 | The `RenderContext` doc says each call gets a new context | `RenderContext` | code |
| S2-10 | Fixed with BH2-02 (the far terrain is saved when the world is left) | | with R3 |
| S2-04 | Open: the tree caches' size (RAM pass) | | |
| S2-06 | Open: the server cache folder stays keyed by address (documented) | | |

Also found while fixing: `LodTrees`' biome profile cache held every visited world's biome registry for the session (cleared when a world is left).

Found by the retests (all fixed, re-verified):
- **The S2-01 fix hid the far terrain from every pack** (R1/R2: BSL showed only fog below the horizon). Two causes. A tile
  whose first mesh was in flight when the pack turned on was installed in the old merge mode and never asked for again (the
  render thread now asks again when it installs a mesh of the other mode). And, already in beta.14: with the far terrain's
  words published by the GPU (the default), each new staged copy of a tile counted its versions from 1 again while the mesh
  remembered the slot's last published version, so every later copy was dropped as "already published": a tile published
  once ignored the real chunks' later changes for good (until it left the window), and in pack mode it never took its
  one-colour mesh. Staged copies now share one version clock. A pack that still waits more than 5 s for its meshes draws the
  far terrain anyway and logs which tiles wait (D3: both switches settle, no wait logged).
- **The BH2-04 fix forgot standing beacons** as their chunks unloaded (`ClientLevel.hasChunk` is always true on the client,
  and an absent chunk reads as air): it now asks the chunk cache (`getChunkNow`).
- **The forest caches (S2-04)**: the hunter's soak flight, live heap after a full GC: 542 -> 435 MB at 4 minutes, 609 ->
  466 MB at 10 minutes (int arrays 147 -> 46 MB; the caches hold about 4,500 chunks instead of up to 40,000).


### Suggested order for fixing (my reading: severity, then how many players meet it, then cost)

1. **BH2-01** (arena growth crash): Quality High dies 14 s after joining and any pack switch on a full arena kills the JVM; a two-line change in `LodMesh.grow()` (`release(oldWords, words - oldWords)`).
2. **BH2-17** (Photon and superDuperVanilla black or fog-only in the Nether with Far Terrain on): the first thing a player with those packs meets after a portal; define the DH macros per dimension or give the dh uniforms finite values.
3. **BH2-14, BH2-13, BH2-02** (far-terrain memory: ~190 MB held in the Nether, 12.6 MB leaked per dimension change, ~190 MB plus the old world held at the title screen): all in `Lod.ensureWorld` / `WorldClosed`, small changes, 8 GB Macs.
4. **BH2-15, BH2-18** (explored land lost when flying fast at a low frame rate; far terrain stuck in the old resource pack's colours): data and cache-key changes in `LodField` / `LodColors`.
5. **BH2-06, BH2-07, BH2-03** (VanillaEX, Noble, Mellow: no far terrain; each a small gap in the DH adapter's contract) and **BH2-08** (the 60 Hz pack cap that never applies: one condition).
6. **BH2-05, BH2-16, BH2-19, BH2-04, BH2-10, BH2-12** (Far Terrain page and its look settings, beacon beam of a broken beacon, status line, log spam) and the API's **BH2-09, BH2-11** (isMetal at the entry point: any addon that checks it first fails to start).

## Coverage

Test bed: one MacBook Air M2 (Mac14,2, 8 GB, 8 GPU cores, built-in 60 Hz display, macOS 27.0 beta, Zulu 25.0.2), the dev client from `tools/cadence-test.sh` (world "tester" restored from its snapshot before every run, 960x540, render distance 6 unless
a step says otherwise), one game at a time, the harness' memory guard on (it never stopped a run), about 95 game launches between 00:45 and 05:30. "Run ids" are `metal/run/results/<MMDD-HHMMSS>/` (screenshots, `latest.log`, `stdout.log`) unless noted.

| Area | What was run | Result |
|---|---|---|
| Far terrain, mesh arena (growth, remesh) | F5 `1023-010825` (13 pack switches), F7 `1023-012021` (Distance 1024), S1 `1023-021730`, S2 `1023-021857`, V-high `1023-023145` (Quality High), E1 `1023-030653`, E2 `1023-030733` | BH2-01: 7 JVM crashes with one signature; Distance 128 and Quality Low never reach it |
| Far terrain + all 25 shader packs | F5 (packs 1-13), S1-S5 (`1023-021730`, `-021857`, `-022034`, `-035322`, `-035621`), F1/F10-F12 | 13 packs draw it (BSL, Bliss, Complementary Reimagined and Unbound, I Like Vanilla, MakeUp UltraFast, Pegasus, Sildur's Enhanced Default, Sildur's Vibrant 1.541 and 2.02, Solas, Photon, superDuperVanilla); 3 fail their `dh_terrain` (Mellow BH2-03, VanillaEX BH2-06, Noble BH2-07); 7 have no `dh_terrain` (AstraLex, Insanity, Kappa, Nostalgia, PotatoShaders, VanillaPlus, miniature: no far terrain, by design, S2-07); 2 refused as packs (Shrimple, rethinking-voxels: "failed while drawing; shaders are off") |
| Pack mode: look settings, still-camera cull reuse | F8 `1023-020137` / `-020543`, G `1023-034130` / `-034535` | BH2-05 (A/B twice) |
| Far Terrain page | P1 `1023-013318` (every button), V-low/V-d128/V-trees/V-fogoff/V-cpulow, E1b `1023-044526`, UI1 `1023-045719`, UI1b `1023-053138` | all options launch; BH2-16, BH2-19 (page rebuild) |
| Explored-only vs generated, caches | X1a/b/c `1023-024056`, `-024347`, `-024535`, X2a/b `1023-041919`, `-042327` | X1 clean; BH2-15 (tile save loss) |
| Dimensions, leaving/rejoining | P4 (End, Nether), N1 `1023-030833` / N2 `1023-031155` (hops), END1 `1023-031506` (End with and without BSL), M1/M3 (leave/join with Max FPS), P5 flat, P6 void `1023-032331` | BH2-02, BH2-13, BH2-14; End, flat and void worlds draw correctly |
| Servers / LAN guest | L1 host `1023-015902` + guest `1023-015923-guest` | works (explored land only); cache folder per ip:port (S2-06) |
| Beacons, Far Clouds, pre-generation, progress line | B1 `1023-013537`, B2 `1023-045957` (BSL, Complementary, Photon), P2, P3a/b | BH2-04; the rest clean |
| Blindness / Darkness, huge coordinates, window/fullscreen/GUI scale/render distance | BL `1023-031910` / `-032118`, T1 `1023-033712` (+-29,000,000), W1 `1023-034943` (31 steps) | clean |
| Soak | SOAK `1023-032527` (11 min flight, BSL on for 2.5 min, histograms, thread count, RSS poll) | no crash, threads flat (58-65), live heap 542 -> 609 MB (S2-04) |
| Edge config values | E1b, E2b `1023-044755`, E1/E2 | BH2-16; Distance 99999 ignored, 2048 accepted (crashed by BH2-01) |
| Render Max FPS | M1 `1023-024727`, M2 `1023-025304`, M3 `1023-040304`, R1 | ~14,000 fps, toggles, resize, HUD, pause, dimension change, leave/join, pack switch while on: all fine; BH2-10 |
| Frame caps and pacing, Heat Control, In Background, On Battery | C1 `1023-022247`, C2 `1023-022707`, C3 `1023-035935`, C4 `1023-043104`, C5 `1023-043554` | caps 30/40/50/60/90/120/144 exact; presents even at 60; BH2-08 (60 Hz pack cap dead); Heat Control hold/ease/release by design |
| Cadence API | A1 `1023-025720`, A1b `1023-044140`, A2 `1023-025942` | BH2-09, BH2-11; hooks, failing hook, re-entrancy, step-aside (22 areas) work |
| Production 18-mod set | PR1 (`metal/run-compat/results/1023-030119`) | far terrain, BSL, End, rejoin, Max FPS fine; BH2-12 |
| Nether with DH packs | NP-on/off `1023-045005` / `-045340`, NP2-on/off `1023-052241` / `-052709` | BH2-17 (Photon black, superDuperVanilla fog-only); the other 11 DH packs fine |
| Resource pack change | RP1-RP6 (scratch folder `scratchpad/bughunt2/run-b/results/`) | BH2-18 (RP6: stale across a restart) |
| Spot re-tests of 10-21 fixes | BH-04 (`mcmetal*` only after crashes), BH-03 (R2), BH-09 (R1), BH-11 (R3), BH-12 (C3), BH-14 (C5), BH-01/02 (VivaceCheck, below) | all hold |
| Older features next to the new | Q1 `1023-051626` (Benchmark, Quick Setup, Dynamic Lights), Q2 (Benchmark with far terrain and BSL) | clean |
| Offline (no game running, scratch `TMPDIR`) | `tools/mixin-check.sh` (513 members checked against Minecraft 26.3, 4 skipped, 0 errors; was 498 at beta.13), `tools/offline-checks/run.sh` (RGSS patch, profile tolerance, per-pack settings, compat probe: all OK), `tools/logic-tests/run.sh` (900,000 section boxes and 200,000 ticker lists identical to vanilla, mob search 23.0x, `VivaceCheck: all passed`) | all green |

What held up and was not reported as a bug: still-camera cull reuse with an unchanged view (the A/B shows no stale geometry), explored -> generated on one cache, the LodYield hypothesis ("tiles gated for good on servers": effective render distance is min(client, server)), 60 Hz presents, `FramerateLimiterMixin` grid,
`RingBufferDepthMixin` (the five `iconst_3` of `MappableRingBuffer` are all its depth), `CollisionAirMixin` against vanilla, the far beam under three packs, the pack-to-pack switch (no second remesh: D1 `1023-042739`, meshed +814 on pack on, +0 pack to pack, +814 off).


## Confirmed bugs

### BH2-01 [crash, JVM SIGBUS] Far terrain: after the mesh arena grows, the allocator hands out memory past the end of the buffer

**Repro 0 (simplest, no shader pack, no other setting): Video Settings > Far Terrain > Quality: High** (config `farTerrain=on`, `farTerrain.quality=high`, default Distance 512). The first fill of the far terrain grows the
arena from 64 to 96 MB and the game dies 2 s later, 14.3 s after the launch (run `1023-023145`, V-high: `cadence-lod: mesh arena grown to 96 MB` at 02:31:59, `SIGSEGV (0xb) ... si_code: 2 (SEGV_ACCERR), si_addr 0x0000007081c00000`
at 02:32:01, `StubRoutines::copy_byte_f` on the Render thread, exit code 134; `scratchpad/bughunt2/crashes/Vhigh-hs_err_pid97411.log`). Quality Auto at Distance 512 and Quality Low stay inside the real 96 MB unless something remeshes the whole field at once (a pack switch, below), so they reach the phantom range only then;
Quality High (N 2048) fills the real arena past its end on its own (last stats line two seconds before the crash: `mesh 1564 tiles installed, ... arena 77.7 of 96 MB`), and so do Distance 1024 and 2048.
**Repro A (deterministic, 2 clicks):** Video Settings > Far Terrain: On, Distance: 1024 Chunks (config `farTerrain=on`, `farTerrain.distance=1024`),
start the game, join a world, wait ~30 s for the far terrain to fill (the mesh arena has grown to 96 MB and holds ~75-82 MB), then switch
shaders on to any pack with a `dh_terrain` program (BSL here). Self-test plan (settle 6, heap 1536 MB, `CADENCE_EXTRA_JVM=-Dcadence.lod.stats=true`):
`cmd=gamemode spectator @me,cmd=tp @me 101 230 450 180 5,wait=30,BSL_v10.1.8.zip,off,BSL_v10.1.8.zip,off,BSL_v10.1.8.zip,off`.
The game dies at the first pack switch (run `1023-012021`: `Shaders switched to BSL_v10.1.8.zip` at 01:21:33, crash at 72.1 s). The last stats line
before the crash is `mesh 1463 tiles installed, 4711 meshed, 1362 pending, arena 88.6 of 96 MB`.
**Repro B (default Distance 512, no special settings):** `farTerrain=on`, `cmd=gamemode spectator @me,cmd=tp @me 101 230 450 180 5,wait=8,Pegasus (v0.3.7).zip,...`: the very
first pack switch kills the game (run `1023-021730`, S1, Pegasus at 02:18:33, SIGBUS; the arena had grown to 96 MB once, 02:17:43). Over this forested view (more quads per tile than the
spawn's lake) the arena is more than half full, so the remesh a pack switch triggers (below) overflows it at once; at the spawn view the same switches survived 6 times (`1023-013027`, `1023-020953`)
and the 13th switch of a sweep crashed (run `1023-010825`, step 13, Pegasus, 01:11:15). A fourth one: run `1023-021857` (S2, default Distance 512, same view): VanillaEX at 02:20:00, then
rethinking-voxels (the pack that fails and turns shaders off, which remeshes again) at 02:20:11: SIGBUS 73.6 s after the start. Seven crashes in all with the same signature (`scratchpad/bughunt2/crashes/`): F5, F7, S1, S2, V-high (Repro 0), and two more from config runs: E1 (`1023-030653`, Quality HIGH plus garbage values, 14.6 s after launch,
`mesh arena grown to 96 MB` one second before) and E2 (`1023-030733`, Distance 2048, at the BSL switch 33.6 s after launch, `arena 83.7 of 96 MB` just before).
**Evidence:** `scratchpad/bughunt2/crashes/F7-hs_err_pid45613.log` and `F5-hs_err_pid40831.log`: `SIGBUS (0xa) ... si_code: 1 (BUS_ADRALN), si_addr 0x...0000` (a page
boundary), problematic frame `StubRoutines::copy_byte_f`, thread "Render thread" in compiled Java, the destination register `x1` is 16 bytes below `si_addr`;
the last compiled method in the F5 log is `dev.cadence.metal.lod.LodMesh::integrate`. A byte copy that runs off the end of a mapped buffer: `LodMesh.install` copies a
finished mesh with `MemoryUtil.memCopy(r[2], this.arenaAddr + at * 4, words * 4)` (`LodMesh.java:454`).
**Cause:** commit b7e7346 ("arena grows by half instead of doubling") changed `grow()` to `words = min(MAX, (arenaWords * 3 / 2 + 4M - 1) & ~(4M - 1))`
(`LodMesh.java:627`) but kept `this.release(this.arenaWords, this.arenaWords)` and `this.usedWords += this.arenaWords` (`LodMesh.java:633-634`), which were right only
for doubling: the free list is told that `[oldWords, 2 x oldWords)` is free while the new buffer ends at `1.5 x oldWords`. After the first growth (64 -> 96 MB) 32 MB
past the buffer's end are on offer, after the second (96 -> 144 MB) 48 MB. `allocate()` is first-fit, so the phantom range is used when the real range is full: a normal
steady state at 1024 chunks is 75-82 MB of 96, and a pack switch or a Quality/mode change remeshes every resident tile (`LodMesh.colorMerge`, each new mesh is
allocated before the old one is freed RING frames later: peak = old + new) and runs through the end. Writes up to one page past the end land in unmapped or foreign
memory silently; further ones are SIGBUS/SEGV.
**Fix hint:** `this.release(oldWords, words - oldWords); this.usedWords += words - oldWords;` (also keeps the capped last step right).
**Confidence:** high (seven crashes with one signature, cause read in the diff and the growth line is in every log just before). **Not tested:** the second growth step on its own (96 -> 144 MB); a fixed build.

### BH2-02 [perf/memory] Far terrain is never torn down when the player leaves a world

**Repro:** `farTerrain=on`; plan `wait=10,leave,wait=15,histo,gc` (run `1023-005511`; also `1023-010053`), then the same with far terrain off (control `1023-010402`).
**Evidence (title screen after Save and Quit):**
- `memory metal`: 385 MB at the title screen with far terrain on (368 MB in the world), 196 MB with it off.
- class histograms (`scratchpad/bughunt2/histos/histo-005626.txt`, taken after a full GC at the title screen): `ClientLevel` 1, `ClientChunkCache` 1,
  `LevelChunk` 213, `dev.cadence.metal.lod.Lod/LodField/LodMesh/LodClip` 1 each, `LodNoise` and `LodForest` (they hold the integrated server's `ServerLevel`) alive; the control
  (`histo-010654.txt`) has no `ClientLevel` and no `LevelChunk` at the title screen. (The 3 `ServerLevel` + 1 `IntegratedServer` the control still shows belong to the self-test
  thread's local `server` variable of the first session, a harness artifact: the far terrain run shows 6 / 2 at the same point.)
- threads at the title screen (`scratchpad/bughunt2/logs/F3-poll.txt`, `jcmd Thread.print`): 4 `cadence-lod-N` workers, 2 `cadence-lod-mesh-N` and the scan thread alive
  the whole time (idle once their queue drains).
**Cause:** `Lod.ensureWorld` (`Lod.java:347`) is the only place that closes the field and releases the GPU buffers, and it runs from `beginLevel`, i.e. only on the next frame of
a level that differs from `worldOwner`. Leaving to the title screen draws no level frame. `WorldClosed.run()` (`WorldClosed.java:17`, called by `WorldClosedMixin` on
`updateLevelInEngines(null)`) releases every other per-world piece of Cadence "so the title screen doesn't keep the old world in memory" but has no call for the far terrain.
`Lod.worldOwner` (`Lod.java:70`) holds the closed `ClientLevel` strongly. Also: `LodField.close()` (`saveDirty(true)`) and `LodBeacons.save()` do not run at that moment, so tiles
changed by real chunks since the last 3000-frame flush (`LodField.saveDirty`: `frame % 3000`) and new beacons are lost if the player quits from the title screen.
**Impact:** about 190 MB of Metal memory (N 1024, radius 512; more at High/1024) plus the old client world's heap held for as long as the player stays at the title screen, on the
8 GB Macs the far terrain is meant for. **Confidence:** high. **Not tested:** quitting from the title screen with unsaved tiles (data loss part: code only).

### BH2-03 [visual, far terrain missing] Mellow: its `dh_terrain` program fails to translate, so no far terrain is drawn

**Repro:** far terrain on, elevated view, switch to Mellow v3.4.1a (F5, step 11, run `1023-010825`, `selftest-11-Mellow_v3.4.1a.zip.jpg`: sky and a flat fog plane, no terrain
anywhere below the horizon, while BSL, Complementary, Photon, Bliss, Solas and MakeUp show the far terrain from the same spot).
**Evidence:** `latest.log:581` `ShaderCompileException: Couldn't parse GLSL: cadence_shaders:pack:1525: error: 'dhProjection' : undeclared identifier`, then `Far terrain not drawn
with this pack: its dh_terrain program failed` (`latest.log:612`, `Couldn't compile pipeline (cadence_shaders:world0/dh_terrain/pipeline/far_terrain/...)`).
**Cause:** Mellow's `dh_terrain.fsh` declares `uniform mat4 dhProjection;` (and `dhProjectionInverse`) itself (`shaders/program/dh_terrain.fsh:407-411`), its `.vsh` does not.
`MinecraftVertexAdapter.distantTerrain()` (`MinecraftVertexAdapter.java:~92`) supplies `dhProjection` as the vertex stage's `gl_ProjectionMatrix`
and `ShaderCompatibilityCompiler` merges the adapter's uniforms into the shared map (`:347`) but the per-stage visibility sets are computed from the pack's source:
`hideInVertex = fragmentUniforms - vertexUniforms` (`:541-543`) contains `dhProjection`, so the vertex declaration block drops it and the adapter's own expression
(`gl_ProjectionMatrix` -> `dhProjection`) is undeclared. Any dh_terrain pair whose fragment stage declares `dhProjection`, `gbufferModelView` or `gbufferModelViewInverse`
while the vertex stage doesn't fails the same way (the adapter's three uniforms). **Fix hint:** add the adapter's uniform names to `vertexUniforms` when an adapter is present.
**Confidence:** high (log + source). Census of the 25 packs' dh programs (separate .vsh/.fsh pairs): only Mellow declares one of the adapter's three uniforms in the fragment stage alone (Bliss's vertex stage declares all three, Shrimple's both stages declare the two matrices it uses, Photon/Sildur/MakeUp declare them in the vertex stage).

### BH2-04 [minor, visual] A broken (or deactivated) beacon keeps its beam, drawn by the far terrain, for as long as its chunk stays loaded

**Repro (self-test, scratch cache `-Dcadence.lod.cacheDir=...`, `-Dcadence.lod.beaconLog=true`, far terrain on):** `cmd=gamemode spectator @me,cmd=tp @me 101 205 106 180 0,
cmd=fill 100 200 100 102 200 102 minecraft:iron_block,cmd=setblock 101 201 101 minecraft:beacon,wait=8,cmd=tp @me 101 230 450 180 5,wait=12,cmd=tp @me 101 215 130 180 5,
wait=8,cmd=setblock 101 201 101 minecraft:air,wait=6,...`. Run `1023-013537` (B1): the beam shows from afar (`beacons 1, drawn far 1, drawn by the game 0`), near the beacon
the game draws it; 8 s after the `setblock ... air` (step 10, 01:37:15) the log says `beacons 1, drawn far 1, drawn by the game 0` (01:37:23) and `selftest-11-wait_6.jpg` still shows
the full beam over the empty spot; the beam is gone only after the chunk is unloaded and reloaded (steps 12-17, `selftest-13/15/17`).
**Cause:** `LodBeacons.submit` (`LodBeacons.java:152`) draws every remembered beacon whose `drawnFrame != thisFrame`, i.e. also one whose chunk is loaded but whose block
entity is gone (the game stops extracting it, so `seen()` is not called), and `BEACONS` is only corrected in `chunk()` (chunk load/unload, `:108`). A beacon that is
deactivated (pyramid removed: `getBeamSections()` empty, `seen()` returns early `:128`) is kept "as last seen" by design and drawn the same way. **Fix hint:** in `submit`, skip (and
forget) a beacon whose chunk the client has loaded when the game didn't draw it this frame. **Confidence:** high.

### BH2-05 [minor, visual] With a shader pack, the Far Terrain page's look settings ("Look (applies now)") don't apply while the camera is still

**Repro:** far terrain on, BSL, the camera standing still (a spectator, no input); open Video Settings > Far Terrain, Brightness 60% and Saturation 50% (three clicks each), close.
Measured on lossless-enough JPEG screenshots as the mean luminance of the picture's lower band (`scratchpad/bughunt2/meanband.py DIR 0.5 0.85`):
- `-Dcadence.lod.stillReuse=false` (cull every frame), run `1023-020543`: 113.8 before, **104.3 right after closing the page**, 104.3 after a turn and back.
- default (still-camera cull reuse), run `1023-020137`: 113.8 before, **113.5 right after closing the page (unchanged)**, 103.7 only after `turn=3,turn=-3` made the cull run again.
Second A/B from a height where the whole picture is far terrain (`cmd=tp @me 101 170 450 180 14`, BSL, plan in `scratchpad/bughunt2/queue19.sh`): default (reuse) `1023-034130`: 127.3 before, **127.3 right after the page closes**,
114.1 after `turn=3,turn=-3`; with `-Dcadence.lod.stillReuse=false` `1023-034535`: 126.9 before, **113.0 right after the page closes**. Curvature (three clicks, Tiny Planet): reuse 113.2 -> **113.0** at close -> 121.7 after the turn; no reuse 113.0 -> **120.9** at close.
The same for Earth Curvature (Tiny Planet): changed only after the view moved. (Without a pack the composite reads the look from CompFrame every frame, so it applies at once.)
**Cause:** in pack mode the colour (brightness and saturation, `compLook`) and the curvature (`args[120]`) are baked into the survivors' records by `lod_mesh_pack` / `lod_mesh_pack_args`
at the end of the cull (`columns.metal:3794-3898`, `mclod.m` `pack` dispatch), and `Lod.cullKey` (`Lod.java:438-462`) hashes the view, mask, table, buffers, `rd`, `pack` and
`packColors` but not `LodConfig.brightness()/saturation()/curvatureDrop()`, so a still frame reuses the old records. **Fix hint:** mix the three look values into the key.
**Confidence:** high (A/B above). Small: the page applies as soon as the player moves the mouse.

### BH2-06 [visual, far terrain missing] VanillaEX r1.2.0: its `dh_terrain` fragment shader uses `dhMaterialId`, which Cadence only declares for the vertex stage

**Repro:** far terrain on (default Distance 512), elevated view (`cmd=tp @me 101 230 450 180 5`), switch to VanillaEX r1.2.0: S2, run `1023-021857`, step 4, 02:20:00.
`selftest-4-VanillaEX_r1.2.0.zip.jpg`: the sky and a flat grey plane below the horizon, no terrain; BSL and the other DH packs show the far terrain from the same spot.
**Evidence:** `latest.log` (02:20:00): `Couldn't compile pipeline (cadence_shaders:dh_terrain/pipeline/far_terrain/math_relaxed_fragment): ... Couldn't parse GLSL: cadence_shaders:pack:749: error:
'dhMaterialId' : undeclared identifier`, then `Far terrain not drawn with this pack: its dh_terrain program failed` (`IllegalStateException: Pack pipeline compilation failed: dh_terrain`, `PackPipeline.compile0:295`).
**Cause:** VanillaEX `shaders/dh_terrain.fsh:30` (and `world1/`, `world-1/`) reads `dhMaterialId` in the FRAGMENT stage (`#ifdef DISTANT_HORIZONS if (dhMaterialId == DH_BLOCK_ILLUMINATED) finalLight = vertexColor.rgb;`) without
declaring it. `MinecraftVertexAdapter.distantTerrain()` supplies it only as a vertex-stage expression (`expressions.put("dhMaterialId", "sl_DhMaterial")`, `MinecraftVertexAdapter.java:129`; the adapter has no
fragment side), so the fragment stage has no such identifier. I grepped all 25 packs' dh programs: VanillaEX is the only one that uses `dhMaterialId` in a fragment stage (BSL, Complementary, Solas, I Like Vanilla,
superDuperVanilla, Photon, Mellow, Bliss, MakeUp and Noble use it in the vertex stage). What real Iris does with a fragment-stage use was not verified.
**Fix hint:** pass `sl_DhMaterial` to the fragment stage (a `flat` varying) and declare/define `dhMaterialId` there, or declare it as a global the fragment stage can read.
**Confidence:** high. **Not tested:** the same pack at its other dimension folders (`world1`, `world-1` have the same line).

### BH2-07 [visual, far terrain missing] Noble v1.9.9: `dh_hasTexture()` / `dh_sampleTexture()` are not provided, its dh program fails

**Repro:** far terrain on, elevated view, switch to Noble v1.9.9 (F5, step 12, run `1023-010825`, 01:11:05).
**Evidence:** `latest.log:689`: `Couldn't parse GLSL: cadence_shaders:pack:3569: error: 'dh_hasTexture' : no matching overloaded function found / pack:3571: error: 'dh_sampleTexture' : no matching overloaded
function found / ... boolean expression expected`, then `Far terrain not drawn with this pack: its dh_terrain program failed` (`latest.log:723`).
**Cause:** Noble's `shaders/programs/gbuffers/dh/dh_opaque.glsl:100` and `dh_forward.glsl:159` (`#if DH_TEXTURES == 1 ... if (dh_hasTexture()) { vec4 albedoTexture = dh_sampleTexture(); ... }`, `DH_TEXTURES` is
on by default: `settings.glsl:713`, listed on Noble's `DISTANT_HORIZONS` option screen) call two functions that exist nowhere in the pack and that the DH adapter does not define. A pack option (`DH_TEXTURES 0`) would
avoid it (not tried: it means writing a file into `metal/run/shaderpacks`). **Fix hint:** define both in the fragment preamble of dh_* programs (`bool dh_hasTexture() { return false; }`, `vec4 dh_sampleTexture() { return vec4(0.0); }`)
when `DISTANT_HORIZONS` is defined (the far terrain has no per-block textures in pack mode). **Confidence:** high (log + pack source). The same log shows `Pack uniform dhProjectionPrevious / dhModelView /
dhModelViewInverse (mat4) isn't supplied by Cadence yet: using zero` for packs that read them (informational; not checked which effect).

### BH2-08 [perf/memory] The shader-pack display cap on a 60 Hz display (commit 77881a3) never applies

**Repro:** this Mac (MacBook Air M2, built-in 60 Hz display), `cooling=off` (Heat Control off), Max Framerate 260 (unlimited), shaders on: plan `cooling=off,maxfps=260,MakeUp-UltraFast-9.5g.zip,Mellow v3.4.1a.zip,off` (C1, run `1023-022247`).
**Evidence:** `[selftest] 7 MakeUp-UltraFast: ... over 2597 frames` in the 10 s window (260 fps), `[selftest] 8 Mellow v3.4.1a: ... over 2106 frames` (210 fps) while the commit message says "shader packs at the display's
rate on 60 Hz displays" (at most 60 fps, 600 frames). With Heat Control's default the packs run at its 120 fps hold (S-runs: 110-120 fps), also twice what the display shows.
**Cause:** `FramerateCapMixin.cadence$heatCap` (`FramerateCapMixin.java:28`) applies the cap when `DISPLAY_CAP && ShaderRuntime.isRendering() && HeatControl.refreshHz() <= 60`, but `isRendering()` is
`instance.active` and `ShaderRuntime.endFrame()` clears `active` in its `finally` (`ShaderRuntime.java:642`) before the game reads its frame-rate limit for the frame's end (the limit is read when the frame has been drawn: the call order is inferred from the measured fps and the code, not traced),
so the condition is false whenever the limit is read. **Fix hint:** test "a pack is selected" (the runtime exists and its pack isn't off / `ShaderPacks` selection) instead of "a frame is being drawn".
**Confidence:** high (measured + code). Effect: wasted GPU time, heat and battery on 60 Hz Macs (every MacBook Air) with a pack and the frame cap left at unlimited. The no-pack frame pacing at caps 30/60/120/144
is fine (C2: `presents: 1296 intervals, 0 longer than 1.5 refreshes` at 60).

### BH2-09 [minor, API] `Cadence.isMetal()` throws at the addon entry point, so an addon that checks it first never starts

**Repro:** a mod with `"entrypoints": {"cadence": ["bh2.Addon"]}` whose `onCadence()` begins `if (!Cadence.isMetal()) ...` (the first thing `docs/API.md` lists the API for: "see where it is running:
`Cadence.isMetal()`"). Test addon `scratchpad/bughunt2/addon/` (`-Dfabric.addMods=...bh2addon.jar`), run `1023-025720` (A1).
**Evidence:** `stdout.log`: `[cadence] api: addon bh2addon failed to start: java.lang.IllegalStateException: Can't getDevice() before it was initialized` with the stack
`RenderSystem.getDevice(RenderSystem.java:365)` <- `MetalCompute.encoder(MetalCompute.java:63)` <- `MetalCompute.available(:67)` <- `Cadence.isMetal(Cadence.java:31)` <- `bh2.Addon.onCadence` <-
`PreloadLater.onInitializeClient(PreloadLater.java:13)` <- `Hooks.startClient` <- `Minecraft.<init>(Minecraft.java:469)`. The exception is caught by the entry-point loop and the addon is skipped: its render hooks are never registered.
**Cause:** `PreloadLater.onInitializeClient` runs the `cadence` entry points during `Minecraft.<init>`, before the game's GPU device exists; `CadenceAddon`'s Javadoc and `docs/API.md` promise "called once at client start,
after Cadence has set itself up", and `Cadence.isMetal()` is `MetalCompute.available()` = `RenderSystem.getDevice().createCommandEncoder()...`, which needs the device. (Same for `MetalCompute.available()` anywhere early.)
**Fix hint:** make `isMetal()` not need the device (the backend choice is known at startup: the same switch `cadence.metal` that selects it), or run the addon entry points once the device exists (after the first frame / `onInitializeClient` of a later phase), or catch
`IllegalStateException` in `isMetal()` and answer from the startup flag. **Confidence:** high. Note: `Cadence.steppedAside()`, `shaderPackActive()`, `farTerrainReach()` don't touch the device and worked at the entry point; with the `isMetal()` call caught, the same addon registered all its hooks (A1b, below in BH2-11).

### BH2-10 [minor, UI] Video Settings still says "Heat (Mac warm): holding ~120 fps" while Render Max FPS is on

**Repro:** Mac in thermal state "fair" (Heat Control holding), Video Settings > Render Max FPS: On, then look at the Cadence status rows (run `1023-024727`, M1, `selftest-17-screen_video.jpg`: the fps are 8,000-14,000 with the line
"Heat (Mac warm): holding ~120 fps" in yellow under "Tuned automatically: resolution · cooling").
**Cause:** `CadenceSettings.status()` (`CadenceSettings.java`) adds `HeatControl.shortDescribe()` when `HeatControl.level() > 0` and the "Tuned automatically" list from the enabled automatic features, with no check of `MaxFpsMode.chosen()`, whose
branch in `FramerateCapMixin` ignores Heat Control, On Battery and Max Framerate. **Fix hint:** while `MaxFpsMode.chosen()`, show "Render Max FPS: nothing holds the frame rate back" instead. **Confidence:** high (screenshot + code).

### BH2-11 [minor, docs/diagnostics] The API doc's step-aside example names a mixin that doesn't exist; unknown `mixin:` names are silently ignored

**Evidence:** `docs/API.md` ("Asking Cadence to step aside"): `"stepAside": ["terrain", "mixin:ModelPartCompileMixin"]`; `grep -rn ModelPartCompile` over `metal/` and `shaders/` finds nothing. `CompatLayers.requested`
(`CompatLayers.java:169`, the `mixin:` branch `:198-199`): for `mixin:NAME` it filters Cadence's hooks by name and, when the set is empty, does nothing and prints nothing (an unknown area word is reported: `asks to step aside in an unknown area: bogusarea`, seen in A1;
a non-string array entry is skipped silently too: my test mod's `42` and `mixin:NoSuchMixinAnywhere` produced no line). **Fix hint:** log `asks to step aside in a mixin Cadence doesn't have: NAME`, and use a real name in the doc.
**Also (docs):** at `AFTER_HUD` `RenderContext.modelView()` and `projection()` are identity matrices and `camera()` is the camera entity's eye position (`ApiHudMixin.java`: `ApiHooks.fire(AFTER_HUD, null, ..., null, null, partial)` -> `IDENTITY`), so an addon cannot project world points there
(A1b log: `AFTER_HUD call 1: ... camera=(0.0, 0.0, 0.0) proj00=1.0 mv00=1.0` against `AFTER_LEVEL call 1: ... proj00=4915553.0 (the world's first frame) ... mv00=0.927`); `docs/API.md` says "`RenderContext` also gives the camera position, its rotation and projection" without that exception.
**Confidence:** high. What the API test (A1b, run `1023-044140`) showed working: hooks at all four stages with sensible values (`pass` set at AFTER_OPAQUE/TRANSLUCENT, `metal()` handles non-zero, `Cadence.shaderPack()` = `BSL_v10.1.8.zip` while it ran, `farTerrainReach()` 2048.0 and 0.0 in the Nether),
a failing hook removed after one log line (`a render hook at AFTER_OPAQUE failed and was removed`), a hook added from inside a hook running from the next frame, AFTER_HUD firing during Render Max FPS (the level stages stop, as the mode intends).

### BH2-12 [minor, log spam / shared counters] The far terrain's noise sampler logs a line every 131,072 calls in release builds

**Evidence:** `metal/run-compat/results/1023-030119/latest.log` (18-mod production set, far terrain on, no debug flags): 62 lines `cadence-lod: nativeNoise volume calls by size (1 / 2-16 / 17-256 / 257-4k / 4k-64k / more): calls ... values ... point calls ...`
from the `cadence-lod-N` workers between 03:01:33 and 03:03:16 (about one a second during the fill-in, then every few seconds); the dev client's `stdout.log` has them too.
**Cause:** `LodNativeNoise.counted(int size)` (`LodNativeNoise.java:97-110`, called after every volume sample at `:318` and `:350`) always increments `volumes`, `volumePoints` and the `sizeCalls`/`sizeValues` `AtomicLongArray`s
(`:98-103`) and `System.out.println`s a line whenever `volumes` crosses a multiple of 2^17. There is no `VERIFY`/stats guard around it. Walking new terrain keeps the 4+ generation threads producing these for as long as tiles are generated
(thousands of lines an hour in the log, none of which is useful to a player), and the shared atomics are a contended cache line across all workers on every one of millions of calls.
**Fix hint:** compile the counting out unless a debug property is set (the way `VERIFY > 0` guards `verify(...)` on the next line). **Confidence:** high (log + source).

### BH2-13 [perf/memory] Each dimension change that builds a far terrain leaks the clip's texture-word buffer (12.6 MB)

**Repro:** far terrain on (default Distance 512, Quality Auto = N 1024), plan `wait=12,dim=the_nether,wait=12,dim=the_nether,wait=6,dim=overworld,wait=12,dim=the_end,wait=12,dim=overworld,wait=12,dim=the_end,wait=12,dim=overworld,wait=12`
(N1, run `1023-030833`) against the same plan with far terrain off (N2, run `1023-031155`, the control). `memory metal` at each step (the self-test's own line):

| visit | far terrain on (N1) | far terrain off (N2) |
|---|---|---|
| Overworld, first | 371 MB | 175-190 MB |
| Nether | 373-374 | 183-190 |
| Overworld, 2nd | 377 | 175-179 |
| End, 1st | 353 | 173-177 |
| Overworld, 3rd | 399-403 | 173-178 |
| End, 2nd | 377-378 | 173 |
| Overworld, 4th | 423-427 | 173-178 |

**Cause:** `Lod.ensureWorld` (`Lod.java:366-369`) releases (via `releaseLater`) a closing clip's `geomBuf`, `colorBuf`, `mipBuf` and `crownBuf` but not `texBuf`, which `LodClip` allocates as well (`LodClip.java:128`, `levelWords * 4 * 3` bytes with plants =
12.6 MB at N 1024, 50 MB at N 2048 / Quality High); `LodClip.free()` (`LodClip.java:549-555`) would release all five but nothing calls it. Each far-terrain world switch therefore leaves 12.6 MB (the table's +24 MB per Overworld->End->Overworld round trip is two
switches). **Impact:** a long session that hops dimensions (portals, /execute in, bed respawns after death in the Nether) loses 12.6 MB of an 8 GB Mac's memory per hop for the rest of the session (a hundred hops: 1.2 GB); 50 MB per hop at Quality High.
**Fix hint:** `this.releaseLater.add(new long[] {this.frames, this.clip.texBuf})`, or call `clip.free()` once the ring has passed. **Confidence:** high (A/B numbers and source).

### BH2-14 [perf/memory] The Overworld's far terrain memory stays allocated for as long as the player is in a dimension without far terrain (the Nether)

**Evidence:** the same N1 run: Metal memory is 371 MB in the Overworld with far terrain and **373-374 MB in the Nether** (N2: 175-190 MB at the same points): the ~190 MB of the far terrain's clipmap, mesh arena and cull buffers of the Overworld are all
still allocated while the player stands in the Nether (no far terrain is drawn there: `cadence-lod: no far terrain in minecraft:the_nether (a ceiling: ...)`). They drop only when the next far-terrain frame runs (the End visit: 353 MB).
**Cause:** `ensureWorld` (`Lod.java:351-370`, the four `releaseLater.add` calls at `:366-369`) queues the old world's buffers on `releaseLater` with the current frame number; the queue is drained by `Lod.draw` (`Lod.java:497`: `while (!releaseLater.isEmpty() && frames - peek[0] >= RING) LodNative.release(...)`) and `draw` returns at
`if (w == null || clip == null) return;` (`Lod.java:480`) before reaching it, and `frames` is incremented after that return too (`:494`), so in a dimension without a far terrain neither the drain nor the clock that releases ever runs. Also the order: on the way
back the new world's ~190 MB are allocated before the old ones are released (`ensureWorld` runs first, the drain later), a short double peak of ~380 MB.
**Impact:** 190 MB held for the whole Nether trip (on the Macs this far terrain is aimed at, 8 GB, where swap starts around 3 GB of game). **Fix hint:** drain `releaseLater` in `Lod.begin` (or `ensureWorld`) as well, counting frames there, or release immediately when the GPU
has no frame using the buffer. **Confidence:** high.

### BH2-15 [data loss: far terrain cache] Explored land is saved only if its tile is still in the clipmap when the periodic save runs

**Repro (A/B, same flight, only the frame rate differs):** `farTerrain=on`, `farTerrain.generate=false` (Explored Only, as on a server), `-Dcadence.lod.cacheDir=<scratch>`, spectator at `101 110 450` facing east, `glide=90:1.5:110` (30 blocks/s for 90 s = 2,700 blocks, the
server kept up: `chunks loaded ahead avg 6.0 min 6` in both), then `leave` and `join` so everything that will be written is written:
- X2a, `cooling=off,maxfps=30` (run `1023-041919`): the cache has **76** level-0 tile files (60 of them east of x = 256), 36 level-1, 20 level-2.
- X2b, `cooling=off,maxfps=260` (about 1,000 fps, run `1023-042327`): **180** level-0 files (144 east of x = 256), 67 level-1, 23 level-2. Same tile range (tx -2..39).
So at 30 fps only 42% of the level-0 tiles of the flight (and 54% of level 1) were kept.
**Cause:** `LodField.saveDirty(false)` (`LodField.java:720-735`) only runs when `frame % 3000 == 0` (every 25 s at 120 fps, 50 s at 60, 100 s at 30) and for each dirty tile does `if (!this.clip.resident(level, tx, tz)) continue;` and then
`this.dirtyTiles.clear()`. `LodClip.recenter` (`LodClip.java:201`) clears every slot whose tile leaves a level's window as the camera moves (`resident()` is then false), so a tile the player flew away from (more than about half the window plus the render distance, ~600 blocks at N 1024) before the
next multiple of 3000 frames is neither saved nor kept dirty: the explored land is gone from the cache (and from the screen once out of the window; it comes back only if its real chunks load again). Walking (5 blocks/s) never outruns it; elytra, boats on ice, horses and
any low frame rate (Pegasus 23 fps, BSL 55 fps, Heat Control holds at 30-60) do. On a server (explored land is the only far terrain there is) this is the feature's whole data.
**Impact:** a long flight over unexplored land on a server leaves a far terrain with the middle of the route missing for the next session (60% of a 30 blocks/s flight at 60 fps by the same arithmetic: tiles must be saved within ~20 s of loading).
**Fix hint:** save a dirty tile when it is about to leave the window (`LodClip.recenter`/`clear`), or keep non-resident dirty keys out of `clear()` and write them from the clip's data before it is cleared, or flush by time (every few seconds) instead of every 3000 frames.
**Confidence:** high (A/B counts + source). **Not tested:** a real dedicated server, `saveDirty(true)` at world switch (same loss for tiles that left the window earlier).

### BH2-16 [minor] The Far Terrain page applies look values from the config unvalidated (NaN, 1e30, negative), the launch path refuses them

**Repro:** `farTerrain=on`, `farTerrain.quality=low`, `farTerrain.brightness=NaN`, `farTerrain.saturation=1e30`, `farTerrain.curvature=-5` (plus garbage in the other keys: `distance=99999`, `cpu=banana`, `generate=maybe`, `trees=0`, `fog=weird`, `progress=maybe`, `clouds=ON`),
plan `cmd=gamemode spectator @me,cmd=tp @me 101 230 450 180 5,wait=15,screen=farterrain,click=Distance,screen=close,wait=8,BSL_v10.1.8.zip,wait=8` (E1b, run `1023-044526`).
**Evidence:** the game starts and draws the far terrain (`field ready: reach 512 chunks, 7 levels of 512 x 512 cells`; distance 99999 and the other garbage ignored or defaulted), the page's buttons all read "100%" (their label lookup falls back to the default), but after the page has been opened and one button clicked the far terrain under BSL is a
uniform blue-grey (`selftest-9-wait_8.jpg`; the same step with default look values in V-low `1023-022939`, `selftest-5-wait_6.jpg`, is the green forest). A first look before the page was opened (step 3) was normal (launch values).
**Cause:** `Profile.farTerrainSettings` (`Profile.java:156-159`) copies the three look keys into `-Dcadence.lod.*` only when they match `[0-9]+(\.[0-9]+)?`; `FarTerrainScreen.applyLook()` (`FarTerrainScreen.java:112-120`, called from every click) reads them again with
`Float.parseFloat` (accepts "NaN", "1e30", "-5") and `LodConfig.look` stores the three floats as they are (`LodConfig.java:31-35`), no clamp. (Only hand-edited files get there: the buttons offer 5 fixed values.) A valid but absurd value (`curvature=999999999`, accepted by both paths) makes the whole far terrain
sink out of sight (E2b, run `1023-044755`: no far terrain at all, no crash). **Fix hint:** run both paths through one validator with a range (brightness and saturation 0.1..4, curvature 0..2000). **Confidence:** high for the inconsistency, medium for which of NaN / 1e30 / -5 paints the grey.

### BH2-17 [visual, black / fog-only screen] Photon and superDuperVanilla in the Nether with Far Terrain on: the DH macros are defined, the DH uniforms are zero

**Repro (A/B, same plan, only `farTerrain` differs):** `cmd=gamemode spectator @me,dim=the_nether,wait=8,BSL_v10.1.8.zip,wait=8,ComplementaryReimagined_r5.9.3.zip,wait=8,photon_v1.3b.zip,wait=8,Solas Shader V3.8.zip,wait=8,Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip,wait=8,off,wait=4`
(settle 6), Far Terrain on at Distance 128 / Quality Low (NP-on, run `1023-045005`) against Far Terrain off (NP-off, run `1023-045340`). Mean luminance of each step's screenshot (the picture is the same Nether canyon):

| step | far terrain on | far terrain off |
|---|---|---|
| BSL | 27.1 | 27.2 |
| Complementary Reimagined | 25.1 | 23.4 |
| **Photon** (`selftest-8-photon_v1.3b.zip.jpg`, `selftest-9-wait_8.jpg`) | **0.6 / 0.2 (black)** | 30.0 / 30.4 |
| Solas | 33.2 | 33.3 |
| Bliss | 51.6 | 51.0 |

The same plan with the other DH packs (NP2-on `1023-052241` / NP2-off `1023-052709`, screenshot after each pack's switch): **superDuperVanilla** shows a flat purple fog colour with a few black specks and no Nether at all with far terrain on (`selftest-11-wait_6.jpg`,
mean luminance 32.9 against 19.8 for the normal canyon with it off); Complementary Unbound differs slightly (30.5 against 27.6: a brighter lava glow); MakeUp, I Like Vanilla and Sildur's Enhanced Default, 1.541 and 2.02 are identical; Pegasus is almost black in both (2.6 / 1.5).
**Cause:** `PackPrograms` (`PackPrograms.java:118-128`) gives any pack that has a `dh_terrain` program the macros `DISTANT_HORIZONS` and `DH_BLOCK_*` whenever far terrain is wired in this launch, in every dimension, including the Nether where `Lod.ensureWorld` creates no field
(`cadence-lod: no far terrain in minecraft:the_nether (a ceiling ...)`). `FrameUniforms.setDistantProjection` (`FrameUniforms.java:645`) returns at `if (reach <= 0) return;`, so `dhRenderDistance`, `dhNearPlane`, `dhFarPlane` and the dh matrices stay zero there.
Photon's `shaders/shaders.properties:543-559` builds its combined-depth uniforms from them: `uniform.float.combined_far = dhRenderDistance`, `combined_projection_matrix_inverse_2 = vec4(0, 0, 0, -(combined_far - combined_near) / (2.0 * combined_far * combined_near))`, `..._inverse_3.w = (combined_far + combined_near) / (2.0 * combined_far * combined_near)`:
with `combined_far = 0` both divide by zero (inf/NaN), and `include/misc/lod_mod_support.glsl` (`LOD_MOD_ACTIVE`) uses them in the composite passes, so every pixel is NaN: black. superDuperVanilla: `shaders.properties:385` `uniform.float.borderFar = if(isEyeInWater == 0, dhRenderDistance, far)` and `lib/atmospherics/fogRender.glsl:3` `exp2(-exp2(playerPosLength / borderFar * 21.0 - 18.0))`: with `borderFar = 0` the fog is total. (How Iris sets the macro for a dimension Distant Horizons doesn't render was not verified here.)
**Fix hint:** compile the pack for a dimension without a far terrain with no `DISTANT_HORIZONS` (the programs are translated per dimension: `Pack ... programs for minecraft:the_end translated in the background`), or give the dh uniforms finite values (dhRenderDistance = the render distance in blocks, near 16, far 1.5x) when reach is 0.
**Confidence:** high for the symptom and the A/B, high for the division (read in the pack), not verified by patching. **Not tested:** Mellow, Noble and VanillaEX in the Nether (their dh program fails anyway), a flat/void world with a pack, the first frames of a world before the far terrain exists.

### BH2-18 [visual] The far terrain keeps the colours of the resource pack it was generated under

**Repro (A/B):** a test pack `bh2tex2.zip` (23 block textures replaced by solid 64x64 colours: dirt blue, stone yellow, sand magenta, gravel cyan, logs red, ...; `scratchpad/bughunt2/rp/`) in a scratch game folder, a test addon that selects it at runtime and calls `reloadResourcePacks()`
(the Resource Packs screen's effect; `scratchpad/bughunt2/rp/src/bh2rp/RpAddon.java`), far terrain on (default Distance 512, Quality Auto), spectator at `101 110 450 180 25`, 45 s after joining:
- RP4 (run `scratchpad/bughunt2/run-b/results/1023-050923`): vanilla textures first, the pack turned on at runtime (`Reloading ResourceManager: ..., file/bh2tex2.zip`, reload done 1 s later), then 80 s of waiting.
- RP5 (control, run `...-051254`): the pack selected in `options.txt` from launch, fresh far terrain cache.
`selftest-11-wait_8.jpg` of each, mean RGB per horizontal band of the picture: middle third (the far terrain just past the render distance) RP4 (67, 97, 42), RP5 (79, 110, 49); far band RP4 (132, 154, 189), RP5 (133, 157, 199); near third (the game's own terrain) RP4 (85, 136, 52),
RP5 (85, 137, 52): the near terrain follows the pack at once, the far terrain does not (the mean absolute pixel difference is 25-26 in the near and middle thirds, the foreground differs pixel by pixel between any two runs, so compare the band means). In RP5 the far terrain is full of the pack's colours (red trunks, violet leaves on the far hills, blue/yellow ground); in RP4, in the same places, it is still the vanilla forest (dark green, brown).
**Cause:** a tile's colour words are computed from the block textures' average colours when the tile is generated (`LodColors.compute`, `LodColors.java:113`, reading the resource manager), `LodField.save` keeps them in the disk cache (`LodField.java:380-430`, keyed by `FORMAT = 10` only (`:38`), no pack signature), and both
`LodColors.LOOKS`/`TEXTURES`/`PROFILES` (`LodColors.java:51-54`) and `LodPalette.IDS`/`STATES` (`LodPalette.java:22,74`) plus the shared palette buffer are static caches that nothing clears on a resource reload (`grep -rn -i reload lod/*.java`: no listener), so tiles generated after a reload also get
the old colours, and the atlas rectangles in the palette buffer are those of the atlas before the reload (the stitching moves when sprite sizes change). A different resource pack at the next launch reads the same cached tiles: RP6 (run `...-053416`): the pack selected from launch, over RP4's cache (tiles generated before the pack was turned on): the far band differs from RP4 by 1.0 and from the control RP5 by 7.8 (mean absolute difference), the middle third is (63, 90, 39) against RP5's (79, 110, 49): it stays in the old pack's colours across a restart (`rp456.jpg`: only the tiles generated near the camera after the restart show the pack's red, yellow and blue).
**Impact:** switching resource packs (Faithful, Better Leaves, a seasonal pack) with far terrain on leaves the far view in the old pack's colours for good; only deleting `cadence-lod/` brings it back. **Fix hint:** key the cache folder by a hash of the resource pack list (or the atlas), drop
`LodColors`/`LodPalette` caches on `ResourceManagerReloadListener`, clear the clipmap and re-request tiles. **Confidence:** high for the colours; medium for the stale atlas rectangles (the near textured band looked right in RP4, not isolated). **Not tested:** F3+T with an unchanged pack list (the atlas layout is then identical).

### BH2-19 [minor, UI] Every click on the Far Terrain page rebuilds it: the scroll position is lost and Pre-generate forgets it was pressed

**Repro:** a window of 800x480 at GUI Scale 3 (the page is taller than the list), far terrain on: `window=800x480,guiscale=3,wait=6,screen=farterrain,wait=1,scroll=200,wait=1,click=Earth Curvature,wait=1,scroll=200,wait=1,click=Pre-generate 1 km,wait=1,click=Brightness,wait=1,screen=close`
(UI1b, run `1023-053138`). `selftest-6-scroll_200.jpg`: the list scrolled to its bottom; `selftest-8-click_Earth_Curvature.jpg`: the list is back at the top (header "Changes take effect at the next launch", Far Terrain / Distance rows) with "Earth Curvature: Gentle" below; scrolled again, `selftest-12-click_Pre-generate_1_km.jpg`:
"Already generated" (disabled; the earlier runs had cached the 1 km); `selftest-14-click_Brightness.jpg`: the page is rebuilt at the top and the Pre-generate buttons read "Pre-generate 1 km" / "4 km" again, active. At GUI Scale 2 in 640x400 (UI1, `1023-045719`) the page fits and the same click after "Queued 2494 tiles" brings the button back as "Pre-generate 1 km"
(`selftest-12` / `selftest-14`).
**Cause:** `FarTerrainScreen.cycle` ends every click with `Minecraft.getInstance().gui.setScreen(new FarTerrainScreen(this.lastScreen))` (`FarTerrainScreen.java:146`, "rebuilt with the new value": the header line tells whether a restart is due), a fresh `OptionsList` at scroll 0 with fresh buttons.
**Impact:** on a small window or a large GUI scale each of the lower options (Earth Curvature, Brightness, Saturation, Pre-generate) means scrolling down again after every click; pressing Pre-generate a second time (the button no longer shows it was pressed) queues every tile again (`LodField.pregenerate` skips only tiles whose file exists), doubling `pregenQueued`
and the work (the progress line's percentage and cost). `CadenceSettings` solved the same problem by relaying out "keeping its scroll position". **Fix hint:** update the clicked button's label and the header in place (as the Look rows and `CadenceSettings.refreshAll` do), or keep the scroll amount across the rebuild. **Confidence:** high.

(more entries follow)

## Suspected, not confirmed

Each is something I saw once or read in the code but could not reproduce or measure here; "S2-nn" ids keep them apart from the confirmed bugs.

- **S2-01 Sildur's Vibrant 1.541 Extreme: giant vertical slabs once (visual).** First switch to that pack (after Kappa and an `off`) at the spawn vantage in F1 (run `1023-004951`, `selftest-7-Sildur_s_Vibrant_Shaders_v1.541_Extreme.zip.jpg`): olive, tan
  and dark vertical slabs the height of the picture hanging in the upper left over the beach, far-terrain colours at the wrong scale. Not reproduced in the same plan again (F10 `1023-020953`, F11, F12) nor in S5 at Distance 128
  (`1023-035621`, pictured fine). Candidates: a pack-mode frame drawn with a stale `args[116..118]` camera offset or stale records right after a switch (the vertex stage reads the camera offset and the quad records from buffers the cull rewrites
  in place); `dhProjectionPrevious` / `dhModelView` being supplied as zero matrices to that pack's other programs. Not proven either way.
- **S2-02 `CollisionAirMixin` answers every `state.isAir()` with the empty shape (exactness).** `CollisionAirMixin.java`: "Air has no collision shape in any context", true for vanilla, but a modded block that sets `air()` (so `isAir()`
  is true) and overrides `getCollisionShape` (a force field, an invisible wall built as an air block) would lose its collision for every entity. The game-logic mixins' other exactness checks in BUG-HUNT-10-21 had a verify switch; this one has none.
- **S2-03 `LodField.waiting` entries of tiles that are dropped are never removed (heap).** `LodField.generate` returns early when `!stillWanted(key)` (`LodField.java:223`) without `waiting.remove(key)`; `waiting` holds up to 16 `LodChunks.Summary` (about 11 KB each)
  per tile whose chunks loaded before the tile arrived. It needs a tile whose generation job loses the race with the window moving on (a fast flight with a backlog). The 11-minute soak's histograms show no `LodChunks$Summary` alive, so it stays a suspicion.
- **S2-04 Far-terrain caches are bounded by entries, not bytes (heap, perf).** `LodForest.trees` and `.grounds` are capped at `CACHE_MAX = 40000` entries each (`LodForest.java:59`, cleared whole at the cap, `:148`, `:189`; `offer()` at `:159` doesn't check the cap).
  In the soak (a forest/plains flight) the trees cache went 17,356 -> 38,983 entries in 6.5 minutes (the live heap after GC 542 -> 609 MB); a dense forest chunk's tree list is several KB, so the cap is hundreds of MB of an 8 GB Mac's 1.5-2 GB heap, and the whole-map `clear()` is a cliff
  (every visible tile's trees recomputed at once). Not measured at the cap.
- **S2-05 `LodField.work()` after the out-of-memory latch leaves `pregenDone` short (UI).** Dropped pre-generation jobs (`work()` `if (this.outOfMemory) { ...; continue; }`) never reach the `finally` that counts `pregenDone`, so `Lod.status()` shows
  "Far terrain: pre-generating N%" for the rest of the world. Needs the OOM latch (the End once ran out of memory at its first coarse tile; fixed by `LodNoise` skipping the interpolation).
- **S2-06 Far terrain cache keyed by server address only.** `Lod.ensureWorld` (`Lod.java:397`): `servers/<address with every char outside a-z0-9._- turned into _>/<dimension>`: two worlds behind one proxy address share one cache (a network's lobbies and survival world), and a LAN
  guest's folder is `servers/<ip>_<port>` with the host's ephemeral port (`metal/run-guest/cadence-lod/servers/localhost_25599` after my L1 run), so every LAN session starts an empty cache folder that is never reused or cleaned.
- **S2-07 No indication when a pack has no far terrain.** A pack without a `dh_terrain` program (7 of the 25 here: AstraLex, Insanity, Kappa, Nostalgia, PotatoShaders, VanillaPlus, miniature) simply shows none (`DistantTerrain.java:29` says so as a design), and the only
  trace is the log line when its program fails to translate (`PackPrograms.java:207`). A player who turned Far Terrain on and then picks one of them sees the far terrain vanish with no message on the page or in Video Settings.
- **S2-08 `RenderContext` Javadoc says the context object is reused every frame; a new record is allocated per hook call.** `ApiHooks.fire` (`ApiHooks.java:78`) creates `new RenderContext(...)` and `new MetalHandles(...)` per call. Harmless unless an addon relies on the identity
  (the A1b test addon's `sameCtxObject` stayed 0).
- **S2-09 A failing `dh_terrain` in a pack is only a log line.** (Mellow, VanillaEX, Noble: BH2-03/06/07.) Whatever the cause, the player sees an empty far view; `PackPrograms` warns once in the log and nothing reaches chat or the page.
- **S2-10 Quit from the title screen loses the last flush window.** With BH2-02's no-teardown, `LodField.close()` (`saveDirty(true)`) and `LodBeacons.save()` never run when the player quits from the title screen: tiles changed since the last 3000-frame flush
  (up to 100 s at 30 fps) and beacons since the last 1024-frame save are lost. Code only.


## Not tested / limits of this audit

- **One machine.** A MacBook Air M2 (8 GB, 8 GPU cores, 60 Hz panel). Nothing here was run on a Mac with 10 or more GPU cores (the `a2`/exact paths are gated off at 8: `a2ExactMaxCores 7` in the logs), on ProMotion (the 120 Hz present latch of commit 77881a3,
  BH2-08's cap on a 120 Hz display), on 16 GB or more (Quality Auto picks Medium up to 16 GB and High above: only the forced High was run, and it crashes: BH2-01), on Intel, or on an older macOS.
- **No Distant Horizons / Iris to compare with.** What Iris does with a fragment-stage `dhMaterialId` (BH2-06), with `dh_hasTexture()` (BH2-07) or with the `DISTANT_HORIZONS` macro in a dimension DH doesn't render (BH2-17) was not checked; those entries state the Cadence side only.
  Fixes were not tried (no source was changed), so every "fix hint" is a reading, not a verified patch. Pack options pages were not used to work around BH2-07 (`DH_TEXTURES 0` would need a file in `metal/run/shaderpacks`).
- **Self-test driven.** Everything went through the harness' steps (`mc.execute`, `click=`, `scroll=`, `window=`, `dim=`, `leave`/`join`, a test addon that selects a resource pack and a thread that moves the player), not a real mouse, keyboard, controller, text field or a player running at the same time.
  The memory guard never stopped a run (swap +0 on every one of ~95 launches); long idle soaks (hours) and overnight runs were not made: the longest run is the 11-minute flight.
- **Far terrain on a real server / LAN with real latency.** Only a LAN host and one guest on the same Mac (L1). Not run: a dedicated server, a server with a far-terrain-hostile world (custom generator, mods), cache sharing across worlds behind one address (S2-06), `saveDirty(true)` at a world switch, quitting from the title screen with unsaved tiles (code only).
- **API.** Hooks were registered and observed, never used to draw: no addon pipeline was drawn into `ctx.pass()` (with or without a pack, where the pass is the pack's G-buffer pass), no Metal handle was used, `FabricLoader` ordering with a second addon was not tried. The failing hook was tested at one stage (AFTER_OPAQUE).
- **Pack coverage.** All 25 packs were switched with far terrain on, but only the DH-capable ones at one vantage (`101 230 450`, noon, clear) and the Nether (NP/NP2); rain, night, the End and the Nether with the packs that failed in the Overworld (Mellow, Noble, VanillaEX) were not looked at; the packs' DH water program (`dh_water`) is not drawn by Cadence at all (only `dh_terrain`), so DH water in packs was out of scope.
- **Earlier hunt.** BUG-HUNT-10-21's BH-15 (about 470 animated sprites), BH-16 (validation layer), BH-17/18 (hostile pack sizes), BH-05 (cache budget pruning) and BH-06/BH-13/BH-20/BH-21/BH-23 were not re-run live; BH-01/02 only through `tools/logic-tests/run.sh` (below).
- **Perf numbers** (fps with far terrain: 1,598 -> 1,040 at Distance 128 / Quality Low in the Benchmark, 1,200 -> 318 fps at Distance 512 with unlimited frame rate) are single runs on a warm fanless laptop (macOS thermal state "fair" all night: every run had Heat Control holding at 120 fps unless a step turned it off); treat them as orders of magnitude.
- **Repo side effects of the runs** (the audit changed no tracked file): `metal/run/results/1023-*` (my result folders, kept as evidence), about 5,600 far-terrain tile files written into `metal/run/cadence-lod/` (generated terrain of the test world; deleting the folder regenerates it), `metal/run-guest/` was touched by the guest tool (it copies `metal/run/config/cadence-shaders.properties` into its folder on every start), crashed runs left `mcmetal*` extraction folders in `$TMPDIR` (removed at the end).
  `metal/run/config/*` (`cadence-shaders.properties` sha1 5a758ae4b99fbec0b00c4d3bfc6f0d5337133f13, the rest unchanged), `metal/run/options.txt` (the harness rewrites `renderDistance` and `enableVsync` in it on every run), `metal/run-compat/config/cadence-shaders.properties` (aa6b721f4d9c00d09d639ae75e51980c1f34c03f) and `metal/run-compat/options.txt` were compared with the copies taken at 00:45 and are identical (`diff -r`).

