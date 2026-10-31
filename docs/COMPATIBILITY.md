# Compatibility layers (first version)

Cadence makes Minecraft faster partly by replacing pieces of its code: how entity models are written, how terrain is
drawn, and how mobs search for each other. Another mod that hooks one of those same pieces would either lose its hook or
remove Cadence's. Compatibility layers let Cadence and other mods share the game.

## What happens at launch

Before any of Cadence's changes are applied, Cadence reads every other mod's mixins, which are the hooks Fabric mods use
to change the game. It reads them the same way the Mixin library will apply them, to learn which classes and methods each
one touches and how. Minecraft 26 is not obfuscated, so the names match Cadence's own directly.

Sometimes another mod and Cadence touch the same method, and either one replaces it (an overwrite, a redirect, a
cancellable inject, a method wrapper). Cadence then steps aside for that area only. That area runs Minecraft's normal
code, which is the code the other mod was written for. Everything else Cadence does stays on.

| Area | When another mod hooks it |
|---|---|
| Render-thread CPU optimizations (for example the entity model writer) | Only the conflicting optimization is left out. |
| Game logic (Cadence Logic) | Only the conflicting optimization is left out, and all of it when Lithium is installed. |
| Cadence's own terrain renderer | Turned off as a whole. Terrain goes through Minecraft's section pipeline, still drawn on Metal with Cadence's culling. |
| Everything else | Reported, not changed yet. |

Two ordinary hooks on the same method (two plain `@Inject`s) work together and are not a conflict. Fabric API is known to
work with Cadence and isn't checked.

The result is logged (`[cadence] compat: ...`) and shown in Cadence's settings, for example
"Compatibility: 1 layer: entity models for examplemod".

## Tested mods (2026-10-17, Minecraft 26.3, M2 MacBook Air)

All twelve mods were installed together with Cadence 0.4.0-beta.3 (the release jar, in a normal, non-development launch).
The libraries they need were installed too: Fabric API, Fabric Language Kotlin, YACL, MezzConfig and Placeholder API.

Each mod was checked in game with these steps:

- shaders off, BSL and Complementary Reimagined
- Retina on and off
- a 4-minute flight with packs switched on and off
- leaving the world and rejoining
- Mod Menu

There were no errors from Cadence or between the mods.

| Mod | Version | Result |
|---|---|---|
| AppleSkin | 3.0.10 | Works: hunger and saturation preview on the HUD. |
| Just Enough Items (JEI) | 31.15.0.79 | Works: item list next to the inventory, its search box and buttons. |
| Essential | 1.5.1 | Works since this build: title-screen and pause-menu sidebars (also with Retina off) and its screenshot toasts. Before this build it failed to load next to Cadence (see below). |
| Mod Menu | 21.0.0 | Works: Cadence's entry has a settings button, which opens Video Settings, where Cadence's options are. Cadence HUD and Cadence Shaders are listed under it. |
| Lithium | 0.26.2 | Works. Cadence Logic steps aside for it, as designed, because two game-logic optimizers would work on the same code. |
| Xaero's Minimap | 26.6.0 | Works: minimap and its coordinates. Its world-map rendering hooks run vanilla code. |
| Jade | 26.3.5 | Works: the tooltip for what you look at. |
| Entity Culling | 1.11.3 | Works: mobs behind glass are still shown, and its culling thread runs alongside Cadence's terrain culling. |
| Continuity | 3.0.1 | Works: connected glass. Its built-in "Default Connected Textures" resource pack needs to be on, as without Cadence. |
| ImmediatelyFast | 1.17.1 | Works: HUD, chat and text. |
| Zoomify | 2.17.0 | Works: hold-to-zoom. |
| Dynamic FPS | 3.11.10 | Works: caps the frame rate when the window is in the background (60 fps by default). |

Cadence's settings screen showed "Compatibility: 6 areas use vanilla code for Dynamic FPS, Xaero's Minimap, Lithium,
Zoomify, EntityCulling". These are the places where those mods change the same code as Cadence. The rest of Cadence stays
on.

### Not compatible by design

Sodium, and Iris, which needs it. Cadence replaces both. Fabric refuses to start with both installed and names the two
mods.

### Mods that use the Iris API

Mods that use the Iris API (`net.irisshaders.iris.api.v0`) work. Cadence Iris API, nested in Cadence's jar, answers it from
Cadence's shader packs without claiming the mod id `iris` ([API.md](API.md#the-iris-api)). Xaero's Minimap finds it
("Iris found!").

Mods that check for the mod id see no Iris. The optional `cadence-iris-provider` jar from the release zip adds it. It
refuses ImmediatelyFast and Distant Horizons, because they load code into Iris's internals or take Iris-only paths.

Test record:

- 2026-10-25: tested with the release jar and the 17 mods above.
- 2026-10-28: tested again with 0.4.0-beta.26 and beta.27 (`tools/compat-run.sh`: PASS; beta.27 with Game Mode on).
- Result: everything loads and there are no mixin errors. With the provider (and without ImmediatelyFast), Essential starts
  on its Iris path.

### Sound mods that drive OpenAL themselves

Sound physics and reverb mods hook the game's `com.mojang.blaze3d.audio` classes in their mixins. They keep OpenAL. With
one installed, Cadence's Apple sound engine stays off for the session, and Music & Sounds says which mod it is for.

### Bobby

Bobby does what Keep Server Chunks does: it keeps the server's forgotten chunks and saves them to disk. With Bobby
installed, Keep Server Chunks stays off so the two don't both hold on to them.

### Log messages that aren't Cadence's

- "Mod appleskin provides a broken implementation of ModMenuApi". AppleSkin's settings button wants Cloth Config, which
  isn't installed.
- With an offline test account, Essential's and Realms' sign-in errors.

### Essential (fixed in this build)

Essential's loader adds its mixins during Fabric's pre-launch step. Cadence's startup preload also ran at pre-launch. The
preload loads common classes on background threads so the game starts faster. Loading classes at that point applied
mixins, including the MixinExtras library's, before Essential had picked its own. The result was "The MixinExtras service
has already been selected", and Essential didn't load.

Now, when any other mod has a pre-launch step, Cadence's preload waits until all of them are done. It starts with the
client mods instead. Without such a mod nothing changes.

## What still doesn't work

- Mods that draw with OpenGL directly. Cadence's renderer is Metal. Mods that use Minecraft's own rendering API work.
- Sodium and Iris. Cadence replaces them. Mods that need Iris's internals (beyond its API) don't load without Iris.

## Apple Hardware features with shader packs and other mods

- **HDR** with a shader pack: the pack's picture is shown as the pack made it (SDR). The frame stays 8-bit-compatible for
  the pack's own passes. Mods that read the frame back (screenshots, replays, map mods) get SDR values, because the copy
  scales the float frame's highlights into 0-1.
- **Ray Tracing** is off with a shader pack (the pack has its own shadows) and with World Resolution below 100%. The
  button's tooltip says so. Blocks added by mods are traced as full cubes in their texture's average colour.
- **Style Filter** is off with a shader pack and with World Resolution below 100%.
- **Smooth Textures** applies to Cadence's own terrain renderer only. With a pack, or with mods that draw blocks
  themselves, those draws keep the game's atlas.
- **World Reverb** and **Sound Around Corners** need the Apple sound engine. Sound mods that replace OpenAL calls (Sound
  Physics Remastered and similar) should be used with the OpenAL engine instead, where both are off.
- **Torch Shadows** and **Neural Lighting** follow Ray Tracing (off with a pack or World Resolution below 100%). Blocks
  added by mods that give light cast shadows as vanilla's do, because their light level is read from the block state.
- **Biome Colours** is off with a shader pack, because the pack grades its own picture. Modded biomes get a grade from
  their temperature, rain and colours.
- **Frame Generation: Neural** follows Frame Generation (Smooth's rules: not with the Minimal Renderer or the Metal 4
  path).
- **Far Terrain > Renderer: Mesh Shaders** falls back to Compute with a shader pack, because the pack draws the far
  terrain's records.

## Game Mode and launchers

With Game Mode on (the default), the process your launcher started becomes a small waiter ("Cadence Helper") a second or
two after launch. The game itself runs as its child, from `~/Library/Application Support/Cadence/Game/<id>/Minecraft.app`
with the same Java and options.

For the launcher nothing changes. The same process runs from start to end, the game's output reaches its console, the stop
button quits the game, and the exit code is the game's. Two things differ:

- Tools that attach to a Java process by its pid (`jstack`, `jcmd`, VisualVM) need the game's pid, not the launcher's.
  `pgrep -f net.minecraft` shows it.
- If the waiter is killed outright, the game saves and quits within a second.

`cadence.gameMode=false` in `config/cadence.properties` (or `-Dcadence.gameMode=false`) keeps the old single process.

## Networking mods

Cadence's native network path for servers (decryption and decompression, `dev.cadence.metal.net`) replaces the same two
stages as Krypton and its ports (Krypton FNP, Pluto), Ceres, Raknetify, ZstdMC and BandwidthOptimizer. With any of those
installed, Cadence leaves the stages to that mod and logs `Network: <mod> is installed`. Mods that only read packets
(proxies, packet loggers, ViaFabric) work with it, because the bytes and packets are Minecraft's own.

## Options

- `cadence.compat=false` (in `config/cadence.properties`, or `-Dcadence.compat=false`): nothing steps aside. The report is
  still written.
- `-Dcadence.compat.log=true`: every hook read from other mods is logged.

## Testing

`tools/compat-run.sh` runs the newest release jar (`dist/`, from `tools/release`) with the tested mods above in a
production launch (`metal/run-compat`). It says PASS or FAIL. PASS means the game reached a world and finished its
self-test, no mixin failed to apply, and the benchmark ran. Exceptions in the log are listed. Run it before every release.

If Cadence's Metal renderer can't start on a Mac (a failure while it sets up), the game now starts on Minecraft's own
renderer instead of crashing, and says so on screen. This was tested with `-Dcadence.metal.fakeFailure=true`: the world
loads and plays on OpenGL.

`tools/compat-test-mod` is a tiny mod whose hook targets the method Cadence's entity model writer replaces. With it in
`mods`, Cadence's writer steps aside, the test mod's hook runs, and entities draw as in vanilla.

## Next steps (not built yet)

- Finer layers for the report-only areas (shader-pack hooks, culling), each with a vanilla-compatible fallback.
- A "permissive" variant of the replaced code that calls the other mod's hook points, so both optimizations and mods run.
- More tested mods (the list above is the first twelve).
