# План оптимизации выделенного сервера

Версия 2, 2026-10-04. Собрано из 6 исследований (синяя команда) и 6 разборов против (красная команда).
Всё найдено чтением кода, ничего не замерено. Пути относительно `work/core/src/mindustry/`, если не указано иное.

## Правила

1. **Только выделенный сервер.** `core` и `Arc` общие с клиентом (desktop, Android, хост LAN, одиночная игра).
   Каждое изменение в общем коде — под `Vars.headless` (не `net.server()`: хост LAN тоже сервер) или в
   `work/server/`. `headless` читать в момент использования, не в `static final` инициализаторе.
2. **Ванильные клиенты не замечают разницы.** Формат пакетов не меняем. Клиент сам симулирует пули, здания
   между block snapshots и физику юнитов, поэтому серверное изменение их поведения = рассинхрон.
   Pathfinding и ИИ — только на сервере (`net.client()` → return, `UnitComp.java:860`), там гейт безопасен.
3. **State-identical (SI) по умолчанию.** Проверка: `scripts/perf/ab.sh` + `mutate.sh`
   ([server-tick-perf.md](server-tick-perf.md)). Изменения, сдвигающие `Mathf.rand` или фазы таймеров, ломают
   бит-в-бит сравнение, даже если игроку не видны.
4. **Сначала замер.** Сейвы с прода и `/tps` до крупных пунктов.

## Архитектура (после красной команды)

- **Патчи ядра, один на функцию, без общего API hook-ов.** Hook-точки на каждое здание/юнита, свой профайлер и
  плагин с политиками выкинуты (см. «Отклонено»).
- **`/tps`** — около 30 строк поверх существующего `PerfCounter` (`core/Logic.java:468-500`), в `src/mindustryX/`
  или `ServerControl`, под `headless`.
- **Профилирование** — JFR (`jcmd <pid> JFR.start duration=60s settings=profile`) или async-profiler.
- **Плагин** — только если после патчей и замеров останется проблема: один `Trigger.update`, `hidden: true`,
  `if(!Vars.headless) return;` в конструкторе (иначе он загрузится и у клиента, положившего его в mods), без `mainX`.

## Баги, найденные по пути (исправить в любом случае)

| # | Что | Где | Проверено |
|---|-----|-----|-----------|
| B1 | `loadautosave` грузит **самый старый** автосейв (`.min(Fi::lastModified)`) | `server/.../ServerControl.java:1157` | да |
| B2 | Ротация автосейвов сортирует `lastModified` как float: шаг ~131 с, сейвы ближе ~2 мин удаляются в случайном порядке. Нужен long-компаратор | `ServerControl.java:299` | да |
| B3 | Автосейв, скорее всего, срабатывает на первом тике после загрузки карты: `Interval.reset(0, T)` ставит `times = Time.time - T`, `check` сразу true. Синхронный сейв при каждой ротации + вытеснение настоящих сейвов. Подтвердить по логам прода ("Autosaving..." сразу после загрузки) | `ServerControl.java:285`, `Arc/.../Interval.java:28-34` | код да, прод нет |
| B4 | Hidden snapshot не нарезается: ~8190 id переполняют буфер записи 32768, исключение глотается, пакет молча перестаёт уходить; ~6000 id больше буфера чтения клиента. Нарезать по ~250 id. Только карты с туманом | `core/NetServer.java:1214-1218` | да |
| B5 | Порядок ротации: старые автосейвы удаляются до записи нового | `ServerControl.java:302-305` | агент |
| B6 | DiscoverHost отвечает на любой 2-байтный UDP без лимита — усилитель отражённых атак; обработчик на net-потоке читает `state` | `ArcNetProvider.java:116-122` | агент |
| B7 | DataPatcher меняет `range`/`linkSameType` мостов без `Tile.topologyVersion++` — кэш 0082 может протухнуть | `mod/DataPatcher.java:272`, `:312` | агент |

## Оставлено: патчи ядра

Порядок — по выгоде с учётом риска.

| # | Что | Гейт | Метка |
|---|-----|------|-------|
| 1 | **Пропуск `sync()` без игроков**: только блок снапшотов (`NetServer.java:1319-1343`), цикл отключений оставить, `snapshotSyncTime` не сбрасывать. Это почти весь холостой CPU пустого сервера | `headless && Groups.player.isEmpty()` | SI |
| 2 | **Pathfinder: не ставить `needsRefresh`, если стоимость не изменилась.** Сравнивать упакованные тайлы всех linked-тайлов с маской команды у несолидных (иначе id команды меняется всегда). Оставить refresh при `rules.randomWaveAI`. `controlPath.updateTile` вызывать как сейчас. Покрывает конвейеры, conduit, ноды | `headless` в `updateTile` | SI |
| 3 | `refreshIntervalMs`: `headless ? 300 : 100` — втрое меньше полных заливок при стройке | `headless` | мягко GC (стены влияют на путь на 0.2 с позже) |
| 4 | Pathfinder/ControlPathfinder: спать 250 мс при `!state.isPlaying()` (в тот же патч, что 2–3) | `headless` | SI |
| 5 | Pathfinder: не пересчитывать поля без обращений ~10 с; отметка в `getField` (его читает и `BaseBuilderAI`). Делать после замера эффекта №2 | `headless` | мягко GC |
| 6 | **`Tile.bridgeVersion`**: сейчас любой `setBlock` сбрасывает кэш всех мостов (в бою кэш 0082 фактически выключен). Бамп только если старый или новый блок `ItemBridge`, смена команды у `ItemBridgeBuild`, `setLink`/`read`. Процессоры оставить на общем счётчике. Новые мутанты в `mutate.sh` | не нужен (SI) | SI |
| 7 | Nulloader: один проход «есть ли сосед с `items.total()>0 && canUnload()`» до цикла по предметам (`Unloader.java:187`) | не нужен (SI) | SI |
| 8 | `ItemBridge`: `all.contains(...)` раз за тик через static boolean со штампом `state.updateId`; в 0082 | не нужен (SI) | SI |
| 9 | Вход игрока: сериализовать `writeWorld` в сырой буфер на главном потоке, сжимать в **отдельном** однопоточном executor (не `mainExecutor`: физика ждёт его `futures.get()`), отправлять через `Core.app.post`, проверив `con.isConnected()`. Убирает фриз 0.1–0.4 с у уже играющих | `headless` в `sendWorldData` | SI, VC |
| 10 | Если №9 откладывается: `BufferedOutputStream(1<<15)` над `FastDeflaterOutputStream` в месте вызова `NetServer:549` (не в классе Arc). Байты те же | `headless` | SI |
| 11 | Загрузка карт: `BufferedInputStream(is, 1<<16)` **под** `CounterInputStream` (иначе сломается проверка длины региона) | `headless` в `SaveIO.java:164` | SI |
| 12 | **Асинхронный автосейв**: сериализация на главном потоке в свой буфер; сжатие и запись в отдельном потоке; временный файл не с `auto_`; atomic move; ротация после успешной записи; пропуск, если прошлый ещё пишется; `exit` дожидается. `save <slot>` и `BeControl` остаются синхронными | `work/server` | SI |
| 13 | Случайный сдвиг фазы автосейва (вместе с B3) | `work/server` | SI |
| 14 | Block snapshots по частям: курсор в `writeBlockSnapshots`, тот же период 6 с на здание, но 1/30 за интервал. Не через `blocksync=false` из мода (настройка сохраняется на диск и останется выключенной, если мод отвалится) | `headless` | VC, клиент видит то же |
| 15 | `maxSnapshotSize`: `headless ? 1100 : 800`. 800 — несжатый размер, LZ4 на float почти не жмёт; 1300 давало бы фрагментацию | `headless` | SI, VC |
| 16 | Пропуск derelict в block snapshots (они статичны). Сначала посчитать их на картах прода | `headless` | VC |
| 17 | Превью планов: пропускать только «пусто → пусто» у одного отправителя, пустое слать 2–3 раза, обновление раз в 5 с. Имеет смысл от ~15 игроков в одной команде | `headless` | VC |
| 18 | Hidden snapshot (туман): дельта + полный список раз в 1 с, полный при смене команды/входе. Вместе с B4 | `headless && rules.fog` | VC |
| 19 | PhysicsProcess: не отправлять пустые слои в executor | `headless` | SI |
| 20 | Пули: `if(!type.collides) return;` в `getCollisions`. Выигрыш крошечный, но бесплатно | `headless` | SI |
| 21 | Настройки раз в минуту: `if(Core.settings.modified()) forceSave()` | `work/server` | SI |
| 22 | `System.gc()` один раз после ~5 мин `autoPaused` — единственный способ вернуть память при SerialGC (вместо гибернации) | `work/server` | SI |

Наш `src/` (без патчей):
- `LogicExt.init` вешает клиентский `Trigger.update` и на сервере — гейт `!Vars.headless`.
- Rhino на headless (`src/mindustryX/Hooks.java:49-51`) — ленивая инициализация, но определение `X` перенести
  в создание Scripts, иначе сломаются `js` и скрипты модов. Выигрыш, скорее, несколько MB, а не 15–30.

## Эксплуатация (ноль кода)

```
-XX:+UseSerialGC -Xms64m -Xmx<по замеру> -XX:MaxNewSize=128m -XX:-UsePerfData
-XX:+ExitOnOutOfMemoryError -Xlog:gc:file=gc.log:time:filecount=3,filesize=2m
-XX:SharedArchiveFile=<app.jsa на сборку jar> -Xshare:auto
env MALLOC_ARENA_MAX=2
```

- `-Xmx`: снять live set на каждом сервере (`jcmd <pid> GC.run; jcmd <pid> GC.heap_info`),
  `Xmx ≈ max(2.5 × live, live + 400m)`. Сумма 9 × (Xmx + ~250 MB) не больше ~10 GiB.
- `ExitOnOutOfMemoryError` — только если `mkystart.sh` перезапускает процесс.
- AppCDS на JDK 17: архив через `-XX:ArchiveClassesAtExit` один раз на сборку jar в deploy-скрипте; у всех
  серверов один путь к jar (можно симлинк). Иначе архив отвергается.
- `ActiveProcessorCount` не трогать до A/B на одном сервере.
- **Не ставить**: `MinHeapFreeRatio`/`MaxHeapFreeRatio` (череда full GC при Serial), `ReservedCodeCacheSize=48m`
  (риск «CodeCache is full, compiler disabled»), `MaxMetaspaceSize` (не экономит, с ExitOnOOM — цикл падений),
  фиксированный `-Xmn`, `TieredStopAtLevel=1` (код в 1.5–2 раза медленнее).
- Проверить на проде: `/sys/kernel/mm/transparent_hugepage/enabled` (нужно `madvise`/`never`; RSS ~1.2 GiB при
  live set в сотни MB подозрителен), clocksource (`/sys/devices/system/clocksource/clocksource0/current_clocksource`),
  `config autosave` и `config autoPause`, разнести фазы автосейва, `cpu.weight` в cgroup.

## Отклонено (с причиной)

Архитектура и политики:
- Hook-точки `shouldUpdateBuilding/shouldUpdateUnit/beforeSnapshot` — API без потребителя, попадает в клиентский jar.
- Троттлинг через `enabled=false` — пропуск `updateTile` работает только при `noUpdateDisabled=true` (роутеры,
  мосты, ducts, conduit, junction, unloader); крафтеры, дрели, турели, процессоры продолжают тикать; `enabled`
  пишется в сейв; конфликт с `control enabled` из логики.
- Бюджет инструкций процессоров, капы юнитов, DAB, интервал самонаведения, `physics=false`, кап пуль, разнос таймеров,
  гибернация — меняют геймплей или рассинхронят ванильных клиентов (клиент сам считает пули и физику).
- Свой сэмплирующий профайлер — JFR/async-profiler лучше. `/lag` с `nanoTime` на здание — до 50–100 мс на тик на VPS
  без TSC; если делать, то 1 тик из N.
- Адаптивный `snapshotInterval` по умолчанию — интерполяция дёргается при каждом переключении; ручной
  `config snapshotInterval` уже есть.
- PVS по вьюпорту — ванильный клиент рисует миникарту из `Groups.unit`.
- `-javaagent`/Mixin.

Патчи без выигрыша или с риском:
- `BEST_SPEED` в deflate — свой `Deflater` течёт без переопределения `close`, экономия ~30 мс.
- Общий сериализованный мир на нескольких входящих — посередине секция игрока, туман, Adler32; выигрыш только при
  смене карты, когда все и так грузятся.
- Shutdown hook — отдельная задача (`System.exit` в `BeControl`, гонки с сохранением).
- Главный цикл 10 Гц на паузе — после №1 пауза стоит микросекунды; ломает `pvpAutoPause`, плагины.
- `updateGlobal` на headless (~1 мкс), подсчёт юнитов только вне паузы (протухший `enemies` → ложный `gameOver`).
- `ControlPathfinder.updateTile` при `clusters == null` — поле `final`, никогда не null; ещё и гонка.
- Дубль `controlPath.updateTile` — 5 других мест вызывают `Pathfinder.updateTile`.
- `getEnemy` без копии, Teams через `fill()` (не SI: `fill` принимает объекты за границей), таблица cos/sin,
  dirty-rect в Avoidance, мелкие слои физики на главном потоке.
- `ConsumeItemFilter` early-out (не эквивалентно при отрицательных количествах), пропуск `stop`-процессоров (`@counter`
  можно записать извне), арифметика draw-only программ (`@operations` читается сенсором), быстрый путь турели,
  кэш `getDrillTime`, PowerGraph без источников, soft sleep для Junction/Router/Duct, кэш Overdrive/Mend,
  `findTile` дистанция раньше предиката — всё меньше 0.1 мс на тик при ~5k блоков.
- Убирать визуальные RNG-вызовы на headless — сдвигает `Mathf.rand` (разброс оружия), ломает бит-в-бит.
- TCP `selector.wakeup()` один раз — JDK и так склеивает; кэш DiscoverHost как оптимизация — <1 мс/с.

## Открытый вопрос

Патчи 0081/0082 сейчас без гейта `headless` и работают на клиенте. Они SI, поведение клиента не меняется, но
формально нарушают правило 1. Варианты: оставить (гейт добавит непроверенную на клиенте ветку) или загейтить.

## Порядок

1. Баги B1–B3, B5 (один патч `ServerControl`) и `/tps`.
2. С прода: сейвы, live set, THP, clocksource, логи автосейва.
3. №1 (пустой `sync`), пакет Pathfinder №2–4, №6–8 (мосты и unloader).
4. IO: №9/10, 11, 12–13. Сеть: №14–15, B4/№18, B6.
5. Остальное по замерам.
