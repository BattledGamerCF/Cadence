# Shaderpack research: what blocks Cadence, how Iris/Metal map, caching, order

Status: research only. No build, no Gradle, no Minecraft run, no repo edits except this file.
Packs were extracted read-only into the agent scratchpad (not the user's instances).

Legend: [V] verified by reading a pack file or repo code (path given). [H] heuristic result from my
scripts (macro/guard evaluation is approximate; see Method). [W] from a web source (URL given).
[I] my inference.

## 0. Method and limits

- Pack census: `unzip` each zip into scratchpad/packs/NN_name. Scripts in scratchpad/tools/
  (census.py, feat2.py, predict.py). [V] for file lists and raw grep counts.
- Refusal prediction (predict.py) mimics Cadence's own rules: `PackPrograms` constructor
  (shaders/src/main/java/dev/cadence/shaders/runtime/PackPrograms.java:110-170), `validate()` in
  compile/ShaderCompatibilityCompiler.java:650, `PackTextures` (raw texture decl, unbound sampler),
  `PackFormats`, `ShaderDirectives`. Option defaults come from the first-applicable `#define`s, with
  a sequential preprocessor pass that mimics `#if`/`#define` and the `MC_*` environment Cadence
  defines (PackEnvironment.definitions(): MC_VERSION 260300, MC_GL_VERSION 330, MC_OS_MAC...). Cadence
  does NOT define IRIS_FEATURE_* or IS_IRIS, so Iris-only branches are taken as off. [V]
- Known weakness: `#if` expressions the tool cannot parse are treated as "kept"; macro defaults
  are first-match. So per-pack predictions are [H], not proof. The authoritative check is simply
  running Cadence's own loader on each pack and reading the refusal message (it names the program and
  the declaration). Recommended as step 0 of the plan (section 7).
- Web research was thin: Iris's shaders.properties / ShaderDoc pages were not found by search, and
  Apple's Metal feature-set PDF was not fetched. Where I say "unverified" the cell or directive
  needs a direct read of the source.

## 1. Per-pack census (overworld folder Cadence would use, default options)

Cadence picks the overworld folder as: world0 if it exists, otherwise the root (ShaderPack.java:332-334)
[V]. Packs are listed as found (duplicates noted).

| # | Pack (instance) | Programs (overworld) | Predicted blockers in Cadence today (default options) | Confidence |
|---|---|---|---|---|
| 01 | BSL v10.1.8 (Cadence Test) | gbuffers_* (incl. dh_), composite..composite7, deferred, deferred1, final, shadow; shadowcomp.csh present but enabled only with MULTICOLORED_BLOCKLIGHT (default off) [V] | none (user reports it works) | [V] |
| 02 | Bliss v2.1.2 Chocapic13 (Cadence Test) | world0: gbuffers_* , composite..composite13, deferred..deferred2, final, shadow; setup.csh and shadowcomp.csh exist, disabled by default in world0 [H]; a `dimensions/` folder exists but dimension.properties maps world0 to overworld [V] | usampler1D texBlockData (shadow.vsh:65 world0) [V]; sampler3D texLpv (dimensions/all_translucent.fsh:62) [V, the same pattern in world0 via H]; gl_Fog.color (lib/nether_fog.glsl:65, lib/end_fog.glsl:251) [V] reached from composite/deferred [H]; image3D imgLpv / imgVoxelMask in lib/lpv_buffer.glsl, lib/voxel_common.glsl [V], reached from shadow [H] | high for usampler1D/sampler3D, medium for gl_Fog |
| 03 & 13 | ComplementaryReimagined r5.9.3 (Cadence Test, MAX FPS: same file) | world0: gbuffers_*, composite1,3..7, deferred1, final, dh_*; shadowcomp.csh only with colored lighting (off) [V] | gl_Fog.start/scale (lib/atmospherics/fog/mainFog.glsl:212; program/deferred1.glsl:506) [V]; reached by composite/gbuffers [H]; image/voxel code is gated by IRIS_FEATURE_CUSTOM_IMAGES and COLORED_LIGHTING=0 by default, so not a blocker [V] | medium (gl_Fog usage reachability unverified) |
| 04 | ComplementaryUnbound r5.9.3 (Cadence Test) | same as 03 | same as 03 | medium |
| 05 | MakeUp-UltraFast 9.5g (Cadence Test) | programs in root and world0; no compute, no gsh | shadowcolor0Clear / shadowcolor1Clear are `const bool` inside `#ifdef SHADOW_CASTING` (lib/config.glsl:219-220; SHADOW_CASTING is defined unconditionally in that file) [V] -> Cadence refuses ("shadowcolor...Clear" in PackPrograms line ~136 regex); gl_FogFragCoord written in vertex stages (common/damage_vertex.glsl:25, and gbuffers_* [H]) -> validate() refuses unknown gl_ [V code] | high for shadowcolor Clear, high-medium for gl_FogFragCoord |
| 06 | Sildur's Vibrant v2.02 Extreme (Cadence Test) | root programs only (no world0); composite, composite1-2, deferred1, final, dh_*; 10 enabled | none predicted | medium-high it loads (not run) |
| 07 | VanillaEX r1.2.0 (Cadence Test) | root: composite, final, dh_*; 9 enabled, minimal | none predicted | medium-high it loads |
| 08 & 09 | ComplementaryReimagined / Unbound r5.7.1 (Genesis) | world0: gbuffers, composite*, deferred1, final | usampler3D voxel_sampler / wsr_sampler (lib/uniforms.glsl:135,147) [V] — NOT gated by an IRIS_FEATURE in 5.7.1 [H]; gl_Fog [H] | medium-high |
| 10 | I Like Vanilla v1.3.3b (Genesis) | world0: _gbuffers*, composite1..composite99 (many), deferred1, dh_*, shadowcomp.csh (off) | size.buffer.colortex4 is active: BLOOM_RENDER_SCALE defaults to 2 (basics/setting_defines.glsl:222) -> `size.buffer.colortex4 = 0.25 0.25` [V] -> Cadence throws "requires unsupported rendering declaration" for any size.buffer.* | high |
| 11 | Pegasus v0.3.7 (Genesis) | world0: prepare.csh (compute, enabled [H]), prepare_a..d.csh, gbuffers_* with image3D cimage3_colored_light (main_f.glsl:30, unguarded in the gbuffer path [H]), const bool shadowcolor0Nearest (main_f.glsl:239) [V] | compute enabled (refused); size.buffer.colortex6 (settings.glsl:225 default divider 3 -> 0.33) [V]; image3D [H]; shadowcolor Nearest directive [V] | high (compute, size.buffer) |
| 12 | Sildur's Vibrant v1.541 Extreme (Genesis) | root: composite..composite2, dh_*, final | gl_FogFragCoord written in gbuffers_basic.vsh:16 (and world-1/world1 copies) [V] | medium-high |
| 14 | Sildur's Enhanced Default v1.20 Fancy (MAX FPS) | root: composite, composite1, dh_*, final, gbuffers_* | gl_FogFragCoord in gbuffers_textured.vsh/gbuffers_basic.vsh [H] | medium-high |
| 15 | Insanity Shader Universal v1.650 (The World That Wasint) | world0: composite1-7, deferred1, final, gbuffers_* ; no compute, no size.buffer | none predicted | medium-high it loads |

Pack-wide raw counts (all files, before default-option gating) [V, grep in scratchpad]:

| Pack | gl_TextureMatrix | gl_Fog*/gl_FogFragCoord | sampler3D/usampler1D/usampler3D | image*/imageLoad/Store | .csh files | size.buffer (raw, not gated) | shadowcolor directives |
|---|---|---|---|---|---|---|---|
| BSL | 18 | 0 | 13 | 3 | 3 | 0 | 1 (gated) |
| Bliss | 7 | 2 | 7 | 4 | 8 | 0 | 0 |
| Complementary R 5.9.3 | 24 | 2 | 3 | 5 | 3 | 2 (IRIS-gated, inactive) | 0 |
| Complementary Unbound 5.9.3 | 24 | 2 | 3 | 5 | 3 | 2 (gated) | 0 |
| MakeUp 9.5g | 7 | 13 | 0 | 0 | 0 | 0 | 2 (Clear, active) |
| Sildur v2.02 | 51 | 24 | 0 | 0 | 0 | 0 | 0 |
| VanillaEX 1.2.0 | 12 | 0 | 0 | 0 | 0 | 0 | 0 |
| Complementary R 5.7.1 (x2 folders) | 24 | 2 | 3 | 5 | 3 | 0 | 0 |
| I Like Vanilla 1.3.3b | 32 | 0 | 1 | 2 | 3 | 2 (colortex4, active) | 0 |
| Pegasus 0.3.7 | 40 | 0 | 3 | 4 | 21 | 1 (colortex6, active) | 1 |
| Sildur v1.541 | 25 | 3 | 0 | 0 | 0 | 0 | 0 |
| Sildur Enhanced 1.20 | 4 | 8 | 0 | 0 | 0 | 0 | 0 |
| Insanity 1.650 | 15 | 0 | 0 | 0 | 0 | 0 | 0 |

Other facts from the census [V]:
- No pack in the set contains a geometry (.gsh), tessellation (.tcs/.tes) or vsh/fsh-only extra
  stage in any enabled program. Geometry and tessellation are not needed to unblock this set.
- Packs set iris.features.optional (CUSTOM_IMAGES, SSBO, BLOCK_EMISSION_ATTRIBUTE, ...). Cadence
  does not advertise those features and does not define IRIS_FEATURE_* macros, so those branches are
  off. The Complementary size.buffer block is only under `#if defined IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE`
  [V shaders.properties:118-121], so it does not trigger in Cadence.
- Pegasus and Bliss keep dimension-specific programs that Cadence reads through the same
  folder rule. Pegasus has both root and world0 programs; world0 wins [V].
- Complementary's custom `image.floodfill_img` lines (shaders.properties:165-186) are keys Cadence
  does not parse (PackTextures handles only `texture.`). They matter only once the image uniforms
  are reachable.

Predicted summary, from the table above (heuristic):
- Likely load today (no predicted blocker): BSL (confirmed by user), Sildur v2.02, VanillaEX, Insanity.
- Likely blocked (one or more high-confidence blockers): ILV (size.buffer), Pegasus (compute + size.buffer + image), MakeUp (shadowcolor Clear).
- Likely blocked by a medium-confidence item: Bliss (sampler types), Complementary (gl_Fog reachability), Complementary 5.7.1 (usampler3D), Sildur v1.541 and Enhanced (gl_FogFragCoord).

## 2. Missing features, ranked by how many packs they block

Counting the packs above (distinct packs, 15 folders -> 10 distinct packs, the duplicates removed):

1. Legacy fixed-function builtins: gl_FogFragCoord (vertex output), gl_Fog.start/scale/color
   (fog uniforms), gl_FogFragCoord in fragment. Blocks Sildur v1.541, Sildur Enhanced, MakeUp,
   Bliss, Complementary (x3 copies, 5.9.3 and 5.7.1). **About 6 of 10 distinct packs** (predicted,
   several medium confidence). Cheapest big win: they are only used as data, no pass structure.
   Cadence already maps gl_TextureMatrix, gl_ModelViewMatrix, gl_Normal, gl_Color, gl_MultiTexCoord0/1
   (ShaderCompatibilityCompiler + MinecraftVertexAdapter). [V]
2. Extra sampler types: usampler1D/usampler3D/sampler3D (`uniform usampler1D texBlockData`,
   `uniform sampler3D texLpv1`, `uniform usampler3D voxel_sampler`). Only sampler2D, sampler2DShadow,
   samplerCube, isampler2D, usampler2D are accepted (ShaderCompatibilityCompiler.java:510-512) [V].
   Blocks Bliss, Complementary 5.7.1, Pegasus. **3 distinct packs.** Requires
   texture-buffer and 3D texture support in the Metal path. The texBlockData 1D case can be a 2D
   texture with a width-based index: a translation, not new hardware.
3. size.buffer.* and scale.*: Cadence refuses ANY active size.buffer.* key (PackPrograms constructor
   loop) [V]; scale.<prog> and flip.<prog>.* only for enabled programs [V]. Blocks ILV, Pegasus
   (**2 packs**), and Complementary if IRIS-gated branches were enabled.
   This is the easiest fix: honor size.buffer as a per-buffer scale (ShaderRuntime already has
   render scale plumbing and PackRenderTargets allocates per-buffer textures [V]).
4. shadowcolor Clear/Nearest/Format/Mipmap/ClearColor directives: refused by regex in PackPrograms
   [V]. Blocks MakeUp (Clear), Pegasus (Nearest). **2 packs.** Clear and Nearest are sampler and
   clear-flag settings; they are cheap to support. Format maps through PackFormats; Mipmap
   through PackMipmaps (currently colortex only).
5. Compute (.csh) enabled: refused outright [V]. Blocks Pegasus (prepare.csh, enabled in world0 [H]).
   Bliss and BSL have compute programs that are default-off. **1 pack enabled-compute, 3 with compute present.**
6. Image load/store, SSBO, atomics, shared memory: refused by validate() (`image[123]D`, `buffer`,
   `atomic_uint`) [V]. Blocks Pegasus (cimage3_colored_light, image3D in gbuffers) and Bliss
   (shadow imgLpv / imgVoxelMask) [H]. Complementary's are gated off by default.
7. Custom `image.*` properties and raw custom textures: `texture.` keys are supported, `image.`
   keys are ignored [V]. Not a direct blocker; required once images are supported.
8. Geometry and tessellation: none in this set. Deprioritise (see section 4).
9. centerDepthSmooth with DOF_FOCUS_MODE=0: refused (PackPrograms.validateFocusSupport) [V]. Not
   triggered by any pack in the default configs I checked (BSL composite3 uses it with DOF off
   by default [H]).

## 3. How Iris implements these (behaviour only; LGPL-3.0, no code copied)

Web search did not yield Iris's ShaderDoc pages (results were Qt/Unity docs) [W, no citable source].
Semantics below are from general knowledge of the OptiFine/Iris pack format and are marked [I]
until checked against the Iris source/ShaderDoc.

- Compute (.csh) [I]: dispatched in the composite-like order (after shadow, before gbuffers or
  between deferred phases as the pack names them), group count from `workGroups` /
  `workGroupsRender` in shaders.properties (scaled by render size for workGroupsRender). Iris
  issues memory barriers between dispatch and dependent passes. Ordering matters: BSL's shadowcomp
  must run before its deferred/composite reads.
- Images/SSBOs [I]: `image.<name> =` declares an image with format and size; `bufferObject.N`
  declares an SSBO with a size in bytes. Both persist across frames unless cleared. Clear state
  is per buffer.
- Setup passes [I]: run once when the pack loads or on resize, not per frame. Cadence refuses
  setup* (PackPrograms.validateProgramProperties) [V]; that is a clean extension point (run once at
  pack load).
- scale.<prog> / flip.<prog>.<buffer> / size.buffer [I]: scale is a per-pass viewport fraction;
  flip is a per-pass ping-pong swap; size.buffer sets a buffer's absolute or relative size.
- shadowcolor directives [I]: Format and Mipmap set the texture format and whether mips are
  generated; Nearest switches the sampler; Clear toggles the per-frame clear; ClearColor sets it.
- Custom textures [I]: texture.<group>.<sampler> binds a 2D or 3D texture; raw declarations
  (`texture.x = name rgba16f ...`) describe a format. Cadence accepts only plain image paths [V].
- centerDepthSmooth [I]: a host-driven per-frame value that Iris smooths over time. Cadence refuses
  it when DOF_FOCUS_MODE is 0 [V].
- Geometry/tessellation [I]: Iris runs gsh/tcs/tes through the GL pipeline; packs using them are
  rare in the census above (none here).
- DH and Voxy programs [I]: Iris runs them with extra uniforms and buffers; Cadence skips dh_*
  and voxy* [V PackPrograms line ~112]. Skipping is safe only if the pack's main path does not depend
  on them.

## 4. Metal mapping (Apple silicon, macOS 26+)

Verified/cited:
- Metal has no geometry or domain shader stages. Layered rendering exists instead. [W: Apple Developer Forums, thread "Metal - Support for geometry and domain shaders?" https://developer.apple.com/forums/thread/3566]. Mesh/object shaders and post-tessellation vertex functions are the Metal options.
- MTLBinaryArchive: create with url nil, add pipelines (addRenderPipelineFunctions / addComputePipelineFunctions / addTileRenderPipelineFunctions), serialize(to:) (parent directory must exist), reload by setting url, use FailOnBinaryArchiveMiss to catch misses. Archives are valid only for the same GPU and OS build; otherwise Metal recompiles. [W: https://developer.apple.com/documentation/metal/creating-binary-archives-from-device-built-pipeline-state-objects.md ; https://developer.apple.com/documentation/metal/mtlbinaryarchive/serialize(to:).md]
- Metal 4 adds MTL4PipelineDataSetSerializer (macOS 26+), with opaque contents. Use it only if targeting Metal 4 compilers. [W: https://developer.apple.com/documentation/metal/mtl4pipelinedatasetserializer.md]
- Texture atomics were added around MSL 3.1 per a secondary MoltenVK discussion (int/uint, load/store/exchange/compare-exchange/fetch-op). Not verified against Apple's spec [W secondary: https://github.com/KhronosGroup/MoltenVK/discussions/1938]. Texture usage must enable atomic access. [W: https://developer.apple.com/documentation/metal/mtltextureusage.md]
- Read-write texture pixel formats per GPU family are in Apple's Metal Feature Set Tables PDF [W: https://developer.apple.com/metal/Metal-Feature-Set-Tables.pdf]. I did NOT fetch that table; the R32Float/R32Uint read-write cells are UNVERIFIED. Query at runtime (`supportsFamily`, `readWriteTextureSupport` / per-format support) before relying on them.
- SPIRV-Cross: no geometry/tessellation translation to Metal in a usable form [W, weak: Qt bug tracker discussion; SPIRV-Cross commit "MSL: Fix writing default gl_PointSize in tessellation shaders" Oct 2025]. Image atomic handling in its MSL backend: UNVERIFIED (check spirv_msl.cpp).

Mapping per feature [I unless marked]:
- Compute -> MTLComputePipelineState, dispatch with threadgroup size from the shader's local_size; threadgroup memory for `shared`; barrier between render and compute encoders (encoder boundary is the barrier on Metal).
- Image load/store -> texture2d/texture3d with access::read / write / read_write; atomics via texture atomic functions on int/uint formats (MSL 3.1+, verify).
- SSBO -> device buffer, MSL buffer binding via SPIRV-Cross (decoration binding option).
- Sampler types: usampler1D -> 2D texture with width indexing; sampler3D -> texture3d; usampler3D -> texture3d<uint>.
- Fog builtins -> uniforms filled from the Minecraft fog state (the same values Cadence already has for vanilla fog), and gl_FogFragCoord -> a plain vertex-to-fragment varying (eye distance).
- Geometry shaders -> not needed by this set. If ever needed: CPU/compute expansion or mesh shaders.
- Tessellation -> not needed by this set.
- Per-stage limits (Metal, to check against Apple's table): 31 buffers, 128 textures, 16 samplers per stage. Packs with many MRTs and samplers (BSL, Complementary) stress the texture count; use argument buffers if needed.
- SPIRV-Cross MSL options that matter: msl_version (set to the Metal version actually used), argument buffers (if the pack exceeds the per-stage limits), enable_decoration_binding, read-write image emulation (if a format is unsupported), and for atomics on textures the MSL version gate.

## 5. Caching translations

What exists today [V]:
- MSL cache on disk, opt-in: `-Dcadence.startup.mslCache=true|verify` (default false). Keys are SPIR-V, stage and uniform count, plus this mod's and SPIRV-Cross's versions; stored under <game dir>/cadence-cache/msl/ (metal/src/main/java/dev/cadence/metal/MetalPipeline.java:326-348).
- Metal's own on-disk shader cache is defeated only by `-Dcadence.metal.uncachedMsl=true` (test mode) [V MetalPipeline.java:236-240].
- Preprocessed source, shaderc SPIR-V and the Metal library/pipeline objects are NOT cached by Cadence (I found no such cache in PackPrograms/ShaderCompatibilityCompiler; the MSL cache is the only one) [V, by grep].

Recommended design:
- Key per program: hash(preprocessed vertex+fragment source bytes after includes, effective option values for that pack, dimension, pack content fingerprint [ShaderPack.contentFingerprint exists, V], translator version, shaderc version, SPIRV-Cross version, Cadence shader-cache version, GPU family and name, macOS build).
- Store three layers: (1) SPIR-V (shaderc output) keyed on source+options; (2) MSL text keyed on SPIR-V+SPIRV-Cross options (exists, opt-in: make it the default once verified); (3) MTLBinaryArchive per pack profile and GPU/OS build, so Metal does not recompile on relaunch. Reload archives with FailOnBinaryArchiveMiss and fall back to compile on a miss.
- Option changes: only programs whose source hash changes are re-translated; the option set is a prefix of each program's key, so toggling a shadow option does not invalidate gbuffers.
- Size: MSL text is small (KB per program); SPIR-V similar; binary archives are the largest, roughly a few MB per profile [I, estimate]. Bound the directory (LRU, e.g. 512 MB) and key by pack fingerprint, so deleted packs age out.
- Background pre-translation: translate the enabled profile on load, then the most likely next profile (e.g. the current pack with the next-most-likely option set) on a worker thread at low priority. Shaderc/SPIRV-Cross are CPU-bound and thread-safe per instance, so parallel translation of independent programs is possible; keep Metal compilation on a background queue and the render thread on cached pipelines.
- Relaunch: the MTLBinaryArchive makes relaunch cost one archive read. A different GPU or macOS build misses the archive and falls back to MSL cache, so the MSL layer still avoids shaderc/SPIRV-Cross work.

## 6. Performance on the M2 Air (8 GB, fanless)

- Compute passes: each dispatch splits the render encoder; on Apple tile GPUs this flushes tile memory. Batch consecutive compute dispatches into one encoder. [I]
- read_write textures: slower than plain texture writes on Apple GPUs; prefer write-only images where the pack only stores. [I]
- Many MRTs and full-resolution RGBA32F buffers: the biggest memory and bandwidth cost at 8 GB. Map RGBA32F to RGBA16F when the pack's precision allows (PackFormats already maps RGBA32F -> RGBA32_FLOAT; consider a quality gate). [I]
- Mitigations: memoryless textures for transient targets (depth/shadow scratch), lossless compression is automatic on Apple GPUs for render targets, half precision in shaders, imageblocks/tile shaders for deferred-style passes where the pack's composite programs read only the previous pass's output. [I]
- Fog-builtin uniform replacement is free (no pass). [I]
- Thermal: the mod already has governors (ShaderRuntime scale, RenderScale). Use them for pack quality tiers rather than adding passes. [I]

## 7. Recommended implementation order

Ranked by packs unblocked per unit of work (based on the predictions above; verify each by running
Cadence's loader on each pack and reading the refusal first):

0. Verification (cheap, no code): run Cadence's loader on the 10 distinct packs with a dry-run
   or a logging flag and record the exact refusal per pack. Replace this report's [H] with [V].
1. Legacy fog builtins (gl_FogFragCoord, gl_Fog.*): map to uniforms/varyings. Unblocks ~6 packs. Low risk.
2. size.buffer.* and scale.<prog> honored as per-buffer scale (use existing render-scale plumbing). Unblocks ILV and Pegasus (partly). Low-medium risk.
3. shadowcolor Clear / Nearest / ClearColor / Format / Mipmap. Unblocks MakeUp and Pegasus (partly). Low risk.
4. Extra sampler types (usampler1D as width-indexed 2D, sampler3D/usampler3D as texture3d). Unblocks Bliss, Complementary 5.7.1 and Pegasus. Medium risk (texture3d bind path).
5. MSL cache default-on with the key in section 5, then the MTLBinaryArchive layer. No new packs unblocked; big relaunch win. Medium risk (cache invalidation: test with the Pack Check frozen-clock screenshots).
6. Compute (.csh) and workGroups for the enabled-compute case (Pegasus prepare). Needs Metal compute encoder, dispatch order, barriers. High effort; one pack.
7. Image load/store and atomics (Pegasus images, Bliss shadow). Needs read_write tier checks and MSL atomics. High effort.
8. Geometry, tessellation, setup refactor: not needed by this set. Defer.

Testing a translation is exact: use the existing Pack Check (frozen clock screenshot comparison)
against the pack's reference rendering on the same build with Cadence's current path for packs
that already work (BSL), plus one screenshot per dimension. Compare translated vs untranslated
output for each newly unblocked feature, with the feature on and off.

## 8. Gaps and open items

- Iris ShaderDoc and Apple's Metal feature-set PDF not read (search returned unrelated pages). Items
  marked [I] or UNVERIFIED need that read.
- Macro/guard predictions are heuristic; the loader's own refusal messages are the ground truth.
- Duplicated packs: Complementary R 5.9.3 appears in Cadence Test (03) and MAX FPS (13) with the same
  file set; Complementary R 5.7.1 and Unbound 5.7.1 are in Genesis (08, 09). Only one copy of each analysed.
