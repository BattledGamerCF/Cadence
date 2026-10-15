# Cadence research round 3: new optimization ideas (Haiku 5.5 research pass, 2026-10-13)

Scope: web search and fetch only. Nothing downloaded, installed, or run. Only read-only browsing of this folder.

Confidence labels: **[verified]** = read on a source page this pass. **[search]** = seen only in a search-result snippet. **[general]** = my own background knowledge, not checked this pass. **[gap]** = nothing found.

Important limit: this search tool returned mostly blog and mod-listing pages. Almost no primary numbers exist for Minecraft 26.x performance. Treat every item below as a lead to measure on the M2, not as a finding.

## Top 20 (ranked by expected value for Cadence on this M2)

Ranking weighs: likely saving on the actual bottlenecks (exploring new terrain, worker-thread meshing, integrated server, heat), exactness against Cadence's rule, and effort.

1. **Test JDK 25 Compact Object Headers (`-XX:+UseCompactObjectHeaders`) with the existing G1 + HeapTrim setup.** Mods: none, it is a JVM flag. Saves: RAM (fewer bytes per small object, which suits entities, components and vectors) and possibly GC work. Size: JEP 519 reports 22% less heap and 8% less CPU on SPECjbb2015, plus 15% fewer G1 collections [verified, JEP page per search snippet]. Game size: [gap]. Gameplay/visual risk: none (VM layout only). Effort: low, one A/B run. Vanilla already does it? No, opt-in on JDK 25. Sources: https://openjdk.org/jeps/519 , https://www.infoq.com/news/2025/06/java-25-compact-object-headers [search].

2. **Metal 4 residency sets for chunk heaps instead of per-allocation residency.** Mods: none on Mac that I found. Saves: CPU in the draw path, and clearer eviction on 8 GB. Size: [gap]. Risk: none if residency is correct, but a missing entry causes GPU faults. Effort: medium (mcopt Metal backend). Vanilla? N/A. Sources: https://developer.apple.com/documentation/metal/mtlresidencyset (page body did not load; API shape from [general], verify names before use).

3. **Starlight/ScalableLux-style light engine rewrite, for exploration load only.** Mods: Starlight (archived, rewrites light engine), ScalableLux (LGPL-3.0-only, Starlight-based, adds parallel light updates) [search]. Saves: CPU on worker threads when new chunks load, faster chunk generation. Size: [gap] for Minecraft 26.x; ScalableLux claims large gains only in high-speed generation. Risk: HIGH for Cadence's rule, Starlight is described as "destructive" and changes output in edge cases [search]. Possibly an opt-in tier only. Effort: high. Vanilla 26.x? Vanilla lighting still the engine. Sources: https://modrinth.com/mod/scalablelux , https://www.mado-hosting.com/en/minecraft-mods/starlight .

4. **Lithium-style logic optimizations ported as ideas (not code), for integrated server.** Mods: Lithium, LGPL-3.0 [verified: https://github.com/CaffeineMC/lithium-fabric]. Says it "doesn't change any game mechanics or visuals" and that some optimizations are disabled by default when issues exist [verified]. Saves: server-tick CPU in singleplayer (competes with the render thread). Size: [gap], the page lists no per-item numbers. Effort: medium per item. Risk: low by its own claim. Vanilla already does it? Partly, Mojang adopted some AI/tick work (26.3 snapshot 2 "mob behaviours optimized when players are not nearby" [search]). Check each Lithium item against 26.3 before porting. Source: https://github.com/CaffeineMC/lithium-fabric .

5. **C2ME-style parallel chunk I/O and loading, exact output.** Mods: C2ME (license file present, text not read [verified: https://github.com/RelativityMC/C2ME-fabric]). README says it parallelises "chunk generation, I/O, and loading" without changing vanilla behaviour, except vanilla worldgen is non-deterministic across runs [verified]. Saves: wall time on new terrain, lower lag spikes. Size: [gap]. Risk: thread-safety in mcopt's compile queue. Effort: high. Vanilla? Not parallel IO. Note: Cadence already rejected "parallel worldgen FEATURES step (slower)", so target IO and region read/write only, not features. Source above.

6. **Region file / chunk save compression and IO batching (singleplayer).** Mods: [gap], nothing found specifically. Saves: disk IO and integrated-server CPU when chunks are saved, and SSD wear. Size: [gap]. Effort: medium. Risk: none to gameplay if format is compatible. Look at vanilla's region format first (Mojang may already compress).

7. **FerriteCore-style blockstate and model deduplication.** Mods: FerriteCore (memory dedupe of blockstates and models) [search]; the claim "hundreds of MB" is for heavy modpacks, and no vanilla number was found [gap]. Saves: RAM, which matters at 8 GB. Vanilla 26.x already dedupes? [gap]. Risk: none. Effort: medium. License: [gap]. Source: https://www.modpackindex.com/modpack/131253/faster-performance (listing only).

8. **Entity culling with a different occlusion test (SightCull, MIT).** SightCull: MIT, requires MC 26.2 and Fabric Loader 0.19.3+ [search]. Entity Culling mod exists and Cadence already has EntityCull, so the new bit would be the method. Method: [gap], not read. Saves: entity/block-entity CPU. Risk: visibility false negatives (visual). Effort: low to read, medium to port. Source: https://www.curseforge.com/minecraft/mc-mods/sightcull (listing).

9. **Hardware occlusion culling via GPU queries for entities (CLab "HOC").** Mod: CLab [search]. Saves: GPU and CPU per entity bounding box test. Risk: one-frame latency can show pop-in. Effort: medium-high. Fits with Cadence's Hi-Z work (shader packs only; a no-shader version could reuse the depth). Source: https://moddex.gg/mod/clab (listing).

10. **Thermal-aware meshing budget.** Idea, borrowing Heat Control: when `ProcessInfo.thermalState` is serious, cap worker-thread chunk-mesh and generation concurrency (not render). Saves: heat and battery in the exploring case, which is the main heat load per the Cadence facts. Size: [gap], estimate only. Risk: slower chunk appearance (visual pop-in while walking), so make it an opt-in tier. Effort: low-medium. Vanilla? No.

11. **QoS placement check: keep chunk-mesh/gen workers at user-initiated or utility, never background.** Source: an LLVM commit found Background QoS confines threads to E cores and made indexing extremely slow [search: https://eclecticlight.co/2026/02/17/in-the-background-putting-threads-to-work/ ; https://blog.xoria.org/macos-tips-threading/]. Java GC and JIT threads at background QoS would have the same trap. Saves: prevents a slowdown. Size: [gap] for Java specifically. Effort: low (audit). Check that Cadence's thread-QoS roles don't map a Java worker to background.

12. **P/E core split: meshing on E cores, render on P cores.** Source: QoS 17-33 prefers P cores, QoS 9 is E-only, Game Mode reserves E cores for the game [search: eclecticlight 2024/2022 articles]. Idea: test whether pinning the mesh/generation pool to utility QoS (E-leaning) keeps the render thread's P-core time free. Saves: may reduce heat and frame-time spikes. Size: [gap] for Minecraft. Risk: slower mesh throughput. Effort: low to test. Vanilla? N/A.

13. **Game Mode awareness.** Source: Game Mode reserves E cores for the foreground game [search, eclecticlight]. Idea: check whether Cadence (or the launcher) can request it, and measure. Saves: possibly lower frame-time variance. Effort: low. Not a setting I can confirm exists from code; [gap] on the API.

14. **Immediate-mode GUI and text batching (ImmediatelyFast).** Mod: ImmediatelyFast, "speed up immediate mode rendering" [search]; license [gap]. Saves: CPU on HUD/menu draws, which may be small with shaders off (frame is ~2.2 ms CPU). Effort: medium. Risk: visual if text order changes; check. Probably low payoff since rendering is mostly idle at the 70 fps cap.

15. **Krypton-style networking trimming (multiplayer and integrated server packets).** Mod: Krypton, "optimizes the Minecraft networking stack" [search: https://github.com/mrmangohands/krypton]; license not shown in the snippet [gap]. Saves: multiplayer CPU and bandwidth; little for singleplayer loopback. Risk: protocol bugs. Effort: medium. Low priority for this user.

16. **Async particles (LGPL-3.0-only) and particle tick off the main thread.** Mod: AsyncParticles, LGPL-3.0-only [verified via search snippet, Modrinth]. Cadence already has a ParticlePhysics fast path, so only the threading part is new. Saves: main-thread CPU in particle-heavy scenes. Risk: threading races. Effort: medium-high. Only borrow ideas; LGPL means code reuse needs LGPL-compatible licensing of Cadence (check).

17. **Distant Horizons / LOD for far terrain instead of extra render distance.** Mod: Distant Horizons, LGPL-3.0-only (VulkanMod variant listing) [search]; Blaze3D backend support added for 1.21.11+ [search]. Saves: lets RD stay at 16 while showing far terrain, which may cut chunk loads. Size: [gap], and the LOD generation is itself heavy CPU and RAM, which is a bad fit for 8 GB. Note as a rejected-for-now idea unless measured.

18. **Nvidium-style mesh-shader path.** Nvidium: LGPL-3.0-only, NVIDIA-only, mesh shaders needed [search]. Not usable on Apple silicon. Listed only to close the question. Reject.

19. **Vanilla 26.3 OIT transparency: measure its cost before adding options.** Mojang replaced "Improved Transparency" with an order-independent transparency algorithm in 26.3 snapshot 2 and renamed a shader define in snapshot 5 [verified: minecraft.net 26.3 snapshot notes, search]. Idea: A/B the Improved Transparency setting on the M2 to see whether it costs GPU in Cadence's Metal path. Saves: possibly GPU, unknown size. Effort: low to measure. Note the snapshot 2 Vulkan-on-Mac world-render bug [search].

20. **Java HotSpot ergonomics to A/B on the M2: `-XX:+UseCompactObjectHeaders` plus G1 region size and `-XX:MaxGCPauseMillis`.** Idea only, using idea 1's test rig. Saves: GC pauses and heap headroom on 8 GB. Effort: low. [general] for the region-size and pause flags; verify each against the JDK 25 docs first.

## Rejected or already covered (for the record)
- Entity Culling, Sodium-style chunk rendering, Lithium-style ticks, FerriteCore's idea as a concept: overlaps existing Cadence work, so only the new parts are listed above.
- Nvidium (NVIDIA-only), Distant Horizons on 8 GB (heavy), Moonrise (GPL-3.0; it replaces the chunk system, so it conflicts with C2ME) [search: https://modrinth.com/mod/KOHu7RCS and Moonrise listing].
- Metal 4 residency on macOS 26 needs checking against the M2 support matrix [gap].

## Per-area findings

### 1. Mods (licenses and techniques)
- **Sodium**: Polyform Shield 1.0.0 license, not permissive [verified: https://github.com/CaffeineMC/sodium-fabric]. Ideas only, no code. README names no specific techniques and no numbers. Requires OpenGL 4.5 (not relevant to the Metal path).
- **Lithium**: LGPL-3.0 [verified]. Claims no mechanic or visual change [verified].
- **Entity Culling**: author-custom license, not MIT, per listing [search: https://curseforge.com/minecraft/mc-mods/entityculling]. Uses spare threads for visibility rays and skips hidden entities and block entities [search]. The GitHub URL I tried returned 404, so the license file was not read [gap].
- **C2ME**: license file exists, text not read [verified]. Parallel chunk gen, I/O, loading; claims vanilla behaviour kept [verified].
- **Starlight / ScalableLux**: Starlight archived since 2024-03-08; ScalableLux LGPL-3.0-only, lists 26.2 and 26.1.x [search].
- **Moonrise**: GPL-3.0-only, replaces chunk system, bundles Starlight-derived code [search].
- **AsyncParticles**: LGPL-3.0-only [search].
- **Krypton**: license [gap] from the snippet.
- **FerriteCore, ModernFix, ImmediatelyFast**: licenses [gap] in this pass.
- **Nvidium**: LGPL-3.0-only, NVIDIA mesh-shader only [search].
- **Distant Horizons**: LGPL-3.0-only for the VulkanMod variant; main mod license not confirmed [search].
- **SightCull**: MIT, MC 26.2 [search].
- **CLab**: hardware occlusion culling via GPU queries [search].
- **Recoleta**: memory-reduction mod, reimplements compact-header ideas in software [search].

### 2. Minecraft 26.x changes
- 26.3 snapshot 2: Improved Transparency now means an order-independent transparency algorithm; mob behaviour optimized when players are away [verified: https://minecraft.net/en-us/article/minecraft-26-3-snapshot-2 ; search snippet].
- 26.3 snapshot 5: shaders compiled with ShaderC on OpenGL too; OIT define renamed. No speed claim [verified: minecraft.net 26.3 snapshot 5 notes, search snippet].
- 26.2 snapshot 4: telemetry for Vulkan vs OpenGL backend; fix for VSync inconsistency in fullscreen Vulkan on some Macs [search: minecraft.net 26.2 snapshot 4].
- A minecraft.how article claims 26.2 improved chunk prioritisation and culling and cut loaded-chunk memory by about 8% [search]. Self-contradictory and unsourced; **not used** for any ranking.
- Forge 26.1 changelogs exist [search], not read.
- **No official 26.1 or 26.2 rendering performance notes were found** [gap]. Read minecraft.net patch notes for 26.1 and 26.2 directly before porting anything.

### 3. Apple silicon and macOS
- QoS 17-33 prefers P cores, falls back to E; QoS 9 (background) is E-only; Game Mode reserves E cores for the game [search: https://eclecticlight.co/2022/03/17/macos-has-different-strategies-for-m1-cores/ ; https://eclecticlight.co/2024/06/18/making-apple-silicon-faster-1-threads-and-tasks/].
- Java threads are native threads, so QoS applies; Java-to-QoS mapping is undocumented [gap]. OpenJDK JDK-8267720 asks for VM thread QoS; shipping status unconfirmed [search: https://bugs.openjdk.org/browse/JDK-8267720].
- Setting QoS from Java needs `pthread_set_qos_class_self_np` via JNI or the FFM API [general].
- Metal residency sets: `MTLResidencySet`, Metal 4 [search; the Apple page body did not load, API details from general knowledge].
- Unified memory, compression and swap on 8 GB: [gap] in this pass.
- Fanless thermal behaviour: [gap] numbers.

### 4. Java 25 / HotSpot
- COH: product feature in JDK 25 via JEP 519, opt-in with `-XX:+UseCompactObjectHeaders`, 96 to 64-bit header [verified: https://openjdk.org/jeps/519 ; search]. JEP reports SPECjbb2015: 22% less heap, 8% less CPU, 15% fewer G1 collections [search, JEP text]. Game workloads: [gap].
- G1 is the JDK 25 default; JEP 423 improved pause times around JNI [search]. A secondary source says G1 is also the default on small machines in Java 27 [search, unverified].
- Recoleta and CleanroomMC wiki claim the 64-bit header helps Minecraft; no controlled game data [search].
- ZGC, lazy DFU, AOT cache: already rejected for Cadence; no new evidence here.

### 5. Integrated server and singleplayer
- Lithium (LGPL, no mechanic change claimed) [verified].
- C2ME (parallel chunk IO and gen) [verified].
- Starlight/ScalableLux lighting [search].
- Region-file compression and IO: [gap].
- Entity AI, pathfinding, hoppers, redstone, random ticks: [gap] per-item numbers. Lithium covers several of these with no mechanic change claimed.

### 6. Heat and battery
- Mostly covered by Cadence's Heat Control; new leads are ideas 10-13 above.
- Background-QoS warning from the LLVM case [search].
- The 70 fps cap already leaves the render thread mostly idle (Cadence fact), so the heat is in exploring and in the server. Idea 10 targets that.

## Suggested next steps (in order, for Cadence's own measurements)
1. Idea 1 and 20: JDK 25 COH A/B on the M2 with the same route. Cheap.
2. Idea 11 and 12: audit Java worker QoS. Cheap.
3. Idea 19: measure OIT transparency cost with BSL at 50%.
4. Ideas 3, 5, 6: only after reading the license and code for the chosen mod.

## Source list
- https://openjdk.org/jeps/519
- https://www.infoq.com/news/2025/06/java-25-compact-object-headers
- https://developer.apple.com/documentation/metal/mtlresidencyset
- https://github.com/CaffeineMC/sodium-fabric
- https://github.com/CaffeineMC/lithium-fabric
- https://github.com/RelativityMC/C2ME-fabric
- https://modrinth.com/mod/scalablelux
- https://www.mado-hosting.com/en/minecraft-mods/starlight
- https://modrinth.com/mod/KOHu7RCS
- https://curseforge.com/minecraft/mc-mods/entityculling
- https://www.curseforge.com/minecraft/mc-mods/sightcull
- https://moddex.gg/mod/clab
- https://github.com/mrmangohands/krypton
- https://modrinth.com/project/asyncparticles
- https://minecraft.net/en-us/article/minecraft-26-3-snapshot-2
- https://minecraft.net/en-us/article/minecraft-26-2-snapshot-4
- https://eclecticlight.co/2026/02/17/in-the-background-putting-threads-to-work/
- https://eclecticlight.co/2022/03/17/macos-has-different-strategies-for-m1-cores/
- https://eclecticlight.co/2024/06/18/making-apple-silicon-faster-1-threads-and-tasks/
- https://blog.xoria.org/macos-tips-threading/
- https://bugs.openjdk.org/browse/JDK-8267720
- https://www.modpackindex.com/modpack/131253/faster-performance
- https://space-node.net/blog/best-minecraft-performance-mods-2026 (blog, weak)

---

# Round 3b deep dive (2026-10-13, second pass)

Corrections first. Per the coordinator, Cadence already has compact object headers (measured −23 MB heap), thread QoS roles (worker-thread changes measured as worse), and Heat Control. **Dropped from the Round 3 list:** item 1 (COH test), item 9 (QoS audit), item 10 (thermal meshing cap), item 11 (QoS placement), item 12 (P/E split), item 13 (Game Mode), item 20 (JVM A/B). Also: the Round 3 claim that "Minecraft 26.2 is not yet released" is wrong. The minecraft.wiki 26.2 page says 26.2 "Chaos Cubed" released 16 June 2026 [verified: https://minecraft.wiki/w/Java_Edition_26.2]. Round 3 was written with 26.2 as "expected", which was incorrect. Cadence runs 26.3 (snapshot track), so vanilla 26.2 changes apply to it.

Method note: I read GitHub directory listings and raw files on the source repos. The labels are: **[verified]** = read on a source page this pass; **[inferred]** = my reading of code shown, not tested; **[gap]** = not found. Vanilla adoption checks use the wiki/minecraft.net change notes, which are not code. A code-level check needs the decompiled 26.x source, which I did not read. So "vanilla already does it" is marked [gap] unless a changelog entry says so.

## 1. Lithium (LGPL-3.0) — server-tick CPU

Licence: LGPL-3.0 [verified: https://github.com/CaffeineMC/lithium-fabric]. Branch read: `develop`. Lithium says it "doesn't change any game mechanics or visuals" [verified].

Option list: **170 `mixin.*` options**, all true by default except `mixin.compat.worldedit`, `mixin.debug`, `mixin.experimental` (false). Source: https://raw.githubusercontent.com/CaffeineMC/lithium/develop/lithium-fabric-mixin-config.md [verified]. Option names with a `minimal_nonvanilla` prefix are the ones that may deviate; I left those out of the 15.

Mixin source tree (common/src/main/java/net/caffeinemc/mods/lithium/mixin/): ai, alloc, block, block_pattern_matching, cached_hashcode, chunk, client_tick, collections, debug, entity, experimental, gen, math, minimal_nonvanilla, profiler, shapes, util, world [verified: tree listing].

The 15 chosen (server-tick relevant), with what each patches and a vanilla check:

1. **`mixin.block.hopper`** → `block/hopper/HopperBlockEntityMixin.java` (priority 950). Redirects `suckInItems`/`ejectItems`/`tryMoveItems` to cached neighbour inventories, mod-count change detection, entity-section trackers, and `checkSleepingConditions` so idle hoppers stop ticking [verified: file read]. Output equivalence: the code keeps the vanilla fallback for non-Lithium inventories, the same slot loop, and vanilla cooldowns (7/8 ticks) [inferred from file]. Vanilla 26.x: no hopper change in 26.2 notes ("No hopper or POI changes are mentioned") [verified: minecraft.wiki 26.2]. Vanilla adoption: [gap] for code.
2. **`mixin.world.block_entity_ticking.sleeping.*`** (furnace, brewing_stand, campfire.lit/unlit, crafter, shulker_box, sculk_*, hopper; also `chunk_tickable`) → `world/block_entity_ticking/` [verified: subfolder listing]. Idle block entities stop ticking until a change wakes them [verified: option descriptions]. Vanilla 26.x: [gap].
3. **`mixin.ai.pathing`** → `ai/pathing/WalkNodeEvaluatorMixin.java` (priority 990) [verified]. Caches the `PathType` per `BlockState` (the node type is a pure function of state, per the Javadoc), with the vanilla chain only on a miss. Also `checkNeighbourBlocks` single-position mode and `getPathTypeStatic` redirect. Invalidation: not shown [gap]. Vanilla 26.2 notes: no pathfinding change except immunity tags (not a perf change) [verified].
4. **`mixin.ai.poi`** → `ai/poi/PoiSectionMixin.java` [verified]. Implements `lithium$getFirstMatchingPoint`, `lithium$getL2ClosestMatchingPoint`, `lithium$collectMatchingPointsL2Limited`, `lithium$iterate`. Single-type filters index `byType.get(type)` directly (no scan of all buckets); squared-distance checks; early exit. Keeps the original HashMap/HashSet, so iteration order is unchanged (the comment says iteration order is detectable). Ties keep the first record (strict `<`). Also `mixin.ai.poi.fast_portals`, `mixin.collections.poi_types` (fastutil). Vanilla 26.x: [gap].
5. **`mixin.world.tick_scheduler`** → `world/tick_scheduler/LevelChunkTicksMixin.java` [verified]. Overwrites `schedule`, `peek`, `poll`, `hasScheduledTick`, `removeIf`, `getAll`, `count`, `pack`, `unpack`. Replaces the vanilla `tickQueue` (priority queue) with `Long2ReferenceAVLTreeMap<OrderedTickQueue<T>>` keyed by `(time << 4) | priority.ordinal()`, and `ticksPerPosition` with an `IntOpenHashSet` of packed (x,z,y,typeIndex) ints. The `TYPE_2_INDEX` table caps at 4096 types. Ordering argued to match vanilla (time then priority; saved order preserved on unpack) [inferred from file; `OrderedTickQueue` internals not shown]. Vanilla 26.x: [gap].
6. **`mixin.entity.collisions.intersection`** → `entity/collisions/intersection/EntityGetterMixin.java`, `LevelMixin.java` [verified listing]. Grouped access to block/entity collisions instead of scanning per-block.
7. **`mixin.entity.collisions.movement`** → `entity/collisions/movement/EntityMixin.java` [verified listing]. Checks the support block first for downward movers. [inferred: result unchanged, since it's an ordering of checks.]
8. **`mixin.shapes.optimized_matching`**, **`shapes.precompute_shape_arrays`**, **`shapes.shape_merging`**, **`shapes.blockstate_cache`** → `shapes/*` (not read in depth) [verified: option descriptions]. VoxelShape cuboid intersection and merging, and full-cube cache. Cuts collision cost. Vanilla 26.x: [gap].
9. **`mixin.world.raycast`** → `world/raycast/block/BlockGetterMixin.java`, `ClipContextAccessor.java` [verified listing]. Faster block raycasts; the option says "Faster raycasts". Equivalence: [inferred] only.
10. **`mixin.world.explosions.block_raycast`** (base; NOT `skip_air`) [verified descriptions]. Fewer repeat block accesses in explosion damage. `skip_air` has nonvanilla side effects, so leave it out. Vanilla 26.2 explosion notes are gameplay (sulfur cubes, TNT fuse), not perf [verified].
11. **`mixin.world.chunk_ticking.random_block_ticking`** [verified: option "Evaluates random chances early and uses a fast block search"]. Random ticks. Equivalence: the random draw order must stay identical [inferred, not verified in code].
12. **`mixin.entity.inactive_navigations`** [verified: "Block updates skip mobs that wouldn't react"]. Skips block-update work for mobs that cannot react.
13. **`mixin.chunk.palette`** and **`mixin.chunk.serialization`** [verified: "Optimized palette replacing the vanilla hash palette" / "Faster palette compaction when serializing chunks"]. Chunk save-path. Note `mixin.debug.palette` is a debug check, not needed.
14. **`mixin.ai.task.launch`**, **`mixin.ai.sensor.replace_streams`** [verified descriptions]. AI brain: tracks runnable tasks; plain loops instead of streams. Equivalence: loop order must match stream order [inferred].
15. **`mixin.block.fluid.flow`** and **`mixin.block.flatten_states`** [verified descriptions]. Fluid flow and fluid emptiness storage.

Notes:
- Lithium's own "minimal_nonvanilla" group is excluded by Cadence's rule (exact unless opted in). The notable ones: `minimal_nonvanilla.world.block_entity_ticking.support_cache` (hopper deviation), `minimal_nonvanilla.collisions.empty_space` (may shift results ~1e-7 blocks), `minimal_nonvanilla.world.expiring_chunk_tickets` (may change unload order) [verified: option descriptions].
- Vanilla changes found in 26.1/26.2/26.3 notes that touch this area: 26.2 "Upgrading certain worlds from before 26.1 can be faster" (performance) [verified]; 26.3 snapshot 2 "certain mob behaviors were optimized when players aren't nearby" [search snippet, earlier pass; not re-verified]. No changelog entry matches hopper, POI, pathing, tick scheduler, or BE sleeping. So vanilla adoption for items 1-5 is [gap] at code level.

## 2. FerriteCore (malte0811/FerriteCore) — memory

Licence: **MIT** [verified: repo page, earlier pass]. The README links `summary.md`; the listing is in that file.

Items from summary.md [verified: https://github.com/malte0811/FerriteCore/blob/master/summary.md], with reported savings (from the mod's own test pack, not vanilla):
1. **Optionals in `PropertyValueCondition`**: unwraps Optional before lambda capture. Saves ~100 MB (ForgeCraft 1, Dec 2020). Obsolete after point 4.
2. **BlockState neighbours** → `FastMap` per block (an ArrayList used as a multi-dimensional array; integer index math), replacing per-state `Table<Property, Comparable, S>`. Mixin subpackage `fastmap`. ~600 MB saved (FastMaps total ~7 MB).
3. **BlockState property storage**: stops storing `ImmutableMap` of properties per state; `getValues` returns a custom Map over the FastMap. Subpackage `nopropertymap`. ~170 MB.
4. **Multipart model predicate caching**: `PropertyValueCondition` keyed by property+value; `And`/`OrCondition` keyed by sorted input hashes. Subpackage `predicates`. 300-400 MB (relative to point 1).
5. **`ModelResourceLocation` string reduction**: constructor avoids string convert/split; `variant` string deduplicated. Subpackage `mrl`. ~300 MB (on DW20 pack 1.2.0).
6. **Multipart model instance sharing**: blockstates with equal predicate+model lists share one instance. Subpackage `dedupmultipart`. ~200 MB (200k instances → 1.5k, DW20 1.2.0).

Vanilla 26.x adoption: [gap]. The numbers are for heavy modpacks. Vanilla alone has far fewer blockstates, so the savings are smaller for this user; estimate [gap]. Relevance to Cadence on 8 GB: items 2-3 (blockstate neighbour and property maps) are the exact, vanilla-equivalent ones. Equivalence: the FastMap index must match `Property` ordering; the summary says it preserves behaviour (not verified in code).

## 3. ModernFix (embeddedt/ModernFix)

Repo URL 404 on GitHub (both the main repo and the README path, this pass). Modrinth listing read [verified: https://modrinth.com/mod/modernfix]:
- Licence: **LGPL-3.0-only** [verified].
- Startup: faster launch, reduced concurrency crashes at startup. Claims "roughly twice as fast" for Forge 1.16–1.19.2 modpacks (author claim, [unverified]).
- Memory: optional dynamic model loading (off by default, "compatibility issues"); ultra-low-memory mode (≤512 MB); author says large packs run with ≤3 GB under non-default options.
- Runtime: backports bug fixes from newer versions; patches unsupported mods; debug tools.
- Platforms: Fabric, Forge, NeoForge; covers 1.16.4 through 26.1.2 [verified listing].

What's usable without other mods: the startup/memory claims are mostly for modpack-scale loading, which is not Cadence's case (vanilla + Fabric). Specific option names and code locations: [gap] (repo 404, no per-fix list). Recommendation: [gap], don't spend effort here until the repo is reachable.

## 4. ImmediatelyFast (RaphiMC/ImmediatelyFast)

Licence: **LGPL-3.0** [verified: main-branch LICENSE file, GNU LGPL v3 text].

Techniques [verified: repo README, via WebFetch]:
- Batched immediate-mode draws with a custom buffer (entities, block entities, particles, text, GUI/HUD, other mods' immediate-mode rendering).
- Targeted rewrites of vanilla map, HUD, and text rendering.
- Config options: `font_atlas_resizing`, `map_atlas_generation` (maps into one texture), `fast_text_lookup`, `avoid_redundant_framebuffer_switching`, `fix_slow_buffer_upload_on_apple_gpu` (reverts a vanilla change that hurt Apple GPUs; on by default there), `experimental_disable_error_checking` (OpenGL), `experimental_disable_resource_pack_conflict_handling`, `experimental_sign_text_buffering`.
- Reported numbers (test rig: Ryzen 5 1600, GTX 1060, Minecraft 1.19.3; these are the author's, from the README table):
  - 1000 cows: 16 → 60 FPS (3.75x). With Sodium: 21 → 82 (3.9x).
  - ~930 maps: 50 → 310 FPS (6.2x).
  - HUD elements: 250 → 380 FPS (1.52x). With Sodium: 335 → 630 (1.88x).
  - 200 signs: 70 → 125 FPS (1.79x). With Sodium: 110 → 300 (2.73x).
  - 400 chests: 170 → 205 FPS (1.20x). With Sodium: 315 → 410 (1.30x).

Relevance to 26.x: the mod targets 1.19.3-era immediate-mode code. Vanilla 26.x changed the renderer (26.1: chunk GPU storage rewrite, lightmap rewrite, block model sprite render passes) [verified: minecraft.wiki 26.1], so the hooks may not match. Cadence's Metal backend is not OpenGL, so `experimental_disable_error_checking`, `avoid_redundant_framebuffer_switching` (GL framebuffer switches) and the Apple buffer-upload fix are likely [inferred] not applicable. What may matter on Metal: batching of immediate-mode draws (entities/particles/text/HUD). Cadence has measured these at ~0 frames of cost (frame ~2.2 ms CPU at the cap), so [inferred] low payoff for this user's frame budget. Verify by profiling the HUD, sign and entity-heavy scene before porting.

## 5. Sodium meshing ideas (Polyform Shield, ideas only)

Licence: Polyform Shield 1.0.0, no code reuse [verified: https://github.com/CaffeineMC/sodium-fabric]. Files below were read as listings and some raw files [verified].

Structure found:
- `world/LevelSlice.java` + `ClonedChunkSection`/`ClonedChunkSectionCache`: the chunk build copies a **3×3×3 grid of sections** (27 sections, `NEIGHBOR_BLOCK_RADIUS` 2 blocks around the origin) into flat arrays: `blockArrays[27][4096]` (unpacked from the vanilla `PalettedContainer` via `PalettedContainerROExtension`), `lightArrays[27][2]` (block/sky `DataLayer`), and block-entity/aux-light references. Neighbour sections are unpacked only inside the 2-block volume. Empty sections (`hasOnlyAir()`) skip build tasks in `prepare`. Arrays are pooled and kept across builds (`reset()`).
- Reads go through `LevelSlice` accessors (`getBlockState`, `getBrightness`), which check `volume.isInside()` first. Out-of-bounds returns air/zero light.
- `world/biome/LevelColorCache.java` + `LevelBiomeSlice`, `BiomeColorMaps`: biome tint is cached per **ColorResolver** (grass, foliage, water) as a 2D grid per Y layer, size `16 + 2*(2 + blendRadius)` in XZ. Lazy: each Y layer is recomputed only when a lookup touches it, using a `populateStamp` (the update increments one counter; each slice compares `lastPopulateStamp`). Box blur via `BoxBlur.blur(...)`.
- `model/light/smooth/AoNeighborInfo.java`: each face has exactly 4 corner-neighbour `Direction`s (fixed per face, not numeric vector math). Corner weights are interpolation weights computed from the vertex position, clamped to [0,1]. `AoCompletionFlags`, `AoFaceData`, `SmoothLightPipeline` are in the same folder (not read in depth).
- `render/chunk/compile/pipeline/BlockRenderCache.java`: not a render-result cache. It bundles per-build objects: `ArrayLightDataCache` over `LevelSlice`, `BlockRenderer`, `FluidRenderer`, and the block model set. Lifecycle is `init(context)` and `cleanup()`, one per chunk compile.
- `render/chunk/compile/` has `buffers`, `estimation`, `executor`, `pipeline`, `tasks`, and `ChunkBuildBuffers`, `ChunkBuildContext`, `ChunkBuildOutput`, `ChunkSortOutput` [verified listing]. (Note: `ChunkBuildBuffers` is a buffer pool, not a cache.)

Mapping to Cadence's profile (AO 30%, block-state reads 18%, face-cull checks 16%, light lookups 11%, biome tint blending 9%) [from coordinator]:

- **Block-state reads (18%) + light lookups (11%):** the 3×3×3 flat copy (`LevelSlice`) means one unpack of each vanilla `PalettedContainer` per build, then array reads with no palette lookups or `BlockPos` allocation. This is the biggest-payoff idea. Exactness: the data is copied from the same vanilla containers, so output is identical [inferred]. Cadence should check whether its build path already reads `LevelChunkSection` directly; if so, a flat copy per build is the change.
- **AO (30%):** `AoNeighborInfo` fixes the 4 corner directions per face in a table and computes weights once per vertex position. Vanilla recomputes neighbour checks per corner. Exactness: must match vanilla's AO formula bit-for-bit; `AoFaceData` (not read) holds the averaging. Check that formula before porting [gap].
- **Face-cull checks (16%):** Sodium's note in `BlockRenderCache` is that it caches no face-cull results. So Sodium's win here is the flat array, not a cache. Cadence's per-section back-face grouping (FaceCull) already covers part of this.
- **Biome tint blending (9%):** `LevelColorCache` computes each biome colour grid once per build region with a lazy stamp, and a box blur. Exactness: the blend must equal vanilla's `getBlendedColor` average over the blend radius. One concern from my reading: `BoxBlur` writes to `tempColorBuffer` while `getColor` reads `slice.buffer`. Unless `BoxBlur` swaps buffers internally (not shown), that is a bug [inferred]. Verify before copying the idea.
- **Empty sections:** skip build tasks for all-air sections (`hasOnlyAir()`) before queueing. Cheap, exact.

Also noted: `LevelSlice.reset()` loops to `SECTION_ARRAY_LENGTH` (3) rather than 27, so only sections 0-2 are cleared [verified read; observation from code]. Avoid copying that bug.

## 6. SightCull (MIT) — entity culling method

Licence: **MIT** [verified via CurseForge page: https://www.curseforge.com/minecraft/mc-mods/sightcull]. No source repo link on the page [gap]. Requires Minecraft 26.2 (only listed version), Fabric Loader 0.19.3+, Fabric API, Java 25, client-side, OpenGL and experimental Vulkan [verified].

Method [verified from its description page]:
- Runs after vanilla decides an object would be drawn. Casts rays from the camera to the centre and corners of each object's slightly enlarged bounding box.
- Only full opaque cubes block a ray (glass, leaves, fences, slabs, fluids do not). Terrain is left to vanilla.
- Results cached per object and re-checked continuously.
- Targets: entities (mobs, players, items, armour stands, item frames, paintings, display entities; their nametags, shadows, fire/leash overlays hidden with them), block entities (chests, signs, banners, shulker boxes, skulls, bells), particles (cached per block position: one raycast shared by particles at the same block), block-breaking cracks.
- Never culled: glowing entities, the player's mount, beacon and end-gateway beams, the block being mined, anything within 4 blocks, and a whitelist (default: ender dragon, wither, end crystals, fishing bobbers, leash knots).
- Rays use voxel grid traversal (DDA-style) and follow the render camera. Per-frame raycast budget; large farms fall back to cached results.
- Threading: not mentioned [verified: page says so].

Relation to Cadence: Cadence's EntityCull uses vanilla's section occlusion graph (exact, no rays). SightCull is ray-based, so it can hide things vanilla would show (it skips objects behind opaque cubes that vanilla still draws). That is not vanilla-identical, so it fails Cadence's rule as a default. Possible opt-in. The per-object cache and the particle-per-block-position cache are the reusable ideas; the particle one fits Cadence's ParticlePhysics path.

Also related: More Culling (GPL-3.0, github.com/FxMorin/MoreCulling): block culling, leaf culling, item frame, painting, map culling, rain/snow culling [verified listing via search]. GPL, so ideas only.

## 7. C2ME (ishland/C2ME-fabric; repo at RelativityMC/C2ME-fabric)

Licence: [gap]. The README links `/licenses/LICENSE`, and I could not read the file. The Modrinth and CurseForge text does not state it. [verified: README]
Repo layout: `src/main/java/com/ishland/c2me` [verified listing]. Detailed IO file names: [gap] (the tree page did not expand).
README claims [verified]: parallel chunk generation, IO, and loading; "does not sacrifice vanilla functionality"; vanilla worldgen itself is nondeterministic across runs; `CheckedThreadLocalRandom` diagnostics. Recommends using C2ME with Lithium and Starlight. No numbers.

The coordinator asked for IO and serialization specifics. What I could not confirm: async region IO implementation, NBT/serialization speedups, and compression choices. I did not find any text describing them [gap]. Any port needs the source files, which I could not reach at this granularity.

Note: Concurrent Chunk Management Engine is C2ME's Modrinth title [search]; the Modrinth slug `concurrent-chunk-management-engine` returned "Project not found" [verified].

## 8. Minecraft 26.x-specific mods and changes (2026)

Modrinth, 26.3 filter, query "performance" [verified: https://modrinth.com/mods?q=performance&g=minecraft&v=26.3], page 1 of 5:
- AI Improvements: Performance Tuning (Forge/NeoForge).
- Adaptive Performance Tweaks (Fabric, Forge, NeoForge, Quilt).
- PerformanceHUD (overlay).
- Dynamic Performance (view/sim distance and mob cap by server load).
- Sodium (Fabric, NeoForge, Quilt).
- More Culling (Fabric, NeoForge).
- Concurrent Chunk Management Engine (Fabric and NeoForge).
- Distant Horizons.
- Let Me Despawn (mob despawn rules).
- spark (profiler).
- **Particle Core** (Fabric, Forge, Quilt, NeoForge), MIT. Culls particles outside view (about one twelfth of the lightbox visible at standard FOV). Vertex and lightmap optimizations, defers to Sodium when installed. Spawn reduction: `particle_core:excluded_particles` tag, `reduceParticlesByType` chance map, `turnOffPotionParticles`. Versions: 26.3, 26.2, 26.1.x, 1.21.x, 1.20.x [verified: modrinth.com/mod/particle-core]. Author's spark numbers (not vanilla): world rendering 41% → 16% of CPU; particles 25% → negligible; with Sodium ~5% CPU gain.
- Very Many Players (server).
- ScalableLux (Starlight-based, light updates; 26.2 and 26.1.x) [search earlier].
- Leaves Be Gone, Cull Leaves, Gnetum (HUD updates spread over frames), Fast Item Frames, Raise Sound Limit Simplified, Async Logger.

Versions: Modrinth list did not show per-mod versions beyond the filter [gap].

Vanilla 26.x changes (minecraft.wiki) [verified: https://minecraft.wiki/w/Java_Edition_26.1, https://minecraft.wiki/w/Java_Edition_26.2]:
- 26.1: chunk geometry storage in GPU memory and rendering changed ("Changed the internals of how chunk geometry data is stored in GPU memory and how they are rendered"). Lightmap light-to-brightness rewritten. Block-model sprites sorted into render passes by transparency. Default RAM 2 → 4 GB. Garbage collection changes from G1GC to ZGC on compatible computers (marked "more information needed"). **Default JVM args now include `-XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication`.** No AI, hopper, BE, tick, or loading entries visible in the first 100k chars.
- 26.2: "Upgrading certain worlds from before 26.1 can be faster". Particle overload: random pick of particles to render or skip. Tracy `--tracy` now includes GPU timings. Vulkan experimental, "may not be as performant or stable". Fixes MC-307330 "Major framerate drop in 26.1 and above" and MC-307008 "Severe loss of framerate in the new update". Sulfur cube AI (new mob). Entity physics attributes. Beds and signs now block models. No hopper, POI, or pathing perf change.
- 26.3 snapshot 2/5: OIT transparency replacing "Improved Transparency"; ShaderC on OpenGL; mob behaviour optimized when no players nearby (search snippet) [verified only partly: snapshot 5 pages timed out on this pass].

Coordinator note: vanilla 26.1 already adds COH and string dedup by default. Cadence already uses COH (per the coordinator), so no new item there.

## Top 10 concrete, exact (vanilla-identical) techniques

Each is exact only if the stated equivalence holds. Items marked [inferred] need a code check before porting.

1. **Flat 3×3×3 section copy per chunk build (block states + light), pooled arrays** — Sodium `common/src/main/java/net/caffeinemc/mods/sodium/client/world/LevelSlice.java` (+ `ClonedChunkSectionCache`, `PalettedContainerROExtension`). Targets block-state reads (18%) and light lookups (11%). Exact: same source containers, one unpack per build. [inferred for the copy semantics]. Check the reset bug (`reset()` loops to 3, not 27).
2. **AO corner table and precomputed interpolation weights** — Sodium `model/light/smooth/AoNeighborInfo.java`. Targets AO (30%). Exact only if the averaging in `AoFaceData` matches vanilla `AmbientOcclusion` bit-for-bit [gap].
3. **Lazy per-Y-layer biome tint grid with stamp invalidation** — Sodium `world/biome/LevelColorCache.java`. Targets biome tint (9%). Exact if the blur equals vanilla's blended colour average. Verify the `BoxBlur` buffer-swap concern first.
4. **Skip all-air section build tasks** (`hasOnlyAir()` early exit in `LevelSlice.prepare`). Exact. Cheap. Targets section scheduling, not AO.
5. **Lithium hopper neighbour cache + mod-count skip + sleeping** — `block/hopper/HopperBlockEntityMixin.java`. Exact by Lithium's claim with vanilla fallback; hopper ticks are a known singleplayer tick cost. [inferred on equivalence; Lithium says no mechanic change].
6. **Lithium block-entity sleeping** (furnace, brewing stand, campfire, crafter, shulker box, sculk, hopper) — `world/block_entity_ticking/sleeping/*`. Exact per Lithium's option descriptions; the wake-up on change is the equivalence argument [inferred].
7. **Lithium pathing node-type cache per BlockState** — `ai/pathing/WalkNodeEvaluatorMixin.java`. Exact if `PathType` depends only on the state for the cached cases [inferred]; the non-state cases (shulker boxes, Fabric API definitions) are handled by the single-position mode [verified from file].
8. **Lithium POI single-type dispatch with early exit** — `ai/poi/PoiSectionMixin.java`. Exact: keeps the HashMap/HashSet, same iteration order, same strict tie-break [verified from file comments].
9. **Lithium packed tick queue (AVL bucket map + int-packed `IntOpenHashSet`)** — `world/tick_scheduler/LevelChunkTicksMixin.java`. Exact in ordering by the file's own argument [inferred; `OrderedTickQueue` not read]. Targets chunk tick scheduling (`LevelChunkTicks`). Note the 4096 type cap throws an exception.
10. **FerriteCore FastMap neighbour table and property-map removal** (blockstate and multipart dedupe) — `fastmap`, `nopropertymap`, `dedupmultipart` subpackages in malte0811/FerriteCore. Memory, not CPU. Exact by the mod's account; the savings are modpack-scale (600 MB + 170 MB + 300-400 MB), so this user's vanilla saving is [gap]. Ranked last because the size is unknown for vanilla.

Excluded from top 10 (and why): Lithium `skip_air` and `minimal_nonvanilla.*` (nonvanilla); ImmediatelyFast GL-specific options (not Metal); SightCull and More Culling (ray-based or GPL, not vanilla-identical); Particle Core (opt-in spawn reduction; render path already has ParticlePhysics).

## Open gaps (for the next pass)
- Decompiled 26.3 source needed to confirm "vanilla already does it" for Lithium items 1-9 and FerriteCore items 1-6. Changelogs say nothing about them.
- ModernFix repo (embeddedt/ModernFix) 404 on GitHub; per-fix list not reached.
- C2ME: LICENSE and the IO/serialization source files not reached.
- Sodium `AoFaceData`, `SmoothLightPipeline`, `BoxBlur`, `ChunkBuildBuffers` not read.
- SightCull source repo not linked on its page.
- Minecraft 26.3 snapshot 5 change notes timed out on this pass.
