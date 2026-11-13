# Cadence

Cadence replaces Minecraft's OpenGL renderer with Apple Metal and runs shader packs on it. It is one mod in place of the
Sodium stack (Sodium, Iris, Lithium, Entity Culling and others), built for Apple Silicon.

> Back up your worlds before trying it, as with any mod that changes how the game runs.

## Performance

Measured on the base M2 MacBook Air (8 GB, 8-core GPU) with Cadence's benchmark, no frame cap:

| Window | Render distance | Average fps | 1% low |
|---|---|---|---|
| 1920 x 1080 | 12 chunks | 1,079 | 490 |
| 2940 x 1846 (full Retina) | 6 chunks | 900 | 419 |
| Fullscreen, Max FPS preset | 16 chunks | 1,391 | 607 |

With shader packs, most hold 60 fps on the same Mac at Auto resolution. Method and every other figure:
[docs/BENCHMARKS.md](docs/BENCHMARKS.md).

## Features

### Shader packs

Iris/OptiFine-format packs run natively on Metal. 23 of 25 tested packs work, including BSL, Complementary, Bliss,
Sildur's, MakeUp and Photon ([docs/SHADER-PACKS.md](docs/SHADER-PACKS.md)).

- Auto resolution lowers the pack's internal resolution when needed and upscales with MetalFX.
- Shadows are reused every other frame, far shadows use simple proxies, and casters that can't shadow anything are skipped.
- Pack options use Iris's files and screens. Mods that call the Iris API get Cadence's answers.
- Switching packs pauses the game for about 0.2 s.

### Frame Generation

Shows more frames than are rendered; the HUD and your hand stay sharp.

- **Smooth** builds a frame between the last two.
- **Responsive** turns the newest frame to where you look now, adding no delay.
- **Ultra** renders only when the view changes (at least 4 frames a second) and generates the rest of 60. With BSL it
  cuts the whole Mac's draw from about 24 W to 5-8 W. Mobs and water update only on rendered frames.

### Battery and memory

- **Battery Saver**: without shaders, a true 60 fps at about 5 W. With BSL, Ultra at 30-60 fps shown: 5.4 W standing
  still, 6.9 W turning, against about 21 W at 60 fps. macOS Low Power Mode turns it on.
- **While you're away** (a minute without input), the last picture is held, compressed by the GPU (M2 and later), and the
  pack's targets and Java heap are released: BSL went from 2,609 to 1,922 MB and 23.6 W to 3.9 W. Any input resumes.
- **Heat Control** keeps fanless Macs at a steady frame rate instead of throttling.

### Replaces

| Instead of | Cadence |
|---|---|
| Sodium | Metal renderer; chunks culled on the GPU (frustum, occlusion, back faces) |
| Iris | Shader packs, pack options, Iris API |
| Lithium | Faster entity searches, mob AI and block-entity ticking; same game behaviour ([docs/GAME-LOGIC.md](docs/GAME-LOGIC.md)) |
| Entity Culling | Entities, block entities and particles behind walls aren't drawn |
| Distant Horizons | Far Terrain (experimental), up to 4,096 chunks |
| Bobby | Keep Server Chunks, saved to disk |
| Krypton (client) | Native network decryption and decompression |
| LambDynamicLights | Dynamic Lights on the GPU |

### Also

- Instant Replay (F10), Photo Mode (F9), a performance overlay and battery-time estimate.
- HDR, ray-traced shadows and lighting, Neural Engine upscaling and textures, spatial audio, macOS Game Mode (normally not
  enabled for Minecraft).
- Threads placed on the right kind of core (Performance "P" Cores vs Efficiency "E" Cores, and Super "S" Cores on newer chips).
- Video Settings > Java can switch to Vivace, a separately downloaded Java build with shorter garbage-collection pauses.
- Minimal Renderer, an attempt at the Minecraft fps record ([docs/MINIMAL-RENDERER.md](docs/MINIMAL-RENDERER.md)).

## Requirements

- Apple Silicon Mac (M1 or newer), macOS 26 or later
- Minecraft 26.3, Fabric Loader 0.19.5 or newer
- Java 25 or newer (the official launcher, Prism and CurseForge provide it)

## Install

1. Make a Minecraft 26.3 Fabric instance (https://fabricmc.net/use/installer/, Prism or CurseForge).
2. Download `cadence-<version>.jar` from the Releases page and put it in your `mods` folder.
3. Remove Sodium, Iris and mods that require them. Lithium, Entity Culling and dynamic-lights mods aren't needed.
4. For shaders, press O in game (or Video Settings > Shaders...) and drop pack `.zip` files onto the list.

F3 shows `Metal 3 (Cadence)` at the bottom right when Cadence is running.

## Settings

Everything is in Options > Video Settings, with a tooltip per setting. Keys: O shader packs, K shaders on/off, F9 Photo
Mode, F10 Instant Replay. Config is in `config/cadence.properties` and `config/cadence-shaders.properties`; every switch is
also a `-Dcadence.*` JVM flag. Menus follow the game's language (16 languages, machine-translated).

## Known issues

- Mods that require Sodium, Iris internals, or draw with OpenGL directly don't work while the Metal renderer is on
  ([docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)). `-Dcadence.metal=false` turns the Metal renderer off.
- Shrimple and Rethinking Voxels need geometry or tessellation shaders, which Metal lacks.
- PBR resource packs (labPBR, OldPBR) aren't read yet.
- Far Terrain is experimental.

## Building

With JDK 25: `./gradlew build`, then `tools/release` for the single jar and zip in `dist/`. See
[CONTRIBUTING.md](CONTRIBUTING.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Credits and license

Cadence is a fork of [mcopt](https://github.com/noahdunnagan/mcopt) by Noah Dunnagan (Apache-2.0), the Metal backend, and
the Shader Loader of [minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) by Kausik
Velaga and contributors (MIT). Licensed under [Apache 2.0](LICENSE); keep [NOTICE](NOTICE) with any copy. See
[CREDITS.md](CREDITS.md). Minecraft is a trademark of Mojang; Cadence is not affiliated with Mojang or Microsoft.
