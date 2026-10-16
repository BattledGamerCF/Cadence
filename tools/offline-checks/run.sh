#!/bin/bash
# Offline regression checks (no game started): the vanilla RGSS patch, shader profile tolerance, per-pack settings and
# the compatibility layers' mixin reading. Build first (./gradlew build); the compat check also needs tools/compat-test-mod/build.sh.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null)}"
CP="$ROOT/shaders/build/classes/java/main:$ROOT/shaders/build/resources/main:$(cat "$ROOT/metal/build/cadence-run/classpath.txt")"
MC=$(tr ':' '\n' < "$ROOT/metal/build/cadence-run/classpath.txt" | grep -m1 -E "minecraft-(client|merged)[^/]*\.jar$")
[ -z "$MC" ] && MC=$(ls ~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar)
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
cd "$OUT"
"$JAVA/bin/javac" -nowarn -proc:none -cp "$CP" -d "$OUT" "$ROOT/tools/offline-checks/OfflineChecks.java" \
	"$ROOT/tools/offline-checks/dev/cadence/metal/compat/CompatProbe.java" 2>&1 | grep -v "^Note" 
status=0
"$JAVA/bin/java" -cp "$OUT:$CP" OfflineChecks "$MC" "$ROOT/metal/run/shaderpacks" 2>&1 | grep -E "^(OK|FAIL|all|[0-9]+ offline)" || status=1
[ -f "$ROOT/tools/compat-test-mod/build/compat-test.jar" ] || "$ROOT/tools/compat-test-mod/build.sh" >/dev/null
"$JAVA/bin/java" -cp "$OUT:$CP" dev.cadence.metal.compat.CompatProbe "$ROOT/tools/compat-test-mod/build/compat-test.jar" "$ROOT/metal/build/classes/java/main" 2>&1 | grep -E "^(OK|FAIL)" || status=1
exit $status
