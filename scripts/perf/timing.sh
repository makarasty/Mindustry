#!/usr/bin/env bash
# Interleaved timing of two jars in run/ (A=base.jar B=opt-full.jar by default), N rounds each (default 5).
# Prints medians: main-thread cpu, whole-process cpu, wall mean, wall p99 per tick, and the min of main-thread cpu.
#   A=base2.jar B=opt2.jar scripts/perf/timing.sh scen_stack scen_war     (saves in run/, cls compiled by ab.sh)
# OPTS: JVM options for the bench (default -Dbench.seed=1), e.g. OPTS="-Dbench.seed=1 -Dbench.players=30"
cd "$(dirname "$0")/run"
J=${JAVA_HOME:-/d/java/jdk-17.0.20.1+1}/bin; N=${N:-5}; T=${T:-2500}; A=${A:-base.jar}; B=${B:-opt-full.jar}
for s in "$@"; do
  for r in $(seq $N); do for v in $A $B; do
    $J/java ${OPTS:--Dbench.seed=1} -Xmx${XMX:-3g} -cp "cls;$v" mindustry.ai.TickBench $s.msav $T - 600 2>/dev/null |
      awk -v s=$s -v v=$v '/^TOTAL/{w=$2;p=$5} /^cpu /{c=$2} /^proccpu /{pc=$2} END{print s, v, c, w, p, pc}'
  done; done
done | awk '
function med(str,  a, n){ n = split(str, a, " "); asort(a); return a[int((n + 1) / 2)] }
function mn(str,  a, n){ n = split(str, a, " "); asort(a); return a[1] }
{k = $1 " " $2; c[k] = c[k] " " $3; w[k] = w[k] " " $4; p[k] = p[k] " " $5; pc[k] = pc[k] " " $6}
END{for(k in c) printf "%-26s cpu %.3f (min %.3f) | proc %.3f | wall %.3f | p99 %.3f\n", k, med(c[k]), mn(c[k]), med(pc[k]), med(w[k]), med(p[k])}' | sort
