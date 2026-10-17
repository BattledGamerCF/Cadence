# Changelog

Versions are `cadence` / `cadence_shaders`. The release jar contains both, plus Cadence HUD.

## 0.4.0-beta.3 / shaders 0.3.2 (2026-10-16)

Smoother:
- **Turning a shader pack on no longer pauses the game.** The pack is read and prepared in the background while the
  current picture keeps drawing, then switched in between two frames: about half a second of frozen game before, now
  no frame over 0.15 s (measured with BSL and Complementary).
- **Changing a pack's settings pauses much less**: the pack's shaders are compiled several at a time, and Cadence
  remembers which ones packs need, so even the first pack after launching gets them ready ahead of time. A settings
  combination never used before: 1.7-2.1 s of frozen game before, now 0.3-0.7 s (once; after that it's cached).
- A small GPU saving with packs (BSL about 0.7 ms a frame): a pass is no longer interrupted to clear a buffer it reads.

Looks right:
- **Items glow and shine as the pack intends** (Complementary, Complementary Unbound, Pegasus, I Like Vanilla): held,
  dropped and framed items, worn armour and armour trims now tell the pack what they are, as Iris does. A held lava
  bucket or blaze rod glows, metal armour shines, and trims glow with the pack's Glowing Armor Trims option.

Fixed:
- Choosing a pack and then Off (or another pack) quickly could leave the first pack on.
- After a shader pack had been on, a piece of terrain from before it could show for a moment when it was turned off.
- A pack that fails to load no longer leaves the terrain empty until it reloads.

Tested: two players on a LAN world (the guest switching packs while connected), all 12 test packs, and quick switching
(16 switches: memory back to the same level every time).

## 0.4.0-beta.2 / shaders 0.3.1 (2026-10-16)

Faster:
- **About 25% more fps without shaders** with RGSS texture filtering (Minecraft's default): terrain pixels that RGSS
  can't change take one texture sample instead of nine. The picture is identical (checked pixel by pixel). The same
  applies when a shader pack is turned off mid-session.
- **Shader packs: about 1.6-1.8 ms less GPU time per frame**: cleared buffers a pack never draws into are no longer
  written to memory.
- Entity models are written a face at a time: about 5% less render-thread CPU in busy scenes.
- Less memory: the glowing-outline target exists only while outlines are on screen (about 40 MB at fullscreen), and the
  Java heap now shrinks when macOS reports memory pressure.

Easier:
- **Cadence says it is optimizing your game**, and the settings show what it tunes automatically.
- **Cooling** (Quiet, Balanced, Performance, Off) replaces the heat-control switch; the tooltip shows temperature
  state, power and fan speed. Fan speed itself can't be set without administrator rights.
- **Quick Setup asks what matters more**: smooth and cool, or the most fps.
- **Shader packs switch in game, any time, and Cadence's faster terrain renderer is back the moment they're off.**
  No restarts and no Shader Switching setting: the fast renderer steps aside while a pack draws and returns when it's
  turned off (measured: 506 fps, BSL 60, off 509, Complementary 57, off 504).
- With no shader packs installed, the Shaders button says so and opens the folder.
- **Every Cadence setting is now in Video Settings**, under "Cadence", "Cadence: Performance" and "Cadence: Shaders"
  (the separate Cadence Settings screen is gone), and Cadence's status lines wrap instead of running past the menu at
  large GUI scales or small windows.
- The Graphics API option's default now reads **Cadence (Metal)**.
- **Shader Pack Settings** has sliders where the pack asks for them, Reset to Defaults and Done at the bottom, changed
  values in yellow, information lines that can't be clicked, and text that wraps at any GUI scale.
- **Shorter tooltips that are quick to scan**: one line per choice, with the choice's name in green.
- Clearer names: the Performance preset "Balanced" is now **Recommended** (so it isn't confused with Cooling: Balanced),
  "Shader Quality" is **Shader Profile**, and Shadows reads "Every 4th Frame" instead of "Every 4rd Frame".
- **Shader Pack Settings**: every pack's own options (shadows, clouds, water, sky...) on the pack's own pages, with its
  names and help text, as in Iris. Click for the next value, shift-click for the previous one; changes apply when you
  leave. Settings are kept per pack in `shaderpacks/<pack>.txt`, the same file Iris uses, so Iris settings carry over.
- **Compatibility layers (first version)**: where another mod hooks code Cadence replaces, Cadence uses Minecraft's own
  code in that one area and keeps every other optimization ([docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)).

Fixed:
- **A shader pack that can't load no longer stops Minecraft from starting.** The game starts without shaders and says
  why in chat when you join a world. Unreadable pack settings files are skipped instead of refusing the pack.
- **A damaged config file can't stop Minecraft from starting** any more: a malformed line (found by testing a hand-broken
  config) used to crash the launch; now only the bad line is skipped. An unreadable Resolution value falls back to Auto, and
  a selected pack whose file was deleted says so in plain words.
- Quieter logs: Cadence's periodic statistics are only written with `-Dcadence.logStats=true`.
- A shader pack refused to load when the quality profile chosen for another pack didn't exist in it ("Unknown shader
  profile: MINIMUM" with MakeUp). Profiles are now kept per pack, and a profile a pack lacks is ignored, as in Iris.
- A crash when turning a shader pack on with World Resolution below 100%.
- When a shader pack fails, it is turned off and the game keeps running (it used to crash a frame later).
- Auto resolution: a light pack after a heavy one comes back to full sharpness at once instead of over 20-30 s.
- Cooling: a Mac that warmed up under a shader pack kept holding the pack's frame rate (about 60 fps) after the pack was
  turned off. Changing shaders now starts the hold again (Balanced: up to 120 fps while warm).

## 0.4.0-beta.1 / shaders 0.3.0 (2026-10-15)

- **Cadence Logic**, Cadence's own replacement for Lithium (written from scratch; exact, so the game plays the same):
  - entity searches look up only the sections they need: a mob-sized search is 22× faster (mobs pushing each other,
    hoppers, item merging, AI sensors, collisions all use it);
  - mob brains (villagers, piglins, frogs and more) tick without allocating iterators and lists every tick;
  - villagers skip a 405-block scan every 2 seconds when their profession has no secondary work blocks;
  - unloading many block entities at once no longer costs quadratic time.
  Details: [docs/GAME-LOGIC.md](docs/GAME-LOGIC.md). Each can be turned off with `cadence.logic.<name>=false`.
- Developer tools: `tools/mixin-check.sh` checks every mixin against Minecraft without starting it;
  `tools/logic-tests/run.sh` fuzz-tests the game-logic changes against Minecraft's own classes.

## 0.3.0-alpha.5 / shaders 0.2.2 (2026-10-15)

- **Pegasus works:** no more pink, red or blue halos where distant land meets water, and no yellow blocks on night
  water. Shader variables that a pack reads before setting are now 0, as on PC graphics drivers, instead of leftover GPU
  data. A uniform declared with the wrong type is now left at 0, as Iris does.
- **Faster shader packs that use parallax depth** (Pegasus, Bliss): the GPU can skip hidden terrain again. Pegasus is
  about 20% faster.
- The low-fps tip names the gentlest lighter preset first (for example "Medium is faster, Potato_Shadows fastest").

## 0.3.0-alpha.4 / shaders 0.2.1 (2026-10-14)

- Fixed black blobs along coastlines, speckled water and soft-looking frames: Auto resolution kept throwing away the
  packs' anti-aliasing history. It's now carried over, and Auto changes size less often.
- Full-screen shader passes are drawn the way Iris draws them (fixes Bliss's bloom).
- Bliss: fixed the olive haze at night and underwater and the milky daytime.
- Mipmaps are generated in one GPU command.

## 0.3.0-alpha.3 / shaders 0.2.0 (2026-10-14)

- All 12 tested shader packs run: compute passes, custom images, per-buffer blending, Iris macros and profiles.
- Faster shader math (relaxed precision where packs allow it).
- Heat control for fanless Macs; the release jar includes the shader loader.

Earlier history (from mcopt and the first Cadence alphas): [docs/CHANGES-FROM-MCOPT.md](docs/CHANGES-FROM-MCOPT.md).
