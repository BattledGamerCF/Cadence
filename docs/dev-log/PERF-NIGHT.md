# Performance night (2026-10-15)

The goal: the most fps possible on an M2 MacBook Air (8 GB, 8-core GPU, fanless), with and without shader packs, with every
gain proven by a test that background load and heat can't fake.

## How things were measured

- **Interleaved A/B in one run.** The Air throttles as it warms, so "before" and "after" runs minutes apart are not
  comparable. Every change was switched on and off inside one game session: the self-test's `ab=FEATURE:S` flips every
  2 s and also reports render-thread CPU per frame; the own renderer's probe (`-Dcadence.own.probe=1000
  -Dcadence.own.probeModes=base/...`) switches every second in a shuffled order and reports frame time per mode every 5 s.
  The tables below give the median of those 5 s windows relative to the configured build.
- **Pictures checked bit for bit.** Screenshots taken seconds apart differ in about 25% of pixels even with nothing
  changed (water, animation), so shader shortcuts are checked inside the shader instead: `-Dcadence.own.sampleVerify`
  computes both the shortcut and vanilla's result for every terrain pixel and paints it magenta if they differ in any bit.
  A positive control (comparing against plain nearest sampling) lights up 440,000 pixels, so the check works.
- Native resolution unless noted: 2940 x 1686 (a 1470 x 920 window on the Retina screen), render distance 16, heat
  control off (to measure the renderer, not the cooling policy).

## Kept

| Change | Result | Picture |
|---|---|---|
| **RGSS skip** (terrain shader): with RGSS texture filtering, which the player's instances use, vanilla takes 9 atlas samples per terrain pixel and blends "nearest" and "RGSS" by a factor that is exactly 0 wherever a texel covers at least a pixel on screen, which is most of the frame. Then only the nearest sample is taken. | Frame time **-25%** at native (in-run A/B: without it +32.9%, 44 windows, quartiles +29..+35%). The coastal view went from about 340-360 fps to 400-445. | Bit-exact: 0 differing pixels in 6 views |
| Lazy clears with shader packs (earlier tonight) | BSL GPU -1.6 to -1.75 ms | Identical |
| Entity model writer (earlier tonight) | Render-thread CPU -5% | 155M faces compared, 0 mismatches |
| **Glowing-outline target only while outlines show**: vanilla keeps a colour + depth target at window size for glowing outlines all the time; it is now 1 x 1 until a frame draws outlines, and 1 x 1 again 10 s after | **-38 MB** of GPU memory at 2940 x 1686 (about -41 MB fullscreen) | Outline drawn and aligned at 100% and 50% world resolution |
| **Memory pressure shrinks the Java heap**: G1 ignores `SoftMaxHeapSize` (JDK-8236073), so under macOS memory pressure only ZGC used to react; now G1's free ratios tighten to 15/5 | Committed heap 576 -> 473 MB seconds after simulated pressure (2 GB heap) | - |

## Measured and not kept

| Idea | Result |
|---|---|
| Render-type draw index (`Group.getOrAddDraw`'s `List.indexOf` as a map) | Render-thread CPU +0.7% (noise): the render thread is only ~1.6 ms a frame in the busy scene, the GPU 4-5 ms |
| Last-section cache in particle culling | +5% (noise or worse) |
| Level-0 sample where the gradients are under half a texel | Bit-exact in all three filtering modes, but **3.8% slower**: on Apple GPUs the gradient sample is not the expensive part |
| Per-vertex fog for translucent terrain (`frag.fogVert`) | +0.9% (noise) |
| Upstream per-GPU switches on the 8-core M2 (`a2Exact`, `a1Exact`, `pyrSkip=2`, `splitNear` 128/320) | All within -0.5% .. +1.8%: the upstream tuning already fits |
| Turning the GPU unit occlusion off (it splits the render pass twice) | Occlusion **wins**: off is up to 15% slower when more terrain is in view, equal at the low view. Kept on |

## Bugs found and fixed on the way

- **Crash turning a shader pack on with World Resolution below 100%.** Vanilla makes its sky renderer lazily inside a
  frame and keeps that frame's main target, which with World Resolution was Cadence's scaled target; when that target was
  freed (pack turned on, or the scale changed) the next sky pass crashed. The sky now draws into the current main target.
- **The "pack failed, shaders off" safety net crashed the game one frame later** ("PreparedFrame already in use"): the
  exception skipped vanilla's end-of-frame release. It is released now; a self-test step (`failframe`) injects a failure
  at the same point to prove the recovery (pack off, game keeps running, pack can be turned back on).

## CPU: where it stands

On the M2 the frame is GPU-bound almost everywhere: in the normal scene the render thread uses about 0.5 ms of CPU per
1.3 ms frame even at 1080p. CPU savings show up only in entity-heavy scenes (render thread 1.5 ms a frame with ~320 mobs)
or at frame rates far above any display. JFR also over-reports time at JNI boundaries: a clock read it put at 5% of the
render thread measured as noise when removed.

## Shader packs: where the time goes (BSL, 1470 x 843 internal, in-run skip A/B)

Water 6.7 ms (its fragment shader does forward lighting with shadow sampling, sky and cloud evaluation and screen-space
reflections), shadow 1.7, terrain 2.2, composite 1.0, deferred1 0.9, composite4 0.8, composite7 0.6, deferred 0.5. Face
culling saves 2.3 ms and HiZ 1.0 ms (both on). The rest is the pack's own math. A provably unused mip generation per frame
(BSL, Complementary) measured -0.06 ms (0.4%): not worth the risk, not enabled.

## Where the no-shader frame goes (native, after the RGSS skip)

- Opaque terrain is about 70% of the frame; everything else (sky, entities, translucent, HUD, present) about 0.35 ms.
- In the nearer solid terrain draw, a constant colour instead of the shader saves 16%; the vertex colour alone saves 14%,
  so the remaining texture sample is most of the shading. Fog and the fade mix are about 1% each.
