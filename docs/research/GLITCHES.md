# Research: shoreline glitches, soft water/foliage, black dots (Cadence on Metal)

## Read this first: what is verified and what is not

Web search returned very little Iris- or Metal-specific material. Pages I could actually read:

- Iris README (https://github.com/IrisShaders/Iris): Iris targets backwards compatibility with ShadersMod and OptiFine packs, works with official Sodium releases, and links to `docs/faq.md`, `docs/unsupportedshaders.md` and `docs/usage/limitations.md`. The README does not mention macOS, shaderc, SPIR-V or MoltenVK. I did not read the linked limitations file.
- SPIRV-Cross README (https://github.com/KhronosGroup/SPIRV-Cross): MSL-specific notes. `fixup_clipspace` converts OpenGL-style `[-w, w]` depth to Metal's `[0, w]`. `flip_vert_y` exists but "The use of this is discouraged." Argument buffers consume extra IDs for resource arrays. Combined image samplers must be split into separate texture and sampler bindings.
- Apple `MTLMathMode` documentation page: only the title came back, so I could not quote the NaN/Inf or reassociation guarantees for `safe`, `relaxed` or `fast`. Check the page directly before relying on any statement about relaxed mode.
- LLVM developer discussion "Should isnan be optimized out in fast-math mode?" (https://discourse.llvm.org/t/should-isnan-be-optimized-out-in-fast-math-mode/58888). Relevant to fast math in general, not to Metal specifically.

Not reachable or not found: the ShaderDoc `shaders.properties` page (404 at the URL tried), the shaderLABS wiki (no results), any Iris GitHub issue about macOS or Apple Silicon black pixels or shoreline artifacts (none surfaced), and any Metal-specific source on how `isnan`/`isinf` behave under relaxed math.

Everything in sections A to E that is not explicitly tied to a source above is engineering reasoning from general graphics knowledge. It is labelled as such and should be confirmed with the tests in section E. The tests matter more than the literature here, because the user's symptoms (motion-only, coastline, scattered dots) point to a small number of causes that a per-frame instrument can separate quickly.

## A. Likely causes of the observed artifacts

### A1. Shoreline glitches (water meets land)

Ranked by how well they explain "glitches along coastlines, in motion":

1. **Refraction samples the wrong pixel.** Water refraction offsets the scene-colour lookup by a normal-derived amount. If the offset is applied before checking whether the sampled pixel is water, a coastal water pixel can read a land pixel (sand, grass, the sky horizon) that is brighter or darker. As the camera or waves move, the offset crosses the land/water edge and the pixel flickers. Pack code usually has a guard like "use the opaque colour only where the water mask is set", so check that the guard exists in the translated shader and that the mask is read at the same coordinate as the colour.
2. **depthtex0 vs depthtex1 mismatch.** In the OptiFine/Iris convention, `depthtex0` includes translucent geometry (water) and `depthtex1` excludes it. Water-depth and terrain-depth comparisons at the shoreline use different depths. If a pass compares water surface depth against terrain depth from the wrong buffer, it draws a one-pixel halo that moves with the waves. This is the classic shoreline bug and the first thing to check.
3. **Vertex displacement vs depth.** Waving water moves vertices in the vertex shader. If the depth reconstruction or the foam/intersection term uses the undisplaced position (or a position from a different wave phase), the shoreline intersection line shifts relative to the displaced surface. Motion makes it shimmer. Check that every consumer of water depth uses the displaced position, or that the wave height is passed to the fragment stage consistently.
4. **Half-resolution render with a nearest-depth upscale that is not depth-aware.** At 50% scale, a coastal edge is a 2-pixel feature. Plain bilinear or Catmull-Rom upscaling across a depth discontinuity blends water and land colour, and CAS then sharpens the blend into a visible seam. This is an artifact of the upscaler, not the pack, and it appears only when the scale is below 100%.
5. **Translucency sort order.** Iris draws translucents in a fixed order. If water is composited over an opaque colour that is already stale (written by the previous ping-pong buffer, see C6), coastal alpha blends show the wrong background for one frame.

### A2. Black dots (scattered, moving)

The strongest candidate is **NaN/Inf that enters a temporal history buffer and persists.** Reasoning:

- A single NaN written to a TAA history texture stays there. The next frame's blend computes `mix(history, current, a)` with `history = NaN`, which is NaN. Neighbourhood clamping (`clamp(history, minC, maxC)`) does not reliably remove NaN, because min/max with a NaN operand is implementation-defined. So a NaN can survive for many frames at one pixel and spread through neighbour reads. Each NaN eventually becomes 0 (black) when it is converted to an 8-bit or UNORM target or tone-mapped. That gives exactly "black dots that move with the camera".
- Sources of NaN/Inf in typical packs: `normalize(vec3(0))` in water or foliage normals, `sqrt` of a slightly negative dot product, `pow(x, y)` with negative `x`, `log(0)` in exposure or bloom, division by `fwidth()` that is zero, `1.0 / depth` at the sky (depth = 1.0), and overflow to `Inf` in RGBA16F history when values exceed 65504.
- Half-precision buffers make overflow much more likely. Any pack that stores HDR in `RGBA16F` and sums bloom or TAA weights can overflow.

Alternative causes of dots, lower probability: sampling a texture at a mip level with a zero or NaN alpha-test threshold (foliage holes flicker to black), and alpha-tested foliage with `discard` where helper invocations take derivative values from discarded lanes (see C4).

### A3. "Soft" or smeared water and foliage

- **TAA history with wrong motion.** Reprojection uses motion vectors to find where a pixel was last frame. For waving foliage and water the vertex positions move in a way the camera-only reprojection does not know about. The history is then sampled from the wrong place and blended in, producing a smear. This is the most common cause of "soft moving foliage" in packs that use TAA. Check whether the pack's motion vectors include the animation term (for example by computing the previous-frame vertex position with the previous frame's wave phase).
- **Neighbourhood clamp too loose.** With a wide clamp box (or no clamp), history is accepted even where it is wrong, and the result is blur. Tight clamps and velocity-based history rejection are the standard fix.
- **Jitter not removed from reprojection.** If the pack jitters the projection but reprojects with the jittered matrix, the history is offset by the jitter every frame, which looks like a soft shimmer. Jitter must be applied to the current frame's projection only, and reprojection must use an unjittered matrix (or a consistent jitter-aware one).
- **Mip/LOD and anisotropy.** Foliage textures are alpha-tested. If the sampler uses a bias or no anisotropy, distant or grazing foliage looks blurred. Conversely, the translation layer may not carry the pack's sampler state (LOD bias, filter, anisotropy) into Metal samplers, which changes the look without any shader change. Metal samplers default to `maxAnisotropy = 1`, so anything the pack expects from anisotropy is lost unless set.
- **Upscale softness.** Catmull-Rom plus CAS, applied to a 50% image, produces a smooth-but-sharpened result that reads as "soft" in motion even when each still frame looks acceptable. Still screenshots hide temporal softness, which matches the user's report.

## B. Iris behaviour that could produce these if mirrored wrong

These are general Iris/OptiFine-compatibility points. I did not confirm each against Iris source; verify against Iris's `src` tree before changing the translator.

- **Previous-frame uniforms.** Packs use `gbufferPreviousModelView`, `gbufferPreviousProjection` and `previousCameraPosition` (OptiFine names, mirrored by Iris). If the translator passes the *current* matrices under these names, or updates them at the wrong point in the frame, every reprojection is off by one frame of camera motion.
- **`frameTimeCounter`.** Used for wave animation. If it is not monotonic or is reset on a shader reload, water phase jumps and motion vectors built from it are wrong for one frame.
- **Camera position precision.** Iris rebases the world so `cameraPosition` is small. Packs that rebuild world positions with a float `cameraPosition` at large coordinates lose precision and produce per-pixel jitter in water intersection and reprojection. Check that the translated shader uses the same rebased position as the matrices.
- **Clear and history persistence.** Buffers with `clear` disabled must persist across frames. If the Metal translation clears them (or the ping-pong swap is not done in the right order), history is lost each frame (flicker, soft/no-history look), or stale history is read (ghosting).
- **Buffer flip.** Packs rely on `colortex` flipping between A and B at the end of the frame. Reading a buffer that is also the current write target (see C6) gives undefined or stale results.
- **depthtex1 contents.** As in A1 item 2, `depthtex1` excludes translucents. Any shoreline logic must use the depth buffer the pack expects.
- **Water pass depth.** Water is drawn in a translucent pass that reads the opaque depth. If the translator binds the depth as a sampled texture while it is also the active depth attachment, Metal reads undefined content (see C6).
- **Hand depth.** Hand geometry is drawn into its own depth range in OptiFine/Iris conventions. Mis-handling it can produce edge artifacts near the screen edges, not usually at coastlines.

## C. Apple Silicon and Metal pitfalls for SPIR-V → MSL translated shaders

### C1. NaN/Inf under fast math

Compilers may assume no NaN or Inf exists and fold `isnan(x)` to false, or remove `x != x` checks. The LLVM thread above is the general discussion. Metal's `fast` mode is the most aggressive and the one most likely to remove sanitisers. **Test:** compile the same shader with `MTLMathModeSafe` and with `MTLMathModeRelaxed`. If the dot count changes, the sanitiser was optimised away. **Fix:** sanitise by bit pattern (`as_type<uint>(x) & 0x7fffffff` compared to the exponent mask) rather than `isnan`, and compile only the TAA/history and water shaders in `safe` mode. Keep `relaxed` for the rest.

### C2. Denormals and precision

Half-precision (`half` in MSL, `RGBA16F` targets) and denormal flushing can make small weights zero, which turns a normalise into `0/0`. This feeds C1 directly. **Test:** count NaN after each pass for a fixed frame. **Fix:** clamp weights to a small epsilon before division.

### C3. Depth range convention (high priority for water)

OpenGL depth is `[-1, 1]` before the viewport transform; Metal depth is `[0, 1]`. SPIRV-Cross's `fixup_clipspace` exists for exactly this. If the translator uses the GL projection matrix for depth but Metal's clip conversion, the linearised depth is wrong. Water-depth and terrain-depth comparisons then disagree near the shoreline, which is A1 item 2. **Test:** render a known flat plane at a known distance and compare linearised depth to the expected value. **Fix:** make the projection convention match the target and keep `fixup_clipspace` consistent with it.

### C4. Derivatives, helper invocations and discard

Helper invocations at triangle edges provide derivatives. Metal's `discard_fragment` and alpha-test paths may leave helpers with stale or undefined values. Foliage and water edges (where alpha-tested quads and helper lanes meet) can then produce `fwidth` = NaN or zero, which yields NaN in mip selection and the black dots in A2. **Test:** make `fwidth` a debug output on foliage; look for zero or NaN. **Fix:** compute mip-independent values (`textureLod` with an explicit level) for alpha-tested foliage.

### C5. Texel coordinates, Y origin and texelFetch

`gl_FragCoord` is bottom-left in GL and top-left on Metal (the Y flip). SPIRV-Cross offers `flip_vert_y`, which its own docs discourage. If a pass reads a neighbour with `texelFetch(tex, ivec2(gl_FragCoord.xy) + offset)` and the Y convention is inverted, the neighbour is one row off. At a coastline that is a one-pixel edge shift that moves with the camera. **Test:** draw a known gradient and read it back with `texelFetch`. **Fix:** normalise the coordinate once at the translator boundary and never mix a flipped `gl_FragCoord` with an unflipped texture.

### C6. Feedback loops (sampling a buffer that is also the render target)

Metal does not allow reading a texture that is simultaneously bound as a render attachment in the same pass; the result is undefined. Ping-pong (A/B) must read one and write the other. A translation bug that binds the same texture to both produces stale or garbage history, which looks like smear plus dots. **Test:** run with the Metal API validation layer on and look for feedback-loop warnings. **Fix:** check the bind table for read/write aliasing at every pass.

### C7. Other items (lower probability)

- **Tile memory and programmable blending.** Apple GPUs are tile-based. Without programmable blending or framebuffer fetch, the translator must copy the target to read it, which is correct but costs bandwidth. A copy that happens before the write (not after) gives one-frame-stale data at water edges.
- **R11G11B10 has no sign and no alpha.** Packs that store signed normals or alpha in this format lose data. Check the format mapping in the translator.
- **sRGB.** Sampling an sRGB texture or writing to an sRGB target applies a transfer function; mixing sRGB and linear paths shifts colour near edges (less likely to cause motion artifacts).
- **Integer vs float textures.** `texelFetch` on an integer texture returns integers. A format mismatch returns values that look like black or saturated pixels.
- **Geometry shaders.** Metal has none. Packs that depend on them will simply not render the geometry. This does not explain these symptoms, but check that no pass silently relies on one.
- **Anisotropy and filter defaults.** Metal samplers default to nearest or linear with anisotropy 1. A translated sampler that does not carry the pack's filter state changes the look of foliage and water textures.
- **Lossless compression.** Apple's lossless render-target compression is transparent to shader code. It does not change results. Do not chase it as a cause.

## D. Known issues for Iris on macOS and translation layers

I could not find documented Iris macOS issues matching these symptoms in the sources I reached. Two related reports:

- Apple Developer Forums: after an Xcode/SDK change, some Metal shader colours came out black; the cause was in the shader compilation path. This matches the "black output after translation" pattern, not a specific pack bug. (https://developer.apple.com/forums/thread/756857, seen only in search results)
- MoltenVK's shader converter translates SPIR-V to MSL, which is the same path Cadence uses. An Apple forum thread notes that Xcode's Metal debugger can crash on shaderc/glslc-compiled shaders from a MoltenVK app, while frame capture still works. Use frame capture rather than the shader debugger to inspect these shaders. (https://origin-devforums.apple.com/forums/profile/cococarbon, seen only in search results)

Iris's own limitations page (https://github.com/IrisShaders/Iris/blob/trunk/docs/usage/limitations.md) is the first place to check for known unsupported behaviour; I did not read it.

No fix from Iris upstream was found for these artifacts. Do not assume one exists.

## E. Prioritised checklist

Each item: the cause, a concrete test, and the fix in a translation layer like Cadence's. Do the tests in order; the first failure usually explains both symptoms.

1. **NaN/Inf in history (black dots).** Test: add a debug pass after each colortex write that counts `isnan`-by-bit-pattern pixels and writes the count to the log. Repeat with `MTLMathModeSafe` on the TAA and water shaders. If the count drops in safe mode, it is C1. Fix: sanitise history and every divide, normalise and log/sqrt input with an epsilon or a bit-pattern check; compile TAA/water in safe mode; clamp HDR to the format maximum before writing RGBA16F.
2. **Depth convention (shoreline).** Test: render a flat plane at a known distance; compare the linearised depth from `depthtex0` and `depthtex1` with the expected value. Fix: make the projection and `fixup_clipspace` agree with Metal's `[0,1]` range everywhere depth is read.
3. **depthtex0 vs depthtex1 at the shore.** Test: output both depths as colour at a coastline and look for a one-pixel halo that moves with the waves. Fix: use `depthtex1` for terrain and an explicit water mask for the water surface; clamp refraction to the water mask before sampling the opaque colour.
4. **Y-flip and texelFetch (shoreline seam).** Test: draw a row-numbered gradient and read it back with `texelFetch` at offsets. Fix: normalise the coordinate once; do not mix flipped and unflipped sources.
5. **Feedback loop / ping-pong aliasing (smear and dots).** Test: Metal validation layer on; log bind tables per pass. Fix: enforce read A / write B per pass; never bind the target as a sampled texture in the same pass.
6. **Previous-frame matrices and animated foliage (soft foliage).** Test: freeze the camera with waving foliage on; history should be stable and foliage should not smear. If it smears, motion vectors ignore the wave term. Fix: pass the previous frame's wave phase (or previous vertex position) so reprojection accounts for vertex animation; keep `frameTimeCounter` monotonic across reloads.
7. **Jitter in reprojection.** Test: disable jitter; if the shimmer and softness disappear, jitter is leaking into reprojection. Fix: reproject with unjittered matrices.
8. **TAA clamp too loose.** Test: tighten the neighbourhood clamp and compare motion blur on foliage. Fix: velocity-based history rejection and a tighter clamp box.
9. **Upscale softness and depth-aware filtering.** Test: render at 100% scale; if the coastline seam and softness disappear, the upscaler is at fault. Fix: depth-aware or edge-preserving upscale for the coast, and cap CAS strength when scale is below 100%.
10. **Sampler state (foliage look).** Test: log the sampler descriptors Metal receives against the pack's `texture` and `filter` settings. Fix: carry LOD bias, filter and anisotropy from the pack into Metal samplers; do not default to anisotropy 1.
11. **Half precision in history.** Test: switch colortex formats to RGBA16 (or RGBA32F for history only) and see whether dots change. Fix: use float history where overflow is possible.

## Bottom line

The two symptoms point to a small set of causes that a per-frame instrument can separate. The most likely single cause of black dots is NaN/Inf surviving in a TAA history buffer, possibly amplified by fast math removing sanitisers. The most likely cause of coastline glitches is a depth-convention or depthtex0/depthtex1 mismatch combined with refraction that is not clamped to the water mask. The soft look in motion is most likely TAA reprojection that does not account for vertex animation, plus a loose history clamp. None of these is confirmed by an Iris or Metal source in this report; the tests in section E are how to confirm them.
