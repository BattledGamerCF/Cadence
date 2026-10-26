# Compatibility layers (first version)

Cadence makes Minecraft faster partly by replacing pieces of its code: how entity models are written, how terrain is
drawn, how mobs search for each other. Another mod that hooks one of those same pieces would either lose its hook or
remove Cadence's. Compatibility layers are how Cadence and other mods share the game.

## What happens at launch

Before any of Cadence's changes are applied, Cadence reads every other mod's mixins (the hooks Fabric mods use to change
the game), the same way the Mixin library will apply them: which classes and methods each one touches, and how. Minecraft
26 is not obfuscated, so the names match Cadence's own directly.

Where another mod and Cadence touch the same method and either one replaces it (an overwrite, a redirect, a cancellable
inject, a method wrapper), Cadence steps aside **for that area only**. That area runs Minecraft's normal code, the code the
other mod was written for. Everything else Cadence does stays on.

| Area | When another mod hooks it |
|---|---|
| Render-thread CPU optimizations (for example the entity model writer) | Only the conflicting optimization is left out |
| Game logic (Cadence Logic) | Only the conflicting optimization is left out (and all of it when Lithium is installed) |
| Cadence's own terrain renderer | Turned off as a whole; terrain goes through Minecraft's section pipeline, still drawn on Metal with Cadence's culling |
| Everything else | Reported, not changed yet |

Two ordinary hooks on the same method (two plain `@Inject`s) work together and are not a conflict. Fabric API is known
to work with Cadence and isn't checked.

The result is logged (`[cadence] compat: ...`) and shown in Cadence's settings, for example
"Compatibility: 1 layer: entity models for examplemod".

## Tested mods (2026-10-17, Minecraft 26.3, M2 MacBook Air)

All twelve installed together with Cadence 0.4.0-beta.3 (the release jar, in a normal, non-development launch), plus
the libraries they need (Fabric API, Fabric Language Kotlin, YACL, MezzConfig, Placeholder API). Each was checked in game:
shaders off, BSL and Complementary Reimagined; Retina on and off; a 4-minute flight with packs switched on and off; leaving
the world and rejoining; Mod Menu. No errors from Cadence or between the mods.

| Mod | Version | Result |
|---|---|---|
| AppleSkin | 3.0.10 | Works: hunger and saturation preview on the HUD |
| Just Enough Items (JEI) | 31.15.0.79 | Works: item list next to the inventory, its search box and buttons |
| Essential | 1.5.1 | Works since this build: title-screen and pause-menu sidebars (also with Retina off), its screenshot toasts. (Before, it failed to load next to Cadence: see below) |
| Mod Menu | 21.0.0 | Works: Cadence's entry has a settings button (it opens Video Settings, where Cadence's options are); Cadence HUD and Cadence Shaders are listed under it |
| Lithium | 0.26.2 | Works; Cadence Logic steps aside for it (as designed: two game-logic optimizers on the same code) |
| Xaero's Minimap | 26.6.0 | Works: minimap and its coordinates; its world-map rendering hooks run vanilla code |
| Jade | 26.3.5 | Works: the tooltip for what you look at |
| Entity Culling | 1.11.3 | Works: mobs behind glass still shown, its culling thread runs alongside Cadence's terrain culling |
| Continuity | 3.0.1 | Works: connected glass (its built-in "Default Connected Textures" resource pack needs to be on, as without Cadence) |
| ImmediatelyFast | 1.17.1 | Works: HUD, chat and text |
| Zoomify | 2.17.0 | Works: hold-to-zoom |
| Dynamic FPS | 3.11.10 | Works: caps the frame rate when the window is in the background (60 fps by default) |

Cadence's settings screen showed: "Compatibility: 6 areas use vanilla code for Dynamic FPS, Xaero's Minimap, Lithium,
Zoomify, EntityCulling" (the places where those mods change the same code as Cadence; the rest of Cadence stays on).

Not compatible by design: **Sodium** (and Iris, which needs it). Cadence replaces both, and Fabric refuses to start with
both installed, naming the two mods.

**Mods that use the Iris API** (`net.irisshaders.iris.api.v0`) work: Cadence Iris API, nested in Cadence's jar, answers it
from Cadence's shader packs without claiming the mod id `iris` ([API.md](API.md#the-iris-api)). Xaero's Minimap finds it
("Iris found!"). Mods that check for the mod id see no Iris; the optional `cadence-iris-provider` jar from the release zip
adds it, and refuses ImmediatelyFast and Distant Horizons (they load code into Iris's internals or take Iris-only paths).
Tested 2026-10-25 with the release jar and the 17 mods above: everything loads, no mixin errors; with the provider (and
without ImmediatelyFast), Essential starts on its Iris path.

**Bobby** does what Keep Server Chunks does (it also saves the chunks to disk); with Bobby installed Keep Server Chunks
stays off so the two don't both hold on to the server's forgotten chunks.

Messages in the log that aren't Cadence's: "Mod appleskin provides a broken implementation of ModMenuApi" (AppleSkin's
settings button wants Cloth Config, which isn't installed), and with an offline test account, Essential's and Realms'
sign-in errors.

### Essential (fixed in this build)

Essential's loader adds its mixins during Fabric's pre-launch step. Cadence's startup preload (it loads common classes on
background threads so the game starts faster) also ran at pre-launch, and loading classes then applied mixins, including
the MixinExtras library's, before Essential had picked its own: "The MixinExtras service has already been selected" and
Essential didn't load. Now, when any other mod has a pre-launch step, Cadence's preload waits until all of them are done
(it starts with the client mods instead). Without such a mod nothing changes.

## What still doesn't work

- Mods that draw with OpenGL directly: Cadence's renderer is Metal. Mods that use Minecraft's own rendering API work.
- Sodium and Iris: Cadence replaces them. Mods that need Iris's internals (beyond its API) don't load without Iris.

## Options

- `cadence.compat=false` (in `config/cadence.properties`, or `-Dcadence.compat=false`): nothing steps aside; the
  report is still written.
- `-Dcadence.compat.log=true`: every hook read from other mods is logged.

## Testing

`tools/compat-test-mod` is a tiny mod whose hook targets the method Cadence's entity model writer replaces. With it in
`mods`, Cadence's writer steps aside, the test mod's hook runs, and entities draw as in vanilla.

## Next steps (not built yet)

- Finer layers for the report-only areas (shader-pack hooks, culling), each with a vanilla-compatible fallback.
- A "permissive" variant of the replaced code that calls the other mod's hook points, so both optimizations and mods run.
- More tested mods (the list above is the first twelve).
