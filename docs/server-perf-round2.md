# Server performance, round 2 (v160.7)

Follow-up to [server-units-logic-perf.md](server-units-logic-perf.md). Upstream merged to v160.7 (Anuken
`17012d61d9`, TinyLake/MindustryX `703f65a`), then patches 0088 to 0105 in `work/` and 0009, 0010 in `Arc/`.
One change per patch; each one says what it touches and why the game state stays the same.

The fleet runs nine servers on two shared cores, so besides the main thread this round also counts the whole
process: physics executor, pathfinder threads, GC and JIT (`proccpu` in the bench).

## Patches

State-identical (SI) means the world after every tick is bit-identical to the build without the patch, checked by
the per-tick world hash on every save in the bench. Server-only changes are gated on `Vars.headless`.

| Patch | Change | Why it is safe | Checked by |
|---|---|---|---|
| 0088 `OG(QuadTree)` | Building, bullet and physics quadtrees get their own `hitbox()` override. | Same method called; only the call site differs. The shared one in `QuadTree` sees every element type and is megamorphic. | hash; mutant on the physics tree |
| 0089, 0105 `OG(LExecutor)` | On a server, `draw` does nothing, and 90% of the instructions in 1871 real player schematics are `draw`. Runs of `draw`/`noop` are stepped over without dispatching, a whole run at once. | Only the counter changes, exactly as `runOnce` would change it; whole steps keep the float accumulator exact. Programs without such instructions do not take the path at all. | `LogicSkipTests` (step and processor-loop equivalence, odd counters), hash on stressA, two mutants |
| 0090 `OG(ItemBridge)` | The linked building is cached with the link validation; the mixed `linkSameType` check is a plain loop. | `Tile.build` only changes in `Tile.setBlock`, which bumps the topology version the cache already keys on. | hash with relink/remove/replace chaos, mutant |
| 0091 `OG(Unloader)` | A nulloader with no giving neighbour holding anything skips the per-item scan. | Without a provider every item is impossible; item counts are never negative. | `UnloaderSkipTests` (400 random neighbourhoods) |
| 0092 `OG(BuilderAI)` | The assist scan stops at the first builder found. | The old scan returned early for every unit after the first match. | `UnitTreeTests.nearbyCheckStopsAtTheSameFirstMatch` |
| 0093 `OG(Units)` | `invalidateTarget` reads unit fields when the 0083 fast path is on. | Same expressions as the generated methods; mods overriding `hitSize()`/`team()` turn the fast path off. | `UnitTreeTests`, mutant |
| 0094 `OG(Bullet)` | Bullets whose type cannot collide skip the unit tree queries. | `collides()` starts with `type.collides`. | mutant on the generated `Bullet.java` |
| 0095 `OG(Weapon)` | Shoot angle and velocity are computed only when they are read. | Read only when shooting or continuous. On a server the angle in `update` only feeds effects. | inspection |
| 0096 `OS(SaveIO)` | Buffers before the deflater and after the inflater for saves, world data and map loading. | Same bytes after inflating. | `ApplicationTests` save/load |
| 0097 `OS(Pathfinder)` | Flow fields refresh only when a tile change can change a cost; both pathfinder threads sleep 250 ms while paused. | Vanilla costs read a tile's team only when it is solid; mods adding cost or field types keep the old behaviour. Paths after an unpause start up to 250 ms later. | `PathfinderRefreshTests` (3000 random changes: a skipped refresh never hides a cost change) |
| 0098 `OS(NetServer)` | No entity and state snapshots on an empty server. | Nobody receives them; saves call `beforeWrite()` themselves. | inspection |
| 0099 `FIX.S(NetServer)` | Hidden (fog) snapshots are split into 250 ids. | Over ~8000 ids the packet overflowed its 32 KB buffer and silently stopped being sent. | inspection |
| 0100 `FIX.S(ServerControl)` | Autosave no longer fires on the first frame after every map load; rotation after a successful write by exact time; `loadautosave` loads the newest, not the oldest; settings.bin is rewritten only when something changed. | Bug fixes. | `SettingsSaveTests` |
| 0101 `OS(Administration)` | One player record scan per IP ban check instead of two. | Same result. | inspection |
| 0102 `OG(LAssembler)` | Numeric constant names without `FloatingDecimal`; one map lookup per variable. | Names are still exactly `"___" + value`, saves store variables by name. | `LAssemblerConstTests` |
| 0103 `OS(LogicBlock)` | Processor code is assembled once when loading a save, not twice. | The first assembly was always replaced before the processor first ran. | hash on stressA |
| 0104 `OS(LogicBlock)` | The compressed code written into saves and world data is reused while the code and links are unchanged; the Deflater is reused. | Bytes depend only on the code and each link's position and name, all checked. | `LogicCompressTests` (500 random edits, including direct `links` changes) |
| Arc 0009 `OG(Settings)` | Putting an unchanged value does not mark settings modified. | `byte[]` compares by identity, so `putJson` still marks. | `SettingsSaveTests` |
| Arc 0010 `OG(QuadTree)` | `insert` recomputes the hitbox only after a split. | Only `split()` overwrites the scratch rect. | hash |

## Numbers

Base is v160.7 with the MindustryX patches up to 0082 (before any of this work); final is everything through
0105. Ryzen 9800X3D, JDK 17, shared with other sessions; `scripts/perf/timing.sh`, medians.

| Save | Main thread CPU ms/tick | Whole process CPU ms/tick | Wall p99 ms |
|---|---|---|---|
| `stack`: 2000 mixed units in a pile | 5.46 to 3.24 (-41%) | 7.63 to 5.50 (-28%) | 18.1 to 11.7 |
| `war`: 1500 vs 1500, walls rebuilt | 6.07 to 4.21 (-31%) | 7.92 to 6.06 (-24%) | 20.2 to 9.3 |
| `war` with 30 connected players | 6.88 to 4.98 (-28%) | 9.06 to 7.64 (-16%) | 21.9 to 14.6 |
| `stressA`: 48k buildings from player schematics | 15.6 to 13.8 (-11%) | 22.7 to 19.7 (-13%) | 35.5 to 29.3 |
| `stressC`: 15k buildings, 3000 units | 12.4 to 11.0 (-11%) | 15.1 to 14.2 (-6%) | 28.1 to 26.8 |
| `logic`: 2000 unit-control processors, 200 world processors | 6.9 to 6.6 (-5%) | 8.1 to 7.9 (-3%) | |

This last run was taken under heavy load from other sessions (base numbers 20-40% above the calmer runs before
it). A calmer run of the round-2 patches alone on stressA gave -18% main thread and -35% whole process.

Saves and joins on stressA (48k buildings, 5.9k processors, a 23 MB save):

| | Base | Final |
|---|---|---|
| Autosave, first after a load | 2.9 s | 2.8 s |
| Autosave, every later one | 3.0 s | 0.66 s |
| Map load (`load`, map rotation) | 8.7 s | 3.1 s |
| World data for a joining player, after the first | 2.65 s | 0.58 s |

All of these run on the main thread, so before this every autosave and every join froze the server for about
three seconds on a map like this, and every map change for nine.

GC choice, measured on `war` with 30 players and the fleet's `-Xmx1024M`: `-XX:+UseSerialGC` instead of G1 took
the main thread from 3.96 to 3.51 ms per tick (-11%) and the whole process from 5.88 to 5.33 ms (-9%). No code;
a line in `run.sh`. On the fleet's 2-core VPS with nine JVMs, G1's concurrent threads and barriers buy nothing.

## Not done, and why

- Building world data for a joining player off the main thread: packets sent while it compresses would reach the
  client before the world, and block changes made meanwhile would be lost until the next snapshot.
- The 300 ms pathfinder refresh throttle from the roadmap: walls would affect paths later.
- Not creating player records for rejected connections: plugins look kicked players up by name.
- Skipping leg IK on the server, sleeping buildings, a lower deflate level (5-10% faster, 1% bigger, not worth a
  format question), PowerGraph and Unloader neighbour scans (memory-bound), the logic interpreter beyond skipping
  `draw` (what is left is the first cache miss on each of the 5.9k executors).
- Upstream after v160.7 has no performance fixes to pick.

## After an update

```
cd work && ./gradlew core:kaptKotlin --rerun tests:test --tests UnitTreeTests --tests ServerLimitsTests --tests LogicSkipTests \
  --tests PathfinderRefreshTests --tests SettingsSaveTests --tests UnloaderSkipTests --tests LAssemblerConstTests --tests LogicCompressTests
```

then the hash A/B on the bench saves and the mutant matrix in `scripts/perf/README.md`.
