# Cadence

**Minecraft on Apple Silicon, on Metal.** Cadence replaces Minecraft's OpenGL renderer with a native Apple Metal renderer
and runs **shader packs directly on Metal**, with no Iris, Sodium or OpenGL translation in between.

> ⚠️ **Beta.** Expect the occasional bug and back up your worlds.

## Features

- 🍎 **Native Metal renderer** for Apple Silicon Macs (M1 and newer)
- 🌅 **Shader packs on Metal.** OptiFine/Iris-format packs are translated to Metal and cached. Compute passes, custom
  images and per-buffer blending are supported.
- 📐 **Auto resolution with MetalFX.** Shaders render at a lower internal resolution when needed and are upscaled by
  Apple's MetalFX, without breaking the packs' anti-aliasing.
- 🌡️ **Heat control.** Fanless MacBook Airs hold a steady frame rate instead of overheating and stuttering.
- ⚡ **Faster terrain, startup and memory use**, plus a built-in fps counter

## Tested shader packs

All of these render correctly (day, night, rain, underwater, Nether, End):

BSL · Complementary Reimagined · Complementary Unbound · Bliss · MakeUp UltraFast · I Like Vanilla · Insanity ·
Sildur's Vibrant (1.541 and 2.02) · Sildur's Enhanced Default · VanillaEX · Pegasus

Most hold 60 fps with Auto resolution on an M2 MacBook Air. Heavy packs like Pegasus run best on their **Medium**
preset. Shader packs are not included; download them from their authors.

## Install

1. Minecraft **26.3** with **Fabric Loader 0.19.5+** (Java 25; launchers download it for you)
2. Put the Cadence jar in your `mods` folder, and shader packs in `shaderpacks`
3. Choose a pack in **Options → Video Settings → Shaders**

**Remove Sodium and Iris:** Cadence replaces them. Mods that draw with OpenGL directly won't work while the Metal
renderer is on.

## Credits

Cadence is an open-source fork of **[mcopt](https://github.com/noahdunnagan/mcopt)** by Noah Dunnagan (Apache-2.0) and of
the Shader Loader from **[minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer)** by Kausik
Velaga and contributors (MIT). Licensed under Apache-2.0.

Source, issues and full credits: https://github.com/BattledGamerCF/Cadence

*Not affiliated with Mojang or Microsoft.*
