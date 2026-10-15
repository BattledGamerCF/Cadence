# Apple Silicon (M1 to M6) research for Cadence auto-tune

Research date: 2026-10-14. Web search and fetch only. Nothing was downloaded or run.

Tags: [verified] = read on the source page. [search] = search snippet or secondary report. [estimate] = my arithmetic or inference. [gap] = not found.

Source note: Wikipedia tables were fetched through a summarizer and contain internal contradictions, flagged inline. Apple newsroom pages were not readable directly (only titles were, via the archive); figures for M5 Pro/Max and M6 come from syndicated copies of the Apple text [search].

---

## 1. Chip table

### 1a. CPU and GPU

| Chip | CPU (P + E, or super/perf/eff as labelled) | GPU core options | Mem bandwidth (GB/s) | Max RAM | Process | Release | Source |
|---|---|---|---|---|---|---|---|
| M1 | 4P + 4E (8) | 7 or 8 | 68.3 | 16 GB | TSMC N5 | Nov 10, 2020 | [verified] Wikipedia Apple M1 |
| M1 Pro | 6P+2E (8) default, or 8P+2E (10) | 14 or 16 | 204.8 (text says 200) | 32 GB | N5 | Oct 2021 | [verified] Wikipedia |
| M1 Max | 8P+2E (10) | 24 or 32 | 409.6 | 64 GB | N5 | Oct 2021 | [verified] Wikipedia |
| M1 Ultra | 16P+4E (20) | 48 or 64 | 819.2 (text says 800) | 128 GB | N5 | 2022 | [verified] Wikipedia |
| M2 | 4P + 4E (8) | 8, 9 or 10 (10 in most Macs) | 102.4 (text says 100) | 24 GB (8/16/24 options) | TSMC N5P | Jun 24, 2022 | [verified] Wikipedia Apple M2 |
| M2 Pro | 8P+4E (12) unbinned, or 6P+4E (10) binned | 16 or 19 | 204.8 (text ~200) | 32 GB | N5P | Jan 2023 | [verified] Wikipedia |
| M2 Max | 8P+4E (12) | 30 or 38 | 409.6 (text ~400) | 96 GB | N5P | Jan 2023 | [verified] Wikipedia |
| M2 Ultra | 16P+8E (24) | 60 or 76 | 819.2 (text ~800) | 192 GB | N5P | Jun 2023 | [verified] Wikipedia |
| M3 | 4P + 4E (8) | 8, 9 or 10 | 102.4 | 24 GB | TSMC N3B | Oct 30, 2023 | [verified] Wikipedia Apple M3 |
| M3 Pro | 5P+6E (11) or 6P+6E (12) | 14 or 18 | 153.6 (text says 150) | 36 GB | N3B | Nov 2023 | [verified] Wikipedia |
| M3 Max | 10P+4E (14) or 12P+4E (16) | 30 or 40 | 307.2 in table; text says 400 for 16-core chip | 96 GB (table); 128 GiB (AI section) | N3B | Nov 2023 | [verified] table; bandwidth conflict unresolved [gap] |
| M3 Ultra | 20P+8E (28) or 24P+8E (32) | 60 or 80 | 819.3 | 256 GB (one row says 512) | N3B | 2025 Mac Studio | [verified] Wikipedia |
| M4 | 3-4P + 4-6E (8, 9, 10) | 8, 9 or 10 | 120 | 32 GB (Mac min 16 GB; 8/12 GB are iPad-only) | TSMC N3E | May 15, 2024 | [verified] Wikipedia Apple M4 |
| M4 Pro | 8P+4E (12) or 10P+4E (14) | 16 or 20 | 273 (theoretical max) | 64 GB | N3E | Nov 8, 2024 (press release dated Oct 30) | [verified] Wikipedia |
| M4 Max | 10P+4E (14) or 12P+4E (16) | 32 or 40 | 546 (410 on the 32-core binned model) | 128 GB | N3E | Nov 8, 2024 | [verified] Wikipedia |
| M5 | 3-4 super + 6 efficiency (9 or 10 total) | 8 or 10 | 153.6 | 32 GB | TSMC N3P (Wikipedia and MacRumors agree on N3P) | Oct 15, 2025 | [verified] Wikipedia; core labels conflict with M5 Pro/Max labels (see note) |
| M5 Pro | 5-6 super + 10-12 performance (15 or 18 total) | 16 or 20 | up to 307 | 64 GB | N3P [search] | Mar 3, 2026 | [verified] Wikipedia; [search] Apple text: 18-core CPU = 6 super + 12 performance, GPU up to 20 |
| M5 Max | 6 super + 12 performance (18) | 32 or 40 | 460 (32-core GPU), 614 (40-core) | 128 GB | N3P [search] | Mar 3, 2026 | [verified] Wikipedia; [search] Apple text: up to 614 GB/s |
| M5 Ultra (quad-die UltraFusion) | 12 super + 24 performance (36) per one source; 30/36 total per Wikipedia | 64 or 80 | 1,228.8 (Wikipedia); "up to 1.2 TB/s" (Macworld) | 512 GB (Wikipedia infobox; a Wikipedia variants row says 256 GB) | N3P [estimate] | Aug 25, 2026 (Mac Studio) | [search] Macworld, Tom's Guide, 9to5Mac, Apple archive title. Core and GPU counts conflict between sources; treat as unverified |
| M6 | 2 super + 4 performance + 6 efficiency (12 total) | 12 | up to 170 (LPDDR5X, Notebookcheck suggests 128-bit at 10,667 MT/s) | 32 GB (Mac mini cap); 16 GB base | TSMC N2 (2nm, GAA) | Aug 25, 2026 (Mac mini, with M5 Pro Mac mini) | [search] TrendForce, Phoronix, MacRumors, Notebookcheck. MacRumors notes the M5 uses N3P, so the M5->M6 gain is smaller than the 2nm label implies |
| M6 Pro / Max | [gap] | [gap] | [gap] | [gap] | | not announced | Bloomberg rumour that they wait for M7 in 2027 [search] |

Note on the M5 base core labelling: Wikipedia calls the M5 CPU "3 or 4 super + 6 efficiency" but the M5 Pro/Max description calls its 12 new cores "performance" and says they replace efficiency cores [verified Wikipedia]. Apple names "super" the high-performance tier. Treat the M5 base split as unresolved [gap]. For tuning, only the total CPU count and P-count matter.

### 1b. GPU family, features, cache

| Family | Chips | Notes | Source |
|---|---|---|---|
| Apple7 | M1 (and A14) | Base GPU features | [search] Apple Metal feature set tables |
| Apple8 | M2 (and A15/A16) | | [search] |
| Apple9 | M3, M4 (and A17 Pro) | Dynamic Caching, hardware ray tracing, mesh shading were introduced with M3/A17 Pro | [search] WWDC23 summary; [verified] Wikipedia M3 and M4 list those features |
| Apple10 | M5 family, A19 series | Apple's current feature-set page lists M5 under Apple10 | [search] Metal feature set page; detailed Apple10 feature list [gap] |
| M6 family | [gap] | | |

Cache sizes (last-level / system-level cache):
- M2 8 MB, M2 Pro 24 MB, M2 Max 48 MB, M2 Ultra 96 MB [verified] Wikipedia.
- M1, M3, M4, M5 and M6 cache sizes: [gap] (not in the fetched tables).

Transistors (for reference): M2 20B, M2 Pro 40B, M2 Max 67B, M2 Ultra 134B; M3 25B, M3 Pro 37B, M3 Max 92B, M3 Ultra 184B [verified] Wikipedia.

Neural Engine is out of scope.

---

## 2. Which Macs, and cooling

Fanless (passively cooled) Macs in this range are the MacBook Air. A "MacBook Neo" (passively cooled, M4 class) also appears in search results [search]. Its exact chip and model were not verified.

| Chip | Fanless (MacBook Air, Air 13" / 15") | Fan-cooled |
|---|---|---|
| M1 | MacBook Air M1 (2020) 13" | MacBook Pro 13" M1; Mac mini M1; iMac 24" M1 |
| M1 Pro/Max/Ultra | none | MBP 14"/16"; Mac Studio (Max, Ultra) |
| M2 | MacBook Air 13" (2022); Air 15" (2023) [verified] Wikipedia | MBP 13" (2022); Mac mini (2023) |
| M2 Pro/Max/Ultra | none | MBP 14"/16" (Pro, Max); Mac Studio (Max, Ultra); Mac Pro (Ultra) |
| M3 | MacBook Air 13"/15" (Mar 2024) [verified] | MBP 14" (Nov 2023); iMac 24" (2023) |
| M3 Pro/Max/Ultra | none | MBP 14"/16"; Mac Studio (Ultra, 2025) |
| M4 | MacBook Air 13"/15" (2025) [verified] | iMac, Mac mini, MBP 14" |
| M4 Pro/Max | none | Mac mini Pro; MBP 14"/16"; Mac Studio Max |
| M5 | MacBook Air 13" (2026) [verified] and 15" [search] | MBP 14" (M5, Oct 2025) |
| M5 Pro/Max | none | MBP 14"/16"; Mac mini (M5 Pro, 2026); Mac Studio (M5 Max, Aug 2026) |
| M5 Ultra | none | Mac Studio (M5 Ultra, Aug 2026) |
| M6 | none announced for Air yet (rumoured early 2027) [search] | Mac mini (M6, Aug 2026) |

Mac identifiers (see section 3): Mac14,2 = MacBook Air 13" M2 (2022); Mac15,12 = MacBook Air 13" M3 (2024); Mac16,12 = MacBook Air 13" M4 (2025); Mac16,13 = MacBook Air 15" M4 (2025); Mac14,15 = MacBook Air 15" M2; Mac15,13 = MacBook Air 15" M3 [search] EveryMac and Apple support snippets.

Throttling evidence (sparse; the Air is the key case):
- MBP 14" M5 Max, Notebookcheck stress test: peak 96 W for 1-2 s, then 46 W, settling at about 42 W. Attributed to the compact chassis. [search]. Apple later changed a macOS 26.6.2 power behaviour (Automatic mode now up to 75 W, then 60 W, then 54 W) on the 16" model [search].
- MacBook Air M5 13": gaming runs with no throttling reported by Notebookcheck [search]. A developer's merge-sort loop showed about 6% drop across repeated runs [search, not a Cinebench loop].
- MacBook Air M4 15" (SmartWorld, Cinebench 2024): throttling starts quickly in multi-core, about 10% lower multi-core and GPU after half an hour versus a Mac mini M4 [search].
- MacBook Air M4 13" Notebookcheck review: CPU PL2 20 W burst, PL1 8 W sustained [search]. The Notebookcheck page was blocked (HTTP 403), so the loop numbers are [gap].
- Sustained numbers for the 13" M5 Air loop: [gap]. Recommend Notebookcheck or Max Tech loop charts.

Notebookcheck and Max Tech publish loop charts; these are the sources to pull for the Air rows.

---

## 3. Programmatic detection on macOS

| Signal | Use | Reliability | Source |
|---|---|---|---|
| `sysctl -n hw.model` | Mac model identifier, e.g. Mac14,2. Chromium's newer code prefers `hw.product` because `hw.model` is deprecated | High for identifier; see the Chromium note | [search] Chromium sys_info_mac.mm; tldr notes |
| `sysctl -n machdep.cpu.brand_string` | Chip name, e.g. "Apple M2 Max". Used by Chromium | High | [search] |
| `hw.perflevel0.physicalcpu`, `hw.perflevel1.physicalcpu` | Performance and efficiency core counts. perflevel0 is the performance cluster | High on Apple silicon | [search] Eclectic Light sysctl reference |
| `hw.memsize` | Installed RAM in bytes; detects the 8 GB tier | High | [gap in search; known from Apple sysctl docs; treat as knowledge] |
| `hw.nperflevels` | Number of cluster levels | [gap] | |
| Metal `MTLDevice.name` | Chip name in Metal | High | [gap, known API] |
| Metal `supportsFamily(.apple7/.apple8/.apple9/...)` | GPU family check | High for Apple family checks; use feature tables for mesh shading and RT | [search] Apple Metal feature set table |
| Metal `hasUnifiedMemory` | Unified-memory flag | High | [gap, known API] |
| Metal `recommendedMaxWorkingSetSize` | GPU budget | High | [gap, known API] |
| GPU core count via IORegistry (`AGXAccelerator`, `gpu-core-count`) | Exact GPU core count | Commonly used by tools but NOT verified here | [gap]. Blender's commit reads the core count through Metal, not IORegistry [search] |
| `system_profiler SPDisplaysDataType` | GPU name and Metal support | High | [gap] |

Reliable: the model identifier, chip brand string, perflevel counts, RAM size, Metal family and unified-memory flags.

Least reliable: GPU core count (IORegistry key not verified; Metal does not appear to expose it directly). Plan a fallback: map model identifier plus chip brand string to a known GPU core option, and use a runtime calibration to break ties between 10- and 8-core parts.

Model identifier to tier mapping: use the identifier, not the marketing name. Several identifiers cover different core options (e.g. same chip, different GPU cores). The identifier must be looked up in a table; Apple's support pages (e.g. "Identify your MacBook Air model", support.apple.com/102869) list them [search].

Caveat: Apple's support page snippet showed Mac15,12 next to M2 specs, which looks like a layout error. Apple's other listings and EveryMac tie it to the M3 13" Air. Treat it as M3 but verify with a real machine [search].

---

## 4. Relative performance (for scaling defaults)

Data are sparse. Use the ratios below only as rough tier anchors.

| Benchmark | Data point | Source |
|---|---|---|
| GFXBench 5 Metal Aztec Ruins Normal (offscreen) | M1: 203.6 FPS; RX 560 146.2; GTX 1050 Ti 159 | [search] Tom's Hardware / GFXBench via search snippets. GFXBench 5 is a phone-oriented benchmark |
| Aztec Ruins Normal (M2 vs M1) | M2 about +45% vs M1 (GFXBench) | [search] Notebookcheck |
| Aztec Ruins (M3 Max vs M2 Max) | M3 Max 443 FPS; M2 Max 374 FPS (+18%); RTX 4080 Laptop 479 | [search] Notebookcheck. Caveat: GFXBench not updated for a year |
| Geekbench 6 Metal (base chips) | M2 base 45,732 (Mac); M3 base about 47,420 (garbled in source, unconfirmed); M1 base 33,971 (iPad Air 5, not comparable to Mac) | [search] Geekbench browser table snippets |
| Geekbench 6 Metal (higher tiers) | M2 Ultra Studio 221,646; M4 Max MBP 190,329; M3 Max MBP 154,860; M4 Pro Mac mini 110,556 | [search] |
| M4 Pro and M4 Max GPU vs M3 | up to 40% and 25% faster for graphics | [search] Apple claim via Geekbench page |
| M5 Pro/Max vs M4 Pro/Max (Apple claim) | "over 4x peak GPU compute for AI"; up to 35% RT improvement | [search] Apple text (syndicated) |
| M5 Ultra vs M3 Ultra | Apple claims 1.8x graphics, 1.3x CPU (vs prior gen, as stated) | [search] Apple claim via Tom's Guide |
| M6 Mac mini vs M4 Mac mini | Apple: up to 2x graphics, up to 40% CPU | [search] Apple claim |
| M4 and M5 GFXBench / Cinebench per chip | | [gap] |

Scaling approach. Work in relative GPU compute by core count times clock class, not by Apple's marketing numbers. Memory bandwidth per GPU core for scaling: M1 base 68.3/8 = 8.5 GB/s per GPU core; M2 base 102.4/10 = 10.2; M3 base 102.4/10 = 10.2; M4 base 120/10 = 12; M4 Pro 273/20 = 13.7; M4 Max 546/40 = 13.7; M5 base 153.6/10 = 15.4; M5 Max 614/40 = 15.4; M6 170/12 = 14.2 [estimate from table values above].

Those per-core bandwidth numbers are the most useful input for fragment-heavy shader packs (bandwidth-bound at Retina resolution). Geometry-bound terrain scales more with core count and clocks.

---

## 5. Per-generation optimisation notes

- Dynamic Caching (M3, Apple9+): local memory allocated in hardware per task, transparent to developers [search] WWDC23, Apple.
- Hardware ray tracing and mesh shading arrived with Apple9 (M3/M4) [search]. Apple10 (M5) adds Neural Accelerators per GPU core [search]. Apple10 details beyond that: [gap].
- Tile memory sizes per family: [gap].
- Bandwidth per GPU core is roughly flat from M2 to M5 (about 10 to 15 GB/s per core); M5 and M6 are the first to add real headroom [estimate].
- Efficiency cores: M5 Pro/Max use 12 new performance cores and no efficiency cores; base M5/M6 keep efficiency cores [search]. Affects core-allocation logic in Java (worker pool sizing).
- Memory compression and swap on 8 GB: [gap] in search. Apple's behaviour is known to compress and swap aggressively on 8 GB; no measured Cadence-relevant number was found. Use a runtime swap probe instead.
- Thermal state behaviour: Air throttles at a lower power ceiling than Pro (see section 2). macOS ProcessInfo thermalState is a reliable runtime signal [gap, known API].
- Game developer notes on GPU-family tuning: [gap]. The Apple Metal feature set tables and WWDC sessions on Dynamic Caching are the starting points.
- Game Porting Toolkit notes: [gap].

---

## 6. How others auto-configure consistently

Searches for game-specific tuning methodology returned little usable material in this run [gap]. Known approaches to consider (knowledge, not verified in this session):
- Fixed calibration scenes with a fixed camera path, run for N frames after a warm-up, then reported as the median of several runs (median is robust to thermal and background noise).
- Quantize results into tiers instead of storing raw numbers, so identical machines land on the same preset even with a few percent noise.
- Store the identifier-based default first and let calibration only move the user one tier up or down, with a cap.
- Re-run calibration after an OS or GPU driver update, or after a chassis-level change, rather than trusting one run.

These need sourcing before Cadence relies on them.

---

## 7. Java on Apple Silicon

Architecture:
- Minecraft Java has native Apple Silicon support from 1.19 onward; 1.6.4 to 1.18.2 need a workaround; older versions can run under Rosetta 2 [search] AppleGamingWiki and AppleInsider (2022).
- Rosetta vs native benchmark for Minecraft: [gap]. One forum anecdote said Rosetta is fine standing still but slows during chunk loading [search, anecdote only].
- Minecraft 26.1 requires Java 25 [search] Fabric docs and a server guide. Java 25 is the next LTS after 21.

Launchers (2025-2026):
- Official Minecraft launcher ships its own Java runtime (arm64 on Apple Silicon) [search] Fabric docs.
- Prism Launcher ships an ARM64 macOS build and can download and manage Java [search].
- CurseForge app and Modrinth App default Java choice on arm64: [gap]. Check their docs or settings.
- Azul Zulu and Eclipse Temurin both have arm64 macOS builds and are the common recommendations [search].

Licensing (not legal advice):
- Temurin is GPLv2 with the Classpath Exception and the OpenJDK Assembly Exception; the Eclipse Foundation board approved this distribution in 2021 [search] Temurin NOTICE and board minutes.
- Azul Zulu is GPLv2 with the Classpath Exception; Azul notices require source offers for components whose licenses require it [search] Azul TPL notices.
- Redistributing or bundling a JDK inside a mod or modpack: the Classpath Exception is intended to allow combining with independently licensed code, but distributing the JDK binary itself carries notice and source-offer obligations. The sources here do not settle whether bundling in a closed-source mod is fine. Recommending or downloading the JDK at runtime (as Prism does) avoids most redistribution questions [estimate]. Confirm with counsel before shipping a bundled JDK.
- Oracle JDK has separate licensing; not covered here.

---

## 8. Gaps to close next

1. Notebookcheck MacBook Air M4/M5 loop charts (blocked; try Max Tech or the German Notebookcheck page).
2. Verified M6 and M5 Ultra core counts (one Apple newsroom copy, or Apple's spec page).
3. Apple10 Metal feature list.
4. IORegistry `gpu-core-count` verification on a real machine (the only reliable core count source).
5. Rosetta vs native Minecraft benchmark.
6. CurseForge and Modrinth default Java choices.
7. Temurin and Zulu full license texts for redistribution.
8. Cache sizes for M1, M3, M4, M5, M6.
9. Geekbench 6 Metal base-chip table (M3 score is unconfirmed).

Sources used (primary and secondary): en.wikipedia.org/wiki/Apple_M1, Apple_M2, Apple_M3, Apple_M4, Apple_M5; apple.com/newsroom archive (titles only); Macworld, Tom's Guide, 9to5Mac, Fstoppers (M5 Ultra); TrendForce, Phoronix, MacRumors, Notebookcheck (M6); Notebookcheck (M5 Max throttle, M3/M2 GFXBench, M4 Air review); SmartWorld (M4 Air Cinebench); Eclectic Light (sysctl); Chromium sys_info_mac.mm (sysctl); support.apple.com/102869 and EveryMac (model identifiers); Apple Metal feature set tables (developer.apple.com); Fabric docs (Java for Minecraft); Azul TPL notices; Eclipse Temurin NOTICE and temurin-dev list; Eclipse Foundation board minutes 2021-09-15.
