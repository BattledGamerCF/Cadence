Cadence @VERSION@

A Minecraft performance mod for Apple Silicon Macs. No Sodium needed. Cadence is a fork of mcopt by Noah Dunnagan
(Apache-2.0) and of the Shader Loader of minecraft-metal-renderer by Kausik Velaga (MIT); see CREDITS.md. BETA: expect the occasional bug, and back up your
worlds.

You need
- An Apple Silicon Mac (M1 or newer) on macOS 26 or later
- Java 25 (the official launcher and Prism download it for 26.3; nothing to install)
- Minecraft: Java Edition 26.3
- Fabric Loader 0.19.5 or newer for 26.3

Install
1. Install Fabric for Minecraft 26.3: https://fabricmc.net/use/installer/
   (or in Prism Launcher: new instance, version 26.3, loader Fabric)
2. Put cadence-@VERSION@.jar in your mods folder.
   Official launcher: ~/Library/Application Support/minecraft/mods
   (in Finder: Go > Go to Folder, paste that path; create the mods folder if it's missing)
   In Prism: Edit the instance > Mods > Add file.
3. Start Minecraft with the Fabric profile.

No Sodium needed: Cadence won't start alongside it (Fabric says so), so take Sodium out of the mods folder.
Fabric API isn't required; keep it if your other mods need it.
Mods that require Sodium (for example Better Block Entities or Iris) can't be used with this build.
Distant Horizons runs on the Metal renderer next to Cadence's own terrain. With DH set to its OpenGL renderer, Cadence
switches its renderer off for it.

The jar is a mod, not an app: double-clicking it only shows these install steps.

Shader packs: put OptiFine/Iris shader packs (.zip) in the shaderpacks folder next to mods, then Options > Video
Settings > Shaders. They run natively on Metal (no OpenGL); the first time you turn a pack on it is translated, which
takes a few seconds, then it's cached. Tested: BSL, Complementary Reimagined and Unbound, Bliss, MakeUp UltraFast,
Sildur's Vibrant and Enhanced Default, I Like Vanilla, Insanity, VanillaEX, Pegasus (very heavy). Resolution "Auto"
lowers the pack's render resolution to hold your frame rate. If a pack can't run, Cadence turns shaders off and says
why in chat.

For the most fps: Options > Video Settings, VSync off and Max Framerate Unlimited.
Shows an fps counter in the top-left corner (hidden with F1 or F3; fn + F1 or fn + F3 on a Mac keyboard).

On a computer the mod can't run on (not an Apple Silicon Mac, or macOS older than 26), the game stops at launch
and says what's missing; remove the Cadence jar from the mods folder to play there.
To check it's working: press F3 (fn + F3 on a Mac keyboard); the bottom right says Metal 3 (Cadence).

The first launch writes config/cadence.properties in your game folder (in Prism, the instance's minecraft/config
folder); profile=none there turns the perf profile off.
To switch Cadence's renderer off, add cadence.metal=false to that file (or launch with -Dcadence.metal=false).
Settings from before the rename (config/mcopt.properties, -Dmcopt.* flags) are still read.

Licence: Apache-2.0 (LICENSE, NOTICE, CREDITS.md); includes MIT-licensed code from minecraft-metal-renderer
(shaders/LICENSE-MIT-shader-loader).
