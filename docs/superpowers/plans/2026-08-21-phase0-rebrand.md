# Phase 0 Rebrand Plan: Steve -> Vasyan

Approved 2026-08-21 with Иосиф Правец.

## Goal

Create the foundation for the Steve -> Vasyan rebrand: a dedicated feature
branch and an exact, version-controlled rename map that drives all subsequent
mechanical renaming.

## Identity target

| Item | From | To |
|------|------|----|
| Project name | Steve | Vasyan |
| GitHub repo | `pravets/Steve` | `pravets/Vasyan` |
| Maven group | `com.steve.ai` | `ru.pravets.vasyan` |
| Mod ID | `steve` | `vasyan` |
| Class prefix | `Steve*` | `Vasyan*` |
| Java package | `com.steve.ai` | `ru.pravets.vasyan` |
| Archive base name | `steve-ai-mod` | `vasyan-ai-mod` |
| Root project name | `steve` | `vasyan` |
| Display name | `Steve AI Mod` | `Vasyan AI Mod` |

## Exact rename map

Stored in `scripts/rebrand-map.txt`. The map is applied mechanically in later
tasks; the longest/most specific matches are listed first. Upstream MIT
attribution to `YuvDwi/Steve` is preserved as a no-op entry.

## Phase 0 tasks

1. Create branch `feat/rebrand-steve-to-vasyan` from `master`.
2. Add `scripts/rebrand-map.txt` with the exact rename map.
3. Commit the map with author `Iosif Pravets <i@pravets.ru>`.
4. Verify the branch is clean.

## Constraints carried forward

- One PR = one task.
- Local VPS build constrained to `nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`.
- Full build + behavior tests in GitHub CI.
