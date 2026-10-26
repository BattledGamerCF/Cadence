# Changelog

Versions are `cadence` / `cadence_shaders`. The release jar contains both, plus Cadence HUD.

## 0.4.0-beta.22 / shaders 0.3.21 (2026-10-26)

- **The game's sound on Apple's audio engine** (Options > Music & Sounds > Sound Engine: Apple Spatial Audio, the default;
  OpenAL is one click away and switches at once). Minecraft's sounds, music and records play through AVAudioEngine instead
  of OpenAL Soft: positional sounds are placed with Apple's spatial audio (HRTF on headphones and AirPods, Apple's own
  rendering on the Mac's speakers; the game's Directional Audio option asks for the highest-quality HRTF on any output),
  with the game's own loudness and per-sound distance falloff, pitch and looping; streams (music, records) are fed a second
  at a time as before. It follows the output you pick in macOS, and plugging in headphones never stops the sound system.
  Tested: sounds, positional sounds, streamed music and records play and finish, pause and resume, live switching to
  OpenAL and back. Mods that drive OpenAL themselves (sound physics, reverb) keep OpenAL. While it runs, the game's own
  sound Device option is not used (the output is macOS's).
- Self-test steps: audio, sound=ID[:DX], disc=ID, soundengine=, volume=, pausesounds / resumesounds.

## 0.4.0-beta.21 / shaders 0.3.20 (2026-10-26)

Multiplayer:
- **Keep Server Chunks now saves to disk.** Every chunk the client lets go on a server (past your render distance, or the
  world or dimension left) is stored as the server's own chunk packet, in region files under `cadence-chunks/` (512 MB at
  most, least recently written first). Coming back, the stored chunks within your render distance are there at once, a
  few each frame, until the server's own copies replace them. LAN test (server view distance 4, render distance 10): back
  from the Nether, 182 chunks from disk in the first second (117 without), the same picture. Nothing in single player.
- Measured, nothing to fix: the client's handling of the server's chunk and light packets is about 6% of the render thread
  flying at 20 blocks a second (vanilla already paces chunk sending to how fast the client handles it); in a crowd of 200
  mobs, entity models are half of the render thread, already on Cadence's exact whole-face writer, and the frame is GPU-bound.

Far terrain:
- **Far Distance up to 4,096 chunks** (65 km): one more clipmap level, 2 MB; 3% slower than 2,048 at 200 blocks up.
- **Full detail reaches 440 blocks by default** (it was 190 on 8-core GPUs, where the terrain turned blocky close by). The
  Quality button is now **Full Detail: 190 / 440 / 960 Blocks**, saying where the blockier terrain starts. Made affordable:
  level 0's block textures and occlusion are drawn only where a block is 4 or more pixels on screen (past that, the cell's
  color, as the coarser levels draw it), and its plants within 192 blocks: at 440 blocks, 200 blocks up on an M2 Air, the
  far terrain's GPU time 4.0 -> 2.65 ms, the same picture.
- **Fixed: a hole under the camera from high up.** Chunks whose ground is more than the render distance below the camera
  aren't drawn by the near terrain, but far terrain still stepped back from them.
- Memory: the cull's buffers shrink back after 30 s of low use (they only grew), and the mesh arena grows by a quarter
  instead of half (Medium at 4,096 chunks: 136 MB instead of 144).
- Not done yet: the far terrain's cull costs about 2 ms of GPU each frame the camera moves (a still camera reuses it).

Fixes from the bug hunt (Sonnet, 11 found, all fixed):
- Keep Server Chunks' disk store: leaving a world saves for at most 40 ms, farthest chunks first (the ones the server won't
  send again), and never stops the disconnect it runs inside; what waits to be written is held to 48 MB; chunks that came
  from disk unchanged aren't written again; the 512 MB budget is kept during a session too (and oversized-chunk files go
  with their region); decoded chunks wait at most 64 ahead of the game; a server switch through a proxy (or a server
  reconfiguring) saves the world it leaves and doesn't keep it in memory; a reset map or another world behind the same
  address and seed (an earlier game time or another spawn) clears its stored chunks instead of showing them; turning the
  setting off stops the store at once; a far teleport reads the stored chunks around where you land.
- Full Detail's Auto stays at 190 blocks on GPUs of 6 cores or fewer (440 was only measured on 8 cores).
- The far terrain's cull buffers no longer shrink from a long still view's counts (a few frames of missing distant land
  after turning).

## 0.4.0-beta.20 / shaders 0.3.19 (2026-10-25)

- **Keep Server Chunks** (Video Settings, on by default): on a server whose view distance is shorter than your render
  distance, the chunks it stops sending are kept while they're within your render distance (as they were when you last saw
  them; the server sends them again, up to date, when you come back), and the server's cap on your render distance is
  lifted, so the view fills to your full distance as you travel. Kept chunks are let go once they're farther than your
  render distance. Off in single player and on a LAN host (they already send everything) and with Bobby installed. LAN test,
  server view distance 4, guest render distance 10: 117 chunks loaded, 184 after moving (67 kept); turned off, back to 117
  and the server's distance.
- **The Iris API** for other mods: Cadence's jar nests Cadence Iris API, which declares Iris's public API
  (`net.irisshaders.iris.api.v0`, as Iris's 26.1 branch has it) and answers it from Cadence's shader packs: whether a pack
  is on, whether its shadow pass is drawing, its sun angle, the shader screen, shaders off and on. It doesn't claim the mod
  id `iris` (mods that see an `iris` load code into Iris's internals); the optional `cadence-iris-provider` jar in the
  release zip does, for mods that need to see Iris installed, and refuses ImmediatelyFast and Distant Horizons. Tested with
  the 17-mod compatibility set; Xaero's Minimap finds it. Cadence API additions: `renderingShadowPass()`, `sunPathRotation()`
  (docs/API.md).
- **Faster startup**: about 15% sooner in a world. The game's block-state caches are built in parallel (the same values:
  the digest of every cache matches vanilla's across runs) and the crash-report warm-up runs on a background thread, now on
  by default (M2 Air, alternating launches, JVM start to in a world: 11.99 s -> 10.21 s, three each). The loading screen
  fades out in 0.4 s and takes clicks 0.8 s after loading instead of 2 s. (The block-cache check had looked like a mismatch:
  it hashed fluid states by a text that holds a per-run identity hash.)
- **Max FPS preset at render and simulation distance 16**: 1,146 -> 1,391 fps fullscreen (M2 Air, cooled runs). The preset
  now also sets Entity Distance to 75% (saved, and restored by another preset unless you changed it since: GPU -0.10 ms,
  render thread -0.06 ms at simulation distance 16, where mobs are the render thread's largest cost). 2,000 fps at distance
  16 isn't reached: terrain still costs about 0.5 ms there, and the next step, merging block faces into larger quads, is a
  renderer project of its own. Far Plants (`-Dcadence.own.farPlants=96`: the cull leaves out far grass and flowers) is
  opt-in only: it also hid far wall torches, chains, sloped rails and fire, for 0.04 ms.
- Leaving the Max FPS preset puts back only the leaves, clouds and entity distance you haven't changed since choosing it.
- Fixes from the final bug hunt (Sonnet, 11 found, all fixed or designed out): Essential would have crashed on the first
  Iris API version (missing `IrisProgram` / `assignPipeline`; the API now matches Iris's current one); claiming the mod id
  `iris` made other mods take Iris-only paths (now the optional provider's job); turning Keep Server Chunks off now puts
  the server's render-distance cap back; the startup hooks can no longer stop a launch after a game patch (they fall back to
  vanilla); the Iris API's shadow-pass flag clears on a failed frame; turning shaders on through the Iris API without the
  Metal renderer no longer rewrites the shader config.
- Measured, not built: a Java AOT cache (about 1.3 s faster to a world, but 261 MB and only the launcher can turn it on).

## 0.4.0-beta.19 / shaders 0.3.18 (2026-10-25)

- **Frame Generation** (Video Settings: Smooth, Responsive, Off), with and without shader packs: a frame made between each two
  by reprojecting a snapshot of the world (no motion vectors needed); the hand, HUD, chat and screens come from the newest
  real frame as they are. Smooth: the camera halfway between the last two frames (one frame of added latency). Responsive:
  the newest frame turned to where the mouse looks now, read between frames (no added latency). It runs where it adds frames
  the screen shows: Battery Saver (and On Battery: Saver, Low Power Mode) renders 30 and shows 60; the others render 60 and
  show 120 on a 120 Hz screen. Measured: a 3 degree turn rebuilt with 91% of pixels exact (47% without reprojection), 0.2
  blocks of movement 71% (36%); with BSL 70% (34%) and 64% (41%).
- **Max FPS preset: 2,000+ fps fullscreen.** It now also draws leaves solid (vanilla's Leaves: Fast) and turns clouds off,
  your own choices coming back with any other preset. M2 MacBook Air, fullscreen (Retina off, 1470 x 923), render distance 6,
  cooled runs: 2,155 fps (1,781 before); 1,598 at render distance 12.
- **Instant Replay** (off by default; F10 saves the last 30 s to replays/, hardware H.264, no sound).
- **Photo Mode** (F9): a full-resolution, full-quality screenshot (Auto resolution's scale set aside, HUD hidden).
- **Performance Overlay** (off by default): frames shown and rendered, frame and GPU time, heat, power, battery.
- **Battery time left** at the current draw in Video Settings and next to the fps counter, on battery.
- **Low Power Mode**: followed as On Battery's Saver.
- **Auto Render Distance** (off by default): with a shader pack and Auto resolution at its floor, the render distance comes
  down 2 chunks at a time (not below 8) to hold the frame rate, and goes back up when there's room.
- Fixes from a deep bug hunt of the new features (21 found, 19 fixed): a crash when a click left the world during Responsive
  Frame Generation's in-between frame; Auto Render Distance no longer changes your saved Render Distance; Frame Generation
  only where the screen shows exactly twice the frames, its memory freed when it stops, no frame from a stale picture after
  a teleport; replays letterboxed after a window resize, pauses cut out, saves that can't hang; Photo Mode cancelled by a
  screen; and smaller ones.
- Measured, nothing to win, not built: a Metal pipeline archive and shader warm-up (the game's whole compile is ~0.2 s on
  macOS's own shader cache, and a flight creates almost no new pipelines), a chunk-mesh disk cache (a rejoin builds its
  chunks within a second), scoped barriers on the Metal 4 path (no change).

## 0.4.0-beta.18 / shaders 0.3.17 (2026-10-25)

Far terrain:
- **Far Distance** slider in Video Settings, beside Far Terrain (and on the Far Terrain page): how far the far terrain reaches,
  64 to 2,048 chunks, like Render Distance for the real chunks. It applies while you play: within the clipmap's levels at
  once, else the far terrain is built again once the slider stands still (a second or two; the tiles come back from the
  disk cache). It used to be a Distance button that took effect at the next launch.
- Quality **Auto** picks Low on GPUs with 8 cores or fewer (base M1/M2/M3 chips), as the Quality tooltip recommended:
  far terrain costs about half (M2 Air, 150 blocks up: 2.3 -> 1.0 ms a frame, 333 -> 570 fps); the cells from ~190 blocks
  out are 2 blocks. Medium and High stay one click away.

Docs: the README says what Cadence replaces (Sodium, Iris, Lithium, Entity Culling, Distant Horizons, dynamic lights) and
its measured frame rates; the research notes and development diaries are gone from the tree (they stay in the git
history); Render Max FPS has one page, docs/RENDER-MAX-FPS.md.

## 0.4.0-beta.17 / shaders 0.3.16 (2026-10-25)

Render Max FPS:
- The world stays as it was under the inventory, chat, pause and any other screen (it glitched: the game's own world
  drawing took over with a stale world). The game's HUD and the screen are drawn over Max FPS's frame.
- Much faster: 66,000 frames rendered a second (132,000 shown with interpolation) looking up from a pillar above the trees,
  30,700 at 30 degrees up with the land in view (it was 5,000), 16,000 looking down into a forest, 11,500-15,000 in a forest
  (M2 MacBook Air). The ray walk crosses air in a loop of its own and loads a step ahead (10-45% faster with terrain in view, the same image).
- **Correction**: looking straight up or down (exactly 90 degrees) drew one flat colour: the camera's sideways direction had
  no length there. Fixed. The earlier "looking down: 25,300 rendered" (beta.15 and beta.16 notes) was measured on that flat
  frame; the real figure is about 13,000 (docs/RENDER-MAX-FPS.md).
- Joining a world or changing dimension with Render Max FPS on no longer waits 30 s on "Loading terrain".
- The title screen and menus keep their usual frame-rate limit with Render Max FPS chosen.

Everything else:
- Entities' render states are kept from frame to frame instead of made new each frame: 8% less memory churn on the render
  thread, fewer garbage-collection pauses.
- Far terrain fixes (bug hunt of 10-24): a re-created world with an old world's name no longer shows the old world's land
  (the cache is kept per world seed; on a server per address and world); the cache of deleted worlds is removed; a tile
  that fails to generate is tried 3 times, not forever; running out of memory pauses generation for a minute instead of
  stopping all far-terrain updates; no native memory is left behind at each dimension change; a failure stops far terrain
  in that world cleanly (it used to keep ~190 MB, or disconnect you from inside the chunk loading); its chunk work no longer
  piles up while Render Max FPS is on; a server's dimension name can't place cache files outside the cache folder.
- Far terrain: plants' quads only as tall as the plants (the GPU shaded and threw away the rest): plants cost about nothing
  now instead of ~13% of the far terrain's GPU time.

## 0.4.0-beta.16 / shaders 0.3.15 (2026-10-24)

Render Max FPS is now playable, and it is Cadence's run at the Minecraft FPS record (Hone's rules: a playable resolution,
every block and entity rendered, every frame really rendered). All of it is automatic when the button is on:
- **The world drawn by Record Mode**: one compute dispatch a frame, no render pass. The loaded blocks around you (2 chunks
  each way) are ray-traced at 320 x 180 with no upscaling. Each block face is drawn as one colour, its texture's average (a
  1x1 texture), and lit as Minecraft's flat lighting lights it: sky and block light, time of day, the Brightness option,
  darker sides and bottoms. Plants are drawn as their crossed quads, slabs and similar as their shape, entities as lit boxes.
- **Everything still works**: the targeted block's outline and break progress, the hotbar, health, food, XP, air
  underwater, the hurt flash, water and lava views, chat, F3-style text, third person (F5), and night and the dimensions'
  skies. Any screen (pause, inventory, death) draws the game as usual.
- **Frame interpolation**: the frame halfway between each two is made too, and every other present shows one. The counter
  shows the frames made and, of them, the frames rendered.
- ~~On an M2 MacBook Air (cool), looking down: 25,300 fps rendered~~ (withdrawn 10-25: a flat-colour frame, see Unreleased).
  The Hone record is 21,632.
  Looking around a forest: 14,000-16,000 rendered (28,000-32,000 shown).
- The old black-screen mode is behind `-Dcadence.maxfps.blank=true`.

## 0.4.0-beta.15 / shaders 0.3.14 (2026-10-23)

(0.4.0-beta.14 was a pre-release build for an audit; everything in it is listed here,
followed by the audit's fixes.)

Far Terrain (experimental; Video Settings > Picture & Speed > Far Terrain):
- **With shader packs**: packs that support Distant Horizons (their `dh_terrain` program: BSL, Complementary, Photon and
  others) draw the far terrain with their own lighting and fog. It reads the far terrain's own culled quads straight from
  the GPU, with no extra copies or memory. Packs without the program show no far terrain, as with Distant Horizons under Iris.
- **Servers and LAN guests**: the far terrain is the land you have explored, kept on disk per server and dimension.
- **The End**: floating islands with their undersides, the void left empty. It used to run out of memory (0-1 fps). The
  Nether still has none (its ceiling would be all you'd see).
- **New settings**: Brightness, Saturation and Earth Curvature (Distant Horizons' option) apply at once, also with packs;
  Far Clouds (clouds out to the far terrain's reach); Progress Line ("Far terrain: 63% filled" under the fps counter);
  Pre-generate 1 km / 4 km (to disk, in the background).
- **Beacon beams** show past the render distance, widened with distance so they stay visible.
- **Memory and speed**: 83 MB less GPU memory at 512 chunks (an unused buffer, a mesh arena that grows by half, cull buffers
  sized to what they hold), and no GPU cull while nothing on screen changes (1.5 ms a frame saved standing still).

Render Max FPS:
- A black screen showing two numbers: the peak frame rate of this visit and the best of the session.
- Nothing holds it back while it's on: the HUD, the world's camera and fog work and shader packs are skipped; no frame
  limit, VSync or Heat Control applies; more frames are in flight. 9,100 -> 14,100 fps on an M2 MacBook Air (with BSL
  selected: 120 -> 14,000).

Frame rate:
- **Frame caps are exact**: a 120 cap ran at 119.x (the counter showed 119, jumping to 120 now and then); it now holds
  120.0, as do 60 and every other cap.
- **Shader packs on a 60 Hz display** run at 60 by default (frames past the display's refresh are never shown);
  `-Dcadence.shaders.displayCap=false` to turn it off.
- **ProMotion displays**: a cap at the display's own rate (120) presents each frame on its own refresh, so motion is even
  instead of now and then showing a frame twice and skipping the next.

For mod authors:
- **Cadence API** (`dev.cadence.api`, docs/API.md): draw at fixed points of Cadence's frame with Minecraft's render passes or
  with Metal itself (device, queue, command buffer, encoder, main textures), query Cadence's state, and ask Cadence to step
  aside in an area from fabric.mod.json. Costs nothing while no mod uses it.

Game logic:
- Collisions with air are answered at once (collisions were 11% of the server tick while exploring).
- docs/COVERAGE.md lists every part of the game, what Cadence does there, and what still runs Minecraft's own code.

Fixes from the audit (19 bugs found, all fixed; 8 of its 10 suspicions too):
- **Crash: Far Terrain at Quality High**, and any shader pack switch at Distance 1024 or 2048 (sometimes at 512): the far
  terrain's mesh memory handed out space past its end once it had grown.
- **The far terrain follows resource packs**: it kept the colours of the pack it was first made under, also across
  restarts. A resource pack change now makes it again (each cached tile records the packs it was made under).
- **Explored land (servers, Explored Only) was partly forgotten** when flying fast at a low frame rate: land that left the
  far terrain's window between two saves was never saved (a 30 fps flight kept 76 of 180 tiles; now all 180).
- **The far terrain could skip the real chunks' later changes** to a tile it had already updated once (what you build or dig,
  seen again from afar).
- **Shader packs in the Nether with Far Terrain on**: Photon drew a black screen and superDuperVanilla nothing but fog.
- **Mellow, VanillaEX and Noble draw the far terrain** (their Distant Horizons programs failed to translate).
- **Memory**: leaving a world frees the far terrain (about 190 MB of GPU memory, the old world and its server stayed at the
  title screen); the Nether no longer keeps the Overworld's far terrain (about 190 MB); 12.6 MB leaked at every dimension
  change; the far forest's caches kept up to about 100 MB of chunks far behind you (now only what is around you, and the
  trees take half the memory: a 10-minute flight ends with 466 MB of live heap instead of 609); a cache kept every visited
  world's biomes.
- **The 60 Hz shader-pack cap applies**: packs ran at 110-260 fps on a 60 Hz display; now 60.
- Smaller: the look settings apply at once with a pack while standing still; a broken beacon's far beam goes; the Far
  Terrain page keeps its scroll position and doesn't queue Pre-generate twice; Video Settings says when Render Max FPS is
  on (not Heat Control's hold) and when the selected pack has no far terrain (or its far terrain failed); hand-edited look
  values are clamped; a debug line no longer prints every second while the far terrain generates.
- Mod authors: `Cadence.isMetal()` works at the `cadence` entry point (it threw, and the addon never started); a `stepAside`
  name Cadence doesn't have is reported in the log; the API doc's example and notes are corrected. The air collision
  shortcut covers only the game's own air blocks (a mod's collidable "air" keeps its shape).

## 0.4.0-beta.13 / shaders 0.3.12 (2026-10-22)

Fixes from an overnight audit (23 bugs found, 21 fixed here).

Crashes and hangs:
- **Many animated textures** (about 470 or more in one atlas: big resource packs and modpacks) stopped the game on its
  out-of-memory screen ("Out of stack space"); the animation copy now uses a reusable table, and any failure there falls
  back to vanilla's upload for that atlas.
- **Shader packs with Cadence's Metal renderer off** (`cadence.metal=false`, or Distant Horizons on its OpenGL renderer)
  aborted the game; packs now say they need the Metal renderer and the Shaders button is unavailable.
- **Malformed shader packs** (an image of zero, negative or more than 16384 pixels a side, or a buffer the GPU can't give)
  aborted the game; they're refused with a chat line naming the size.
- **Video Settings > Java = Vivace**: a Vivace that never answers (a download still waiting on macOS's "can't be opened"
  prompt) no longer stops the game from opening (20 s, then the launcher's Java, and not tried again until the switch is
  turned on again); a damaged Vivace folder no longer stops Video Settings from opening.

Frame rate:
- **Max Framerate 40 or 50 on a 60 Hz screen ran at 30**: frame pacing now only paces caps a fixed-rate display can show
  evenly (60, 30, 20 on 60 Hz); others are held by the CPU's wait as in vanilla (50 -> 50 fps).
- **Cooling could hold 45 (shown as 30) for a whole session** when the Mac was first "warm" while a world was loading; the
  hold now starts at its ceiling (120 fps with Balanced) when the moment's rate is under the screen's refresh.
- **Render Max FPS** no longer makes every world join and dimension change wait 30 s on "Loading terrain...".

Smaller:
- Why Vivace isn't in use (missing, refused an option, didn't start) now shows in Video Settings after Cadence's restart;
  the Java button is greyed (and says where) when `cadence.java` is set in cadence.properties or the Java arguments.
- A shader pack that failed to load at launch no longer shows as "in use", and picking it again retries.
- Shader pack errors no longer print full file paths (with the account name); a folder without `shaders/` inside says so.
- Dropping a newer version of a pack onto the Shader Packs screen replaces the old file (copied whole, then moved in).
- The shader caches (cadence-cache/spirv and msl) are kept under 256 MB each; they grew without limit.
- Faster Shadows' check frees its frame-sized buffer when it ends (8-22 MB kept for the session before).
- `autoMin=NaN` in the config no longer makes Auto resolution draw nothing; Pack Settings' first page fits a small window.
- No empty temporary folder left at every launch; a temporary folder that can't be used falls back to the game folder,
  and the error names the folder and the reason.
- Metal's validation layer runs cleanly (the culling kernels' unused slots are bound; the animation copy stays under
  Metal's 4 KB inline-data limit); failed GPU frames are reported in the log.
- A server's own `/cadence` command reaches the server (only `/cadence messages on|off` stays local); Cadence's heap
  trimming leaves GC options you set yourself alone; Quick Setup can't get stuck on "Setting up Cadence...".

Known, not fixed: changing a pack's settings still pauses for 1-2 s (its pipelines compile on the first frame);
PBR resource packs (labPBR/OldPBR `_n`/`_s` maps) aren't read (README > Known issues).

## 0.4.0-beta.12 / shaders 0.3.11 (2026-10-21)

- **Video Settings > Java: Launcher's / Vivace** (Extras): play on Vivace, the Java build made alongside Cadence, without
  changing the launcher's settings. Off by default; takes effect at the next launch. Cadence looks for an installed Vivace
  in ~/Library/Application Support/Vivace or ~/Library/Java/JavaVirtualMachines (or /Library/...), starts it once with
  your Java arguments to check it accepts them, then restarts the game on it at the very start of loading (the same
  restart Cadence's Java defaults use: one process, the launcher keeps tracking it). If Vivace refuses an argument, macOS
  blocks it (a download still in quarantine) or a start on it never reaches the game, the game carries on with the
  launcher's Java and the settings screen says why. Cadence downloads nothing and ships no part of Vivace: it is a
  separate, open-source download. Also `cadence.java=vivace|launcher|PATH` in config/cadence.properties or
  `-Dcadence.java=`.
- **Java 26 and newer: young generation capped at 128 MB** (Cadence's Java defaults, G1 only; your own young-generation
  options win). From Java 26, G1 counts background marking as GC overhead and grows the heap after it, then fills the
  heap with new objects, so each young collection takes longer: on Java 27, a 14-minute flight had 193 young pauses over
  8 ms and used ~200 MB more memory than on Java 25. With the cap: 58 over 8 ms and the same memory as 25 (41-51 there).
  Java 25 keeps its own sizing (the cap only adds collections there).
- Vivace 27 (OpenJDK 27.0.1 with the same patches) exists alongside Vivace 25 and plays the same with the cap above;
  Vivace 25 stays the recommended one (long-term support). Minecraft 26.3, Fabric and Cadence run on Java 27 unchanged
  (18-mod production launch checked).
- On Java 27, Cadence's Java defaults use `-XX:G1IHOP=90` (Java 27's new name for InitiatingHeapOccupancyPercent, which
  now prints a deprecation warning at every start) and leave out ParallelRefProcEnabled (removed in 27); when the Java
  switch restarts onto a newer Vivace, options that Java reports as removed are left out too.

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
