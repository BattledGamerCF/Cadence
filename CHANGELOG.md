# Changelog

Versions are `cadence` / `cadence_shaders`. The release jar contains both, plus Cadence HUD.

## Unreleased

- **Video Settings > Java: Launcher's / Vivace** (Extras): play on Vivace, the Java build made alongside Cadence, without
  changing the launcher's settings. Off by default, from the next launch. Cadence looks for an installed Vivace in
  ~/Library/Java/JavaVirtualMachines (or /Library/...), starts it once with your Java arguments to check it accepts them, then
  restarts the game on it at the very start of loading (the same restart Cadence's Java defaults use: one process, the
  launcher keeps tracking it). If Vivace refuses an argument, macOS blocks it (a download still in quarantine) or a start on
  it never reaches the game, the game carries on with the launcher's Java and the settings screen says why. Cadence downloads
  nothing and ships no part of Vivace: it is a separate, open-source download. Also `cadence.java=vivace|launcher|PATH`
  in config/cadence.properties or `-Dcadence.java=`.

## 0.4.0-beta.11 / shaders 0.3.10 (2026-10-21)

- **Fewer garbage-collection pauses while exploring**: Minecraft's world generator samples noise into large float buffers
  from pools that dropped any buffer unused for a short while, so every few chunks it allocated new ones; those arrays were
  89% of what Java's young collections copied while flying over new terrain. Cadence keeps them (same pools, same values):
  young collections -33%, their pause time -26%, background marking cycles -55%, about 20 MB more heap.
  `-Dcadence.gen.bufferAge=20` restores vanilla.
- Cadence's own mesher no longer allocates fresh sort arrays for every chunk section it builds (identical meshes, checked
  over 1.26 million quads).
- The Benchmark card and its text file say which Java ran the test (e.g. "25.0.5 · Vivace-25.0.5.1").
- GC policy on Vivace: class unloading during play only every 32nd marking cycle (was 12); menus still get it.

## 0.4.0-beta.10 / shaders 0.3.9 (2026-10-21)

- **GC policy + Vivace**: on Java builds whose G1 can schedule class unloading (Vivace, the macOS-first OpenJDK 25 build made
  alongside Cadence: -XX:G1ClassUnloadingInterval), Cadence keeps the expensive part of G1's Remark pause (class and compiled
  code unloading, 5-100 ms) out of play: it happens in a cycle Cadence starts while a menu is open; during play every Remark
  took 0.5-3.7 ms in a flight over new terrain. On any other Java nothing changes.

## 0.4.0-beta.9 / shaders 0.3.8 (2026-10-20)

Settings:
- **Video Settings regrouped**: Cadence's rows now read top to bottom under vanilla-style headings: Picture & Speed
  (Sharpness & Speed..., Leaves, Dynamic Lights), Power & Heat (Cooling, On Battery, In Background), Extras (Benchmark, FPS
  Counter, Cadence Messages, Render Max FPS). Four fewer buttons on the main panel.
- **Sharpness & Speed...** also holds the two shader-pack speed settings (Shadows, Faster Shadows).
- **Benchmark Details** moved onto the results card (More Details / Fewer Details), where it matters.
- Buttons that do nothing right now are greyed out with the reason: Sharpening at a fixed 100% without shaders, Shadows
  without a shader pack.
- Shorter tooltips (Resolution, Retina, On Battery, Dynamic Lights, Render Max FPS).

Performance:
- **Cadence GC policy**: Java's garbage collector (G1) was marking the whole heap back to back, each round ending in an 8-26 ms
  pause (class unloading) every few seconds, even when nothing had grown. Cadence now starts that marking itself, only when
  the long-lived part of the heap has grown (sooner with a menu open or when macOS is short of memory); G1's own trigger stays
  as a safety net. A 4-minute flight over new terrain: 126 of these pauses -> 16, total pause time -46%, same memory. Takes
  effect through Cadence's Java defaults (not with your own -XX:+DisableExplicitGC or another collector);
  `-Dcadence.gcPolicy=false` turns it off.
- Text in left-to-right languages skips the right-to-left reordering every string went through each frame (HUD, chat,
  signs, menus): less garbage, identical text (checked over 500,000 strings). `-Dcadence.cpu.textBidi=false` turns it off.

Edge cases:
- The display's refresh rate and scale are read from the screen the game is on (not the screen of whichever app is active).
- Retina off no longer halves the GUI scale on a non-Retina monitor (where it made every menu half size).
- Settings are saved whole or not at all (a full disk or a crash mid-save no longer empties the settings file).
- Quick Setup recognises Macs with iPhone-class chips (A-series).

Fixes:
- Cycling the Performance preset through Max FPS left Retina off for good; the other presets turn it back on (at the next
  launch, as before), and a "Restart Minecraft to turn Retina off/on" line says so.
- A status line that appeared while Video Settings was open (Cooling easing off, the restart note) was squeezed onto the
  last line and ran past the panel; the panel now lays itself out again, keeping its scroll position.
- The Benchmark card said "Frame cap: 120 fps (Cooling)" next to a higher average when Cooling only stepped in halfway; it
  now says "for part of the run".

## 0.4.0-beta.8 / shaders 0.3.7 (2026-10-19)

New:
- **Benchmark** (Video Settings, Cadence: Performance): 30 seconds of your view turning once round, then a results card
  anyone can read: average fps, the slowest 1% of frames, the worst single frame, graphics and processor time per frame, and
  what it ran on (Mac, memory, window, render distance, shader pack, Cadence settings), with "last time" for comparison.
  Save Image puts the card in your screenshots folder (with a text copy for forum posts). Nothing leaves your Mac.
- **On Battery** (Video Settings): unplugged, the frame rate is capped at your display's refresh rate (Match Display, the
  default: 60, or 120 on ProMotion; nothing you can see is lost), or at 30 (Saver), or not at all (Off). Plugged in, nothing
  changes. Drawing 500 frames a second on battery mostly turns the battery into heat.
- **Even frames under a cap**: with any frame cap (Max Framerate, menus, In Background, On Battery, Cooling) each frame is
  shown at least the cap's interval (Metal's presentDrawable:afterMinimumDuration:), so a capped game advances one frame per
  interval instead of jittering against the display; on ProMotion displays it also tells macOS the rate to refresh at.
  Capped frame rates are unchanged; the evenness itself couldn't be measured here (this Mac's display doesn't report when
  frames reach it). `-Dcadence.framePacing=false` turns it off.
- **Render Max FPS** (Video Settings, for curiosity): the world isn't drawn at all (no block faces, sky, mobs, particles),
  just a sky-blue void and your HUD: about 8,500 fps on an M2 MacBook Air. It turns itself off when Minecraft restarts.
- **Full screen**: the Metal layer is marked opaque, so macOS doesn't blend the game window and can show full-screen frames
  directly.
- **Sharpness & Speed...** (Video Settings): Resolution, Sharpening and Retina moved to their own page, the settings that
  trade picture sharpness for frame rate (fewer pixels: much higher fps, especially in full screen), so the main panel is
  shorter.
- **FPS Counter** (Video Settings: On / Off) turns the corner frame rate off. **Benchmark Details** (Simple, the default /
  Detailed): Simple shows the average frame rate and what it ran on; Detailed adds the slowest 1% of frames, the worst
  frame, and graphics and processor time per frame.
- The Benchmark card says when a frame cap was in effect (On Battery, Cooling, Max Framerate), so a capped run isn't taken
  for what the Mac can do.

Faster:
- **Mob and item drawing**: model and item vertices are written in pairs (the same bytes, half the stores): mob models
  ~15% less render-thread time in a crowded scene.

Cadence Logic (the integrated server; exact, each checked in game against vanilla's answer):
- **Scheduled ticks just outside the simulation distance** (water, leaves, redstone waiting for a chunk to tick) are no
  longer re-checked every tick: the server thread's scheduled-tick bookkeeping went from 22% to under 1% in a test world by
  a river (600,000 checks against vanilla: none different). `cadence.logic.tickRange=false` turns it off.
- **Mob pathfinding**: the per-search cache of block path types no longer allocates per question or clears its whole table
  per search: path finding 11.7% -> 5.3% of the server thread in a crowded scene (1.8 million answers checked: none
  different). `cadence.logic.pathCache=false` turns it off.

## 0.4.0-beta.7 / shaders 0.3.6 (2026-10-19)

- **Core tiers on M5 Pro / Max and M6** (Super + Performance (+ Efficiency) cores): Cadence reads the chip's tiers, and where
  there is a middle Performance tier the chunk-building workers are marked lower priority, so macOS keeps them on the
  Performance and Efficiency cores and the few Super cores stay free for the render thread and the game logic. Chips with
  two tiers in the old sense (M1-M4, the base M5) are unchanged. Not yet measured on that hardware;
  `-Dcadence.workerQos=default` turns it off. The startup log names the layout ("CPU cores: ...").
- **Crowded scenes: less CPU per frame for mobs, items and particles.** Each cube of a mob's model now has its 8 corners
  placed once instead of 24 times (entity models 30% -> 25% of the render thread in the test scene); models textured from
  the block atlas (chests, signs, banners, shulker boxes, ...) take the same fast path; items (dropped, held, in frames) are
  written a quad at once (1.2 million checked, identical); Cadence's particle culling looks each section up once a frame instead of once a particle. All
  write exactly the bytes vanilla does (checked on 57 million faces with `-Dcadence.cpu.modelWriteVerify=true`).

Native shaders, now on in the regular jar (there is no separate native jar any more; `-Dcadence.native=false` turns them off):
- Vanilla's shaders as hand-written Metal instead of GLSL translated through SPIR-V: 35 programs, now including Fabulous
  graphics' order-independent transparency (every translucent program's three passes, the composite and the depth-bounds
  pass). A program goes native only while no resource pack replaces any file it reads; anything else is translated as before.
- Measured in game (crowded scene and plain views, then the same with Fabulous transparency): 193 pipelines native, none
  failed; pictures the same as the translated ones; frame rate the same to a few percent higher. Each native program is now
  compiled once for both stages (half the shader compile time when the game loads or a pack switches).
- Fixed before it shipped: the native programs were missing their shared definitions at run time; the game's pipelines were
  looked up by name, which isn't unique (and the first in-game try failed the resource reload: the game stayed on the loading
  screen); a native program Metal refused wasn't translated instead. Now each pipeline is matched exactly, and anything that
  goes wrong in the native path means "translated as before".

## 0.4.0-beta.6 / shaders 0.3.5 (2026-10-18)

Less memory:
- **43 MB less GPU memory while you play** (in a world, 2940 x 1686 window: textures 123 -> 80 MB). Vanilla's depth target
  for the hand in post-effect views (spectating a creeper, spider or enderman) is now only full size while one is on; the
  rest of the time the hand draws into the main depth anyway. The title screen's panorama (24 MB) is let go 10 seconds into
  a world and loaded again when you leave it.
- **Another 8.5 MB at World Resolution 67%**: a scaled hand-depth target that nothing drew into since the hand moved to full
  resolution is gone.
- **Fixed in beta.5: Java's compiled code kept growing.** Beta.5 turned off class unloading during the garbage collector's
  marking (it made its pauses shorter), but Java frees replaced compiled code only while unloading classes: the code cache grew
  from 35 to 100 MB in 4 minutes and kept growing. Back to Java's default. Beta.5's shorter "Remark" pauses go with it.

Fixed:
- **The "Cadence: optimizing your game automatically" line repeated** on server networks (Hypixel-style lobbies log you in
  again at every switch): it now shows once per connection.
- Dynamic Lights' tooltip says when they can't work this session (the game started with a shader pack on: Cadence's terrain
  renderer is off until a restart without one).

## 0.4.0-beta.5 / shaders 0.3.4 (2026-10-18)

New:
- **Dynamic Lights** (Video Settings, Cadence: Performance; off by default): a torch, lantern, glowstone or lava bucket in
  anyone's hand lights the world around them, as if placed there. **All** also lights up dropped light items, burning
  mobs and arrows, blazes, magma cubes, fireballs, lit TNT, glowing items in item frames, falling light blocks and an
  enderman carrying one. Torches and other fire go out under water. The light is added on the GPU by Cadence's terrain
  renderer, so a moving light rebuilds nothing (LambDynamicLights rebuilds the chunks around each light): 16 lights at
  once cost 0.07 ms a frame. With a shader pack on, the pack's own held-item light applies instead (most packs have one);
  with LambDynamicLights installed, Cadence's step aside.

Smoother:
- **Fewer hitches while exploring.** Flying over new terrain, Java's garbage collector paused the game for up to 25 ms
  every 2-3 seconds (its "Remark" step, which went through all the game's compiled code to unload classes the game never
  unloads). Cadence's Java defaults now leave that to rare full collections: those pauses are about 1 ms (11 ms on average
  before). Metaspace starts at 256 MB, so loading no longer triggers collections either. Applied automatically at launch,
  as Cadence's other Java defaults are; your own Java arguments still win.
- **16% less garbage on the render thread in crowded scenes** (about 320 mobs: 355 -> 298 KB a frame), so the collector
  pauses less often: render passes reuse their arrays, model parts without children and rotated parts allocate nothing,
  and Cadence's chunk mesher no longer boxes every light lookup.

## 0.4.0-beta.4 / shaders 0.3.3 (2026-10-18)

Easier:
- **A Shader Packs screen** (Video Settings > Shaders..., or press **O** in game): every pack in your shaderpacks folder
  in one list. Click a pack to turn it on; click Off to turn shaders off. Under each name: whether it has been tested
  with Cadence and how heavy it is (Light, Medium, Heavy), or why it couldn't load this session. Drop .zip files onto
  the window to add packs (the first one turns on). Profile, Pack Settings and Open Packs Folder are at the bottom. This
  replaces the button that cycled through packs one at a time, and the separate Live, Quality and Folder buttons.
- **Keys as in Iris**: **O** opens Shader Packs, **K** turns shaders off and back on to the same pack. Change them in
  Controls > Key Binds > Miscellaneous.
- **Video Settings in a clearer order**: shaders first (Shaders..., Shader Pack Settings...), then Performance and Quick
  Setup, then Cadence's own options in two groups, "Cadence: Performance" and "Cadence: Shader Speed". The status lines
  at the top are shorter: what Cadence is tuning by itself right now, the Mac's heat and memory, and the mods it's working
  alongside.
- **Pack Settings has an Apply button**: see a change without leaving the screen. Reset is at the top, and a line says
  how values change (click: next value, Shift-click: previous).
- **The shadow speed-up is now called Faster Shadows**, with plain states (On, Testing, Not Safe, Unsure, ...).
- **Graphics API is greyed out and reads Metal**: Cadence always draws with Metal, so choosing another did nothing.

More shader packs work: **23 of the 25 tested packs** (the 12 before, plus 11 of 13 new ones):
- Now working: **Photon, Solas, Kappa, Nostalgia, AstraLex, Super Duper Vanilla, Mellow, Miniature, Vanilla Plus,
  Potato Shaders**, and **Noble** (its shaded sides are darker than intended; the pack list says so).
- Not supported: **Shrimple** and **Rethinking Voxels**. They need geometry or tessellation shaders, which Metal doesn't
  have; the pack list now says that in plain words.
- The fixes behind these help any pack: 3D textures (Photon's sky and clouds were black), struct and array varyings,
  large compute shared memory, names that clash with Metal's built-ins, `gcolor` in world programs (blocks drawn as flat
  colours in Nostalgia and Vanilla Plus), integer colour outputs, flip directives, shadowcomp passes, textures from the
  game's own resources, other mods' samplers, and more old colour formats.

Faster:
- **Complementary Reimagined about 6% faster** (44 -> 41 ms a frame at full Retina size, M2 Air; the same picture): a
  buffer a pass draws into is copied first only if the pass actually reads it, not just declares it. 12 copies a frame
  became 2. BSL and Photon are unchanged.

## 0.4.0-beta.3 / shaders 0.3.2 (2026-10-16)

Smoother:
- **Turning a shader pack on no longer pauses the game.** The pack is read and prepared in the background while the
  current picture keeps drawing, then switched in between two frames: about half a second of frozen game before, now
  no frame over 0.15 s (measured with BSL and Complementary).
- **Changing a pack's settings pauses much less**: the pack's shaders are compiled several at a time, and Cadence
  remembers which ones packs need, so even the first pack after launching gets them ready ahead of time. A settings
  combination never used before: 1.7-2.1 s of frozen game before, now 0.3-0.7 s (once; after that it's cached).
- A small GPU saving with packs (BSL about 0.7 ms a frame): a pass is no longer interrupted to clear a buffer it reads.

Faster without shaders:
- **Up to 11% more fps outdoors**: Cadence's terrain renderer now measures, every few seconds, whether its hidden-terrain
  check is worth it in the current view, and skips it where little is hidden (open land, the ocean, high up). It keeps it
  underground and in caves, where it still pays.
- **New option: Retina** (Video Settings, Cadence: Performance; takes effect at the next launch). Off, the game draws at
  your screen's point size, a quarter of the pixels, and macOS shows each one as a sharp 2x2 block, like a non-Retina
  Mac. Menus and the HUD keep their size (the GUI scale is halved to match). **About twice the fps without shaders and
  three times with shaders** (M2 Air, a 1470x920 window, launches alternated: no shaders at render distance 12, 530 ->
  1,170 fps; BSL at render distance 8, 30 -> 88 fps).
- **New Performance preset: Max FPS**: Retina off, World Resolution 100%, the cheapest upscale and no cooling limit.
- **Auto resolution stays at 100% without shaders**: lowering it there only added blur and the upscale's cost.
- A World Resolution below 100% with Sharpening on Fast or Smooth is about 10% faster without shaders: the upscaled image
  stays on the GPU chip for the hand and HUD instead of being written out and read back (50%: 1.70 -> 1.54 ms a frame).
- **Sharpening: Fast is now "sharp bilinear"**: block edges stay crisp (each texel a solid block), as cheap as before.
- Buffer clears are skipped when everything is drawn over anyway, now without shaders too.

Clearer:
- **Names above players and server holograms stay sharp** when World Resolution is below 100% (Auto, Max FPS, or a set
  percentage). They used to be drawn with the smaller world and stretched back up, which smeared the small text on servers
  like Hypixel; now they're drawn at full resolution on top, still hidden behind walls as before. Your hand and held item
  are now drawn at full resolution too.
- **Quick Setup is readable over the world**: a dark backdrop behind its text (with Menu Background Blur at 0 the text sat
  straight on the sky), and shorter lines. Choosing "Max FPS" there now recommends the Max FPS preset and never a longer
  render distance.

Looks right:
- **Items glow and shine as the pack intends** (Complementary, Complementary Unbound, Pegasus, I Like Vanilla): held,
  dropped and framed items, worn armour and armour trims now tell the pack what they are, as Iris does. A held lava
  bucket or blaze rod glows, metal armour shines, and trims glow with the pack's Glowing Armor Trims option.

Works with more mods (tested together: AppleSkin, JEI, Essential, Mod Menu, Lithium, Xaero's Minimap, Jade, Entity
Culling, Continuity, ImmediatelyFast, Zoomify, Dynamic FPS; details in docs/COMPATIBILITY.md):
- **Essential loads next to Cadence.** It didn't before: Cadence's startup preload ran during Fabric's pre-launch step, the
  moment Essential sets up its own mixins, and got in its way. The preload now waits when another mod has a pre-launch step.
- **Mod Menu**: Cadence's entry has a settings button (it opens Video Settings), and Cadence HUD and Cadence Shaders are
  listed under Cadence, with its icon.
- The compatibility line in Video Settings names a mod once (Dynamic FPS was listed twice, as "Dynamic FPS" and its
  "Common" module).

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
