#!/usr/bin/env bash
# A/B check of a patched server against a baseline server on one save.
#
#   BASEJAR=base.jar OPTJAR=opt.jar scripts/perf/ab.sh <save.msav> <hashTicks> <benchTicks>
#
# 1) hash: both jars run <hashTicks> ticks in deterministic mode; the per-tick world hashes must match.
# 2) bench: <benchTicks> timed ticks per jar after 600 warmup ticks, two interleaved rounds.
#
# OPTJAR may be omitted: then uncommitted work/core changes are compiled over BASEJAR
# (plain classes only; component classes need a real gradle build).
# HASH_OPTS  JVM options for hash runs, e.g. "-Dbench.chaos=2 -Dbench.schems=/path/to/schematics"
# BENCH_OPTS JVM options for timing runs (keep chaos out of these: it defeats every cache)
# HASH_XMX   heap per hash run (default 4g); SEQUENTIAL=1 runs the two hash runs one after another
#
# exit: 0 ok, 1 usage/setup error, 2 hash mismatch, 3 only the opt run crashed, 4 the base run crashed
set -eo pipefail
PERF="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$PERF/../.." && pwd)"
RUN="$PERF/run"
J="${JAVA_HOME:+$JAVA_HOME/bin/}"
#classpath separator, and native paths for the JVM on Windows
sep=":"; wp(){ echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; wp(){ cygpath -w "$1"; };; esac
abs(){ echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
die(){ echo "$1" >&2; exit 1; }

[ $# -eq 3 ] || die "usage: BASEJAR=... [OPTJAR=...] $0 <save.msav> <hashTicks> <benchTicks>"
[ -f "$1" ] || die "no save: $1"
[ -f "${BASEJAR:-}" ] || die "set BASEJAR to the unpatched server jar"
[ -z "${OPTJAR:-}" ] || [ -f "$OPTJAR" ] || die "no OPTJAR: $OPTJAR"
tag=$(basename "$1" .msav)
save="$(wp "$(abs "$1")")"; hticks=$2; bticks=$3
BASE="$(wp "$(abs "$BASEJAR")")"
[ -n "${OPTJAR:-}" ] && OPTJAR="$(abs "$OPTJAR")"
mkdir -p "$RUN"
cd "$RUN"

rm -rf cls
"$J"javac -nowarn -encoding UTF-8 -d cls -cp "$BASE" "$PERF"/src/mindustry/ai/*.java

if [ -n "${OPTJAR:-}" ]; then
  [ "$OPTJAR" -ef opt.jar ] || cp "$OPTJAR" opt.jar
else
  files=()
  while IFS= read -r f; do files+=("$ROOT/work/$f"); done < <(git -C "$ROOT/work" diff --name-only HEAD -- core/src | grep '\.java$' || true)
  [ ${#files[@]} -gt 0 ] || die "no uncommitted work/core changes to compile; build the patched jar and pass OPTJAR"
  rm -rf optcls; mkdir -p optcls
  "$J"javac -nowarn -encoding UTF-8 -proc:none -d optcls -cp "$BASE$sep$(wp "$ROOT/work/annotations/build/classes/java/main")" "${files[@]}"
  #same jar layout for both runs, so class loading is identical
  cp "$BASE" opt.jar
  (cd optcls && "$J"jar uf ../opt.jar $(find . -name '*.class' | sed 's#^[.]/##'))
fi

if [ "$hticks" -gt 0 ]; then
  #constant identity hash: identity-hashed sets iterate in a fixed order, independent of JVM internals
  HOPT="-XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Xmx${HASH_XMX:-4g} ${HASH_OPTS:-}"
  run_hash(){ # jar out
    "$J"java $HOPT -cp "cls$sep$1" mindustry.ai.TickBench "$save" "$hticks" "$2" 0 > "$2.log" 2>&1 || true
    [ -f "$2" ] && [ "$(wc -l < "$2")" -eq "$hticks" ]
  }
  rb=0; ro=0
  if [ "${SEQUENTIAL:-0}" = 1 ]; then
    run_hash "$BASE" "hash_base_$tag.txt" || rb=1
    run_hash opt.jar "hash_opt_$tag.txt" || ro=1
  else
    run_hash "$BASE" "hash_base_$tag.txt" & p1=$!
    run_hash opt.jar "hash_opt_$tag.txt" & p2=$!
    wait $p1 || rb=1
    wait $p2 || ro=1
  fi
  if [ $rb = 1 ]; then echo "BASE RUN CRASHED, see $RUN/hash_base_$tag.txt.log"; exit 4; fi
  if [ $ro = 1 ]; then echo "OPT RUN CRASHED, see $RUN/hash_opt_$tag.txt.log"; exit 3; fi
  if cmp -s "hash_base_$tag.txt" "hash_opt_$tag.txt"; then
    echo "HASH OK: $hticks ticks identical"
  else
    echo "HASH MISMATCH (tick buildings units misc)"
    diff "hash_base_$tag.txt" "hash_opt_$tag.txt" | head -4 || true
    exit 2
  fi
fi

if [ "$bticks" -gt 0 ]; then
  for round in 1 2; do
    for v in base opt; do
      jar="$BASE"; [ $v = opt ] && jar=opt.jar
      echo "--- $v (round $round)"
      "$J"java ${BENCH_OPTS:-} -Xmx6g -cp "cls$sep$jar" mindustry.ai.TickBench "$save" "$bticks" - 600 2>/dev/null | grep -E "TOTAL|stateUpdate|unitUpdate|powerUpdate|buildingUpdate|bulletUpdate"
    done
  done
fi
