# Cadence (Metal) — plan

Cadence is a fork of [mcopt](https://github.com/noahdunnagan/mcopt) (Apache-2.0, native Metal renderer for
Minecraft 26.3 on Apple Silicon) combined with the Shader Loader from
[minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) (MIT, runs real shader packs
such as BSL on Metal). Goal: the fastest way to play Minecraft on a Mac, with shader packs, all native Metal.

**Rule for every change:** say whether the picture is exactly the same, put it behind a switch, and measure it
before and after on a real Mac. mcopt's author holds every change to this bar; so do we. A slower change is a failed change.

## What each half already does

mcopt (the base) already does most of the Apple-GPU work: on-chip (memoryless) render targets, no needless
memory loads/stores, batched indirect draws, residency sets, private GPU memory, its own fast terrain path that
turns ~2000 draw calls into a few, a hidden-geometry cull, faster meshing, faster startup, thread priorities, and an
optional on-disk cache of translated shaders.

The Shader Loader compiles a pack's GLSL to SPIR-V once (LWJGL shaderc) and hands the pipelines to Minecraft's own
graphics layer (`com.mojang.renderpearl`). It never touches its own renderer's internals, so it can run on mcopt's
Metal backend, which turns SPIR-V into Metal shaders once per pipeline (SPIRV-Cross) and caches them.

## Status (2026-10-12, after the overnight loop — details in OVERNIGHT.md)

Runs in game (MC 26.3 + BSL 10.1.8, M2 Air 8 GB), installed in the player's CurseForge "Cadence Test" instance.
- Phase 1 done. Live shader switching (Video Settings > Shaders: pack, quality preset, resolution), chunk buffers
  rebuilt for the new vertex stride; preset changes keep the chunks when geometry is unchanged.
- Phase 2: render scale + Auto dynamic resolution (GPU-timed, works under VSync) instead of MetalFX for now;
  upstream's audited shadow receiver culling on; compact 48-byte pack vertices; the discard-loads hint measured
  no gain (kept off); shadow-map sizing is the pack's preset (Quality button) — a one-time hint suggests LOW when Auto
  can't reach the target. Frame pacing measured: no hitches from chunk loading (99th percentile ~55-65 ms at 25 fps).
- Phase 3 (CPU/memory) started: vanilla's chunk compile queue replaced by an exact heap-based one (was 13.8% of CPU
  while loading with a pack; also fixed a 5-minute hang when leaving a world), shadow compile cull, 32 MB chunk heaps
  with a pack, runtime heap trim for oversized -Xmx, lazy pack material lookup.
- At RD 16 with BSL the GPU frame is geometry-bound (shadow casters ~17 ms, terrain ~10 of ~45 ms); further fps there
  comes from the pack's quality settings or render distance.
- Next candidates: MetalFX spatial upscaling (quality at 50%), native partial mip generation, active-resource lists,
  game-logic/tick CPU work (not measured yet).

## Phase 1 — combine

1. Move the Shader Loader in as the `shaders` Gradle module (MIT notice kept in NOTICE and in the jar).
2. Point its mod dependency at `mcopt-metal` instead of `minecraft_metal`.
3. **Hand-off rule:** when a shader pack is selected, mcopt's own fast terrain (`mcopt.own`) stays off and terrain
   goes through the standard path the loader hooks — still drawn by mcopt's Metal backend. The two mods patch 7 of
   the same game classes, and 6 of those are mcopt's own-terrain path, so this removes nearly all overlap. Same
   pattern mcopt already uses to step aside for Distant Horizons.
4. When a pack is selected, turn on mcopt's translated-shader disk cache (packs build ~120 pipelines; vanilla few).
5. Port the renderer fix the loader needs: mip sizes clamped to at least 1 for narrow textures.
6. Build both jars.

## Phase 2 — make shader packs fast on Apple GPUs (measured, switch-gated)

1. **Skip loading old pixels in full-screen pack passes.** Pack composite/final passes cover every pixel with no
   blending; loading the previous contents from memory first is wasted bandwidth on Apple's tile GPUs. Only when
   blending is off and the shader has no `discard`. Off until checked in-game.
2. **MetalFX upscaling for packs.** Render the pack's world at a lower resolution and let Apple's MetalFX upscale it.
   The largest single FPS lever with shaders. Needs the live test first, because the pack's render targets must be
   resized together.
3. **Shadow-map sizing per Mac.** BSL defaults to a 2048² shadow map; on small GPUs offer a smaller one.
4. Find the cause of the uneven frame pacing the loader's author measured (p99 10–27 ms) using mcopt's frame log.

## Phase 3 — the parts of the game mcopt doesn't touch (CPU side)

Measure first on a real world, then go after the biggest: game logic/tick (mobs, redstone, hoppers), memory use
(FerriteCore-style), menus and text, particles, entities and block entities. Prefer existing, licence-compatible
work where it exists over rewriting it.

## Testing

Needs: a CurseForge (or Prism) **Minecraft 26.3 Fabric** instance, Fabric Loader 0.19.5+, and **BSL 10.1.8** placed
in `shaderpacks/` by the player (packs can't be redistributed). Compare, same world, same spot, VSync off:
mcopt alone, Cadence without a pack, Cadence with BSL. Record FPS, 1% lows, and frame-time spikes.
