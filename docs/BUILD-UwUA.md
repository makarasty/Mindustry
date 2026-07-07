# MindustryX — сборка кастомной версии `UwUA`

Документация процесса: какие правки вносятся вручную перед компиляцией сервера
MindustryX и как собрать актуальную версию.

> Обновлено: 2026-07-07. upstreamBuild `158` / minGameVersion `158.1`.

---

## 0. TL;DR — автоматическая сборка

Весь процесс автоматизирован скриптом. Собрать самую свежую версию:

```powershell
# из корня репо, PowerShell:
powershell -ExecutionPolicy Bypass -File scripts\build-uwua.ps1 -Pull
```
или просто **двойной клик** по `scripts\build-uwua.bat` (он вызывает `.ps1 -Pull`).

Что делает [`scripts/build-uwua.ps1`](../scripts/build-uwua.ps1):
1. `-Pull` — `git pull --ff-only` parent до `origin/main` (сбросив спурьёзный CRLF `mod.hjson`);
2. reset+патч **Arc** (`patches/arc/*`) → group `com.github.TinyLake.MindustryX`;
3. reset+патч **work** (`patches/picked/*`, `patches/client/*`);
4. применяет правки `NetworkIO.java` (`UwUA` + `300`) — **с проверкой якорей**:
   если upstream переписал `writeServerData()` и якоря пропали → скрипт
   останавливается с «наши изменения невозможны»;
5. чистая пересборка `:core:jar server:dist --rerun-tasks --no-build-cache`;
6. проверяет, что `UwUA` реально попал в `NetworkIO.class` внутри jar.

Пины submodule читаются динамически (`git ls-tree HEAD work|Arc`), поэтому
скрипт переживает bump пинов upstream.

Флаги: `-Pull` (тянуть latest), `-SkipPatch` (не перепатчивать, только правка+сборка),
`-NoClean` (без `gradlew clean`).

Ручной бэкап правки: [`patches/custom/networkio-uwua.diff`](../patches/custom/networkio-uwua.diff).

Ниже — как всё устроено вручную (для отладки, если скрипт упал).

---

## 1. Структура репозитория

Корень (`MindustryX/`) — это **обёртка-патчер**, а не сам исходник игры:

| Путь | Назначение |
|------|-----------|
| `work/` | git-submodule с реальным исходником MindustryX (Anuken/Mindustry + клиент X). Здесь идёт компиляция. |
| `Arc/` | git-submodule движка Arc. |
| `patches/arc/*` | патчи поверх Arc. |
| `patches/picked/*` | отобранные (cherry-picked) патчи поверх базового Mindustry. |
| `patches/client/*` | патчи клиента MindustryX. |
| `scripts/applyPatches.sh` | применяет все патчи на submodules через `git am`. |
| `buildPlugins/` | сборочные gradle-плагины (`sourceSets["main"].java.srcDirs("plugins")`). |

Текущее состояние `work/`: detached HEAD на `401efdf641 F: Mod.mainX` —
это уже **полностью пропатченный** tip MindustryX (+77 коммитов над базовым
pin submodule `05b2ecd4`).

---

## 2. Ручные правки перед компиляцией

Единственный файл, который правится вручную:
**`work/core/src/mindustry/net/NetworkIO.java`**, метод `writeServerData()`.

### Правка A — версия/тег сервера (строка 131)

```java
buffer.putInt(Version.build);
writeString(buffer, "UwUA");        // было: "MindustryX"
```

Эта строка пишется в server-data пакет и показывается в списке серверов как
метка версии/клиента. `"UwUA"` — кастомный идентификатор сборки.

### Правка B — длина описания сервера (строка 136)

```java
writeString(buffer, description, 300);   // было: description, 100
```

Поднимает лимит длины `description` (поле `Config.desc`) со **100 до 300**.

> ⚠️ **Важно про порядок.** `scripts/applyPatches.sh` делает `git reset --hard`
> (`git_reset`) на submodules. Если запустить его — **обе правки сотрутся**.
> Правильный порядок:
> 1. `./scripts/applyPatches.sh` (при необходимости обновить патчи)
> 2. **потом** внести правки A и B в `NetworkIO.java`
> 3. собрать
>
> Сейчас правки уже внесены (uncommitted в `work/`), applyPatches повторно
> запускать **не нужно**, если не тянется новый upstream.

### ⚠️ Известные подводные камни правок

`writeString` кодирует длину строки **одним байтом**:

```java
private static void writeString(ByteBuffer buffer, String string, int maxlen){
    byte[] bytes = string.getBytes(charset);
    if(bytes.length > maxlen) bytes = Arrays.copyOfRange(bytes, 0, maxlen);
    buffer.put((byte)bytes.length);   // ← 1 байт, максимум 255
    buffer.put(bytes);
}
// readString: short length = (short)(buffer.get() & 0xff);  // тоже максимум 255
```

1. **Реальный потолок описания = 255, а не 300.** Если `description` в байтах
   256–300 — `(byte)bytes.length` переполнится (256 → 0, 257 → 1 …), и
   `readString` на клиенте прочитает мусорную длину → битый вывод. Полезный
   прирост от правки: фактически **100 → 255**. Значение `300` безопасно только
   пока описание ≤ 255 байт (кириллица в UTF-8 = 2 байта/символ → ≤ ~127 символов).

2. **`ByteBuffer.allocate(500)` (строка 123) может переполниться.** Худший
   случай (макс. name 100 + map 64 + desc 300 + modeName 50 + служебные) ≈ 542
   байта > 500 → `BufferOverflowException`. На практике почти недостижимо, но
   если поднимать desc — стоит поднять и `allocate(...)` до ~600.

---

## 3. Конфигурация версии

`work/core/build.gradle.kts` — задача `writeVersion`, пишет
`assets/version.properties`:

```kotlin
val writeVersion by registering(WriteProperties::class) {
    setOutputFile(file("assets/version.properties"))
    property("type",     findProperty("versionType")     ?: "official")
    property("modifier", findProperty("versionModifier") ?: "release")
    property("number",   '7')
    property("build",    findProperty("upstreamBuild")    ?: "custom build")
}
```

`work/gradle.properties`:
```properties
upstreamBuild=158    # → отображается как "v158"
```

`assets/mod.hjson` (лоадер мода, корень репо):
```hjson
version: "1.0-dev"
minGameVersion: "158"
```
> `assets/mod.hjson` числится изменённым в git только из-за окончаний строк
> (LF→CRLF), реальных правок содержимого нет.

---

## 3.5. ⚠️ КРИТИЧНО: Arc должен быть пропатчен (иначе jitpack 404)

`work` резолвит Arc через composite build `includeBuild("../Arc")`. Зависимости
задаются функцией `arcModule` (`work/build.gradle`):

```groovy
arcModule = { String name ->
    if(localArc) return "com.github.TinyLake.MindustryX:$name:$arcHash"  // arcHash = v158
    else         return "com.github.Anuken.Arc:$name:$arcHash"
}
```

Composite-build подстановка сработает, только если локальный `../Arc`
**публикует группу `com.github.TinyLake.MindustryX`**. Ванильный Arc (pin
submodule `0e5679dc`) имеет `group = 'com.github.Anuken'` → группы не совпадают
→ подстановка не срабатывает → gradle лезет на jitpack за
`com.github.TinyLake.MindustryX:arc-core:v158` и `:packer:v158`, которых там
**нет (HTTP 404)** → сборка падает:

```
Could not resolve com.github.TinyLake.MindustryX:packer:v158
Could not resolve com.github.TinyLake.MindustryX:arc-core:v158
```

Группу меняет патч **`patches/arc/0008-H.BUILD-MindustryX-group-for-publish.patch`**
(`com.github.Anuken` → `com.github.TinyLake.MindustryX`).

**Значит: перед сборкой Arc обязан быть пропатчен.** Достаточно применить только
arc-патчи (work при этом не трогается, ручные правки NetworkIO целы):

```bash
cd Arc
export GIT_COMMITTER_DATE='2024-01-01 00:00:00 +0000'
git am --no-gpg-sign -3 ../patches/arc/*.patch
grep -n "group = 'com.github" build.gradle   # → com.github.TinyLake.MindustryX
```

> Почему всплыло сейчас: `:core:jar` зависит от `:tools:pack`, а buildscript
> проекта `:tools` тянет `arcModule(":extensions:packer")` + `arcModule(":arc-core")`.
> `upstreamBuild` подняли до `158`, версии v158 на jitpack нет, локальный Arc был
> не пропатчен → 404. Прошлые сборки использовали кэш старых версий.

---

## 4. Окружение сборки

| Компонент | Значение |
|-----------|----------|
| JDK | GraalVM 17 — `D:\java\graalvm-jdk-17.0.12` |
| Gradle | wrapper 9.3.1 (`work/gradle/wrapper/gradle-wrapper.properties`) |
| Целевой артефакт | `work/server/build/libs/server-release.jar` |

Устаревшее из старых заметок: `gradle 7.6.1` / `8.7` — **не актуально**,
сейчас wrapper 9.3.1. Команды `./gradlew wrapper --gradle-version ...` больше
не нужны.

---

## 5. Пошаговая сборка

PowerShell (Windows):

```powershell
# 1. env: GraalVM 17
$env:JAVA_HOME = "D:\java\graalvm-jdk-17.0.12"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH

# 2. (опционально) применить патчи — СОТРЁТ ручные правки NetworkIO!
#    bash ./scripts/applyPatches.sh    # запускать только при обновлении upstream

# 3. правки A + B в work/core/src/mindustry/net/NetworkIO.java (если сбросились)

# 4. собрать server
Set-Location "C:\Users\mky\Desktop\LiveProjects\MindustryX\work"
.\gradlew.bat server:dist
```

Чистая пересборка (если кэш мешает):
```powershell
.\gradlew.bat --stop
.\gradlew.bat clean
.\gradlew.bat :core:jar server:dist --no-build-cache --rerun-tasks
```

> ⚠️ **Инкрементальная сборка не видит ручную правку.** Обычный
> `gradlew.bat server:dist` может показать все таски `UP-TO-DATE` и НЕ пересобрать
> jar, даже если `NetworkIO.java` изменён (несовпадение mtime / build-cache).
> Результат — старый jar без `UwUA`. Поэтому для гарантии всегда чистая
> пересборка через `--rerun-tasks --no-build-cache` (шаг 4 выше).

### Результат
```
work\server\build\libs\server-release.jar
```
Запуск: `java -jar server-release.jar`

### Проверка, что jar содержит правку
jar — это zip, классы сжаты DEFLATE → `grep` по сырому jar **не работает**
(даёт 0 даже когда правка есть). Извлечь класс и грепнуть его:
```bash
cd /tmp && unzip -o -q <path>/server-release.jar "mindustry/net/NetworkIO.class"
grep -c "UwUA" mindustry/net/NetworkIO.class   # 1 = OK
```
Если `0` — правка не попала, пересобрать с `--rerun-tasks`.

---

## 6. Чек-лист

- [ ] GraalVM 17 в `JAVA_HOME`
- [ ] **Arc пропатчен** → `Arc/build.gradle` group = `com.github.TinyLake.MindustryX` (иначе jitpack 404)
- [ ] `NetworkIO.java:131` = `writeString(buffer, "UwUA");`
- [ ] `NetworkIO.java:136` = `writeString(buffer, description, 300);`
- [ ] applyPatches **не** запускался после ручных правок (иначе повторить правки)
- [ ] сборка через `--rerun-tasks --no-build-cache` (инкрементальная пропускает правку)
- [ ] `server-release.jar` пересобран (свежая дата файла)
- [ ] `unzip -p ...jar mindustry/net/NetworkIO.class | grep -c UwUA` = 1 (grep по сырому jar не работает — zip)
