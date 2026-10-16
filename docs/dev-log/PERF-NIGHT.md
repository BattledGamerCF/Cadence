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

## Measured and not kept

| Idea | Result |
|---|---|
| Render-type draw index (`Group.getOrAddDraw`'s `List.indexOf` as a map) | Render-thread CPU +0.7% (noise): the render thread is only ~1.6 ms a frame in the busy scene, the GPU 4-5 ms |
| Last-section cache in particle culling | +5% (noise or worse) |
| Level-0 sample where the gradients are under half a texel | Bit-exact in all three filtering modes, but **3.8% slower**: on Apple GPUs the gradient sample is not the expensive part |
| Per-vertex fog for translucent terrain (`frag.fogVert`) | +0.9% (noise) |
| Upstream per-GPU switches on the 8-core M2 (`a2Exact`, `a1Exact`, `pyrSkip=2`, `splitNear` 128/320) | All within -0.5% .. +1.8%: the upstream tuning already fits |
| Turning the GPU unit occlusion off (it splits the render pass twice) | Occlusion **wins**: off is up to 15% slower when more terrain is in view, equal at the low view. Kept on |

## Where the no-shader frame goes (native, after the RGSS skip)

- Opaque terrain is about 70% of the frame; everything else (sky, entities, translucent, HUD, present) about 0.35 ms.
- In the nearer solid terrain draw, a constant colour instead of the shader saves 16%; the vertex colour alone saves 14%,
  so the remaining texture sample is most of the shading. Fog and the fade mix are about 1% each.
