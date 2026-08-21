# Phase 0 Rebrand Plan: Vasyan -> Vasyan

Approved 2026-08-21 with Иосиф Правец.

## Goal

Create the foundation for the Vasyan -> Vasyan rebrand: a dedicated feature
branch and an exact, version-controlled rename map that drives all subsequent
mechanical renaming.

## Identity target

| Item | From | To |
|------|------|----|
| Project name | Vasyan | Vasyan |
| GitHub repo | `pravets/Vasyan` | `pravets/Vasyan` |
| Maven group | `com.vasyan.ai` | `ru.pravets.vasyan` |
| Mod ID | `vasyan` | `vasyan` |
| Class prefix | `Vasyan*` | `Vasyan*` |
| Java package | `com.vasyan.ai` | `ru.pravets.vasyan` |
| Archive base name | `vasyan-ai-mod` | `vasyan-ai-mod` |
| Root project name | `vasyan` | `vasyan` |
| Display name | `Vasyan AI Mod` | `Vasyan AI Mod` |

## Exact rename map

Stored in `scripts/rebrand-map.txt`. The map is applied mechanically in later
tasks; the longest/most specific matches are listed first. Upstream MIT
attribution to `YuvDwi/Steve` is preserved as a no-op entry.

## Phase 0 tasks

1. Create branch `feat/rebrand-vasyan-to-vasyan` from `master`.
2. Add `scripts/rebrand-map.txt` with the exact rename map.
3. Commit the map with author `Iosif Pravets <i@pravets.ru>`.
4. Verify the branch is clean.

## Constraints carried forward

- One PR = one task.
- Local VPS build constrained to `nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`.
- Full build + behavior tests in GitHub CI.
