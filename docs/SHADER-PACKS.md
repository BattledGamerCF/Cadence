# Shader packs beyond BSL (2026-10-14)

Goal: run as many OptiFine/Iris-format packs as possible natively on Metal, and run them fast. Translations are kept, so turning shaders off and on, or relaunching, does not translate a pack again.

## How a pack gets to the GPU, and what is cached

| Step | Where | Cached |
|---|---|---|
| Pack text: includes, option values | ShaderPack.source | no (cheap) |
| Translation: preprocess, lower OptiFine GLSL to the game's GLSL | ShaderCompatibilityCompiler | TranslationCache: memory (48 MB) and disk `cadence-cache/translated/` (256 MB, LRU) |
| GLSL to SPIR-V (the game's GlslCompiler) | ShaderGlslCompilerMixin | SpirvCache: disk `cadence-cache/spirv/` |
| SPIR-V to Metal source (SPIRV-Cross) | MetalPipeline | MSL cache: disk `cadence-cache/msl/` (existed before) |
| Metal source to GPU code | Metal | macOS's own shader cache |

Cache keys contain everything the output depends on, including a fingerprint of the translator's class files. A Cadence update therefore never reuses an old translation. Programs are also translated in parallel, with one shader compiler per worker.

Measured on an M2 Air:

- BSL's programs take 1,804 ms the first time, 207 ms from disk on the next launch, and 58 ms from memory when shaders are turned off and on in one session.
- Complementary takes 8,739 ms the first time and 1,317 ms from disk.

## Tools

- `tools/pack-census.sh [--profiles] [packs...]` lists every blocker of every pack in every dimension without starting the game. It runs the loader's own discovery and translation, recording refusals instead of throwing them. It then runs the game's GLSL to SPIR-V compile of each program. It also reports the uniforms each pack reads that Cadence does not supply. These are notes, not blockers. `CENSUS_JVM=-Dcadence.census.dump=DIR` writes every translated stage for reading.
- Self-test steps:
  - `option=NAME=VALUE` sets a pack option, then reloads.
  - `debugbuffer=N` makes the final pass show colortexN.
- `-Dcadence.shaders.strictUniforms=true` restores the loader's old rule, which refuses uniforms Cadence does not supply.

## What changed in the loader

All changes are in `shaders/` unless noted.

Robustness:

- A pack that fails while drawing turns shaders off at the start of the next frame and shows a chat message. Before, it crashed the game.
- A draw type the GPU compiler refuses is skipped.
- Screen passes and draws that write nothing are skipped.

Pack format:

- Legacy dimension folder fallback (Sildur's overworld lives at the root).
- Only the program names OptiFine/Iris run.
- Options in any #included file (Sildur's shaders.settings).
- Conflicting option declarations are made ambiguous instead of fatal.
- Macro redefinition, unclosed #if, `program.*.enabled`, `size.buffer`.
- shadowcolor Format/Clear/ClearColor/Mipmap/Nearest, and all OptiFine colour formats.
- vec2/vec3/vec4 custom uniforms (component-wise), matrix elements and legacy biome names in custom uniforms.
- Lenient block/item/entity lists and OptiFine alpha-test spellings.
- A synthesized final pass (colortex0) when a pack has none.

GLSL:

- Uniform initializers.
- Unsupplied uniforms read zero, as in OpenGL.
- Modern in/out, and gl_Fog with gl_FogFragCoord.
- Iris core-profile names (vaPosition, modelViewMatrix, ...).
- Functions that newer GLSL made built-in (fma, tanh), and old names (texelFetch2D).
- `sampler` and `bias` as identifiers.
- Outputs past DRAWBUFFERS are dropped.
- Missing vertex inputs get OpenGL's values, and a pack's declared input size is respected.
- Every vertex output starts at zero.
- Iris/OptiFine macros (MC_NORMAL_MAP, MC_RENDER_QUALITY, ...).

Uniforms:

- The full Iris/OptiFine list: player status, is_* flags, biome category/precipitation/rainfall, dimension, fog, date and time, camera parts, CAT_*/PPT_* constants.
- A first frame lasts 1/60 s. A length of 0 put a NaN into Sildur 2.02's TAA history, which gave a black picture forever.

Metal (`metal/`):

- A pass with more than 16 samplers compiles the samplers that are always sampled alike as constexpr samplers, which take no slot.
- 154 uniform slots per pass.
- Bindings are reset when a pipeline's slot layout differs.

## Status of the user's packs (10-14 evening, 0.2.0)

Conditions: overworld, default options, M2 Air, Auto resolution.

All 12 packs pass the offline census in every dimension and every profile (201 combinations, Metal compile included). Frame rates were measured in the dev client at 960x540 on the fanless Air, warm, with Auto resolution holding 60 fps.

| Pack | Result | fps (GPU ms) | before this round |
|---|---|---|---|
| BSL 10.1.8 | correct | 53 (15.7) | 56 (13.4, then 21 safe math) |
| VanillaEX 1.2.0 | correct | 60 (8.5) | 56 |
| MakeUp UltraFast 9.5g | correct | 58 (13.4) | 42 |
| I Like Vanilla 1.3.3b | correct (its own vanilla-style clouds) | 57 (10.9) | 25 |
| Insanity 1.650 | correct (dark horror look) | 60 (10.4) | 30 |
| Sildur's Enhanced Default 1.20 | correct | 60 (9.8) | 46 |
| Sildur's Vibrant 1.541 | correct | 60 (16.1) | 43 |
| Sildur's Vibrant 2.02 | correct (water streaks fixed) | 58 (16.8) | 31-39, black streaks |
| Complementary Unbound 5.9.3 | correct | 49 (16.5) | 43 |
| Complementary Reimagined 5.9.3 | correct | 40 (16.1) | 32 |
| Bliss 2.1.2 | correct (sky fixed; night/underwater haze fixed 10-14 night) | 33 (20.3) | 10, wrong sky |
| Pegasus 0.3.7 | correct since 10-15 (halos, night speckles fixed; rain blocky, likely its own); see the 10-15 section | 24 (41) at 1080p Auto; Medium 36 (28) | refused |

## What this round fixed

Every pack benefits from these changes.

- Clear colour. JOML's `new Vector4f()` is (0,0,0,1), so the default clear of colortex2+ had alpha 1 where OptiFine/Iris use 0. Packs that test an alpha nothing wrote saw "covered" pixels. This caused Pegasus's black sky (its water mask) and Bliss's wrong sky.
- Feedback reads. A world program that samples a buffer it also draws into now reads a copy made once per phase. Sildur 2.02's water reads colortex4 for reflections while writing it, and Pegasus's water reads its own depth. On Apple's tile GPUs, reading an attachment of the open pass is undefined, which caused Sildur's black streaks.
- Relaxed math for every pack's colour and compute programs. Metal's Relaxed mode still honours Inf/NaN and matches what OpenGL drivers give GLSL. Bliss is 2.2x faster on the GPU and BSL/Complementary are 1.5x faster, with alike pictures. `-Dcadence.shaders.relaxedFragmentMath=false` turns it off.
- Mipmaps. A chain is remade only when its buffer changed since the last remake. Pegasus asked for mipmaps 9 times a frame, which meant 81 mip passes. World programs now get mipmaps once a frame, only for buffers they read.
- `blend.<program>[.<buffer>]` overrides (Pegasus turns blending off for its normal/material buffers).
- Render flags: `clouds=off` (for packs that draw their own: Complementary, MakeUp, Sildur 2.02, ...), `sun`, `moon`, `stars`.
- Profiles. OptiFine's `OPTION:value` syntax is supported, because Pegasus's profiles were unusable without it. Unknown options and values a pack does not list are tolerated, as OptiFine/Iris do. An older pack's saved settings no longer cause the pack to be refused.
- Alpha test. Opaque draws that the game does not alpha-test (solid terrain) are not tested unless the pack asks, as in Iris.
- Compute and storage (earlier the same day): compute passes in every Iris stage, custom images (2D/3D), buffer objects, image load/store and atomics, and `#if` with decimals (`compile/Conditionals`).
- The quality hint only offers a profile whose name says it is a low tier. Before, Bliss's "SHADER_VERSION_LABEL" was offered.
- The release jar now nests the shader-pack loader (`tools/bundle.py --shaders`).

## Open issues

- ~~Bliss at full night: olive haze~~ Fixed 10-14 night. A custom texture standing in for colortexN (`texture.composite.colortex6 = blueNoise.png`) now applies only until a pass of the frame has written colortexN, as in Iris. Bliss's bloom, drawn into colortex6 later in the chain, was showing its blue noise. This also fixed its daytime milkiness.
- ~~Pegasus: magenta/red/blue halo at the waterline, yellow blocks on night water~~ Fixed 10-15 (see below). Its rain draws as wide blocky streaks. Its 3D-texture code runs for rain and reads mc_midTexCoord, which a weather draw does not have (OpenGL's default is (0,0,0,1), and Iris gives the same). This is most likely how Pegasus looks on Iris too, but it is not verified.
- Final sweep 10-14 night (day, night, rain, underwater, Nether, End, across 12 packs): all other packs were correct.
- The first load of a pack with a cold cache translates and compiles for several seconds. This happens on a new install's first use. The other dimensions are translated in the background afterwards.

## Overnight 10-14/15: motion glitches

The user reported coastline glitching, soft water and grass, and black dots.

Found and fixed:

- Auto resolution threw away every pack's history.
  - Auto moved the render size every 3-5 s, and each move remade the pack's targets. TAA packs then showed their raw, unconverged image for the next frames. The symptoms were black blobs along coastline reflections, speckled and noisy water and foliage, and softness while the image converged again.
  - The history buffers (Clear=false) are now carried over and rescaled.
  - A resize no longer resets the pack's smoothed uniforms.
  - Auto backs off rising when its rises keep being undone, so there are fewer resizes.
  - Measured on Sildur 2.02 right after a resize: dark speck pixels 79/29 -> 15/4.
- Full-screen passes drew one clip-space triangle, but Iris draws a 0..1 quad with an orthographic projection. Packs that shrink the quad in the vertex shader (lower-resolution passes such as Bliss's bloom tiles) got a triangle.
- Custom textures for colortexN (`texture.composite.colortex6 = blueNoise.png`) now stand in only until a pass of the frame has written colortexN, as in Iris. Bliss's bloom was showing its blue noise. This was the olive grain at night and underwater, and part of its daytime milkiness.

Checked and fine:

- NaN/Inf in the main buffers: none (new `debugbuffer=nanN` view).
- gl_FragCoord, uv and texelFetch row origin: matches OpenGL (test pack zz-CadenceTest-coords).
- Previous-frame matrices: set once per frame, with bobbing in the model-view as in Iris.
- Atlas sampling: vanilla's mipmapped sampler, nearest magnification.
- Rain, underwater, night.

Other results:

- Pictures are still soft at 50% resolution. That is the resolution itself, and MetalFX spatial upscale is already the default. Raising Auto's 50% floor would trade frame rate for sharpness. That is a user choice and was not changed.
- Mip chains now use one blit (generateMipmaps) per chain. There is no measurable GPU change, but there are fewer passes.
- Test tools: self-test `burst=N:MS[:SCALE]`, `spin=S:DEG`, `uniforms=a+b`, `debugbuffer=nanN`. `tools/cadence-test.sh` restores the world by checksum and refuses to start under 3 GB free. The disk filled once, with 19 GB of old results.

## 10-15 morning: Pegasus

The user reported neon pink/blue on distant blocks and an ugly sky.

- Uninitialized shader variables (every pack).
  - A GLSL local read before it is written is 0 on the desktop drivers packs are written for, but register garbage in Metal.
  - Pegasus's composite11 declares `vec3 pre_reflection;`, sets it only on metals, and then takes `max(reflection, pre_reflection)` everywhere. This caused the red/blue/magenta waterline halos and the yellow blocks on night water.
  - SPIRV-Cross's FORCE_ZERO_INITIALIZED_VARIABLES now zero-fills every translated variable, in render and compute programs.
  - Pegasus's night water is now dark and reflective, and NaN views of colortex0/5/7 are clean.
- Wrong-type uniforms (as in Iris).
  - Pegasus declares `uniform float frameCounter`. Iris logs "Wrong uniform type" and disables such a uniform (0 or its initializer). OptiFine's glUniform1i fails the same way.
  - We converted the value, so Pegasus's noise moved every frame.
  - Int uniforms declared float are now left alone. `-Dcadence.shaders.convertWrongTypes=true` restores the old behaviour.
- Conservative depth (every pack that writes gl_FragDepth in world programs: Pegasus, Bliss's POM).
  - Such a shader turns off early depth rejection, so all hidden terrain was shaded.
  - World programs now declare `depth_greater` and keep the written depth at least the surface's, since parallax only pushes into the block.
  - Pegasus at 720p went from 61-68 ms to 49-54 ms GPU, with the same picture.
  - `-Dcadence.shaders.conservativeDepth=false` turns it off.
- Tried and dropped:
  - Precise sin/cos in fast-math stages (Pegasus hashes with fract(sin(big)*k)). Clouds barely changed and GPU time went from 60 to 200 ms.
  - Safe math for all of Pegasus. It gave magenta/cyan water, so relaxed is closer to GL drivers here.
- Where Pegasus's time goes (720p, full scale, before conservative depth):

  | Part | GPU ms |
  |---|---|
  | Clouds, composite1 (1/3 res, ~19,000 sin per pixel) | 19 |
  | Terrain | 15 |
  | Water | 12 |
  | Shadow | 7 |
  | Sky map, composite2 | 4.5 |
  | composite10/11 | 2-3 each |
  | Compute | ~0 |

  Its Medium profile looks nearly the same at 60% of the cost (720p: 51 -> 31 ms; 1080p Auto: 24 -> 36 fps).
- The quality hint now names the gentlest lower preset first ("Medium is faster, Potato_Shadows fastest").
- Tests:
  - `cadence-test.sh` passes `-Dcadence.backgroundFps=false`. An unfocused test window had been capped at ~15 fps, and its GPU reading froze.
  - Screenshots are kept as 1280 px JPEGs (`CADENCE_KEEP_PNG=1` for PNGs).
  - Old results: 19 GB of PNGs were deleted. The 10-14 final sweep is kept as JPEGs in metal/run/keepers.

## Debugging tools added

- Self-test `uniforms=a+b`: current values, including the pack's custom uniforms.
- Self-test `debugbuffer=N`, `Na` (alpha) and `depthtexN` (red where depth is 1).
- Self-test `skipprograms=a+b`: those programs (world, screen or compute) draw nothing.
- `-Dcadence.shaders.logPipelines=true`: game pipeline -> pack program, with defines, depth and blend.
- The census dump writes each program's draw buffers and the buffer specs.
- `-Dcadence.metal.trace=N`: frame N's render passes with attachments and load/store actions. This found nothing wrong in the passes, which pointed at the clear value.

## 10-18: thirteen more packs (0.3.3)

Selection: packs were picked for compatibility, not looks. They are the most-downloaded Iris packs on Modrinth that the 12 above do not cover. They span old and new (Potato Shaders is from 2023, Solas and Noble from this autumn) and small and huge (Miniature 44 KB, Noble 17 MB).

Method: the packs were downloaded from Modrinth and checked against its SHA-512 hashes. They are in `metal/run/shaderpacks`. AstraLex was renamed, because its file name is full of `§` colour codes. Every pack got an offline census, then a self-test sweep (day, the same view), and the pictures were checked.

Result: 11 of 13 load and look right. 23 of the 25 tested packs now work.

GPU time is at a fixed 50% World Resolution (`-Dcadence.shaders.renderScale=0.5`, 1470x843 pixels). Anchors in the same runs: VanillaEX 3.8 ms, BSL 13.0, Bliss 18.5. The fanless Air slows after about two minutes of full load, so a pack measured late in a sweep reads high. Vanilla Plus took 31 ms late in one sweep and 12.4 ms in a short one. Such readings are marked "late".

| Pack | Version | Result | GPU ms at 50% | Weight shown | What it needed |
|---|---|---|---|---|---|
| Photon | 1.3b | correct (sky, clouds, weather) | 21.2 | Heavy | 3D textures bound (sky was black, then sepia), struct varyings, shared vec3 arrays split |
| Solas | 3.8 | correct | 21.4 | Heavy | several `image3D`s in one declaration |
| Kappa | 5.3 | correct | 38.2 (late) | Heavy | nothing pack-specific |
| Nostalgia | 5.1 | correct | 21.4 (late) | Heavy | `gcolor` is the block texture in world programs (blocks were flat colours) |
| AstraLex | 93.0 | correct | 26.1 (late) | Heavy | SNORM mip formats |
| Noble | 1.9.9 | loads; shaded sides darker than intended | 32.3 (late) | Heavy | integer colour outputs, `type[N] name` varyings |
| Super Duper Vanilla | 1.3.8 | correct | 9.6 | Medium | nothing pack-specific |
| Mellow | 3.4.1a | correct | 4.8 | Light | names clashing with vertex inputs (`Normal`, `Color`), R3_G3_B2, `minecraft:` textures, Voxy's sampler |
| Miniature | 2.19 | correct | 8.5, 7.9 | Light | defines before a leading `#if`, a vanilla program for draws it has no program for |
| Vanilla Plus | 3.3 | correct | 12.5, 12.4 | Medium | `gcolor` (as Nostalgia) |
| Potato Shaders | 1.0d | correct | 13.3 (late) | Medium | nothing pack-specific |
| Shrimple | 0.12 | not supported: geometry shaders | | | |
| Rethinking Voxels | r0.1-beta9 | not supported: geometry and tessellation shaders | | | |

Shrimple and Rethinking Voxels are refused, and the pack list gives the reason in plain words ("it needs geometry shaders, which Metal doesn't have"). Metal has no geometry stage. Emulating one with a compute pass that writes the expanded vertices is possible but large, and these are the only two of the 25 packs that need it.

What changed, for every pack:

- Raw and 3D custom textures (`texture.STAGE.NAME = file TEXTURE_3D RGBA8 ...`).
  - They are uploaded through a new 3D Metal texture (MetalVolume) and keyed by the sampler type the program declares, as in Iris.
  - `sampler3D` uniforms bind through set 1 (storage), so they were never bound before. This is why Photon's sky lookup read black.
  - Filtering and repeat follow the file's `.mcmeta`.
  - `minecraft:` paths read the game's own resources.
- `flip.PASS.BUFFER` directives and `shadowcomp` passes over the shadow map are supported. A constant declared twice keeps the later value, as in Iris.
- Translation:
  - Struct varyings are flattened into plain varyings, because Metal cannot pass a struct between stages as GLSL does.
  - `shared vec3` arrays are split into three float arrays when Metal's 16-byte float3 would push a compute pass over Apple's 32 KB of threadgroup memory (Photon's sky harmonics: 36 KB -> 27 KB).
  - Identifiers named like Metal built-ins (`length_squared`, `powr`, ...) or like the adapter's vertex inputs are renamed.
  - `type[N] name` declarations, several images in one declaration, and uniform defaults written `- 123` are handled.
  - Integer colour outputs are never blended and have no alpha test.
- `gcolor` means the block texture (unit 0) in world programs, as in OptiFine. It is colortex0 only in screen passes.
- A pack with no program for a draw type and nothing to fall back to gets a built-in vanilla program (texture x colour x lightmap) instead of failing.
- Samplers that belong to other mods (Voxy's `vxDepthTexTrans`, ...) bind a neutral texture: white for depth, black otherwise. This is logged once.
- Colour formats: R3_G3_B2, RGB4, RGB5, RGBA2, RGBA4, RGB5_A1 (as RGBA8), and RGB12/RGBA12 (as RGBA16). Mipmaps work for SNORM and 16-bit formats.
- Census: shadow samplers are checked as hardware comparisons. `textureLod` and `textureGather` on them were false blockers.

Open: Noble's shaded sides are darker than in its screenshots. The suspect is its sky-illuminance storage buffer, written in a compute pass, because the binding covers both stages. This is listed in the pack catalog, so the pack list says so.

### GPU pass (same day)

Kept: feedback copies only for samplers a program's code reads.

- When a world program samples a buffer it also draws into, Cadence copies the buffer first, because Metal cannot read and write the same image in a pass.
- Complementary declares colortex0/3/4/6 and depthtex0 in its world programs and reads few of them. It made 12 copies a frame and now makes 2.
- Complementary Reimagined at 2940x1686 went from 44 to 41 ms (-2.6 and -2.8 ms in two in-run A/Bs, `ab=feedbackreads`).
- BSL changed by +0.04 ms and Photon by +0.05 ms, because they have no unread declarations.
- Pictures are alike for Complementary, BSL, Photon, Bliss, and the 11-pack sweep.
- `-Dcadence.shaders.logReads=true` lists each program's read samplers.

Measured and dropped:

- Flushing pass clears that a pack never relies on (learned per frame): +0.10 / +0.18 ms, so slower.
- Relaxed math for shadow vertex shaders: about 2%, inside the noise.
- The same read analysis for the per-frame decisions (shadowtex1 copy, depthtex2 copy, shadow-colour attachment, hand depth merge). It gave no gain where measured, since Complementary does read shadowtex1. AstraLex turned almost white, so a use the analysis cannot see feeds one of those decisions. Not kept.

## Not supported yet

- PBR resource packs.
  - The `normals` and `specular` samplers are constants (a flat normal, black). A pack's labPBR / seuspbr "resource pack required" modes therefore see no material maps (bug hunt 10-21, BH-22).
  - The missing piece is reading `_n` / `_s` into material atlases laid out like the block atlas, as Iris does.
- Shader packs on the OpenGL fallback (`cadence.metal=false`) are refused with a message, because the translated programs are Metal-only.
- 1D images.
- Geometry and tessellation stages (Shrimple and Rethinking Voxels need them).
- Distant Horizons programs (dh_*).
- A cold first load of a big pack translates for 2-7 s. Later loads take 0.1-0.4 s from the cache.

`colorimgN` / `shadowcolorimgN` (programs storing straight into colour targets) work. Those targets, and only those, are made with shader-write usage. None of the 12 packs uses them. `metal/run/shaderpacks/zz-CadenceTest-colorimg` is a four-program test pack whose compute pass halves colortex0's green through colorimg0. A purple world means it is working.
