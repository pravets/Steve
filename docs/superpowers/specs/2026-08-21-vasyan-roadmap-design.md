# Vasyan Roadmap & Rebrand Design

Approved 2026-08-21 with Иосиф Правец.

## Identity

- **Name:** Vasyan
- **GitHub repo:** `pravets/Vasyan` -> `pravets/Vasyan`
- **Maven group:** `ru.pravets.vasyan`
- **Mod ID:** `vasyan`
- **Class prefix:** `Vasyan*` (was `Vasyan*`)
- **Package:** `ru.pravets.vasyan` (was `com.vasyan.ai`)

## Phase 0 — Rebrand

1. Branch `feat/rebrand-vasyan-to-vasyan` from `master`.
2. Rename Java package `com.vasyan.ai` -> `ru.pravets.vasyan`.
3. Rename `Vasyan*` classes -> `Vasyan*` (`VasyanMod`, `VasyanEntity`, `VasyanManager`, etc.).
4. Update mod ID, entity/item/block IDs, network channel, lang keys, `mods.toml`.
5. Update `build.gradle` group/archivesBaseName and `settings.gradle` rootProject name.
6. Rename asset folder, example config file, helper scripts.
7. Update CI workflows and behavior-test scripts.
8. Add bilingual docs (`docs/USAGE.ru.md`, `docs/USAGE.en.md`), `AGENTS.md`, GitHub PR/issue templates, `CHANGELOG.md`.
9. Preserve MIT attribution to `YuvDwi/Steve`.
10. Verify local `compileJava compileTestJava`, push PR, wait for CI green, merge, then rename GitHub repo.

## Later phases (linear order)

1. Diagnostics & visibility: "what do you see?" command + `/vasyan dump <name>`.
2. Waypoints, return, inventory, XP: named waypoints, auto-`worksite`, return-to-worksite, unload to player, bidirectional inventory, XP storage/transfer.
3. Survival crafting: 2x2 + 3x3, recipe lookup, auto-craft tools on break.
4. Mining: surface ore gathering, 2x2 spiral staircase, branch mining, configurable whitelist/blacklist.
5. Survival building: build from inventory blocks, no flying/teleport, clear site, reuse `StructureGenerators`.
6. Advanced scouting: chunk-level resource memory, numbered resource points, careful vein mining.
7. Publication: Modrinth/CurseForge release workflow.

## Constraints

- One PR = one task.
- Local VPS build: `nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`.
- Full build + behavior tests in GitHub CI.
- Living test in Minecraft for gameplay features.
