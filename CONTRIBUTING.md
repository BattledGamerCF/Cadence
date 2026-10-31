# Contributing

Thank you for helping. A useful bug report includes your Mac model, macOS version, Cadence version, the shader pack and
preset you used, a screenshot, and `logs/latest.log`.

## Building

You need a JDK 25.

- `./gradlew build` builds the four mods, including the Iris API module.
- `tools/release` makes the release jar and zip in `dist/`.

The native part (`metal/src/main/native`, Objective-C) is compiled by the Gradle build. It needs the Xcode command-line
tools (`xcode-select --install`).

## Running a development client

`./gradlew :metal:runClient` starts Minecraft with the mods from source. Its game folder is `metal/run/`, which git
ignores. Put test shader packs in `metal/run/shaderpacks/`.

## Automated in-game tests

`tools/cadence-test.sh` launches the development client and runs a list of steps. For each step it saves a screenshot and
log lines in `metal/run/results/<time>/`. For example:

```bash
tools/cadence-test.sh "BSL_v10.1.8.zip,time=18000,cmd=weather rain,off"
```

Steps include:

- a pack file name
- `off`
- `time=`
- `cmd=` (a command; `@me` is the player)
- `scale=`
- `profile=`
- `option=NAME=VALUE`
- `debugbuffer=N` (shows colortexN)
- `skipprograms=a+b` (switches pack programs off so they can be measured)
- `wait=S`

The full list is at the top of `CadenceSelfTest.java`.

The script waits for any other Minecraft to close. It also runs a memory guard (`tools/cadence-guard.sh`), so it is safe
on an 8 GB Mac. It expects a singleplayer world called `tester`.

Run `tools/mixin-check.sh` before starting the game after you change a mixin. It checks every mixin's targets, methods,
fields and call sites against Minecraft 26.3. This catches mistakes that would otherwise crash the game at launch.

`tools/logic-tests/run.sh` fuzz-tests the game-logic optimizations against Minecraft's own classes.

`tools/pack-census.sh [pack.zip ...]` checks shader packs for unsupported features without starting the game.

### Measuring

- `benchmark` is the 30-second turning benchmark. `ab=FEATURE:SECONDS` switches a feature every 2 s and compares both
  states in the same run. Both go in a plan.
- `tools/bench-round.sh` runs a list of plans. Each plan is its own launch, with a cool-down between launches. A fanless
  MacBook Air loses up to half its speed when hot.
- `gputrace` with `-Dcadence.metal.trace=later` (in `CADENCE_EXTRA_JVM`) logs the GPU time of every pass of one frame in
  `stdout.log`.
- The stall recorder is on by default. It logs every frame over 50 ms with its cause. Find the entries with
  `grep "\[cadence\] stall" stdout.log`.
- `tools/compat-run.sh` runs the release jar with the tested mods and reports PASS or FAIL. Run it before a release.

## Translations

Cadence's text is written in British English in the code, and the English is the translation key. Text shown to the player goes through `dev.cadence.metal.system.Lang` (`Lang.literal(...)` in place of `Component.literal(...)`, and every `Tips` tooltip). Each language is a JSON file in `metal/src/main/resources/cadence/lang/`, named with Minecraft's language code (`de_de.json`), mapping English to the translation.

- A key with `{}` is a template for text put together from parts, such as `Performance: {}`. The parts are translated in turn. A translation that needs the parts in another order writes `{1}`, `{2}` instead of `{}`.
- Spaces and punctuation at the start and end of a text are kept as written; the key is the text between them.
- `en_gb` is the text as written and has no file. `en_us.json` holds the American spellings.
- `tools/i18n/run.sh` lists every English text in the code and checks each language file against the list: missing or unused keys, and `{}` placeholders, `§` codes and line breaks that don't match. Run it after adding or changing text, then add the new keys to each file.
- `-Dcadence.lang=CODE` shows a language without changing Minecraft's language setting.

## Code layout

- `metal/`: the Metal backend. `dev.cadence.metal` is the renderer. Its sub-packages hold terrain (`own`, `chunk`), far
  terrain (`lod`), CPU and startup work (`cpu`), allocation (`alloc`) and compatibility (`compat`).
- `shaders/`: the shader-pack loader.
  - `pack` reads packs.
  - `compile` translates GLSL to SPIR-V for Metal.
  - `runtime` runs a pack's passes.
  - `cadence` holds Cadence's own features (Auto resolution, settings screen, self-test).
- `fpshud/`: the fps counter.

Files that came from mcopt or the Minecraft Shader Loader say so on their first line. Keep that line when you edit them.
New code is Apache-2.0.
