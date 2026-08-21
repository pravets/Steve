# AGENTS.md — Contributor guide for Vasyan

## Project overview

Vasyan is a Minecraft Forge 1.20.1 mod that adds autonomous AI agents ("bots") controlled via natural-language commands. It is a rebrand and continuation of the upstream `YuvDwi/Steve` project, preserved under the MIT license.

## Quick commands

```bash
./gradlew compileJava compileTestJava
./gradlew jarJar          # produces build/libs/vasyan-ai-mod-1.0.0-all.jar
./gradlew runClient       # start a development client
```

## Package layout

- `src/main/java/ru/pravets/vasyan/` — main source
  - `entity/` — Vasyan entity, spawning, persistence
  - `action/` — action executor and action implementations
  - `llm/` — LLM client, task planner, prompt builder, response parser
  - `memory/` — per-bot memory and world knowledge
  - `structure/` — procedural structure generators and NBT loader
  - `client/` — GUI overlay and key bindings
  - `command/` — Brigadier commands (`/vasyan`)
  - `config/` — Forge config spec
- `src/main/resources/assets/vasyan/` — assets and lang files
- `scripts/behavior/` — headless-server behavior tests

## Branch & PR policy

- Start every feature branch from `master`.
- One PR per task.
- Do not force-push after review has started unless agreed.
- Author commits as `Iosif Pravets <i@pravets.ru>`.

## Code style

- Java 17, 4-space indentation, max line length 120
- JavaDoc for public APIs
- PascalCase classes, camelCase methods/variables, UPPER_SNAKE_CASE constants

## LLM provider notes

The mod supports any OpenAI-compatible Chat Completions endpoint. Default provider is `opencode-go`.

## Attribution

This repository is a fork of https://github.com/YuvDwi/Steve. Upstream MIT license and credits are preserved.
