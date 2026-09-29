# Server tick bench and A/B harness

Tools to measure the server tick and to prove that an optimization does not change game state.
Nothing here is part of the Gradle build; it compiles against a built `server-release.jar`.

- `src/mindustry/ai/TickBench.java` loads a save headless and runs ticks. Timing mode prints mean/p50/p99
  per `PerfCounter` phase. Hash mode writes a world hash per tick and makes the run reproducible.
- `src/mindustry/ai/StressGen.java` builds a synthetic late-game save from a folder of schematics.
- `ab.sh` runs a baseline jar and a patched jar on the same save: hash traces must be identical, then timing.
- `mutate.sh` breaks an optimization and expects `ab.sh` to fail. Use it to check that the scenario
  actually exercises the code you changed.
- `jfragg.py` groups JFR samples of the main thread by the block `updateTile` they run under.

## Usage

```bash
# baseline = server jar built without the change, opt = with it (cd work && ./gradlew server:dist)
export BASEJAR=/path/base.jar OPTJAR=/path/opt.jar
SCHEMS=/path/to/schematics   # e.g. %APPDATA%/Mindustry/schematics

# any ab.sh call compiles the bench into scripts/perf/run/cls; generate saves from that directory
cd scripts/perf/run
java -cp "cls:$BASEJAR" mindustry.ai.TickBench gen stressA.msav "$SCHEMS" 1 800 120000        # building-heavy
java -cp "cls:$BASEJAR" mindustry.ai.TickBench gen stressC.msav "$SCHEMS" 2 500 15000 1500   # 1500 units per team fighting
cd -

# 1200 ticks of hash comparison with a random world mutation every tick, then 3000 timed ticks
HASH_OPTS="-Dbench.chaos=1 -Dbench.schems=$SCHEMS" scripts/perf/ab.sh scripts/perf/run/stressA.msav 1200 3000

# the check must catch a broken invalidation
HASH_OPTS="-Dbench.chaos=2 -Dbench.chaosActions=0,1,5 -Dbench.schems=$SCHEMS" \
  scripts/perf/mutate.sh scripts/perf/run/stressA.msav 900 core/src/mindustry/world/Tile.java \
  '        changing = true;\n        topologyVersion++;\n' '        changing = true;\n'
```

Use `;` instead of `:` in `-cp` on Windows. `ab.sh` options: `HASH_OPTS` (hash runs only),
`BENCH_OPTS` (timing runs only; keep chaos out, it defeats every cache), `HASH_XMX` (default 4g per run),
`SEQUENTIAL=1` (hash runs one after another, for small machines).
Exit codes: 0 ok, 1 usage/setup, 2 hash mismatch, 3 only the opt run crashed, 4 the base run crashed.

Profile with `java -XX:StartFlightRecording=filename=x.jfr,settings=profile -cp ... TickBench <save> 2400 - 1200`,
then `python scripts/perf/jfragg.py <jdk>/bin/jfr x.jfr`.

## What the hash covers

Every building by tile order (`writeAll` plus efficiency, timeScale, enabled; processors without code
compression plus variables, bound unit, ipt; display `operations`), units by id (`write`), bullets
(id, type, position, damage, time), fires and puddles, every power graph's produced/needed/stored/capacity/
balance, the global RNG and wave state. Not covered: unit AI and weapon runtime state beyond `write`,
team rebuild plans, weather. A divergence there shows up only once it reaches covered state.

## Determinism

Two unpatched runs of the same save do not match out of the box. Sources found and neutralized in hash mode:

| Source | Effect | Handling |
|---|---|---|
| `Building.proximity` built through an identity-hashed `ObjectSet` | neighbour order, so dump/transfer order, differs per process | `-XX:hashCode=2`: all identity hashes equal, so these sets iterate in a fixed order (slower, and not the order a real server uses) |
| `OverdriveBuild.charge = Mathf.random(reload)` during save load, not saved | overdrive pulses at a random phase | `Mathf.rand` seeded before `SaveIO.load` |
| unseeded static `Rand`s (logic `rand`, `FlyingAI`, `Lightning`, physics worlds, ...) | random divergence | seeded by reflection |
| `Pathfinder`/`ControlPathfinder` threads with a wall-clock budget | paths ready at a random tick | threads parked, stepped to completion on the main thread |
| persisted settings | changed startup, shifted identity hashes | fresh data directory per run |

Still wall-clock driven and not neutralized: the pathfinder target refresh throttle (`Pathfinder`,
`refreshIntervalMs` and `PositionTarget.refreshRate`) and dynamic fog updates. A faster build can hit them on
other ticks, which can only cause false mismatches, never false passes. Re-run on a mismatch in unit or fog maps.

## Chaos actions (`bench.chaosActions`)

0 kill, 1 place schematic, 2 team swap, 3 bridge relink, 4 unloader/sorter config, 5 remove,
6 copy processor code, 7 toggle processor link, 8 link or recode a processor then replace a linked building,
9 unlink+relink a processor link in one tick then replace it, 10 silent `Tile.setTeam` (plugin style).
`bench.mixedBridges=1` gives phase conveyors `linkSameType=false`, like a map data patch.

## Mutation matrix for patches 0078/0079

All killed on stressA (900 ticks): no bump in `Tile.setBlock`, in `Tile.setTeam`, in `ItemBridge.setLink`;
no reset on processor link toggle; `updateCode` + `readCompressed` resets together; size check + toggle reset
together; inverted unloader pre-check; battery sum and graph capacity off by 0.01%.
Single processor guards survive alone because they overlap, which is intended.
The mixed-`linkSameType` guard is not killed by the random chaos (it needs a dumping bridge next to a
bridge of another type); with the guard the bridge runs the original per-tick code.
