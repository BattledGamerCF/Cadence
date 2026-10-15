#!/bin/bash
# Pack Census: every blocker per shader pack, without starting Minecraft (see PackCensus). Build the shaders module first.
# usage: tools/pack-census.sh [--profiles] [pack.zip|folder ...]   (default: metal/run/shaderpacks)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-$(/usr/libexec/java_home -v 25 2>/dev/null)/bin/java}"
args=("$@"); [ ${#args[@]} -eq 0 ] && args=("$ROOT/metal/run/shaderpacks")
[ "${args[0]}" = "--profiles" ] && [ ${#args[@]} -eq 1 ] && args+=("$ROOT/metal/run/shaderpacks")
# relative pack paths first resolved against the caller's directory; the JVM runs elsewhere (it may write logs/)
abs=(); for a in "${args[@]}"; do [ "$a" = "--profiles" ] && abs+=("$a") || abs+=("$(cd "$(dirname "$a")" && pwd)/$(basename "$a")"); done
args=("${abs[@]}"); cd "${TMPDIR:-/tmp}"
nice -n 5 "$JAVA" -Xmx768m --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.util.NoChecks=true -Dcadence.census.src="$ROOT/shaders/src/main/java/dev/cadence/shaders" $CENSUS_JVM \
  -cp "$ROOT/shaders/build/classes/java/main:$(cat $ROOT/metal/build/cadence-run/classpath.txt)" \
  dev.cadence.shaders.cadence.census.PackCensus "${args[@]}"
