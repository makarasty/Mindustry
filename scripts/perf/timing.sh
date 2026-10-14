#!/usr/bin/env bash
# interleaved timing of base.jar and opt-full.jar in run/, N rounds each (default 5): median and min of main-thread cpu, wall mean, p99
#   scripts/perf/timing.sh scen_stack scen_war   (saves in run/, cls compiled by ab.sh)
cd "$(dirname "$0")/run"
J=/d/java/jdk-17.0.20.1+1/bin; N=${N:-5}; T=${T:-2500}
for s in "$@"; do
  for r in $(seq $N); do for v in base opt-full; do
    $J/java ${OPTS:--Dbench.seed=1} -Xmx3g -cp "cls;$v.jar" mindustry.ai.TickBench $s.msav $T - 600 2>/dev/null | awk -v s=$s -v v=$v '/^TOTAL/{w=$2;p=$5} /^cpu /{c=$2} END{print s, v, c, w, p}'
  done; done
done | awk '{k=$1" "$2; c[k]=c[k]" "$3; w[k]=w[k]" "$4; p[k]=p[k]" "$5} END{for(k in c){n=split(c[k],a," "); asort(a); m=split(w[k],b," "); asort(b); q=split(p[k],d," "); asort(d); printf "%-22s cpu med %.3f min %.3f | wall med %.3f | p99 med %.3f\n", k, a[int((n+1)/2)], a[1], b[int((m+1)/2)], d[int((q+1)/2)]}}' | sort
