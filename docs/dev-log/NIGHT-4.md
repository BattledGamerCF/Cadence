# Night 4 (2026-10-17): raw fps without shaders, then mod compatibility

Two parts, as asked: a deep pass on the frame rate with shaders off, then twelve popular mods installed alongside Cadence
(AppleSkin, JEI and Essential among them; Essential had failed for the player before). M2 MacBook Air, a 1470 x 920 window
(2940 x 1686 pixels on the Retina screen) unless noted, heat control off. Gains were measured in the same run (`ab=`, the
own renderer's probe) or by alternating launches; pictures were compared with the game frozen (`/tick freeze`).

## Part 1: raw fps without shaders

### Where the frame goes (100%, render distance 12, ~1.9-2.1 ms, ~500 fps)

Each part dropped in turn inside one run (`ab=drop-NAME`, interleaved):

| Part | Cost |
|---|---|
| Cadence's terrain (probe `skip`) | **1.46 ms** |
| of which: the solid layer's pixel shading (probe `frag.aKind=pos` / `flat`) | 0.33-0.37 ms |
| Water and other translucent | 0.09 ms |
| Clouds | 0.05 ms |
| Entities | 0.05 ms |
| Items (held, dropped) | 0.04 ms |
| Sky (a full-screen draw) | 0.02 ms |
| GUI, text | 0.01 ms each |

The sky's 0.02 ms for a full-screen draw shows that shading 5 million pixels is cheap; what costs is the terrain at full
resolution. Leaves didn't matter in this view (Detailed 522 / 509 fps, Smart 515 / 499, Fast 490, alternated as the Mac
warmed), and a depth-discard hint changed nothing (1.89 / 1.89 ms). Render distance 8 / 12 / 16: 2.0 / 2.1 / 2.3 ms, so the
number of sections isn't what limits it either. The cost scales with pixels.

### Retina off: about twice the fps (kept, the big one)

macOS gives the game a Retina window: twice the points in each direction, four times the pixels. Without shaders the frame
is pixel-bound, so drawing at the screen's point size (1470 x 843 for this window) and letting macOS show each pixel as a
sharp 2 x 2 block is the largest single gain left.

- The window is created without SDL's high-pixel-density flag (MetalBackend, `Retina.on()`); the Metal layer's
  magnification filter is nearest, so pixels stay crisp instead of being smoothed.
- The GUI scale is halved (RetinaGuiScaleMixin, rounded up), so menus, the HUD and text keep their physical size.
- A Video Settings button (Cadence: Performance, "Retina: On/Off", applied at the next launch, "(restart)" shown until
  then), and the Max FPS preset now turns it off (instead of a half-resolution world).

| Alternating launches | Retina on | Retina off |
|---|---|---|
| No shaders, render distance 12 | 531, 528 fps (1.9 ms) | **1,188, 1,148 fps (0.8 ms)** |
| BSL 10.1.8, render distance 8, 100% | 30, 30 fps (32.7 ms) | **88, 88 fps (11.3 ms)** |

Checked with BSL and Complementary (88 and 61 fps), at GUI scales 3 and 4, and with all twelve compatibility mods (Part 2).

### World Resolution below 100%: the upscale no longer costs a round trip (kept)

Why a 50% world barely helped without shaders (1.83 ms against 1.89 at 100%) while Retina off halves the frame: a GPU trace
(`-Dcadence.metal.trace`) showed the scaled path's full-size passes. The upscale was its own render pass (colour only) and
the hand, the world's text and the HUD then opened another pass on the same image with the depth attached, so the full-size
image was written to memory and read straight back. The upscale pass now attaches the full-size depth too, cleared as
vanilla clears it before the hand, and the following passes continue it.

- In-run A/B at 50% (Fast): **1.70 -> 1.54 ms and 1.69 -> 1.53 ms** (-0.16 / -0.17 ms, about 10%).
- Pictures with the game frozen: off vs on differs only in the fps counter (477 pixels, all in its corner; two shots with
  nothing changed differ by 279 there).
- `-Dcadence.worldScale.merge=false` turns it off. MetalFX's upscale is its own encoder and can't be merged.

### Measured and not changed

- Smart Leaves in the Max FPS preset: no gain in the test views (above).
- MetalFX at 67% without shaders stays slower than 100% (3.25 against 1.89 ms; MetalFX itself is about 1.3 ms at this
  size). Auto already stays at 100% without a pack; the tooltip says so.
- With Retina off the GPU frame is ~0.8 ms and the render thread still waits for it about two-thirds of the time, so the
  CPU isn't the limit even at 1,170 fps.

## Part 2: twelve mods

The test setup is a separate game folder (`metal/run-compat`, git-ignored), launched like a real install: Fabric's
production mode (`fabric.development=false`), the release jar in `mods`, and no Cadence classes from the build. The mods
were downloaded from Modrinth (its API can be scripted; CurseForge's needs a key) and checked against their published
SHA-512 hashes. `tools/cadence-test.sh` takes `CADENCE_RUN`, `CADENCE_DLI_CONFIG` and `CADENCE_CLASSPATH` for this.

Mods: AppleSkin, JEI, Essential, Mod Menu, Lithium, Xaero's Minimap, Jade, Entity Culling, Continuity, ImmediatelyFast,
Zoomify, Dynamic FPS (Sodium was dropped from the list: Cadence replaces it and declares that). Libraries: Fabric API,
Fabric Language Kotlin, YACL, MezzConfig, Placeholder API. Results per mod: ../COMPATIBILITY.md.

### Essential didn't load (fixed)

Essential failed next to Cadence alone ("The MixinExtras service has already been selected", then "Attempted to pop plugin
but the stack is empty"). Bisected to Cadence's startup preload: it ran during Fabric's pre-launch step and loaded common
classes on background threads, which applied mixins (and initialized MixinExtras) before Essential's loader, also a
pre-launch step, had added its own mixin configs. Now, if any other mod has a pre-launch entrypoint, the preload waits for
the client entrypoint (PreloadLater), after every pre-launch step; otherwise nothing changes. Essential then loaded: its
title-screen and pause-menu sidebars, screenshot toasts, no errors but the offline test account's.

### Mod Menu (added)

- Cadence's entry has a settings button: it opens Video Settings, where Cadence's options are (ModMenuSettings, loaded
  by Mod Menu only; compiled against Mod Menu's API, not bundled).
- Cadence HUD and Cadence Shaders are listed under Cadence (Mod Menu's parent) with Cadence's icon, instead of two "?"
  entries.

### Smaller findings

- The compatibility line named Dynamic FPS twice ("Dynamic FPS" and its nested "Dynamic FPS · Common" module, split over
  two lines on screen): a nested module now goes by the mod that contains it.
- "Saving world" staying on screen after leaving was the self-test's `leave` step (it saved but never opened the title
  screen); it now does what the pause menu's button does. Every save in the logs finished at once.
- Continuity's connected glass needs its built-in resource pack on, as without Cadence (the test folder's options had
  only "vanilla").
- Xaero's minimap missing once with BSL didn't happen again in isolation or with all twelve mods.

New self-test steps: `key=NAME:down|up` (hold another mod's key binding, e.g. Zoomify's), `modmenu=MODID` (the screen Mod
Menu's settings button opens), and `leave` now goes to the title screen.
