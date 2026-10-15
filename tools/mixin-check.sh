#!/bin/bash
# Checks every compiled Cadence mixin against Minecraft 26.3 without starting the game (see MixinCheck.java).
# Build first (./gradlew build); run from anywhere: tools/mixin-check.sh
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-$(/usr/libexec/java_home -v 25 2>/dev/null)/bin/java}"
CP="$ROOT/metal/build/cadence-run/classpath.txt"
[ -f "$CP" ] || { echo "Run ./gradlew :metal:writeRunClasspath first (or a build)."; exit 2; }
LIBS=$(tr ':' '\n' < "$CP" | grep -v "/build/" | tr '\n' ':')
ASM=$(tr ':' '\n' < "$CP" | grep "org.ow2.asm/asm" | tr '\n' ':')
exec "$JAVA" -cp "$ASM" "$ROOT/tools/MixinCheck.java" "$LIBS" \
	"$ROOT/metal/build/classes/java/main" "$ROOT/shaders/build/classes/java/main" "$ROOT/fpshud/build/classes/java/main"
