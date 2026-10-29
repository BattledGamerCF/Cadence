# For mod-platform reviewers

A short guide to what Cadence does on a player's computer, to make reviewing it quick. The full source is in this
repository; the release jar is built from it by `tools/release` (Gradle plus `tools/bundle.py`, no other inputs).

## What it is

A client-side performance mod for Minecraft 26.3 (Fabric) on Apple Silicon Macs: Minecraft drawn with Apple's Metal API
instead of OpenGL, plus engine optimizations, shader-pack support and a far-terrain renderer. Four mods in one jar
(`cadence`, `cadence_shaders`, `cadence_hud`, `cadence_iris_api`: Iris's public API answered by Cadence for mods that use
it; docs/ARCHITECTURE.md). The release zip also holds an optional jar with no code, `cadence-iris-provider` (mod id `iris`,
`1.11.4+cadence`), for mods that check for Iris by its mod id. It runs only on macOS on Apple Silicon and declares
`environment: client`.

## Native code

- `libmcmetal.dylib` (arm64) is built from the Objective-C and C sources in `metal/src/main/native` by the Gradle build
  (`clang`, no prebuilt binaries). At startup it is extracted from the jar to the system temp directory and loaded with the
  Java Foreign Function & Memory API (`dev.cadence.metal.Native`; if the temp directory can't be used, a
  `.cadence-natives` folder in the game directory). It links Apple's Metal, MetalFX, QuartzCore, CoreVideo, CoreGraphics,
  IOKit, AppKit, AVFAudio (the game's sound, on AVAudioEngine: output only, the microphone is never opened) and Foundation
  frameworks only. VideoToolbox, CoreMedia and AVFoundation are opened at run time (dlopen) only
  when the player turns on Instant Replay (and AVFoundation only to save one) or Frame Generation: Neural (VideoToolbox's
  low-latency frame interpolation). MetalFX's denoiser is used only for path-traced Photo Mode shots. IOKit is read for the battery's charge, the
  power source, the fans and the whole Mac's power draw (the System Management Controller's `PSTR` key; reads only, nothing
  is ever written), and once per launch `/usr/sbin/ioreg` is run to read the GPU's core count.
- Core ML and VideoToolbox's ML frame processors are opened at run time (dlopen, classes by name) only when the player turns
  on a Neural Engine feature (Style Filter, Smooth Textures, the Neural Engine upscaler). Metal's ray tracing (acceleration
  structures) is used only with Ray Tracing on.
- **Cadence Helper** (`metal/src/main/helper`: one Swift file and an Info.plist) is built by the Gradle build with `swiftc`,
  signed ad hoc, shipped inside the jar and unpacked into `~/Library/Application Support/Cadence/Cadence Helper.app` at the
  first launch (Game Mode, on by default, uses it). It does three things: trains a Style Filter's network with Apple's Create ML (on the Mac, nothing
  downloaded) and, for Voice Control, recognises speech with Apple's Speech framework set to on-device recognition only
  (`requiresOnDeviceRecognition`), each only when the player turns that feature on; and for Game Mode (on by default) it is
  what the game's first process becomes: it waits for the game, passes it the launcher's quit signals and exits with its
  exit code. It runs as its own app so that macOS asks the player for the microphone and speech recognition for
  "Cadence Helper"; Java never touches the microphone.
- **Game Mode** (`GameMode`): macOS gives Game Mode only to an app whose Info.plist says it is a game, decided when the
  process starts. At the restart Cadence already does at launch (to add its Java options), it starts the game as a new
  process from `~/Library/Application Support/Cadence/Game/<id>/Minecraft.app`: an Info.plist that says "game", a copy of the
  same Java's `java` launcher (signature unchanged) and links to that Java's own folders. Nothing is installed system-wide;
  `-Dcadence.gameMode=false` or `cadence.gameMode=false` in `config/cadence.properties` turns it off. If the waiting first
  process is killed outright, the game notices within a second and quits normally (saving the world).
- **App Nap**: while the game is in the background with its world open to LAN, Cadence tells macOS the game is busy
  (`beginActivityWithOptions`, idle sleep still allowed), so the server keeps running at full speed for the other players.
- Thread priorities: the render thread runs at macOS's user-interactive quality of service, the game's server and worker threads lower.
- GPU shaders (`*.metal` under `metal/src/main/resources/cadence`) are compiled by Metal at runtime from source.

## Network

None. Cadence makes no network connections (no update checks, telemetry or downloads). The optional Vivace Java runtime
(Video Settings > Java) is a separate download the player installs themselves; Cadence only points the launcher's Java
setting at it when the player chooses it.

## Files it writes

All inside the Minecraft game directory unless noted:

| Where | What |
|---|---|
| `config/cadence.properties`, `config/cadence-shaders.properties` | Settings. |
| `cadence-cache/` | Caches: translated shader-pack shaders (`translated/`, `spirv/`; `msl/` only with a developer flag) and `pack-pipelines.txt` (the pipelines a pack used last time, made ahead on the next switch), kept under a size budget (safe to delete). |
| `cadence-lod/<world or server>/<dimension>/` | Far Terrain's saved land, when the player turns Far Terrain on (safe to delete). |
| `cadence-chunks/servers/<address>-<seed>/<dimension>/` | Keep Server Chunks: a multiplayer server's chunks as the client last had them (vanilla region files holding the server's own chunk packets), so a world or dimension shows its land at once when the player comes back; kept under 512 MB, least recently written first; nothing in single player (safe to delete). |
| `replays/` | Instant Replay's videos (mp4), only when the player turns it on and presses Save Replay. |
| `screenshots/cadence-photo-*.png` | Photo Mode's screenshots (F9), where the game's own screenshots go. |
| `screenshots/` | Benchmark result cards when the player saves them. |
| system temp directory | The extracted native library (`mcmetal*/libmcmetal.dylib`). |
| `~/Library/Application Support/Cadence/` | Cadence Helper.app (unpacked from the jar, with `helper-stamp.txt`); `styles/`: Style Filters' trained networks, the paintings they were trained on (from the game's resources) and `content/`, the pictures of the player's world used for training (copies of their screenshots or frames taken while they play); `voice-PID-N.txt`, the phrases Voice Control heard, deleted by the helper when Voice Control stops or the game exits (the helper exits when the game's process is gone). `Game/`: the Game Mode bundle (remade when needed). All safe to delete. |
| files named by `-D` flags | Measurement logs and recordings, only when a developer flag asks for them. |

## Code that changes Minecraft

Mixins, listed in each module's `*.mixins.json`. Optimizations that are not on by default sit behind `-Dcadence.*` switches
checked by the mixin plugins (`*MixinPlugin.java`): with a switch off, its mixins are not applied. `@Overwrite` is used
where a method is replaced whole: entity model vertex writing (`mixin.cpu.ModelCubeWriteMixin`), Cadence Logic's
game-logic replacements (`mixin.logic`: brains, POI sensors, path-type cache, block-entity ticking, entity sections) and
one opt-in server tick change (`mixin.chunk.ServerLevelTickMixin`). `dev.cadence.metal.compat` hands each of those back to
Minecraft's own code when another mod hooks the same method.

## Licences and origins

Apache-2.0 (Cadence, derived from mcopt by Noah Dunnagan, Apache-2.0) and MIT (the shader loader, derived from Kausik
Velaga's Minecraft Shader Loader). LICENSE, NOTICE and CREDITS.md list every upstream and its licence. The `irisapi/` module
declares Iris's public API interfaces (`net.irisshaders.iris.api.v0`) again from their published names and signatures, for
compatibility; it contains no Iris code (CREDITS.md).
