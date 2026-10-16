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

## Requirements

- An Apple Silicon Mac (M1 or newer) on macOS 26 or later
- Minecraft **26.3** with **Fabric Loader 0.19.5** or newer
- Java 25 (the official launcher, Prism and CurseForge download it for you)

## Install

1. Install Fabric for Minecraft 26.3 (https://fabricmc.net/use/installer/), or make a 26.3 Fabric instance in Prism or
   CurseForge.
2. Download `cadence-<version>.jar` from the Releases page (or Modrinth) and put it in your `mods` folder.
3. For shaders, put shader pack `.zip` files in the `shaderpacks` folder and choose one in
   **Options > Video Settings (Cadence: Shaders)**.
4. Start the game. To check that Cadence is running, press F3 (fn + F3): the bottom right says `Metal 3 (Cadence)`.

Cadence replaces Sodium and Iris, so remove them from the `mods` folder. Lithium isn't needed either (if it's installed,
Cadence's own game-logic optimizations step aside for it). Fabric API isn't required, but keep it if
your other mods need it.

## Shader packs

These 12 packs have been tested and render correctly (day, night, rain, underwater, Nether and End):

BSL 10.1.8 · Complementary Reimagined and Unbound 5.9.3 · Bliss 2.1.2 · MakeUp UltraFast 9.5g · I Like Vanilla 1.3.3b ·
Insanity 1.650 · Sildur's Vibrant 1.541 and 2.02 · Sildur's Enhanced Default 1.20 · VanillaEX 1.2.0 · Pegasus 0.3.7

Most hold 60 fps with Auto resolution on an M2 MacBook Air. Pegasus is very heavy: its **Medium** preset looks
almost the same and is about 40% faster. Shader packs aren't included with Cadence; download them from their authors.
Details and known issues: [docs/SHADER-PACKS.md](docs/SHADER-PACKS.md).

## Settings

- **In game:** Options > Video Settings (Cadence: Shaders) (pack, preset, render scale) and the Cadence settings screen.
- **Config files:** `config/cadence.properties` and `config/cadence-shaders.properties` in your Minecraft folder.
  Set `profile=none` in `cadence.properties` to turn off Cadence's performance profile.
- **Advanced:** every switch is also a `-Dcadence.*` JVM flag. To turn off the Metal renderer for a mod that needs
  OpenGL, use `cadence.metal=false`.

## Known issues

- Mods that require Sodium or Iris, or that draw with OpenGL directly, can't be used while the Metal renderer is on.
  Mods that use Minecraft's own rendering API work; where one hooks code Cadence replaces, Cadence steps aside in that
  area (compatibility layers, first version).
- Far terrain (`cadence.lod=true`) is experimental and best on Macs with 10 or more GPU cores.
- Pegasus's rain draws as wide, blocky streaks (most likely the pack's own look; unverified).

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

## Credits and license

Cadence is a fork of two open-source projects, and it wouldn't exist without them:

- **[mcopt](https://github.com/noahdunnagan/mcopt)** by Noah Dunnagan (Apache-2.0): the Metal backend Cadence is built on.
- **The Shader Loader of [minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer)** by
  Kausik Velaga and contributors (MIT): the base of the shader-pack loader.

Cadence is distributed under the [Apache License 2.0](LICENSE). The MIT notice for the shader-loader code is in
[shaders/LICENSE-MIT-shader-loader](shaders/LICENSE-MIT-shader-loader). Keep [NOTICE](NOTICE) with any copy. Who made
what: [CREDITS.md](CREDITS.md). Minecraft is a trademark of Mojang; Cadence is not affiliated with or endorsed by Mojang
or Microsoft.
