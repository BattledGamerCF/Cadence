# Cadence Logic: game-logic optimizations

Cadence Logic is Cadence's own replacement for [Lithium](https://github.com/CaffeineMC/lithium). It makes the game's
simulation (mobs, entities, block entities) cheaper without changing what happens. Lithium is LGPL-3.0, so none of its
code is used. Each optimization here was designed from Minecraft 26.3's own bytecode and from Lithium's public
descriptions of *what* it optimizes.

In singleplayer, the game logic runs on an integrated server thread inside the same app as the renderer. On a MacBook Air
both share one power and heat budget. Less logic work leaves more headroom for frame rate, and less garbage means fewer
collection pauses on 8 GB machines.

## Rules

- **Exact.** Every optimization gives vanilla's results, in vanilla's order. Nothing that changes game behaviour is
  included. Lithium's own "non-vanilla" options are not replicated.
- **Switchable.** `cadence.logic=true` turns the set on. The performance profiles set it, and `profile=none` is pure
  vanilla. `cadence.logic.<name>=false` in `config/cadence.properties`, or `-Dcadence.logic.<name>=false`, turns one
  optimization off.
- **Lithium takes precedence.** Each optimization steps aside when Lithium is loaded.
- **Verified without the game.** `tools/mixin-check.sh` checks every hook against the Minecraft jar. The pure-logic parts
  are fuzz-tested against Minecraft's own classes (`tools/logic-tests/run.sh`).

## The optimizations (beta 1)

| Name | What it speeds up | How | Verified |
|---|---|---|---|
| `entitySections` | Every entity search by area: mobs pushing each other (every mob, every tick), hoppers looking for items, item merging and pickup, AI sensors, entity collisions. | For each 16-block column the box touches, vanilla walks every entity section in that column of the loaded world. Each walk allocates a sorted-set view and an iterator. Cadence looks up only the sections inside the box, in the same order vanilla visits them. Keys sort by their bit pattern: non-negative coordinates first, then negative. Very large boxes use vanilla's walk. | 900,000 random boxes: identical sections and order on both paths. Mob-sized search 1,365 ns → 61 ns (22×). |
| `brain` | Every brain-driven mob, every tick: villagers, piglins, hoglins, axolotls, goats, frogs, allays, camels, sniffers, wardens. | Each tick, vanilla walks the behaviour table (sorted map → hash map → set) twice and builds a new list of running behaviours, allocating iterators each time. Cadence keeps a flat copy in the same order. It rebuilds the copy only when the table changes, which two methods can do. | By construction; hooks checked. |
| `villagerSensor` | Each villager's secondary work-site scan, which runs every 40 ticks. | Vanilla reads 405 blocks around each villager and allocates a position for each, looking for the profession's secondary work blocks. Every profession except the farmer has none, so nothing can match. Cadence erases the memory directly, as vanilla ends up doing. Farmers keep the scan, with one reused position. | By construction; hooks checked. |
| `blockEntityTick` | Block-entity ticking when many are removed at once, for example chunks unloading as you fly past a big base. | Vanilla removes each removed ticker from an ArrayList one at a time and shifts the whole list each time (quadratic). Cadence makes one compacting pass. | 200,000 random ticker lists: identical ticks, order and resulting list. |
| `skySources` | Every chunk generated and every chunk a server sends: where sunlight stops in each of its 256 columns (ChunkSkyLightSources.fillFrom). | Vanilla walks each column down block by block, with one block lookup and one edge test per block. Cadence unpacks each section's 4,096 block ids once and classifies its few distinct blocks once. An edge under a light-dampening block is occluded. An edge between two blocks with no occlusion shape on the facing sides is not. Any other edge uses vanilla's own test. | Self-test step `skysources`: 585 chunks, all 149,760 columns identical. 43-46 µs → 26-33 µs per chunk (1.4-1.6×). |
| `ambientParticles` | The client's animate tick, which runs 20 times a second in singleplayer and on servers: biome particles (ash, spores) around the player. | Vanilla picks 1,334 random spots within 31 blocks. At each spot that isn't a full block (mostly air), it works out the biome and the particle setting's layers. Only a few biomes have particles. Once a tick, Cadence checks the biome lists of the sections in reach. When none has such a biome, every spot gets the setting's value without the biome layer, which the game already keeps per tick. It steps aside if another mod adds its own layer. | `-Dcadence.logic.ambientVerify=true`: 917,504 lookups, all identical. The shortcut stays off near crimson forests and other particle biomes. Animate tick 330-420 µs → 260-290 µs (about 25%). |

## Considered and not done (yet)

- **Quarter-size sine table** (fits Apple Silicon's L1 cache). It is bit-identical, but it measured 4-11× *slower* than
  Mojang's 256 KB table, which the M-series L2 serves at ~1 ns. Dropped.
- **Skipping air in block collisions.** The per-block collision call is a cached lookup, and the earlier measurement of
  block-collision speed-ups found no gain. Dropped.
- **Pathfinding streams.** They run once per path search, which is small against the search itself.
- **Measured in game (2026-10-15 night) before building the rest.**
  - Setup: busy test scene (about 320 mobs, items, chests, campfires), Lithium removed, 45 s JFR profile of the
    integrated server thread.
  - Result: the server thread is only about 12-15% busy, against about 78% for the render thread.
  - Inside the server thread:
    - Natural spawning bookkeeping (`NaturalSpawner.createState`, local mob caps, environment attributes) is under 2%.
    - Chunk ticket removal is about 2%.
    - Block-entity ticking is barely visible.
    - Pathfinding's node-type lookups are about 3%.
  - Conclusion: each is well under 1% of the game's CPU, so none can change the frame rate. Block-entity "sleeping"
    would risk redstone and hopper timing for nothing measurable. These are not built.
  - What remains on the server thread is inherent work: block reads for collisions, pathfinding and random ticks, and
    checking crowded mobs against each other. An earlier exact collision fast path measured no gain.
