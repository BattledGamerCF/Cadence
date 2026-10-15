# Research: Apple Silicon / Metal / Minecraft optimization (for Cadence on M2 Air 8 GB)

Provenance: only a few claims were checked against live pages this session (marked [V] with a URL). Everything else is from background knowledge and is marked "(unverified)" where I am not sure. Licenses especially must be re-checked on GitHub before copying any code. Web search was shallow, so treat this as a map of leads, not a benchmark.

Verified this session:
- [V] Sodium is PolyForm Shield 1.0.0 (https://github.com/CaffeineMC/sodium). That is source-available and not open source; do NOT copy its code into an Apache-2.0 fork. Learn from the ideas only.
- [V] Moonrise is GPL-3.0-only (Modrinth). Cannot be merged into Apache-2.0 code.
- [V] M2 is Apple8 family: Metal 3 and 4, mesh shaders supported (https://developer.apple.com/metal/feature-sets, WWDC22 10162). Query `supportsFamily(.apple8)` and the mesh-shader feature at runtime.
- [V] Load/store action rules (Apple docs, link below).
- [V] JDK 25: `-XX:+UseCompactObjectHeaders` is a product flag (JEP 519, off by default); JEP 514 adds `-XX:AOTCacheOutput` one-step AOT cache creation; JEP 515 is AOT method profiling for warmup; ZGC is generational-only since JDK 24.

---

## 1. Executive summary: top 15 ideas, ranked by gain / (effort + risk)

| # | Idea | Why on Apple GPU/CPU | Gain (M2 Air) | Risk | Exact? | Code source / license |
|---|---|---|---|---|---|---|
| 1 | JVM flags: `-XX:+UseCompactObjectHeaders`, `-Xshare`/AOT cache, `-XX:+UseSerialGC` or G1 with small heap + `-XX:G1PeriodicGCInterval`/`-XX:+ShrinkHeapInSteps`, no AlwaysPreTouch | Less heap = less swap on 8 GB. Compact headers shrink every object 4-8 bytes (BlockState, BakedQuad, chunk data). | Heap -5 to -15%, less GC work; start-up -20-40% with AOT cache | Very low (flag test; compact headers is JDK 25 product but check mods using Unsafe/header assumptions) | Exact | JDK, free |
| 2 | Verify every render pass uses `MTLLoadActionDontCare`/`Clear` and `StoreActionDontCare` where not needed; shadow depth: Store only, color attachments none; scene depth: DontCare store if no later pass samples it; use memoryless for MSAA/depth-only transients | TBDR: Load/Store = full tile memory <-> DRAM traffic per pass. Cheap on bandwidth-starved fanless Air. | 0.5-3 ms per frame, also less heat | Low | Exact | Apple docs |
| 3 | Shadow pass: depth-only pipeline with NO fragment function (or only alpha-test discard for cutout), no color attachment, front-face cull off/on tested, `setDepthBias` in hardware instead of shader math | With no fragment shader, Apple GPU skips the fragment stage entirely (only HSR depth update). Cutout leaves/grass need discard, which disables early-Z/HSR benefits for those draws: draw opaque first, cutout last. | 2-5 ms of the 17 ms | Low-med | Exact (bias tuning aside) | Apple |
| 4 | Shadow map LOD/cull by cascade: skip shadow casters that cannot project onto visible receivers (done for receivers) and add light-space frustum + distance cull for far sections; lower shadow-distance caster range (Iris `shadowDistance` equivalent) | Shadow cost on Apple is vertex/tiler-bound (sections' geometry is processed again). Cutting casters cuts vertex + bin cost linearly. | 3-8 ms | Med (visible pop of far shadows) | Trade-off | Own code |
| 5 | Terrain vertex cost: 48-byte vertex done; try 16-24 byte quantized (positions as `short4`/int per-section local, packed uv via atlas index, packed light/normal/color into 1-2 words, as Sodium's "compact vertex" 20 byte format does) | Apple GPUs vertex-fetch through the memory system; vertex bound workloads gain from bytes per vertex. Also halves GPU memory (~1 GB at RD16 -> 0.4-0.5 GB). | Terrain pass -10-25%, RAM -400-600 MB | Med (shader + mesher changes) | Exact | Idea from Sodium (PolyForm Shield: reimplement, do not copy) |
| 6 | Indexed quads with 32-bit shared index buffer (4 verts + 6 idx) rather than 6 verts; keep idx 16-bit per section if <65k verts | Cuts vertex bytes by 33%; post-transform cache works for indexed. | 5-15% on vertex fetch | Low | Exact | Vanilla does this; verify the Metal path does too |
| 7 | GPU-driven terrain: Indirect Command Buffers (ICB) filled by a compute culling pass over per-section data (frustum + section-graph visibility bits + Hi-Z), one `executeCommandsInBuffer`; or simpler: indirect draw args (`drawIndexedPrimitives(indirectBuffer:)`) | Removes CPU cull + encode time on render thread (CPU matters on thermally limited M2) and enables Hi-Z. ICB is Apple8-supported. | CPU render thread -20-40% terrain time; GPU slightly lower | High (large rewrite, argument-buffer bindings) | Exact | Nvidium (see s3) is the reference idea; license (unverified, check) |
| 8 | Hi-Z / occlusion from previous frame depth for terrain sections (compute reduce + conservative AABB test in the cull kernel), reuse for shadow caster culling | Vanilla section graph is only cave-visibility; Hi-Z removes hills/buildings occlusion. On TBDR, HSR already kills fragments but NOT vertex/bin work, so cull before submission. | 10-30% terrain vertex work in caves/hilly | Med (popping on fast turn if reprojection wrong; use last-frame depth + conservative) | Near-exact | Own, algorithm public (Nvidium/Voxy) |
| 9 | Chunk loading IO/deserialization: bigger region-file read-ahead, avoid DFU on current-version chunks (skip `DataFixer` when `DataVersion` equals current), palette bulk copy, off-thread NBT -> `SerializableChunkData` | Disk is fast (NVMe), the CPU deserialization is the cost, on 4 P cores. | Chunk load -20-40% CPU time | Med | Exact | Moonrise (GPL) and C2ME (MIT, unverified) have similar patches |
| 10 | Thread QoS: set render thread to `QOS_CLASS_USER_INTERACTIVE`, chunk meshing/worldgen workers to `USER_INITIATED`/`UTILITY`, background IO `BACKGROUND` (via FFM `pthread_set_qos_class_self_np`) | macOS schedules by QoS: UI threads on P cores, UTILITY mostly E cores. Mesh workers hogging P cores steal from render thread and worsen thermals. Java threads default to QoS "default"/user-initiated. | Fewer frame spikes; 5-15% sustained | Low-med (too low QoS can starve meshing) | Exact | Apple pthread API |
| 11 | Thermal governor: cap GPU work not per-frame but with dynamic res target tied to `ProcessInfo.thermalState` (nominal/fair/serious/critical) and lower shadow rate (every 3rd frame) before throttling hits | Fanless Air: sustained clock halves after ~2 min; staying at 70% load steadily beats peaking and being throttled. | Smoother sustained fps, avoids 2x drop | Low | Trade-off (quality) | Own |
| 12 | Resource storage: all static terrain buffers `MTLStorageModePrivate` via blit upload from a small shared staging ring; transient render targets `Memoryless`; dynamic buffers `Shared` with `MTLHazardTrackingModeUntracked` + manual fences; mark rarely used textures/buffers purgeable (`setPurgeableState(.volatile)`) | Private enables lossless texture compression and GPU-optimal layout; hazard tracking costs CPU per bind. On unified memory Shared has no copy but forgoes compression. | CPU encode -5-15%, bandwidth lower | Med | Exact | Apple |
| 13 | MTLHeap with aliasing for transient render targets (shadow, post-process, TAA history ping-pong) | Lower resident memory; shadow map and post buffers are not needed simultaneously. `makeAliasable()` | -50-200 MB | Med | Exact | Apple |
| 14 | LOD / far terrain (Distant Horizons-style) so render distance can drop from 16 to ~10-12 for full-detail meshes while keeping the horizon; excludes LOD from shadows | Geometry-bound: far chunks at distance are tiny triangles that cost vertex + tiler time. Fewer chunks also cuts worldgen, memory, IO. | 30-50% fewer full chunks; big | High (huge feature) | Trade-off | Distant Horizons LGPL-3.0 (unverified), Voxy (license unverified, possibly restrictive) |
| 15 | Memory dedup: FerriteCore-style (BlockState neighbor tables, dedup `BlockStateModel` quads, `Property` maps), ModernFix lazy model baking/DFU lazy load/ string dedup, `-XX:+UseStringDeduplication` (G1 only) | 8 GB. FerriteCore claims large reductions of modded packs, much less for vanilla. | Vanilla: -100-300 MB; modded packs more | Low (just install, if compatible with 26.x) | Exact | FerriteCore MIT (unverified, reported), ModernFix LGPL-3.0 (unverified). Both are drop-in mods; no need to copy code |

Notes on ranking: items 1-3, 10, 11 are cheap. 5-8 are the big GPU wins and share infrastructure (compact vertex + ICB + Hi-Z). 14 is the largest conceptual win but costly.

---

## 2. GPU deep notes

### 2.1 TBDR basics that matter here
- Apple GPUs bin primitives per tile after vertex processing, then rasterize each tile with hidden surface removal (HSR) so opaque overdraw does not shade. Consequences:
  - Fragment cost for opaque overdraw is low; do not spend effort on front-to-back sorting for fragments (HSR does it), but order matters for alpha-tested/discard/depth-writing-in-shader draws, which break HSR for that tile (draw them after opaque, in a separate batch). Apple rule of thumb: opaque, then alpha-test, then translucent. (unverified detail, widely stated in WWDC 2019/2020 talks)
  - Vertex shader results are written to a parameter buffer in DRAM before tiling. Many triangles -> big parameter-buffer traffic. That is why "terrain is geometry/vertex-bound" and why shadow passes at high triangle counts are expensive. Reduce triangles and bytes per output vertex (fewer varyings, smaller interpolants: use `half`, `[[flat]]`, packed).
  - Load/Store actions = tile memory <-> DRAM [V]. Use `dontCare` for depth store, `clear` over `load`. See https://developer.apple.com/documentation/metal/setting-load-and-store-actions
  - Memoryless storage: for attachments that never leave the tile (MSAA samples, depth if not sampled later, G-buffers consumed in-pass via programmable blending / framebuffer fetch `[[color(n)]]`). Load/Store cannot be used on them [V].
  - Merge passes: composite/post effects that only read the current pixel can use framebuffer fetch / tile shaders in the same render pass instead of sampling a texture in a new pass (saves a store+load). Applies to shaderpack deferred composites that are per-pixel only; passes needing neighbors (blur, SSAO, bloom) cannot merge. Translating BSL composite chain into merged tile passes is complex (unverified feasibility).
- Avoid encoder splits: each render encoder end = tile flush. Keep the main scene as one encoder; do not read back mid-frame; avoid `waitUntilCompleted`.
- Draw call overhead: Metal CPU cost per draw is low but nonzero; setting buffers/textures per draw dominates. Use argument buffers (Tier 2 on Apple GPUs, supports bindless-ish arrays) to bind the whole texture atlas set and per-section data once, `useResource`/`useHeap` once per encoder. Metal 4 adds explicit residency sets and argument tables (M2 supported with macOS 26) (unverified specifics).
- GPU counters/profiling: Xcode GPU capture and Instruments "Metal System Trace"/"GPU Counters" (occupancy, limiters, vertex/fragment/bandwidth), Metal Performance HUD (`MTL_HUD_ENABLED=1`) for fps and thermals; `MTLCounterSampleBuffer` with stage boundary sampling (on Apple Silicon sample at stage boundaries only, not per-draw) to time shadow vs terrain. Use the "Limiter" counters to confirm vertex vs bandwidth bound before optimizing.

### 2.2 Shadow maps on Apple GPUs
- Depth-only pass: no fragment function, no color attachment, `MTLPixelFormatDepth32Float` (or Depth16Unorm: halves bandwidth; test precision). Store depth, load `clear`. Texture with `MTLTextureUsageRenderTarget|ShaderRead`, storage Private.
- Use `setDepthBias(_:slopeScale:clamp:)` and `setCullMode(.front)` instead of shader bias hacks.
- Layered rendering / cascades: one pass with `[[render_target_array_index]]` renders all cascades but each triangle is replicated per layer in the vertex stage; with vertex-bound workloads separate passes with per-cascade culling are usually cheaper. Cascades with distance-based triangle LOD are better than a single huge map (unverified for this workload; measure).
- Reduced-rate shadow: already every 2nd frame with shift compensation. Extensions: static-cascade caching (far cascade updated every 8 frames when sun moves slowly, near cascade each frame/2), light-space texel snapping to prevent shimmer on updates, per-section "dirty" invalidation. Also render shadow with a lower resolution when thermalState >= fair.
- Shadow casters: a section whose AABB projected along light direction cannot hit the view frustum + receiver range can be dropped (receiver culling done; extend to casters by extruding visible-receiver bounds toward the sun, a standard "shadow frustum fitting"). Iris does a version of this plus configurable shadow distance (Iris is LGPL-3.0, unverified; Sodium dependency not needed for ideas).
- Entities/block-entities into shadow: skip small/far entities; draw entities in shadow with simplified shader.
- Transparent/cutout in shadow: leaves are the big triangle sink (cutout). Option: render leaves into shadow with reduced geometry (only every second leaf quad, or fancy->fast leaves in shadow only) (visual trade-off).

### 2.3 Culling and GPU-driven
- Mesh shaders: supported on M2 (Apple8) [V]. Object+mesh shader pipeline could replace vertex stage for terrain: per-section meshlets with cone/AABB culling on the GPU, no parameter-buffer write for culled triangles. Caveat: Apple's mesh pipeline output also goes through the tiler; gains come from culling. Complexity very high; ICB+compute cull gets most of the benefit (unverified comparison). Reference: https://metalbyexample.com/ (mesh shaders and meshlet culling) and WWDC22 10162.
- ICB: `MTLIndirectCommandBuffer` filled by a compute kernel (`MTLIndirectRenderCommand`), execute with `executeCommandsInBuffer:withRange:`; supports changing buffers/pipeline per command in Apple GPUs. Combine with one big vertex/index heap and per-section offset (base vertex) so a draw = (index count, base vertex).
- Hi-Z: build mip pyramid of last frame's depth (depth must be Stored, a cost; use a half-res depth copy via a cheap compute downsample, or render a low-res occluder depth pass of nearby opaque sections only, as Nvidium/others do). Occlusion queries: Metal offers `MTLVisibilityResultMode.boolean/counting` with a visibility result buffer, but results arrive a frame later and per-draw queries are costly; Hi-Z compute is preferred for GPU-driven, queries for a few big occluders.
- Entities/particles: already using section graph; a Hi-Z test can additionally kill occluded entities (EntityCulling mod uses raycast-based occlusion on CPU threads; licensing unverified).

### 2.4 Vertex-bound techniques
- Quantize: positions relative to section origin as 16-bit fixed (1/2048 block range up to 16*? fits `short`), UV as 16-bit offset into atlas or sprite index + 0/1 corner via `vertex_id & 3` (quads reconstruct corners from vertex id, so only one stored vertex per quad is possible: "quad-per-instance" or `gl_VertexID`-style tricks), color/AO/light into one uint, normal as 3-bit face index. Sodium's chunk vertex is ~20 bytes (unverified exact). Target 16 B/vertex or 4 vertices from 1 quad record (~28 B/quad).
- Alternative: vertex pulling, i.e. quads in a `device` buffer, `drawPrimitives` instanced with 6 vertices/quad and vertex shader reading quad record by `instance_id`; saves memory and index buffer, costs shader ALU (cheap on Apple).
- Primitive restart is irrelevant for quads; use indexed triangle lists with shared index pattern.
- Face culling inside sections: already culled hidden faces by vanilla mesher; ensure section interior face culling and greedy meshing options for large flat areas (water surfaces, stone). Greedy meshing changes UV tiling (needs texture wrap) -> trade-off with atlas.
- Sort translucents: water/glass sort per section on CPU is vanilla's cost; limit re-sort frequency (only when camera crosses a threshold distance), use order-independent approximation for water (draw back-to-front by section only, no intra-section sort) (visual trade-off); Sodium does per-section sort with caching ("translucency sorting" rewrite in 0.5+, unverified details).

### 2.5 MetalFX / resolution
- MetalFX spatial done. MetalFX temporal (needs motion vectors, jittered projection, depth, exposure) gives much better quality at lower render scale (e.g., 50-60% per axis), allowing ~2x pixel reduction; shaderpacks with TAA already produce jitter and may expose motion via previous-frame matrices (BSL has TAA). Replace pack TAA with MetalFX temporal where possible (unverified practicality, high effort). `MTLFXTemporalScaler` requires Apple GPU Apple6+/M1 and later.
- Dynamic resolution: base on GPU frame time from `MTLCommandBuffer.GPUStartTime/GPUEndTime`, plus thermal state.

### 2.6 Memory-side GPU
- ~1 GB geometry at RD16 shows up as wired/compressed pressure on an 8 GB machine. Options: compact vertices (5), LOD (14), evict meshes of far sections behind camera, keep meshes of sections out of frustum purgeable? Purgeable state (`MTLResource.setPurgeableState(.volatile)`) lets the OS drop contents under pressure, but contents are lost (must re-mesh). Useful only for caches that are cheaply rebuilt (shadow history, post buffers, not terrain).
- Sparse textures/heaps: `MTLHeap` placement sparse (`MTLSparseTextureMappingMode`) available on Apple6+; not useful for Minecraft's single atlas.
- Use `MTLHeapType.placement` or automatic heaps, size them to need; "smaller GPU buffer heaps" already done. Sub-allocate section meshes with a best-fit allocator and periodically defragment to avoid heap bloat.

---

## 3. Minecraft mods: techniques and licenses

Licenses below not marked [V] are from memory and must be rechecked. Versions: Fabric mods moved to 1.21.11/26.x lines; check each Modrinth page for 26.x (I could not confirm).

| Mod | Technique | License | Notes for Cadence |
|---|---|---|---|
| Sodium | Rewrites terrain renderer: region/section batching in big GL buffers, compact vertex format, multi-draw indirect, section-graph + frustum cull, async translucency sorting | PolyForm Shield 1.0.0 [V] | Do not copy; use as design reference. Cadence already replaces renderer, so concepts (compact vertex, region buffer, culling) transfer |
| Iris | OptiFine shader pack loader; shadow pass culling (frustum fitting to view), configurable shadow distance, shadow entity culling | LGPL-3.0 (unverified) + GPL-style parts? check | Cadence already has own pack path; port ideas only |
| Lithium | Game logic: entity collisions, block ticking, pathfinding, chunk access caches, hopper, AI. Pure CPU, no rendering | LGPL-3.0 (unverified) | Compatible target for the integrated server CPU; install as mod where it supports 26.x |
| FerriteCore | Memory: dedups blockstate/model/neighbor tables, property maps | MIT (unverified) | Memory win; vanilla gain moderate |
| ModernFix | Faster startup, lazy DFU init, dynamic model baking (models baked on demand), dedup of strings/ BakedQuads, fixes leaks | LGPL-3.0 (unverified) | Dynamic resources saves a lot with mods, little in vanilla |
| ImmediatelyFast | Batches immediate-mode drawing (HUD, text, block entities) into fewer draws | LGPL-3.0 (unverified) | With a custom backend, equivalent batching of GUI at the renderpearl layer would cut CPU encode |
| EntityCulling | CPU async raytrace occlusion for entities/block entities | License unverified (tr7zw; may be custom) | Cadence did section-graph; ray test adds more |
| Distant Horizons | LOD generated from chunks, own renderer, stores LODs in SQLite | LGPL-3.0 (unverified) | Heavy RAM/CPU; could hurt on 8 GB; LOD in shadows not supported by default (unverified) |
| Voxy | Voxel LOD with GPU-driven rendering (needs modern GPU features, OpenGL/Vulkan) | License unverified (possibly restrictive) | Reference for GPU-driven LOD |
| Nvidium | Uses NVIDIA mesh shaders for terrain with occlusion culling | License unverified | NVIDIA-only; idea: mesh shader + Hi-Z maps to Metal mesh shaders |
| C2ME | Parallel chunk generation/IO/loading, better chunk scheduling | MIT (unverified) | User measured gen is already parallel at 4-5 cores in 26.3; IO/serialization part may still help. Incompatible with Moonrise [V] |
| ScalableLux / Starlight | Rewritten light engine (faster propagation, less memory) | Starlight MIT? ScalableLux LGPL? (unverified) | Light is a CPU hotspot at chunk load; vanilla 26.x light engine may already be improved (unverified) |
| Moonrise | Paper chunk system port (chunk loading, entity lookups, IO, light) | GPL-3.0-only [V] | Cannot merge code; may be installed as a separate mod. Compatible with Lithium/FerriteCore [V] |
| Noisium | Faster noise/worldgen math | MIT? (unverified) | Measured: gen already CPU-bound; small gain |
| VMP | Player-related server scaling | unverified | Singleplayer irrelevant |
| Krypton | Netty/network | unverified | Integrated server has local channel: irrelevant |
| MoreCulling / BadOptimizations | Extra culling (leaves faces, hidden block-entity, miscellaneous micro-opts) | unverified | Cheap CPU wins; ideas such as "leaves culling" reduce triangles (visual change) |
| Dynamic FPS | Lower FPS when window unfocused | unverified | Trivial to add; saves thermals when alt-tabbed |
| Enhanced Block Entities | Bakes chests, signs, beds into chunk meshes | unverified (probably custom) | Reduces block entity draw calls; idea transferable |
| Exordium | Caches GUI rendering to a texture, fewer GUI redraws | unverified | HUD at 1/3 refresh |

Techniques not yet done which look best for Cadence: compact vertex/quad pulling, ICB + compute cull, Hi-Z, GUI/blockentity batching, LOD, translucency sort throttling, chunk IO/DFU bypass.

---

## 4. CPU / OS notes

### Scheduling and QoS
- Apple Silicon M2: 4 P + 4 E cores. macOS schedules threads by QoS: `USER_INTERACTIVE` and `USER_INITIATED` prefer P cores; `UTILITY` and `BACKGROUND` run predominantly on E cores (BACKGROUND is also throttled for CPU/IO/network and can be starved when P cores busy). (https://developer.apple.com/library/archive/documentation/Performance/Conceptual/power_efficiency_guidelines_osx/PrioritizeWorkAtTheTaskLevel.html, unverified fetch)
- A single JVM process: threads inherit process QoS; Java's `Thread.setPriority` is mostly ignored on macOS. Use FFM to call `pthread_set_qos_class_self_np(QOS_CLASS_*, relative_priority)` per thread. Suggested: render/main thread `USER_INTERACTIVE`; Metal command submission thread same; integrated server tick thread `USER_INITIATED`; chunk mesher workers `USER_INITIATED` at lower relative priority; worldgen workers `UTILITY`; IO threads and autosave `BACKGROUND` or `UTILITY`. Be careful: world generation at UTILITY may make chunk pop-in worse; measure (memory note: M2 gen is CPU-bound at 4-5 cores, so pushing it to E cores could slow it).
- Limit worker count to 3-4 (not 7) to keep render thread off contention and to hold power: fewer threads = lower sustained clocks risk (thermal). Fanless Air throttles via power/thermal, so energy-efficient E-core work is "cheaper" in heat than P-core work.
- Thermal: `NSProcessInfo.thermalState` (Nominal/Fair/Serious/Critical), notification `NSProcessInfoThermalStateDidChangeNotification`, `ProcessInfo.isLowPowerModeEnabled`. Back off before Serious (sheds quality gradually).

### Memory pressure
- macOS compresses memory before swapping; swap on an SSD is fast but spikes cause frame hitches. Observe with `vm_stat`, `memory_pressure`, Activity Monitor "Memory Pressure". Unified memory: GPU buffers count against the same 8 GB; Metal "wired" allocations cannot be compressed/swapped (heaps resident while used).
- Reduce native footprint: free Metal buffers promptly (`release`), use `MTLHeap`s; `madvise(MADV_FREE_REUSABLE)` / `MADV_FREE` on freed native regions; Java side: `-XX:+UseG1GC -XX:G1PeriodicGCInterval=...`, `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20` (already "G1 heap trimming"), `-Xmx` ~ 2.5-3 GB max at RD16 (more heap hurts when swapping), `-Xss` default, `-XX:ReservedCodeCacheSize=128m`, `-XX:+UseCompactObjectHeaders`.
- GC choice: G1 on small heaps (<4 GB) is fine; generational ZGC (JDK 24+) has lower pauses but higher footprint overhead and load-barrier CPU cost; on 8 GB / 4 P cores, I'd stay with G1 (or Parallel for throughput) (unverified, benchmark; one third-party ZGC-vs-G1 Minecraft benchmark exists: https://mineguard.pro/pl/blog/zgc-vs-g1gc-minecraft-java-21-benchmark, not checked). Shenandoah generational is product in JDK 25 but untested here.
- `-XX:+AlwaysPreTouch` touches whole heap at start, forcing RSS = Xms and burning compression/swap: harmful on 8 GB; do not use. Large pages not available on macOS Apple Silicon usefully; skip `-XX:+UseLargePages`.
- Start-up: CDS archive of app classes (`-XX:ArchiveClassesAtExit`), or JDK 25 AOT cache `-XX:AOTCacheOutput=cadence.aot` then `-XX:AOTCache=cadence.aot` (JEP 514/515) [V]. Mod loaders with many classes (Fabric transformers) may break the cache; test. Gain: faster launch, less JIT warmup. Does not help steady-state frame time.
- `-XX:+UseStringDeduplication` (G1): small saving, background CPU; low value for vanilla.
- JIT: `-XX:TieredStopAtLevel` lowering hurts steady state; keep default. `-XX:+UseParallelGC` for lowest overhead on tiny heaps is possible but long pauses.

### Minecraft internals (26.x/1.21.x; unverified details)
- Chunk pipeline: `ChunkStatus` / `ChunkPyramid` dependency steps (empty, structure_starts, structure_references, biomes, noise, surface, carvers, features, initialize_light, light, spawn, full). Features and light are the heavy steps. `ThreadedLevelLightEngine` queues light tasks; light work per chunk can be a large fraction of generation and of chunk loading from disk (relight when light data missing/ignored).
- Disk loading: `RegionFileStorage` / `IOWorker` -> NBT -> `DataFixer` (if DataVersion older) -> `SerializableChunkData.parse` -> `ProtoChunk`. Costs: zlib/LZ4 decompress, NBT tree building (object-heavy, GC pressure), palette decode. Options: use LZ4 region compression (vanilla option `region-file-compression`; faster than deflate at larger size), skip DFU when versions match, reuse buffers, parse palettes straight to `PalettedContainer` arrays.
- Rendering: blaze3d "renderpearl" backend is Cadence's own Metal implementation; chunk meshing in `SectionCompiler` on worker threads, results uploaded as `SectionMesh`.

---

## 5. Things that do NOT help / myths

- Parallelizing worldgen features (measured no gain; 26.3 already uses all workers).
- More Java heap: on 8 GB it just causes swapping; do not set -Xmx above ~3 GB.
- `AlwaysPreTouch`, large pages, `-XX:+AggressiveOpts` (removed), exotic GC flags from old Minecraft guides.
- Sorting opaque terrain front-to-back for fragment savings: HSR already removes opaque overdraw on Apple GPUs. Sorting still helps for discard/alpha-test and for cache locality but is low priority.
- Pulling Hi-Z for fragment work: no benefit for fragment shading, only for vertex/binning.
- Mesh shaders as a magic fix: the tiler still handles outputs; gain comes only from culling/compaction. ICB+compute reaches most of it with less risk.
- Krypton/VMP in singleplayer; Starlight/ScalableLux if the 26.x vanilla light engine is already efficient (unverified).
- Lowering `Depth32Float` to 16 for the main pass: precision problems at RD16; shadow map only.
- Purgeable state on terrain buffers: contents lost; only for rebuildable caches.
- Running more chunk workers than P cores: contention with the render thread, more heat.
- Generational ZGC to "fix" stutter: footprint and CPU cost likely outweigh on 8 GB (unverified, measure).
- Distant Horizons on 8 GB: LOD data itself uses RAM/CPU; may net negative.

---

## 6. Suggested order of work
1. Measure first: Metal HUD + GPU counters, `GPUStartTime/EndTime` per pass, vm_stat swap deltas.
2. Cheap: JVM flags (1), load/store audit (2), depth-only shadow pipeline (3), QoS (10), thermal governor (11), purgeable post buffers/heap aliasing (13).
3. Medium: quantized vertices/quad pulling (5,6), shadow caster frustum fitting (4), translucency sort throttling.
4. Large: ICB + compute cull + Hi-Z (7,8), LOD (14).

---

## 7. Sources
- Apple: Setting load and store actions: https://developer.apple.com/documentation/metal/setting-load-and-store-actions
- Apple: Metal feature sets: https://developer.apple.com/metal/feature-sets and Feature Set Tables PDF: https://developer.apple.com/metal/Metal-Feature-Set-Tables.pdf
- Apple: Choosing a resource storage mode for Apple GPUs: https://developer.apple.com/documentation/metal/choosing-a-resource-storage-mode-for-apple-gpus
- WWDC22 10162 Mesh shaders (Metal 3): https://developer.apple.com/videos/play/wwdc2022/10162
- WWDC20 10602 / 10632 (Apple silicon GPU best practices; "Optimize Metal apps and games with GPU counters"): https://developer.apple.com/videos/play/wwdc2020/10632
- WWDC21 10153 (Discover compilation workflows / GPU performance, unverified title): https://developer.apple.com/videos/play/wwdc2021/10153/
- Metal Best Practices Guide (archive): https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/MTLBestPracticesGuide/LoadandStoreActions.html
- Metal by Example, mesh shaders and meshlet culling: https://metalbyexample.com/2023/03/
- Sodium repository (license): https://github.com/CaffeineMC/sodium
- Moonrise (Modrinth): https://modrinth.com/project/KOHu7RCS (listing found by search; unverified id mapping)
- JDK 25 overview: https://inside.java/2025/10/17/new-in-jdk-25-2-mins/ and https://www.infoq.com/news/2025/09/java25-released
- Apple energy guide, QoS: https://developer.apple.com/library/archive/documentation/Performance/Conceptual/power_efficiency_guidelines_osx/PrioritizeWorkAtTheTaskLevel.html (not fetched)
- ZGC vs G1 Minecraft benchmark (third-party): https://mineguard.pro/pl/blog/zgc-vs-g1gc-minecraft-java-21-benchmark (not fetched)
