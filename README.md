# Cadence

**Minecraft on Apple Silicon, drawn with Metal. One mod instead of the whole Sodium stack.** Cadence replaces Minecraft's
OpenGL renderer with a native Apple **Metal** renderer, runs **shader packs** on it (BSL, Complementary, Bliss and 20 more),
and brings its own versions of what Sodium, Iris, Lithium, Entity Culling, Distant Horizons and dynamic-lights mods do,
built for Apple's GPUs and tested together.

> **Beta software.** Expect the occasional bug and back up your worlds before trying it.

## Why Cadence

**Frame Generation.** Twice the frames on screen for the frames rendered, with and without shader packs: a frame made between
each two by reprojecting the world (the HUD, chat and your hand stay sharp). With a shader pack, Battery Saver renders 30 and
shows 60 (BSL at render distance 16: 11-12 W for the whole Mac instead of 21 W at 60 fps); on a 120 Hz screen Recommended and
Max Quality render 60 and show 120. Smooth (between the last two frames) or
Responsive (the newest frame turned to where your mouse looks now: no added delay), or Off; the counter shows "60 fps
(30 rendered)".

**Shader packs on the base Macs.** The packs run on Metal (no OpenGL translation), and Cadence keeps them playable on an
8-core M2 MacBook Air, the entry-level Apple Silicon GPU, where most packs hold 60 fps:
- **Auto resolution:** the pack renders at a lower internal resolution when the Mac can't hold the target frame rate,
  and Apple's MetalFX upscales it; the packs' anti-aliasing history survives the changes, so the picture stays clean.
- **Faster Shadows:** the shadow pass reused every other frame, far shadows drawn as simple proxies, and casters that
  can't shadow anything skipped.
- **Heat Control:** fanless Macs hold a steady frame rate instead of overheating and throttling.
- **Quick Setup** tests the Mac once and picks settings; the Shader Packs list marks each pack Light, Medium or Heavy.
- **Quick pack switching:** turning a pack on pauses the game for about 0.2 s (Complementary took up to 4 s before
  0.4.0-beta.26).

**Very high frame rates without shaders.** Measured on an M2 MacBook Air (8 GB, 8-core GPU) with Cadence's benchmark
(Video Settings > Benchmark), no frame cap:

| Window | Render distance | Average fps | 1% low |
|---|---|---|---|
| 1920 x 1080 | 6 chunks | 1,641 | 719 |
| 1920 x 1080 | 12 chunks | 1,079 | 490 |
| 2940 x 1846 (the Air's full Retina screen) | 6 chunks | 900 | 419 |
| Fullscreen, Max FPS preset (Retina off, 1470 x 923, fast leaves, no clouds) | 6 chunks | 2,155 | 942 |
| Fullscreen, Max FPS preset | 12 chunks | 1,598 | 680 |
| Fullscreen, Max FPS preset, simulation distance 16 | 16 chunks | 1,391 | 607 |

And the **Minimal Renderer** (Video Settings > Cadence: Speed) is Cadence's run at the Minecraft FPS record: a playable,
lit, ray-traced 320 x 180 view at up to 66,000 frames rendered a second (132,000 shown with frame interpolation, counted
apart); about 30,000 with the land in view. See [docs/MINIMAL-RENDERER.md](docs/MINIMAL-RENDERER.md).

**The Sodium stack in one jar.** Remove these; Cadence does their job (each part measured, most switchable):

| Instead of | Cadence |
|---|---|
| **Sodium** | The Metal renderer and its own terrain path: chunk meshes in one GPU arena, culled on the GPU (frustum, occlusion with a depth pyramid, faces turned away), meshing caches, faster entity and item vertex writing |
| **Iris** | Shader packs (Iris/OptiFine format: compute passes, custom images, buffer objects, Distant Horizons programs), per-pack options in the same files Iris uses, live switching, and the **Iris API** for other mods: calls to Iris's public API (is a pack on, the shadow pass, the sun angle, the shader screen) get Cadence's answers |
| **Lithium** | Cadence Logic: entity searches up to 22x faster, mob brains and villager sensors without per-tick garbage, block entities ticked only when they need it. Exact: the game plays the same ([docs/GAME-LOGIC.md](docs/GAME-LOGIC.md)) |
| **Entity Culling** | Entities, block entities and particles behind walls aren't drawn |
| **Bobby** | Keep Server Chunks: on a server whose view distance is shorter than yours, the chunks it stops sending stay within your render distance, so you see your full distance as you travel; saved on disk, so a world or dimension you come back to is there at once |
| **Distant Horizons** | Far Terrain (experimental): land past the render distance, out to a **Far Distance** of up to 4,096 chunks (65 km; a slider in Video Settings, applied while you play), full detail to 440 blocks (Far Terrain > Full Detail: 190, 440 or 960), generated from the world's own generator or from explored land on servers, drawn by shader packs that support Distant Horizons |
| **LambDynamicLights** | Dynamic Lights: held torches and glowing entities light the world around them, on the GPU |
| **More Culling / Cull Leaves** | Smart Leaves (opt-in): leaf faces hidden inside a canopy skipped |
| **Sodium's options, Reese's Sodium Options** | Everything in Options > Video Settings, with tooltips and a Performance preset |
| **An fps counter** | Cadence HUD |

Also: **Instant Replay** (the last 30 seconds saved as a video with F10, by the Mac's video encoder), **Photo Mode** (F9: a
full-resolution, full-quality screenshot), a **Performance Overlay** (frames, GPU time, heat, power), **battery time left** at
the current draw, macOS **Low Power Mode** followed as Battery Saver, **Auto Render Distance** for shader packs, and the game's
sound on **Apple's audio engine** instead of OpenAL (spatial audio on headphones and AirPods; Options > Music & Sounds).

**Apple hardware** (Video Settings > Cadence: Visuals; Voice Control under Extras): **HDR** (light sources
brighter than white on the display's extended range), **ray-traced shadows and lighting** (a voxel tracer on M1/M2, Metal's
ray tracing hardware on M3 and newer), **Torch Shadows** (Ray Tracing's "+ Torches": every torch, lantern and lava pool casts ray-traced shadows),
**Neural Lighting** (the bounced light cached by a small neural network that learns the world as you play, trained on the
GPU), denoised **path-traced Photo Mode** shots (about a second), and the **Neural Engine** at work: **Style Filters** (the
world repainted like one of the game's paintings, trained on your Mac), **Smooth Textures** (block textures upscaled 2x by
Apple's ML super resolution), a Neural Engine **upscaler** for World Resolution (with shader packs too: BSL 80 fps instead of 74
at 50%), **Frame Generation: Neural** (Apple's own
frame interpolation) and **Voice Control** (spoken commands, recognised on your Mac only). **Biome Colours** grades the picture
for where you are. **Game Mode**: macOS's Game Mode (priority for the full-screen game, lower Bluetooth latency) turns on
for Minecraft, which a launcher's Java never gets. Sound: **World Reverb** (caves echo, walls
muffle) and **Sound Around Corners** (sounds come through the doorway they really come by).

On top of that: a garbage-collection policy for games, faster startup (about 15% sooner in a world: block data built in
parallel, cached bootstrap data, a quicker loading fade), lighter chunk storage, threads placed on the right kind of core,
exact frame caps and even pacing, and the optional Vivace Java build. Where another mod hooks code Cadence replaces,
Cadence runs Minecraft's own code in that one area and keeps everything else
([docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)). The full list: [docs/COVERAGE.md](docs/COVERAGE.md). Every number,
how it was measured, and power draw: [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

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

Cadence replaces Sodium and Iris, so remove them (and the mods that need them) from the `mods` folder. Lithium, Entity
Culling and a dynamic-lights mod aren't needed either (if Lithium is installed, Cadence's game-logic optimizations step
aside for it). Fabric API isn't required, but keep it if your other mods need it.

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
  as in Iris, with Apply), then the Performance preset and Quick Setup. Below them, **Cadence: Visuals** (the settings that cost
  frames for the picture: Far Terrain and Far Distance, Ray Tracing (with or without Torch Shadows; the tracer is picked for your chip), HDR,
  Dynamic Lights, leaves, Style Filter, Smooth Textures, Biome Colours; each tooltip ends with its performance impact, as in Sodium: Low in green, Medium in
  yellow, High in gold), **Cadence: Speed** (Sharpness & Speed, Frame Generation, Auto Render Distance, Keep Server Chunks, Minimal Renderer),
  **Cadence: Power & Heat** (cooling, On Battery, In Background) and **Cadence: Extras** (Benchmark, Overlay: Off / FPS / Full,
  messages, Instant Replay, Voice Control, Java).
- **Keys:** **O** opens Shader Packs, **K** turns shaders off and back on, **F9** takes a Photo Mode shot, **F10** saves an
  Instant Replay (Controls > Key Binds > Miscellaneous).
- **Shader pack options** are kept per pack in `shaderpacks/<pack>.txt`, the same file Iris uses.
- **Config files:** `config/cadence.properties` and `config/cadence-shaders.properties` in your Minecraft folder.
  Set `profile=none` in `cadence.properties` to turn off Cadence's performance profile.
- **Advanced:** every switch is also a `-Dcadence.*` JVM flag. To turn off the Metal renderer for a mod that needs
  OpenGL, use `cadence.metal=false`.

## Known issues

- Mods that require Sodium, or Iris's internals beyond its API, or that draw with OpenGL directly, can't be used while the
  Metal renderer is on. Mods that call the Iris API work (Cadence answers it); a mod that only uses it after checking that
  the mod `iris` is installed needs the optional `cadence-iris-provider` jar from the release zip ([docs/API.md](docs/API.md#the-iris-api)).
  Mods that use Minecraft's own rendering API work; where one hooks code Cadence replaces, Cadence steps aside in that
  area (compatibility layers, first version). Addon mods can fill the gap for OpenGL-only mods with Cadence's API
  (render hooks, Metal handles, step-aside requests): [docs/API.md](docs/API.md).
- Far terrain (Video Settings > Far Terrain) is experimental. On the 8-core M2 Air it costs about 0.4 ms a frame while you
  turn (494-504 fps instead of 603 at render distance 16, fullscreen Retina) and one 5% step of resolution with a shader pack
  ([docs/BENCHMARKS.md](docs/BENCHMARKS.md)); GPUs of 6 cores or fewer use Low quality. With a shader pack it's drawn by
  packs that support Distant Horizons (their dh_terrain program); the Nether has none.
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

This builds the four mods into `metal/build/libs`, `shaders/build/libs`, `fpshud/build/libs` and `irisapi/build/libs`.
To make the single release jar and zip in `dist/`, run:

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
| [`irisapi/`](irisapi) | **Cadence Iris API**: Iris's public API answered by Cadence (mod id `cadence_iris_api`; the optional provider in `packaging/iris-provider` claims the id `iris`) |
| [`tools/`](tools) | Release packaging, the automated in-game test harness, benchmarks |
| [`docs/`](docs) | How it's built, shader-pack status, compatibility, the Minimal Renderer, changes from mcopt |
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
