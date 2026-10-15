# Day 3 designs (2026-10-12 afternoon) — build first, test later (user's order)

The user asked for: (1) far shadow proxies, (2) GPU occlusion culling, (3) Lithium, (4) launcher memory + compact
headers, (5) faster world-generation maths, (6) memory trims; then multithreaded chunk loading (design); then, after a
research report (../research/APPLE-METAL-MC.md, by one Sonnet agent), GPU → CPU → RAM optimization.
Rules as always: exact or a flagged trade-off, switchable, measured before it ships on by default.

## 1. Shadow proxies (GPU, shaders on) — `cadence/shadow/`
Problem: the shadow pass is geometry-bound; at RD 16 it drew every compiled section in the light's box (receiver
culling removes some) with full meshes, ~17 ms/frame (now drawn every 2nd frame).
Design:
- Beyond a horizontal distance D from the camera (config `shadowProxyDistance=`, default 96 blocks, 0 = off), the shadow
  pass stops drawing real section meshes and draws one coarse proxy mesh per chunk column instead.
- Proxy mesh of a column: per block column the top "shadow caster" (scan down from WORLD_SURFACE: skip air, fluids and
  blocks without a full collision box unless leaves; plants, snow layers, carpets don't count), a top quad at its height
  (textured with the block's particle sprite so leaves keep their alpha-tested holes), and vertical wall quads toward the
  4 neighbours where the neighbour's caster top is lower (needed for low sun). Neighbour columns across chunk borders
  read the neighbour chunk if loaded. Roughly 100-300 quads per column versus thousands in its real sections.
- Vertices are written in the pack's own terrain format through the existing path (TerrainShaderGeometry section/block
  context + the BufferBuilder mixin), so BSL's shadow vertex shader (distortion, waving) treats them like terrain.
- Proxies are grouped in 8×8-column regions: one GpuBuffer per region (rebuilt when a column in it changes), drawn with
  vanilla's own `ChunkSectionsToRender.DrawIndirect` (public constructor): vertex buffer = region buffer, index buffer =
  the shared sequential quad buffer, instance data = one ChunkSectionInfo per column (region origin), indirect commands
  = one IndexedDraw per column that is far and selected this frame. Same pipeline as SOLID terrain, same uniforms (the
  shadow selection's terrainTransformUBO via accessor).
- Columns are (re)built on the render thread with a budget (16 columns/frame), when first needed and when one of the
  column's sections is marked dirty (hook on section dirty), or when a neighbour column's height changed at the border.
- Compile saving: shadow-only sections (not in the camera's list) beyond D need not be compiled any more
  (ShadowCompileCull gets a second rule) — 100-200 MB of GPU memory at RD 16 in the earlier census.
Trade-off (flagged): far shadows come from a height field: no overhangs/caves/arches beyond D, trees as canopy blocks.
BSL's far shadow texels are already ~1 block. Expected: shadow pass vertex work −50..70 % at RD 16.

## 2. GPU occlusion culling (Hi-Z) for the main terrain pass
- Vanilla's section flood already removes sections behind closed walls (caves); Hi-Z adds "behind a hill".
- Two-phase, so nothing can pop in: phase 1 draws the sections that were visible last frame; a compute pass builds a
  depth pyramid from that depth; a second compute pass tests the remaining sections' boxes against it and writes their
  indirect commands (instanceCount 0/1); phase 2 draws those. Exact: a section is skipped only if its whole box is
  behind already-drawn depth this frame.
- Needs native work in mcopt's Metal backend (compute pipelines, writing indirect buffers, pyramid mips) and a split of
  vanilla's terrain draw into two groups. With a pack the depth to test is the gbuffers' depth after the terrain pass.
- Largest and riskiest item; built after proxies, behind `-Dcadence.hiz=true` until measured.

## 3. Lithium — test only at the end (user). Needs a download (Modrinth, its 26.3 Fabric build): ask with file name,
source and size at test time.

## 4. Launcher memory and flags — the per-instance JVM arguments edit was refused by the permission classifier. The
user pastes in CurseForge (Cadence Test > Profile Options > Java arguments):
`-XX:+UseZGC -XX:ZUncommitDelay=60 -XX:ZFragmentationLimit=5 -XX:+UseCompactObjectHeaders -XX:+DisableExplicitGC
-XX:+ParallelRefProcEnabled -XX:+PerfDisableSharedMem -Dfile.encoding=UTF-8 -Dlog4j2.formatMsgNoLookups=true`
(their global arguments minus AlwaysPreTouch, plus compact headers; both GCs verified to start with the flag). Memory
is already 2048 MB for the instance. Whether G1 or ZGC is better on 8 GB is a RAM-phase measurement.

## 5. World generation maths (CPU)
Hot (fly test JFR): surface rules 33 % (SequenceRule/ConditionRule lambdas, LazyYCondition), noise fill 32 %
(density volumes, GradientNoise.permute 9 % self), Aquifer.computeSubstance 7 %, ores 9 %, trees 7 %.
Rule: output must be bit-identical. TERRAIN (noise+surface+carvers) is deterministic per chunk, so it CAN be verified:
a self-test that builds TERRAIN for a list of chunk positions straight through ChunkGenerator.buildTerrain on fresh
ProtoChunks (same seed/random state) and hashes them, run with and without each change. Candidates only after that
harness exists: surface-rule condition caching per column, aquifer per-cell caching, permute table access.

## 6. Memory trims (RAM)
- Unifont glyphs (UnihexProvider: 136k glyph objects + 115k ShortContents ≈ 7 MB): decode lazily on first use.
- DataFixerUpper types (~16 MB built at startup): only needed to upgrade old data; lazy build is what ModernFix does —
  risky, design only until measured.
- ThreadingDetector slimming — done (−17 MB).

## 7. Multithreaded chunk loading (design)
Vanilla 26.3: readChunk (IO worker) → `SerializableChunkData.parse` on the worker pool ("parseChunk"; sections are
decoded here) → `read` on the server main thread (POI consistency, light data queueing, LevelChunk/ProtoChunk
construction, structure starts unpack, heightmap priming, entity/block-entity post-load). So loading is already mostly
parallel; the main-thread `read` is the serial remainder.
Plan: measure `read`'s share of the server thread while flying back over explored land (second pass of a glide). If it
matters: move the pure parts (structure start/reference unpacking, heightmap priming, ProtoChunkTicks/LevelChunkTicks
building) into the parse stage on the pool, leaving POI/light/chunk-map publication on the main thread; same objects,
same order of publication.
