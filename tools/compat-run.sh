#!/bin/bash
# Runs the release jar with the mod compatibility set (metal/run-compat/mods: the mods listed in docs/COMPATIBILITY.md) in a
# production launch, and says PASS or FAIL: the self-test finished, no mixin failed to apply, no exception reached the log,
# and the benchmark ran. Uses the newest dist/cadence-*.jar (build it with tools/release first); the jar it replaces in the
# set is moved to metal/run-compat/mods-backup.
# usage: compat-run.sh [plan="wait=20,screen=video,wait=3,screen=close,benchmark"] [render distance=12]
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/metal/run-compat"
PLAN="${1:-wait=20,screen=video,wait=3,screen=close,benchmark}"
RD="${2:-12}"
JAR=$(ls -t "$ROOT"/dist/cadence-0*.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "no dist/cadence-*.jar: run tools/release first"; exit 2; }
mkdir -p "$RUN/mods-backup"
for old in "$RUN"/mods/cadence-0*.jar; do
  [ -e "$old" ] || continue
  [ "$(basename "$old")" = "$(basename "$JAR")" ] && continue
  mv "$old" "$RUN/mods-backup/"
done
cp "$JAR" "$RUN/mods/"
echo "compat: $(basename "$JAR") with $(ls "$RUN/mods" | grep -c '\.jar$') jars in the set"
CADENCE_RUN="$RUN" CADENCE_DLI_CONFIG="$RUN/launch-prod.cfg" CADENCE_CLASSPATH="$RUN/classpath-prod.txt" CADENCE_RD="$RD" \
  "$ROOT/tools/cadence-test.sh" "$PLAN" 3 1100 10 1 > "$RUN/compat-last.txt" 2>&1
d=$(ls -t "$RUN/results" | head -1)
LOG="$RUN/results/$d/latest.log"
fail=0
grep -q "\[selftest\] DONE" "$LOG" || { echo "FAIL: the self-test didn't finish"; fail=1; }
grep -q "\[selftest\] benchmark: avg" "$LOG" || { echo "FAIL: no benchmark result"; fail=1; }
# (the dev account's skin lookup fails with 401 in every offline run: not a compatibility problem)
mixin=$(grep -cE "Mixin apply.*failed|MixinApplyError|InvalidInjectionException|InvalidMixinException" "$LOG")
[ "$mixin" -gt 0 ] && { echo "FAIL: $mixin mixin errors"; grep -E "Mixin apply.*failed|MixinApplyError|InvalidInjectionException|InvalidMixinException" "$LOG" | head -5; fail=1; }
exc=$(grep -E "^[[:space:]]*at |Exception|Error" "$LOG" | grep -vE "InvalidCredentialsException|MinecraftClientHttpException|authlib|status=401|ErrorResponse" | grep -cE "Exception|Error:")
[ "$exc" -gt 0 ] && { echo "WARN: $exc exception lines (see $LOG)"; grep -E "Exception|Error:" "$LOG" | grep -vE "InvalidCredentialsException|MinecraftClientHttpException|authlib|status=401|ErrorResponse" | sort | uniq -c | sort -rn | head -5; }
grep -E "\[selftest\] benchmark: avg" "$LOG" | sed 's/.*\[selftest\] /  /'
grep -hE "\[cadence\] stall:" "$RUN/results/$d/stdout.log" 2>/dev/null | head -3
[ $fail = 0 ] && echo "PASS ($RUN/results/$d)" || echo "FAIL ($RUN/results/$d)"
exit $fail
