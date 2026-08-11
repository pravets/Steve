# ТЗ: поддержка локальных и альтернативных LLM-провайдеров

**Дата:** 2026-08-11
**Ветка:** master (форк pravets/Steve, апстрим YuvDwi/Steve — только в main)
**Статус:** согласовано, этап 1 в реализации

## Контекст

Конечная цель проекта — порт на GTNH (Minecraft 1.7.10) и обучение агента
работе с GregTech. Перед портом необходимо проверить работоспособность мода
на текущей версии (1.20.1) и перевести LLM-слой на провайдеров, которыми
пользуется владелец:

- **opencode go** — подписка ($5 первый месяц, далее $10/мес), OpenAI-совместимый
  эндпоинт `https://opencode.ai/zen/go/v1/chat/completions`, ключ из OpenCode Zen.
  Модели (chat/completions): `deepseek-v4-flash`, `deepseek-v4-pro`, `grok-4.5`,
  `glm-5.2/5.1`, `kimi-k3`, `kimi-k2.7-code`, `mimo-v2.5`, `hy3`.
  (Часть моделей Go — только Anthropic-формат `/messages`, в этап 1 не входят.)
- **ollama** — локально, `http://localhost:11434/v1/chat/completions`, ключ не нужен
- **LM Studio** — локально, `http://localhost:1234/v1/chat/completions`, ключ не нужен

Все три — OpenAI-совместимый Chat Completions API → один универсальный клиент.

## Проблемы текущей реализации (ревизия)

1. Модели захардкожены в 4 из 6 клиентов (GroqClient, GeminiClient — модель в URL,
   оба async-клиента).
2. Один ключ `OPENAI_API_KEY` на все провайдеры.
3. Нет настройки baseUrl — локальные эндпоинты подключить нельзя.
4. Дублирование sync/async (6 классов вместо одного).
5. Gemini — не-OpenAI формат (contents/parts, ключ в query string).
6. Fallback-ответы LLMFallbackHandler не совпадают с форматом ResponseParser
   (`thoughts/target/size` вместо `reasoning/plan/tasks/parameters`) → при сбое
   LLM агент молча ничего не делает (задачи не проходят validateTask).
7. Нет JSON mode — парсер чинит невалидный JSON костылями.
8. Groq sync игнорирует конфиг (max_tokens=500, temperature=0.7 хардкод).

## Этап 1 — реализация

### 1. Конфиг (SteveConfig), секция `llm`

```
provider = "ollama"      # openai | groq | gemini | ollama | lmstudio | opencode-go | custom
baseUrl = ""             # пусто = дефолт пресета; для custom — обязателен
apiKey = ""              # пусто для ollama/lmstudio; для openai/groq/gemini/opencode-go — ключ
model = ""               # пусто = дефолт пресета
jsonMode = true          # response_format: {"type":"json_object"}; выключить, если провайдер не поддерживает
maxTokens = 8000
temperature = 0.7
timeoutSeconds = 60
```

Пресеты (дефолты в коде):

| provider | baseUrl | model по умолчанию | ключ |
|----------|---------|--------------------|------|
| openai | https://api.openai.com/v1 | gpt-4o-mini | да |
| groq | https://api.groq.com/openai/v1 | llama-3.1-8b-instant | да |
| gemini | https://generativelanguage.googleapis.com/v1beta/openai | gemini-2.5-flash | да |
| ollama | http://localhost:11434/v1 | llama3.1 | нет |
| lmstudio | http://localhost:1234/v1 | (пусто — что загружено) | нет |
| opencode-go | https://opencode.ai/zen/go/v1 | deepseek-v4-flash | да |
| custom | — (обязателен) | — (обязателен) | нет |

### 2. Единый клиент `OpenAICompatibleClient` (com.steve.ai.llm.async)

- Реализует `AsyncLLMClient` (async-ядро, как прежний AsyncOpenAIClient)
- Синхронный метод `sendRequest(systemPrompt, userPrompt)` поверх async-ядра
  (для deprecated-пути TaskPlanner.planTasks)
- Параметры: providerId, baseUrl, apiKey (nullable), model (nullable), maxTokens,
  temperature, jsonMode, timeoutSeconds
- `response_format: {"type":"json_object"}` при jsonMode
- `Authorization: Bearer <key>` только если ключ задан
- `model` в запросе — только если задан (для LM Studio с пустой моделью)
- `checkHealth()` — GET `{baseUrl}/models`, таймаут 3 сек
- Маппинг ошибок в LLMException (как в AsyncOpenAIClient)

### 3. Удаление дублей

Удалить: OpenAIClient, GroqClient, GeminiClient, AsyncOpenAIClient,
AsyncGroqClient, AsyncGeminiClient. TaskPlanner держит один
OpenAICompatibleClient, обёрнутый в ResilientLLMClient.

### 4. TaskPlanner

- Только async-путь (planTasksAsync); sync planTasks остаётся deprecated-обёрткой
- Провайдер/модель/ключ — из конфига, а не хардкод
- Fallback между провайдерами не делаем (resilience-слой + паттерн-fallback достаточны)

### 5. Починка LLMFallbackHandler

Формат ответов → `{"reasoning","plan","tasks":[{"action","parameters"}]}`,
параметры строго под validateTask и экшены:

| паттерн | action | parameters |
|---------|--------|------------|
| mine/dig/gather ore | mine | block=iron_ore, quantity=10 |
| build house/structure | build | structure=house, blocks=[oak_planks,cobblestone], dimensions=[9,6,9] |
| attack/kill/monster | attack | target=hostile |
| follow/come | follow | player=USE_NEARBY_PLAYER_NAME |
| go to/move | follow | player=USE_NEARBY_PLAYER_NAME |
| place torch/block | place | block=torch, x/y/z=0 |
| stop/wait | follow | player=USE_NEARBY_PLAYER_NAME (безопасный дефолт; wait-экшена нет) |
| default | follow | player=USE_NEARBY_PLAYER_NAME |

### 6. Диагностика `/steve providers`

Список провайдеров: имя, baseUrl, модель, наличие ключа, health активного
(GET /models, таймаут 3 сек; health остальных — без проверки, чтобы не
тормозить серверный поток).

### 7. Тесты

- FallbackHandlerTest: все паттерны → JSON парсится ResponseParser'ом,
  action ∈ {mine, build, attack, follow, place}, параметры валидны
- OpenAICompatibleClientTest: корректность тела запроса (jsonMode, отсутствие
  model при пустой, отсутствие Authorization при пустом ключе)

## Этап 2 — проверка на 1.20.1 (на машине владельца)

1. Собрать мод, положить в mods
2. Запустить ollama (или использовать opencode go)
3. `/steve spawn Bob`, команда «mine some iron», убедиться в цепочке
   LLM → план → действия

## Этап 3 — GTNH (отдельное ТЗ)

Порт на Forge 1.7.10 + GregTech-интеграция (рецепты, EU-сеть, мультиблоки).
Ветка gtnh отпочковывается от master после фиксации этапов 1–2.

## Не входит в этап 1

- Anthropic/Claude (не требуется)
- Streaming, vision/multimodal
- Пер-провайдерные секции конфига (активен всегда один провайдер;
  YAGNI — добавим, если понадобится кросс-провайдерный fallback)
