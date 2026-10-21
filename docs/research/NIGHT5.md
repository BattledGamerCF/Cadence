# Night 5 research: Metal edge cases, chip features, 8 GB memory, Minecraft UI

Date of research: 2026-10-20. Tags: [verified] = read on the source page (Apple .md pages or primary specs), [search] = search snippet or secondary source, [estimate] = my inference, not checked.

Note on sources: several developer.apple.com HTML pages returned only their title. The `.md` variant of the same URL (for example `.../cametaldisplaylink.md`) returned the full text, and that is what the [verified] tags below rely on.

---

## A. Edge cases for a Metal game on macOS

### A1. Window moves between displays (ProMotion 120 Hz vs 60 Hz, different backing scale)
- [verified] `CAMetalDisplayLink` is available on macOS 14.0+. Its `preferredFrameRateRange` is "a best attempt" and the system "also takes into account the device's hardware capabilities and the other tasks your game or app is running". https://developer.apple.com/documentation/quartzcore/cametaldisplaylink.md and https://developer.apple.com/documentation/quartzcore/cametaldisplaylink/preferredframeraterange.md
- [verified] The `CAMetalDisplayLink` page does not say what happens on a screen change. It does not mention window moves.
- [search] SDL watches the backing-properties change and sends a resize event when the backing scale factor changes, because the alternative is comparing drawable sizes every frame. https://git.axiodl.com/encounter/SDL/src/commit/218c3dbb348e042e02072f950915662a6f345e94/src/video/cocoa/SDL_cocoametalview.m
- [search] Apple's MetalInstancedHelix sample recomputes `drawableSize` as bounds times content scale and calls the reshape path when the display changes. https://developer.apple.com/library/archive/samplecode/MetalInstancedHelix/Listings/MetalInstancedHelix_AAPLView_mm.html
- [search] An Apple Developer Forums thread reports that `nextDrawable` often (not always) stalls for about 1 second after `drawableSize` changes. https://www.developer.apple.com/forums/thread/773229 (I did not open this page; the claim comes from the search snippet.)
- [verified] Forum thread 809460 (answered by a community member, not Apple): do size changes and frame encoding on the same thread, and recreate resources on resize instead of mutating in-flight ones. https://developer.apple.com/forums/thread/809460
- [estimate] Detection: observe `NSWindowDidChangeBackingPropertiesNotification` (scale changes) and `NSWindow.didChangeScreenNotification` (screen changes), then re-read `NSScreen.maximumFramesPerSecond` and re-apply the display-link range. I did not verify these notification names on a fetched page.
- What breaks if you skip this: a wrong drawable size (blurry or 2x too large on a Retina external display) and a display link that stays at the old rate.

**Implication for Cadence:** On any backing or screen change, recompute drawable = bounds x backingScaleFactor once, resize on the render thread, and re-read the refresh cap. Add a guard for the `nextDrawable` stall (measure it on the 8 GB machine before assuming it happens).

### A2. ProMotion / adaptive refresh on macOS
- [verified] `preferredFrameRateRange` defaults to the display's maximum refresh rate. The callback can be called at 60, 30, 20 or 15 Hz when the range is set lower. https://developer.apple.com/documentation/quartzcore/cametaldisplaylink/preferredframeraterange.md
- [verified] The system can lower the range for Low Power Mode, critical thermal state, and accessibility settings. Same page.
- [verified] Apple's doc says to choose a range the app "can consistently maintain". Same page.
- [verified] `CAMetalDisplayLink` is for variable-rate displays; `CADisplayLink` is recommended for less demanding or non-Metal apps. https://developer.apple.com/documentation/quartzcore/cametaldisplaylink.md
- [estimate] Setting range 60...120 with preferred 120 is the normal pattern. On macOS I did not verify whether the window's fullscreen state changes the achieved rate.
- [estimate] `NSScreen.maximumFramesPerSecond` reports the hardware maximum (120 on ProMotion) and does not tell you whether variable refresh is active. Notes from memory; the Apple page did not load.

**Implication for Cadence:** Use `CAMetalDisplayLink` (macOS 14+) and keep the existing thermal/LPM governor as the source of cap changes. Keep a `CVDisplayLink` or timer fallback for macOS 13 and below. Measure real cadence in fullscreen and windowed mode before claiming 120 Hz.

### A3. Sleep/wake, lid closed with external display, GPU loss, display disconnect
- [verified] `isRemovable` (macOS 10.13 through a listed end of 27.0.0) indicates whether a GPU can be removed. Apps register with `MTLCopyAllDevicesWithObserver` and handle `removalRequested` and `wasRemoved`. https://developer.apple.com/documentation/metal/mtldevice/isremovable.md
- [verified] Apple warns that "if a person removes a GPU without warning, MTLDevice APIs may fail even before your app receives a wasRemoved notification." Same page.
- [verified] Apple's `isRemovable` availability entry ends at macOS 27.0.0 on the page. Confirm before relying on it.
- [search] External GPU eject policy (`GPUEjectPolicy` = "wait") applies to external GPUs. https://developer.apple.com/documentation/metal/handling-external-gpu-additions-and-removals.md (linked from search, not opened)
- [estimate] On Apple silicon the built-in GPU is the only GPU and is not removable. I did not find a page that states `isRemovable == false` for it.
- Not researched: sleep/wake, lid closed with external display, and fullscreen display disconnect (see Gaps).

**Implication for Cadence:** Low priority. Check the command buffer error status, and if the device or drawable fails, recreate the device and layer instead of crashing. Test lid-close with an external display on real hardware.

### A4. macOS Game Mode
- [verified] Game Mode turns on "when your game enters full screen" and turns off when the game quits. A manual off setting sticks for that game. https://support.apple.com/105118 (also at https://support.apple.com/HT213658)
- [verified] Requirements: Mac with Apple silicon, macOS Sonoma 14 or later, and "a game that supports macOS full-screen mode". Intel is not listed.
- [verified] What it does: gives "the highest priority access to your CPU and GPU, lowering usage for background tasks", and doubles the Bluetooth sampling rate for controllers and AirPods.
- [verified] Toggle: Tahoe 26 uses Command-Esc / Game Overlay; Sequoia 15 and Sonoma 14 use the Game menu in the menu bar.
- [verified] Apple's page does not address windowed or borderless games. A Czech blog says borderless windowed can trigger Game Mode; MacKeeper says windowed play pauses it. Treat both as unverified.
- Not researched: `LSSupportsGameMode` / `LSApplicationCategoryType`, and whether a Java process launched by a launcher gets Game Mode.
- [estimate] Game Mode depends on native full screen (`NSWindow.toggleFullScreen`), not on exclusive full screen. If Cadence fakes full screen with a borderless window, it probably won't get Game Mode.

**Implication for Cadence:** Enter native full screen via `toggleFullScreen:` for the game window (a Metal-owned window), and show the Game Mode state in the Cadence UI. Test whether a Java-launched window qualifies.

### A5. Low Power Mode / High Power Mode
- [verified] `ProcessInfo.isLowPowerModeEnabled` is available on macOS 12.0+. Observe `NSProcessInfoPowerStateDidChange`, then re-read the property. https://developer.apple.com/documentation/foundation/processinfo/islowpowermodeenabled.md
- [verified] The page lists the effects as "Reducing CPU and GPU performance", reducing screen brightness, and pausing discretionary background activity. The page is written from an iOS perspective.
- Not researched: High Power Mode on MacBook Pro (macOS 14+) and its GPU clock behavior.

**Implication for Cadence:** Already detected by the mod. Add the notification observer so changes apply without a restart, and lower the frame-rate cap and the quality tier when LPM is on. Verify High Power Mode before adding it as a boost.

### A6. Scaled ("More Space") resolutions
- [search] Apple's sample code sets `drawableSize` from bounds times the content scale. This is the path that scaled modes use.
- [estimate] macOS renders at the backing scale and downsamples to the panel. A "More Space" mode is a larger logical size, so the backing store is larger and the GPU cost rises. I did not verify the cost.
- Not researched: recommended drawable sizes for scaled modes, and how other renderers handle them.

**Implication for Cadence:** Use the backing-scale path from A1 and add a render-scale slider (the RESOLUTION setting already exists in Blacksite; for Minecraft it can be the same concept). Measure fps at the default scaled mode before picking a default.

### A7. Rosetta 2 (x86 Java on Apple silicon)
- [search] Detect translation with `sysctlbyname("sysctl.proc_translated")`: 1 means translated, 0 means native, and a missing key (ENOENT) means native. https://developer.apple.com/forums/thread/652667 and https://developer.apple.com/forums/thread/659846 . The OpenJDK HotSpot source uses the same key. https://cr.openjdk.java.net/~lucy/webrevs/8233787.11u.01/src/hotspot/os_cpu/bsd_x86/vm_version_bsd_x86.cpp.sdiff.html
- [search] `uname -m` reports `x86_64` even on Apple silicon when run from a translated process, so it cannot be used to detect hardware.
- Not researched: typical slowdown figures for a JVM under Rosetta.
- [estimate] A translated JVM cannot load the arm64 Metal dylib, so the native library load would fail. Check the JVM architecture (`os.arch` and `hw.optional.arm64`) before loading the library.

**Implication for Cadence:** Add a startup check: `os.arch` must be `aarch64`, and `sysctl.proc_translated` must be 0. If not, show a clear "install an arm64 Java" message and disable the Metal path.

### A8. Intel Macs and AMD GPUs
- [verified] `MTLStorageMode.memoryless` "defines tile memory within the GPU that only the GPU can access" and is only for textures. https://developer.apple.com/documentation/metal/choosing-a-resource-storage-mode-for-apple-gpus.md
- [search] Memoryless storage is only available on Apple-family GPUs. Check with `supportsFamily(_:)`, not with a storage-mode property. https://developer.apple.com/documentation/metal/mtlstoragemode/memoryless.md (search snippet)
- [estimate] Intel and AMD GPUs have no tile memory and no unified memory. Storage-mode and memoryless assumptions break there, so the renderer needs an "unsupported" path.

**Implication for Cadence:** A clean "not supported" message needs: `arm64` architecture, a Metal device whose `supportsFamily` returns an Apple GPU family, and a device that is not removable. Any failure falls back to the existing OpenGL path.

### A9. Stage Manager, Split View, notch, Spaces
- Not researched. No search was run on these.
- [estimate] The notch falls inside the fullscreen safe area. A Metal view that covers the full screen will draw under the notch; the view's `safeAreaInsets` (if exposed by the Java window layer) is the thing to check.

**Implication for Cadence:** Test fullscreen on a notched MacBook and check that the HUD is not under the notch. Frame pacing in Split View or Stage Manager has not been checked.

---

## B. Chip-architecture features

### B1. GPU family differences (tile memory, Dynamic Caching, and related)
- [verified] Memoryless textures are tile memory, usable as render targets only. Buffers cannot be memoryless. https://developer.apple.com/documentation/metal/choosing-a-resource-storage-mode-for-apple-gpus.md
- [search] Imageblocks give more flexible transient tile data on tile-rendering devices. See the Metal Shading Language spec, sections 2.11, 4.5, 5.6.
- [search] M3 Dynamic Caching: registers and other private memory are drawn from the L1 cache, allocated over the shader's lifetime based on live use, not reserved at the worst-case count. Occupancy drops when registers stay live too long at high SIMD-group counts, when private memory overflows on-chip storage, or when memory requests back up. Apple tech talk 111431. https://developer.apple.com/kr/videos/play/tech-talks/111431/ (also AppleInsider: https://appleinsider.com/articles/23/11/10/what-apples-three-gpu-enhancements-in-a17-pro-and-m3-actually-do)
- [search] An anecdote reports a register-heavy shader losing 60% of threads per core on M1 Max and no change on A17 Pro. One commenter disputed the cause. Unverified.
- [search] M5 adds lower register-access latency and better cache efficiency on top of the M3 design (same tech talk, secondary summary).
- Not researched: family 7/8/9/10 feature lists, mesh shader and ray-tracing support by generation, sparse texture tiers, 64-bit atomics, BC support, and lossless texture compression (`compressionType`).

**Implication for Cadence:** Measure occupancy-sensitive shaders (the shader packs) on M1 and M3+ before assuming the same behavior. Keep register-heavy branches short on older chips. Treat any "family N" claim as needing a `supportsFamily` check.

### B2. Metal 4
- [verified] `MTL4CommandQueue` is available on macOS 26.0+ and iOS 26.0+. It commits `MTL4CommandBuffer`s and exposes `addResidencySet` / `removeResidencySet`, sparse texture and buffer mapping, and `signalDrawable` / `waitForDrawable`. https://developer.apple.com/documentation/metal/mtl4commandqueue.md
- [verified] `MTL4Compiler` is macOS 26+ (search snippet; the Apple page was not fetched).
- [search] WWDC25 session 205 presents Metal 4 for M1 and later, and A14 and later. https://nonstrict.eu/wwdcindex/wwdc2025/205/
- [search] Shader compilation is quicker with more options (WWDC25 session summary). Not measured.
- Not verified: `MTL4ArgumentTable`, unspecialized pipeline states, and any measured CPU win for many small draws. These came from general knowledge and I did not confirm them on a page.
- [estimate] Metal 4 is a larger API change than Metal 3. A CPU win is plausible for draw-call-heavy chunk rendering, but I have no number. Keep the Metal 3 path.

**Implication for Cadence:** Keep Metal 3 as the shipping path. Prototype a Metal 4 queue behind a flag, then measure CPU time per frame on a chunk-heavy scene on the 8 GB machine before committing.

### B3. MTLResidencySet (macOS 15+)
- [verified] Residency sets make allocations resident with "minimal CPU overhead", and attach to a queue or command buffer with one call. They do not track hazards, so you need fences or events. Residency sets are not thread-safe, and do not support sparse heaps or sparse textures. A heap added to a set makes the whole heap resident. https://developer.apple.com/documentation/metal/mtlresidencyset.md
- [verified] The Apple forum answer on `requestResidency()` says to call it "at any noncritical moment", for example at launch or a state change, before the first command buffer that uses the set is committed. It does not say to rebuild the set each frame. https://developer.apple.com/forums/thread/813140 (one reply, documentation excerpt)
- [verified] `recommendedMaxWorkingSetSize` is "an approximation of how much memory... this GPU device can allocate without affecting its runtime performance". Keep the total resource and heap footprint under it. https://developer.apple.com/documentation/metal/mtldevice/recommendedmaxworkingsetsize.md
- [estimate] On an 8 GB machine this value will be well under 8 GB. I did not retrieve the number on the test Mac; read it at runtime.

**Implication for Cadence:** Put stable resources (atlases, static chunk buffers, shader pipelines) in one long-lived residency set. Do not rebuild the set each frame. Read `recommendedMaxWorkingSetSize` at startup and feed it to the memory governor.

### B4. Memoryless render targets and purgeable state
- [verified] Memoryless textures are only for temporary render-pass data, and load or store actions cannot touch them. Good candidates: depth and multisample targets used only within a pass. https://developer.apple.com/documentation/metal/choosing-a-resource-storage-mode-for-apple-gpus.md
- Not verified: purgeable state (`setPurgeableState`, volatile / nonVolatile / empty). The Apple purgeable-memory page returned 404.
- [estimate] Mark caches (mip chains for disposable textures, shader intermediate buffers) volatile so the OS can reclaim them under pressure, and mark them nonVolatile when they are needed.

**Implication for Cadence:** Use memoryless for any depth or MSAA attachment that never leaves a pass. This saves real VRAM on an 8 GB machine. Verify the purgeable API on a page before writing code.

### B5. P-core vs E-core scheduling and QoS
- [search] Threads at background QoS are restricted to E-cores. Higher user QoS is preferred on P-cores and can spill to E-cores when P-cores are busy. Promotion is one-way. https://eclecticlight.co/2026/02/08/last-week-on-my-mac-why-e-cores-make-apple-silicon-fast/ and https://eclecticlight.co/2024/12/05/inside-m4-chips-cpu-core-management/
- [search] On M4 Pro and Max, the scheduler selects a P-cluster by load and uses E-cores only when both P-clusters are fully active. Same Eclecticlight article.
- [search] Apple forums say thread-to-core affinity is not selectable on macOS; QoS is the main lever. https://developer.apple.com/forums/thread/674456
- [search] One third-party blog gives the M5 base chip as 4 P-cores and 6 E-cores. Low confidence.
- Not found: M5 Pro core layout, "super" cores, and any game-specific scheduling guidance.

**Implication for Cadence:** Run the render thread and game-tick thread at `userInteractive` or `userInitiated` QoS. Do not put render work on background QoS. Do not expect an affinity hint; test whether the render thread stays on P-cores under load.

---

## C. Memory on 8 GB Macs

### C1. JVM (JDK 25) flags
- [verified] Compact object headers became a product feature in JDK 25 (JEP 519). Flag: `-XX:+UseCompactObjectHeaders`. It is not the default. The JEP says "It is not a goal to make compact object headers be the default object-header layout." https://openjdk.org/jeps/519
- [verified] Reported savings (SPECjbb2015 setting): heap use down 22%, CPU time down 8%. Those are benchmark numbers, not game numbers. The JEP does not list supported platforms. Verify on arm64 macOS.
- [verified] JEP 534 ("Compact Object Headers by Default") is linked from JEP 519. I did not check its status.
- [verified] AOT cache (JEP 483, JDK 24): stores loaded and linked classes. The page reports about 42% startup gains on two server examples and cache sizes of 11.4 MB and 130 MB. It says nothing about runtime memory and is aimed at server apps.
- [verified] JEP 514 (JDK 25): `-XX:AOTCacheOutput=<file>` does the training and cache creation in one command. Creation needs about double the heap of the training run (for example `-Xmx4g` needs about 8 GB). It does not reduce memory. https://openjdk.org/jeps/514
- Not found: JEP 515.
- [verified] `G1PeriodicGCInterval` (JEP 346) starts a periodic GC when the app looks idle, which returns unused heap to the OS. A value of zero disables it. The periodic GC is cancelled if host load is above a threshold. https://openjdk.org/jeps/346
- [search] JDK-8357445 (time-based idle uncommit, `G1UseTimeBasedHeapSizing`) was withdrawn upstream. A Microsoft Build of OpenJDK page says the flag exists and is on by default. The two conflict, and I could not resolve it. Check with `java -XX:+PrintFlagsFinal -version | grep -i uncommit` on the bundled JDK. https://mail.openjdk.org/pipermail/hotspot-dev/2025-July/108484.html
- [estimate] Metaspace, code-cache, and `TieredStopAtLevel` settings: no verified numbers. Keep the defaults until measured.
- Not verified: `-XX:+UseStringDeduplication` benefit for a client game.

**Implication for Cadence:** Benchmark `-XX:+UseCompactObjectHeaders` on the bundled arm64 JDK 25 and record heap and fps. Use `G1PeriodicGCInterval` (for example 10-30 s, to be measured) only after confirming the flag works on the bundled JDK. Treat the AOT cache as a startup feature only; its creation doubles heap, so do it in a separate step, not on the 8 GB machine during play.

### C2. macOS memory accounting
- [search] `phys_footprint` counts compressed and swapped-out anonymous pages. RSS does not shrink when the OS compresses idle pages, so RSS understates the real pressure. Secondary source (a Rust crate's comments). https://docs.rs/crate/basemind/0.23.1/source/src/sysres.rs
- [search] `footprint <pid>` shows the headline footprint and `phys_footprint` / `phys_footprint_peak`, with Dirty, Clean, and Reclaimable columns. https://www.unixtutorial.org/commands/footprint/
- [search] Activity Monitor's figure is not RSS, and RSS can include shared and mapped pages, so summing RSS across processes overstates use. https://github.com/org2AI/ORG2/pull/451
- [estimate] Purgeable and IOSurface memory are attributed to the owning process. Not verified on a page.
- Not verified: `madvise(MADV_FREE_REUSABLE)`. I did not find an Apple page for it.

**Implication for Cadence:** Read `phys_footprint` (task_vm_info, [estimate] for the exact call) in the memory governor, not RSS. Report it next to the JVM heap in the Cadence memory display. Test `footprint <pid>` on the running game while the shader packs load.

### C3. Metal memory accounting
- [verified] Residency sets do not track hazards. Use fences or events. A resource from a heap makes its whole heap resident when added. See B3 above.
- [verified] `recommendedMaxWorkingSetSize` is the performance budget. See B3.
- Not verified: `makeAliasable()` (the Apple page returned 404). [estimate] Heap aliasing lets two passes reuse one allocation if their lifetimes do not overlap, but needs fences and careful lifetime tracking.
- [estimate] Memoryless render targets (B4) are the cheapest VRAM saving.

**Implication for Cadence:** Keep one heap per resource class (chunk meshes, textures, render targets) with aliasable transient targets, and budget against `recommendedMaxWorkingSetSize`. Do not add aliasing until the simpler wins are measured.

---

## D. Minecraft performance-mod UI

### D1. Sodium and Iris settings conventions
- [search] Sodium's video settings explain each option in an in-game tooltip on hover. https://github-wiki-see.page/m/pajicadvance/sodium-fabric/wiki/Sodium-video-settings
- [search] Sodium labels each option with a performance impact (Low, Medium, High). The Sodium developer docs show the declared impact (`OptionBuilder.setImpact(Impact)`, with values such as low, high, or "varies"). https://support.lunarclient.com/solutions/how-to-fix-fps-issues-in-lunar-client and https://docsearch.algolia.com/mcp/docs/repo/caffeinemc/sodium (search results, not opened)
- [search] Sodium Profiles: selecting a preset does not apply it; "Apply" activates it all at once and "Undo" reverts before applying. https://www.curseforge.com/minecraft/mc-mods/sodium-profiles
- [search] PrismPanel reworks Sodium's presentation with a sidebar and animated explanations, and keeps the native impact labels. https://www.modpackindex.com/mod/176343/prismpanel-a-better-ui-for-sodium
- Not found: Embeddium and Reese's Sodium Options tooltip or impact-label design, and usability complaints about option count (I did not find sources).

**Implication for Cadence:** Use per-option Low / Medium / High impact labels with a one-line tooltip. Use a preset + Apply + Undo flow for the Cadence quality tiers so the user can see what changes before committing.

### D2. Recommended / reset / showing FPS effect
- [search] The Sodium Profiles Apply and Undo pattern (above) is the closest convention I found.
- Not found: a common convention for "Recommended" or "Reset to defaults" buttons, or for showing measured fps change per setting.

**Implication for Cadence:** Show the impact label as a static estimate, and show a measured delta only when the in-game benchmark (already in Cadence) has run. Do not claim a fixed fps gain without a measurement.

---

## E. Techniques from other Minecraft renderers (optional)

- [search] Distant Horizons is an LOD mod with about 13 million CurseForge downloads, drawing far terrain as simplified models. https://sportskeeda.com/minecraft/great-minecraft-lod-mods-2025 (secondary)
- [search] Voxy is a Fabric LOD mod that builds distant terrain as the player explores, with pre-generated chunks imported via Chunky. It has no server-side support and pairs best with Sodium and Nvidium. https://modrinth.com/mod/wynnvista (search result; Voxy details from search snippets)
- Not found: vertex compression, GPU-driven culling, region batching, or async upload specifics for any of these mods.
- [estimate] Ideas that map well to Metal: indirect draw and mesh shaders for LOD batches on M3+; compressed vertex formats (half-float positions, packed normals) for chunk meshes; occlusion culling on the GPU with a previous-frame depth buffer. None are verified for these mods.

**Implication for Cadence:** Study the Voxy and Nvidium repositories directly for their draw-batching design before picking a Metal approach. Do not copy numbers from this research.

---

## Top 10 actionable ideas (ranked by value / effort)

1. Enter native full screen (`toggleFullScreen:`) for the game window so Game Mode can turn on, and show its state in Cadence. Value high, effort low. Verify on a Java-launched window.
2. On backing-scale or screen change, recompute the drawable (bounds x backing scale) once, on the render thread, and re-read the refresh cap. Value high, effort low-medium.
3. Move frame pacing to `CAMetalDisplayLink` (macOS 14+) with range 60...120, preferred 120, plus a `CVDisplayLink` fallback. Value high, effort medium.
4. Observe `NSProcessInfoPowerStateDidChange` so Low Power Mode changes apply immediately. Value medium, effort low.
5. Startup guard: require arm64 JVM (`os.arch`) and `sysctl.proc_translated == 0` before loading the Metal library; show a clear message otherwise. Value high (prevents a confusing crash), effort low.
6. Use `phys_footprint` (not RSS) in the memory governor and display it next to the JVM heap. Value medium-high on 8 GB, effort low-medium.
7. Benchmark `-XX:+UseCompactObjectHeaders` on the bundled arm64 JDK 25, and test `G1PeriodicGCInterval` after confirming the flag exists on that JDK. Value medium-high, effort low.
8. Put stable resources (atlases, static chunk buffers, pipelines) in one long-lived `MTLResidencySet` (macOS 15+), never rebuilt per frame; read `recommendedMaxWorkingSetSize` at startup. Value medium, effort medium.
9. Use memoryless storage for depth and MSAA attachments that never leave a render pass, gated by `supportsFamily`. Value medium on 8 GB, effort low.
10. Settings UI: a Low / Medium / High impact label per option with a one-line tooltip, and a preset + Apply + Undo flow for quality tiers (borrowed from Sodium and Sodium Profiles). Value medium, effort low.

Also worth a prototype, not in the top 10: a Metal 4 command queue behind a flag, measured on CPU time per frame for chunk-heavy scenes (B2).

---

## Gaps

Not researched (no search run):
- Sleep/wake, lid closed with an external display, and display disconnect while in fullscreen (A3).
- Stage Manager, Split View, notch safe-area behavior for a Metal view (A9).
- Scaled-resolution rendering cost and recommended drawable sizes (A6).
- High Power Mode on MacBook Pro (A5).
- `LSSupportsGameMode`, and whether a launcher-started Java app gets Game Mode (A4).
- Rosetta slowdown magnitude (A7).
- Apple GPU family feature lists per generation: mesh shaders, ray tracing, sparse tiers, 64-bit atomics, BC support, lossless texture compression (B1).
- JVM metaspace, code-cache, `TieredStopAtLevel`, and `UseStringDeduplication` settings for a client game (C1).

Searched but not confirmed:
- `isRemovable` value on Apple silicon built-in GPUs (A3).
- Whether time-based G1 idle uncommit ships in JDK 25; the upstream change was withdrawn and a vendor build says it is on (C1).
- Metal 4 `MTL4ArgumentTable`, unspecialized pipelines, and any measured CPU win (B2).
- Dynamic Caching occupancy numbers (B1).
- M5 Pro core layout and "super" cores (B5).
- Purgeable state, `madvise(MADV_FREE_REUSABLE)`, and `makeAliasable()`. Apple pages returned 404 or titles only (B4, C2, C3).
- JEP 515 (not found on openjdk.org); JEP 534 status (C1).

Could not verify on fetched pages:
- `CAMetalDisplayLink` behavior on screen change and in fullscreen vs windowed (A1, A2). The Apple page does not say.
- `nextDrawable` stall after `drawableSize` changes (A1). Search snippet only.
- Embeddium and Reese's Sodium Options UI design, and any usability complaints about option count (D1).
- Voxy and Nvidium rendering internals (E).
