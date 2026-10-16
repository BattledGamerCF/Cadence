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

## What still doesn't work

- Mods that draw with OpenGL directly: Cadence's renderer is Metal. Mods that use Minecraft's own rendering API work.
- Sodium and Iris: Cadence replaces them.

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
- A list of tested mods.
