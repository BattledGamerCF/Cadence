# Cadence Logic: game-logic optimizations

Cadence Logic is Cadence's own replacement for what [Lithium](https://github.com/CaffeineMC/lithium) does: it makes
the game's simulation (mobs, entities, block entities) cheaper without changing what happens. Lithium is LGPL-3.0, so
none of its code is used; each optimization here was designed from Minecraft 26.3's own bytecode and Lithium's public
descriptions of *what* it optimizes.

Why it matters on a Mac: in singleplayer the game logic runs on an integrated server thread inside the same app as the
renderer. On a MacBook Air both share one power and heat budget, so less logic work leaves more headroom for frame rate,
and less garbage means fewer collection pauses on 8 GB machines.

## Rules

- **Exact.** Every optimization gives vanilla's results, in vanilla's order. Nothing that changes game behaviour is
  included (Lithium's own "non-vanilla" options are not replicated).
- **Switchable.** `cadence.logic=true` turns the set on (the performance profiles set it; `profile=none` is pure vanilla).
  `cadence.logic.<name>=false` in `config/cadence.properties`, or `-Dcadence.logic.<name>=false`, turns one off.
- **Lithium wins if installed.** Each optimization steps aside when Lithium is loaded.
- **Verified without the game.** `tools/mixin-check.sh` checks every hook against the Minecraft jar; the pure-logic parts
  are fuzz-tested against Minecraft's own classes (`tools/logic-tests/run.sh`).

## The optimizations (beta 1)

| Name | What it speeds up | How | Verified |
|---|---|---|---|
| `entitySections` | Every entity search by area: mobs pushing each other (every mob, every tick), hoppers looking for items, item merging and pickup, AI sensors, entity collisions | Vanilla walks, for each 16-block column the box touches, every entity section in that column of the loaded world, allocating a sorted-set view and an iterator each time. Cadence looks up only the sections inside the box, in the same order vanilla visits them (keys sort by their bit pattern: non-negative coordinates first, then negative). Very large boxes use vanilla's walk. | 900,000 random boxes: identical sections and order (both paths). Mob-sized search **1,365 ns → 61 ns (22×)** |
| `brain` | Every brain-driven mob every tick: villagers, piglins, hoglins, axolotls, goats, frogs, allays, camels, sniffers, wardens | Vanilla walks the behaviour table (sorted map → hash map → set) twice per tick and builds a new list of running behaviours, allocating iterators each time. Cadence keeps a flat copy in the same order, rebuilt only when the table changes (two methods change it). | By construction; hooks checked |
| `villagerSensor` | Villagers' secondary work-site scan (every 40 ticks each) | Vanilla reads 405 blocks around each villager, allocating a position for each, looking for its profession's secondary work blocks. Every profession but the farmer has none, so nothing can match: Cadence erases the memory directly, as vanilla ends up doing. Farmers keep the scan, with one reused position. | By construction; hooks checked |
| `blockEntityTick` | Block-entity ticking when many are removed at once (chunks unloading as you fly past a big base) | Vanilla removes each removed ticker from an ArrayList one by one, shifting the whole list each time (quadratic). Cadence makes one compacting pass. | 200,000 random ticker lists: identical ticks, order and resulting list |

## Considered and not done (yet)

- **Quarter-size sine table** (fits Apple Silicon's L1 cache): bit-identical, but measured 4-11× *slower* than
  Mojang's 256 KB table, which the M-series L2 serves at ~1 ns. Dropped.
- **Skipping air in block collisions**: the per-block collision call is a cached lookup; the earlier measurement of
  block-collision speed-ups found no gain. Dropped.
- **Pathfinding streams**: once per path search, small against the search itself.
- **Measured in game (2026-10-15 night) before building the rest.** Busy test scene (about 320 mobs, items, chests,
  campfires), Lithium removed, 45 s JFR profile of the integrated server thread: the server thread is only about 12-15%
  busy, against about 78% for the render thread. Inside it: natural spawning bookkeeping (`NaturalSpawner.createState`,
  local mob caps, environment attributes) under 2%, chunk ticket removal about 2%, block-entity ticking barely visible,
  pathfinding's node-type lookups about 3%. Each is well under 1% of the game's CPU, so none can change the frame rate,
  and block-entity "sleeping" would risk redstone and hopper timing for nothing measurable: they are not built. What
  remains on the server thread is inherent work: block reads for collisions, pathfinding and random ticks (an earlier
  exact collision fast path measured no gain), and checking crowded mobs against each other.
