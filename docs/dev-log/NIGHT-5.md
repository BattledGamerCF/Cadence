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
