# For mod-platform reviewers

This guide describes what Cadence does on a player's computer, to make review quick. The full source is in this
repository. The release jar is built from it by `tools/release` (Gradle plus `tools/bundle.py`, no other inputs).

## What it is

Cadence is a client-side performance mod for Minecraft 26.3 (Fabric) on Apple Silicon Macs. It draws Minecraft with Apple's
Metal API instead of OpenGL, and adds engine optimizations, shader-pack support and a far-terrain renderer.

- One jar holds four mods: `cadence`, `cadence_shaders`, `cadence_hud` and `cadence_iris_api` (see docs/ARCHITECTURE.md).
  `cadence_iris_api` answers Iris's public API for mods that use it.
- The release zip also holds an optional jar with no code, `cadence-iris-provider` (mod id `iris`, `1.11.4+cadence`). It is
  for mods that check for Iris by its mod id.
- Cadence runs only on macOS on Apple Silicon and declares `environment: client`.

## Native code

- `libmcmetal.dylib` (arm64) is built from the Objective-C and C sources in `metal/src/main/native` by the Gradle build
  (`clang`, no prebuilt binaries). At startup it is extracted from the jar to the system temp directory and loaded with the
  Java Foreign Function & Memory API (`dev.cadence.metal.backend.Native`). If the temp directory can't be used, it goes in a
  `.cadence-natives` folder in the game directory.
  - It links only these frameworks: Apple's Metal, MetalFX, QuartzCore, CoreVideo, CoreGraphics, IOKit, AppKit, AVFAudio
    and Foundation. It also links the system's zlib (`libz`, for the network path's decompression).
  - AVFAudio carries the game's sound, on AVAudioEngine. It is used for output only, and the microphone is never opened.
  - VideoToolbox, CoreMedia and AVFoundation are opened at run time (dlopen), and only when the player turns on Instant
    Replay or Frame Generation: Neural. Instant Replay uses AVFoundation only to save a replay. Frame Generation: Neural
    uses VideoToolbox's low-latency frame interpolation.
  - MetalFX's denoiser is used only for path-traced Photo Mode shots.
  - IOKit is read for the battery's charge, the power source, the fans and the whole Mac's power draw (the System
    Management Controller's `PSTR` key). These are reads only, and nothing is ever written.
  - Once per launch, `/usr/sbin/ioreg` is run to read the GPU's core count.
- Core ML and VideoToolbox's ML frame processors are opened at run time (dlopen, classes by name). This happens only when
  the player turns on a Neural Engine feature (Style Filter, Smooth Textures, the Neural Engine upscaler). Metal's ray
  tracing (acceleration structures) is used only with Ray Tracing on.
- **Cadence Helper** (`metal/src/main/helper`: one Swift file and an Info.plist) is built by the Gradle build with
  `swiftc` and signed ad hoc. It ships inside the jar and is unpacked into
  `~/Library/Application Support/Cadence/Cadence Helper.app` at the first launch. Game Mode, which is on by default, uses
  it. The helper does three things:
  - It trains a Style Filter's network with Apple's Create ML. This happens on the Mac, and nothing is downloaded.
  - For Voice Control, it recognises speech with Apple's Speech framework, set to on-device recognition only
    (`requiresOnDeviceRecognition`).
  - For Game Mode, it becomes the game's first process. It waits for the game, passes it the launcher's quit signals and
    exits with its exit code.

  The first two run only when the player turns on the matching feature. The helper runs as its own app so that macOS asks
  the player for the microphone and speech recognition for "Cadence Helper". Java never touches the microphone.
- **Game Mode** (`GameMode`): macOS gives Game Mode only to an app whose Info.plist says it is a game, and decides this
  when the process starts. Cadence already restarts the game at launch to add its Java options. At that restart, it starts
  the game as a new process from `~/Library/Application Support/Cadence/Game/<id>/Minecraft.app`. That bundle contains an
  Info.plist that says "game", a copy of the same Java's `java` launcher (signature unchanged) and links to that Java's own
  folders. Nothing is installed system-wide. `-Dcadence.gameMode=false` or `cadence.gameMode=false` in
  `config/cadence.properties` turns it off. If the waiting first process is killed outright, the game notices within a
  second and quits normally, saving the world.
- **App Nap**: while the game is in the background with its world open to LAN, Cadence tells macOS the game is busy
  (`beginActivityWithOptions`, idle sleep still allowed). This keeps the server running at full speed for the other players.
- Thread priorities: the render thread runs at macOS's user-interactive quality of service. The game's server and worker
  threads run lower.
- GPU shaders (`*.metal` under `metal/src/main/resources/cadence`) are compiled by Metal at runtime from source.

## Network

Cadence makes no network connections of its own. It has no update checks, telemetry or downloads.

On a multiplayer server, Cadence decrypts and decompresses the game's own connection natively (`dev.cadence.metal.net`).
It uses the same bytes as Minecraft's stages. Nothing is sent, added or read beyond what Minecraft receives.

The optional Vivace Java runtime (Video Settings > Java) is a separate download that the player installs. Cadence only
points the launcher's Java setting at it when the player chooses it.

## Files it writes

All paths are inside the Minecraft game directory unless noted.

| Where | What |
|---|---|
| `config/cadence.properties`, `config/cadence-shaders.properties` | Settings. |
| `cadence-cache/` | Caches: translated shader-pack shaders (`translated/`, `spirv/`; `msl/` only with a developer flag) and `pack-pipelines.txt` (the pipelines a pack used last time, made ahead on the next switch). Kept under a size budget (safe to delete). |
| `cadence-lod/<world or server>/<dimension>/` | Far Terrain's saved land, when the player turns Far Terrain on (safe to delete). |
| `cadence-chunks/servers/<address>-<seed>/<dimension>/` | Keep Server Chunks: a multiplayer server's chunks as the client last had them (vanilla region files holding the server's own chunk packets). A world or dimension shows its land at once when the player comes back. Kept under 512 MB, least recently written first. Nothing in single player (safe to delete). |
| `replays/` | Instant Replay's videos (mp4), only when the player turns it on and presses Save Replay. |
| `screenshots/cadence-photo-*.png` | Photo Mode's screenshots (F9), where the game's own screenshots go. |
| `screenshots/` | Benchmark result cards when the player saves them. |
| system temp directory | The extracted native library (`mcmetal*/libmcmetal.dylib`). |
| `~/Library/Application Support/Cadence/` | `Cadence Helper.app` (unpacked from the jar, with `helper-stamp.txt`). `styles/` holds Style Filters' trained networks, the paintings they were trained on (from the game's resources) and `content/`, the pictures of the player's world used for training (copies of their screenshots or frames taken while they play). `voice-PID-N.txt` holds the phrases Voice Control heard. The helper deletes it when Voice Control stops or the game exits (the helper exits when the game's process is gone). `Game/` holds the Game Mode bundle (remade when needed). All safe to delete. |
| files named by `-D` flags | Measurement logs and recordings, only when a developer flag asks for them. |

## Code that changes Minecraft

Cadence changes Minecraft through mixins, which are listed in each module's `*.mixins.json`.

Optimizations that are not on by default sit behind `-Dcadence.*` switches. The mixin plugins (`*MixinPlugin.java`) check
the switches. With a switch off, its mixins are not applied.

`@Overwrite` is used where a method is replaced whole:

- entity model vertex writing (`mixin.cpu.ModelCubeWriteMixin`)
- Cadence Logic's game-logic replacements (`mixin.logic`: brains, POI sensors, path-type cache, block-entity ticking,
  entity sections)
- one opt-in server tick change (`mixin.chunk.ServerLevelTickMixin`)

`dev.cadence.metal.compat` hands each of these back to Minecraft's own code when another mod hooks the same method.

## Licences and origins

- Apache-2.0: Cadence, derived from mcopt by Noah Dunnagan (Apache-2.0).
- MIT: the shader loader, derived from Kausik Velaga's Minecraft Shader Loader.

LICENSE, NOTICE and CREDITS.md list every upstream and its licence. The `irisapi/` module declares Iris's public API
interfaces (`net.irisshaders.iris.api.v0`) again, from their published names and signatures, for compatibility. It
contains no Iris code (CREDITS.md).
