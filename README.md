# Cadence

**A performance mod for Minecraft on Apple Silicon Macs.** Cadence replaces Minecraft's OpenGL renderer with a native
Apple **Metal** renderer, adds a faster terrain path and quicker startup, and runs **shader packs** (BSL, Complementary,
Bliss and more) directly on Metal, without Iris, Sodium or OpenGL.

> **Beta software.** Expect the occasional bug and back up your worlds before trying it.

## Features

- **Native Metal renderer.** No OpenGL translation layer between Minecraft and your Mac's GPU.
- **Shader packs on Metal.** OptiFine/Iris-format packs are translated to Metal and cached, so the second load is
  quick. Compute passes, custom images and per-buffer blending are supported.
- **Auto resolution.** Shaders render at a lower internal resolution when your Mac can't hold the target frame rate,
  then upscale with Apple's MetalFX. Temporal anti-aliasing history survives the changes, so the picture stays clean.
- **Thermal-aware.** On fanless Macs, Cadence holds a steady frame rate rather than letting the Mac overheat and throttle.
- **Faster game logic (Cadence Logic).** Cadence's own replacement for Lithium: entity searches up to 22× faster, mob
  brains and villager sensors without per-tick garbage, smoother chunk unloading. Exact: the game plays the same.
  See [docs/GAME-LOGIC.md](docs/GAME-LOGIC.md).
- **Faster terrain shading.** With RGSS texture filtering, terrain pixels that RGSS can't change take one texture
  sample instead of nine: about 25% more fps without shaders, with an identical picture (checked pixel by pixel).
- **Works next to other mods (compatibility layers).** Where another mod hooks code Cadence replaces, Cadence uses
  Minecraft's own code in that one area and keeps every other optimization. See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).
- **Faster chunk meshing, startup and memory use**, plus an fps counter (Cadence HUD).
- **Render Max FPS.** The game at the highest frame rate this Mac can render it while still playable: the world ray-traced
  on the GPU at 320 x 180, each block one colour with Minecraft's lighting, with the HUD, and frame interpolation counted
  apart. 66,000 frames rendered a second on an M2 MacBook Air looking at the sky (see
  [docs/dev-log/RECORD.md](docs/dev-log/RECORD.md)).

## Requirements

- An Apple Silicon Mac (M1 or newer) on macOS 26 or later
- Minecraft **26.3** with **Fabric Loader 0.19.5** or newer
- Java 25 or newer (the official launcher, Prism and CurseForge download it for you)
- Optional: **Vivace**, a free Java build made alongside Cadence (shorter and rarer garbage-collection pauses; Vivace 25
  recommended). It is a separate download with its own licence (GPLv2 with the Classpath Exception); Cadence never
  downloads or bundles it. Install it, then pick **Video Settings > Java: Vivace** (or point your launcher at it).

## Install

1. Install Fabric for Minecraft 26.3 (https://fabricmc.net/use/installer/), or make a 26.3 Fabric instance in Prism or
   CurseForge.
2. Download `cadence-<version>.jar` from the Releases page (or Modrinth) and put it in your `mods` folder.
3. For shaders, open **Options > Video Settings > Shaders...** (or press **O** in game) and drop shader pack `.zip`
   files onto the list, or put them in the `shaderpacks` folder. Click a pack to turn it on; **K** turns shaders off
   and back on.
4. Start the game. To check that Cadence is running, press F3 (fn + F3): the bottom right says `Metal 3 (Cadence)`.

Cadence replaces Sodium and Iris, so remove them from the `mods` folder. Lithium isn't needed either (if it's installed,
Cadence's own game-logic optimizations step aside for it). Fabric API isn't required, but keep it if
your other mods need it.

## Shader packs

23 of the 25 packs tested work. These 12 render correctly by day, at night, in rain, underwater, and in the Nether and End:

BSL 10.1.8 · Complementary Reimagined and Unbound 5.9.3 · Bliss 2.1.2 · MakeUp UltraFast 9.5g · I Like Vanilla 1.3.3b ·
Insanity 1.650 · Sildur's Vibrant 1.541 and 2.02 · Sildur's Enhanced Default 1.20 · VanillaEX 1.2.0 · Pegasus 0.3.7

These 11 load in every dimension and look right in the overworld by day (tested 2026-10-18):

Photon 1.3b · Solas 3.8 · Kappa 5.3 · Nostalgia 5.1 · AstraLex 93.0 · Super Duper Vanilla 1.3.8 · Mellow 3.4.1a ·
Miniature 2.19 · Vanilla Plus 3.3 · Potato Shaders 1.0d · Noble 1.9.9 (its shaded sides are darker than intended)

Shrimple and Rethinking Voxels don't run: they need geometry or tessellation shaders, which Metal doesn't have. The
Shader Packs list shows, for each pack, whether it's been tested and how heavy it is (Light, Medium, Heavy).

Most hold 60 fps with Auto resolution on an M2 MacBook Air. Pegasus is very heavy: its **Medium** preset looks
almost the same and is about 40% faster. Shader packs aren't included with Cadence; download them from their authors.
Details and known issues: [docs/SHADER-PACKS.md](docs/SHADER-PACKS.md).

## Settings

- **In game:** everything is in **Options > Video Settings**, at the top: the status lines, then **Shaders...** (the
  Shader Packs list, with the pack's profile) and **Shader Pack Settings...** (the pack's own options, on its own pages,
  as in Iris, with Apply), then the Performance preset and Quick Setup; resolution, sharpening, Retina, leaves, cooling,
  background frame rate, dynamic lights and messages under **Cadence: Performance**; shadows under **Cadence: Shader Speed**.
- **Keys:** **O** opens Shader Packs, **K** turns shaders off and back on (Controls > Key Binds > Miscellaneous).
- **Shader pack options** are kept per pack in `shaderpacks/<pack>.txt`, the same file Iris uses.
- **Config files:** `config/cadence.properties` and `config/cadence-shaders.properties` in your Minecraft folder.
  Set `profile=none` in `cadence.properties` to turn off Cadence's performance profile.
- **Advanced:** every switch is also a `-Dcadence.*` JVM flag. To turn off the Metal renderer for a mod that needs
  OpenGL, use `cadence.metal=false`.

## Known issues

- Mods that require Sodium or Iris, or that draw with OpenGL directly, can't be used while the Metal renderer is on.
  Mods that use Minecraft's own rendering API work; where one hooks code Cadence replaces, Cadence steps aside in that
  area (compatibility layers, first version). Addon mods can fill the gap for OpenGL-only mods with Cadence's API
  (render hooks, Metal handles, step-aside requests): [docs/API.md](docs/API.md).
- Far terrain (Video Settings > Far Terrain) is experimental and best on Macs with 10 or more GPU cores. With a shader pack
  it's drawn by packs that support Distant Horizons (their dh_terrain program); the Nether has none.
- Pegasus's rain draws as wide, blocky streaks (most likely the pack's own look; unverified).
- PBR resource packs (labPBR or OldPBR normal and specular maps, `_n.png` / `_s.png`) aren't read yet: a shader pack's
  "labPBR / seuspbr (resource pack required)" mode draws as with a plain resource pack (flat normals, no reflections).
- Shader packs need the Metal renderer: with `cadence.metal=false` (or Distant Horizons on its OpenGL renderer) the
  Shaders button is unavailable.

## Building from source

You need a JDK 25 (for example Zulu or Temurin). Then:

```bash
./gradlew build
```

This builds the three mods into `metal/build/libs`, `shaders/build/libs` and `fpshud/build/libs`. To make the single
release jar and zip in `dist/`, run:

```bash
tools/release
```

For the development and test tools, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Project layout

| Folder | What's in it |
|---|---|
| [`metal/`](metal) | **Cadence**, the core mod: the Metal renderer (Java plus Objective-C in `src/main/native`), terrain, chunk and startup work |
| [`shaders/`](shaders) | **Cadence Shaders**: the shader-pack loader (GLSL → SPIR-V → Metal), Auto resolution and the in-game settings |
| [`fpshud/`](fpshud) | **Cadence HUD**: the fps counter |
| [`tools/`](tools) | Release packaging, the automated in-game test harness, benchmarks |
| [`docs/`](docs) | Shader-pack status, changes from mcopt, licensing notes, development log and research notes |
| [`packaging/`](packaging) | Release README, Modrinth page text, project icon |

New to the code? [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) maps every package: what it does and where to start.
Reviewing it for a mod platform? [docs/FOR-REVIEWERS.md](docs/FOR-REVIEWERS.md): native code, network (none), files written.

## Credits and license

Cadence is a fork of two open-source projects, and it wouldn't exist without them:

- **[mcopt](https://github.com/noahdunnagan/mcopt)** by Noah Dunnagan (Apache-2.0): the Metal backend Cadence is built on.
- **The Shader Loader of [minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer)** by
  Kausik Velaga and contributors (MIT): the base of the shader-pack loader.

Cadence is distributed under the [Apache License 2.0](LICENSE). The MIT notice for the shader-loader code is in
[shaders/LICENSE-MIT-shader-loader](shaders/LICENSE-MIT-shader-loader). Keep [NOTICE](NOTICE) with any copy. Who made
what: [CREDITS.md](CREDITS.md). Minecraft is a trademark of Mojang; Cadence is not affiliated with or endorsed by Mojang
or Microsoft.
