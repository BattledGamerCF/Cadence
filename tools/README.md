# Tools

| Script | What it does |
|---|---|
| `release` | Builds the mods and packages `dist/cadence-<version>.jar` and `.zip` |
| `bundle.py` | Nests the HUD and shader mods into the main jar (used by `release`) |
| `cadence-test.sh` | Runs the development client through a list of test steps and saves screenshots and logs (see CONTRIBUTING.md) |
| `cadence-guard.sh` | Memory guard used by `cadence-test.sh`: stops the test game if the Mac runs short of memory |
| `pack-census.sh` | Lists what each shader pack needs that Cadence doesn't support yet, without starting the game |
| `cadence_native_test.m`, `icb_bench.m`, `mfx_bench.m` | Small standalone Metal tests and benchmarks |
