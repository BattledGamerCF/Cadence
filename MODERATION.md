# Notes for reviewers and moderators

Cadence is a macOS-only Fabric client mod that replaces Minecraft's OpenGL renderer with Apple's Metal. Because of that it does
more low-level work than most mods. This file lists all of it, so nothing has to be guessed from the bytecode.
Everything below is open source: https://github.com/BattledGamerCF/Cadence (Apache License 2.0; see LICENSE and NOTICE).

Summary: no network access of its own, no downloads, no telemetry, nothing installed system-wide, no admin rights asked for.

## Native code in the jar

| File | What it is | Source |
|---|---|---|
| `natives/libmcmetal.dylib` | The Metal renderer, Apple audio engine, video encoder and other native parts (arm64, macOS 15+) | `metal/src/main/native/*.m`, `*.c`; built by the `compileNative` task in `metal/build.gradle` (clang) |
| `natives/CadenceHelper.app` | A small helper app, signed ad hoc, for jobs that need their own process (see below) | `metal/src/main/helper/main.swift`; built by `compileHelper` (swiftc) |

The library is copied to a new folder in the system temporary folder (or `.cadence-natives` in the game folder if that
fails) and loaded with `System.load`. Java calls into it through the Foreign Function & Memory API (`java.lang.foreign`),
which needs `--enable-native-access` (Java prints a warning without it; the mod still works).

## Processes it starts

| What | When | Where in the code |
|---|---|---|
| Java restart (`execve`, same process id, same Java, same arguments plus Cadence's recommended Java options) | Once, at the start of loading, on macOS, unless the player's own options already cover them. Never under Prism Launcher, MultiMC or PolyMC (they can't be restarted this way). Off with `cadence.jvmDefaults=false` in `config/cadence.properties` | `metal/.../system/JvmDefaults.java` |
| macOS Game Mode: the game is started again from an app bundle macOS treats as a game, `~/Library/Application Support/Cadence/Game/<id>/Minecraft.app` (a copy of the same Java launcher, links to the same Java). The first process waits for it and passes on quit signals | Part of the restart above. Off with `cadence.gameMode=false` | `metal/.../system/GameMode.java` |
| Vivace (an optional OpenJDK build) | Only if the player downloaded it themselves and chose it in Video Settings > Java; the restart then goes to that Java. Its `-version` is run first as a check | `metal/.../system/Vivace.java` |
| `/usr/sbin/sysctl -n hw.memsize` / `sysctl.proc_translated` | At start: memory size, and whether Java runs under Rosetta | `Profile.java`, `PlatformCheck.java` |
| Cadence Helper `train-style` | Only when the player trains a Style Filter (Create ML, on device) | `metal/.../neural/StyleFilter.java` |
| Cadence Helper `listen` via `/usr/bin/open` | Only when the player turns on Voice Control (on-device speech recognition; macOS asks for microphone permission) | `shaders/.../voice/VoiceControl.java` |
| `/opt/homebrew/bin/ffmpeg` | Only when the player uses Instant Replay and has ffmpeg installed | `metal/.../rec/Recorder.java` |
| `open <folder>` | When the player clicks "Open shader packs folder" | `shaders/.../ui/ShaderToggle.java` |

## Files it writes

- Game folder: `config/cadence*.properties` (settings), `cadence-cache/` (translated shader packs and other caches, with a size
  limit), `cadence-chunks/servers/` (chunks kept from servers the player joined, for far terrain; Keep Server Chunks), `replays/` (Instant Replay clips, only when saved), `screenshots/`.
- `~/Library/Application Support/Cadence/`: the Game Mode bundle, the unpacked helper app, trained Style Filters.
- The system temporary folder: the native library (see above).

## Network

Cadence opens no connections of its own. Its native network path (`mcnet.c`) only moves the bytes of the connection the game
already made to the server the player joined, with the same data; off with the Java argument `-Dcadence.net=false`.

## Other things a scan may flag

- Mixins into many Minecraft classes: that is the renderer replacement. They are listed in the `*.mixins.json` files.
- `java.lang.foreign` native memory access: vertex and texture buffers shared with Metal.
- Nested jars (`META-INF/jars/`): Cadence HUD, the shader-pack loader and Cadence Iris API, all from the same repository.

Questions: https://github.com/BattledGamerCF/Cadence/issues
