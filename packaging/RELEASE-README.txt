Cadence @VERSION@

A Minecraft performance mod for Apple Silicon Macs. It draws Minecraft with Metal, runs shader packs on that renderer,
and includes Cadence's own versions of Sodium, Iris, Lithium, Entity Culling, Bobby, far terrain and dynamic lights in
one jar.

On an M2 MacBook Air: about 1,600 fps without shaders (1080p, render distance 6), and most shader packs at 60 fps with
Auto resolution.

Cadence is a fork of mcopt by Noah Dunnagan (Apache-2.0) and of the Shader Loader of minecraft-metal-renderer by Kausik
Velaga (MIT). See CREDITS.md.

This is a beta. Expect the occasional bug, and back up your worlds.

You need
- An Apple Silicon Mac (M1 or newer) on macOS 26 or later
- Java 25 (the official launcher and Prism download it for 26.3, so there is nothing to install)
- Minecraft: Java Edition 26.3
- Fabric Loader 0.19.5 or newer for 26.3

Install
1. Install Fabric for Minecraft 26.3: https://fabricmc.net/use/installer/
   (or in Prism Launcher: new instance, version 26.3, loader Fabric)
2. Put cadence-@VERSION@.jar in your mods folder.
   Official launcher: ~/Library/Application Support/minecraft/mods
   (in Finder: Go > Go to Folder, paste that path; create the mods folder if it is missing)
   In Prism: Edit the instance > Mods > Add file.
3. Start Minecraft with the Fabric profile.

The jar is a mod, not an app. Double-clicking it only shows these install steps.

Other mods
- Sodium is not needed. Cadence will not start alongside it (Fabric names the conflict), so remove Sodium from the
  mods folder.
- Fabric API is not required. Keep it if your other mods need it.
- Mods that require Sodium (for example Better Block Entities, or Iris itself) cannot be used with this build.
- Mods that use Iris's API, for example to ask whether shaders are on, work. Cadence answers the API.
- A mod that uses the API only once it sees Iris installed needs the optional cadence-iris-provider jar from this zip in
  the mods folder as well. Do not use it with ImmediatelyFast or Distant Horizons. Fabric will name the conflict.
- Distant Horizons runs on the Metal renderer next to Cadence's own terrain. With DH set to its OpenGL renderer,
  Cadence switches its renderer off for it.

Shader packs
- Open Options > Video Settings > Shaders... (or press O in game). The list shows the packs in the shaderpacks folder
  next to mods.
- Drop OptiFine/Iris shader pack .zip files onto the list to add them. Click one to turn it on.
- K turns shaders off and back on.
- Packs run natively on Metal, without OpenGL. The first time you turn a pack on, it is translated, which takes a few
  seconds. The result is cached.
- 23 of 25 tested packs work. They include BSL, Complementary, Bliss, Photon, Solas, Sildur's, MakeUp UltraFast, Mellow,
  Kappa and Pegasus (very heavy). The list says which packs were tested and how heavy each is.
- Resolution "Auto" lowers the pack's render resolution to hold your frame rate.
- If a pack cannot run, Cadence turns shaders off and says why, in chat and next to the pack in the list.

For the most fps
- In Options > Video Settings, set VSync off and Max Framerate Unlimited.
- An fps counter is shown in the top-left corner. F1 or F3 hides it (fn + F1 or fn + F3 on a Mac keyboard).

Checking that it works
- Press F3 (fn + F3 on a Mac keyboard). The bottom right says Metal 3 (Cadence).
- On a computer the mod cannot run on (not an Apple Silicon Mac, or macOS older than 26), the game stops at launch and
  says what is missing. Remove the Cadence jar from the mods folder to play there.

Configuration
- The first launch writes config/cadence.properties in your game folder (in Prism, the instance's minecraft/config
  folder). profile=none there turns the perf profile off.
- To switch Cadence's renderer off, add cadence.metal=false to that file, or launch with -Dcadence.metal=false.
- Settings from before the rename (config/mcopt.properties, -Dmcopt.* flags) are still read.

Licence: Apache-2.0 (LICENSE, NOTICE, CREDITS.md). It includes MIT-licensed code from minecraft-metal-renderer
(shaders/LICENSE-MIT-shader-loader).
