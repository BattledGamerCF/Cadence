# Tools

| Script | What it does |
|---|---|
| `release` | Builds the mods and packages `dist/cadence-<version>.jar` and `.zip` |
| `bundle.py` | Nests the HUD and shader mods into the main jar (used by `release`) |
| `cadence-test.sh` | Runs the development client through a list of test steps and saves screenshots and logs (see CONTRIBUTING.md) |
| `cadence-guest.sh` | A second dev client that joins the first one's LAN world (multiplayer tests) |
| `cadence-guard.sh` | Memory guard used by `cadence-test.sh`: stops the test game if the Mac runs short of memory |
| `HeapPaths.java` | What keeps objects alive: the reference chains from GC roots to each instance of a class in a heap dump (`java tools/HeapPaths.java run/heap-*.hprof CLASS`; the self-test's `heapdump` step writes one) |
| `mixin-check.sh`, `MixinCheck.java` | Checks every compiled mixin against the Minecraft jar without starting the game |
| `offline-checks/run.sh` | Offline regression checks: the vanilla RGSS patch, shader profile tolerance, per-pack settings files and the compatibility layers' mixin reading |
| `compat-test-mod/build.sh` | Builds a tiny test mod that hooks code Cadence replaces (for the compatibility layers) |
| `logic-tests/run.sh` | Offline fuzz tests and a benchmark of the game-logic optimizations |
| `pack-census.sh` | Lists what each shader pack needs that Cadence doesn't support yet, without starting the game |
| `native-check.sh`, `msl-check.m` | Compile-check Metal shaders (Cadence's native programs, or any .metal file) through the Metal API, no Xcode needed |
| `profile/` | Python helpers for profiles and graphics: JFR hot methods, the benchmark and comparison graphics |
| `cadence_native_test.m`, `icb_bench.m`, `mfx_bench.m` | Small standalone Metal tests and benchmarks |
| `record_bench.m` | Times Render Max FPS's GPU kernel (`record.metal`) on a world dumped from the game, variant against variant (docs/RENDER-MAX-FPS.md) |
| `gpu_frame_floor.m` | The GPU's fixed cost per frame on this Mac: command buffers and render passes a second, no Minecraft |
| `metal4_interp_probe.m`, `metal4_interop_probe.m` | Metal 4 probes: MetalFX frame interpolation and submit cost; driving Metal 3 objects through Metal 4 |
