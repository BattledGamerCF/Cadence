#!/bin/bash
# Offline tests of Cadence Logic against Minecraft 26.3's own classes (no game started). Build first (./gradlew build).
#   SectionFuzz  - 900k random boxes: the entity-section scan visits exactly vanilla's sections in vanilla's order
#   TickerFuzz   - 200k random block-entity ticker lists: the compacting pass ticks and keeps exactly what vanilla does
#   SectionBench - speed of a mob-sized entity search, vanilla vs Cadence
#   VivaceCheck  - which Java Video Settings > Java restarts the game on, and when it stays on the launcher's
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null)}"
CP="$(tr ':' '\n' < "$ROOT/metal/build/cadence-run/classpath.txt" | grep -v /build/ | tr '\n' ':')"
OUT=$(mktemp -d)
set -e
"$JAVA/bin/javac" -nowarn -cp "$CP" -d "$OUT" "$ROOT/metal/src/main/java/dev/cadence/metal/logic/EntitySectionScan.java" \
	"$ROOT/tools/logic-tests/SectionFuzz.java" "$ROOT/tools/logic-tests/SectionBench.java" 2>&1 | grep -v "^Note" || true
"$JAVA/bin/java" -cp "$OUT:$CP" SectionFuzz
"$JAVA/bin/java" "$ROOT/tools/logic-tests/TickerFuzz.java"
"$JAVA/bin/java" -cp "$OUT:$CP" SectionBench | tail -1
M="$ROOT/metal/src/main/java/dev/cadence/metal"
"$JAVA/bin/javac" -nowarn -d "$OUT/v" "$M/system/Vivace.java" "$M/system/ConfigFiles.java" "$M/system/Legacy.java" "$ROOT/tools/logic-tests/VivaceCheck.java"
"$JAVA/bin/java" -Dcadence.javaProbeTimeout=3 -cp "$OUT/v" dev.cadence.metal.system.VivaceCheck "$JAVA" | tail -1
rm -rf "$OUT"
