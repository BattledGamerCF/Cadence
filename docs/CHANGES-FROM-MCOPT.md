> **Renamed in the legal pass (branch `legal-pass`).** This file, dev-log/PLAN.md, dev-log/DAY2.md, dev-log/OVERNIGHT.md and the RESEARCH/DESIGN
> notes are history and keep the names of their day (mcopt, Minecraft Shader Loader, `dev.kausik`, `minecraftShaders.*`).
> Old name -> current name:
>
> | Old | Now |
> |---|---|
> | Java `mcopt.metal.*` / `mcopt.fps.*` | `dev.cadence.metal.*` / `dev.cadence.fps.*` |
> | Java `dev.kausik.shaders.*`, `dev.kausik.scene.*`, `dev.kausik.sceneoptimizer.*` | `dev.cadence.shaders.*`, `dev.cadence.scene.*`, `dev.cadence.sceneoptimizer.*` |
> | mod ids `mcopt-metal`, `minecraft_shader_loader`, `mcopt-fps` | `cadence`, `cadence_shaders`, `cadence_hud` |
> | jars `metal-*.jar`, `shaders-*.jar`, `fpshud-*.jar` | `cadence-metal-*.jar`, `cadence-shaders-*.jar`, `cadence-hud-*.jar` |
> | `-Dmcopt.X`, `-DminecraftShaders.X`, `-DminecraftScene.X` | `-Dcadence.X`, `-Dcadence.shaders.X`, `-Dcadence.scene.X` (the old ones still work) |
> | `config/mcopt.properties`, `config/minecraft-shader-loader.properties` | `config/cadence.properties`, `config/cadence-shaders.properties` (the old ones are still read) |
> | resources `mcopt/...`, mixin configs `mcopt-metal-*.mixins.json` etc. | `cadence/...`, `cadence-metal-*.mixins.json`, `cadence-hud.mixins.json`, `cadence-shaders.mixins.json`, `cadence-scene-*.mixins.json` |
> | log prefixes `[mcopt]`, `mcopt-own:`, `mcopt-lod:` ... | `[cadence]`, `cadence-own:`, `cadence-lod:` ... |
>
> Full details: LICENSING.md. Credits and licenses: CREDITS.md, NOTICE.

# Cadence changes to mcopt

Apache-2.0 §4(b): files Cadence modified or added, relative to mcopt commit `7b1d0fb`.

## Added
- `shaders/` — the shader-pack loader and scene layer from minecraft-metal-renderer (MIT, commit `c609745`), adapted:
  imports point at this backend; `CadencePackGate` applies its game hooks only when a pack is selected at launch;
  `PackMipmaps` uses its raster mip path (no partial native mip kernel here yet); mod dependency is `mcopt-metal`.
- `metal/.../MetalPassHints.java`, `MetalPipelineMath.java`, `SpirvMathPolicy.java` — adapted from minecraft-metal-renderer (MIT).
- `metal/.../MetalGpuSampler.java`, `MetalPipelineResources.java` — backend extension entry points for the loader.
- `tools/cadence_native_test.m` — GPU check of the new native functions, no Minecraft needed.
- `dev-log/PLAN.md`, `CHANGES-FROM-MCOPT.md`.

## Modified
- `metal/src/main/native/mcmetal.m` — `mc_library_new_mode` (per-pipeline math mode + preserveInvariance),
  `mc_sampler_new_compare` (depth-comparison sampler).
- `Native.java` — bindings for the two functions above.
- `MetalPipeline.java` — MSL source passed via native heap, not the 64 KB MemoryStack (pack shaders overflowed it: crash on world load); shader-pack pipelines: Safe/Relaxed math with invariance; per-kind Metal slot layout
  (16-sampler limit); slot layout in the MSL cache key.
- `MetalRenderPass.java` — binds each uniform at its pipeline's slot.
- `MetalEncoder.java` — honours the loader's "fully overwritten" hint as a don't-care load.
- `MetalDevice.java` — advertises the implemented extensions.
- `MetalSampler.java` — comparison twin.
- `MetalTexture.java` — mip extents clamped to at least 1.
- `Profile.java` — shader pack selected: own near terrain off, translated-shader disk cache on.
- `settings.gradle` — `shaders` module.
- `NOTICE` — this attribution.
- `shaders/.../cadence/` — Video Settings "Shaders" row (pick pack / Off, open folder); `CadencePackGate.selectedPack()`; hand-off log line in `ShaderLoaderMod`.
- Live shader switching: `ShaderLoaderMod.activate/deactivate` (was one-shot init), `FeatureDrawContext.disable`,
  `cadence/ShaderSwitcher` (close old runtime, load new, rebuild chunks via `levelExtractor.allChanged()`), applied when
  Video Settings closes; `liveSwitching` config key (default true) loads the hooks with no pack — `CadencePackGate` and
  mcopt `Profile` then keep `mcopt.own` off; "Switching: Live / Fastest" button.

- Switching rebuilds the chunk buffers in place (`cadence/ChunkBufferRebuild`, `cadence/mixin/LevelRendererRebufferMixin`):
  vanilla's per-layer uber buffers are sized for one vertex stride, so a pack on/off switch (28 <-> 64 bytes) replaces them
  under the dispatcher lock before the reload (`-Dcadence.switchMode=rebuffer|dispose|plain`).
- Render scale (`cadence/RenderScale`, `runtime/PackUpscale`, `ShaderRuntime`): the pack renders its frame at 50-100% of
  the window, then a Catmull-Rom + contrast-adaptive-sharpen pass upscales it to the main target. Default `auto`: dynamic
  resolution holding `autoTarget` (60) fps — proportional drops, +0.05 rises after two agreeing 1 s windows, 2 s hold after
  each change, no rises while chunks are still compiling. Video Settings "Resolution" button.
- Shader Quality button: the pack's `profile.*` presets, applied live (pack reload).
- `ShaderRuntime.DISCARD_FULLSCREEN_LOADS` settable at runtime (A/B only; default unchanged).
- Scene Optimizer (`dev/kausik/sceneoptimizer`, from minecraft-metal-renderer, MIT): spatial section index for shadow-view
  selection; behind the same gate, `-Dcadence.sceneOptimizer=false` turns it off.
- Diagnostics: `cadence/SwitchProbe` (`[probe]` log lines), `cadence/CadenceSelfTest` (`-Dcadence.selftest=` scripted
  steps, FPS windows, screenshots, world frozen at noon), `tools/cadence-test.sh` + `tools/cadence-guard.sh` (memory-guarded
  test launches).
- `PackMipmaps` native mip path disabled (not implemented here).
- `metal/.../GpuBusy.java` (added), `MetalEncoder.java` — GPU busy time (union of command-buffer GPU spans), always on;
  Auto render scale judges by GPU time per frame (works under VSync; ignores CPU-bound frames).
- `TerrainShaderGeometry.java` — compact 48-byte pack terrain vertices by default (lossless; `-DminecraftShaders.compactTerrainVertices=false` for 64).
- `ShadowRenderer.java` — shadow receiver culling (upstream, audited per pack) on by default and switchable at runtime;
  measured -5..-7 ms GPU/frame on BSL at RD 12 (48% of shadow sections culled).
- `cadence/render/CompileQueue.java`, `cadence/mixin/SectionTaskDynamicQueueMixin.java`, `SectionTaskAccessor.java` (added) —
  chunk compile queue as two heaps ordered (distance, insertion), vanilla's exact pick without the O(n) scan per poll
  (13.8% of CPU while loading with a pack); verified 140k polls / 0 mismatches; `-Dcadence.fastCompileQueue=false|verify`.
- `cadence/mixin/MinecraftDisconnectMixin.java` (added) — leaving a world drops queued compile tasks so the integrated
  server's save isn't starved (vanilla hung 5+ min with a pack's backlog); `-Dcadence.dropCompileOnLeave=false`.
- `cadence/shaderpack/QualityHint.java` (added) — one chat hint per launch when Auto is pinned at 50% and GPU-bound.
- `CadenceSelfTest` — `receiver=`, `tp=` steps; chunk build timing per step.
- `cadence/shadow/ShadowCompileCull.java`, `cadence/render/ReceiverDepth.java` (added), `ShadowRenderer.extractionSections` — with a
  pack, only shadow casters that can shadow a receiver (camera-visible sections, open air in view) are compiled;
  `-Dcadence.shadowCompileCull=false` compiles every light-view section as upstream.
- `cadence/memory/HeapTrim.java` (added), `ShaderLoaderMod` — runtime G1 tuning when -Xmx > 2.5 GB on <= 16 GB RAM;
  `-Dcadence.heapTrim=false`.
- `cadence/memory/MemoryProbe.java`, `cadence/render/LightReach.java` (added) — diagnostics (chunk buffer heaps, geometry census).
- `metal/.../GpuMemory.java` (added) — Metal device allocated bytes for diagnostics.
- `tools/cadence-guard.sh` — `CADENCE_GUARD_MAXRSS`; `tools/cadence-test.sh` — waits for an earlier dev game,
  `CADENCE_WORLD_SNAPSHOT`, `CADENCE_VSYNC`.
- `cadence/render/ChunkHeaps.java`, `cadence/mixin/ChunkHeapSizeMixin.java` (added), `ChunkBufferRebuild` — chunk uber-buffer
  heaps of 32 MB / 8 MB while a pack is active (vanilla's 128 / 32 MB otherwise); `-Dcadence.chunkHeapMB=N`.
- `TerrainShaderGeometry.beginBlock/currentBlock` — pack block metadata resolved at the block's first emitted vertex
  instead of for every block (air included): F3+A with BSL at RD 16 ~35% faster. `BlockMaterialMap` — fastutil map.
- `ShaderSwitcher`, `TerrainShaderGeometry.geometryDifference`, `BlockMaterialMap.differingBlockIds` — a reload that
  leaves layout, AO mode and every block's pack id unchanged keeps the compiled chunks; `-Dcadence.keepChunksOnReload=false`.
- `TerrainShaderGeometry.isEnabledForThread`, `TerrainChunkLayerMixin` — inside a section compile the section's own
  snapshot (pack or no pack) decides vertex format and pipeline (fixes a crash when switching during a rebuild).
- `RenderScale` — fixed scales down to 0.33, Auto floor `autoMin=`; stutter statistics for the self-test.
- `metal/src/main/native/mcfx.m`, `metal/.../MetalFxUpscaler.java` (added), `Native.java`, `metal/build.gradle` (links
  MetalFX) — MetalFX spatial upscaling of the pack frame; `ShaderRuntime` uses it when `upscaler=metalfx` (default), else
  or on failure `PackUpscale`. `ShaderToggle.upscalerButton` + Video Settings row.
- `ShadowCullingEligibility.presetDifference` — receiver culling also for BSL's quality presets (only shadow features
  switched off, AO, and shadow resolution/distance with a texel of at most 1/3 block); any other option change still
  refuses.

### 2026-10-12 (day 2: entities, particles, shadows, memory, world generation) — all Cadence's own code
- `cadence/render/EntityCull.java`, `cadence/mixin/EntityCullMixin.java`, `EntityRendererCullInvoker`, `ParticleCullMixin`
  (added); `ShadowRenderer.UnionFrustum/casterTest`, `FeatureDrawContext` (mainOnly tag, per-pass block entity tags),
  `BlockEntityFeatureTagMixin` — entities/particles behind walls (vanilla's camera search) not drawn; with a pack each
  entity/block entity drawn only in the passes that can see it; `-Dcadence.entityCull=false`.
- `cadence/shadow/ShadowReuse.java` (added), `ShaderRuntime.beginFrame/skipShadow`, `PackRenderTargets.beginFrame(fog, clear)`,
  `ShadowRenderer.render` — shadow map drawn every Nth frame (config `shadowRate=`, default 2), reused with
  camera-shifted matrices in between; Video Settings "Shadow Updates".
- `cadence/render/ParticlePhysics.java`, `mixin/ParticleMoveMixin.java` (added) — particles skip the collision search when
  vanilla's own cell rule finds no candidate block; `-Dcadence.particlePhysics=false`.
- `mixin/PalettedContainerLockMixin.java` (added) — one field instead of a ThreadingDetector (5 objects) per chunk
  section container, same crash on concurrent writes; `-Dcadence.sectionLocks=vanilla`.
- `cadence/worldgen/RegionScheduler.java`, `ParallelWorldgen.java`, `mixin/ChunkMapParallelMixin.java`,
  `StructurePaletteCacheMixin.java`, `StructureStartPlaceMixin.java` (added) — FEATURES generation steps on the worker
  pool under per-region ordering; OFF by default (measured slower), `-Dcadence.parallelWorldgen=true`.
- `mixin/ChunkLoadSlotsMixin.java` (added) — `-Dcadence.chunkLoadSlots=N` overrides vanilla's 4 player chunk loads in
  flight; unset = vanilla (16 measured worse).
- `CadenceSelfTest` / `BusyScene` — steps scene=busy, entitycull=, shadowrate=, glide=S[:D], cmd=, gc, particlephysics=,
  genarea=CX:CZ:R (+ per-chunk content hashes).

### 2026-10-12 evening (day 3; tested since, see dev-log/DAY2.md)
- `cadence/shadow/ShadowProxies.java`, `mixin/ChunkSectionsToRenderAccessor`, `mixin/SectionDirtyProxyMixin` (added),
  `ShadowRenderer.render` (far columns drawn as proxies), `ShadowCompileCull` (far shadow-only sections not compiled),
  `ShaderToggle.configValue` public — far shadow height-field proxies (config `shadowProxyDistance=`, 0 = off).
- `cadence/memory/UnihexCompact.java`, `mixin/UnihexCompactMixin.java` (added) — the unifont's glyphs packed in one array;
  `-Dcadence.compactUnifont=false`.
- `dev-log/DESIGN-DAY3.md` (added) — designs: proxies, GPU occlusion, worldgen maths, memory trims, chunk loading.
- `cadence/facecull/` (FaceGroups, FaceCull, FaceGroupsHolder) + mixins `FaceGroupsResultsMixin`, `FaceGroupsMeshMixin`,
  `FaceGroupsCompileMixin`, `FaceGroupsDrawMixin` — opaque/cutout section layers reordered into 7 facing groups at build
  time; only groups that can face the viewer (camera, or the sun for the shadow pass) are drawn; `-Dcadence.faceCull=false`.
- `metal/src/main/native/mccull.m` (added), `metal/.../GpuCull.java` (added), `Native.java`, `metal/build.gradle`;
  `cadence/hiz/` (HiZ, DrawIndirectAccess) + mixins `HiZDrawIndirectMixin`, `HiZRenderGroupMixin`, `HiZPrepareMixin`;
  `ShaderRenderPass.depthTexture()` — two-phase GPU occlusion culling of the pack's terrain pass; `-Dcadence.hiz=false`.
- `PackRenderTargets.shadowColorAttached`, `PackPipeline`, `ShaderRuntime` — shadow color targets no program samples are
  left unattached (never slot 0).
- `cadence/render/OcclusionEstimate.java` (added) — diagnostic.
- `cadence/memory/HeapTrim.java` — SoftMaxHeapSize follows the live heap every 30 s; `cadence/memory/NativeTrim.java` (added) —
  jemalloc purge every 30 s; `-Dcadence.nativeTrim=false`.
- `metal/.../GpuMemory.census`, `MetalTexture`, `MetalBuffer`, `MetalDevice` — live texture/buffer census (diagnostics).
- `MetalEncoder` — encoder log labels carry the pass name.
- `sceneoptimizer/SpatialSceneProvider`, `DirtySectionMembership` (version counter), `scene/SceneExtractionRequest`
  (excludeExtra), `ShadowRenderer.extractionSections`, `ShadowCompileCull` — shadow dirty-section extraction: primitive
  iteration, caller exclusion, cached candidates (verified in self-tests).
- `ShaderRuntime` (memoized mipmap buffers), `PackPrograms.sequence`, `PackTextures.custom`, `FrameUniforms`,
  `ReceiverDepth`, `EntityCull` — per-frame allocations removed.
- `compile/ShaderCompatibilityCompiler` — #version/#line/#extension strippers no longer quadratic on blank-line runs.
- `cadence/alloc/PackedDraws.java`, `PackedDrawList.java`, `mixin/PackedDrawsMixin.java` (added),
  `sceneoptimizer/mixin/LevelRendererDrawMetadataMixin`, `LevelRendererLayersMixin` (@Redirect) — chunk draw commands
  as pooled packed ints; `-Dcadence.packedDraws=false`.
- `CadenceSelfTest` — steps hiz=, facecull=, shadowproxy=, occlusion, ab=FEATURE:S, histo, packeddraws=;
  `tools/cadence-test.sh` — CADENCE_GC, CADENCE_ENV, stops an idle Gradle daemon.

### 2026-10-13 (round 2, tier 1-2)
- `cadence/render/WorldScale.java`, `mixin/GameRendererWorldScaleMixin`, `mixin/LevelRendererOutlineAccess` — World Resolution
  without shaders; `runtime/PackUpscale` (Fast bilinear mode), `RenderScale` (three upscalers, Auto ceiling, restart,
  freeze, saved auto target).
- `metal/src/main/native/mcsys.m`, `metal/.../MacSystem.java`, `Native.java`, `metal/build.gradle` (AppKit) — thermal
  state, Low Power Mode, memory pressure, thread QoS, display Hz.
- `cadence/system/` (HeatControl, MemoryGuard, SystemMonitor, ThreadRoles, Notices) + `mixin/FramerateCapMixin`,
  `ThreadRolesMixin`, `ThreadRolesWorkerMixin`; `HeapTrim.pressure`, `ChunkHeapCompactor.pressure`,
  `ShadowReuse` (heat minimum, suspend).
- `cadence/ui/CadenceSettings.java`, `ShaderToggle` (plain names/tooltips), `mixin/VideoSettingsShadersMixin` — Performance
  preset, Heat Control, status lines, Pack Speed-ups button.
- `cadence/render/WorldClosed.java`, `mixin/WorldClosedMixin`; `ShadowProxies.release`, `HiZ.release`,
  `ShadowRenderer.releaseWorld`, `FrameUniforms.forgetLevel`, `SpatialSceneProvider.invalidate` — leaving a world frees it.
- `facecull/FaceCull` — empty draw when every face turned away (crash fix).
- `ChunkHeaps` — 32 MB heaps without shaders too.
- `cadence/packcheck/PackCheck.java`, `mixin/PackCheckTextureMixin`; `PackPrograms.checkKey`,
  `ShadowRenderer.cullingFor`, `FrameUniforms` (frozen clock), `PackRenderTargets.clearHistory`,
  `ShadowProxies` (unbudgeted during a check), `ShaderRuntime` hooks — pack check for shadow receiver culling.
- `geometry/TerrainShaderGeometry`, `TerrainVertexWriter`, `compile/MinecraftVertexAdapter` — 44-byte compact layout.
- `metal/.../FrameHints.java` — frame-end depth discard switchable (stays off: no measured gain).
- `CadenceSelfTest` — thermal=, pressure=, screen=, leave, join=, packcheck, ab frame time without a pack.

### 2026-10-13 night (overnight performance and testing cycle; details and measurements in dev-log/DAY2.md)
- `cadence/mesh/MeshStateCache`, `mixin/MeshStateCacheMixin` — chunk meshing reads each block state of the section and a
  one-block border once per build (RenderSectionRegion is an immutable copy); `-Dcadence.meshCache=false`.
- `cadence/mesh/MeshLightCache`, `mixin/MeshLightCacheMixin` — per-build flat light/shade arrays in place of vanilla's
  100-entry LRU maps during meshing; `-Dcadence.meshLight=false`.
- `cadence/mesh/MeshTintCache`, `mixin/MeshTintCacheMixin` — biome blend lookups once per position per build;
  `-Dcadence.meshTint=false`. Together ~38% less meshing CPU without shaders, ~33% with BSL; exact (verify mode
  `-Dcadence.meshCacheVerify` re-reads every cached value).
- `mixin/CompileTaskReleaseMixin` — a finished compile task drops its region copy (vanilla kept ~70 KB per compiled
  section; ~120 MB live heap after exploring); `-Dcadence.releaseRegions=false`.
- `mixin/MeshTimingMixin`, `mixin/DrawListTimingMixin` — section being meshed for the caches; self-test timings.
- `facecull/FaceGroups.pack`, `FaceGroupsHolder.cadence$facePacked`, `FaceCull.addPacked(int[]...)`,
  `mixin/FaceGroupsMeshMixin`, `mixin/FaceGroupsDrawMixin` — face groups read from one array per mesh in the per-frame
  draw list (-6%); `-Dcadence.faceCullPacked=false`.
- `cadence/system/BackgroundFps`, `FramerateCapMixin`, `CadenceSettings.backgroundButton`, `VideoSettingsShadersMixin`,
  `RenderScale` (Auto holds) — "In Background: 15 fps" setting (default on).
- `CadenceSettings` — Pack Speed-ups label "No World" / "No Pack".
- `CadenceSelfTest` — meshcache=, meshlight=, meshtint=, facepacked=, releaseregions=, meshreport=, wait=, reload,
  background=; `tools/icb_bench.m`, `tools/mfx_bench.m` (tier 3 evaluation benches).

### 2026-10-14 (shader packs: near-universal Iris/OptiFine support; details in SHADER-PACKS.md)
- `metal/src/main/native/mcpack.m` (added) — compute pipelines (MSL 3.1), storage textures and buffers, clears, compute
  encoder, binding pack storage in render passes. `metal/build.gradle` — compiles it.
- `metal/.../MetalCompute.java`, `MetalStorage.java` (added) — compute kernels and the pack's set-1 resources (Iris custom
  images, buffer objects, 3D textures) at fixed Metal slots.
- `mcmetal.m` — `mc_library_new_mode` compiles MSL 3.1 when asked (texture atomics). `MetalPipeline.java` — pack
  pipelines with storage bind set 1, MSL 3.1, storage in the cache key. `MetalRenderPass.java` — binds a pipeline's storage.
- `shaders/` — compute programs (setup/begin/prepare/deferred/composite/shadowcomp, `_a`..`_z`), images and buffer
  objects (`PackStorage`, `PackCompute`, `ShaderSpirvReflectionMixin`), preprocessor conditionals as Iris evaluates them
  (`compile/Conditionals`), transparent default clears, feedback snapshots for programs that read what they draw into,
  `blend.*` overrides (`PackBlend`), `clouds=off` and `sun`/`moon`/`stars` flags (`ShaderSkyFlagsMixin`,
  `ShaderCloudFlagMixin`), OptiFine profile syntax and lenient option values, mip chains remade only when stale, Relaxed
  math by default for pack programs, debug tools (`skipprograms=`, depth views, `-Dcadence.shaders.logPipelines`).
- `tools/bundle.py`, `tools/release` — the release jar nests the shader-pack loader too.
