# Cadence Metal: MetalFX, Iris, Metal pipeline caching, and renderer performance research

Date: 2026-10-14. Scope: web research only. Nothing was built, downloaded, or changed except this file.

How to read this report: each claim is tagged.
- **[verified]** read directly from an Apple or project page fetched in this session.
- **[secondhand]** came from a search-result summary or an AI-summarized fetch of a talk or page, not the primary text. Re-check before relying on it.
- **[unverified]** could not be confirmed here (page returned only a title, a 404, or nothing relevant). These are general knowledge or inference and are labelled as such.

Sources that failed to load: the Apple `MTLFXSpatialScalerColorProcessingMode`, `MTLCompileOptions.optimizationLevel`, `MTLCompileOptions.mathMode`, `MTLPipelineOption`, and `MTLStorageMode.memoryless` pages returned only their titles. The Apple "Optimizing performance with Metal best practices" page, the Apple async render-pipeline page, the WWDC20 10632 transcript page, the SPIRV-Cross `RelaxedPrecision` question, and the Iris `RenderTargets.java` raw URL returned 404 or no usable content. The Iris repository landing page loaded but has no rendering code in it.

---

## Part 1: Specific questions

### 1. MetalFX spatial upscaling

**API shape [verified].** `MTLFXSpatialScalerDescriptor` is available from macOS 13.0 (`https://developer.apple.com/documentation/metalfx/mtlfxspatialscalerdescriptor.md`). Its properties, as listed on that page:

- Input: `inputWidth`, `inputHeight` (Int), `colorTextureFormat` (MTLPixelFormat), `colorProcessingMode` (MTLFXSpatialScalerColorProcessingMode).
- Output: `outputWidth`, `outputHeight` (Int), `outputTextureFormat` (MTLPixelFormat).
- Class method `supportsDevice(_:)` to check GPU support before creating anything. Always call this and have a fallback path.
- Factory `makeSpatialScaler(device:)` returns `MTLFXSpatialScaler?`, so handle nil.

The runtime protocol is `MTLFXSpatialScaler` (`https://developer.apple.com/documentation/metalfx/mtlfxspatialscaler.md`). Apple's steps, per frame:

1. Set `colorTexture` to the input texture.
2. Set `inputContentWidth` and `inputContentHeight`.
3. Set `outputTexture` to the destination.
4. Call `encodeWithCommandBuffer:` (`encode(commandBuffer:)` in Swift).

Objective-C shape, assembled from those steps (the method names are as Apple documents them; the code is my assembly, not a copy of an Apple sample):

```objc
MTLFXSpatialScalerDescriptor *d = [[MTLFXSpatialScalerDescriptor alloc] init];
d.inputWidth  = maxRenderW;   // size the scaler for the LARGEST render size
d.inputHeight = maxRenderH;
d.outputWidth  = windowW;
d.outputHeight = windowH;
d.colorTextureFormat  = MTLPixelFormatBGRA8Unorm_sRGB; // must match the input texture
d.outputTextureFormat = MTLPixelFormatBGRA8Unorm_sRGB;
d.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
if (![MTLFXSpatialScalerDescriptor supportsDevice:device]) { /* fallback */ }
id<MTLFXSpatialScaler> s = [d newSpatialScalerWithDevice:device];

// per frame
s.colorTexture = renderedTex;
s.inputContentWidth  = curRenderW;
s.inputContentHeight = curRenderH;
s.outputTexture = upscaledTex;
[s encodeToCommandBuffer:cmd];
```

Check the exact Objective-C selector spellings against the header in your Xcode SDK. The Swift forms are the ones shown on Apple's pages.

**Texture requirements.** [unverified] The Apple pages I could load do not state usage flags, storage mode, or whether input and output must be different textures. Practical assumptions until checked:
- Output is written by the scaler, so give it `usage` including `renderTarget` and probably `shaderWrite`. Treat `shaderWrite` as a cost on lossless compression (see Part 2). Check the scaler docs first.
- Use a different output texture from the input. Do not alias them.
- Use `storageMode = .private` for both, since they stay on the GPU.
- The pixel format of `colorTextureFormat` must match the actual input texture exactly, including sRGB vs linear. A mismatch is the likely cause of a validation failure.

**Input content size and dynamic resolution [verified for the API shape, inferred for the strategy].** `inputWidth`/`inputHeight` on the descriptor fix the scaler's configured input size. `inputContentWidth`/`inputContentHeight` on the scaler are set every frame and describe how much of the input texture holds valid rendered content. The WWDC22 session describes the same idea [secondhand, see below].

Inference: one scaler created at the maximum render resolution, with `inputContentWidth/Height` set to the current dynamic-resolution size each frame, avoids recreating the scaler when the Auto resolution changes. Creation is expensive [secondhand]. Confirm that a single scaler handles smaller content sizes correctly by testing the output for border artefacts when the content is smaller than the texture.

Window size changes: the output size is fixed in the descriptor, so a window resize means a new scaler. That is cheap to rebuild only on resize, not per frame.

**colorProcessingMode [verified for names; mode semantics secondhand].** The three modes are perceptual, linear, and HDR. The Apple case page did not load, so the descriptions below come from the WWDC22 session summary [secondhand]. That summary says:
- Perceptual gives the best performance, and expects tone-mapped values in the 0 to 1 range, sRGB encoded.
- Input must be anti-aliased and noise-free, so run it after tone mapping.

For an already tone-mapped 8-bit image, choose **perceptual**. Use linear only if your pipeline's final texture is linear light before the sRGB encode. Use HDR only if the input holds unclamped scene-linear values, which an 8-bit tone-mapped image does not. Check the mode definitions before shipping.

Consequence for Cadence: the pack's composite output must be tone-mapped and anti-aliased before it reaches MetalFX. If the pack output includes aliased or noisy regions (for example, undersampled transparency or particle noise), MetalFX output will show those artefacts, and the Catmull-Rom plus CAS path may look better on those packs. Test per pack.

**Cost on M1/M2 [unverified].** WWDC22 gives no performance numbers, only qualitative claims [secondhand]. Measure it yourself with GPU timers on the 8 GB Air. Compare: (a) render at 50% and CAS-upscale; (b) render at 50% and MetalFX spatial; (c) render at 100% baseline. The MetalFX pass is a fixed cost per output pixel, so its cost grows with window size, not render size.

**Quality vs FSR1 and Catmull-Rom plus CAS [unverified].** No source compared these. General expectation: MetalFX spatial is an edge-adaptive spatial upscaler, in the same class as FSR1, and it should beat plain Catmull-Rom on edges. Judge on your own packs, at your own render scales, with screenshots.

**MTLFXTemporalScaler without engine motion vectors [verified requirements, inference for the workaround].** WWDC22 lists the temporal scaler's required inputs [secondhand]:
- Jittered colour, with the jitter sequence changing every frame.
- Motion vectors in render-resolution pixel space, scaled through `motionVectorScale`.
- Depth, with `reversedDepth` set to match the engine's depth convention.
- `resetHistory` set on the first frame and on scene cuts.
- `jitterOffset` in the range -0.5 to 0.5.
- Mip bias of log2(render/target) minus 1.

Feasibility with motion = 0: the scaler will accumulate history without correct reprojection, so moving content and camera motion will ghost and smear. It is not viable as-is.

Feasibility with camera-only reprojection [inference]: you can synthesize a motion texture from depth plus the previous and current view-projection matrices. That reprojects static world geometry correctly and ignores moving entities, which will ghost. Minecraft's world is mostly static, so this may be acceptable for terrain, but entities, particles, water, and leaves need care. It also needs the pack's jitter applied to the projection, which packs do not do. Pack compatibility makes this a poor first step. Keep temporal out of the first version and treat it as a later experiment.

### 2. Iris: resolution change, history buffers, and block atlas sampling

**Render target resizing [partly verified, secondhand].** I could not read Iris's source. The only specific information came from a third-party client-mod page describing Iris and Distant Horizons compatibility (`https://www.modpackindex.com/mod/171975/window-resize-fix-iris-distant-horizons`). That page says:
- Iris resizes its render targets by walking every framebuffer it owns and attaching Minecraft's depth texture to each one.
- The function it names is `RenderTargets.resizeIfNeeded`.
- A related bug: if something renders the world at a different resolution from the window, Iris draws colour buffers at one size against a depth buffer of another.

The repository landing page (`https://github.com/IrisShaders/Iris`) lists top-level directories `common`, `fabric`, `neoforge`, and `docs`. A raw fetch of `common/.../targets/RenderTargets.java` returned 404, so the exact path is different from my guess.

**History buffers (`clear=false`) [unverified].** Not established here. General expectation from how Iris exposes `colortex0..N`: on resize, the buffers are reallocated, which means their contents are lost, and the history is effectively cleared. Confirm by reading `resizeIfNeeded` and whatever clears or copies targets on reallocation. For Cadence this matters because a pack's TAA or history buffer resets on every dynamic-resolution step. That causes a flash or smear each time the Auto resolution changes. If it is true, the fix is to change the resolution in coarse steps and to keep history in a buffer you control across resizes, or accept a one-frame reset.

**Block atlas sampling [unverified].** Not verified. Conventional Minecraft behaviour is nearest-neighbour magnification with mipmaps, and anisotropic filtering is a separate setting. Iris packs read the block atlas through the `gtexture` sampler. Check Iris's texture binding code (the sampler state set in its `GlStateManager` or equivalent) before matching it in Cadence. Matching sampler state matters for visual parity more than for speed.

### 3. Metal pipeline caching

**MTLBinaryArchive: how it works [verified, from Apple's "Creating binary archives from device-built pipeline state objects" article, `https://developer.apple.com/documentation/metal/creating-binary-archives-from-device-built-pipeline-state-objects.md`].**

Creating and filling an archive:
- Create an `MTLBinaryArchiveDescriptor` with `url = nil`. That tells Metal to create a new archive rather than load one.
- Call `newBinaryArchiveWithDescriptor:error:` on the device.
- For each pipeline you use, call `addRenderPipelineFunctionsWithDescriptor:error:` on the archive, passing the same render pipeline descriptor the app creates at runtime. The compute and tile variants exist too [secondhand from the search summary].
- Serialize with `serializeToURL:error:`. Apple's sample writes to `~/Library/Application Support/<bundle id>/<name>.binary.metallib`. The directory must exist first. Do not write into the app bundle. Apple warns that this causes code-signing and verification errors when the app is rebuilt.

Reusing on the next launch:
- Create an `MTLBinaryArchiveDescriptor`, set `url` to the saved file, and call `newBinaryArchiveWithDescriptor:error:` to load it.
- Add the loaded archive to each pipeline descriptor's `binaryArchives` array before creating the pipeline.
- Apple's tip: create the pipeline states after creating the archive instance, so Metal can apply the compile-speed and memory optimisations.
- Failing to load an archive is not fatal. Metal falls back to compiling Metal IR at runtime.
- To make a missing entry fatal in tests, set `MTLPipelineOptionFailOnBinaryArchiveMiss` on the pipeline, so you find cache misses instead of silently stalling. The `MTLPipelineOption` page did not load, so the option name comes from the Apple article only.

**Pitfalls [verified unless marked].**
- OS updates: Apple says Metal may invalidate binaries when the device's operating system is upgraded, and that shaders then recompile from the Metal IR stored in the archive. So an OS update costs a recompile, not a full loss of the source, but expect a hitch. Key your own cache on the OS build as well.
- Device specificity [secondhand]: the WWDC20 session 10615 says archives are usable only by the device that created them. Do not assume an archive from one Mac works on another. Include the GPU name in the cache key.
- Descriptor match: an archive entry only helps if the runtime descriptor is the same as the one added. Any difference in pixel formats, blend state, function constants, or vertex layout is a miss. The practical method is to build a key from the full descriptor state, not from the pack name.
- Specialized functions: serialization of specialized functions on device requires macOS 15.0 or later [verified from the Apple article].
- Metal 4 alternative [secondhand]: `MTL4PipelineDataSetSerializer` (macOS 26) can serialize collected pipeline data with `serializeAsArchiveAndFlush(url:)`. It is newer and I did not verify its behaviour. Since Cadence targets macOS versions where the classic API works, stay on `MTLBinaryArchive` for now.
- Forum reports of an exception when assigning an archive to `binaryArchives` [secondhand, Apple developer forums thread 672049]. Check the descriptor and archive creation order if you hit it.

**Does the OS already cache compiled libraries across launches? [unverified].** I could not verify whether macOS caches the results of `newLibraryWithSource` or `newRenderPipelineStateWithDescriptor` across app launches. Apple's binary-archive article implies that the system does not reliably keep your app's pipelines for you, since it recommends the archive. Do not rely on any implicit OS cache. Build your own cache.

**Practical plan for Cadence [inference from the above].**
- Cache the MSL source and the compiled `MTLLibrary` output, keyed on a hash of the GLSL input, the shaderc and SPIRV-Cross versions, the MSL language version, the compile options, the OS build, and the GPU name.
- Cache pipeline states in one `MTLBinaryArchive` per pack, saved under Application Support. Write it after the first successful run and at a clean shutdown, not on the render thread.
- On a cache miss, show the frame with the previous pipeline, or a simple fallback pipeline, rather than blocking for seconds.

**Asynchronous pipeline creation [unverified].** The async method `makeRenderPipelineStateWithDescriptor:options:completionHandler:` exists in the API, but its Apple page returned 404. General practice: compile on a background thread, keep the render loop running with a fallback, and swap in the finished pipeline on a later frame. Make sure the completion handler's thread does not touch shared renderer state without a lock or a queue hop.

### 4. MTLCompileOptions knobs

Apple's pages for `optimizationLevel` and `mathMode` returned only titles, so the descriptions below are general knowledge and are marked as such.

- **optimizationLevel** [unverified] (`default` vs `size`): `default` is the right choice for runtime speed. `size` may shrink the library at some shader-speed cost. Use `default` for packs, since they are heavy in math and the savings in library size are small compared with the pipeline-state cost.
- **mathMode** [unverified] (`safe`, `relaxed`, `fast`): these trade floating-point accuracy for speed. `relaxed` is the one the Cadence design already uses for pack fragment shaders. Keep in mind that relaxed math can change results enough to show visible differences in lighting or depth. Compare images before and after.
- **preserveInvariance** [unverified]: prevents the compiler from reordering arithmetic so that a position computed in two shaders matches bit for bit. Shadow and G-buffer passes often rely on this when they compute the same world position separately. Turning it on can cost performance. Turn it on only for the passes that need it, if you can identify them, or measure its cost on a pack that uses shadows.
- **languageVersion** [unverified]: a higher MSL version can enable newer features and sometimes better code generation. Pick the lowest version that supports every feature SPIRV-Cross emits for the pack, and check it against the SPIRV-Cross output.

---

## Part 2: Performance tweaks

For each item: what it is, expected benefit, risk, how to apply, and source.

### A. Pass count, load/store actions, and merging

**Merge passes that differ only in load and store actions.** Apple: "Render passes don't need to be split when only load and store actions change." [verified, WWDC20 10632 transcript via a summarized fetch; secondhand quote].
- Benefit: each pass boundary costs a store and a load of every attachment. Load and store actions "consume the majority of your app's system bandwidth" [secondhand].
- Risk: a merged pass can serialise work that a separate encoder would overlap, so measure.
- Apply: audit the pack's composite chain for adjacent passes with identical render targets and blend state. Merge them where the pack allows, with MRT output.
- Source: WWDC20 session 10632, `https://developer.apple.com/videos/play/wwdc2020/10632/`.

**Clear load action is tile-only.** Apple: the clear load action "only operates on tile memory," so you do not store cleared pixels that get overdrawn [secondhand]. Fold standalone clears into the first pass that uses the target.

**Use dontCare store actions.** For any attachment not read after its pass, set `storeAction = .dontCare`. [verified in the same talk; secondhand].
- Risk: reading it later returns undefined data. Check every consumer first.

**Memoryless attachments.** Attachments that never leave a pass can use `storageMode = .memoryless`. The talk shows this for an attenuation target, and the MTLStorageMode page describes it [title only; general knowledge: it exists only in tile memory, works on Apple silicon only, and cannot be read by a later pass].
- Benefit: no system memory, and no bandwidth to write it back.
- Risk: the target cannot be read or sampled by any later pass.
- Apply: good candidates in a deferred pack are the intermediate targets written and read within one pass, or depth or stencil that is not reused.

**Tile shaders and imageblocks.** The WWDC20 talk shows tile shaders dispatched per tile inside a render encoder, with `tileWidth`, `tileHeight`, and `threadgroupMemoryLength` on the render pass descriptor. Apple says "Apple GPUs only support a few different tile dimensions" and the talk uses 32x32 [secondhand]. Programmable blending keeps G-buffer data on-chip. A memory barrier inside a fragment stage is costly because it flushes tile memory [secondhand].
- Benefit: a deferred lighting step can read the G-buffer from tile memory instead of from textures.
- Risk: this is a large rewrite. Packs' G-buffer layouts are arbitrary and tile shaders need fixed formats. Treat it as a long-term option.

**MRT limit.** Metal supports "up to eight color attachments in each pass" [secondhand]. Packs with more than eight outputs in one pass must split. Packs that write unused outputs should write zeros instead of leaving them with a write mask, because write masks and unused outputs hurt hidden surface removal on Apple GPUs [secondhand].

**Dependencies between passes.** Avoid false dependencies. If a pass writes a resource the next frame's early work reads, Metal serialises them. Use separate resources or mark resources untracked and synchronise with fences or events [verified in the WWDC22 and 10632 summaries; secondhand]. Encode independent work as early as possible and check overlap with Metal System Trace.

**Early fragment tests.** Add the early fragment test attribute to fragment functions that write resources wherever possible [secondhand, WWDC20 10632]. Shader-side `discard` or depth output disables it, so only use it where the pack allows.

**Depth pre-pass.** Apple says it is "usually unnecessary" because hidden surface removal does the same job on Apple GPUs [secondhand]. Do not add one by default.

**Draw order.** Opaque, then alpha-tested or discard-using, then translucent [secondhand].

### B. Lossless compression and texture usage

**What disables it [verified via the Apple texture usage pages, read in summary; secondhand].** On GPU family 5 and later, lossless compression is not applied to a texture whose usage includes `shaderWrite` or `pixelFormatView`, or whose usage is unknown. It is also excluded for block-compressed formats (PVRTC, ASTC, BC) and for textures that use an underlying `MTLBuffer`.
- Consequence for Cadence: if pack targets are created with `shaderWrite` because a compute pass or an image store writes them, they lose compression. Targets written only by render passes do not need `shaderWrite`.
- Apply: create render-target textures with `usage = .renderTarget` (plus `.shaderRead` if sampled). Add `.shaderWrite` only for images written by compute or `imageStore`. Do not add `.pixelFormatView` unless you really need a view with a different component order. Use a swizzle on a view instead.
- Benefit: compression reduces the bandwidth of every read and write of that target. The memory footprint can grow slightly because of metadata [secondhand].
- Risk: low; it is a usage-flag change. Check that nothing relies on `pixelFormatView` for sRGB, since Apple says a linear-to-sRGB view does not need it.

**Minimal usage flags.** Every usage bit you do not need can cost. Request only the bits each texture uses. [inference, consistent with the above]

**Storage modes.** Use `.private` for GPU-only targets. Use `.shared` only for CPU-written data. [unverified details; general practice]

**Heaps and aliasing for transient targets.** Allocate transient targets from an `MTLHeap` and alias them across passes that do not overlap. [unverified: the Apple pages were not loaded and the WWDC20 transcript said nothing about heaps. General practice on Apple silicon: this lowers the peak memory on an 8 GB machine.] This is probably the biggest memory win for a deferred pack on 8 GB, because G-buffers and shadow-stage targets rarely live at the same time.

### C. Half precision

**What it does [secondhand, WWDC20 10632].** "Apple GPUs are optimized for 16-bit data types." Using `half` and `short` reduces register use, which raises shader core occupancy, and often speeds up arithmetic. Use `H`-suffixed literals (`-2.0h`) or the expression is promoted to float. Float-to-half conversions are typically free.

**When it is safe.** Half has about three decimal digits of precision. It is safe for colours in 0 to 1, for normals with care, for blend weights and tone-mapping curves, and for UV offsets within a tile. It is not safe for world positions, depth reconstruction, shadow-map lookups, or accumulated values. Keep positions and depth in float.

**Applying it to SPIRV-Cross output [unverified].** I could not verify the SPIRV-Cross options for relaxed precision or half mapping. The README's MSL options are the ones I could confirm. The README covers argument buffers: `--msl-argument-buffers` enables indirect argument buffers, and bindings map to `[[id(N)]]` [verified, SPIRV-Cross README, `https://raw.githubusercontent.com/KhronosGroup/SPIRV-Cross/main/README.md`]. For half precision, check the SPIRV-Cross source for `RelaxedPrecision` handling in `CompilerMSL`, and test the result on one pack.

**Risk.** Subtle colour banding and precision bugs. Make it per-pass opt-in and compare images.

### D. Bandwidth: formats and mips

**R11G11B10 vs RGBA16F [inference, general knowledge].** `RG11B10Float` halves the size of an RGBA16F target and needs no alpha. It has no sign bit and limited precision, so it suits HDR colour but not signed normals or values that go negative. Check each G-buffer channel. Use it for emissive or lit colour where the range is positive.

**Fewer targets.** Pack each G-buffer channel into fewer targets where precision allows. Two 8-bit channels can share one target. Test visually.

**Mip generation.** The blit encoder's `generateMipmapsForTexture` avoids writing a render pass per level [unverified: the Apple page was not loaded; general knowledge]. It is simpler, but for filtering control a custom downsample pass can be better. Measure on a mip-heavy pack.

**Sampling cost.** Fewer texture taps per fragment help. Use `sample`-with-LOD when the mip is known, and keep sampler state the same across passes so the GPU does not rebuild it [unverified; general practice].

### E. Shadow map cost

[unverified across the board; general practice, no source loaded]
- **Depth-only pass.** Shadow passes need depth only. Do not bind colour targets, and do not run the fragment stage if the pack does not need it.
- **Front-face culling** (or back-face, depending on the depth bias): reduces acne and can cut draw work.
- **Lower resolution** for distant cascades, or lower the 2048x2048 map on the 8 GB Air.
- **Caching static shadows**: re-render only the cascades that change, using the camera movement threshold. Risky with moving entities, so cache terrain only.
- **Depth bias** tuned per cascade. Tune for the pack, not a global default.

### F. CPU-side overhead

[secondhand where noted; the rest is general practice]
- **Argument buffers** reduce the number of `setFragmentTexture` calls. Use a single argument buffer per pass with the textures it needs. SPIRV-Cross can emit them with `--msl-argument-buffers` [verified in the README].
- **useResource / residency** [unverified]: for argument-buffer textures, call `useResource` (or use `MTLResidencySet` on newer OS versions) so the GPU knows the resource is needed. Missing this is a common correctness and perf trap.
- **Parallel render command encoders** let multithreaded encoding run without the cost of extra command buffers [secondhand, 10632].
- **Metal API validation off** in release builds, because validation has a runtime cost [unverified; general practice].
- Reduce the number of command buffers per frame. One command buffer per frame is typical; split only when the CPU is the bottleneck.

### G. WWDC points for Apple GPUs (summary)

From WWDC20 10632 [secondhand, summarized fetch]:
- Tile memory eliminates overdraw and reduces system memory traffic.
- Load and store actions dominate bandwidth. Merge passes and drop unneeded stores.
- Fold clears into the first pass that uses the target. Resolve MSAA in the same pass.
- Use memoryless storage for attachments that never leave the pass.
- Avoid dependencies with fences, events, or untracked resources.
- Use `half` for registers and occupancy, with `H` literals.
- Use early fragment tests where safe.

From WWDC22 10103 (MetalFX) [secondhand]:
- Input must be anti-aliased, noise-free, and tone-mapped before MetalFX.
- Create the scaler at startup, or when the display size changes.
- Keep the temporal scaler before post-processing.
- Avoid false dependencies between a post pass and the next frame's shadow pass.

I could not load the WWDC session pages for "Harness Apple GPUs with Metal" or "Discover Metal performance optimization" in this session. Check them directly for the tile-shader and heap material.

### H. Thermal and frame pacing

[unverified; general knowledge, no source loaded]
- `CAMetalLayer.maximumDrawableCount`: 2 is lower latency, 3 smooths frame times at a small latency cost. On an 8 GB fanless Air, 2 is usually better for pacing under throttling.
- `displaySyncEnabled` (macOS): controls vsync for the layer. Check the current name and availability in the SDK.
- `presentsWithTransaction`: needed only when presenting in sync with AppKit layer changes. Leave it off otherwise, since it costs latency.
- Thermal: the fanless Air throttles under sustained load. Cap the frame rate below the throttle point, and use dynamic resolution with a target frame time. Lower the render scale early instead of letting the clock drop.
- Low-power strategies: shrink the shadow map and cull shadow cascades when the framerate falls. Reduce particle and entity work before lowering the render scale.

---

## Recommended order for Cadence

1. Measure first. Time each pack pass with GPU timers on the 8 GB Air. Find the top three passes by cost.
2. Lossless compression and usage flags. Remove `shaderWrite` and `pixelFormatView` from render-only targets. Low risk, possibly large bandwidth win.
3. Merge passes and fold clears. Reduce load and store traffic. Medium effort.
4. Memoryless and heap aliasing for transient targets. Probably the biggest memory win for 8 GB.
5. Binary archive per pack, keyed on full descriptor state, OS build, and GPU name. Removes the multi-second first-time hitch after the first run.
6. MetalFX spatial as an option next to Catmull-Rom plus CAS, behind a flag, with a per-pack quality check. Create at the max render size, drive `inputContentWidth/Height` per frame. Confirm the texture requirements and colour mode on the Apple pages first.
7. Half precision for colour math only, per pass, after image comparison.
8. Skip temporal MetalFX for now. It needs jitter and motion the packs do not provide.

## Open items to check directly

- Apple pages for `MTLFXSpatialScalerColorProcessingMode`, `MTLCompileOptions.mathMode` and `.optimizationLevel`, `MTLPipelineOption`, `MTLStorageMode.memoryless`, and `makeRenderPipelineStateWithDescriptor:options:completionHandler:` (these returned titles or 404 here).
- Iris `RenderTargets.resizeIfNeeded` and whatever clears or copies targets on reallocation, and the `gtexture` sampler state. Find the correct path in the IrisShaders/Iris repository (the `common` directory).
- SPIRV-Cross `CompilerMSL` source for relaxed precision and half mapping.
- WWDC sessions "Harness Apple GPUs with Metal" and "Discover Metal performance optimization" for heap and tile-shader specifics.
- Whether `newLibraryWithSource` results are cached by the OS across launches. Assume not until a launch-to-launch timing test says otherwise.

## Sources

- MTLFXSpatialScalerDescriptor: https://developer.apple.com/documentation/metalfx/mtlfxspatialscalerdescriptor.md (verified)
- MTLFXSpatialScaler: https://developer.apple.com/documentation/metalfx/mtlfxspatialscaler.md (verified)
- Creating binary archives from device-built pipeline state objects: https://developer.apple.com/documentation/metal/creating-binary-archives-from-device-built-pipeline-state-objects.md (verified)
- MTLBinaryArchive overview (search result): https://developer.apple.com/documentation/metal/mtlbinaryarchive.md (secondhand)
- Apple developer forums, binary archive thread: https://developer.apple.com/forums/thread/672049 (secondhand)
- WWDC20 session 10615 (binary archives, device specificity): https://developer.apple.com/videos/play/wwdc2020/10615 (secondhand, from search summary)
- WWDC22 session 10103, MetalFX upscaling: https://developer.apple.com/videos/play/wwdc2022/10103/ (secondhand, AI-summarized fetch)
- WWDC20 session 10632, Apple silicon GPU optimization: https://developer.apple.com/videos/play/wwdc2020/10632/ (secondhand, AI-summarized fetch)
- MTLTextureUsage, shaderWrite, pixelFormatView pages (lossless compression rules): https://developer.apple.com/documentation/metal/mtltextureusage.md and linked subpages (secondhand, from search summary)
- SPIRV-Cross README: https://raw.githubusercontent.com/KhronosGroup/SPIRV-Cross/main/README.md (verified for argument buffers only)
- Iris repository: https://github.com/IrisShaders/Iris (landing page only)
- Third-party Iris and Distant Horizons compatibility note: https://www.modpackindex.com/mod/171975/window-resize-fix-iris-distant-horizons (secondhand, third party)

Not used or not reachable: Apple `MTLFXSpatialScalerColorProcessingMode`, `MTLCompileOptions` (`optimizationLevel`, `mathMode`), `MTLPipelineOption`, `MTLStorageMode.memoryless`, Apple best-practices and async-pipeline pages (title only or 404).
