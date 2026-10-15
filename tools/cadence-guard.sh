#!/bin/bash
# Memory guard for a Cadence dev-client test on an 8 GB Mac (adapted from the Wayfarer long-run guard).
# Stops only the dev game (matched by its DLI config path, never the player's own Minecraft) when:
#   free memory < 20% for 20 s (after the first 2 min: loading a world spikes and settles), or < 15% for 10 s;
#   swap grows > 600 MB past where it was at the start; the game's RSS > 2600 MB; or the time limit passes.
# usage: cadence-guard.sh <log file> <max minutes>. Ends by itself once the game has been gone for 20 s.
LOG="$1"; MAXMIN="${2:-12}"; MINFREE="${CADENCE_GUARD_MINFREE:-20}"; MAXSWAP="${CADENCE_GUARD_MAXSWAP:-600}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PAT="[f]abric.dli.config=$ROOT"
ts() { date '+%H:%M:%S'; }
free_pct() { memory_pressure | tail -1 | grep -o '[0-9]*'; }
swap_used() { sysctl -n vm.swapusage | sed -E 's/.*used = ([0-9.]+)M.*/\1/' | cut -d. -f1; }
game() { pgrep -f "$PAT" | head -1; }
stop_game() {
  echo "$(ts) GUARD stopping the game: $1" >> "$LOG"
  pkill -TERM -f "$PAT"; sleep 10; pkill -KILL -f "$PAT" 2>/dev/null
}
start=$(date +%s); swap0=$(swap_used); low=0; verylow=0; gone=0; n=0
echo "$(ts) GUARD START swap ${swap0}MB free $(free_pct)% limit ${MAXMIN}min minfree ${MINFREE}% maxswap +${MAXSWAP}MB" >> "$LOG"
while true; do
  now=$(date +%s); el=$((now - start))
  g=$(game)
  if [ -z "$g" ]; then gone=$((gone + 2)); [ $gone -ge 20 ] && break; sleep 2; continue; fi
  gone=0
  f=$(free_pct); s=$(swap_used); growth=$((s - swap0))
  rss=$(( $(ps -o rss= -p "$g" 2>/dev/null || echo 0) / 1024 ))
  [ $((n % 15)) -eq 0 ] && echo "$(ts) GUARD up=${el}s free=${f}% swap=${s}MB (+${growth}) game_rss=${rss}MB" >> "$LOG"
  n=$((n + 1))
  if [ "$f" -lt "$MINFREE" ] && [ $el -ge 120 ]; then low=$((low + 1)); else low=0; fi
  if [ "$f" -lt 15 ]; then verylow=$((verylow + 1)); else verylow=0; fi
  why=""
  [ $low -ge 10 ] && why="free memory under ${MINFREE}% for 20 s (${f}%)"
  [ $verylow -ge 5 ] && why="free memory under 15% (${f}%)"
  [ $growth -gt $MAXSWAP ] && why="swap grew ${growth} MB"
  [ $rss -gt ${CADENCE_GUARD_MAXRSS:-2600} ] && why="game RSS ${rss} MB"
  [ $el -ge $((MAXMIN * 60)) ] && why="time limit ${MAXMIN} min"
  if [ -n "$why" ]; then stop_game "$why"; break; fi
  sleep 2
done
echo "$(ts) GUARD END" >> "$LOG"
