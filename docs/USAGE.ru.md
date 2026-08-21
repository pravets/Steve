# Руководство по использованию Vasyan

Vasyan — мод для Forge 1.20.1, добавляющий в Minecraft автономных ИИ-агентов.

## Установка

1. Скачайте `vasyan-ai-mod-<version>-all.jar` из GitHub Releases.
2. Поместите JAR в папку `mods` рядом с Forge 1.20.1.
3. Запустите Minecraft.
4. Скопируйте `config/vasyan-common.toml.example` в `config/vasyan-common.toml`.
5. Добавьте ключ API и выберите провайдера.

## Спавн бота

Откройте чат и выполните:

```
/vasyan spawn Bob
```

Имена могут содержать буквы (любой скрипт), цифры и `_ - . +`. Поддерживаются кириллические имена.

## Основные команды

- `/vasyan list` — показать активных ботов.
- `/vasyan stop <имя>` — остановить все задачи бота.
- `/vasyan remove <имя>` — удалить бота из мира.
- `/vasyan tp <имя>` — телепортировать бота к вам.
- `/vasyan tell <имя> <задача>` — дать задачу на естественном языке.
- `/vasyan inv <имя>` — открыть инвентарь бота.

## Задачи на естественном языке

Нажмите **K**, чтобы открыть панель Vasyan, или используйте `/vasyan tell`:

- "mine 20 iron ore"
- "build a small house here"
- "follow me"
- "gather wood from that forest"

## Конфигурация

См. `config/vasyan-common.toml`:

```toml
[llm]
provider = "opencode-go"
baseUrl = "https://opencode.ai/zen/go/v1"
apiKey = "your-key"
model = "deepseek-v4-flash"
```

## Ссылки

- Репозиторий: https://github.com/pravets/Vasyan
- Апстрим: https://github.com/YuvDwi/Steve (MIT)
