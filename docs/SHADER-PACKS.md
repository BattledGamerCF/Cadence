# Shader packs beyond BSL (2026-10-14)

Goal: as many OptiFine/Iris-format packs as possible running natively on Metal, fast, with their translations kept so
turning shaders off and on (or relaunching) doesn't translate them again. Research: research/SHADERPACKS.md (one Haiku agent).

## How a pack gets to the GPU, and what is cached

| Step | Where | Cached |
|---|---|---|
| Pack text: includes, option values | ShaderPack.source | no (cheap) |
| Translation: preprocess, lower OptiFine GLSL to the game's GLSL | ShaderCompatibilityCompiler | **TranslationCache**: memory (48 MB) + disk `cadence-cache/translated/` (256 MB, LRU) |
| GLSL to SPIR-V (the game's GlslCompiler) | ShaderGlslCompilerMixin | **SpirvCache**: disk `cadence-cache/spirv/` |
| SPIR-V to Metal source (SPIRV-Cross) | MetalPipeline | MSL cache: disk `cadence-cache/msl/` (existed) |
| Metal source to GPU code | Metal | macOS's own shader cache |

Keys contain everything the output depends on, including a fingerprint of the translator's class files, so a Cadence
update never reuses an old translation. Programs are also translated in parallel (one shader compiler per worker).

Measured (M2 Air): BSL's programs 1,804 ms first time, 207 ms from disk on the next launch, 58 ms from memory when
turning shaders off and on in one session. Complementary 8,739 ms -> 1,317 ms from disk.

## Tools

- `tools/pack-census.sh [--profiles] [packs...]`: every blocker of every pack, every dimension, without starting the game:
  the loader's own discovery and translation with refusals recorded instead of thrown, then the game's GLSL -> SPIR-V
  compile of each program, and the uniforms each pack reads that Cadence doesn't supply (notes, not blockers).
  `CENSUS_JVM=-Dcadence.census.dump=DIR` writes every translated stage for reading.
- Self-test steps: `option=NAME=VALUE` (a pack option, then reload), `debugbuffer=N` (the final pass shows colortexN).
- `-Dcadence.shaders.strictUniforms=true`: the loader's old rule (refuse uniforms Cadence doesn't supply).

## What changed in the loader (all in shaders/ unless noted)

Robustness: a pack that fails while drawing turns shaders off at the start of the next frame with a chat message
(it used to crash the game); a draw type the GPU compiler refuses is skipped; screen passes and draws that write
nothing are skipped.

Pack format: legacy dimension folder fallback (Sildur's overworld lives at the root), only the program names
OptiFine/Iris run, options in any #included file (Sildur's shaders.settings), conflicting option declarations made
ambiguous instead of fatal, macro redefinition, unclosed #if, program.*.enabled, size.buffer, shadowcolor
Format/Clear/ClearColor/Mipmap/Nearest, all OptiFine colour formats, vec2/vec3/vec4 custom uniforms (component-wise),
matrix elements and legacy biome names in custom uniforms, lenient block/item/entity lists, OptiFine alpha-test
spellings, a synthesized final pass (colortex0) when a pack has none.

GLSL: uniform initializers, unsupplied uniforms read zero (as OpenGL), modern in/out, gl_Fog + gl_FogFragCoord,
Iris core-profile names (vaPosition, modelViewMatrix, ...), functions newer GLSL made built-in (fma, tanh), old names
(texelFetch2D), `sampler`/`bias` as identifiers, outputs past DRAWBUFFERS dropped, missing vertex inputs get OpenGL's
values and a pack's declared input size is respected, every vertex output starts at zero, Iris/OptiFine macros
(MC_NORMAL_MAP, MC_RENDER_QUALITY, ...).

Uniforms: the full Iris/OptiFine list (player status, is_* flags, biome category/precipitation/rainfall, dimension,
fog, date and time, camera parts, CAT_*/PPT_* constants); a first frame lasts 1/60 s (0 put a NaN into Sildur 2.02's TAA
history: a black picture forever).

Metal (metal/): a pass with more than 16 samplers compiles the ones that are always sampled alike as constexpr
samplers (no slot), 154 uniform slots per pass, bindings reset when a pipeline's slot layout differs.

## Status of the user's packs (overworld, default options, M2 Air, Auto resolution) — 10-14 evening, 0.2.0

All 12 pass the offline census in every dimension and every profile (201 combinations, Metal compile included).
Measured in the dev client at 960x540 (fanless Air, warm), Auto resolution holding 60 fps:

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
| Pegasus 0.3.7 | correct since 10-15 (halos, night speckles fixed; rain blocky, likely its own); see 10-15 section | 24 (41) at 1080p Auto; Medium 36 (28) | refused |

## What this round fixed (every pack benefits)

- **Clear colour**: JOML's `new Vector4f()` is (0,0,0,1); the default clear of colortex2+ had alpha 1 where OptiFine/Iris
  use 0. Packs that test an alpha nothing wrote saw "covered" pixels (Pegasus's water mask: black sky; Bliss's sky).
- **Feedback reads**: a world program sampling a buffer it draws into (Sildur 2.02's water reads colortex4 for
  reflections while writing it; Pegasus's water reads its own depth) reads a copy made once per phase. On Apple's tile
  GPUs reading an attachment of the open pass is undefined (Sildur's black streaks).
- **Relaxed math** for every pack's colour and compute programs (Metal's Relaxed: Inf/NaN still honoured; what OpenGL
  drivers give GLSL): Bliss 2.2x, BSL/Complementary 1.5x faster on the GPU, pictures alike.
  `-Dcadence.shaders.relaxedFragmentMath=false` turns it off.
- **Mipmaps**: a chain is remade only when its buffer changed since (Pegasus asked 9 times a frame: 81 mip passes), and
  world programs get them once a frame, only for buffers they read.
- **blend.<program>[.<buffer>]** overrides (Pegasus turns blending off for its normal/material buffers).
- **Render flags**: `clouds=off` (packs that draw their own: Complementary, MakeUp, Sildur 2.02, ...), `sun`, `moon`,
  `stars`.
- **Profiles**: OptiFine's `OPTION:value` syntax (Pegasus's profiles were unusable); unknown options and values a pack
  doesn't list are tolerated as OptiFine/Iris do (an older pack's saved settings no longer refuse the pack).
- **Alpha test**: opaque draws the game doesn't alpha-test (solid terrain) aren't tested unless the pack asks, as Iris.
- **Compute and storage** (earlier today): compute passes in every Iris stage, custom images (2D/3D), buffer objects,
  image load/store and atomics, `#if` with decimals (`compile/Conditionals`).
- Quality hint only offers a profile whose name says it is a low tier (Bliss's "SHADER_VERSION_LABEL" was offered).
- The release jar now nests the shader-pack loader (`tools/bundle.py --shaders`).

## Open issues

- ~~Bliss at full night: olive haze~~ FIXED 10-14 night: a custom texture standing in for colortexN
  (`texture.composite.colortex6 = blueNoise.png`) now applies only until a pass of the frame has written colortexN, as
  Iris; Bliss's bloom (drawn into colortex6 later in the chain) was its blue noise. Also its daytime milkiness.
- ~~**Pegasus**: magenta/red/blue halo at the waterline, yellow blocks on night water~~ FIXED 10-15 (see below). Its rain
  draws as wide blocky streaks: its 3D-texture code runs for rain and reads mc_midTexCoord, which a weather draw doesn't
  have (OpenGL's default (0,0,0,1), as Iris gives it), so this is most likely how Pegasus looks on Iris too; not verified.
- Final sweep 10-14 night (day, night, rain, underwater, Nether, End x 12 packs): all others correct.
- First load of a pack with a cold cache translates and compiles for several seconds (a new install's first use; the
  other dimensions are translated in the background afterwards).

## Overnight 10-14/15: motion glitches (user: coastline glitching, soft water/grass, black dots)

Research: research/GLITCHES.md and research/PERF2.md (Haiku agents; mostly reasoning, few sources). Found and fixed:

- **Auto resolution threw away every pack's history.** Auto moved the render size every 3-5 s; each move remade the
  pack's targets, so TAA packs showed their raw, unconverged image for the next frames: black blobs along coastline
  reflections, speckled/noisy water and foliage, softness while it converged again. Now the history buffers (Clear=false)
  are carried over, rescaled, a resize no longer resets the pack's smoothed uniforms, and Auto backs off rising when its
  rises keep being undone (fewer resizes). Measured on Sildur 2.02 right after a resize: dark speck pixels 79/29 -> 15/4.
- **Full-screen passes drew one clip-space triangle, Iris draws a 0..1 quad** with an orthographic projection. Packs that
  shrink the quad in the vertex shader (lower-resolution passes: Bliss's bloom tiles) got a triangle.
- **Custom textures for colortexN** (`texture.composite.colortex6 = blueNoise.png`) now stand in only until a pass of the
  frame has written colortexN, as Iris. Bliss's bloom was its blue noise: the olive grain at night/underwater and part of
  its daytime milkiness.
- Checked and fine: NaN/Inf in the main buffers (none; new `debugbuffer=nanN` view), gl_FragCoord/uv/texelFetch row
  origin (test pack zz-CadenceTest-coords: matches OpenGL), previous-frame matrices (set once per frame, bobbing in the
  model-view as Iris), atlas sampling (vanilla's mipmapped sampler, nearest magnification), rain, underwater, night.
- Still soft at 50% resolution: that is the resolution (MetalFX spatial upscale is already the default). Raising Auto's
  50% floor would trade frame rate for sharpness (a user choice, not changed).
- Mip chains now one blit (generateMipmaps) per chain: no measurable GPU change, fewer passes.
- Test tools: self-test `burst=N:MS[:SCALE]`, `spin=S:DEG`, `uniforms=a+b`, `debugbuffer=nanN`; tools/cadence-test.sh
  restores the world by checksum and refuses to start under 3 GB free (the disk filled once: 19 GB of old results).

## 10-15 morning: Pegasus (user: neon pink/blue on distant blocks, ugly sky)

- **Uninitialized shader variables** (every pack): a GLSL local read before it is written is 0 on the desktop drivers packs
  are written for, but register garbage in Metal. Pegasus's composite11 declares `vec3 pre_reflection;`, sets it only on
  metals and then takes `max(reflection, pre_reflection)` everywhere: the red/blue/magenta waterline halos and the yellow
  blocks on night water. SPIRV-Cross's FORCE_ZERO_INITIALIZED_VARIABLES now zero-fills every translated variable (render and
  compute). Pegasus's night water is now dark and reflective; NaN views of colortex0/5/7 are clean.
- **Wrong-type uniforms** (as Iris): Pegasus declares `uniform float frameCounter`. Iris logs "Wrong uniform type" and
  disables such a uniform (0 or its initializer), OptiFine's glUniform1i fails the same way; we converted the value, so its
  noise moved every frame. Int uniforms declared float are now left alone (`-Dcadence.shaders.convertWrongTypes=true`: old).
- **Conservative depth** (every pack that writes gl_FragDepth in world programs: Pegasus, Bliss's POM): such a shader turns
  off early depth rejection, so all hidden terrain was shaded. World programs now declare `depth_greater` and keep the
  written depth at least the surface's (parallax only pushes into the block): Pegasus 720p 61-68 -> 49-54 ms GPU, same
  picture. `-Dcadence.shaders.conservativeDepth=false` turns it off.
- Tried and dropped: precise sin/cos in fast-math stages (Pegasus hashes with fract(sin(big)*k)): clouds barely changed,
  GPU time 60 -> 200 ms. Safe math for all of Pegasus: magenta/cyan water (relaxed is closer to GL drivers here).
- Where Pegasus's time goes (720p, full scale, before conservative depth): clouds composite1 (1/3 res, ~19,000 sin per
  pixel) 19 ms, terrain 15, water 12, shadow 7, sky map composite2 4.5, composite10/11 2-3 each, compute ~0. Its Medium
  profile looks nearly the same at 60% of the cost (720p: 51 -> 31 ms; 1080p Auto: 24 -> 36 fps).
- The quality hint now names the gentlest lower preset first ("Medium is faster, Potato_Shadows fastest").
- Tests: cadence-test.sh passes `-Dcadence.backgroundFps=false` (an unfocused test window was capped at ~15 fps and its GPU
  reading froze) and keeps screenshots as 1280 px JPEGs (`CADENCE_KEEP_PNG=1` for PNGs). Old results: 19 GB of PNGs
  deleted; the 10-14 final sweep kept as JPEGs in metal/run/keepers.

## Debugging tools added

- Self-test `uniforms=a+b` (current values, the pack's custom uniforms too), `debugbuffer=N` / `Na` (alpha) / `depthtexN`
  (red where depth is 1), `skipprograms=a+b` (those programs —
  world, screen or compute — draw nothing), `-Dcadence.shaders.logPipelines=true` (game pipeline -> pack program, with
  defines, depth and blend), census dump writes each program's draw buffers and the buffer specs.
- `-Dcadence.metal.trace=N`: frame N's render passes with attachments and load/store actions (this found nothing wrong in
  the passes, which pointed at the clear value).

## 10-18: thirteen more packs (0.3.3)

Picked for compatibility, not looks: the most-downloaded Iris packs on Modrinth that the 12 above don't cover, old and new
(Potato Shaders is from 2023, Solas and Noble from this autumn), small and huge (Miniature 44 KB, Noble 17 MB). Downloaded
from Modrinth, checked against its SHA-512 hashes, into `metal/run/shaderpacks` (AstraLex renamed: its file name is
full of `§` colour codes). Every pack: offline census, then a self-test sweep (day, the same view), pictures checked.

**11 of 13 load and look right; 23 of the 25 tested packs now work.** GPU time is at a fixed 50% World Resolution
(`-Dcadence.shaders.renderScale=0.5`, 1470x843 pixels; anchors in the same runs: VanillaEX 3.8 ms, BSL 13.0, Bliss
18.5). The fanless Air slows after about two minutes of full load, so a pack measured late in a sweep reads high (Vanilla
Plus: 31 ms late in one, 12.4 ms in a short one); those are marked.

| Pack | Version | Result | GPU ms at 50% | Weight shown | What it needed |
|---|---|---|---|---|---|
| Photon | 1.3b | correct (sky, clouds, weather) | 21.2 | Heavy | 3D textures bound (sky was black, then sepia), struct varyings, shared vec3 arrays split |
| Solas | 3.8 | correct | 21.4 | Heavy | several `image3D`s in one declaration |
| Kappa | 5.3 | correct | 38.2 (late) | Heavy | nothing pack-specific |
| Nostalgia | 5.1 | correct | 21.4 (late) | Heavy | `gcolor` is the block texture in world programs (blocks were flat colours) |
| AstraLex | 93.0 | correct | 26.1 (late) | Heavy | SNORM mip formats |
| Noble | 1.9.9 | loads; **shaded sides darker than intended** | 32.3 (late) | Heavy | integer colour outputs, `type[N] name` varyings |
| Super Duper Vanilla | 1.3.8 | correct | 9.6 | Medium | nothing pack-specific |
| Mellow | 3.4.1a | correct | 4.8 | Light | names clashing with vertex inputs (`Normal`, `Color`), R3_G3_B2, `minecraft:` textures, Voxy's sampler |
| Miniature | 2.19 | correct | 8.5, 7.9 | Light | defines before a leading `#if`, a vanilla program for draws it has no program for |
| Vanilla Plus | 3.3 | correct | 12.5, 12.4 | Medium | `gcolor` (as Nostalgia) |
| Potato Shaders | 1.0d | correct | 13.3 (late) | Medium | nothing pack-specific |
| Shrimple | 0.12 | **not supported**: geometry shaders | | | |
| Rethinking Voxels | r0.1-beta9 | **not supported**: geometry and tessellation shaders | | | |

Shrimple and Rethinking Voxels are refused with that reason in plain words in the pack list ("it needs geometry shaders,
which Metal doesn't have"). Metal has no geometry stage; emulating one (a compute pass writing the expanded vertices) is
possible but large, and these are the only two of 25 that need it.

What changed, for every pack:
- **Raw and 3D custom textures** (`texture.STAGE.NAME = file TEXTURE_3D RGBA8 ...`): uploaded through a new 3D Metal
  texture (MetalVolume) and keyed by the sampler type the program declares, as Iris does. `sampler3D` uniforms bind
  through set 1 (storage), so they were never bound before: Photon's sky lookup read black. Filtering and repeat follow
  the file's `.mcmeta`. `minecraft:` paths read the game's own resources.
- `flip.PASS.BUFFER` directives, `shadowcomp` passes over the shadow map, and a constant declared twice keeps the later
  value (as Iris).
- **Translation**: struct varyings flattened into plain varyings (Metal can't pass a struct between stages as GLSL does);
  `shared vec3` arrays split into three float arrays when Metal's 16-byte float3 would push a compute pass over Apple's
  32 KB of threadgroup memory (Photon's sky harmonics: 36 KB -> 27 KB); identifiers named like Metal built-ins
  (`length_squared`, `powr`, ...) or the adapter's vertex inputs renamed; `type[N] name` declarations; several images in
  one declaration; uniform defaults written `- 123`; integer colour outputs (never blended, no alpha test).
- `gcolor` means the block texture (unit 0) in world programs, as in OptiFine; it is colortex0 only in screen passes.
- A pack with no program for a draw type and nothing to fall back to gets a built-in vanilla program (texture x colour x
  lightmap) instead of failing.
- Samplers that belong to other mods (Voxy's `vxDepthTexTrans`, ...) bind a neutral texture (white for depth, else
  black), logged once.
- Colour formats: R3_G3_B2, RGB4, RGB5, RGBA2, RGBA4, RGB5_A1 (as RGBA8), RGB12/RGBA12 (as RGBA16); mipmaps for SNORM
  and 16-bit formats.
- Census: shadow samplers are checked as hardware comparisons (`textureLod`/`textureGather` on them were false blockers).

Open: Noble's shaded sides are darker than in its screenshots (suspect: its sky-illuminance storage buffer, written in
a compute pass; the binding covers both stages). Listed in the pack catalog, so the pack list says so.

### GPU pass (same day)

Kept: **feedback copies only for samplers a program's code reads.** When a world program samples a buffer it also
draws into, Cadence copies the buffer first (Metal can't read and write the same image in a pass). Complementary declares
colortex0/3/4/6 and depthtex0 in its world programs and reads few of them: 12 copies a frame, now 2. Complementary
Reimagined at 2940x1686: **44 -> 41 ms** (-2.6 and -2.8 ms in two in-run A/Bs, `ab=feedbackreads`); BSL +0.04 ms and
Photon +0.05 ms (no unread declarations there); pictures alike for Complementary, BSL, Photon, Bliss, and the
11-pack sweep. `-Dcadence.shaders.logReads=true` lists each program's read samplers.

Measured and dropped:
- Flushing pass clears a pack never relies on (learned per frame): +0.10 / +0.18 ms, slower.
- Relaxed math for shadow vertex shaders: about 2%, inside the noise.
- The same read analysis for the per-frame decisions (shadowtex1 copy, depthtex2 copy, shadow-colour attachment, hand
  depth merge): no gain where measured (Complementary does read shadowtex1) and AstraLex turned almost white, so a use
  the analysis can't see feeds one of those decisions. Not kept.

## Not supported yet

PBR resource packs: the `normals` and `specular` samplers are constants (a flat normal, black), so a pack's labPBR /
seuspbr "resource pack required" modes see no material maps (bug hunt 10-21, BH-22; reading `_n` / `_s` into material
atlases laid out like the block atlas, as Iris does, is the missing piece). Shader packs on the OpenGL fallback
(`cadence.metal=false`): refused with a message (the translated programs are Metal-only).

1D images, geometry/tessellation stages (Shrimple and Rethinking Voxels need them), Distant Horizons programs (dh_*). A
cold first load of a big pack translates for 2-7 s (cached afterwards: 0.1-0.4 s).

`colorimgN` / `shadowcolorimgN` (programs storing straight into colour targets) work: those targets, and only those, are
made with shader-write usage. None of the 12 packs uses them; `metal/run/shaderpacks/zz-CadenceTest-colorimg` is a
four-program test pack whose compute pass halves colortex0's green through colorimg0 (a purple world = working).
