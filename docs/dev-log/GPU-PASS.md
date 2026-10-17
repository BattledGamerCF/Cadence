# GPU pass without shaders (2026-10-17): maximum frame rate

Goal: the most fps with shaders off (the player's usual setup, render distance 12, M2 Air, 1470x920 window =
2940x1686 pixels). Without shaders the GPU limits the frame (the render thread waits for it ~80% of the time).

## Where the GPU time goes

Interleaved in-run A/B only: the Mac was in use during this pass (a heavy page in the browser took 25-50% CPU and GPU
time), so absolute frame times moved between 2 and 7 ms; each comparison below alternates its two states every 1-2 s
in the same run.

- **The fast renderer's opaque terrain** is about two thirds of the frame (probe `skip` mode: 6.5 -> 2.1 ms under load).
- The vanilla parts are small (`ab=drop-NAME`): sky 0.11 ms, clouds 0.11, entities 0.15, sun/moon 0.04, eyes 0.05,
  particles and GUI ~0. Clouds off, fast or fancy made no measurable difference.
- **Pixels decide it**: a quarter of the pixels ran 1179-1348 fps against 500 at full size.

A new diagnostic splits the frame into pieces with their own GPU timestamps (`-Dcadence.metal.profileSplit=true` with
`-Dcadence.metal.trace=N`; `GpuProfileMark.mark(label)` adds a split anywhere). Caveat found by using it: every split
stores and reloads the whole screen (Apple GPUs work tile by tile), about 0.35-0.4 ms for anything that touches every
tile, so full-screen draws look far more expensive than they are; use it to find where work is, and A/B to size it.

## Changes

1. **The occlusion step is chosen per scene** (OwnAdaptive). The own renderer's occlusion test (a depth pyramid, then
   the units tested against it) splits the main pass twice, and each split pays the full-screen store and reload above.
   It only pays where much is hidden. Probe, both modes in one run: ocean shore 2.13 -> 1.89 ms without it, over land
   2.9 -> 2.4, high up looking down 4.8 -> 4.5, underground 2.15 -> 2.45 (there it wins). Now both are timed every 8 s for
   about a second (alternating 120 ms windows) and the faster is kept (3% margin). Result: shore 470 -> 527 fps;
   underground keeps it (decision log: with 2.22, without 2.32 ms). `-Dcadence.own.adaptiveUocc=false`: always on.
2. **Lazy clears on without shaders too** (were on only with a pack): -0.07 ms at full resolution, -0.31 ms with World
   Resolution below 100% (the window-size target's clear was paid and then drawn over).
3. **Performance: Max FPS** (new preset, after Max Quality): World Resolution 50%, the Fast upscale, Cooling off.
   - At exactly 50% the Fast upscale now samples the nearest pixel: each world pixel a sharp 2x2 block, as a screen of
     that resolution shows it (bilinear blurred every edge); the HUD and text stay full resolution.
   - Upscalers at Retina size (70%, interleaved under load): MetalFX 3.4 ms a frame, Cadence Smooth 3.0, Fast 1.7,
     100% without upscaling 2.2. MetalFX's sharpening costs 1-2 ms at 5 megapixels: more than a 70% world saves without
     shaders. Max FPS therefore uses Fast.
   - 50% Fast against 100%, interleaved under load: 7.4 -> 4.7 ms a frame (+58% fps); without load 460 -> ~590 fps
     (the rest is fixed work: GUI, the own renderer's culling, the CPU's ~1 ms a frame).
4. Video Settings' status line now follows the buttons (it showed "Auto resolution" after Max FPS set 50%).
