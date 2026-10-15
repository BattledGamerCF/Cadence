# Lithium: what it does (research for Cadence's clean-room replacement)

Gathered 2026-10-15 by an AI research agent from Lithium's public pages (no code read or copied). **Lithium is
LGPL-3.0-only**: Cadence re-implements ideas from these descriptions only and never copies or translates its code.
Sources: https://github.com/CaffeineMC/lithium, its `lithium-fabric-mixin-config.md`, https://modrinth.com/mod/lithium.

Summary of its option groups (all on by default except `compat.worldedit`, `debug`, `experimental`):

- **ai**: faster path-node type lookup and chunk cache in pathfinding; faster POI searches; brain sensors/tasks with loops
  instead of streams; tracking of task/memory changes to skip prerequisite checks; useless sensors and behaviours removed.
- **alloc**: fewer allocations (random ticking positions, Enum#values() copies, explosion lambdas, NBT maps, entity trackers).
- **block**: hopper caching/notifications/sleeping; fluid flow; redstone wire without duplicate block reads; moving-block
  shape reuse; fluid states store "is empty".
- **chunk**: optimized hash palette, no debug locking/validation on block access, faster palette compaction on save,
  entity class groups.
- **client_tick**: skip server-only work on the client (ambient sounds, brains, water supply, biome particle lookups).
- **collections**: fastutil maps for attributes, brains, POI types, tickets, entity-by-type, mob spawning, block entity tickers.
- **entity**: collision fast paths (block effects, intersections, supporting block first, cramming lists), equipment
  change tracking, elytra/powder-snow fast checks, fast entity retrieval, framed maps, inactive navigations,
  projectile-vs-projectile skip, sprint particle skip.
- **math**: fast BlockPos, inlined Direction/Axis/AABB helpers, smaller sine table.
- **shapes**: specialized cuboid/empty VoxelShapes, faster intersection, precomputed arrays, shape merging, block-state
  shape caches.
- **world**: block-entity sleeping (furnaces, hoppers, brewing stands, campfires, shulkers, crafters, sculk), faster chunk
  BE ticking checks, chunk ticking (precipitation, random ticks, ice), combined heightmap updates, explosion raycasts,
  game-event dispatch only where listeners exist, inlined block access/height, raycast, tick scheduler with packed ticks.
- **minimal_nonvanilla / experimental**: behaviour-changing or risky; Cadence does not replicate these.

No per-option benchmarks are published; Lithium names physics, mob AI and block ticking as the main wins.
