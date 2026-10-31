# Cadence

**Minecraft on Apple Silicon, on Metal. One mod instead of the whole Sodium stack.** Cadence replaces Minecraft's OpenGL
renderer with a native Apple Metal renderer, runs **shader packs directly on Metal**, and brings its own versions of
Sodium, Iris, Lithium, Entity Culling, Distant Horizons and dynamic lights, built for Apple's GPUs.

> ⚠️ **Beta.** Expect the occasional bug and back up your worlds.

## Features

- 🍎 **Native Metal renderer** for Apple Silicon Macs (M1 and newer): no OpenGL translation
- ⚡ **Very high fps without shaders:** about 1,600 fps at 1080p (render distance 6), 1,080 at render distance 12, on an
  M2 MacBook Air; with the Max FPS preset fullscreen, 2,155 fps (distance 6) and 1,391 (render and simulation distance 16)
- 🌅 **Shader packs on the base Macs.** OptiFine/Iris-format packs translated to Metal and cached; **Auto resolution**
  with Apple's MetalFX, **Faster Shadows** and **Heat Control** keep most packs at 60 fps on an 8-core M2 MacBook Air
- 🧱 **Replaces the Sodium stack:** Sodium (renderer, GPU culling), Iris (shaders, and its API for other mods), Lithium
  (Cadence Logic, exact), Entity Culling, Bobby (Keep Server Chunks), Distant Horizons (Far Terrain, experimental), dynamic
  lights, an fps counter, all in Video Settings
- 🎞️ **Frame Generation, Instant Replay, Photo Mode,** a Performance Overlay and battery time left
- 🏁 **Render Max FPS:** a playable, lit 320 x 180 view at up to 66,000 frames rendered a second (Cadence's run at the
  Minecraft FPS record)
- 🧩 **Plays well with other mods.** Where another mod hooks code Cadence replaces, Cadence uses Minecraft's own code in
  that one area and keeps everything else fast

## Tested shader packs

All of these render correctly (day, night, rain, underwater, Nether, End):

BSL · Complementary Reimagined · Complementary Unbound · Bliss · MakeUp UltraFast · I Like Vanilla · Insanity ·
Sildur's Vibrant (1.541 and 2.02) · Sildur's Enhanced Default · VanillaEX · Pegasus

Most hold 60 fps with Auto resolution on an M2 MacBook Air. Heavy packs like Pegasus run best on their **Medium**
preset. Shader packs are not included; download them from their authors.

## Install

1. Minecraft **26.3** with **Fabric Loader 0.19.5+** (Java 25; launchers download it for you)
2. Put the Cadence jar in your `mods` folder, and shader packs in `shaderpacks`
3. Choose a pack in **Options → Video Settings** (Cadence: Shaders); **Shader Pack Settings** has its options

**Remove Sodium, Iris, Lithium, Entity Culling and Bobby:** Cadence replaces them. Mods that use Iris's API work (Cadence
answers it); mods that draw with OpenGL directly won't work while the Metal renderer is on.

## Credits

Cadence is an open-source fork of **[mcopt](https://github.com/noahdunnagan/mcopt)** by Noah Dunnagan (Apache-2.0) and of
the Shader Loader from **[minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer)** by Kausik
Velaga and contributors (MIT). Licensed under Apache-2.0.

Source, issues and full credits: https://github.com/BattledGamerCF/Cadence

*Not affiliated with Mojang or Microsoft.*
