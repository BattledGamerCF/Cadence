# Overnight test-and-optimize loop (2026-10-11 → 10-12)

The user asked for an autonomous overnight loop: test, fix, build performance improvements, re-test — and make sure nothing
stalls so they don't find out in the morning that it sat idle. They are away. Mac: M2 Air, 8 GB, on AC.

## Morning summary (kept current through the night — read this first)

**Your setup (1470x919 window, BSL, M2 Air 8 GB) — where it stands**
- Before tonight: BSL at full resolution = 13-17 fps (RD 16 / RD 12), and the Mac swapped (GPU memory 1.45 GB at RD 16).
- Now (default settings, measured on your world): **RD 12: ~31-33 fps on a cool Mac, 22-27 sustained once the Air is hot.
  RD 16: ~25-29 cool, ~19-21 sustained; with Shader Quality LOW ~28-34 sustained (~37-40 cool).** GPU memory at RD 16 ~1.0 GB at first, ~1.25 GB once you've looked around (was 1.45 GB). World ready in ~10 s at RD 16 (was ~16),
  F3+A rebuild ~3-4 s (was 30+). No more hang when leaving a world.
- The fanless Air's GPU slows to about half speed after 1-2 minutes of heavy shaders; Auto gives up resolution instead
  of frame rate. At RD 16 the frame is limited by geometry (shadow casters + terrain, ~27 ms of ~45), not pixels: the
  real fps levers there are Shader Quality (its shadow distance) and render distance.
- Shaders off: ~200-450 fps at RD 12-16 depending on the view.

**New defaults (all measured, all switchable)**
1. Auto resolution, now judged by real GPU time (works with VSync on; ignores CPU-bound moments).
2. Shadow receiver culling (upstream, audited for BSL 10.1.8): skips up to ~50% of shadow casters, -5..-7 ms per frame
   when it can; now also active under the Quality presets (upstream only trusted the exact defaults).
3. Compact 48-byte terrain vertices for packs (25% less terrain memory, identical image).
4. Faster chunk-build queue (same order as vanilla, verified on 140,000 picks with 0 differences): vanilla's queue
   used 13.8% of all CPU while loading with shaders and saturated every core after a teleport.
5. Leaving a world no longer hangs on "Saving world": with vanilla's queue it hung 5+ minutes in 4 of 4 shader runs
   (every worker still compiling); with Cadence 0 of 6.
6. Shadow compile cull: underground sections that can't shadow anything you could see are no longer built for the
   shadow map (surface terrain is all still built, so nothing pops in when you turn). BSL at RD 16: world ready in ~10 s
   instead of ~16, 130-290 MB less GPU memory, a few fps more while it matters.
7. Smaller chunk-buffer heaps while shaders are on (32 MB instead of 128 MB): -314 MB of GPU memory, same fps.
   Together: BSL at RD 16 uses ~1.0 GB of GPU memory at first and ~1.25 GB once you've looked around (was 1.45 GB).
8. Shader Quality changes apply in about a tenth of a second: the world isn't rebuilt when the preset leaves every
   block's pack id and the vertex layout unchanged (true for all of BSL's presets). Was a 7-50 s rebuild at RD 16.
9. Fixed a crash when switching shaders while chunks were still building (a worker could mix the two vertex formats).
10. Heap trim: with a big -Xmx (your 4 GB) on an 8 GB Mac, unused Java heap goes back to macOS (-263 MB measured).
11. One-time chat hint when even 50% resolution isn't enough, pointing at Shader Quality.
12. Apple's MetalFX now upscales the lower-resolution shader image (same GPU cost as before, sharper edges); new
    "Upscaler" button in Video Settings > Shaders to switch back to Cadence's smoother filter.

**Tried and rejected (measured: no gain, slower, or not exact):** shaderc SPIR-V optimisation, the discard-loads hint,
a 9-tap upscale filter (slower), resolution below 50% (no gain at RD 16), section-level sun reachability (only 30 MB),
evicting shadow casters (would make terrain pop in), caching the shadow map (the sun moves every frame).

**Trade-offs to know:** at 50% resolution the image is softer than full resolution (MetalFX upscale; HUD stays crisp). Shadows cast from
underground (looking into a big cave) may take a moment to appear the first time. Quality and resolution can be set in
Video Settings > Shaders; every new default has an off switch (see ../CHANGES-FROM-MCOPT.md).

**Recommendations**
- In CurseForge, lower Minecraft's memory from 4096 MB to about 2048-2560 MB and remove AlwaysPreTouch (8 GB Mac;
  the game used 0.5-0.9 GB of Java heap in every test tonight).
- Your options (read, not changed): render distance 16, **simulation distance 16**, VSync off, fullscreen. Simulation
  distance only decides how far the world *ticks* (mobs, crops, redstone); it doesn't change what you see. 8-10 saves
  CPU and memory in singleplayer at no visual cost.
- Shaders: leave Resolution on Auto. Quality LOW is about +50% (RD 16: ~30 fps sustained instead of ~20). Measured
  LOW at RD 8: 53 fps cool, ~47 warm; RD 10: 44 cool, ~30-34 hot. A steady 60 with BSL isn't realistic on a fanless Air;
  the next step down would be the MINIMUM preset (no shadows) or a lighter pack.
- Your "Cadence Test" instance has the newest build (last installed 02:13; the jars from before tonight are in
  `Cadence Test/mods-backup-1012-0003/` if you want to go back). Shaders were off in your config; turn BSL on in
  Video Settings > Shaders.

**Housekeeping:** tonight's test runs left ~2.4 GB in `cadence-metal/metal/run/results/` (logs + full-size
screenshots for every run) and ~0.6 GB in `metal/run/screenshots/`. Nothing there is needed; delete them whenever you
like (I didn't delete anything).

**Ideas for next time:** "Quality: Auto" (now that preset changes take ~0.1 s, Auto could step the preset down when
even 50% resolution isn't enough — opt-in, since it changes the look); GPU occlusion culling for the pack's terrain pass
(mcopt's own path has one); native partial mip generation (~0.5-1 ms).

## Rules for the loop
- One dev game at a time via `tools/cadence-test.sh <plan>` (runs in background; memory guard `tools/cadence-guard.sh`:
  free < 20% / swap +600 MB / RSS > 2.6 GB / 12 min hard limit). Never Gradle and the game together. Never touch the
  player's own Minecraft (CurseForge, pid differs; detected by not having `cadence-metal` in its command line).
- If the guard aborts twice in a row: wait 15 min before the next launch. Never relaunch in a tight loop.
- Every background wait has a deadline. Keep-awake: app hold + `caffeinate -i -s -t 36000`.
- Every change switch-gated and measured; correctness checked by screenshot (Read the PNG) + probe numbers.
- Log each run below (what, result, decision). Morning summary at the top.

## Test harness
- `-Dcadence.selftest=STEP,...` (pack | off | f3a | pack@mode), `-Dcadence.selftest.settle=S`; screenshots and
  `[selftest]` / `[probe]` lines; results in `metal/run/results/<time>/`.
- Probe line: `sections uncompiled/renderable/solidDraws` = terrain present; `pC/aB` = queue / free workspaces.

## Queue (priority order)
1. Live shader switching correct both ways (`rebuffer` mode vs `dispose`).
2. Baselines (FPS, same spot): mcopt Fastest (own terrain, no pack) vs Live no-pack vs BSL. 
3. Shader perf: discard-loads hint (`minecraftShaders.discardFullscreenLoads`), MSL cache, render scale / MetalFX.
4. CPU-side wins mcopt doesn't touch (measure first).

## Run log
- **18:16 run 1** (alongside the player's own 4 GB Minecraft; heap 1024M, RD 6, 960x540): start (Live mode, no pack) 932 fps.
  Off -> BSL with `rebuffer`: same dispatcher, chunk buffers rebuilt for stride 28 -> 64, terrain rebuilding at once
  (+6 s: 64 renderable sections, 3398 queued). **Guard stopped the run at +1 min: swap +673 MB** at the BSL shader-compile
  spike (Apple's compiler processes + the player's idle game being paged out). Decision: coexist runs get swap limit
  +1000 MB (free-memory floor 25% unchanged); built Shader Quality button, render scale, runtime discard A/B.
- **18:18 run 2** (coexist): **live switching fixed both ways with `rebuffer`** — off 1129 fps -> BSL 34 fps -> off 1142
  fps -> BSL 35 fps; screenshots correct (vanilla terrain after off; full BSL shadows/water after on). `scale=0.75` crashed:
  "Unidentified scene pipeline cadence_upscale" — the runtime intercepted my upscale pass as a scene pass. Fix: run it
  `raw` like the loader's own passes. (`dispose` mode no longer used; `rebuffer` is the default.)
- **18:21 run 3** (coexist, BSL defaults, RD 6, 960x540 window = 1920x1080 px): **render scale 1.0 = 37 fps, 0.75 = 51 fps
  (+38%), 0.5 = 74 fps (+100%)**; screenshots: 0.75 nearly indistinguishable, 0.5 softer but clean, HUD crisp. Discard-loads
  A/B on/off/on/off = 27/24/23/26 fps — inside the thermal drift (fanless Air): no measurable gain, stays off.
- **18:26 run 4** (coexist): live profile reload works (~100 ms + chunk rebuild). BSL fps, full res: default 24-36 (drifting),
  MEDIUM 29, LOW 35-37; at 75%: default 35, MEDIUM 46, LOW 50. Confounded: **night fell in-world during the run** and the
  Air heats up. Fix: self-test now freezes the world at noon, clear (advance_time/advance_weather false).
- **18:32 run 5** (coexist, shaders off, RD 6, small window): Live 1144/965 -> Fastest 651/732/801 -> Live 802/638/657 fps.
  Thermal drift dominates; no measurable cost of Live mode (own terrain off) at this load. Live stays default.
  Built: render scale **Auto** (dynamic resolution, holds autoTarget=60 fps, 0.05 steps every 0.5 s, 0.5..1.0).
- **18:38 run 6** (coexist, noon frozen): BSL default full res 38 fps; **Auto -> 0.5 floor, 55 fps**; **LOW + Auto -> 0.65,
  59-61 fps (holds 60)**; back to fixed 1.0 = 40 fps. Flaw: Auto stepped 0.05 at a time = ~10 resizes in 5 s, a 12 fps dip.
  Fix: jump to scale*sqrt(target/actual) (pixels ~ scale^2), 0.05 quantized, up-steps capped +0.15.
- **18:43 run 7**: proportional Auto still 14 resizes; hunted after a profile reload (transient fast/slow frames trusted).
  Fix: 2 s hold after any resize/reload, 1 s windows, increases need two agreeing windows (decreases immediate).
- **18:47 run 8**: still 14 resizes: after each reload, frames are cheap while chunks rebuild (longer than the 2 s hold) so
  Auto climbs, then drops. Fix: no increase while the chunk compile queue > 32; increases are +0.05 steps (decreases stay
  proportional). Note: the Air is now warm — BSL default at 0.5 measured 32 fps vs 55 an hour earlier.
- **18:53 run 9** (after 2 min cool-down): gated Auto works: 4 real scale changes (+3 same-size reallocations from profile
  reloads), holding 58-63 fps: BSL default @0.65 = 59, LOW @0.6-0.65 = 61-63, MEDIUM @0.55 = 60. **Default render scale is
  now Auto** (only lowers resolution when below 60 fps; button still offers fixed 100%).
- 19:00 ported upstream's Scene Optimizer (MIT; spatial section index for shadow-view selection, incremental dirty tracking)
  into the shaders module behind the same gate; `-Dcadence.sceneOptimizer=false` turns it off. Upstream claims no proven
  gain, so: A/B it.
- **18:59 run 10** Scene Optimizer A/B (4 launches, BSL LOW @0.5): on 97/97/97, off 95/91/44, on 97/96/41, off 96/78/55.
  Third windows collapse from memory pressure (player's 4 GB game open: free 26-30%, game RSS compressed 686->344 MB = stalls,
  one 0-fps second). Small differences unmeasurable alongside it. Clean windows lean slightly to "on"; upstream ships it on
  -> keep on, pending a correctness check (it is active in Live mode with shaders off too).
- **19:11 run 11** (correctness, Scene Optimizer on, default settings): shaders off 1175 fps -> BSL **59-60 fps (Auto chose
  0.65)** -> off 1122 fps; all three states full terrain (245/249/219 renderable sections, none uncompiled); BSL screenshot
  correct (shadows incl. player, clouds, water). Scene Optimizer stays on.
- **19:14 run 12** realistic size (1470x919 window = 2940x1838 px, RD 12), BSL default @1.0: **13 fps** (matches the
  user's 12 fps report). Guard stopped it at +1088 MB swap after 50 s (bigger targets + player's 4 GB game). Realistic runs
  deferred until the player's game closes (queued job, normal guard).
- 19:20 built a `compact=on|off` self-test step (sets `minecraftShaders.compactTerrainVertices`, reloads the pack; layout is
  chosen at pack activation). The pack's terrain vertex is 64 bytes; COMPACT is 48 (-25% vertex memory/bandwidth), used
  only when every block id fits in 16 bits. A/B queued as run 13.
- **19:18 run 13** compact terrain A/B (BSL, fixed 0.5): on 64 / off 45 / on 45 / off 50 fps — thermal drift again
  (75 -> 45 within 2 min), no measurable speed difference. Screenshots identical. Read the code: COMPACT is lossless (entity
  id + render type + the block's cell in its section packed in one uint; the shader rebuilds `at_midBlock` exactly from
  Position), only used when every material id fits in 16 bits. **Now default** (-25% pack terrain vertex memory, 64 -> 48
  bytes; `-DminecraftShaders.compactTerrainVertices=false` restores 64).
- **19:23 run 14** compact default: stride 48 at launch, live off (28) -> BSL (48) -> off (28), full terrain every state,
  no errors. Fixed: the self-test's `scale=` step saved its value into the config (dev config was left at 0.50; restored to
  auto; the step no longer saves).
- 19:28 run 15 queued: shaderc SPIR-V performance optimisation for pack shaders (upstream flag, off by default), 4
  interleaved launches off/on/off/on with 60 s cool-downs.
- 19:27 run 15 **invalid**: run 14 ended on "off" and the self-test had saved that as the player's choice -> run 15
  launched with no pack. Stopped it. Fix: the self-test snapshots config/minecraft-shader-loader.properties at start and
  writes it back at the end. Config restored to BSL.
- 19:30 built **GPU-timed Auto**: mcopt `GpuBusy` sums GPUEndTime-GPUStartTime of every retired command buffer (one native
  call per command buffer, always on). Auto now drops resolution only when frames are slow *and* the GPU is the reason
  (GPU ms/frame > 85% of budget), and rises when the GPU has 25% to spare even if the interval is pinned at the target —
  previously with VSync on (the user's setting) Auto could drop but never come back up; a CPU-bound frame (e.g. chunk
  building at RD 16) no longer costs resolution for nothing. Self-test line logs "interval X ms gpu Y ms".
- 19:32 run 16 queued: Auto check, then the SPIR-V optimisation A/B (4 launches, pack on).
- **19:30 run 16** Auto with GPU timing: the GPU figure read *higher* than the frame interval (19.6 vs 15.4 ms) — command
  buffers overlap on the GPU, so summing their spans double-counts; Auto then saw "GPU-bound" and sat at 0.5. Fix: GpuBusy
  counts the union of the spans.
- **19:34 runs 17-20** shaderc SPIR-V optimisation A/B (BSL, fixed 0.5, off/on/off/on, 60 s cool-downs): first fixed
  window off 75 / **on 59** / off 79 / **on 67** fps; second window collapses in all four (75->56, 59->38, 79->35, 67->38:
  the Air throttles ~90 s into each run). Optimised SPIR-V is no faster, likely slower (Apple's compiler already
  optimises the MSL) -> **stays off**.
- **19:47 run 21** Auto with union GPU timing: GPU ms now matches the interval when GPU-bound (15.1 vs 15.2 ms) — correct.
  It also explains the recurring "second window collapse": at the same 0.5 scale the **GPU's own time per frame doubles
  (15 -> 30 ms) ~1-2 min into a run** = the fanless M2 GPU throttling (CPU/memory not the cause). Auto was at its 0.5
  floor; it recovered to 50-56 fps as the GPU settled. Self-test now logs interval/GPU for fixed scales too;
  `CADENCE_VSYNC=true` makes a test run with VSync (the player's setting).
- **19:52 run 22** Auto under **VSync** (the player's setting): fixed 0.5 = 60 fps with GPU 13.3 ms of 16.7; Auto then
  climbed 0.5 -> 0.55 -> 0.6 while holding 60 fps (GPU 12-14 ms). Before GpuBusy it could never rise under VSync. Kept.
- 19:55 the user (back briefly) saw two Minecraft windows: their own CurseForge game (open ~3 h) + one dev test launch —
  expected, not a bug; only one test game ever runs at a time.
- **19:58 runs 23-24 REALISTIC** (player's game closed; 1470x919 window = 2940x1838 px, BSL, normal guard, fresh Air):
  | RD | default @1.0 | Auto (->0.5) | LOW @0.5 | MEDIUM @0.5 | MEDIUM @1.0 |
  |----|----|----|----|----|----|
  | 12 | 17 fps (58 ms GPU) | 35 (28 ms) | **51** (19 ms) | 29 (35 ms) | 12 (76 ms) |
  | 16 | 13 fps (76 ms GPU) | 24 (42 ms) | **40** (25 ms) | 26 (39 ms) | 15 (62 ms) |
  Always GPU-bound (GPU ms = interval). Auto doubles fps but sits at its 0.5 floor; LOW roughly +45-65% on top. RD 12->16
  at 0.5 costs +14 ms of GPU: geometry passes (shadow map + terrain) are a big share, not only pixels. Next: per-pass GPU
  profile to find the heavy passes.
- **20:10 run 25 per-pass GPU profile** (realistic size, RD 12, BSL default, fixed 0.5; mcopt `-Dmcopt.own.int.frameLog=true
  -Dmcopt.own.int.encLog=true` -> metal/run/enclog.csv; analysis by critical path = how far each encoder pushes the frame's
  end, since encoder spans overlap on the TBDR GPU): **36 ms/frame = shadow map 13.9 ms (38%, two 2048x2048 depth passes,
  geometry-bound)**, fullscreen deferred/composite passes at half res 10.8 ms (30%, ~11 passes), terrain/entity passes
  3.6 ms, upscale 1.4 ms, 3 full mip chains (33 tiny passes/frame) ~1.2 ms, vanilla atlas/lightmap ~2.7 ms.
  -> Shadow pass is the target. Upstream has audited **shadow receiver culling** (off by default; pinned to the BSL 10.1.8
  source fingerprint, 64-block padding) — made runtime-switchable (`receiver=on|off` self-test step) for an in-run A/B.
- **20:14 run 26** receiver culling in-run A/B (realistic, RD 12, fixed 0.5): eligible ("audited-bsl-10.1.8-defaults-
  overworld-v1"), culls **1853 of 3876 shadow sections (48%)**, 0.6 ms CPU/frame. GPU ms on/off/on/off/on = 28.4 / 46.8 /
  41.7 / 43.3 / 37.7 — every adjacent pair favours "on" (~5 ms/frame) under heavy thermal drift. Screenshot diff on vs
  off: only clouds and waving leaves/grass (an off vs off pair differs more). **Now on by default**
  (`-DminecraftShaders.shadowReceiverCulling=false` to disable; only audited packs are ever culled).
- **20:21 run 27** re-profile with receiver culling on (realistic, RD 12, 0.5): **29.6 ms/frame (was 36.4)**, shadow
  opaque+translucent 13.9 -> 8.2 ms; 36 fps vs 31 at the same point. Remaining per pass (BSL default): shadow 8.2, terrain
  3.4, two deferred passes 4.7, composites ~5, sky ~1.4, **Cadence upscale 1.46 ms**.
- **20:26 run 28** upscale as 5 bilinear taps + 4 point fetches (9 instead of 16): **slower, 2.08 ms** (other passes
  unchanged within 0.1-0.4 ms) — on this GPU 16 texelFetch beat filtered taps. Reverted.
- Shadow caching ruled out: the sun direction is interpolated with partial ticks, so the light view changes every frame
  in normal play (only identical with the daylight cycle off).
- **20:30 runs 29-30** discard-loads hint, per-pass profile (realistic, RD 12, 0.5): ten BSL passes qualify (deferred,
  deferred1, composite..composite7, final, skybasic; load action L1 -> L0 confirmed). Off-run throttled (window 2: 27 fps);
  per-pass off/on ratios: discarded passes 1.07-1.19, untouched passes 0.99-1.22 (shadow 1.22, terrain 1.05, upscale
  1.05) -> **no measurable effect** (Apple's lossless framebuffer compression makes loads cheap). Stays off.
- **20:37 runs 31-34** shaders OFF at the player's size (1470x919, RD 16), Live (default; mcopt own terrain off) vs
  Fastest (mcopt own terrain), interleaved, fresh windows: Live 328 / 323 fps, Fastest 349 / 341 fps (+6%); later
  windows collapse in both (thermal, uncapped 300+ fps; no memory pressure: free 33-45%, swap flat). The Live cost is
  ~5% at 300+ fps, irrelevant at 60/120 Hz -> **Live stays default**.
- Gotcha: `until ! pgrep -f script.sh` loops match their own command line and never end — use the task notification.
- 20:52 built a one-time chat hint (QualityHint): when Auto has been at its 50% floor for 30 s, GPU-bound and under 3/4
  of the target, it says once per launch that Shader Quality: LOW is much faster (never changes settings itself).
- **20:58 run 35** guard stop after 23 s: swap +681 MB at launch (RAM 60% free; swap file nearly full at 2.75/3 GB).
  Retrying once after 2 min with a JFR CPU recording of the world load (BSL, RD 16).
- Chunk loading with shaders: BSL at RD 16 needs ~15 s until every visible section is built vs 6-9 s with shaders off;
  the compile queue peaks at ~18,800 tasks with shaders (off-camera shadow casters) vs <20 without. Vanilla's
  SectionTaskDynamicQueue.poll scans the whole list (synchronized) for the nearest task on every poll -> O(n^2) with
  shaders. Measuring before changing it.
- **21:01 run 36** (retry, ok): quality hint shown once at +34 s ("about 30 fps, already at 50% resolution ... Quality: LOW
  is much faster"). JFR of the BSL RD 16 world load: **SectionTaskDynamicQueue.poll + removeTaskByIndex = 13.8% of all
  CPU samples** (more than block-state reads). Built `CompileQueue` (+ mixin, applies with or without shaders): two
  binary heaps ordered by (distance to the camera, insertion number) = exactly vanilla's pick (nearest; ties: earliest),
  rebuilt only when the camera moves; vanilla's recompile quota. `-Dcadence.fastCompileQueue=false|verify` (verify: vanilla
  decides, both choices compared every poll). Self-test step `tp=DX` moves the player (camera moves, tasks cancelled).
- **21:07 run 37** compile queue **verify**: 140,000 polls (world load, two teleports, F3+A), **0 mismatches** with vanilla.
- **21:11 runs 38-41** chunk build time, new queue vs vanilla (BSL, player's size, RD 16; self-test now logs "chunks built
  in X s" = until the compile queue has been empty for 1 s):
  | step | new queue | vanilla queue |
  |----|----|----|
  | world start | 14.4 s | 17.8 s |
  | F3+A | 8.0 s | 13.1 s |
  | teleport 64 blocks | 11.5 s | **56.8 s** (all cores saturated) |
  | F3+A | 6.9 s | **still building at the end; then quitting hung on "Saving world" 5+ min** |
  jstack of the hang: every Worker-Main thread at 100% compiling sections while the integrated server waited to save
  (same worker pool). Built `MinecraftDisconnectMixin`: leaving a world drops the queued compile tasks first
  (`-Dcadence.dropCompileOnLeave=false` = vanilla).
- Runs 38-41 were **confounded**: `tp=` moved the saved player, so every launch started further east and had to generate
  new terrain (worldgen shares the worker pool). Still: both vanilla-queue launches hung on quit ("Saving worlds") until
  the guard's 8-min limit; both new-queue launches exited normally (one also quit mid-build). The world is now restored
  from a snapshot of the player's `tester` save (copied read-only to the scratchpad) before every chunk benchmark.
- 21:37 runs 42-45 queued: clean A/B, Cadence (fast queue + drop-on-leave) vs vanilla, world restored each launch.
- **21:37 runs 42-45** clean A/B (world restored each launch; BSL, RD 16): **leaving the world: Cadence 23-24 s, vanilla
  hung until the guard (6 min) — 3 of 3 vanilla launches hung on quit, 0 of 4 Cadence launches.** Chunk build times are
  noisy at RD 16 with world generation (free RAM 24%, memory compression): Cadence 12.2/25.5/8.3/7.1 s and
  12.4/still/54.7/still vs vanilla 14.7/still/still/still. Next: repeated F3+A (pure rebuild, no worldgen) A/B.
- **21:57 runs 46-49** pure F3+A rebuild A/B (world restored): too noisy to call a speed winner (Cadence 6.7 / 41.1 s,
  vanilla 10.0 / 10.8 / 8.1 s; later steps overlap). The 41.1 s window ran at 9 fps while the **whole Mac stalled** (the
  guard's 30 s samples were 71 s apart; free RAM 67% -> 24%; game RSS swinging 429-967 MB = compressed and paged back):
  BSL at RD 16 uses ~3.4 GB in total on this 8 GB Mac, mostly outside the Java heap. **Quit hang: vanilla 4 of 4, Cadence
  0 of 6.** (Run 46 died at start: the previous batch's hung game was still exiting; the test script now waits for any
  earlier dev game and restores the world itself, `CADENCE_WORLD_SNAPSHOT`.)
- Next: where the 3.4 GB goes (`footprint`).
- **22:16 run 50** memory footprint (`footprint`), BSL at RD 16, player's size, ~70 s in: **3,438 MB total** = Java heap
  + JVM ~1,330 MB, **Metal (IOAccelerator) 1,125 MB + 270 MB unmapped graphics**, native malloc 241 MB, drawables 57 MB.
  (fps sank to 13 in that run: the Mac was swapping.) Built `MemoryProbe`: per-layer chunk uber-buffer heaps (vertex
  heaps are 128 MB each, index 32 MB, allocated whole) vs bytes used, plus the Metal device total; logged per step.
- **22:21 run 51** chunk geometry memory at RD 16 (MemoryProbe): **BSL: Metal 1,448 MB, chunk buffers 1,056 MB reserved /
  835 MB used** (SOLID 4 heaps 502 MB, CUTOUT 3 heaps 294 MB, TRANSLUCENT 37 MB) vs **shaders off: Metal 633 MB, chunk
  buffers 416 / 101 MB**. With shaders every section in the sun's view box is compiled for shadows (and vertices are 48
  vs 28 bytes): ~8x the geometry. Switching off releases it (rebuffer), switching on brings it back.
- 22:28 run 52: geometry census (on-screen vs shadow-only, above vs wholly below the terrain surface) + Java heap use.
- **22:27 run 52** geometry census (BSL, RD 16, 795 MB of chunk geometry): on-screen surface 133 MB (574 sections),
  on-screen underground 34 MB (318), shadow-only surface 207 MB (913), **shadow-only underground 421 MB (5,161 sections)**.
  Java heap ~900 of 1,280 MB used. More than half of all chunk memory is caves compiled only for shadows, where the sun
  can't reach. Upstream's receiver culling keeps them (its receivers are the whole camera frustum, which reaches
  underground). Built `LightReach`: section-level BFS of the shadow light through vanilla's per-section visibility sets
  (monotone in the light direction's signs; unknown = open), grown one step; a section it doesn't reach can't hold a
  surface any light ray hits first, so it never decides a shadow-map texel. Diagnostic first: how many MB it would skip.
- **22:34 run 53** LightReach diagnostic: only **30 MB (607 sections)** of shadow-only geometry is unreachable at section
  granularity (caves, water and openings connect almost everything at 16-block resolution) -> dropped.
- **22:38 run 54** ReceiverDepth diagnostic: receivers = the camera's visible sections + open-air sections in the view
  (volumetric light samples air in front of visible surfaces); light-space map of the deepest receiver per 8x8-block cell,
  receivers widened 32 blocks sideways (upstream's audit: filter reach + raster/waving displacement < 21) and 4 along the
  light. **372 MB (3,564 sections) of the 628 MB shadow-only geometry cannot shadow any receiver.** Built
  `ShadowCompileCull`: those sections are not scheduled for compiling (rendering unchanged: upstream's audited culling
  still picks what the shadow pass draws); map rebuilt every 10 frames; `-Dcadence.shadowCompileCull=false` = upstream.
- 22:42 run 55-56 queued: cull on vs off (memory, chunk time, screenshots).
- **22:42 runs 55-56** shadow compile cull, same world/spot (BSL, RD 16, player's size): **cull on: Metal 1,066 MB, chunk
  buffers 672 reserved / 448 MB used, world built in 11.5 s, F3+A 6.1 s, GPU 36-41 ms/frame; cull off: 1,450 MB,
  1,056 / 836 MB, 15.2 s, F3+A still building after 30 s, GPU 53-79 ms** (uncompiled casters also draw nothing in the
  shadow pass). Screenshot diff on vs off: mean 1.09/255, only the FPS counter, clouds, swaying leaves and the dappled
  light they cast. Next: the risky cases — view turned 180/90 degrees and a low sun (time 1500).
- **22:49 runs 57-58** cull on vs off with the view turned 180 and 90 degrees and a low morning sun (time 1500):
  screenshots match once compiled (player's and trees' long shadows identical; diffs = clouds, swaying leaves/grass).
  Trade-off seen: newly needed casters compile when you first look a new way (turn 180: 2.4 s; turn 90 at low sun:
  29 s with 11 fps meanwhile), and compiled sections are kept, so memory climbs back (449 -> 735 -> 778 MB used vs 835
  with the cull off). Net: faster loading and lower memory until you've looked around; shadows of not-yet-compiled
  casters can show up to a couple of seconds late the first time you look a new way. **Kept on** (vanilla shows the same
  pop-in for ordinary terrain); `-Dcadence.shadowCompileCull=false` restores upstream.
- Java heap in all runs: 700-930 MB used of 1,280. The player launches with -Xmx4096m + AlwaysPreTouch. Built
  `HeapTrim` (manageable JVM flags at runtime, only when -Xmx > 2.5 GB on <= 16 GB RAM: SoftMaxHeapSize max(1.5 GB,
  Xmx/3), Max/MinHeapFreeRatio 30/10, G1PeriodicGCInterval 30 s; logs the heap every minute). A/B with the player's JVM
  shape queued (runs 59-60, RD 12).
- **22:59 runs 59-60** HeapTrim with the player's JVM shape (-Xmx4096m, AlwaysPreTouch; BSL, RD 12, ~100 s in):
  footprint **2,680 MB off -> 2,417 MB on (-263 MB)**; Java part 1,165 -> 882 MB; committed heap held at ~660 MB (used
  460-544). Same fps in the first windows (33/33). **On by default** (only when -Xmx > 2.5 GB on <= 16 GB RAM).
  Also: Metal memory at RD 12 with the shadow compile cull = 600 MB.
- 23:10 chunk uber-buffer heap size: vanilla grows each layer's geometry by whole 128 MB heaps (index 32 MB) and mcopt
  makes them resident on first GPU use, so the unused tail is real memory (RD 16: 315 MB reserved-but-unused without
  shaders, 224 MB with BSL + cull). Built `ChunkHeaps` + `ChunkHeapSizeMixin`: 32 MB vertex / 8 MB index heaps
  (`-Dcadence.chunkHeapMB=128` = vanilla); ChunkBufferRebuild uses the same sizes. A/B queued (runs 61-64).
- **23:08 runs 61-64** heap 32 vs 128 MB (BSL RD 16 then shaders off): **with BSL, Metal 878 vs 1,192 MB (-314 MB), same
  fps (GPU-bound); shaders off, 388 vs 635 MB but fps 180/106 vs 226/167** (more draw groups cost ~20% when CPU-bound,
  even though the 128 MB runs were later and hotter). -> small heaps **only with a pack active**; without one, vanilla's
  128 MB. Chosen when the buffers are created (world load, and every switch via ChunkBufferRebuild).
- **23:21 run 65** per-mode heaps verified: BSL 32 MB heaps (Metal 877 MB) -> off: rebuilt with 128 MB heaps (633 MB,
  212 fps) -> BSL: 32 MB heaps again (880 MB); terrain complete in every state. **BSL at RD 16: Metal 1,448 -> 877 MB
  tonight** (shadow compile cull + small heaps).
- **23:25 run 66** JFR of world load + teleport + F3+A with everything on: the compile queue is gone from the profile.
  Top now: block reads during meshing (vanilla), the integrated server encoding chunks to save them after the
  teleport (vanilla, 4.7%+1.5%), and the pack's per-block material lookup (`BlockMaterialMap`, unmodifiable boxed map,
  1.6%) -> now a fastutil Reference2IntOpenHashMap (same answers, no boxing).
- 23:31 run 67: full sanity run (Auto, turn 180, off, BSL again) with everything together.
- **23:29 run 67** sanity, everything on (BSL RD 16): start 878 MB Metal, turn 180 -> 1,168 MB (casters for the new view
  compiled in 2.4 s), off -> 636 MB (128 MB heaps), BSL again -> 656 MB and rebuilding; terrain complete in every state;
  screenshots correct (re-enabled BSL identical to before the switch apart from clouds).
- **23:33 run 68** F3+A with BSL now rebuilds in 5.5-6.1 s (earlier tonight: still building after 30 s). Worker JFR: the
  pack's per-block material lookup was 10.5% of chunk-worker time — `beginBlock` runs for all 4,096 blocks of a section,
  air included. Now deferred to the block's first emitted vertex (same values). Verifying (run 69).
- **23:37 run 69** lazy material resolve: **F3+A rebuild 4.0 / 3.3 s (was 5.5 / 6.1 s)**; the lookup is gone from the
  worker profile (now vanilla: block reads 34%, biome lookups, fluid checks). Screenshot vs the previous build: mean
  diff 0.69/255 (animation level).
- **23:41 runs 70-71 final benchmark** on the player's own world spot (a big tree next to the camera: heavier than the
  meadow of runs 23-24) after 4+ hours of continuous testing (hot Mac): RD 12: full res 16 fps, Auto 33, LOW 39,
  MEDIUM 22; RD 16: 14 / 26 / 27 / 18. Not a clean before/after against runs 23-24 (different spot, thermal state);
  the clean A/Bs are the per-change runs above. Profile switches include first-time shader compiles (MEDIUM at RD 16
  took 49.8 s to rebuild everything while compiling its pipelines).
- 23:52 refined the shadow compile cull: vanilla's camera search can't see through an uncompiled section, so skipping
  surface casters behind you made terrain load in when you turned (vanilla-like, but upstream with shaders had it all
  ready). Now only **wholly underground** light-view sections that can't shadow a receiver are skipped; surface sections
  are all compiled as upstream.
- **23:52 runs 72-73** refined cull (underground only) vs off, turns 180 + 180: **on: Metal 1,006 MB at start (583 MB
  geometry), 1,169 MB after turning (736 MB); off: 1,296 MB (836 MB) throughout**. World built 10.1 vs 15.8 s; fps at
  start 25 vs 19. After a turn only 1.6 s of compiling (underground casters the new view needs); surface terrain was
  already built, so nothing pops in. Kept: saves ~130-290 MB, no terrain pop-in.
- 00:02 keep-awake extended to ~10:00 (caffeinate). Self-test now logs stutter: worst and 99th-percentile frame interval
  of the last 10 s (pack frames). Run 74: VSync on (player's setting), RD 16, Auto, two 48-block teleports, a turn.
- **00:00 run 74** stutter with VSync on (player's setting), BSL RD 16, Auto, teleports and a turn: 25-30 fps; frames
  alternate 33/50 ms (VSync quantisation at ~37 ms of GPU per frame); worst frame 55-68 ms during the teleports, 99th
  percentile 54-65 ms -> chunk loading causes no big hitches.
- **00:03 installed** the current build into the player's "Cadence Test" instance (previous jars backed up in
  `Cadence Test/mods-backup-1012-0003/`, outside mods/ so it isn't loaded). Their config has shaders off (`pack=`),
  left as is.
- **00:05 run 75** how much the shadow pass could still drop with the receiver map: 330 of 3,339 drawn sections per
  frame (10%) can't shadow a receiver (upstream's frustum-based culling already rejects 2,783 of 6,122) -> not worth a
  per-frame map. Diagnostic kept behind `-Dcadence.shadowSelectionDiag=true`.
- 00:12 render scale: fixed scales now go down to 0.33 (config or self-test); Auto's floor is `autoMin=` in the config
  (default 0.5, unchanged behaviour). Measuring 0.5 / 0.4 / 0.33 (run 76).
- **00:08 run 76** fixed scale at RD 16 (BSL): 0.5 = 25 fps (39.5 ms GPU), **0.4 = 27 fps (38.5 ms), 0.33 = 26 fps
  (41.2 ms)** -> below 50% the frame is no longer pixel-bound here; Auto's floor stays 0.5.
- **00:13 run 77** per-pass GPU profile at RD 16 (0.5, hot Mac, 46.7 ms/frame): **shadow pass 17.5 ms (37%)**, terrain
  gbuffers 7.2 + 2.1 + 0.5 ms, deferred 5.1, composites ~5, upscale 1.7 (first encoder's 5.7 ms includes overlap with
  the previous frame). At RD 16 the frame is geometry-bound (shadow casters + terrain), which is why going below 0.5
  doesn't help. The remaining big lever is the pack's own shadowDistance (BSL default 256 blocks, LOW 128) — a quality
  choice, which the Quality hint points to.
- 00:20 run 78: stability soak (~9 min: teleports, turns, F3+A, off/on switches, profile reload; memory per step).
- **00:17 run 78 stability soak** (RD 12, 21 steps over 9 min: teleports, turns, F3+A, three off/on switches, LOW and
  back): no errors or exceptions, normal shutdown. Metal with BSL 744-913 MB, off 633-638 MB every time (no growth
  across switches); Java heap used 483-868 MB, committed 834 -> 1,019 of 1,280 (normal G1 growth). LOW at RD 12 / 0.5 =
  37-40 fps vs default 27-29. (The teleports landed the player inside a hill, so visible-section counts dropped — not a
  rendering fault: all visible sections were compiled in every step.)
- **00:28 runs 79-80** is the shadow pass pixel- or geometry-bound? Shadow map 2048 vs 1024 (per-pass profile, RD 16):
  17.3 -> 12.9 ms for 4x fewer pixels -> ~11 ms is caster geometry (vertex + tiling), ~6 ms fragments. Draw-order
  tricks wouldn't buy much; generic GPU work for BSL is near its floor at the pack's settings.
- 00:36 **Shader Quality changes without rebuilding the world**: a reload compares the old and new terrain configuration
  (vertex layout, AO mode, every block state's pack id); when identical — the usual case for a quality preset — the
  compiled chunks are exactly what a rebuild would make, so they are kept (`-Dcadence.keepChunksOnReload=false` =
  always rebuild). Verifying (run 81).
- **00:35 run 81 — a crash found**: chunks were rebuilt anyway (the presets changed something; now logged), and the
  game **crashed** on the last preset switch ("Tesselating block in world: Shader terrain vertex has no block/material
  context"). A race in live switching: a chunk worker that started a section during the ~200 ms with no pack
  (deactivate -> activate) had no pack context, but asked for its layers' vertex format after the pack was back on, so it
  got the pack format. Happened because the switch landed while a big rebuild was still running. Fixed: inside a section
  compile the section's own snapshot decides the format and pipeline (`isEnabledForThread`, including "no pack");
  elsewhere the current configuration. Stress test queued: 9 switches 12 s apart (presets, off/on) at RD 16.
- **00:40 run 82 stress test** (RD 16, 9 switches 12 s apart incl. during rebuilds: LOW, MEDIUM, default, LOW, off, BSL,
  default, off, BSL): **no crash**; terrain complete after every step. **Preset changes now keep the chunks** (logged
  "terrain geometry unchanged": BSL's presets don't change any block's id) -> a Shader Quality change takes ~0.1 s
  instead of a 7-50 s world rebuild; off/on still rebuild (1-9 s). Screenshots: LOW (37 fps) vs default (24 fps) on the
  kept chunks both correct.
- **00:45 run 83** dimension change (RD 8): overworld 39 fps -> Nether (BSL, no shadows) 62 fps, off 488, BSL again 57
  (switching inside the Nether works) -> back to the overworld 18 fps (hot), terrain complete everywhere, no errors.
- 00:43 reinstalled the build with the crash fix + kept-chunks presets into the player's instance (backup from 00:03
  still holds the evening's original jars).
- **00:51 run 84** render distance changes with shaders on (rd= step): 12 -> 16 -> 8 -> 12 -> off -> 16 -> BSL: memory
  follows (RD 8 678 MB, 12 ~780-810, 16 1,165), heap sizes adapt, terrain complete, no errors. (RD 16 with shaders off
  took 30 s because the server had to generate new chunks.)
- **00:57 run 85 chaos test**: 40 mixed actions 5 s apart (on/off, presets, fixed/Auto scale, F3+A, teleports, render
  distance, compact layout off/on) at RD 12: all 40 done, no exceptions, normal shutdown. (Low visible-section counts at
  the end = the last teleport left the player inside a hill; the screenshot shows the inside of blocks.)
- 01:00 built an optional **MetalFX spatial upscaler** (Apple's, `mcfx.m` + `MetalFxUpscaler`; config `upscaler=metalfx`,
  default stays Cadence's shader). Run 86: works (correct image, no fallback); side-by-side crops at 50%: MetalFX gives
  crisper block edges and distant trees than Catmull-Rom + sharpen. Cost A/B (interleaved, 8 windows) running.
- **01:10 run 87** MetalFX vs Cadence upscaler, 8 interleaved windows (RD 12, 0.5): GPU ms per frame before the Mac
  throttled: MetalFX 32.2 / 31.8, Cadence 32.4 / 35.5 -> same cost. With the sharper edges, **MetalFX is now the default
  upscaler** (falls back to Cadence's shader automatically if MetalFX can't run); Video Settings > Shaders has a new
  "Upscaler: MetalFX / Cadence" button. Run 88: works through Auto's resizes (scaler rebuilt per size) and at 75%.
- **01:19 run 89** 0.5 vs 0.4 at RD 12 with MetalFX (interleaved): 31 vs 34 fps (GPU 31.9 vs 29.3 ms, +10%); crops show
  0.4 clearly blurrier textures -> not offered by default (set `autoMin=0.4` or `renderScale=0.4` in the config to trade
  sharpness for ~10% at RD 12).
- **01:22 run 90 MetalFX soak** (RD 12, 31 steps: ~25 scale changes = scaler rebuilt each time, off/on, presets, F3+A,
  turns): no errors. Metal memory outside the chunk buffers at 50% scale stayed 404-410 MB from start to end -> no
  scaler leak (the rise in the total was chunk geometry for casters compiled after the turns).
- **01:37 run 91 — 25-minute steady play** (RD 12, Auto, BSL default, small turns and back-and-forth moves): 57 steps,
  no errors. Metal memory flat at 924-926 MB once casters for every direction were built (~minute 8); Java heap used
  516-764 MB, committed constant 837 MB -> no leak. **Sustained fps on the hot Air: 22-27** (the cool-Mac 31-33 drops as
  the GPU throttles); Quality LOW adds roughly 40-50% on top.
- 02:08 found: upstream's receiver culling is **off under every Quality preset** ("non-default-pack-options": it only
  trusts BSL's exact defaults) — so LOW/MEDIUM lost the culling. The presets only switch shadow features off (light
  shafts, shadow color, shadow filter, TAA), change AO (screen space), and scale shadow map resolution with shadow
  distance (texel 0.25 block at LOW/MEDIUM/HIGH and the defaults, 1/3 at ULTRA): every shadow lookup is one the audited
  defaults also make, and the filter reach grows at most 4/3 (13.9 -> 18.5 blocks, far inside the 64-block padding).
  Eligibility now accepts exactly those differences (reason logged; anything else still refuses). Testing (run 92).
- **02:05-02:10 runs 92-93** receiver culling under presets: now applied for LOW, MEDIUM and ULTRA (reason
  "audited-bsl-10.1.8-defaults-overworld-v1-preset"). In the starting view it rejects nothing (depends on view vs sun);
  after a 180-degree turn under LOW it draws 1,324 of 2,631 casters. Screenshot diffs on vs off (mean 7.78 / 7.68) are no
  larger than off vs off (8.21) -> same image. GPU gain at LOW lost in thermal noise (LOW's shadow pass is cheaper).
- **02:15 run 94 — final 25-minute soak of the installed build at RD 16** (59 steps: turns, short moves, 18 preset
  switches, off/on): no errors. Metal memory plateaus at 1.23-1.29 GB once every direction has been seen (was 1.45 GB
  at the start of the night; shaders off 0.67 GB). **Sustained on the hot Air at RD 16: default ~19-21 fps, LOW ~28-34
  fps.**
- **02:43 runs 95-96** LOW + Auto at small render distances: RD 8 = 53 fps cool -> 47 warm (GPU 19-22 ms); RD 10 = 44
  cool -> 27-34 hot. Recommendation corrected: a steady 60 with BSL isn't realistic on the Air.
