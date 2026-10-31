# Tools

| Script | What it does |
|---|---|
| `release` | Builds the mods and packages `dist/cadence-<version>.jar` and `.zip` |
| `bundle.py` | Nests the HUD and shader mods into the main jar (used by `release`) |
| `cadence-test.sh` | Runs the development client through a list of test steps and saves screenshots and logs (see CONTRIBUTING.md) |
| `cadence-guest.sh` | A second dev client that joins the first one's LAN world (multiplayer tests) |
| `bench-round.sh` | Runs a list of benchmark plans, each in its own launch with a cool-down between (docs/BENCHMARKS.md) |
| `cadence-guard.sh` | Memory guard used by `cadence-test.sh`: stops the test game if the Mac runs short of memory |
| `mixin-check.sh`, `MixinCheck.java` | Checks every compiled mixin against the Minecraft jar without starting the game |
| `offline-checks/run.sh` | Offline regression checks: the vanilla RGSS patch, shader profile tolerance, per-pack settings files and the compatibility layers' mixin reading |
| `compat-run.sh` | The release jar with the mod compatibility set (`metal/run-compat`) in a production launch: PASS or FAIL (self-test finished, no mixin errors, benchmark ran), exceptions listed |
| `compat-test-mod/build.sh` | Builds a tiny test mod that hooks code Cadence replaces (for the compatibility layers) |
| `i18n/run.sh` | Lists Cadence's English UI text and checks every translation file against it (CONTRIBUTING.md, Translations) |
| `logic-tests/run.sh` | Offline fuzz tests and a benchmark of the game-logic optimizations |
| `pack-census.sh` | Lists what each shader pack needs that Cadence doesn't support yet, without starting the game |
| `native-check.sh`, `msl-check.m` | Compile-check Metal shaders (Cadence's native programs, or any .metal file) through the Metal API, no Xcode needed |
