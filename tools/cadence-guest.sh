#!/bin/bash
# A second dev client that joins a host's LAN world (multiplayer tests): the host runs tools/cadence-test.sh with a
# "lan=PORT" step and waits; this one joins localhost:PORT as CadenceGuest with its own folder (metal/run-guest: options,
# config and logs of its own; the shader packs are the host's). Small window, small heap: two games on an 8 GB Mac.
# usage: cadence-guest.sh <plan> <port> [heap MB=900] [max minutes=6]
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN=$ROOT/metal/run; GUEST=$ROOT/metal/run-guest
JAVA="${JAVA:-$(/usr/libexec/java_home -v 25 2>/dev/null)/bin/java}"
PLAN="$1"; PORT="$2"; HEAP="${3:-900}"; MAXMIN="${4:-6}"
mkdir -p "$GUEST/mods" "$GUEST/config" "$GUEST/screenshots"
[ -e "$GUEST/shaderpacks" ] || ln -s "$RUN/shaderpacks" "$GUEST/shaderpacks"
cp "$RUN/mods/cadence-shaders.jar" "$GUEST/mods/"
[ -f "$GUEST/options.txt" ] || cp "$RUN/options.txt" "$GUEST/options.txt"
cp "$RUN/config/cadence-shaders.properties" "$GUEST/config/" 2>/dev/null
grep -v '^shaderSwitching=' "$GUEST/config/cadence-shaders.properties" > "$GUEST/config/c.tmp" 2>/dev/null
echo "shaderSwitching=instant" >> "$GUEST/config/c.tmp"; mv "$GUEST/config/c.tmp" "$GUEST/config/cadence-shaders.properties"
sed -i '' -E "s/^renderDistance:[0-9]+$/renderDistance:${CADENCE_RD:-6}/" "$GUEST/options.txt"
OUT=$RUN/results/$(date '+%m%d-%H%M%S')-guest; mkdir -p "$OUT"
rm -f "$GUEST/screenshots/selftest-"*.png
cd "$GUEST"
nice -n 5 "$JAVA" -XstartOnFirstThread -Xms256m -Xmx${HEAP}m -XX:+UseG1GC --enable-native-access=ALL-UNNAMED \
  -Dfabric.dli.config=$ROOT/.gradle/loom-cache/projects/metal/launch.cfg -Dfabric.dli.env=client \
  -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient \
  -Dcadence.selftest="$PLAN" -Dcadence.selftest.settle=2 -Dcadence.backgroundFps=false $CADENCE_EXTRA_JVM \
  -cp "$(cat $ROOT/metal/build/cadence-run/classpath.txt)" net.fabricmc.devlaunchinjector.Main \
  --width ${CADENCE_W:-854} --height ${CADENCE_H:-480} --quickPlayMultiplayer "localhost:$PORT" --username CadenceGuest > "$OUT/stdout.log" 2>&1 &
GPID=$!
( sleep $((MAXMIN * 60)); kill $GPID 2>/dev/null ) &
TIMER=$!
wait $GPID; code=$?
kill $TIMER 2>/dev/null
echo "GUEST EXIT code=$code"
cp "$GUEST/logs/latest.log" "$OUT/latest.log" 2>/dev/null
for png in "$GUEST/screenshots/selftest-"*.png; do
  [ -f "$png" ] || continue
  sips -s format jpeg -s formatOptions 80 -Z 1280 "$png" --out "$OUT/$(basename "$png" .png).jpg" >/dev/null 2>&1
done
echo "---- results in $OUT"
