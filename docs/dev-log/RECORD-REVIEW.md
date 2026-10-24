# Record Mode review (10-23 night, commits 39c7d6b..77f7468)

Read-only review. Nothing was run (no game, no GPU, no Gradle); every claim below comes from reading the code, the git
history and `javap` of the 26.3 client jar (`~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar`). Where a finding needs
one look at a real screen to confirm, it says so.

Ranked most severe first. "Sev" is crash / wrong output / perf / minor.

---

## 1. A stale record frame is presented over every screen (pause, inventory, chat, death, options)  [wrong output, unplayable]

- `Record.java:69-71` `presentHandle()` returns `chosen ? presentHandle : 0`. `presentHandle` is only zeroed in
  `Record.set(false)` (58), `worldClosed()` (85) and on failure (206). `Record.active()` (62-66) going false because a screen
  opened does **not** clear it.
- `MetalSurface.java:118-119` runs for every frame the game presents (`Minecraft.runTick` always calls
  `GpuSurface.blitFromTexture`, verified in the bytecode): `if (record != 0) presentHandle(drawable, record)`.
- Scenario: Render Max FPS on, in a world, press Esc. `active()` is false, so `RecordModeMixin` does not cancel, the game draws
  the pause menu into its own target, and `blitFromTexture` then presents the last record texture (`color[(cur+2)%3]`, which
  nothing writes any more). The player sees a frozen world, no menu. Same for inventory, chat, the death screen, and the
  Video Settings screen that has the button to turn the mode off (it can only be reached blind). docs/dev-log/RECORD.md line 29
  and the button tooltip both promise "any screen draws the game as usual".
- Fix: make the handle valid for one present only. E.g. `Record.frame0` sets it, `presentHandle()` returns it and zeroes it
  (take-once), or `RecordModeMixin` zeroes it when `!Record.active()`; or compare a `presentedFrame == frames` stamp.

## 2. The record image is probably presented upside down  [wrong output, verify with one screen capture]

- `mcmetal.m:30-33` (`present_vs`): `uv = p`, vertex 0 is NDC (-1,-1) = bottom of the drawable, so **texture row 0 lands at the
  bottom of the screen**; the comment says "row 0 is the scene's bottom row", `mcmetal.m:1328` / `MetalPipeline.java:34`
  "Vertex Y is flipped so render targets keep the same row order as OpenGL", and `mcmetal.m:1932` "(flipped back to top-down)".
  So game targets are bottom-up and `mc_present` flips them.
- `record.metal:99-100` (and `interpolated()` 292-293, the `uv = 0.5 - q.y*0.5` mapping 306) writes row 0 = top of the view
  (`ndc.y = -ndc.y`), and the HUD overlay (`RecordHud.put`, y = 0 top) and `Record.snapshot` are top-down too. `Native.present`
  (`MetalEncoder.presentHandle`, line 898-901) uses the same `mc_present`, so the record texture is shown vertically
  mirrored: hotbar at the top, F3 text bottom-left upside down.
- Why it was likely missed: the self-test's screenshots come from the game's own target (black in record mode) and
  `Record.snapshot` reads the raw texture, which looks upright. Nothing in the repo looks at the presented drawable.
- Fix (cheapest): in `record_trace` write `outColor`/`outDepth`/`outMid` at `uint2(gid.x, size.y-1-gid.y)` and mirror the reads
  in `interpolated()` and the HUD index; or give `mc_present` a "no flip" variant for `presentHandle` (a second pipeline with
  `uv.y = 1-p.y`). Do the latter if `snapshot()` should stay top-down.

## 3. Third person (F5) fills the screen with the player's box  [wrong output]

- `Record.java:461`: the camera entity is skipped only while `getCameraType().isFirstPerson()`. The camera itself is always the
  eye (`writeFrame` 298-303), never the third-person offset. In F5 the player's own box is therefore in the list, the eye is
  inside it, `screenRect` (545) returns the whole screen and the shader (`te = 0`, `te < hitT`) paints every pixel
  `0x3B6FD0` shaded. Playable-breaking for anyone who presses F5.
- Fix: always skip `e == cam` (or implement the third-person camera offset).

## 4. Window is not centred: only 16-32 blocks of world in +x/+z, but fog ends at 28  [wrong output]

- `RecordWorld.java:100`: `nx = (floor(camX) - 32) & ~15`, and `follow` only moves when `nx` changes (steps of 16). So
  `cam - ox` is in [32, 48): the window reaches 16.1..32 blocks ahead in +x/+z and 32..48 behind in -x/-z. docs say "2 chunks each
  way".
- The shader stops every ray at the window (`tExit`, `record.metal:107`), the fog is `smoothstep(16.8, 28)` (`Record.java:325,
  328`) and `Frame.fog.w` = 28. Looking +x/+z from the far end of a chunk pair the terrain simply ends 16-17 blocks away
  where the fog has barely started, and the gap shows the sky mix. Entities are selected out to +-30 (`writeEntities` 458) and
  can sit beyond that edge. Walking back and forth over a 16-block boundary also flips the window (no hysteresis) and rewrites 4
  columns each time, with the gained far columns showing wrapped (old) terrain until written.
- Fix: round instead of floor (`(floor(cam) - 32 + 8) & ~15` gives `cam - ox` in [24, 40)) and set `fogEnd` <= 24 and the entity
  half-range to 22; or use a 128 window.

## 5. Turning Record Mode off and on again shows a stale world  [wrong output]

- `Record.set(false)` (51-59) keeps `world` (RecordWorld), and `RecordSectionMixin.java:15` / `RecordChatMixin` drop events
  while `!chosen`. `frame0` (176) reuses `world` when the level object is the same. Every block or light change made while the
  mode was off (mined, placed, torches, doors) is missing until those chunks reload; only columns gained by `follow` are
  rewritten.
- Fix: in `Record.set(false)` call the `worldClosed()` logic (release the window and null it), or keep receiving dirty events
  while a world copy exists.

## 6. `Record.worldLevel` keeps the whole ClientLevel alive after leaving a world  [memory, 8 GB Mac]

- `Record.java:213` `private static ClientLevel worldLevel`, assigned at 179, never cleared by `worldClosed()` (80-86).
  `NEAR[]` (447) also holds `Entity` objects (each references the level) and is never cleared. After a record session, quitting
  to the title screen leaves the old level (chunks, light, entities) reachable until the next record frame in another level;
  this is exactly what `WorldClosed.run` exists to prevent (its javadoc: hundreds of MB).
- Fix: null `worldLevel`, `chosenCount = -1`, clear `NEAR`, `tilesSet = 0` in `worldClosed()`.

## 7. Entity boxes and tile masks are rewritten under in-flight frames: intermittent entity flicker  [wrong output, no crash]

- The GPU reads `entities` (boxes + tile masks, one shared buffer) up to ~16 command buffers x 4 frames after the CPU encoded
  that frame (`RECORD_IN_FLIGHT` 16, batch 4), so a frame sees entity data ~64 frames newer than its camera. At 12k fps that is
  ~5 ms (invisible); at low frame rates (a heavy GPU frame, background throttling) it is tens of ms and entities slide against the
  world during turns, and the +-1 px rect margin no longer covers the mismatch.
- Worse, `placeEntities` (`Record.java:500`) first writes **zero** to every tile it set last frame and only then sets the new
  masks (524-530). Any GPU frame executing in that window sees tiles with no entity bits, so entities vanish in horizontal
  bands for that frame. The window is a few microseconds out of a ~50-80 us CPU frame, so on the order of a few percent of
  frames show it; the presented frame is one of ~200 per refresh, so it appears as occasional entity flicker.
- Same class (harmless): `RecordHud.draw` `memSet`s the whole overlay then redraws it (`RecordHud.java:84`), so 4 times a second
  (and on any bar change) the HUD is blank for the duration of the redraw; at 1080p the memset + redraw is several ms of CPU
  too. Grid/occ/light/cells are written in place while frames read them: transient wrong voxels only, all indices stay in range
  (checked, see "verified OK").
- Fix: keep a shadow int[] of the masks, compute the new ones, and write each changed tile once (no zero step); better, a ring of
  N copies of the entity region (N >= frames in flight + batch, ~72, region sized to the real tile count) bound at
  `frames % N * stride`. Double-buffer the HUD overlay the same way (the existing `retired` logic can serve).

## 8. FPS counter and the record rules

- **Stale `inBucket` inflates the first reading and `best` after a re-enable.** `Record.set` (51-56) resets `bucketStart`,
  `filled`, `peak` but not `inBucket`, and `count()` (621-624) on `bucketStart == 0` returns without zeroing it. Turn the mode off
  mid-bucket (inBucket up to ~3000 at 12k fps), turn it on again: the first bucket carries that stale count plus its own, the
  fourth bucket then reports `total / ~1 s` with up to a quarter more frames than were rendered, and `best` (only ever raised) keeps
  it. Fix: `inBucket = 0` in `set()` and in the `bucketStart == 0` branch.
- **The interpolated frame is never shown.** `mcrm_frame` binds `mid` for writing only (`mcrecord.m:110`); nothing reads or
  presents it (`presentHandle` is `color[cur]`). `shown()` (643) = rendered x 2 still adds a frame to the headline number for it.
  In the camera-still case (`flags.x == 2`, `record.metal:290`) it is just the mean of two stored frames (~1.5 us), no scene
  work. The label ("N fps (M rendered)", "Interpolated frames counted apart") is honest, but R04 ("every frame really rendered")
  and a record checker are likely to treat "frames counted that no one ever sees" as the thing to avoid. Decide whether to show
  the in-between frame (alternate presents) or to stop counting it.
- Counted frames are CPU-encoded frames, not GPU-completed ones: error at most the frames in flight (~64 + 3 uncommitted), under
  1% at 12k fps. Fine, but worth a line in the docs.
- Distance: terrain is cut at 28 blocks (16-32 in practice, finding 4) whatever the render distance; particles, clouds,
  sun/moon, weather and block-entity models are absent; water and glass are opaque full cubes (`record.metal:180-195`: a fluid
  is a full cube, 16/16 high instead of 14/16). A strict reading of R03 ("not rendering blocks or entities") may object; the
  "playable" argument is weaker for opaque glass/water.

## 9. `mcrecord.m`: dangling sampling counter and a stale GPU-time sampler  [crash (low probability) / minor]

- `mcrecord.m:122-141`: "same command buffer" is detected by comparing the `id` pointer with `r->lastCb` (not retained). When the
  previous command buffer has already completed and been freed, the next `commandBuffer` can be allocated at the same address.
  Then `cb == lastCb`, `r->sampledFrames` still points at the `n` that the previous buffer's completion handler already
  `free`d (line 138), and line 141 does `atomic_fetch_add(r->sampledFrames, 1)` on freed memory (heap corruption), or the
  new buffer is silently never sampled. Needs a sampled batch (1 in 16) followed by a reused address; more likely when the CPU is
  the bottleneck (small `Record.height`). Fix: `r->lastCb = [cb retain]` (release the old one), or have Java pass the commit
  index, or clear `r->sampledFrames` at commit.
- `mcrm_size` zeroes `r->frames` (87) but not `r->lastSample`, so after any resize `frames - lastSample` stays negative until
  frames catch up (at 20k fps and an hour of play: an hour), and "GPU x us/frame" in the F3 text is frozen. Also prints 0 until the
  first sample.

## 10. Failure handling  [robustness]

- `Record.java:204-209`: any `RuntimeException` anywhere in `frame0` (a modded block whose `getShape(EmptyBlockGetter...)`
  throws in `RecordWorld.writeEntry`, a light-engine NPE, a one-off) sets `failed = true` for the rest of the session; the mode
  silently reverts to the normal game with all the "fast mode" side effects still on (uncapped, no upscaling). No way to retry
  short of restarting. Suggest: count failures, retry a few times, and reset `failed` on `set(true)`.
- Same place: on that failure the frame's `extract` phase already ran with the Record cancels (`Record.active()` was true at
  extract time), then `renderLevel` runs on un-extracted state for one frame.
- `Record.java:176-179`: `world.release()` then `new RecordWorld(...)`; if the constructor throws (allocation failure ->
  `RecordNative.contents(0)` -> `memSet(0, ...)` SIGSEGV, or a level without sky data), `world` still points at the released
  instance and `worldClosed()` releases the same MTLBuffers again (over-release). Set `world = null` before constructing.
- Native `Rec`, `entities`, `hud`, `frameBuf` are never freed (`RecordNative.release` is unused); the world copy is only freed
  on level change. About 7 MB total, bounded; mentioned for completeness.

## 11. Gameplay / visual details  [minor to wrong output]

- Sky and fog colours are the overworld's in every dimension (`Record.java:323-324`): the Nether is blue-white haze, the End's void
  shows day sky. `Level.dimensionType().hasSkyLight()` is already read in RecordWorld; use it for a fog colour.
- `entitiesForRendering()` includes `Interaction` entities (invisible hitboxes servers use for NPCs/lobbies) and zero-size
  displays; only `isInvisible()` is tested (`Record.java:462`), so interaction boxes appear as solid coloured boxes on such servers.
- Palette cap: after 4096 distinct block states are seen (`RecordWorld.id`, 242), new states are marked "-1" forever and render as
  air (holes). Rare in vanilla, plausible with big worlds or mods; log it.
- Crossed plants take the first of the two planes that is hit, not the nearer (`record.metal:168-179`): wrong `hitT` only.
- `HUD`: only `RecordHud` text and bars; no boss bars, titles, action bar, subtitles (`Hud.extractRenderState` is cancelled in
  full by `MaxFpsModeHudMixin`). F2 screenshots stay black/stale (`tryTakeScreenshotIfNeeded` and the render target are in
  the cancelled `GameRenderer.render`).
- On a fresh `follow` or a teleport, window slots that were not yet written show the previous contents (wrapped old terrain)
  until the column loads; columns whose chunk never loads (server view distance 2 and the window reaching 3 chunks behind)
  keep that stale terrain permanently.
- Interpolation uses `camA/camB` from before a screen/mode gap (`camsKnown` is never reset), so the first mid frame after a gap
  is garbage; invisible today only because mid is never shown (finding 8).

## 12. Perf  [perf]

- `RecordWorld.work(400_000)` (`Record.java:187`) is checked only every 16th frame and always writes at least one whole column
  (24 sections x 4096 voxels, each with a palette-container read, `Block.getId`, two `DataLayer.get`, 4 puts) before it looks at
  the clock (`writeColumn` 159-164 has no budget check). A column is plausibly several ms: a hitch every 16 frames while the window
  fills (4 columns per 16 blocks walked, 16 on join), and at low frame rates (30 fps) refilling the window takes seconds.
  Check the clock between sections and key the stride on time, not frame count.
- `RecordHud.draw` at higher `Record.height` (1080p: scale 4, 8.8 MB overlay): memset + redraw several ms, 4+ times a second.
- Per-frame allocations (20k fps): `Vec3` from `getEyePosition`, four `float[16]` in `camera()` (`Record.java:384`, likely not
  scalar-replaced), `BlockPos.containing(eye)`; ~0.5 KB/frame. Small.
- `atlasHandle(mc)` (195, 284-289) is looked up and bound every frame but `record_trace` never samples the atlas (1x1 colours);
  it costs a map lookup plus a tracked texture dependency on the animated atlas. Remove the binding.
- `RecordSectionMixin` calls `sectionDirty` for every dirty event, including the thousands a resource/RD change fires; each is a
  `HashSet` insert + `int[3]` + boxed `Long` before the `inWindow` test drops it. Filter by window on insert.

## 13. Batching (`MetalEncoder.submit`, lines 199-202)  [no defect found; assumptions]

Checked and fine:
- A skipped commit cannot strand a batch: `recordFrame` is consumed by every `submit()`, so the first submit that is not a record
  frame (mode off, screen opened, world closed, resize race) commits the pending frames in the same command buffer; presents
  commit at once (`presentedThisSubmit`, set before `submit` because `blitFromTexture` runs first, bytecode-verified order:
  `render` -> `blitFromTexture` -> `submit` -> `present`); profiled frames commit.
- `afterThisFrame` runnables, `transientMemory.endSubmit()` and the fence list simply extend to the commit that includes all
  batched frames; `submitIndex` counts commits, so `RECORD_IN_FLIGHT` is 16 commits (64 frames).
- Resize: `mcrm_size` drops textures while the cb still references them: Metal 3 command buffers retain them (queue made with
  `commandBuffer`, not the unretained variant); Metal 4 defers through `mc4_unpin` ("dying" list keyed to the next commit,
  which is the one holding the batch). `RecordHud` retire (64 frames) and `RecordWorld.release` are likewise safe by retain
  (and are redundant under M4). Consecutive compute encoders have a barrier in M4 (`mcm4.m` serial encoders).
Assumption to keep true: nothing else in an iteration whose render was cancelled writes dynamic-uniform or transient memory
(`DynamicGpuData.reset()` runs every iteration, outside `submit`). Today `GameRenderer.render` is the only drawer and it is cancelled.

Notes on paths I did not exercise: `-Dcadence.metal.presentQueue=acquire` (`MetalSurface.java:114`) presents the game's own
view and ignores `presentHandle`, so Record would show a black/stale game target in that opt-in mode.

## 14. Test-only

- `Record.snapshot` (`mcrm_read`) commits its own blit on the queue; the record frames still sitting in the uncommitted batch
  (up to 3) are not in it, so it reads the last committed frame, and on the first frames an uninitialised private texture.
- `cpuSplit()` / `shown()` / `rendered()` are read and reset from the self-test thread without synchronisation: lost updates only.
  `Record.interpolate` is volatile but read at several points of one frame, so a toggle can split a frame.

---

## Verified OK (no defect)

- **Frame struct**: `record.metal` Frame = 4 Cameras (0,64,128,192) + sky 256 + fog 272 (fog.w 284) + grid 288 (x wlog, y h,
  z ox&mask, w oz&mask) + size 304 (w, h, count, lines) + misc 320 (mips, scale, fogStart 328, top 332) + flags 336 (interp,
  darken, gamma bits) + hud[3] 352..400 + target 400 (xyz, w 412) + status 416..428 + chat 432 (lines, tilesX 436) + light[4]
  448..512 = 512 bytes = `FRAME_BYTES`. Every `memPut` offset in `writeFrame`/`writeHud` matches. Camera layout (pos, fwd+tanX,
  right+tanY, up) matches `camera()` including the up-vector handedness and MC yaw/pitch convention.
- **Entity**: 48 bytes (lo, hi, rect); Java offsets 0/12/16/32 match; colour as `as_type<uint>(lo.w)`; 32 boxes x 48 = 1536 =
  `ENTITY_REGION` = `mcrecord.m`'s `32 * 48`; mask bits `1 << out` with `out < 32`; `TILES_MAX` 36864 >= 256 x 135 tiles at the
  clamped maximum size (2048 x 1080); tile indices derive from `rect` clamped to `w-1`/`h-1`; width changes are safe because the
  clear uses the stored indices.
- **Palette** 16 bytes (`PALETTE_STRIDE`), 4096 entries, ids < 4096; shape box 6 x 5 bits matches the decode.
- **Voxel index**: Java `((iy*w + iz)*w + ix)` with world coordinates mod w equals shader `cellIndex` (window-relative + `ox & m`)
  for negative values too; occupancy `at >> 5` / `at & 31` matches; sizes: grid `w*w*h*2`, light `w*w*h`, occ `w*w*h/8`, cells
  `(w/8)^2 * 4`, cells row/column order (z rows, x columns) and values (window y + 1) match the shader's skip loop; cell
  alignment holds because `ox`/`oz` are multiples of 16.
- **GPU bounds**: `v.y` is checked against `H <= grid.y` before every voxel/light/occ read; x/z wrap by mask; `pal[id]`,
  `tiles[...]`, `entities[i]`, `hud[...]` and `cells[...]` indices are in range for any torn or stale data; every loop is bounded
  (24 + 96 steps; fog-capped rays cross at most ~49 voxels). `RecordHud` bounds-checks every `put`; a resize that shrinks reuses the
  buffer, one that grows allocates a new one, so in-flight larger frames never read past a buffer.
- **Mixins** (descriptors checked with `javap` against the 26.3 jar): `GameRenderer.render()V`, `renderLevel()V`,
  `extractCamera(DeltaTracker,F)V`, `Hud.extractRenderState(GuiGraphicsExtractor,DeltaTracker)V`,
  `LevelExtractor.extract(DeltaTracker,Camera,F)V` and the private `setSectionDirty(IIIZ)V` (the public 3-int overload and the
  range/neighbour variants all end there), `ChatComponent.addMessage(Component,MessageSignature,GuiMessageSource,GuiMessageTag)`,
  `RenderSystem.pollEvents(SDLEventHandler)`. `GameRenderer.update` (which calls `Camera.update`) is outside the cancelled
  methods, so the camera and sound listener keep working; `Minecraft.pick(F)` runs per frame in `runTick`, so the crosshair target
  is per frame. All four new mixins are registered. Priority 400 on `render` HEAD runs before the other Cadence hooks.
  `active()` is evaluated in the extract phase and again in `render` within one loop iteration, so the two stay consistent
  (except the failure case in finding 10).

## Suggested order

1 (screens), 2 (flip: look at the window first), 3 (F5), 4 (window centring/fog), 5 and 6 (stale world, level leak), then 7, 8.
Everything else can wait.
