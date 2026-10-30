# How Cadence is put together

A map of the source tree for anyone reading the code for the first time. Start here, then open the package you need: each
Cadence package begins with a short description (package-info.java or the first class's doc comment).

## The four mods

The release jar (`tools/release`) nests four Fabric mods built by Gradle from four modules:

| Module | Mod id | What it is |
|---|---|---|
| `metal/` | `cadence` | The Metal renderer and everything that runs below Minecraft's rendering: the backend, the native library, the fast terrain path, game-logic optimizations, startup and Java settings. Forked from mcopt (Apache-2.0). |
| `shaders/` | `cadence_shaders` | Shader packs on Metal (forked from the MIT Shader Loader), and Cadence's own features that need the game's screens and settings: Video Settings, Auto resolution, cooling, the benchmark, the GC policy. |
| `fpshud/` | `cadence_hud` | The frame-rate counter in the corner. |
| `irisapi/` | `cadence_iris_api` | Iris's public API (`net.irisshaders.iris.api.v0`) declared again and answered from Cadence (`dev.cadence.irisapi.CadenceIrisApi`, reached by name, so it builds without the other modules). It doesn't claim the id `iris`; the optional provider jar (`packaging/iris-provider`, made by `tools/release`) does, and `Profile.realIris` tells it from a real Iris by its `+cadence` version. |

`metal` never depends on `shaders`; `shaders` uses `metal` (MacSystem, Native, the backend's hooks).

## metal/ (dev.cadence.metal)

| Where | What |
|---|---|
| `dev.cadence.metal` (root) | The Metal backend: Minecraft's `renderpearl` device/encoder/buffer/texture API implemented on Metal (`MetalDevice`, `MetalEncoder`, `MetalRenderPass`, `MetalBuffer`, `MetalTexture`, `MetalSurface`), `Native` (every native call, through Java's foreign-function API), `MacSystem` (thermal state, battery, fans, display, sysctl), `Profile` and `JvmDefaults` (startup settings), `Vivace` (restarting on the Vivace Java build when the player chose it), `GameMode` (the game started from a bundle macOS treats as a game), `PlatformCheck` (Apple Silicon, macOS, Rosetta), `Stalls` (the stall recorder: a frame over 50 ms logged with its cause, from `FrameLog`'s per-frame counters and a watchdog thread's samples of the render thread). `MetalBackend` falls back to Minecraft's own renderer if Metal can't start. Kept as one package: the backend's classes share package-private state, as in mcopt. |
| `own/` | Cadence's own terrain renderer: chunk meshes in one arena, GPU culling (`OwnFrag`, Hi-Z; the opt-in Far Plants, `OwnTerrain.farPlantBlocks`, is a test in the cull), the opaque/cutout draw path, dynamic lights. |
| `lod/` | Far terrain (experimental, off by default). GPU code: `src/main/resources/cadence/lod/columns.metal`. |
| `record/` | The Minimal Renderer: the compute-only renderer for the FPS record (`Record`, `RecordWorld`, `RecordHud`); GPU code `src/main/resources/cadence/record/record.metal`, native `mcrecord.m`, offline bench `tools/record_bench.m` (docs/RENDER-MAX-FPS.md). |
| `chunk/`, `chunkio/` | Opt-in chunk pipeline and save-file changes (each behind a `-Dcadence.chunk.*` switch). |
| `logic/` | Cadence Logic: the game-logic optimizations that replace Lithium (see docs/GAME-LOGIC.md). |
| `cpu/` | Render-thread CPU work: model and item vertex writers, text, A/B timers. |
| `compat/` | Compatibility layers: where another mod hooks code Cadence replaces, Minecraft's own code runs there. |
| `alloc/` | Allocation-free data structures used by the hot paths, and entity render states kept between frames (`EntityStates`). |
| `framegen/` | Frame Generation (`FrameGen`): per real frame a snapshot of the world, between real frames one more made by reprojection (`src/main/resources/cadence/framegen/framegen.metal`, native `mcfg.m`); Smooth and Responsive. |
| `audio/` | The Apple sound engine (`AppleAudio`): the game's sound on AVAudioEngine instead of OpenAL, native `mcaudio.m`; the hooks on the game's audio classes are in `mixin/audio/`. World Reverb (`WorldAcoustics`): reverb and occlusion from rays through the blocks; Sound Around Corners (`SoundPaths`): the way a sound takes round to the listener, through the air around them. |
| `grade/` | Biome Colours (`BiomeGrade`, `GradeNet`: the 15-16-7 network trained at first use; native `mcgrade.m`, one programmable-blending pass). |
| `rt/` | Ray Tracing (`RayTracing`): the voxel copy of the world near the camera (`RtWorld`), shadows, Torch Shadows (the light grid, `rt_torch`), Ray Traced Lighting's lighting cache and Neural Lighting's network (`rt_nrc_*`), path-traced photos (MetalFX's denoiser); GPU code `src/main/resources/cadence/rt/rt.metal`, native `mcrt.m` (also the acceleration structure for Metal's hardware ray tracing). |
| `neural/` | The Neural Engine features: Style Filters (`StyleFilter`; `StyleSpec` rewrites a trained network so all of it runs on the Neural Engine), Smooth Textures (`SmoothTextures`), and Cadence Helper (`Helper`, the small Swift app in `src/main/helper/` that trains styles with Create ML and recognises speech for Voice Control); native `mcneural.m` (Core ML and VideoToolbox, loaded only when used). `NeuralUpscaler` (root package) is World Resolution's Neural Engine upscale, with or without a shader pack, in tiles when the picture is larger than Apple's scaler takes. |
| root: `Hdr` | HDR: the frame's float targets, the EDR layer, the tone-mapped present (`mcmetal.m` present_hdr_fs); `own/OwnEmission` is the emissive blocks' mask. |
| `net/` | The native network path for servers (`FastNet`): `FastDecrypt` and `FastInflate` replace Minecraft's `CipherDecoder` and `CompressionDecoder` on remote connections (mixins in `mixin/net/`); native `mcnet.c` (zlib, and AES-128-CFB8 decryption on the ARMv8 cryptography instructions, 8 bytes at a time); `NetSelfTest` checks both against Minecraft's (self-test step `net`). |
| `rec/` | Instant Replay (`Replay`: the last 30 s kept as hardware-encoded video, saved on request) and an opt-in video recorder for development (`Recorder`); native `rec.m`. |
| `mixin/<area>/` | The mixins for each area above, one mixin config per area (`cadence-metal-<area>.mixins.json`), each with a plugin that decides whether it applies. Areas without a package of their own: `ownmesh` (own terrain's meshing), `framegen` (Frame Generation's camera hooks), `gen` (world-generation buffers and QoS), `qos` (thread priorities), `startup` (faster start), `edge` (the opt-in frame cap while terrain generates, `EdgeCap`). The mixins directly in `mixin/` are the backend's (`cadence-metal.mixins.json`). |
| `src/main/native/` | The Objective-C/C native library: `mcmetal.m` (the backend), `mcown.m` (own terrain), `mccull.m` (Hi-Z), `mcfx.m` (MetalFX), `mcsys.m` (system signals), `mcpack.m` (shader-pack helpers), `mcbench.m` (Quick Setup's GPU test), `mclod.m`/`lod*.c` (far terrain), `mcrecord.m` (the Minimal Renderer), `mcfg.m` (Frame Generation), `mcaudio.m` (the Apple sound engine and World Reverb), `mcrt.m` (ray tracing), `mcneural.m`
(the Neural Engine features), `mcm4.m` (the opt-in Metal 4 path), `mcgrade.m` (Biome Colours), `mcnet.c` (the network path for servers), `mcfg.m` (Frame Generation, with Neural's VideoToolbox interpolation), `rec.m` (Instant Replay, the recorder). Built by clang from Gradle. |
| `src/main/resources/cadence/` | Metal shaders: `own/terrain.metal`, `native/` (Minecraft's own shaders rewritten in Metal: `common.metal` plus one file per program), `lod/`, `rec/`. |

## shaders/ (dev.cadence.shaders, dev.cadence.scene, dev.cadence.sceneoptimizer)

| Where | What |
|---|---|
| `dev.cadence.shaders` | Mod entry (`ShaderLoaderMod`) and the gate that applies the shader-pack hooks only when they are needed (`CadencePackGate`). |
| `shaders/pack/` | Reading a shader pack: `shaders.properties`, options, profiles, programs. |
| `shaders/compile/` | Translating pack GLSL to Metal (GLSL → SPIR-V → MSL) and the translation caches. |
| `shaders/runtime/` | Running a pack: render targets, pipelines, uniforms, the frame's passes. |
| `shaders/geometry/` | The vertex data packs see (terrain, entities, items). |
| `shaders/mixin/` | Hooks into Minecraft's renderer for shader packs. |
| `scene/`, `sceneoptimizer/` | The Scene Optimizer port (from the Shader Loader). |
| **`shaders/cadence/`** | **Cadence's own features**, by area: |
| `cadence/ui/` | Video Settings rows and buttons (`CadenceSettings`, `AppleHardwareSettings` for the Apple hardware features' buttons, `LookSettings` for Biome Colours, `Tips` = every tooltip, with the performance impact line, `ShaderToggle` = the shader settings and the config file), the Sharpness & Speed page, the Shader Packs list, Shader Pack Settings, tooltips (`Tips`), keys O/K, the Mod Menu entry. |
| `cadence/render/` | Rendering features: Auto resolution and World Resolution (`RenderScale`, `WorldScale`), crisp text, dynamic lights, entity culling, the Minimal Renderer, the compile queue, lazy targets (outline, HUD, panorama), recovering from a failed frame, cleanup when a world closes. |
| `cadence/shaderpack/` | Switching packs live (`ShaderSwitcher`), the pack catalogue (tested/heavy), quality hints. |
| `cadence/shadow/` | Shader-pack shadows: every-2nd-frame reuse, shadow proxies, skipping casters that can't shadow anything. |
| `cadence/hiz/`, `cadence/facecull/`, `cadence/mesh/` | Occlusion (Hi-Z), face culling, chunk meshing caches and Smart Leaves. |
| `cadence/voice/` | Voice Control (`VoiceControl`): the phrases Cadence Helper heard, turned into game actions. |
| `cadence/server/` | Keep Server Chunks (`ServerChunks`): on a server with a shorter view distance, the chunks it forgets kept within the render distance, and stored on disk (`ServerChunkStore`: the server's own chunk packets in region files, handed back to the game's packet handler when you return; mixins in `cadence/mixin/server/`). |
| `cadence/system/` | The Mac: cooling (`HeatControl`), Battery Saver (`BatterySaver`: what saving power does, with and without a shader pack), On Battery, In Background, frame pacing, the memory guard, CPU core tiers, thread priorities, Cadence messages. |
| `cadence/memory/` | Memory: the GC policy (`GcPolicy`), heap and native trimming, chunk heap compaction. |
| `cadence/setup/` | Quick Setup (tests the Mac, recommends settings). |
| `cadence/benchmark/` | The Benchmark screen and run. |
| `cadence/packcheck/` | Faster Shadows' picture test for a pack. |
| `cadence/selftest/` | The development self-test driven by `tools/cadence-test.sh` (`-Dcadence.selftest=steps`). Not used in normal play. |
| `cadence/alloc/`, `cadence/worldgen/`, `cadence/census/` | Allocation fixes, worldgen experiments, the offline pack census. |
| `cadence/mixin/<area>/` | The mixins for all of the above, by area: `maxfps`, `chunk`, `memory`, `mesh`, `hiz`, `shadow`, `light`, `entity`, `render`, `server`, `ui`, `system`, `worldgen` (one config, `cadence-shaders-ui.mixins.json`; `CadenceMixinPlugin` decides which apply). |

## Settings and switches

- Player settings live in `config/cadence-shaders.properties` (Video Settings writes it, atomically) and
  `config/cadence.properties` (the profile and Java defaults).
- Every optimization has a `-Dcadence.<name>=false` switch, and most exact ones a `...Verify=true` mode that runs both paths
  and compares; the switch names are in each class's doc comment.

## Where to look next

- [FOR-REVIEWERS.md](FOR-REVIEWERS.md): for mod-platform reviewers: native code, network (none), files written, licences.
- [CONTRIBUTING.md](../CONTRIBUTING.md): building, the test harness, the rules for changes (exact or switch-gated, measured).
- [tools/README.md](../tools/README.md): the scripts.
- [MINIMAL-RENDERER.md](MINIMAL-RENDERER.md): the Minimal Renderer, its results and the record attempt.
- [CHANGELOG.md](../CHANGELOG.md): every change, with its measurements; older design notes are in the git history.
