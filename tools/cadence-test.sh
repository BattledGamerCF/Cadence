#!/bin/bash
# Runs one Cadence self-test in the dev client, safely on an 8 GB Mac, without Gradle running alongside.
# usage: cadence-test.sh <plan> [settle s=30] [heap MB=1280] [max minutes=12] [max hours to wait for the player's game=4]
#   plan: comma list for -Dcadence.selftest (pack file name | off | f3a), e.g. "BSL_v10.1.8.zip,off,f3a"
# Waits until no other Minecraft is running (the player's own game is never touched) and free memory >= 35%,
# starts tools/cadence-guard.sh, runs the game from metal/run joining the world "tester", then copies the log and
# screenshots into metal/run/results/<time>/ and prints the [selftest]/[probe]/GUARD lines.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN=${CADENCE_RUN:-$ROOT/metal/run}  # CADENCE_RUN=dir: another game folder (mod compatibility runs: metal/run-compat)
JAVA="${JAVA:-$(/usr/libexec/java_home -v 25 2>/dev/null)/bin/java}"
PLAN="$1"; SETTLE="${2:-30}"; HEAP="${3:-1280}"; MAXMIN="${4:-12}"; WAITH="${5:-4}"
ts() { date '+%H:%M:%S'; }
free_pct() { memory_pressure | tail -1 | grep -o '[0-9]*'; }
other_game() {  # any Minecraft that is not this dev client (prints nothing about it: its command line holds a token)
  # every java process whose full command line (ps -ww: never truncated) is a Minecraft client that isn't ours
  # (pgrep -f missed the player's game once: its command line is very long)
  # (another project's dev client, e.g. Wayfarer's, launches through Fabric's devlaunchinjector with its arguments in a file)
  ps -A -ww -o pid=,command= 2>/dev/null | grep -E '[j]ava .*(net\.minecraft\.client\.main\.Main|KnotClient|minecraft|devlaunchinjector)' \
    | grep -v 'cadence-metal' | awk '{print $1; exit}'
}
# The player's own Minecraft may be open (never touched). WAITH=0: don't wait for it, run alongside with a stricter guard
# (free < 25% stops ours) and a note in the results; otherwise wait up to WAITH hours for it to close.
deadline=$(( $(date +%s) + WAITH * 3600 ))
COEXIST=0
while [ -n "$(other_game)" ]; do
  if [ "$WAITH" = "0" ]; then COEXIST=1; break; fi
  [ $(date +%s) -ge $deadline ] && { echo "$(ts) GAVE UP: the player's Minecraft is still open"; exit 2; }
  sleep 15
done
# CADENCE_WAIT_IDLE=S: also wait until nobody has touched the keyboard/mouse for S seconds (the player is using the Mac)
if [ -n "$CADENCE_WAIT_IDLE" ]; then
  while true; do
    idle=$(ioreg -c IOHIDSystem | awk '/HIDIdleTime/ {print int($NF/1000000000); exit}')
    [ "${idle:-0}" -ge "$CADENCE_WAIT_IDLE" ] && break
    [ $(date +%s) -ge $deadline ] && { echo "$(ts) GAVE UP: the Mac is in use"; exit 2; }
    sleep 30
  done
  echo "$(ts) the Mac has been idle ${idle}s"
fi
[ $COEXIST = 1 ] && echo "$(ts) the player's Minecraft is open: running alongside it (stricter guard)" || echo "$(ts) no other Minecraft running"
export CADENCE_GUARD_MINFREE=$([ $COEXIST = 1 ] && echo 25 || echo 20)
# alongside the player's idle game, swap growth is mostly its pages going out: allow more, keep the free-memory floor
export CADENCE_GUARD_MAXSWAP=${CADENCE_MAXSWAP:-$([ $COEXIST = 1 ] && echo 1000 || echo 600)}  # CADENCE_MAXSWAP=MB overrides
# never the game and a Gradle build together (8 GB): wait for any build to end, up to 15 min
# (an idle daemon left behind by a finished build holds ~0.5-1 GB: stopped, not waited for)
for i in $(seq 1 90); do pgrep -f '[G]radleWrapperMain' >/dev/null || break; sleep 10; done
pgrep -f '[G]radleWrapperMain' >/dev/null && { echo "$(ts) NOT STARTED: a Gradle build is still running"; exit 5; }
pgrep -f '[G]radleDaemon' >/dev/null && { (cd "$(dirname "$0")/.." && ./gradlew --stop -q >/dev/null 2>&1); sleep 2; }
for i in $(seq 1 40); do [ "$(free_pct)" -ge 35 ] && break; sleep 15; done
f=$(free_pct); [ "$f" -lt 35 ] && { echo "$(ts) NOT STARTED: free memory ${f}% < 35%"; exit 3; }
# one dev game at a time: wait (up to 12 min) for an earlier test's game to be gone, then let its files settle
for i in $(seq 1 72); do [ -z "$(pgrep -f "[f]abric.dli.config=$ROOT")" ] && break; sleep 10; done
[ -n "$(pgrep -f "[f]abric.dli.config=$ROOT")" ] && { echo "$(ts) NOT STARTED: another dev game is still running"; exit 4; }
sleep 3
# CADENCE_WORLD_SNAPSHOT=dir: the world "tester" is restored from it first (benchmarks that move the player or build chunks)
# (--checksum: a world file the run rewrote can keep its size and time; quick-check rsync left those behind)
# default: metal/run/world-snapshots/tester (a clean copy of the world, kept outside temp folders that get cleaned up);
# CADENCE_WORLD_SNAPSHOT=none keeps whatever the world is now
[ -z "$CADENCE_WORLD_SNAPSHOT" ] && [ -d "$ROOT/metal/run/world-snapshots/tester" ] && CADENCE_WORLD_SNAPSHOT="$ROOT/metal/run/world-snapshots/tester"
[ "$CADENCE_WORLD_SNAPSHOT" = none ] && CADENCE_WORLD_SNAPSHOT=
if [ -n "$CADENCE_WORLD_SNAPSHOT" ] && [ ! -d "$CADENCE_WORLD_SNAPSHOT" ]; then echo "$(ts) NOT STARTED: world snapshot $CADENCE_WORLD_SNAPSHOT is missing"; exit 7; fi
if [ -n "$CADENCE_WORLD_SNAPSHOT" ]; then rsync -a --checksum --delete "$CADENCE_WORLD_SNAPSHOT/" "$RUN/saves/tester/" && echo "$(ts) world restored from snapshot"; fi
# a full-size screenshot is ~5 MB: never start with less than 3 GB free (a full disk broke a run and the world restore)
freegb=$(df -g "$RUN" | awk 'NR==2 {print $4}'); [ "${freegb:-0}" -lt 3 ] && { echo "$(ts) NOT STARTED: only ${freegb} GB free on disk"; exit 6; }
OUT=$RUN/results/$(date '+%m%d-%H%M%S'); mkdir -p "$OUT"
GLOG=$OUT/guard.log
rm -f "$RUN/screenshots/selftest-"*.png
echo "$(ts) START window=${CADENCE_W:-960}x${CADENCE_H:-540} rd=${CADENCE_RD:-6} coexist=$COEXIST extra=[$CADENCE_EXTRA_JVM] plan=$PLAN settle=$SETTLE heap=${HEAP}M limit=${MAXMIN}min free=${f}%"
# the dev run loads the shaders module from run/mods: always the jar just built
J=$(ls -t "$ROOT"/shaders/build/libs/cadence-shaders-*.jar 2>/dev/null | grep -v sources | head -1)
[ -z "$CADENCE_CLASSPATH" ] && [ -n "$J" ] && [ "$J" -nt "$RUN/mods/cadence-shaders.jar" ] && cp "$J" "$RUN/mods/cadence-shaders.jar" && echo "$(ts) mods/cadence-shaders.jar updated from $(basename "$J")"
# (and the fps counter's: it stayed at an October build, so the counter's changes were never in the test game)
H=$(ls -t "$ROOT"/fpshud/build/libs/cadence-hud-*.jar 2>/dev/null | grep -v sources | head -1)
[ -z "$CADENCE_CLASSPATH" ] && [ -n "$H" ] && [ "$H" -nt "$RUN/mods/cadence-fpshud.jar" ] && cp "$H" "$RUN/mods/cadence-fpshud.jar" && echo "$(ts) mods/cadence-fpshud.jar updated from $(basename "$H")"
I=$(ls -t "$ROOT"/irisapi/build/libs/cadence-iris-api-*.jar 2>/dev/null | grep -v sources | head -1)
[ -z "$CADENCE_CLASSPATH" ] && [ -n "$I" ] && [ "$I" -nt "$RUN/mods/cadence-iris-api.jar" ] && cp "$I" "$RUN/mods/cadence-iris-api.jar" && echo "$(ts) mods/cadence-iris-api.jar updated from $(basename "$I")"
cd "$RUN"
# the shader config as it was: restored after the run even if the game crashes (the self-test restores it only on a clean exit)
CFG_BACKUP=$(mktemp); cp config/cadence-shaders.properties "$CFG_BACKUP" 2>/dev/null
# shader switching for this run (restored after with the config): CADENCE_SWITCHING=auto|instant|restart; a plan that turns a
# pack on (a .zip step) needs instant, since a game started without shaders otherwise uses Cadence's own terrain (Auto)
SWITCHING="$CADENCE_SWITCHING"
[ -z "$SWITCHING" ] && [[ "$PLAN" == *.zip* ]] && SWITCHING=instant
if [ -n "$SWITCHING" ]; then
  grep -v '^shaderSwitching=' config/cadence-shaders.properties > config/cadence-shaders.properties.tmp 2>/dev/null
  echo "shaderSwitching=$SWITCHING" >> config/cadence-shaders.properties.tmp && mv config/cadence-shaders.properties.tmp config/cadence-shaders.properties
fi
# render distance for this run (CADENCE_RD), default 6
sed -i '' -E "s/^renderDistance:[0-9]+$/renderDistance:${CADENCE_RD:-6}/" options.txt
sed -i '' -E "s/^enableVsync:(true|false)$/enableVsync:${CADENCE_VSYNC:-false}/" options.txt  # CADENCE_VSYNC=true: as the player's default
# windowed at launch (a "fullscreen" step's option is saved with the options: the next run would start fullscreen)
sed -i '' -E "s/^fullscreen:(true|false)$/fullscreen:false/" options.txt
# backgroundFps=false: the test window is often not the active app (the player is using the Mac); its few-fps cap
# would hold every measurement at ~15 fps and freeze the GPU reading
# CADENCE_ENV: extra environment for the game only (e.g. MallocStackLogging=lite for malloc_history)
nice -n 5 env ${CADENCE_ENV} "$JAVA" -XstartOnFirstThread -Xms256m -Xmx${HEAP}m ${CADENCE_GC:--XX:+UseG1GC} --enable-native-access=ALL-UNNAMED \
  -Dfabric.dli.config=${CADENCE_DLI_CONFIG:-$ROOT/.gradle/loom-cache/projects/metal/launch.cfg} -Dfabric.dli.env=client \
  -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient \
  -Dcadence.selftest="$PLAN" -Dcadence.selftest.settle=$SETTLE -Dcadence.backgroundFps=false $CADENCE_EXTRA_JVM \
  -cp "$(cat ${CADENCE_CLASSPATH:-$ROOT/metal/build/cadence-run/classpath.txt})" net.fabricmc.devlaunchinjector.Main \
  --width ${CADENCE_W:-960} --height ${CADENCE_H:-540} --quickPlaySingleplayer tester --username CadenceTest > "$OUT/stdout.log" 2>&1 &
GPID=$!
sleep 3
"$ROOT/tools/cadence-guard.sh" "$GLOG" "$MAXMIN" &
wait $GPID; code=$?
wait
echo "$(ts) GAME EXIT code=$code"
[ -s "$CFG_BACKUP" ] && cp "$CFG_BACKUP" config/cadence-shaders.properties; rm -f "$CFG_BACKUP"
cp "$RUN/logs/latest.log" "$OUT/latest.log" 2>/dev/null
# screenshots are kept as small JPEGs (1280 px, ~150 KB, not ~5 MB PNGs); CADENCE_KEEP_PNG=1 keeps the PNGs (pixel analysis)
for png in "$RUN/screenshots/selftest-"*.png; do
  [ -f "$png" ] || continue
  if [ -n "$CADENCE_KEEP_PNG" ]; then cp "$png" "$OUT/"
  else sips -s format jpeg -s formatOptions 80 -Z 1280 "$png" --out "$OUT/$(basename "$png" .png).jpg" >/dev/null 2>&1; fi
done
rm -f "$RUN/screenshots/selftest-"*.png
ls "$RUN/crash-reports" 2>/dev/null | tail -2
echo "---- results in $OUT"
grep -h -E 'GUARD (START|stopping|END)' "$GLOG"
grep -h -E '\[selftest\]|\[probe\]|ERROR|Exception|Caused by' "$OUT/latest.log" 2>/dev/null | cut -c1-400 | head -120
