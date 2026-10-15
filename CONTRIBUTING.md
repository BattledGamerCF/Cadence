# Contributing

Thanks for helping! Bug reports are most useful with your Mac model, macOS version, Cadence version, the shader pack and
preset you used, a screenshot, and `logs/latest.log`.

## Building

You need a JDK 25. `./gradlew build` builds the three mods; `tools/release` makes the release jar and zip in `dist/`.
The native part (`metal/src/main/native`, Objective-C) is compiled by the Gradle build with the Xcode command-line tools
(`xcode-select --install`).

## Running a development client

`./gradlew :metal:runClient` starts Minecraft with the mods from source. Its game folder is `metal/run/` (ignored by git);
put test shader packs in `metal/run/shaderpacks/`.

## Automated in-game tests

`tools/cadence-test.sh` launches the development client, runs a list of steps and saves a screenshot and log lines for
each, in `metal/run/results/<time>/`. For example:

```bash
tools/cadence-test.sh "BSL_v10.1.8.zip,time=18000,cmd=weather rain,off"
```

Steps include a pack file name, `off`, `time=`, `cmd=` (a command; `@me` is the player), `scale=`, `profile=`,
`option=NAME=VALUE`, `debugbuffer=N` (show colortexN), `skipprograms=a+b` (switch pack programs off to measure them) and
`wait=S`. The full list is at the top of `CadenceSelfTest.java`. The script waits for any other Minecraft to close and
runs a memory guard (`tools/cadence-guard.sh`), so it's safe on an 8 GB Mac. It expects a singleplayer world called
`tester`.

`tools/pack-census.sh [pack.zip ...]` checks shader packs for unsupported features without starting the game.

## Code layout

- `metal/`: the Metal backend. `dev.cadence.metal` is the renderer; sub-packages hold terrain (`own`, `chunk`), far
  terrain (`lod`), CPU and startup work (`cpu`), allocation (`alloc`) and compatibility (`compat`).
- `shaders/`: the shader-pack loader. `pack` reads packs, `compile` translates GLSL to SPIR-V for Metal, `runtime` runs a
  pack's passes, and `cadence` holds Cadence's own features (Auto resolution, settings screen, self-test).
- `fpshud/`: the fps counter.

Files that came from mcopt or the Minecraft Shader Loader say so on their first line; keep that line when editing them.
New code is Apache-2.0.
