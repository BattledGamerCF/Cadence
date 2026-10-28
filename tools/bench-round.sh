#!/bin/bash
# Runs a list of benchmark configurations one after another, each in its own game launch with a cool-down between them
# (the fanless MacBook Air loses up to half its speed when hot; 150 s between runs keeps sequential runs within ~1%).
# usage: bench-round.sh <list file> <summary file> [cool-down s=150]
#   list file: one run per line, "NAME|PLAN|EXTRA" (# comments allowed):
#     NAME  a label for the summary
#     PLAN  the self-test plan (tools/cadence-test.sh), e.g. "fullscreen,sd=16,wait=25,benchmark"
#     EXTRA space-separated settings for the run: rd=N (render distance, default 16), far=on (Far Terrain on for this
#           launch), jvm="..." (more JVM flags), heap=MB (default 1100)
# Each run's [selftest] benchmark / fps / watts lines and the results folder go to the summary file.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIST="$1"; SUM="$2"; COOL="${3:-150}"
PROPS="$ROOT/metal/run/config/cadence-shaders.properties"
first=1
while IFS='|' read -r name plan extra; do
  case "$name" in ''|\#*) continue ;; esac
  [ $first = 1 ] || sleep "$COOL"
  first=0
  rd=16; far=off; jvm=""; heap=1100
  for kv in $extra; do
    case "$kv" in rd=*) rd=${kv#rd=} ;; far=*) far=${kv#far=} ;; heap=*) heap=${kv#heap=} ;; esac
  done
  [[ "$extra" == *'jvm="'* ]] && jvm=$(sed -E 's/.*jvm="([^"]*)".*/\1/' <<<"$extra")
  cp "$PROPS" "$PROPS.bench-orig"
  [ "$far" = on ] && echo "farTerrain=on" >> "$PROPS"
  echo "=== $name ($(date '+%H:%M')) rd=$rd far=$far jvm=[$jvm] plan=$plan" >> "$SUM"
  CADENCE_RD=$rd CADENCE_MAXSWAP=1200 CADENCE_EXTRA_JVM="-Dcadence.heatControl=false $jvm" \
    "$ROOT/tools/cadence-test.sh" "$plan" 3 "$heap" 12 2 > "$SUM.last" 2>&1
  cp "$PROPS.bench-orig" "$PROPS" && rm -f "$PROPS.bench-orig"
  d=$(ls -t "$ROOT/metal/run/results" | head -1)
  echo "results $d" >> "$SUM"
  grep -E "GUARD (STOP|KILL)|NOT STARTED|GAVE UP" "$SUM.last" >> "$SUM"
  grep -E "\[selftest\] (benchmark|watts|fullscreen|ahead|[0-9]+ [^:]+: fps last)" "$ROOT/metal/run/results/$d/latest.log" \
    | sed -E 's/.*\[selftest\] //; s/; worst.*//' >> "$SUM"
  grep -E "cadence-lod stats: (cull|[0-9]+ fps)" "$ROOT/metal/run/results/$d/stdout.log" | tail -2 >> "$SUM"
done < "$LIST"
echo "=== DONE $(date '+%H:%M')" >> "$SUM"
