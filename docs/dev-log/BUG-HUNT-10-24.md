# Bug hunt 2026-10-24/25 (audit of far terrain, Render Max FPS / Record Mode, and what surrounds them; no fixes made)

Repo `cadence-metal`, source as of `6d5a4c7` (I started on HEAD `45df4b3` plus three uncommitted far-terrain files, which were committed
as `6d5a4c7` "plant quads only as tall as their sprites' opaque texels" while I worked; every commit after it, up to `76e06ed`, is docs
only). Line numbers are those of the committed files (HEAD `76e06ed`). The working tree has uncommitted edits of someone else's, made
while or after I wrote this: a `cadence.lod.noAo` benchmark flag at `Lod.java:142` (shifts every later line of `Lod.java` by one), and,
after this report was on disk, fixes for BH3-01, BH3-02, the `dirtySet` key and the menu frame cap (`Record.java`, `RecordWorld.java`,
`FramerateCapMixin.java`); I read them and they match the fixes suggested below. Audit only: no source file, config or commit was
changed by me, **nothing was run** (no Gradle, no game, no GPU or Metal program). This report is the only file I wrote. Evidence is of
three kinds: the source and `git` history, `javap` on the 26.3 game jar (vanilla behaviour), and the artifacts the self-test left in
`metal/run/results/` (screenshots, `stdout.log`). Run folders are named `<MMDD-HHMMSS>`.

Severity classes: crash, data loss, wrong output, leak, perf, minor. "Evidence: run" = shown by an existing run artifact; "code" = read
in the source with a precise trigger but not run end to end (I could not run anything).

> STATUS: COMPLETE. 9 findings (a camera bug that also invalidates the record numbers, a regression of a bug fixed on 10-21, 2 leaks, a
> cache-identity bug, a worker error-handling problem, a failure-handling gap, a rare crash race, a path traversal) plus a list of minor
> items. The Record Mode and far-terrain structs, rings and index math I checked are consistent (see "Checked and fine"). BH3-01 and
> BH3-02 are small fixes and worth doing before anything else.

## Summary table

| ID | Severity | Area | Bug | Evidence |
|---|---|---|---|---|
| BH3-01 | wrong output (and invalid record numbers) | Record Mode camera | The camera basis collapses at pitch exactly +-90 degrees (what the mouse stops at): every pixel's ray is the same, the frame is one flat colour. The "looking down 25,300 / 24-27k" and "sky 66,200 (best 132,934)" numbers were measured at exactly those poses | run + code |
| BH3-02 | perf (30 s stall) | Render Max FPS (Record) | BH-09 is back: with the mode chosen, "Loading terrain..." waits for vanilla's full 30 s timeout at every world join and dimension change (`Record.active()` lost its loading-screen exemption in `3cffe43`) | code + the 10-21 live repro of the same mechanism |
| BH3-03 | leak (heap) | Far terrain + Record / Max FPS | While Record Mode (or the black-screen variant) is on, the far terrain's per-chunk results are queued but never drained: about 11 KB per chunk loaded or unloaded, unbounded | code |
| BH3-04 | wrong output (persistent), leak (disk) | Far terrain disk cache | The cache folder is keyed by the world's folder name (or the server's address) only: a new world with the same name shows the old world's terrain; nothing prunes the cache | code |
| BH3-05 | wrong output, perf | Far terrain workers | A deterministic job failure is retried forever with a stack trace each time (1,628 log lines in one 4-minute run before the OOM latch existed); the OOM latch also switches off chunk summaries and tile saves; a bad cache count trips it | run + code |
| BH3-06 | leak (native) | Far terrain: world and dimension changes | About 3 MB of native memory is never freed per far-terrain world or dimension entered (mesh worker scratch buffers, the table copy, the noise stacks) | code |
| BH3-07 | crash (conditional) / leak | Far terrain: failure handling | Only `draw` has a handler, and it abandons everything on failure; `begin`/`ensureWorld`/`chunk` have none (a throw in the chunk hook disconnects the player, javap-confirmed); `Error`s (Metal out of memory) pass `catch (RuntimeException)` | code + javap |
| BH3-08 | crash (rare race) | Far terrain: leaving a world | Mesh workers are interrupted but not joined before the clipmap's buffers are released; a worker still inside `copyRegion` reads freed memory | code |
| BH3-09 | data written outside the cache (security hygiene) | Far terrain cache path | The dimension id is a path component of the cache folder, and a server chooses it; `Identifier` paths may contain `..` and `/` (javap) | code + javap |

Counts by primary severity: wrong output 3, crash 2 (one conditional, one a rare race), leak 2, perf 1, hygiene 1; minor items below.

---

## BH3-01  Record Mode's camera is degenerate at pitch exactly +-90 degrees

**Severity:** wrong output; it also invalidates the headline benchmark numbers.
**Where:** `metal/src/main/java/dev/cadence/metal/record/Record.java:471-481` (`camera`, the basis at 475-478), written for four cameras a
frame at `:381-390`; read by `metal/src/main/resources/cadence/record/record.metal:79` (`rayDir`), `:322-335` (the in-between frame's
reprojection into cameras a and b) and `Record.screenRect` (the entity boxes' screen rectangles, from the same floats).

**What is wrong.** The right vector is `normalize(forward x worldUp)` computed as `(-fz, 0, fx)` over `rl = max(1e-9, hypot(rx, rz))`
(line 475). At pitch +-90 the horizontal part of `forward` is `cos(pi/2)` = 6.1e-17, so `rl` is clamped to 1e-9 and `right` and `up` come out
6.1e-8 long instead of 1. The shader's ray is `normalize(fwd + right * (ndc.x * tanX) + up * (ndc.y * tanY))`, so every pixel gets
`(0, -+1, 0)` (a corner pixel differs by 8e-8). I checked the arithmetic numerically: `|right| = |up| = 6.123e-08` at +-90, 1.0 at 89.9999999.
Vanilla clamps the player's pitch to exactly +-90.0F, both `xRot` and `xRotO` (`javap -c Entity.turn`: `Mth.clamp(.., -90.0f, 90.0f)`), so
"look all the way down/up" lands exactly on the bad pose and `getViewXRot(partial)` interpolates between two equal values.

**Evidence (run).**
- `metal/run/results/1024-000104/selftest-5-wait_8-record.jpg`: "Facing: south (0.0 / 90.0)", one flat brown frame, "48152 fps (24076
  rendered)", "GPU 33 us/frame". The game's own frame at the same pose, `selftest-3-cmd_tp__me_101_69_456_0_90.jpg`, is a detailed leaf canopy.
- `metal/run/results/1024-225000/selftest-7-wait_2-record.jpg`: "Facing: south (0.0 / -90.0)", flat blue, "132934 fps (66467 rendered)",
  "GPU 12 us/frame"; the game's frame at that pose (`selftest-5-...-90.jpg`) shows sun glow and clouds.
- The harness poses are `tp @me 101 69 456 0 90` (looking down) and `tp @me 101.5 130 456.5 0 -90` (looking up) in the `armed:` lines of
  `1024-000104`, `1024-000821` and `1024-225000`. The numbers they gave are the ones written up as the record: `docs/dev-log/RECORD.md:107`
  ("looking straight down ... 25,300 (best 26,300)"), `:121` (the recipe: "Look straight down ... stand still"), `:152-155` (the new table:
  sky 66,200 / best 132,934, looking down 24,000-27,000; the 30-degrees-up row, 33,000, is a real view), `CHANGELOG.md:10-11` and `:32`
  ("looking down: 25,300 fps rendered ... The Hone record is 21,632").
- The only real-scene reading I could find at a steep angle is `1024-225000` step 11, pitch 15: 16,367 rendered, 54 us GPU a frame.
- The HUD's "Best:" counter is a session maximum (`Record.best`, never reset), so a single look straight up or down sets it from the
  degenerate frame; the "record attempt" recipe ("look up, read Best") reads exactly that.

**Trigger for a player.** Look at your feet (bridging, pillaring, mining) or straight up: the screen is one colour (the top of the first block
below the eye, or the sky). Anything short of the stop is fine; the basis only collapses within 6e-8 degrees of 90, which the mouse reaches
only by hitting the clamp.

**Fix.** Build the right vector from the yaw, which is exact at every pitch and identical to the current result for |pitch| < 90:
```java
double rx = -Math.cos(ry), rz = -Math.sin(ry);   // (replaces line 475-477; keep up = right x forward)
```
Then re-measure "looking down" and "looking up" at 89 degrees and at the stop, correct the numbers in `RECORD.md` and `CHANGELOG.md`
(the rows above), and change the self-test poses to 85 / -80 so the benchmark cannot silently go degenerate again (a cheap guard: assert
`|right|` and `|up|` are within 1e-3 of 1 in `camera`, or compare the snapshot's colour variance in the self-test).

---

## BH3-02  Render Max FPS holds "Loading terrain..." for 30 s at every join and dimension change again

**Severity:** perf (a repeatable 30 s stall); a regression of BUG-HUNT-10-21 **BH-09**.
**Where:** `Record.java:78-82` (`active()`: `mc.level != null && mc.player != null && mc.gui.overlay() == null`, no loading-screen test);
`shaders/.../mixin/MaxFpsModeExtractMixin.java:15` (cancels `LevelExtractor.extract`), `MaxFpsModeMixin.java:25-29` (cancels `renderLevel`)
and `:34` (cancels `extractCamera`). The old guard, `shaders/.../render/MaxFpsMode.java:36` (`on && BLANK && !(screen instanceof
LevelLoadingScreen)`), only applies to the legacy black-screen variant, so the default (Record) path never reaches it.

**What is wrong.** Vanilla's loading screen ends when `LevelLoadTracker$WaitingForPlayerChunk.isReady()` is true: the `playerSectionReady`
flag, or a 30 s timeout that logs "Timed out while waiting for the client to load chunks, letting the player into the world anyway"
(javap: `isReady`, `CLIENT_WAIT_TIMEOUT_MS`). The flag is set by a `Runnable` that `LevelExtractor.extract` stores in
`LevelRenderState.playerCompiledSectionCallback` (javap, `LevelExtractor.extract` offset 1065) and `LevelRenderer.render` runs once the camera's
section is compiled and visible (javap, `LevelRenderer.render` offset 923-966). While the loading screen is up the player and level exist and
it is a screen, not an overlay, so `Record.active()` is true: `extract` returns at once and `renderLevel` is cancelled; the callback is never
stored or run and the screen waits out the full timeout.
`3cffe43` ("the world stays under screens", tonight 21:43) deleted `mc.gui.screen() == null` from `active()`
(`git show 3cffe43 -- .../Record.java`), which is what used to keep the loading screen out.

**Evidence.** BUG-HUNT-10-21 BH-09 measured the same mechanism live: `metal/run/results/1022-004445/stdout.log` shows the
timeout warning exactly 30 s after the join; the fix, the exemption in `MaxFpsMode.on()`, did not carry over. I did not re-run it (the
Record self-test plans never join a world with the mode on).

**Trigger.** Turn Render Max FPS on, then join a world, enter a portal, or leave to the title and join again in the same session (the
setting is not saved but survives leaving a world).

**Fix.** `Record.active()` adds `&& !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)`. Add a self-test
plan "record on, then `leave`, `join=tester`" and fail on the vanilla timeout warning (the 10-21 plans had it).

---

## BH3-03  Far terrain keeps queuing chunk summaries while Record Mode is on

**Severity:** leak (heap). Only with Far Terrain on (experimental, off by default) and Render Max FPS chosen, but then unbounded.
**Where:** `metal/.../lod/Lod.java:1476-1482` (`chunk`), `LodField.java:476-486` (`chunk`) and `:802-809` (`integrate`, the only drain of
`results`), called only from `Lod.java:570` (`draw`), which runs only from `Lod.begin` through `LodLevelRendererMixin.render` HEAD.

**What is wrong.** With the mode active `GameRenderer.render` (no screen) or `renderLevel` (screen open) is cancelled, so
`LevelRenderer.render` never runs: `Lod.beginLevel` and `draw` never run and `integrate` never drains. `LodClientLevelMixin` still calls
`Lod.chunk` for every chunk loaded **and** unloaded: each makes a `LodChunks.Snapshot` on the render thread, a `Summary` on a worker
(`short[]`x4, `int[]`x8, `boolean[]`x2, `byte[]` of 256: about 11.3 KB) and a closure in `LodField.results`, a
`ConcurrentLinkedQueue` with no bound. At render distance 12 that is about 13 events a second walking (about 0.15 MB/s, 0.5 GB an hour),
several times that gliding with an elytra. The same holds in the `-Dcadence.maxfps.blank=true` variant. The far terrain draws nothing in
this mode, so all of it is waste; when the mode is turned off the first frames then drain a backlog of stale summaries at 1.5 ms a frame.

**Fix.** Either drain while active (a `Lod.pump()` that runs `field.integrate()` every few dozen frames from `Record.frame()`; the mesh
side is bounded by the number of tiles), or keep only the newest summary per chunk key in a map capped at the window's chunk count and apply
it on the next `draw`. Skipping `Lod.chunk` while the mode is on is simple but loses what you explored meanwhile (on a server the explored
land is the only far terrain there is), so I would not do that.

---

## BH3-04  The far-terrain cache cannot tell two worlds with the same folder name apart

**Severity:** wrong output (persistent on disk); also unbounded disk growth.
**Where:** `Lod.java:454` (singleplayer key: the world folder's file name), `:460-462` (servers: lower-cased address, every other character
`_`), `:465-466` (cache path), `LodField.java:337-347` (`load` accepts any tile with the right FORMAT and resource-pack signature; nothing
identifies the world), `:279` (every generated tile is saved).

**What is wrong / triggers.**
- Delete "New World" and create another one: Minecraft offers the name "New World" again and the seed is new. The new world's far terrain
  is read tile by tile from `cadence-lod/New World/minecraft_overworld/L*/..` wherever files exist: the old world's land (and trees),
  until real chunks overwrite it. The same happens when a world folder is replaced or restored from a backup, or a map is copied in.
- Servers: the key is the address only, so a server that resets its map (or a different server on the same address) shows the old explored
  land. For a LAN guest the key is `host:port` (javap: `LanServerDetection$LanServerList.addServer` builds `host:port`) and the host's port
  is new every time it opens to LAN: a fresh cache folder for every session, no reuse.
- Nothing prunes `cadence-lod`: deleting a world leaves its tiles forever. `metal/run/cadence-lod` is 95 MB in 11,935 files after the test
  runs.

**Fix.** Put the world's identity in the key: the seed for generated worlds (`ServerLevel.getSeed()`, `sl` is at `Lod.java:446`; for a
LAN host too) as `<folder>-<seed hex>`, or a `world.id` file in the folder compared on open (wipe or ignore the folder on a mismatch). For
servers add the port, and the hashed seed the client received (the `long` that `ClientPacketListener.handleLogin` passes to the
`ClientLevel` constructor, kept in `BiomeManager.biomeZoomSeed`; needs an accessor mixin) so a reset map starts clean. Add a size budget
(least recently used first) and a start-up pass that drops folders whose save no longer exists.

---

## BH3-05  Far-terrain workers: retry storm, an over-wide OOM latch, and a corrupt count that trips it

**Severity:** wrong output (the far terrain stops updating), perf (log and CPU churn).
**Where:** `LodField.java:125-157` (`work`), `:337-392` (`load`, the counts read at `:361-362` and `:374-375`).

**What is wrong.**
- (a) `catch (Throwable t)` (`:151-155`) prints the stack trace and removes the tile from `pending`. The next scan (every move, and every
  0.5 s while any tile is missing: `updateScreen`, `:935`) queues it again, so a tile that fails deterministically fails forever and is
  never resident. Evidence (run): before the OOM latch existed, `metal/run/results/1022-210917/stdout.log` holds 1,628 "cadence-lod: job
  failed: java.lang.OutOfMemoryError: Java heap space" lines in about four minutes. The latch (added in `39525bc`) covers OOM only; any
  other exception (a biome or feature from a worldgen mod, a corrupt tile) repeats the same way, and keeps `settled` false and the progress
  line stuck.
- (b) The latch (`:138-141`, `:149`) makes `work` drop **every** job afterwards, including the chunk-summary jobs (`Job.task != null`:
  the real-chunk overlay, which on a server is all the far terrain there is) and the saves of evicted tiles (`saveTile`), not just
  generation. The log line says "generation stopped". One OOM in any worker (it is only the thread that hit the limit; the game's own
  allocations may be the cause) ends all updating for the rest of the world and nothing resets it.
- (c) `load` reads `int n = in.readInt(); new int[n]` (`:361-362`, `:374-375`) with no bound. A tile written by a build that changed the
  layout without bumping `FORMAT`, or a damaged file, gives a huge `n`: `NegativeArraySize` is caught by `catch (IOException |
  RuntimeException)` but the `OutOfMemoryError` is not, so it reaches `work` and trips (b). Saves write at most `LodPalette.MAX` (1024)
  names, so anything larger is a bad file.

**Fix.** Count failures per key; after 2-3 give the tile the empty "no data" words (as the explored-only path does at `:259-262`) and stop
retrying, logging once. Make the latch skip only generation and pre-generation jobs (`task == null` and the pre-generation tasks), keep
chunk and save jobs, and clear it after a while (say 60 s). In `load` reject `n < 0 || n > LodPalette.MAX` as a bad file.

---

## BH3-06  About 3 MB of native memory is never freed per far-terrain world or dimension

**Severity:** leak (native, small per event). A session with many portal trips or world switches adds up (100 changes: ~300 MB).
**Where:** `LodMesh.java:94` (`frame`, 432 B) and `:196` (`tableHost`, levels x slots x 16 B) are `nmemCalloc`'d and never freed;
`:266-278` (`work`): `header`, `quads` and `scratch` are allocated per worker, freed only at `:341-343` after the loop, but `close()`
(`:802-806`) interrupts the workers and an idle one is blocked in `jobs.take()`, so it takes the `catch (InterruptedException)` `return`
at `:276-277` and skips the frees. Per worker that is `quads` 1 MB (`SCRATCH_QUADS = 1 << 17`, 8 B each), `scratch` 393 KB (6 x 128 x 128 x
4, with PAIRS) and `header` 2 KB; 2 workers by default. Mesh results still in `results` when the mesh closes (`:330`, `:455` frees them
only when processed) leak too. `LodNativeNoise.java:420-437` (`Natives.attach`) creates a `mcn_stack_new` copy of each noise stack per
`LodNoise.of` (per world or dimension) and keeps it "for the session" (`:419`, "a few hundred KB for the overworld's stacks");
`lodnoise.c:103` has `mcn_stack_free` but nothing binds or calls it.

**Trigger.** Every `dropWorld` (dimension change, leaving a world) with far terrain on and the workers idle, which is the usual case.

**Fix.** Free the three scratch blocks in a `finally` in `work`; free `frame`/`tableHost` in `close()` after joining (BH3-08); bind and call
`mcn_stack_free` when a `LodNoise` is dropped (or cache the stacks per seed and dimension).

---

## BH3-07  Far terrain's failure handling is inconsistent

**Severity:** crash (conditional) and leak; I know of no trigger other than memory exhaustion, so this is hardening.
**Where:** `Lod.java:213-226` (`run`), `:194-198` (`beginLevel`), `:315` (`begin`), `:432-490` (`ensureWorld`), `:1476` (`chunk`),
`Native.java:232` (`bufferNew`).
- Only construction (`get`, `:149-167`) and `draw` (`run`'s `catch (RuntimeException)`) have a handler. On a `draw` failure it sets `failed`,
  `instance = null`, `activeReach = 0` and **abandons** the instance: no `dropWorld`, so ~190 MB of Metal buffers, the field and mesh worker
  threads and the scan thread stay, dirty tiles are not saved (on a server: up to 30 s of explored land), and far terrain is off until the
  game restarts.
- `beginLevel` (called from `LevelRenderer.render` HEAD) runs `begin` and `ensureWorld` with no handler: `LodNoise.of` (`:452`: reflection over
  worldgen records, registry lookups), `new LodClip` (`:469`) and `new LodMesh` (`:471`) can throw, and so can `LodMesh.grow()` (`:711`, in
  `integrate`). The Metal wrapper turns a nil buffer into an `OutOfMemoryError` (`Native.java:232`), an `Error`, which also passes the
  `catch (RuntimeException)` in `run`. Out of GPU memory therefore ends in a crash instead of "far terrain stopped".
- `ensureWorld` sets `worldOwner = owner` at `:438`, before anything that can throw. If a handler is added later without resetting it, the
  next frame's `owner == worldOwner` return leaves the world without far terrain silently.
- `Lod.chunk` and `blockChanged`/`unloaded` run inside `ClientLevel.onChunkLoaded`/`sendBlockUpdated`/`unload`. A throw from
  `LodChunks.snapshot` there reaches the packet handler, and vanilla's `ClientCommonPacketListenerImpl.onPacketError` logs "Failed to handle
  packet {}, disconnecting" and disconnects (javap): the player is kicked to the title screen.

**Fix.** One guard, used by every entry point: catch `RuntimeException | OutOfMemoryError`, call `dropWorld(true)` inside its own
try/catch, set `failed` (or better, retry on the next world change), log once. Reset `worldOwner` when `ensureWorld` fails.

---

## BH3-08  Mesh workers are not joined before the buffers they read are released

**Severity:** crash (rare race, native).
**Where:** `LodMesh.java:802-806` (`close`: sets `closed`, interrupts, no join); `Lod.java:374-413` (`dropWorld`: `mesh.close()` at `:381`,
`waitIdle` at `:407`, then `LodNative.release` of the clipmap's five buffers) as called with `now = true` by `worldClosed` (`:420-430`);
`LodMesh.java:351-411` (`copyRegion`: reads `clip.geom`, `clip.color`, `clip.crown`, `clip.tex` through raw addresses, `:392-401`).

**What is wrong.** A worker already inside a job when `closed` is set finishes the job (the flag is only tested at the loop head, `:272`).
`worldClosed` releases the buffers right after the GPU wait, so a worker still in `copyRegion` (about 100 us of work, longer in the
non-PAIRS mode where `meshTile` reads the clipmap directly) reads memory the Metal buffer has given back. The window is tiny, but the
workers run at below normal priority and a loaded Mac can deschedule one in the middle. The dimension-change path is safe: it releases
RING frames later.

**Fix.** `LodMesh.close()` joins its workers (`t.join(500)`) before `dropWorld` releases anything; the same join is the place to free the
native blocks of BH3-06.

---

## BH3-09  The dimension id is an unsanitised path component of the cache folder

**Severity:** data written outside the cache root (security hygiene; I know no code-execution path).
**Where:** `Lod.java:466`: `root.resolve(save).resolve(namespace + "_" + path)`; `LodField.save` (`:400-447`) and `LodBeacons.save`
(`LodBeacons.java:84-105`) write under it.

**What is wrong.** `Identifier` paths may contain `.` and `/` (javap `Identifier.validPathChar`: `_`, `-`, `a-z`, `0-9`, `/`, `.`), so `..` is a
valid path. On a server the level's dimension key comes straight from the login or respawn packet (`CommonPlayerSpawnInfo.dimension()`
into `new ClientLevel(...)`, javap `ClientPacketListener.handleLogin`) and the far terrain is on for such a level. A key such as
`x:../../../../../../tmp/y` makes `createDirectories` and the tile writes (`L<n>/<tx>.<tz>.lod`, `beacons.bin`) land outside the cache
root, as far up as the `..`s go (`createDirectories` makes the literal `x_..` folder first, so the later `..`s resolve), replacing a file of
the same name. (A legitimate `/` in a modded dimension id just nests; harmless.) The server address is already sanitised at `:462`; the
dimension is not.

**Fix.** Sanitise the folder name like the address (`[^a-z0-9._-]` to `_`, no leading dots) plus a short hash of the full id, and assert
`cache.normalize().startsWith(root.normalize())` before use.

---

## Minor items

- **Record `dirtySet` key collides for 32+ sections.** `RecordWorld.java:54,126,134` key a dirty section as `chunkKey(sx, sz) * 31 + sy`.
  Two sections of neighbouring columns whose `sy` differ by 31 (column z and z+1) share a key, so the second update is dropped and its voxels
  stay stale until the next change. Needs a dimension 512+ blocks tall (32+ sections); vanilla's 24 cannot collide. Use
  `SectionPos.asLong(sx, sy, sz)`.
- **Title screen and menus run uncapped with Record chosen.** `FramerateCapMixin.java:17` returns `BackgroundFps.fpsCap()` (unlimited when
  focused) whenever `Record.chosen()`, even with no world loaded; vanilla throttles `OUT_OF_LEVEL_MENU` to 60 fps (javap:
  `FramerateLimitTracker.getFramerateLimit`, `FramerateThrottleReason.OUT_OF_LEVEL_MENU`) and the mixin throws that `limit` away. Leave
  to the title with the mode on and the menu renders at full speed until the mode is turned off (it is not saved, but it survives the
  world). Keep the vanilla cap when `mc.level == null`.
- Far terrain cache: a crash during a save leaves `<tx>.<tz>.lod.tmp<threadId>` files forever (nothing cleans them; none found in
  `metal/run/cadence-lod` today).

---

## Checked and fine

- **Record structs and indices.** `record.metal` `Frame` is 448 B (4 cameras + 12 x 16) and every offset `Record.writeFrame` writes
  (256 sky, 272 fog, 288-300 grid, 304-316 size, 320-332 misc, 336-348 flags, 352 hud, 400-412 target, 416-428 status, 432-436 chat) matches;
  `Entity` 48 B; the tile-mask region (`TILES_MAX = 256 x 144`) covers the largest frame (2048 x 1080); the voxel word layout (FULL bit 30,
  colours 0-14 / 15-29; SPECIAL bit 31, id 0-11, light 12-19; air = light) and `cellIndex` with negative window origins (`& mask`);
  height-field cells and `top`; `screenRect`/`pixels` clamp to the frame; the HUD's glyph index and `put` are bounded, chat text is never a
  format string; the 3-camera history (`CAMS`, N-2 and N-3 for the in-between frame) matches the comment in `mcrecord.m`.
- **Record GPU plumbing.** `MetalEncoder.submit` batching (`:201`) never defers a frame that presents or is profiled, and a screen frame
  commits at once; `dropPendingClear` only forgets the colour clear; retained command buffers keep the old `RecordWorld` and HUD buffers
  alive after `release`, so the early `releaseRetired` in `RecordHud.frame` is not a hazard.
- **Far-terrain math.** `LodTile.key/txOf/tzOf` (29-bit sign-extended, round trip for negatives), `LodClip` toroidal slots, `applyChunk`'s
  `ceilDiv`/`floorDiv` sample points, `LodField.update` windows; tile saves are tmp + atomic move; `LodBeacons` bounds its counts.
- **Far-terrain memory.** `LodMesh.grow()` frees exactly the added range (BH2-01 fix is right), free-list coalescing, ring release (RING 4
  against 2 frames in flight), the `copyRegion` sequence lock; `Lod.begin`'s release clock (BH2-14 fix) and `worldClosed` (BH2-02 fix);
  `dropWorld` releases all five clip buffers.
- **`6d5a4c7` (plant height).** `LodPalette` writes the plant's tallest texel into the side colour's `w` (`:73`), `STRIDE` 20 floats = the
  80-byte `PaletteEntry`, `mclod.m` binds `texWords`/`palette` at vertex buffers 20/21 for the plant draw and `lod_mesh_plant_vs` reads them;
  indices and sizes agree.
- **Settings.** The Far Terrain page's keys reach `Profile.farTerrainSettings` (Look keys too, regex-checked) and apply live through
  `applyLook`; Far Clouds and Progress caches reset on each click.
- **Entity-state reuse (`45df4b3`).** `EntityStates` with its three mixins reuses a state per entity and `FeatureDrawContext.stateTags`
  is cleared per extraction; I found no stale-state path.
- **Hook targets.** `tools/mixin-check.sh`: 521 members, 0 errors, against the compiled classes that were in `metal/build`,
  `shaders/build` and `fpshud/build` when I ran it (it is plain `java` + ASM: it starts neither Gradle nor the game, and I did not rebuild).
- **Vanilla facts checked with javap (26.3):** `Entity.turn` clamp, `Identifier.validPathChar`, `ClientPacketListener.handleLogin` and
  `ClientLevel`'s constructor arguments, `LevelLoadTracker$WaitingForPlayerChunk.isReady` and its 30 s timeout, `LevelExtractor.extract` /
  `LevelRenderer.render` and the compiled-section callback, `ClientCommonPacketListenerImpl.onPacketError`,
  `FramerateLimitTracker.getFramerateLimit`.

## Looked at and dropped (could not substantiate)

- Chunk events of another dimension corrupting the old field's tiles: not real. `applyChunk` only runs through `LodField.integrate`, called
  from `Lod.draw`, which runs after `ensureWorld` has closed the old field. Only `LodBeacons.chunk` acts at once (a beacon loaded in the
  few ticks between a dimension change and the first frame goes to the old dimension's file), a negligible window.
- Real chunks that load before the first far-terrain frame of a world are not applied to its field: harmless, `unload` writes each
  chunk's final state into the field just before the far terrain takes it over.
- Reprojection smear of the Record HUD between interpolated frames: sub-pixel at record frame rates.
- Record's window fill at one `work` call per 16 frames is slow at low frame rates: design, not a defect.
- A premature `LodNative.release` of far-terrain buffers: command buffers retain what they bind, so a late release is memory-safe.

## Suggested order

1. BH3-01 (a few lines), then re-measure and correct `RECORD.md`/`CHANGELOG.md` (the record claim is the part that matters most).
2. BH3-02 (one line plus a self-test step).
3. BH3-03 and BH3-04 (they grow with use); BH3-05 with them (same file).
4. BH3-06 and BH3-08 together (both in `LodMesh.close`), then BH3-07 and BH3-09.

## Not tested / limits

- Nothing was run: BH3-02 is the 10-21 live repro plus the new code path read end to end; BH3-03 to BH3-09 are code readings with exact
  triggers, none reproduced. BH3-01's cause is shown by arithmetic and by the dev's own screenshots, not by a fresh run.
- I read the far-terrain Java side, `mclod.m`, `lodmesh.c`, the Record Java and `mcrecord.m`/`record.metal` closely, `columns.metal` only in
  the frame structs, the publish and pack paths and the plant shader (about 1,000 of its 3,900 lines), and I did not review the shader-pack
  runtime beyond its far-terrain hooks (`ShaderRuntime`, `DistantTerrain`, `FrameUniforms`).
- BH3-08's probability and BH3-07's triggers are estimates from reading (a descheduled low-priority worker; memory exhaustion).
- The tree changed under me (the three uncommitted files became `6d5a4c7`, then docs commits, then the uncommitted edits named at the
  top); everything above is against the committed source, and the working-tree fixes were only read, not tested.
