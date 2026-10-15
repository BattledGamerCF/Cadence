# Cadence research round 2: further optimization ideas (M2 Air, 8 GB, fanless)

Scope: ideas not already in the mod (see the list in the brief). Each idea carries an expected gain, risk, effort, and whether it changes visuals. Gains are my estimates from sources and reasoning, not measurements. Nothing was installed or downloaded. Only this file was written.

Confidence legend: **[src]** claim backed by a fetched or searched source. **[know]** from general knowledge, not verified in this session. **[gap]** searched, nothing usable found.

---

## 1. Engine internals (Minecraft 26.x / 1.21+)

- **Sodium 0.9.1 (for MC 26.1.2) added asynchronous occlusion culling** to cut frame drops when the camera moves at high render distance. Sodium's own task-scheduler work is described as ongoing. [src: modrinth.com/mod/sodium/version/mc26.1.2-0.9.1-fabric; newreleases.io CaffeineMC/sodium]. Cadence's two-phase GPU Hi-Z already covers this on the GPU side. Use Sodium's approach only as a benchmark baseline, not as a new feature.
- **Shader-pack compatibility**: a guide reports that the 26.1 render changes broke many shader setups and recommends Iris with Sodium as the more reliable path. [src: search summary, unverified primary]. Relevant because Cadence's shader loader must track engine changes too.
- **Not found [gap]**: no authoritative source on the 26.x chunk-meshing, entity-render, or lighting-engine internals, and no Java 25 requirement statement for MC 26.1 from the searches. Read the decompiled 26.3 sources directly (the mod already targets them) before designing anything around engine internals.

## 2. Apple Silicon M2 GPU, CPU, memory

### 2a. Tile-based deferred rendering (TBDR)
- Apple GPUs split the render target into tiles and process all geometry per tile, writing the final tile to device memory once. Tile memory is much faster and lower-latency than device memory, and "dontCare" store actions and memoryless attachments avoid device-memory traffic entirely. [src: developer.apple.com/tutorials/.../tailor-your-apps-for-apple-gpus-and-tile-based-deferred-rendering]
- Imageblocks, tile shaders, and raster order groups (A11 and later, so M2 qualifies) let a pass read and write per-pixel data in tile memory, and let several passes share it without a device-memory round trip. [src: same Apple page]

**Ideas**
1. **Memoryless and dontCare for transient targets.** Depth/stencil and MSAA intermediates that are never sampled later should use memoryless storage and dontCare store. Apply this to shadow-pass and intermediate attachments. Gain: lower device-memory bandwidth, moderate on a 60 fps target at 100% scale. Risk: low (validate with Metal GPU capture). Effort: low. Visuals: no.
2. **Tile shaders for the shader-pack composite.** Fold tonemap, exposure, and composite passes into one render pass using tile shading, so the lit scene is never written back to device memory before the final pass. Gain: real for shader-pack users, where the post chain is long. Risk: medium (shader-pack compatibility). Effort: high. Visuals: no, if the math is kept identical.
3. **Raster order groups for any custom blend in the pack pipeline.** Only worth it if a pack needs ordered blending, such as custom transparency. Gain: unclear. Effort: medium. Visuals: no.

### 2b. Command submission and GPU-driven drawing (CPU side)
- Indirect command buffers (ICBs) let commands be encoded on the GPU, and Metal 4's `executeCommands(buffer:indirectBuffer:)` takes a GPU-filled range buffer so the GPU chooses which span runs. Draw arguments can come from a compute pass. [src: developer.apple.com indirect command encoding; MTL4RenderCommandEncoder docs]
- Any pipeline state referenced by an ICB must be in a residency set that is active when the buffer is committed. [src: same Apple docs, pitfall section]
- Hardware note: ICBs are supported on A9 and later; pointer-indexed argument buffers need argument buffer tier 2 (A13 and Apple silicon). [src: Kodeco Metal tutorial; secondary]

**Ideas**
4. **GPU-driven chunk draws via ICB, fed by the existing Hi-Z pass.** The compute pass that culls chunk sections writes draw arguments and the render pass executes a GPU-filled range, so the CPU encodes almost nothing per frame for terrain. Gain: CPU frame-time reduction in draw-heavy scenes, possibly significant. Risk: medium-high (needs Metal 4 / recent macOS; residency bugs are silent). Effort: high. Visuals: no. Note the existing "packed int draw commands" design is already close to this shape, so the migration is mostly mechanical.
5. **Residency sets for the chunk heaps.** Put the chunk-geometry heaps and texture atlases in one residency set, rather than making each resource resident per encoder. Gain: CPU overhead and stalls reduced when the heap is large. Risk: low. Effort: low-medium. Visuals: no. [know] Metal 3/4 residency sets are the supported mechanism for this; the exact Metal 4 attachment path was not verified in this session [gap].

### 2c. Upscaling (MetalFX)
- MetalFX has a spatial scaler (single input, macOS 13+), a temporal scaler (needs motion vectors, jitter, and reactive/exposure masks), and a frame interpolator (macOS 26+, Metal 4). The frame interpolator is less thoroughly documented than the upscalers. [src: developer.apple.com MTLFXSpatialScaler, MTLFXTemporalScaler, MTLFXFrameInterpolatorDescriptor; WWDC22 10103; WWDC25 211]

**Ideas**
6. **MetalFX temporal scaler for world-only render scale (planned path).** Better image quality than spatial at the same internal resolution. Gain: visual quality at the same cost, which lets the mod keep a higher internal scale for a given frame budget. Risk: medium. Motion vectors for Minecraft's block animation, particles, and entities are the hard part; reactive masks are needed for water and particles. Effort: high. **Visuals: yes** (ghosting and temporal smear on fast-moving entities; user must accept). Recommend keeping spatial as the default.
7. **Frame interpolator: do not use for Cadence.** It adds latency and produces generated frames, which changes what the player sees. Expected gain is high on paper but it conflicts with the "fair, faithful rendering" goal. Visuals: yes. Risk: high. Effort: high. Rejected unless the user asks.

### 2d. Thermal and power behaviour (fanless M2 Air)
- Reviewers ran a 30-minute Cinebench R23 loop: the M2 Air fell about 12-13% from its own peak multi-core score, while the fan-cooled M2 Pro held steady and was about 25% ahead under sustained load. Apple's passive design is intentional for thinness. [src: tweaktown.com/news/87417; wccftech.com/m2-macbook-air-throttling-problem-under-sustained-workload; macworld.com/article/821312]
- `ProcessInfo.thermalState` gives four coarse levels (nominal, fair, serious, critical) and posts `thermalStateDidChangeNotification`. Apple gives no temperature or clock thresholds. One source reports it sat in "fair" through throttling, but that is anecdotal. [src: developer.apple.com ProcessInfo.thermalState docs; stanislas.blog/2025/12/macos-thermal-throttling-app]
- Low Power Mode (LPM): one researcher measured LPM capping GPU frequency under 1 GHz (GPU power about 20 W to 6 W on an M4 Pro, with render performance falling only to about 69%), and CPU P-core clocks down to 1.8 GHz with high-QoS threads moved to E cores. On an M3 Pro, LPM showed no detectable change. [src: eclecticlight.co/2025/01/06/power-modes-and-apple-silicon-gpus; eclecticlight.co/2025/01/22/m4-pro-full-on...; M4/M3 figures, not M2]
- Developer reports: with Low Power Mode on and fullscreen vsync on macOS 26 beta, an app was limited to 30 fps. Apple DTS said the behaviour may be intentional. [src: developer.apple.com/forums/thread/795447]

**Ideas**
8. **Steady-state governor, not burst.** Target the sustained clock, not the burst. Measure frame time over a 2-3 minute window and set the render scale from the sustained result, not the first 30 seconds. The mod's "Auto targets 60 fps" should be validated under a 30-minute run, because the Air will settle lower than the first minute suggests. Gain: smoother frame pacing, no late collapse. Risk: low. Effort: low-medium. Visuals: only as the governor adjusts scale. This is the most important finding in this round for a fanless machine.
9. **Read `thermalState` and `isLowPowerModeEnabled` as hints to step down early.** Use them to drop render scale or the frame target before frames actually miss, and to defer chunk compile work. Do not treat `thermalState` as a throttling detector, because it is coarse. Gain: avoids heat-driven spikes. Risk: low. Effort: low. Visuals: only when the governor steps down.
10. **Frame-rate cap at the display refresh (60 Hz) rather than uncapped.** Race-to-idle (finish fast, then idle) and steady-state (run at a lower clock all the time) are both plausible on this chip. [know] Measure with a 30-minute powermetrics run before picking one. Gain: less heat, potentially more sustained clock. Risk: low. Effort: low. Visuals: no.
11. **Do not fight LPM.** If LPM is on, honour the lower target instead of trying to hold 60 fps. Check whether the 30 fps clamp appears in windowed mode too before blaming Cadence. Gain: avoids a worse experience than the OS would give anyway. Risk: low. Effort: low.

### 2e. CPU scheduling (P/E cores, QoS)
- Per Eclectic Light's reverse-engineering: `userInteractive` and `userInitiated` threads prefer P cores; `utility` and above can also run on P cores; `background` (QoS 9) is confined to E cores; overflow from busy P cores spills high-QoS threads onto E cores, which run at higher frequency. Game Mode gives priority access to CPU cores. [src: eclecticlight.co/2025/05/09/what-is-quality-of-service...; eclecticlight.co/2024/12/17/tune-for-performance-core-types]
- This is observed behaviour, not documented by Apple. Thresholds may change with macOS.

**Ideas**
12. **Set QoS by role.** Render and main thread at `userInteractive`; chunk compile workers at `utility` (so they can use P cores without starving the render thread); IO and disk-cache threads at `background`. Verify that the render thread does not get demoted. Gain: frame-time consistency under chunk load. Risk: low. Effort: low. Visuals: no.
13. **Watch for the "demotion" tools.** App Tamer and `taskpolicy` can demote other processes, but cannot promote. Document this for the user, and do not rely on promotion. [src: eclecticlight.co QoS article]

### 2f. Unified memory and compression on 8 GB
- macOS compresses inactive RAM pages before writing to swap. High "Memory Used" is not a problem by itself. The memory-pressure graph (green, yellow, red) is the meaningful signal. A few hundred MB of swap is normal; multi-GB swap with the pressure graph in yellow or red is the real warning. [src: forums.macrumors.com threads; mole.fit/blog/mac-memory-pressure-explained; macos-tahoe.com (unverified numbers)]
- Caution: the macos-tahoe.com claims (50% average compression, 10-100x faster than SSD swap) are unsourced. Do not cite them.

**Ideas**
14. **Use memory pressure as a governor input.** Register a `DispatchSource` memory-pressure source [know, not verified in this session] and back off chunk cache size and texture budgets when pressure rises. Also sample `vm.swapusage` and `memory_pressure` in the test harness. Gain: avoids stalls from swap at 8 GB. Risk: low. Effort: low-medium. Visuals: only if caches shrink.
15. **Texture formats.** Use ASTC or BC-compressed atlases where the pack allows it, to cut unified-memory use for textures. [know: Apple GPUs support ASTC; not verified here] Gain: significant RAM relief, if the atlas is currently uncompressed. Risk: low-medium (artifacts on some textures). Effort: medium. Visuals: slight, if compression artifacts show.

### 2g. Frame pacing and display
- `CAMetalLayer` has `displaySyncEnabled` and `maximumDrawableCount`. Two drawables usually present on the next vsync if rendering is quick. A forum thread reports that changing `drawableSize` made `nextDrawable` stall for about a second in many cases. Turning off `displaySyncEnabled` reduced this (from over 90% of cases to about 50%) but did not remove it. Apple's DTS suggests `CAMetalDisplayLink`. [src: developer.apple.com/forums/thread/711033; forums/thread/773229; WWDC21 10147]
- Reference: Cadence's dynamic render scale changes the drawable size, which can trigger this stall.

**Ideas**
16. **Never resize the drawable for render scale.** Render the world into an offscreen target at the variable scale, then upscale into a fixed-size drawable. Gain: removes the one-second `nextDrawable` stall that resizing causes. Risk: low. Effort: low-medium. Visuals: no (the upscale path is already planned for MetalFX). This is probably the most concrete hitch fix in this round, but it needs in-game verification.
17. **Adopt `CAMetalDisplayLink`** (macOS 14+) for pacing on ProMotion displays, instead of a hand-rolled timer. Gain: steadier frame timing. Risk: low. Effort: medium. Visuals: no. [know, API availability not verified here]

---

## 3. JVM on macOS ARM

- **JEP 514 (AOT ergonomics)**: a single `-XX:AOTCacheOutput` option makes the launcher do training and cache creation in one command. `JDK_AOT_VM_OPTIONS` passes options only to the cache build. [src: mail.openjdk.org jdk-dev May 2025 JEP 514 proposal]
- **JEP 515 (AOT method profiling)**: method profiles from a previous run are stored in the AOT cache, so the JIT can compile native code right away at startup. [src: same search; medium.com/@achrafhasbi (secondary)]
- **JEP 483** (JDK 24) is the base: AOT class loading and linking.
- **Not found [gap]**: "Fabric CDS" did not match any source. Whether Fabric Loader and Minecraft's class set work with `-XX:AOTCacheOutput` was not verified.

18. **AOT cache for startup and warm JIT.** Build the cache once per Minecraft and mod set, and pass it at launch. Gain: faster startup and earlier peak JIT speed. Risk: low-medium (cache invalidates on any jar change; must rebuild after each mod update). Effort: low-medium. Visuals: no. Test with `-Xlog:startuptime` (or equivalent) before and after.

### GC choice
- One benchmark (mineguard.pro, 2026) on a 16 GB server heap reports Generational ZGC averaging about 0.18 ms pauses versus G1's 35 ms, with G1 slightly ahead on throughput when CPU-bound. It is one source with no methodology in the excerpt. [src: mineguard.pro/en/blog/zgc-vs-g1gc-minecraft-java-21-benchmark]
- Generational ZGC is production-ready in JDK 21 (JEP 439). [src: same article]
- Client-side Sodium-style rendering was not benchmarked against GC in any source [gap].

19. **Keep G1 for the client; do not switch to ZGC on 8 GB without a test.** ZGC's larger memory overhead conflicts with the 8 GB budget, and the pause data is from a server, not a client. Gain: unknown. Risk: RAM regression on 8 GB. Effort: low to test, but the test needs `-Xlog:gc*` over a real session. Visuals: no.

### Other JVM items
- **Large pages / thread priorities**: [know, not researched] macOS does not support explicit large pages in the same way as Linux; skip unless measured.

---

## 4. Other mods: what they do and their licenses

Licenses confirmed from search results. Items marked unknown should be checked on the upstream page before any code is copied.

| Mod | What it does | License (source) | Relevance |
|---|---|---|---|
| Sodium | Rendering engine; async occlusion culling in 0.9.1 | [gap] Sodium's own license not confirmed; Embeddium forked the last FOSS version (0.5.8), which hints Sodium may not be FOSS past that point | Benchmark baseline only |
| Iris | Shader loader on top of Sodium | LGPL-3.0-only (modrinth.com/mod/iris) | Model for shader-pack compatibility |
| Lithium | Server logic optimizations | [gap] not confirmed; user said skip | Skip |
| C2ME | Parallel chunk generation/IO | MIT for the Fabric build (modrinth.com/mod/VSNURh3q); some uploads ARR; check per file | Parallel chunk gen has been tried and rejected for FEATURES; C2ME's approach differs, worth reading the code |
| ModernFix | Startup and memory fixes | [gap] not searched | Ideas for startup and RAM |
| FerriteCore | Memory use for blockstates/models | [gap] not searched | RAM relief; likely already covered |
| Nvidium | Region-based GPU-driven terrain using NVIDIA mesh shaders | LGPL-3.0-only on Modrinth; some repackages ARR (modrinth.com/modpack/nvidium, curseforge) | NVIDIA-specific; concept (GPU-driven region culling) maps to ICB (idea 4), code does not port |
| ImmediatelyFast | Batches immediate-mode UI and text draws | LGPL-3.0-or-later (modrinth.com/mod/immediatelyfast) | Concept (batch UI and font draws) fits the planned UI-at-full-res path; do not copy code |
| EntityCulling | Culls entities behind walls | tr7zw Protective License, "do not redistribute" [src: awesome.ecosyste.ms]; older 1.8 backport MIT | Cadence already has entity culling; use as a comparison |
| Distant Horizons | Far-terrain LOD | [gap] license not found in results | Far-terrain LOD would be a visual change; check license before reading code |
| Noisium, Krypton, More Culling, Bobby, Moonrise/ScalableLux | Worldgen, networking, culling, LOD cache, lighting | [gap] not searched | Next pass if wanted |

20. **Immediate-mode UI batching (ImmediatelyFast's concept).** Batch the UI and text draws into one pass, with a glyph atlas. Gain: CPU reduction in UI-heavy screens; small in gameplay. Risk: low. Effort: medium. Visuals: no.

---

## 5. Everything else

- **Audio**: [gap] not researched. Suggest a separate search (OpenAL on macOS, sound-engine caching).
- **Texture atlas and mipmap memory**: covered in idea 15. Mipmap bias is a visual change; skip unless needed.
- **Font rendering**: covered in idea 20; a glyph cache is the main lever. [know]
- **Power usage and startup**: covered in ideas 18 and 8-11.
- **Blender and other off-machine tools**: not relevant.

---

## Ranked top 15 (highest expected value first, for a fanless 8 GB M2 Air)

1. **Steady-state governor** (idea 8): tune on a 30-minute sustained run, not burst. High value, low risk.
2. **Never resize the drawable** for render scale (idea 16): removes the known `nextDrawable` stall. High value, low risk.
3. **Memoryless and dontCare transients** (idea 1): cuts device-memory bandwidth. Medium-high value, low risk.
4. **QoS by role** (idea 12): steadier frame times under chunk load. Medium value, low risk.
5. **Thermal and LPM hints to step down early** (idea 9) plus **do not fight LPM** (idea 11). Medium value, low risk.
6. **Memory-pressure governor** (idea 14) for the 8 GB budget. Medium value, low risk.
7. **Residency sets for chunk heaps and atlases** (idea 5). Medium value, low risk.
8. **AOT cache for startup** (idea 18). Medium value on startup, low-medium risk.
9. **ASTC/BC texture compression** (idea 15), if atlases are uncompressed. Medium-high RAM value, low-medium risk, slight visual risk.
10. **Frame cap at the display refresh**, measured both ways (idea 10). Medium value, low risk.
11. **GPU-driven chunk draws via ICB** (idea 4). Potentially high CPU value, high effort, medium-high risk.
12. **Tile-shader composite for shader packs** (idea 2). Medium value for pack users, high effort.
13. **CAMetalDisplayLink for pacing** (idea 17). Medium value on ProMotion, medium effort.
14. **MetalFX temporal for world scale** (idea 6), opt-in only. Quality value; visual change; high effort.
15. **UI and font batching** (idea 20). Low-medium value in gameplay, medium effort.

Rejected or deferred: frame interpolation (idea 7, visual and latency cost, against the fair-rendering goal); ZGC switch (idea 19, 8 GB risk, no client data).

---

## Gaps for the next research pass

- Metal 4 residency set attachment path and the ICB residency requirement in full (Apple's "Encoding indirect command buffers on the GPU" article was not fetched).
- Apple's current `CAMetalDisplayLink` and `DispatchSource` memory-pressure API docs were not fetched.
- Sodium, Caffeine, Lithium, and Distant Horizons licenses (upstream pages not fetched).
- Audio, startup profiling tools, and the M2-specific GPU architecture (the searches returned M3/A17-era material only).
- Real numbers for any of the above on this machine. Every gain here is an estimate until measured with the existing test harness.
