# Night 3 (2026-10-16): fixing the known issues, then optimization and bug passes

The list left at the end of night 2: the pause when a pack is switched or its settings change, the own renderer's 64 MB
arena after a pack, the compile race at a switch, and multiplayer (untested). Same rules: measured in game, pictures
checked, a change that doesn't clearly help is left out.

## Part 1: the known issues

### The pause at a pack switch or settings change

Where the time went (the stall tracer, -Dcadence.stallTrace=150, and a count of pipeline compiles):

- a switch with warm caches: 450-570 ms on the render thread, made of the pack read (130-160 ms), the programs built from
  the translation cache (240-280 ms) and the pack's images decoded (PNG) in the first frame;
- a settings change the caches hadn't seen: 1.7-2.1 s, almost all of it Metal compiling ~30 pipelines one after another
  (`Native.libraryNewMode`), plus some geometry programs translated on the render thread.

What changed:

1. **The pack is prepared off the render thread** (ShaderSwitcher): the pack read, its checks, the block material map, the
   programs (the existing warm-up) and now its images too (PackTextures.decode) happen on a worker thread while the old
   picture keeps drawing; the pack is installed between two frames once that's done. A newer switch asked for meanwhile
   wins (the older one is closed unseen). A pack that fails to load still leaves shaders off with the reason in chat, and the
   terrain is untouched (tested with a broken test pack).
2. **Pipelines compile in parallel** (ShaderRuntime.prewarmPipelines): every screen program, and every game pipeline packs
   have drawn with (with its hand/shadow/block-entity flags), is compiled on up to 4 threads before the first frame needs
   them. The game pipelines are remembered for the session and across launches (`cadence-cache/pack-pipelines.txt`,
   PipelineMemory), and the warm-up thread translates them for the new pack ahead of time.
3. Cadence's terrain pipeline objects (TerrainShaderGeometry) are no longer remade at every pack install, so pipelines
   compiled for them can be found again.

| Case (M2 Air, BSL / Complementary) | Before | After |
|---|---|---|
| Switch a pack on (caches warm) | 450-570 ms pause | **no frame over 150 ms**; 2-33 ms of render-thread work |
| Settings change never compiled before | 1.7-2.1 s pause | **0.3-0.4 s** |
| First pack of a launch, settings never compiled before | ~2 s | **0.74 s** (22 geometry pipelines: 2.05 s of compiling done in 0.74 s) |

The switch itself now takes 0.2-0.9 s of background time before the new pack appears (more when two games share the Mac).
Pictures compared after every switch: identical to before.

### The 64 MB arena after a pack

Measured instead of assumed: a 64 MB shared Metal buffer that nothing has written adds 0.03 MB to the process footprint
(a small Swift test: 3.45 MB before, 3.48 MB after allocating, 7.5 MB after touching 4 MB, 67.5 MB after touching all).
When a pack turns on, the arena is already swapped for a fresh buffer (renewArena), so the "64 MB" is address space, not
memory. Left as it is.

### The compile race at a switch

Real, and slightly worse than the note said: a section a worker had started meshing for Cadence's own terrain just before a
pack came on finished while the pack drew; its mesh stayed queued, was shown in its slot when the pack went off (old
terrain until that section's rebuild), and its arena space was never freed (the game's release of it came while the own
renderer was stepped aside). Now the own renderer drops those leftovers when a pack goes off (OwnTerrain.dropStale).

Found while testing it: every switch first turns the pack off, so an off -> pack switch dropped the own renderer's live
meshes too (harmless when the pack loaded, but a pack that failed to load would have left the terrain empty until the
next rebuild). packActive now acts only on a real change.

### Multiplayer

- Code audit: every place that reaches for the integrated server (EdgeCap, LOD, LodYield, latency probe, busy scene)
  handles a remote server (no local server) already.
- Live: a second dev client (tools/cadence-guest.sh, its own folder, 854x480, 900 MB heap) joined the first one's world
  opened to LAN (new self-test step `lan=PORT`; `players` lists who each client sees). Both saw both players; the guest
  switched BSL and Complementary on and off while connected, with correct pictures and no errors; the host switched BSL on
  and off with the guest in the world; leaving and the host quitting were clean.
- Not tested: a dedicated server (running one needs its EULA accepted, which is the player's call).
