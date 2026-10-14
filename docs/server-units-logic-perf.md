# Server load from units and logic

Follow-up to [server-tick-perf.md](server-tick-perf.md), October 2026: the loads players use to take a sandbox
server down, mass units and logic processors, and two optional admin limits. Patches 0083 to 0087.

## Patches

One change per patch, so each can be dropped or rebased on its own when upstream or MindustryX moves.

| Patch | Change | Why the result is the same | Guarded by |
|---|---|---|---|
| 0083 `OG(Teams)` | Team unit quadtrees and the `Groups.unit` tree (`Teams.UnitTree`) read `x`, `y`, `hitSize` from the `Unit` base class instead of calling `hitbox()` through the `QuadTreeObject` interface. `Units.nearby(team, x, y, radius)` does the same for its distance check. | Every generated unit class implements `hitbox`, `getX`, `getY`, `damaged`, `within` with exactly these field reads. A reflection check on all unit types turns the fast path off for the whole server if any class (a Java mod) overrides one of them. | `UnitTreeTests` (fields equal the generated methods for every unit type, query results equal a plain `QuadTree`, `Units.nearby` equals a brute-force `within` filter), hash A/B, mutant `hitbox-half` |
| 0084 `OG(RepairFieldAbility)` | The repair scan checks `damaged()` before the distance. | Both checks are pure; only units the old code also skipped are skipped. | hash A/B on `war`, mutant `repair-threshold` |
| 0085 `OG(BuilderAI)` | The "find a builder to assist" scan checks `plans.size != 0` before `canBuild()`/`activelyBuilding()`. | `activelyBuilding()` is false without plans in every generated class; all checks are pure. | hash A/B on `stack`, mutant `builder-active` |
| 0086 `FS` | `maxTeamUnits` server config. | Off (0) by default: `canCreate` is unchanged. | `ServerLimitsTests.teamUnitCap`, `spawn` scenario |
| 0087 `FS` | `logicBudget` server config. | Off (0) by default: the accumulator gets the same float as before. | `ServerLimitsTests.logicBudget*` |

The new code in `src/` is `mindustryX/features/ServerLimits.java` (both configs) and one line in `Hooks.beforeInit`.

### Why these were the hot spots

With a few thousand units, a quarter of the tick was the interface call `QuadTreeObject.hitbox()`: units come in
a dozen generated classes, so the call site is megamorphic and the JVM scans the class's ~20 interfaces for every
object in every tree query and rebuild. Mixed unit types are what players spawn on sandbox.

`RepairFieldAbility` (nova, poly, mega) does not reset its timer when nobody around is damaged, so every healer
scans its neighbourhood every tick until someone gets hurt. In a pile of 2000 units that was 106 scans per tick and
35% of the tick. Changing when it scans would change when healing happens, so only the scan got cheaper.

## Admin limits

Set in the server console, saved in settings, effective at once. Both change gameplay when on.

- `config maxTeamUnits <n>`: a team cannot create more than n units through factories, reconstructors,
  assemblers, unit-spawning abilities, cargo loaders and logic `spawn`, on top of the per-type cap. Player units and
  missiles are not counted. Teams without a cap (waves outside PvP) are not limited; in PvP every team, the wave
  team included, gets the same cap. The count is live, so twenty factories finishing in one tick cannot overshoot.
  Map scripts that `spawn` units for a capped team also stop at the cap.
- `config logicBudget <n>`: player processors (not world processors) together get n instructions per server frame.
  Over budget every busy processor slows down by the same factor; processors sitting in `wait` or `stop` do not
  count. The budget is shared by all teams. `wait` durations stretch by a step, not smoothly, once a processor gets
  less than one instruction per frame.

Clients are not told about either limit. With a limit on, a client's factory progress bars and its local
processor simulation (displays, printed text) can run ahead of the server until the next block sync. The server
stays authoritative for units and anything `ucontrol`/`spawn` does.

## Numbers

Ryzen 9800X3D, JDK 17, shared with other work, so treat them as ratios. `scripts/perf/timing.sh`: five
interleaved rounds per jar, 2500 ticks after 600 warmup, `-Dbench.seed=1` so both jars simulate the same battle.
Base is the build without 0083 to 0087.

| Save | Main-thread CPU ms/tick, median | Wall p99 ms, median |
|---|---|---|
| `stack`: 2000 mixed units in a pile | 5.46 to 3.18 (-42%) | 16.7 to 8.0 |
| `war`: 1500 vs 1500, walls being rebuilt | 6.02 to 4.21 (-30%) | 14.9 to 9.8 |
| `stack1`: 2000 daggers in a pile | 1.71 to 1.53 (-10%) | about the same |
| `logic`: 2000 hyper processors in unit control loops, 200 world processors | 6.7 to 5.9 (-12%, noisy) | |
| `stressA`/`stressC` (buildings, from the earlier doc) | no change expected, none measured beyond noise | |

`stack1` shows the gain depends on the unit mix: with one type the JVM inlines the call and there is little to win.

Limits, on `logic` with `opt` jar: `logicBudget=5000` takes the building phase from 7.0 to 4.3 ms per tick;
`spawn` with 20 world processors spawning every 6 ticks ends at 2000 units without a cap and at exactly 150 with
`maxTeamUnits=150`.

Allocation: the main thread allocates about 200 KB per tick on `logic` (radar lambdas), which costs 5 young
collections and about 6 ms per 2500 ticks. Not worth a patch.

## How it was checked

- Hash A/B (`ab.sh`), 900 ticks, base against patched: `stack`, `stack1`, `war`, `logic`, `stressC` identical,
  `stressA` with a world mutation every tick identical for 600 ticks. One `stack` run out of eight mismatched at
  tick 360 while the machine was heavily loaded; the seven runs after it, three of them base against base, were
  identical. The harness has known wall-clock throttles in pathfinding (see the perf README), which is the likely
  cause, but it was not proven.
- Mutants (`mutate.sh`), each must break the hash on at least one save: halved hitbox and repair threshold die on
  `war`, `activelyBuilding()` replaced by `true` and the distance check without `hitSize` die on `stack`
  (1500 ticks, with pending rebuild plans). The control, fast path forced off, survives as it should.
- JUnit: `UnitTreeTests` and `ServerLimitsTests` (7 tests) plus the existing `ApplicationTests`, `LogicTests` and
  power tests (296) pass.
- A local server on Java 21 with the built jar: configs registered and changed from the console, `war` at
  59 to 61 FPS with 2300 units, no exceptions. No game client was connected.
- Three read-only review passes. They found: the first unit cap counted players and missiles and was only
  updated once per tick; the first logic budget counted waiting processors; mod classes overriding `within` were
  not detected; the benchmark never ran the `BuilderAI` branch. All fixed.

## After an upstream or MindustryX update

1. `cd work && ./gradlew core:kaptKotlin --rerun tests:test --tests UnitTreeTests --tests ServerLimitsTests`.
   `vanillaUnitsUseFastPath` failing means some unit class now overrides a method the fast path mirrors: the server
   still works, only slower; check whether the override changes the hitbox. `fieldsMatchGeneratedMethods` failing
   means the generated code changed and 0083 must follow it.
2. Build base and patched jars, then `scripts/perf/ab.sh` on the unit saves and the mutants listed in the perf
   README for anything the update touched. A survived mutant means the saves no longer reach that code.
3. `scripts/perf/timing.sh scen_stack scen_war` for the numbers.

`--rerun` on `kaptKotlin` is needed because `patchGeneratedSources` re-applies `patches/generated.patch` on every
build and fails when kapt output is up to date. `scripts/genPatches.sh` needs `#PATCH-BASE#`/`#END-PICKED#` marker
commits, which this checkout of `work/` and `Arc/` does not have; 0083 to 0087 were exported with the same
`git format-patch` flags (checked to reproduce 0081 and 0082 byte for byte).

## Not done, and why

- Skipping repair scans while nobody is damaged: needs a hook on every health write, plugins write `health`
  directly, and a missed write would stop healing for good. Left as is; 0084 made the scan cheap instead.
- Caching unit hitboxes at tree build time: units move between the rebuild and the queries, so results would
  change.
- Legs IK on the server (`LegsComp`, 6 to 8% in `war`): leg positions decide drowning and splash damage.
- `StatusComp` `Bits.clear` and the logic interpreter itself: memory-bound or spread thin, no single fix.
- Fewer radar allocations: about 0.1% of time in GC.
- Upstream since `beaa1da` (to 2026-10-08) has no performance fixes to pick.
- Seen by the plugin profiling session, not addressed here: world data for joining players is serialized and
  compressed per player (a burst of bot joins keeps the executor busy), and `Administration.findByIP` scans all
  player records linearly. Both are candidates for the next round (roadmap items 9 and the bot work).
