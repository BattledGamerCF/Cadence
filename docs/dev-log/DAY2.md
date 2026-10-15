# Day 2 (2026-10-12): entities, particles, shadow pass

The user asked for more optimization beyond rendering: entity logic, chunk loading, entity/particle culling, anything,
built into Cadence itself (no external mods; Lithium may be tried at the very end, not during tests). Rules as before:
each change exact (or a flagged visual trade-off), switchable, measured.

## Research (one Sonnet subagent, low effort; two web searches, the rest from background knowledge — treat as leads)
- EntityCulling (tr7zw) has 26.1 Fabric builds but a custom "Protective" license: not copyable. Write our own.
- Sodium/Iris/Lithium/ModernFix/ImmediatelyFast: LGPL — study only, don't copy code.
- FerriteCore (reportedly MIT) and C2ME (reportedly MIT): copyable if the license checks out on their own pages.
- -XX:+UseCompactObjectHeaders (production in Java 25): smaller Java objects; a launch flag, can't be set by a mod.
- Shadow pass ideas: update less often, cache static terrain, depth-only casters.

## What vanilla 26.3 and mcopt already do (read from the 26.3 jar)
- Particles: already frustum-tested at extraction (QuadParticleGroup.extractRenderState → Frustum.pointInFrustum), but
  every particle is still ticked, with block collisions.
- Entities: frustum + "section compiled and faded in" (LevelExtractor.isEntityVisible); no occlusion.
- Block entities: drawn from visibleSections (camera search + frustum) without a pack — already occlusion culled.
- With a pack (loader): every entity inside the light's box (all around the player, out to the shadow distance) is
  extracted and drawn in BOTH passes; block entities of every light-view section, too.
- Vanilla's camera search (SectionOcclusionGraph) keeps a node for every reached section, empty ones included (empty
  sections are left out of visibleSections but have nodes): getNode(section) != null = "can be seen at all".

## Runs
- 10:02 run invalid: the dev run loads metal/run/mods/cadence-shaders.jar, which was still the 02:04 build (the old jar
  took "scene=busy" for a pack name). tools/cadence-test.sh now copies the freshly built jar in before every run.
- 10:04 BSL RD 12 1470x919 scale 0.5, busy scene (120 mobs, 63 chests, 63 campfires in a sealed room under the ground
  ahead; 60 animals + 80 items + 6 campfires in view; 60 animals behind): EntityCull counters: 93% of drawn particles
  were behind walls (the buried campfires' smoke), ~10-14% of entities in view were behind walls; 88% of extracted
  block entities and ~60% of extracted entities were not camera-visible (now shadow pass only). FPS: thermal drift
  30 → 21 over the run swamped any difference (GPU-bound: interval = GPU time, 33-48 ms).
- 10:10 no pack, same scene, JFR: the scene costs 414 → ~120-170 fps (render thread bound). Cull off 142/117/142, on
  120/147/173 (noisy, within drift). Render thread: 27% entity model vertex building (ModelPart.Cube.compile → vanilla's
  already-fast BufferBuilder path), 11% particle extraction, 8% particle ticks (campfire smoke collisions), 8% entity
  extraction, 8% terrain draw lists. Conclusion: entity CPU is not the limit at these counts (still 120+ fps); with BSL
  the GPU is.

## Built
1. **EntityCull** (cadence/EntityCull.java, mixins EntityCullMixin, EntityRendererCullInvoker, ParticleCullMixin; on by
   default, -Dcadence.entityCull=false). Entities and particles whose sections vanilla's camera search didn't reach are
   not drawn (glowing, ridden/riding, leashed, opted-out and >3-section entities never culled). With a pack: each entity
   and block entity goes to the camera pass only if the camera can see it and to the shadow pass only if the shadow
   pass's own section test (light box + audited receiver volume) accepts it; neither = not extracted. Exact: the same
   test terrain passes. Effect is CPU/vertex work in busy scenes; no fps change measurable at 120+ fps / GPU-bound BSL.
2. **ShadowReuse** (cadence/ShadowReuse.java; config shadowRate=, default 2; Video Settings > Shaders "Shadow Updates").
   The shadow map is drawn every 2nd frame; the frame between gets the drawn frame's shadow matrices shifted by the
   camera's movement (the pack's shadow matrices take camera-relative positions), so still terrain's shadows land on the
   same texels. Redrawn at once on new targets, a >4-block jump or a >0.5° sun change. Measured BSL RD 12 1470x919, hot,
   interleaved 3×: GPU 46.9 → 41.5 ms/frame, fps ~22 → ~25 (+15%). Static screenshots: rate1-vs-rate2 differences equal
   rate1-vs-rate1 (cloud/grass animation only). Moving camera (spectator at y 110 looking down, gliding 10 blocks/s,
   rate 4 to exaggerate): house and tree shadows stay attached and crisp. Trade-off: shadows of moving things update at
   half the frame rate.
3. **ParticlePhysics** (cadence/ParticlePhysics.java, mixin ParticleMoveMixin; -Dcadence.particlePhysics=false). Before
   vanilla's collision search for a moving particle, the same cells are checked with vanilla's own per-cell rule
   (BlockCollisions + Cursor3D types, read from the 26.3 bytecode: inside cells any block, face-shell cells only large
   collision shapes, edge-shell cells only moving pistons, corners nothing). No candidate cell = vanilla's list would be
   empty = movement unchanged, exactly. Busy scene: 2.37M of 2.61M particle moves (90%) took the fast path. FPS effect
   not measurable in that run (a 70 s system-wide stall hit it; CurseForge was busy); kept because it is exact.

## Measured, not built
- **-XX:+UseCompactObjectHeaders** (Java 25 launch flag; a mod can't set it): live heap after full GC 437/440 → 414 MB
  with BSL, 393/395 → 371 MB without (−5-6%, ~23 MB). Recommended for the CurseForge JVM arguments; small.
- **Server thread** (busy scene, ~320 mobs): ~18% busy; mob AI + movement collisions. Not a bottleneck at these counts.
  A walking mob's swept box always touches the ground, so the particle fast path wouldn't help entity movement.
- **New terrain at elytra speed** (spectator, 20 blocks/s, no pack, RD 12): fps steady ~115; chunk building can't keep
  up because every worker core is busy (≈50% world generation: noise fill, surface, features; ≈22% meshing — in live
  mode the Fabric Indigo mesher; mcopt's own faster mesher is used only with liveSwitching=false). Faster world
  generation would need an exact (seed-identical) rewrite of noise/feature code — big and risky; idea only.
- "chunks built in 65.7 s" after turning shaders off was a metric artefact: every visible section was rebuilt in 3 s
  (probe: uncompiled=0 at +3 s); far, unseen sections trickle in afterwards.

## Regression (11:00, all new defaults on): BSL → busy scene → LOW → default → F3+A → off → BSL → Nether → turn →
   overworld → RD 8 → RD 12 → shadow rate 1/2: every step ran, no exceptions.

## Installed into the player's "Cadence Test" instance 11:12 (shaders jar; backup in mods-backup-1012-1110).

## Afternoon: "build the others anyway" + world generation on more cores (user request ~12:00)
4. **Section locks** (mixin PalettedContainerLockMixin; -Dcadence.sectionLocks=vanilla). Live heap histogram (RD 12, no
   pack): 133k PalettedContainers each with a ThreadingDetector + Semaphore + ReentrantLock (+ syncs) ≈ 17 MB. Now one
   field per container + one shared detector; a concurrent write still crashes with vanilla's ReportedException.
   First build crashed (Mixin forbids a NEW-redirect returning null) → shared instance. Measured live heap after GC:
   407/409 MB vanilla → 390/390 MB (−17 MB at RD 12, grows with loaded chunks).
5. **FastCollisions** (entity block colliders without the Guava iterator): first version no change (server-thread
   share of the collision search 11.0/11.6% vs 9.9% vanilla; the cost is block lookups, not the iterator). Second
   version reads blocks straight from cached sections — to be measured; removed if it shows nothing.
6. **World generation** — what 26.3 actually does (bytecode): ChunkMap runs every chunk's generation steps one at a time
   on a ConsecutiveExecutor("worldgen"), but TERRAIN (noise + surface + carvers) is a supplyAsync on the worker pool:
   already parallel. FEATURES (write radius 1) runs synchronously on the serial executor. Player chunk loading goes
   through a ThrottlingChunkTaskDispatcher with 4 chunks in flight.
   - Forced 16×16 areas (genarea), vanilla, unstalled: 8-17 s per 256 chunks with the JVM at 400-485% CPU: generation
     already uses 4-5 cores. Worker samples: terrain ~70-75%, features ~15%, other serial ~20%.
   - Vanilla world generation is NOT deterministic run to run: two vanilla runs of the same area differ in 89-99 of
     256 chunk hashes (feature placement order); parallel vs vanilla differ 63-71. Exact comparison is impossible;
     block-type totals per area are compared instead.
   - Timing on this Mac is disturbed by system-wide stalls (8 GB: Claude ~1.3 GB, CurseForge ~1.5 GB, Apple's on-device
     model; the game gets compressed, a 1 s shell sampler goes silent up to 34 s, the guard 77 s): one vanilla area
     took 110 s. Runs are now checked for sampler gaps.
   - ParallelWorldgen v1 (FEATURES on the pool under RegionScheduler; every other step blocked inline when its chunk
     was held): slower — the worldgen thread spent up to 13 s per area blocked. v2: only INITIALIZE_LIGHT can meet a
     running FEATURES step (vanilla's dependencies; confirmed by a counter: "other steps that had to wait: 0"), and it
     now runs after it instead of blocking.
   - Player loading slots (-Dcadence.chunkLoadSlots, vanilla 4): 16 is WORSE flying at 20 blocks/s (chunks loaded ahead
     of 12: vanilla 7.8/6.8, 16 slots 6.1/4.9) — work spreads sideways. Left at vanilla's 4.
   - ParallelWorldgen v2 (INITIALIZE_LIGHT deferred, never blocking), 16-slot vs vanilla, 3× interleaved fly tests:
     chunks ahead vanilla 5.7(stalled)/6.8/6.6, parallel 5.2/4.9/4.9 → worse. Serial executor time while flying is 86%
     FEATURES, but generation is CPU-bound at 4-5 cores (JVM 400-485%) alongside the 300-400 fps render thread and the
     other apps; terrain (noise 32%, surface rules 33%) and features (ores 9%, trees 7%) are the CPU. mcopt's worker QoS
     is unset (threads may use the P-cores). **Shipped OFF** (-Dcadence.parallelWorldgen=true to try); the palette cache
     and per-structure placement mixins stay (harmless, needed if it's turned on).
   - FastCollisions v2 (direct section reads): server-thread collision share 9.8/10.9% vs vanilla 12.3/10.8% — within
     noise. **Removed** (exact but no measurable gain).
   - Regression 14:12 (BSL, busy scene, LOW/default, F3+A, off/on, Nether, new 16×16 area, 20 b/s glide, gc): all steps
     ran, no exceptions or crashes.

## Day 3 (evening): GPU phase
- **Shadow proxies** (DESIGN-DAY3.md §1; default ON, shadowProxyDistance=96). RD 16, BSL, 1470x919, scale 0.5,
  interleaved ×3 (15:00): proxies off 21/21/21 fps (GPU 48.5/49.8/47.9 ms) vs on 27/31/30 (37.2/31.2/31.7 ms);
  Metal memory from a fresh start 714 MB (chunk buffers 296 MB) vs 1022-1128 MB (616-680 MB) — far shadow-only sections
  are no longer compiled. (The player's own game was open for the first minute of this run — see below.) Elevated
  spectator view over a forest, morning sun: GPU 74.8 → 60.3 and 74.0 → 68.0 ms; screenshots nearly identical (canopy
  shadows slightly fuller under some trees).
- **Test-harness bug found**: tools/cadence-test.sh's "is the player's Minecraft open" check used `pgrep -f KnotClient`,
  which missed the player's game (very long command line) — a benchmark ran alongside their multiplayer session
  14:58-15:03. Now `ps -A -ww` over every java command line (verified it matches a client launched like theirs and
  excludes the dev client).
- **FaceCull** (cadence/facecull; default on, -Dcadence.faceCull=false). Opaque/cutout section layers are reordered at
  build time into 7 facing groups (stable partition: same-facing quads keep their order); draws are emitted per group
  that can face the viewer (camera position for the main view, sun direction for the shadow pass); only for pipelines
  that cull back faces, so the image is identical. 33% of grouped quads skipped (RD 16). Screenshots: no holes; diffs =
  clouds/water animation only. In-run A/B (new `ab=FEATURE:S` self-test: flips every 2 s, compares GPU ms of 1-s
  windows wholly in each state): GPU −6.15 ms and −3.65 ms per frame. No shaders, one pair: 109 → 140 fps.
- Shadow color targets no program samples are left unattached in the shadow pass (BSL with SHADOW_COLOR off, e.g. LOW).
- Compact unifont (UnihexCompact): glyphs in one byte array, ~6 MB heap.
- Rejected without building: half-float positions/16-bit UVs (UV16 is not exact on the atlas; position-only saves 4 of
  48 bytes), thermal governor (only lowers fps earlier).
- Per-pass GPU at RD 16 with proxies (enclog): main terrain gbuffers 15.5 ms, shadow (drawn frames) 10.6 ms, composites
  ~3.6 ms each, final upscale+GUI 3.65 ms; ~60 ms critical path on the hot Mac.
- The player's own session 16:00-16:03 on the 14:17 build with the new JVM arguments (compact headers, ZGC, 2 GB, no
  AlwaysPreTouch): clean start and exit, no errors. (Their first paste lost the leading "-": "Could not find or load
  main class XX:+UseZGC".)
- **Occlusion estimate** (self-test step `occlusion`: 9 rays per drawn section through opaque blocks, BSL RD 16):
  ground level 48-94% of drawn quads hidden behind terrain (4 directions), from y+100 17-31%. Vanilla's section flood
  can't remove these (open terrain connects everything behind a hill). → built Hi-Z.
- **HiZ** (cadence/hiz/HiZ.java, native/mccull.m, mcopt.metal.GpuCull; default on with a pack, -Dcadence.hiz=false):
  two-phase GPU occlusion culling of the camera's opaque terrain: the same indirect commands drawn twice; cull_a sets
  instance counts from a 64×32×64 section visibility grid (visible last frame), phase 1 draws, the pass is suspended,
  pyr0/pyrN reduce the depth (pack convention: z' = (z+w)/2, standard depth, GL row order) to a max-depth pyramid,
  cull_b tests each section box (nearest corner depth vs farthest pyramid depth over its rect, 1 px margin, any corner
  near/behind the eye = visible) and phase 2 draws the newly visible. Translucent sections are then tested once against
  the same pyramid (cull_t). Untested at commit.
- CPU profile, no pack, flying 20 b/s east then back (idle Mac): chunks ahead 11.4/12 and 12/12 (the earlier 6-7 were
  under system load). Render thread: prepareChunkRendersIndirect 28% (FaceCull.addDraws 10%: now per-frame cached camera
  and layer cull flags, and a section is split only when ≥15% of its quads face away), terrain draw encoding 21% (mcopt
  turns each indirect command into one Metal draw call: mc_r_draw_indexed_indirect), entity models 12%. Workers: terrain
  generation 46%, meshing 26%, features 14.5%, face reorder 0.3%.
- **HiZ tested** (evening): first run never culled (the pack pass's depth texture is only bound after the terrain
  pipeline is, so it is read after phase 1 now; with no depth target the rest is drawn and HiZ turns itself off). Then
  a Metal validation abort at 2940×1846 (pyramid mip count one too many: fixed). Working: in-run A/B −1.14 ms GPU;
  stepwise off/on 42→45 and 41→43 fps (BSL RD 16); off-vs-on screenshots differ only in clouds and grass sway.
- **G1 vs ZGC** (RD 16 BSL, 2 GB heap, compact headers, the same route): ZGC footprint 3246 MB (heap committed 1650 MB);
  G1 + HeapTrim (now: above 1.5 GB max, every 30 s SoftMaxHeapSize = live×1.6+128 MB, 768 MB..¾ max) 2380 MB (heap
  committed 672 MB). → recommend G1 for the 8 GB Mac.
- **Crash found by the regression run**: BSL LOW samples no shadow color, so the shadow pass's slot 0 was left
  unattached, and vanilla's createRenderPass sizes a pass from its first color attachment (NPE). Slot 0 now always stays
  attached; only later unread slots drop out. Hi-Z also forgets last frame's camera view if vanilla hands the same view
  object to the shadow pass. The test script now stops an idle Gradle daemon instead of waiting 15 min for it.
- **RAM census** (RD 16 BSL after a glide; malloc_history with MallocStackLogging=lite, a new GPU census per self-test
  step, a new `histo` step = in-JVM class histogram): footprint ~2.9 GB = Java heap committed ~1 GB (live 613 MB: block
  palettes long[] 173 MB, light byte[] ~100 MB, 386k scheduled ticks ~30 MB; the integrated server holds 1,783 full +
  1,610 proto chunks vs the client's ~1,089 — vanilla's generation margin), metaspace 168 MB, JVM malloc 130 MB (symbol
  table 26 MB, JIT metadata), LWJGL jemalloc 90-120 MB, libshaderc 30 MB (glslang keeps it even after the compiler is
  released: tried, measured, reverted), Metal ~700-820 MB = chunk UberBuffer heaps 300-460 MB + textures 260 MB (window-
  size main color/depth + vanilla's HUD hand depth 83 MB, atlases ~50 MB, pack targets ~60 MB).
- **NativeTrim** (default on, ≤16 GB RAM; -Dcadence.nativeTrim=false): every 30 s `arena.4096.purge` hands jemalloc's
  freed pages back. Same route twice: jemalloc mapped 121 → 87 MB (another pair 111 → 88). LWJGL's jemalloc has no stats.
- Per-pass GPU profile now names each pack program (encLog labels carry the pass name). Sky-after-terrain reordering
  rejected: skipping the sky draws entirely (debug A/B) saved only 0.19-0.24 ms (0.83 ms looking up); the critical-path
  attribution of 1.26 ms to the sky pass was the frame's first-touch cost, not the sky shader.
- **Render-thread CPU with a pack** (JFR, BSL RD 16, same 2-min route): the shader loader's shadow "dirty sections"
  extraction was 35% of the render thread — ShadowCompileCull drops far/unneeded casters, but they stay dirty, so every
  frame re-walked ~13k of them (boxed Long iteration, identity-map lookups, frustum tests, merge). Now: primitive
  iteration; the request carries ShadowCompileCull's skip set by section node (excluded before any lookup; camera
  sections still come from the camera list); the candidate set is cached until the dirty set (new version counter),
  the skip set or the index changes — verified against a fresh scan (self-tests check every 16th reuse: 0 differences
  in 6,186 checks incl. glide/turns/setblock); skip set kept by node only, the 10-frame refresh reads each column's
  surface once (not 24×5 heightmap lookups). Pack mipmap-buffer lists memoized (a regex over all constants ran on
  every pipeline change). Render-thread samples 4,380 → 2,430 (−45%).
- **Pack load / dimension change stall**: the GLSL preprocessor's `(?m)^\s*#line` strippers were quadratic on
  shaderc's long blank-line runs (\s matches newlines). Horizontal whitespace only: same GLSL. Returning to the
  Overworld: worst frame 4,338 → 128 ms; pack re-enable 2,662 → 34 ms.
- **Garbage (user: "GC in the mod, not just flags")**: the lever is the allocation rate — every young collection is
  a 3-18 ms pause inside a frame. JFR allocation profile (BSL RD 16, 2.5 min): 15.3 GB allocated, 228 collections
  (~1.4/s), render thread 55 MB/s. Fixed (all exact): PackPrograms.sequence memoized (a regex per program per frame,
  830 MB), ReceiverDepth.bounds scratch array (500 MB), PackTextures.custom lookups cached, FrameUniforms component
  names cached, EntityCull's camera-section set and pass arrays reused, ShadowCompileCull's sets double-buffered.
  **PackedDraws**: vanilla builds an IndexedDraw record per visible section-layer per frame (FaceCull multiplies
  them) in fresh ArrayLists; opaque/cutout groups now use pooled int-packed lists, the record constructor is
  redirected to stash its ints, the list's add (FaceCull's split included) stores them, and vanilla's GPU writer
  iterates one reused writer object — same bytes, same order (screenshots on/off within the scene's own noise;
  A/B GPU ±0.07 ms; 15 M draws stashed, 0 materialized). MixinExtras @WrapOperation allocates an operation object per
  call: our per-draw hooks (and the loader's per-section layer hook) are plain @Redirects now. Result on the same
  route: render thread 8.2 → 5.4 GB, collections 228 → 100.
- **ChunkHeapCompactor** (review pass): vanilla frees a chunk-geometry heap only when it holds nothing; after
  travelling, 305 MB used of 584 MB reserved (BSL RD 16). Every 2 s, an uber buffer's emptiest heap (under half full,
  fitting in the others) is evacuated: allocations moved by GPU buffer copies (4 MB/frame), vanilla's allocation map
  repointed (draws look slices up every frame), the heap excluded from vanilla's placement, closed a frame after it
  empties. Same route: 584 → 392 MB reserved after the turn (309 used). Static-camera screenshots before/after 242
  moved meshes differ only in clouds and leaf shimmer (6.8 vs 5.5 without moves); no missing or broken sections.
  `-Dcadence.heapCompact=false`, self-test heapcompact=. 16 MB heaps (instead of 32) measured: only -36 MB, not kept.
- Review pass, small items: ShadowCompileCull's receiver map rebuilds when the view changes (4 blocks, 5 degrees,
  sun 0.5 degree, or 30 frames) instead of every 10 frames: 8.2% → 3.2% of the render thread (it was a 3-5 ms spike
  every 10th frame). Parallel-worldgen mixins (ConcurrentHashMap palette cache, synchronized structure placement) are
  no longer applied unless -Dcadence.parallelWorldgen=true (new CadenceMixinPlugin).
- **No-shader mode** (the player's usual setting) profiled too: CPU-bound at 160-430 fps, render thread ~75 MB/s of
  garbage. Fixed (exact): particles' light lookup reuses one mutable BlockPos (700 MB/2.5 min); each section's
  per-frame instance record reused when unchanged (593 → 14 MB); translucent draw records reused per section (two
  slots, main/shadow); the loader's entity tag and EntityCull's isEntityVisible hook no longer box arguments
  (@WrapMethod → head/return injections, @ModifyVariable + @ModifyReturnValue; ~1 GB). Interleaved A/B (frame time
  from the fps counter when there's no pack): EntityCull -0.31 ms/frame in the busy scene, neutral elsewhere.
- **ChunkHeapCompactor crash** (found by the regression run): it copied Allocation.getSize() bytes, the TLSF block
  size, which past the data's aligned start can run 32 bytes beyond the heap end. Now blockSize - pad (the allocator's
  own rule: allocate(s, align) reserves alignUp(s + pad, 32) with the data at most pad bytes in), with bounds checks
  that abandon the evacuation instead. Regression route passes; 846 meshes (88 MB) moved during the glide.

## Day 4 (10-13): tier 1-2 of the round-2 plan (user: "be transparent with auto settings", "no forced frame cap")
- **World Resolution without shaders** (WorldScale): world + hand drawn into scaled targets swapped in around
  GameRenderer.renderLevel, upscaled before the GUI (HUD/menus/text full resolution). Measured on the M2 at RD 16:
  100% 2.3 ms GPU; 75% MetalFX 4.6 ms; 50% MetalFX 4.2, Smooth 4.0, Fast (new bilinear) 2.6 ms — the upscale to
  2940x1846 costs more than the smaller world saves when vanilla's GPU work is this small. Auto starts at 100% without
  shaders and only lowers when GPU-bound; the heat ceiling applies only with a pack.
- **Heat Control** (native NSProcessInfo thermalState/LPM): warm or Low Power Mode -> fps held at the display rate;
  hot -> shadows every 2nd/3rd frame, Auto ceiling 85/70% (pack). Hysteresis 60 s. Toast + Video Settings status.
  The Mac really was "fair" during this morning's runs (fps held at 60, then 120 when the main display was 120 Hz).
- **Memory guard** (dispatch memory-pressure source): purge, tighter heap soft max, eager heap compaction.
- **Thread roles**: render thread user-interactive, server user-initiated. Workers measured: default 15.9/16 chunks
  ahead on a glide, utility 10.5, user-initiated 12.2 + stutter -> left at default.
- **Settings UI**: Performance preset (Battery Saver 40 fps Auto / Balanced / Max Quality / Custom), Heat Control,
  status lines of every automatic adjustment, framerate tip, every button renamed + plain tooltip.
- Frame-end depth discard (mcopt flag) A/B: no measurable gain with or without shaders -> stays off.
- Startup ~10 s to title; DFU build 0.34 s on a background thread -> lazy DFU not worth its risk. AOT cache needs
  launcher flags and Knot classes aren't cached -> not pursued.
- **Leaving a world kept the old world in memory**: ShadowProxies (static instance -> ClientLevel), FrameUniforms
  .previousLevel, the extraction candidate cache, and vanilla itself (it only flags its render data for reset and
  does it when the next world draws). WorldClosed (updateLevelInEngines(null)) releases all of it and runs vanilla's
  resetLevelRenderData now: title screen with BSL 678 -> 414 MB Metal, heap 328 -> 248 MB live; no LevelChunk/mesh
  left. Rejoin tested (with and without shaders). Found a crash on rejoin: FaceCull emitted no draw when every face
  turned away (the light direction on the first shadow frame) -> vanilla's writer getLast() on an empty group list;
  now an empty draw is emitted.
- **32 MB chunk heaps without shaders too**: 416 -> 264 MB reserved (Metal 689 -> 539 MB), fps within noise.
- **Pack check ("Pack Speed-ups" button)**: shadow receiver culling (-5..-7 ms with BSL) was pinned to BSL 10.1.8's
  fingerprint, overworld only. Now any pack can be checked on the player's machine: Video Settings (game paused),
  camera turned through 6 directions; per direction the pack's final picture (before upscale/GUI) is drawn three
  times from a cleared history with a frozen clock (frameTimeCounter/frameCounter restarted, shadows every frame,
  Auto scale held, far proxies built at once, animated textures held: water kept animating while paused and was the
  main run-to-run difference) — culling off, on, off — read back and compared. Verdict against the pack's own noise
  (the two 'off' pictures; BSL 0-41 px of 8.1 M): SAFE if 'on' ≤ max(2×noise, 0.001%), CAN'T TELL if noise > 0.1%,
  else NOT SAFE. Positive control BSL: SAFE (37 vs 41 px). Negative control (test-only sabotage: no shadows in 'on'):
  NOT SAFE (3.06 M px). Result saved per pack content + options + dimension (config/cadence-pack-checks.properties);
  culling then applies (ShadowRenderer.cullingFor). Limits stated in the tooltip: it tests the views around you.
  Also fixed: unfreezing gave a negative frame time (BSL's custom uniform threw: crash) -> timing restarts.
- **44-byte pack vertices**: PackMidUV as RG16_UINT fractions of 65536 (exact for pixel-aligned block models up to
  16384-wide atlases, within 1/64 texel otherwise). BSL RD 16 same view: chunk geometry 337 MB (64 B FULL) / ~253
  (48 B) / 231 MB (44 B). Screenshot diff vs FULL only on waving plants/leaves (unfrozen animation time).
- Rejected: atlas texture compression (ASTC is lossy on pixel art, needs a runtime encoder, < 50 MB to gain).

## Day 4 (10-13 afternoon): tier 3 evaluated (user: "proceed with the tier 3 objectives") — measured, none shipped
- **Where the time is (JFR, shaders off, RD 16, live-switched off like the player does):** frames 2.2 ms CPU and GPU
  (~440 fps with Heat Control off); the render thread's chunk draw list (extractSectionDrawGroups + FaceCull + the
  per-draw Metal loop) ~37% of it. At the player's 70 fps cap the render thread is idle ~85% of the time.
  JFR gotcha: `jfr print` keeps only 5 frames per stack by default — use `--stack-depth 64` for callers/totals. A 6%
  "pack vertex writer on entity models" in the shaders-off profile was a JFR attribution artifact: a guarded log
  (-Dcadence.debugVertex, reverted) caught no pack-format vertex on the render thread once shaders were off.
- **11 GPU-driven chunk draws (ICB): net loss on the M2, dropped.** tools/icb_bench.m (standalone): Metal indirect
  draws issued one by one cost 0.08 µs CPU each (3000 draws 0.25 ms); a GPU-encoded indirect command buffer brings
  the CPU to 0.025 ms but the GPU to 0.76 vs 0.43 ms (+0.33 ms per 3000 draws; 6000: +0.5 ms). CPU-encoded ICB: no CPU
  gain at all. (Wiring would have been: encode kernel in the pre command buffer, executeCommandsInBuffer in the pass,
  useResource on the index buffer, pipelines with supportIndirectCommandBuffers; not with Hi-Z, whose GPU-written
  instance counts come after the pre buffer.)
- **12 Faster worldgen maths: no worthwhile exact win, dropped.** 26.3 already evaluates noise in batched volumes
  (addToVolume/sampleVolume, float gradients). Worldgen worker samples: noise ~38%, surface rules ~30%, features ~26%;
  hottest leaf GradientNoise.permute 8% = one byte-table load (memory latency, nothing to remove). Ore BitSets are
  already pre-sized. Exact candidates left are 1-2% each (cache ChunkAccess minY instead of dimensionType() per
  Heightmap/isOutsideBuildHeight call) and touch every chunk access: not worth it. Note: most "ServerChunkCache.getChunk
  from BiomeManager" samples are the client's biome-blend tinting during chunk meshing, not generation.
- **13 Tile-shader composite merging: < ~1% for BSL, dropped.** At the player's 0.5 scale a 1470x923 RGBA16F target
  is 10.9 MB; even if every composite pass's output stayed on chip (impossible: BSL's bloom/DOF/TAA/FXAA passes read
  neighbours) the saving is ≤ ~110 MB/frame ≈ 1.1 ms of a 22-30 ms frame; the mergeable subset is a fraction of that.
  (mcopt's encoder-log spans overlap on the tiled GPU — a 1x1 mip pass "takes" 2 ms — so they can't give pass costs.)
- **14 MetalFX temporal: costs more, dropped.** tools/mfx_bench.m (M2, output 2940x1838): spatial 2.37 ms at any
  input scale; temporal 5.4 ms from 50%, 8.1 ms from 75%. BSL also has its own TAA (MetalFX would get an already
  resolved, un-jittered image: no new detail, only ghosting risk). Without shaders World Resolution itself doesn't
  pay on the M2 (vanilla RD 16 = 2.3 ms at 100%), so temporal there is slower still.

## Night (10-13 evening, user: "overnight performance and testing cycle", one Haiku research agent)
- **Research** (../research/ROUND3.md, Haiku, 2 passes): first pass thin (listing pages); second pass read Lithium's 170
  options, FerriteCore summary.md (MIT), ImmediatelyFast (LGPL-3), Sodium's LevelSlice/LevelColorCache/AO ideas
  (Polyform Shield: ideas only). Taken: the Sodium-style meshing ideas, re-done Cadence's own way (below). Not taken:
  FerriteCore (26.3's StateHolder already stores keys/values/neighbours in arrays; the live heap's models are ~10 MB),
  Lithium ports (the player will install Lithium itself), ImmediatelyFast GL paths (not Metal), SightCull/More Culling
  (ray-based or GPL, not exact).
- **Meshing caches (exact, default on, all switchable):** profile of client meshing (shaders off, exploring): AO 30%,
  block-state palette reads 18%, face culling 16%, light 11%, biome tint 9%.
  - MeshStateCache (RenderSectionRegion: block states of the section + 1-block border, 18³, memo on first read; the
    region is an immutable copy) — A/B per-section worker time 2883 -> 2474 µs (-14%).
  - MeshLightCache (BlockModelLighter.Cache: flat 18³ light + shade arrays tagged per build instead of vanilla's
    100-entry linked hash maps that evict and recompute) — -11%.
  - MeshTintCache (ClientLevel.calculateBlockTint: the blend square's getBiome once per position per build, window
    section ±8 horizontally for blend radius up to 7) — biome tint had become 18% of meshing; -23%.
  - Together ~2.9 ms -> ~1.8 ms per section (-38%). Verified exact: -Dcadence.meshCacheVerify re-reads every cached
    value: 693M block/biome reads 0 mismatches (BSL + off + Nether route); light: 483 of 133M differed because light
    was still updating mid-build (vanilla's own cache has the same race; the section is re-meshed when light settles).
- Measured, nothing to fix: re-meshing while exploring (95% of sections meshed exactly once); Unsafe.beforeMemoryAccess
  (4% "self" in JFR is the inlined store itself: a micro-bench is identical with --sun-misc-unsafe-memory-access=allow).
- Self-test steps added: meshcache= / meshlight= / meshtint= (report the period before switching), meshreport=LABEL
  (worker ms per section + how many times each section was meshed), wait=SECONDS (soaks keep a glide running).
- **Retained region copies (vanilla):** RenderSection.lastCompileTask keeps the finished CompileTask and its
  RenderSectionRegion (27 section copies, ~70 KB) until the section is compiled again. CompileTaskReleaseMixin drops the
  region when doTask returns (only doTask reads it). 5-min spectator flight, live heap after GC: 842 MB -> 538 MB (181 MB
  of it was the new mesh-state arrays riding on those regions — caught by the soak's histogram before shipping; they're
  also dropped at build end now; ~120 MB is vanilla's own retention). -Dcadence.releaseRegions=false: vanilla.
- High spectator view, shaders off: GPU 7-8 ms at 100%, 10.7 at 75%, 8.3 at 50% — geometry-bound, not pixels; the
  remedies (quantized vertex positions, far LOD) aren't exact: left for the player to decide.
- 15-min spectator soak (8 blocks/s): 16/16 chunks ahead throughout, Metal peak 645 -> 514 MB after, no crash.
  Final regression (BSL/LOW/default/F3+A/off/scale/Nether/RD/spectator glide/leave+join) passed.
- INSTALLED 19:31 into Cadence Test (shaders jar only; backup mods-backup-1013-1931).
- Mesh caches with BSL (same alternating F3+A A/B): 1382/1154/1302 -> 844/919/786 µs per section (~-33%).
  Blend radius 7 and 0: exact (161M / 81M checks, 0 mismatches).
- FaceCull packed groups (one int[] per mesh instead of map -> EnumMap table -> FaceGroups -> start[]/plane[]):
  exact (36.7M draws verified), interleaved ab=facepacked: draw list 1461 -> 1361 and 1514 -> 1438 µs/frame (-6%).
  While exploring shaders off: render thread ~32% of game CPU, draw-list building ~54% of it; vanilla's per-section
  UberGpuBuffer.getAllocation HashMap is ~25% of the draw list — a cache on the mesh was judged not worth the risk of a
  stale allocation (~0.15 ms/frame).
- Tried and reverted: splitting the loader's per-vertex addVertex inject so its CallbackInfo is scalar-replaced (the
  9.5% "handler self" with many mobs moved into ModelPart.compile instead: it was the vertex writes, not allocation).
- **In Background** (BackgroundFps, Video Settings button, default on): at most 15 fps while another app is active
  (vanilla throttles only when minimized/exclusive fullscreen, or after 60 s AFK to 30 fps). Measured 15.0 fps both with
  and without a pack; Auto resolution holds while capped (15-fps frames would read as "slow" with a pack). Pack
  Speed-ups label now "No World" / "No Pack" (was a cut-off "(load a world)").
- Tests: edge run (F3+T reloads with/without BSL, the End, pack switch there) passed, 232M cached reads exact; leave/join
  x5 off + x3 BSL: Metal flat (408-414 / 714-722 MB), heap after GC back to start (491 -> 494 MB).
- Finding for the player: BSL at RD 16 seen from high up (elytra/spectator 60 blocks over terrain, 8 b/s) grows chunk
  geometry fast (Metal 736 -> 1198 MB in ~2 min; 2802 underground sections on screen via cave openings) and pushed the
  8 GB Mac into swap before macOS signalled memory pressure (the guard stopped it). Not from tonight's changes.
- One-off: the first shaders-off frame after a resource reload stalled ~1 s (vanilla pipelines recompiled once).
- INSTALLED 21:09 (backup mods-backup-1013-2109).
- Hi-Z for the no-shader terrain pass: measured first (OcclusionEstimate, rays): ground level 61% of quads hidden but the
  whole GPU frame is 3.1 ms; 120 up looking 30° down only 29% hidden. One mid-pass split stores+reloads colour and depth
  at 2940x1686 (~80 MB, ~0.8 ms): net ~0. Not built. (mcopt's split machinery exists: MetalTerrain, Sodium-only, off.)
- Tried and reverted: bulk-decoding the meshed section's 4096 states (BitStorage.unpack) — Mixin @Accessor can't type
  PalettedContainer.data (package-private record) as Object (InvalidAccessorException at startup).
- **Hi-Z for the no-shader pass, built and measured (off by default, -Dcadence.hizVanilla=true / ab=hizvanilla):**
  the 30-min soak showed GPU 12-14 ms/frame in open views at the 70 cap (hidden cave geometry 3-5x the surface), so
  it was built after all: phase 1 set up in the pre command buffer (mc_cull_a_pre), one split of the vanilla pass
  (mc_cull_vsplit = mc_render_suspend + pyramid on its compute encoder; phase 2 on it; mc_cull_vresume + restorePass),
  reversed depth (26.x clears depth to 0; kernels take p.frac.w bit 0 reversed / bit 1 z 0..1, min-pyramid pyr0r/pyrNr),
  the projection captured as uploaded (HiZProjectionMixin: bobbing/nausea included). Exact: screenshots off/on differ
  only at moving cloud edges. Interleaved A/B (uncapped, -Dmcopt.edgeCap=false): ground facing a hill +0.79 ms, turned
  +0.32, 6 up looking across open land -0.72 (8.19 -> 7.48), 60 up looking down -0.14. The split stores and reloads
  colour + depth at 2940x1686 (~0.8 ms), eating most of the saving -> off. Pack Hi-Z unchanged (ab=hiz -1.88 ms).
  Gotcha: mcopt EdgeCap (profile) caps at 2x display Hz (120) while terrain generates nearby — pass
  -Dmcopt.edgeCap=false for GPU A/Bs, and GpuBusy reads ~= interval under any cap (waits count as busy).
