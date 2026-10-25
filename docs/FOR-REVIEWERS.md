# For mod-platform reviewers

A short guide to what Cadence does on a player's computer, to make reviewing it quick. The full source is in this
repository; the release jar is built from it by `tools/release` (Gradle plus `tools/bundle.py`, no other inputs).

## What it is

A client-side performance mod for Minecraft 26.3 (Fabric) on Apple Silicon Macs: Minecraft drawn with Apple's Metal API
instead of OpenGL, plus engine optimizations, shader-pack support and a far-terrain renderer. Three mods in one jar
(`cadence`, `cadence_shaders`, `cadence_hud`; docs/ARCHITECTURE.md). It runs only on macOS on Apple Silicon and declares
`environment: client`.

## Native code

- `libmcmetal.dylib` (arm64) is built from the Objective-C and C sources in `metal/src/main/native` by the Gradle build
  (`clang`, no prebuilt binaries). At startup it is extracted from the jar to the system temp directory and loaded with the
  Java Foreign Function & Memory API (`dev.cadence.metal.Native`; if the temp directory can't be used, a
  `.cadence-natives` folder in the game directory). It links Apple's Metal, MetalFX, QuartzCore, CoreVideo, CoreGraphics,
  IOKit, AppKit and Foundation frameworks only.
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
| `cadence-cache/` | Caches: translated shader-pack shaders (`spirv/`, `msl/`), kept under a size budget (safe to delete). |
| `cadence-lod/<world or server>/<dimension>/` | Far Terrain's saved land, when the player turns Far Terrain on (safe to delete). |
| `screenshots/` | Benchmark result cards when the player saves them. |
| system temp directory | The extracted native library (`mcmetal*/libmcmetal.dylib`). |
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
Velaga's Minecraft Shader Loader). LICENSE, NOTICE and CREDITS.md list every upstream and its licence.
