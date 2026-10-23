# Metal 4 port and the FPS record (plan, 2026-10-23)

Research: `scratchpad/research/METAL4.md`, `scratchpad/research/FPS-RECORD.md` (session scratchpad).

## The target
- Best community claim: **21,632 fps** (NotroDan, Jan 2024, i7-14700 + RTX 4070; Hone archive). Next: 15,523, 14,346.
  There is no official category. Hone's rules: the world must be rendered and playable, no mod that stops block/entity
  rendering, the counter not falsified, every frame really rendered, specs and methods disclosed.
- So Render Max FPS (black screen, 14,100 fps) does not count. A record run needs **the world drawn, F3 visible, a video**.
- **Frame interpolation** (MetalFX) doubles shown frames, but generated frames break "every frame rendered": the counter
  shows both ("28,000 fps, 14,000 rendered") and a record claim uses the rendered figure.

## What we have (M2 Air, macOS 27, SDK 26.2: all MTL4* and MTL4FX* headers present)
Native Metal: `mcmetal.m` (2.1k lines, the backend: queue, command buffers, encoders, presentation), `mcown.m` (2.1k,
terrain renderer), `mclod.m` (far terrain), `mccull.m` (HiZ/culling), `mcpack.m`, `mcfx.m` (MetalFX), `lodseam.m`,
`mccap.m`, `rec.m`, `mcbench.m`: ~6,500 lines that touch Metal.

## Phases
0. **Probe** (`tools/m4_probe.m`, standalone): MTL4 queue + allocator + command buffer, argument table, residency set,
   present through `waitForDrawable/signalDrawable`, and `MTL4FXFrameInterpolator` on this M2 (one report says its encode
   is a silent no-op: check the output changes). Measure: empty-frame rate Metal 3 vs Metal 4 in the probe.
1. **Backend on Metal 4** in `mcmetal.m`, behind `-Dcadence.metal4` (Metal 3 path kept as the fallback and A/B):
   per-frame-slot allocator + command buffer; argument tables instead of per-draw `set*`; one residency set holding every
   buffer/texture the backend makes (+ the layer's); explicit barriers where Metal 3 tracked hazards (pass -> pass, compute
   -> render, blits); `MTL4Compiler` for pipelines. Unified compute encoder for blits/compute.
2. **The rest of the natives**: terrain (`mcown.m`), HiZ/culling, far terrain (cull -> pack -> draw chains: every hazard
   explicit), shader packs (compute, images), MetalFX spatial -> `MTL4FXSpatialScaler`, recorder/capture.
3. **Frame interpolation**: `MTL4FXFrameInterpolator` (2x). Inputs: colour, previous colour, depth (reversed: set
   `depthReversed`), motion vectors. Minecraft makes no motion vectors: a compute pass reconstructs camera motion from depth
   and last frame's view-projection (entities move slightly wrong; the HUD is drawn after interpolation, `uiTexture`).
   Presentation on its own thread with Apple's pacing (generated frame, then the real one ~half an interval later). The
   fps counter and Benchmark count presented frames and rendered frames separately.
4. **Record mode** (in-world, legal by Hone's rules): Max FPS that still renders the world and F3 (tiny render distance,
   the cheapest real frame), uncapped Metal 4 presentation, frame-loop overhead cut down to the millisecond (ticks, input
   polling, timers, present), on Metal 4 with and without interpolation. Measure rendered and presented fps.
5. **Verify**: one Sonnet 5.5 (high) review of the port for missing barriers / residency (the bug class Metal 4 creates);
   the self-test matrix (25 packs, far terrain, End/Nether, Max FPS, 18-mod set), A/B Metal 3 vs 4; a second Sonnet 5.5
   (high) bug hunt at the end; fixes; release.

Risk notes: missing barriers show as flicker/garbage only sometimes; resources bound by address aren't resident unless in
the residency set (page faults); interpolation base rate should be >= 30 fps and adds about a frame of input latency.

## Status (2026-10-23 evening)
- **Port done, opt-in** (`-Dcadence.metal4=true`, `mcm4.m`): every native module runs on Metal 4 through a facade.
  Verified: world, own terrain, far terrain, BSL / Complementary / Photon / Sildur's Vibrant, MetalFX upscaling, the End,
  Render Max FPS (memory flat: residency released on completion). Sonnet review (METAL4-REVIEW.md): no missing barrier or
  residency on default paths; its bugs fixed (pipeline lifetimes, table clearing, recorder, tile, scaler, addon calls).
  Speed: parity with Metal 3 (Max FPS ~13k both, within thermal noise). Stays opt-in until a full bug hunt.
- **Frame interpolation** (`tools/m4_probe.m`): MetalFX works on the M2 but costs 0.48 ms at 160x90 ... 28 ms at 2940x1912:
  useful for heavy shader-pack frames, far too slow for a record frame (budget 46 us). Record interpolation needs our own
  reprojection kernel.
- **Where Max FPS time goes** (sample + JFR): the render thread waits 69% of the time in waitUntilCompleted: the GPU is the
  limit, not the CPU. `tools/m4_floor.m`: a render pass has a fixed GPU cost of ~53 us on the M2 whatever its size (16.8k
  passes/s at 5 in flight, 21.1k at 32); a compute-only frame does 25.7k/s (5) and 37.2k/s (32 in flight) at 320x180.
- **Record plan** (rules: Hone R01-R04, the world rendered, every frame really rendered; with interpolation, rendered
  frames alone must beat 21,632): a record mode whose frames have **no render pass**: the world (blocks and entities) drawn
  by a compute renderer at a low playable resolution straight into the drawable, the F3/HUD text by compute too, 16-32
  frames in flight, input polled at ~1 kHz, the game's per-frame buffer churn removed (17% of the render thread now),
  then our own reprojection interpolator for the doubled presented count.
