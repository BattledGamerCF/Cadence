# Night 5 (10-20 → 10-21): UI, edge cases + chips, optimization, file layout, memory, bugs

The user asked for an all-nighter in this order. Every change is gated or exact, and it gets measured or checked in
game before it stays. One Haiku 5.5 research agent: docs/research/NIGHT5.md.

## Plan

1. **UI experience**
   - Walk every Cadence screen in game (self-test `screen=`, `hover=`, screenshots): Video Settings, Sharpness & Speed,
     Cadence Settings, Shader Packs, Pack Settings, Benchmark, Quick Setup, Mod Menu entry.
   - Look for: wording a normal player can't follow, rows that do nothing in the current state (greyed with a reason),
     inconsistent labels (On/Off vs names), tooltips that are too long, buttons that lose state, GUI scale 1-4 and Retina
     off layouts, narrow windows.
   - Group the Performance rows so the panel reads top to bottom: picture, speed, power/heat, tools.
   - Test the Sharpness & Speed page (built last night, compile-checked only).
2. **Edge cases + chip architectures** (from the research)
   - Per-chip: GPU family (Apple7 M1, Apple8 M2, Apple9 M3/M4 Dynamic Caching, Apple10 M5), core layouts (M5 Pro/Max
     have no efficiency cores), memory tiers (8 GB vs 16+), fanless vs fans, ProMotion 120 Hz / external displays.
   - Edge cases: display change mid-game (laptop lid, external monitor with another refresh rate), sleep/wake,
     Low Power Mode, Game Mode, Rosetta Java, Intel Macs (clear message), macOS versions, very small windows,
     non-integer display scaling, out of disk space for caches, read-only config.
3. **Deep optimization pass**: profile the crowded and open scenes (JFR + frame log), with and without a pack; try
   anything plausible; keep only measured wins.
4. **File structure**: make the tree readable (dev.cadence.shaders.cadence is 38 flat files plus sub-packages;
   leftover agent worktree; tools/ and docs/ index).
5. **Deep memory pass**: heap histogram after a soak, native (NMT at exit, footprint, vmmap), Metal (logTextures,
   logBuffers), per-chip memory tiers.
6. **Deep bug pass**: my own read-through of tonight's changes and older risky paths, then the in-game sweep.

## Log

### 1. UI (done)
Tours with screenshots (self-test screen=/click=/hover=/scroll=): regrouped Video Settings (Picture & Speed / Power & Heat /
Extras, bold-underlined like vanilla), Shadows + Faster Shadows on the Sharpness & Speed page, Benchmark details on the card,
greyed no-op buttons, shorter tooltips. Bugs found by looking: cycling the preset through Max FPS left Retina off for good; a
status line added while the screen was open overflowed the panel (rebuildWidgets on an OptionsSubScreen duplicates the layout:
open a fresh VideoSettingsScreen, carry the scroll); the benchmark card claimed a cap that only applied part of the run.

### 2. Edge cases and chips (done)
Research: docs/research/NIGHT5.md. Already covered: Rosetta/Intel (PlatformCheck), Low Power Mode (Cooling treats it as warm),
core tiers incl. M5 Pro/Max and M6 (CoreLayout), GPU features (SIMD-group functions and imageblocks are Apple7/M1-safe).
Added: display rate and scale from the game window's own screen (mainScreen is the ACTIVE app's screen; AppKit read on the main
thread, other threads get a cache and queue one refresh on the main queue); Retina-off GUI halving only on >=1.5x screens (on a
1x monitor it halved every menu for nothing); On Battery no longer calls native displayHz every frame; settings files written
atomically (temp + move; saveKey synchronized); A-series chips in Quick Setup; -Dcadence.retina respected by the restart note.

### 3. GC: Cadence's GC policy (user asked for "our own GC" if possible)
A real new collector means changing HotSpot (C++) and shipping our own Java; a mod can't. What a mod can do is decide when G1
works. Measured in the benchmark turn (JFR + frame log): 11 of 20 hitches were GC; the rest were the OS stalling the render
thread (CPU ~0.3 ms of a 40 ms frame). G1's adaptive IHOP had fallen to 0 bytes (young gen ~280 MB of a 760 MB heap) so it
marked back to back, each cycle ending in a Remark whose class unloading ("G1 Complete Cleaning") took 8-19 ms, every ~8 s,
while old gen stayed flat. GcPolicy + JvmDefaults: -XX:-G1UseAdaptiveIHOP -XX:InitiatingHeapOccupancyPercent=90 (safety net),
-XX:+ExplicitGCInvokesConcurrent (replaces DisableExplicitGC; Minecraft only calls System.gc() on out-of-memory), and Cadence
starts marking when old gen grew 5% of -Xmx (48-128 MB; half with a menu open, a quarter under memory pressure), 30 s apart.
The JVM still cleans the code cache itself (SweeperThreshold 15% -> "CodeCache GC Threshold" cycles), so JIT code stays bounded.
- benchmark scene, 70 s x2 interleaved: Remarks 14/10/7 (max 19-26 ms) -> 0; worst frames 38-54 ms -> 17-41 ms.
- 4-min flight over new terrain, -Xmx2048 (HeapTrim on): Remarks 126 (951 ms) -> 16 (196 ms); young pause time 1765 -> 1352 ms;
  heap committed max 754 -> 770 MB.
Young pauses are almost all ObjCopy (survivors: new chunk data copied survivor-to-survivor up to 15 times).

### 4. Text (optimization)
FormattedBidiReorder (ICU Bidi + ArabicShaping on every drawn string, ~8% of render-thread allocation in the benchmark) skipped
for left-to-right text below U+0590: one even run, shaping a no-op. -Dcadence.cpu.textVerify: 500,000+ strings, 0 mismatches.
- Third flight soak pair: MaxTenuringThreshold=3 on top of the policy WORSE (young 1778 vs 1424 ms, Remarks 21 vs 15): dropped.
- The longest Remarks while generating terrain (40-78 ms, a hot Mac) are ~98% class unloading ("G1 Complete Cleaning" over
  every class and compiled method): fewer of them is what a mod can do; making each cheaper is HotSpot work (custom JDK).
- The other benchmark hitches: the render thread in cmdWait (GPU) while the GPU took 20-40 ms for a 0.7 ms frame, no uploads
  or copies of ours in those frames: other apps/WindowServer on the GPU. Non-GC safepoints: none over 3 ms.
- GUI glyphs: one reused identity Matrix4f per render thread instead of a new one per glyph per frame (TextGlyphMatrixMixin).
  TextureSetup per glyph NOT cached: the record holds a random sort value per instance (Math.random), batching might change.

### 5. File layout
dev.cadence.shaders.cadence held 38 unrelated classes flat; now by area: ui/ (settings screens, ShaderToggle = shader
settings + config file), render/, shaderpack/, shadow/, memory/, selftest/ (scripted move: git mv, package lines, qualified
names, imports; package-private members the new packages need made public by a compiler-driven fixer). package-info.java in
every Cadence package. docs/ARCHITECTURE.md maps all three modules. Doc indexes and tools/README brought up to date; the
merged agent worktree under .claude/ removed (its branch kept). The metal root package (the mcopt backend, 58 classes) stays one
package: its classes share package-private state with Native. Checks: build, mixin-check (494 members, 0 errors), offline
checks, in-game smoke test (21 steps: settings, Sharpness page, BSL on/off, pack settings, dynamic lights, benchmark, F3+A).

### 6. Memory
Census, render distance 12, no pack, 2 GB heap (footprint, vmmap, NMT at exit, malloc_history -callTree, histogram):
footprint 1,413 MB (1,695 on 10-18 at a bigger window). Java ~925 (heap committed 525, live ~450; metaspace 125 + class 46;
JIT code 97; GC 61; symbols 26); native malloc ~156 (the JVM 118 of it: symbol table 26, compiled-code metadata ~37, G1 16;
zlib inflater windows 14); GPU ~210 (IOGPU 90 + owned 98 + IOSurface 24). Heap: byte[] 82 MB (light layers), long[] 47 (block
palettes), int[] 24, 205k ScheduledTicks (this world's water), BakedQuad 9.5, ModelPart$Vertex 7.
- Kept: the own renderer's cull lists start at 4,096 records (was 32,768, also the floor after a world closes); beginFrame
  grows them before every cull. At RD 12 they grow back to 32,768 within 2 s (16-32k live records: 64 lists x 2 sets x phases
  A/B sized for every record = 32 MB), so the saving is at small render distances. GPU capacity counters: 0 lost.
- Measured, not changed: pooling the cull lists (one pool, offsets by prefix sum) would cut most of those 32 MB but changes every
  list kernel; the JVM's own memory (symbols, nmethod metadata, class unloading's cost) is for the custom JDK.
