# Vasyan Roadmap

Approved 2026-08-21 with Иосиф Правец.

## Identity

- **Name:** Vasyan
- **GitHub repo:** `pravets/Vasyan`
- **Maven group:** `ru.pravets.vasyan`
- **Mod ID:** `vasyan`
- **Class prefix:** `Vasyan*`
- **Package:** `ru.pravets.vasyan`
- **Artifact:** `vasyan-ai-mod`
- **Display name:** Vasyan AI mod

## Constraints

- **Version:** 1.20.1; GTNH-port отложен до стабилизации текущей версии.
- **Definition of Done:** зелёный CI + unit/behavior тесты + live Minecraft test.
- **One PR = one task.**
- **Branch from `master` only.**
- **Local VPS build:** `nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`.
- **Full build + behavior tests:** GitHub CI.
- **Upstream MIT attribution to `YuvDwi/Steve` is preserved.**

## Phases

### Phase 0 — Rebrand ✅

1. Branch `feat/rebrand-steve-to-vasyan` from `master`.
2. Rename Java package `com.steve.ai` → `ru.pravets.vasyan`.
3. Rename `Steve*` classes → `Vasyan*` (`VasyanMod`, `VasyanEntity`, `VasyanManager`, etc.).
4. Update mod ID, entity/item/block IDs, network channel, lang keys, `mods.toml`.
5. Update `build.gradle` group/archivesBaseName and `settings.gradle` rootProject name.
6. Rename asset folder, example config file, helper scripts.
7. Update CI workflows and behavior-test scripts.
8. Add bilingual docs (`docs/USAGE.ru.md`, `docs/USAGE.en.md`), `AGENTS.md`, GitHub PR/issue templates.
9. Preserve MIT attribution to `YuvDwi/Steve`.
10. Verify local `compileJava compileTestJava`, push PR, wait for green CI, merge, rename GitHub repo to `pravets/Vasyan`.

### Phase 0.5 — Respawn bugfix

- Боты сейчас респавнятся рядом с игроком после релога вместо восстановления сохранённой позиции.
- Сохранять позицию/инвентарь/память в NBT и `adopt` из мира при логине.
- Результат: после рестарта бот остаётся там, где был.

### Phase 1 — Diagnostics & visibility

- Команда `/vasyan dump <name>` — сохраняет полное состояние бота в `logs/vasyan-dumps/<bot>-<timestamp>.json`.
  - По умолчанию включает ответ LLM.
  - Промпт — только по флагу `with-prompt`.
- Команда «что ты видишь?» — запросить краткое описание окружения от бота.

### Phase 2 — Waypoints, return, inventory, XP

- **Waypoints:** именованные NBT-персистентные точки + авто-`worksite`; формат с заделом на будущий экспорт в GTNH teleport points.
- **Return:** бот возвращается к `worksite` по команде.
- **Unload MVP:** бот подлетает к игроку, чтобы игрок забрал ресурсы вручную. Сундуки/ME — позже.
- **Bidirectional inventory:** бот может получать инструменты/материалы от игрока (read-only отменяется).
- **XP:** бот накапливает XP и по команде спавнит Experience Orb рядом с игроком.

### Phase 3 — Survival crafting

- 2×2 и 3×3 крафт.
- Поиск рецепта по названию/ингредиентам.
- Авто-крафт инструмента при поломке и по команде.
- Начать с инструментов и сундуков.

### Phase 4 — Mining

- Поверхностная добыча руды в горах.
- Спуск 2×2 винтовой лестницей.
- Branch mining с конфигурируемым шагом/высотой.
- Whitelist/blacklist руд (конфиг + команда).

### Phase 5 — Survival building

- Строительство из блоков в инвентаре бота.
- Без полёта/телепорта — ходьба, прыжки, размещение блоков.
- Разбор площадки перед постройкой.
- Повторное использование `StructureGenerators` (дома, башни, замки).

### Phase 6 — Advanced scouting

- Память ресурсов на уровне чанков.
- Нумерованные resource points.
- Аккуратный vein mining.

### Phase 7 — Publication

- Резервирование slug `vasyan` на Modrinth и CurseForge.
- Release workflow в GitHub Actions.
- Публикация релиза.

## Multi-agent

- Дальний бэклог, не в текущих фазах.

## Open questions / resolved

- **Full rebrand scope:** mod ID, Maven group `ru.pravets.vasyan`, Java packages, entity ID, lang keys, network channel, config sections, GitHub repo rename — да.
- **Dump location:** `logs/vasyan-dumps/<bot>-<timestamp>.json`.
- **Dump content:** LLM response по умолчанию, prompt только по флагу `with-prompt`.
- **Mining mode:** branch mining + поверхность + 2×2 spiral down.
- **Unload MVP:** подлёт к игроку для ручного забора.
- **XP transfer:** Experience Orb рядом с игроком.
