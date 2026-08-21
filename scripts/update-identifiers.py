#!/usr/bin/env python3
"""
Task 3: Update mod metadata, resource identifiers, command literal, and lang keys.
Run from repo root.
"""
import os
import re
import shutil
import subprocess

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def write(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

def patch_file(path, replacements):
    if not os.path.exists(path):
        print(f"SKIP (not found): {path}")
        return
    content = read(path)
    for old, new in replacements:
        content = content.replace(old, new)
    write(path, content)
    print(f"PATCHED: {path}")

# 1. VasyanMod.java
patch_file(
    os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/VasyanMod.java"),
    [
        ('public static final String MODID = "steve";', 'public static final String MODID = "vasyan";'),
        ('ENTITIES.register("steve",', 'ENTITIES.register("vasyan",'),
        ('.build("steve")', '.build("vasyan")'),
    ],
)

# 2. ClientEventHandler.java
patch_file(
    os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/client/ClientEventHandler.java"),
    [
        ('@Mod.EventBusSubscriber(modid = "steve",', '@Mod.EventBusSubscriber(modid = VasyanMod.MODID,'),
    ],
)
# ensure import
path = os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/client/ClientEventHandler.java")
content = read(path)
if "import ru.pravets.vasyan.VasyanMod;" not in content:
    content = "import ru.pravets.vasyan.VasyanMod;\n" + content
    write(path, content)

# 3. VoiceCommandHandler.java
patch_file(
    os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/voice/VoiceCommandHandler.java"),
    [
        ('@Mod.EventBusSubscriber(modid = "steve",', '@Mod.EventBusSubscriber(modid = VasyanMod.MODID,'),
    ],
)
path = os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/voice/VoiceCommandHandler.java")
content = read(path)
if "import ru.pravets.vasyan.VasyanMod;" not in content:
    # insert after package line
    content = re.sub(r'(package .*?;\n)', r'\1import ru.pravets.vasyan.VasyanMod;\n', content)
    write(path, content)

# 4. StructureTemplateLoader.java
patch_file(
    os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/structure/StructureTemplateLoader.java"),
    [
        ('new ResourceLocation("steve", structureName)', 'new ResourceLocation(VasyanMod.MODID, structureName)'),
    ],
)
path = os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/structure/StructureTemplateLoader.java")
content = read(path)
if "import ru.pravets.vasyan.VasyanMod;" not in content:
    content = re.sub(r'(package .*?;\n)', r'\1import ru.pravets.vasyan.VasyanMod;\n', content)
    write(path, content)

# 5. VasyanCommands.java
patch_file(
    os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/command/VasyanCommands.java"),
    [
        ('dispatcher.register(Commands.literal("steve")', 'dispatcher.register(Commands.literal("vasyan")'),
        ('/steve ', '/vasyan '),
        ('/steve spawn', '/vasyan spawn'),
        ('/steve tp', '/vasyan tp'),
        ('/steve remove', '/vasyan remove'),
        ('/steve list', '/vasyan list'),
        ('/steve stop', '/vasyan stop'),
        ('/steve debug', '/vasyan debug'),
        ('/steve inv', '/vasyan inv'),
        ('Use /steve spawn', 'Use /vasyan spawn'),
        ('No Steves spawned. Use /steve spawn', 'No Vasyans spawned. Use /vasyan spawn'),
    ],
)

# 6. KeyBindings.java
patch_file(
    os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/client/KeyBindings.java"),
    [
        ('"key.categories.steve"', '"key.categories.vasyan"'),
        ('"key.steve.', '"key.vasyan.'),
    ],
)

# 7. VasyanNameArgumentType.java
patch_file(
    os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/command/VasyanNameArgumentType.java"),
    [
        ('"argument.steve.vasyan_name.invalid"', '"argument.vasyan.vasyan_name.invalid"'),
    ],
)

# 8. MultipartSttClient.java boundary
patch_file(
    os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/voice/MultipartSttClient.java"),
    [
        ('"steve" + UUID.randomUUID()', '"vasyan" + UUID.randomUUID()'),
    ],
)

# 9. NameMatcher nicknames
path = os.path.join(REPO_ROOT, "src/main/java/ru/pravets/vasyan/chat/NameMatcher.java")
content = read(path)
content = content.replace('Map.entry("стиви", List.of("steve", "stevie"))', 'Map.entry("васян", List.of("vasyan", "vasyann"))')
content = content.replace('Map.entry("стеви", List.of("steve", "stevie"))', 'Map.entry("вася", List.of("vasyan", "vasyann"))')
write(path, content)

# 10. mods.toml
path = os.path.join(REPO_ROOT, "src/main/resources/META-INF/mods.toml")
content = read(path)
content = content.replace('modId="steve"', 'modId="vasyan"')
content = content.replace('displayName="Steve AI Mod"', 'displayName="Vasyan"')
content = content.replace(
    'AI-controlled Steve entities that can follow natural language commands,',
    'AI-controlled Vasyan entities that follow natural language commands,'
)
content = content.replace('and behave like real players using OpenAI.', 'and behave like real players.')
content = content.replace('authors="Steve AI Team"', 'authors="Iosif Pravets"')
content = content.replace('displayURL="https://github.com/yourusername/steve-ai-mod"', 'displayURL="https://github.com/pravets/Vasyan"')
content = content.replace('[[dependencies.steve]]', '[[dependencies.vasyan]]')
write(path, content)

# 11. pack.mcmeta
patch_file(
    os.path.join(REPO_ROOT, "src/main/resources/pack.mcmeta"),
    [
        ('"Steve AI Mod Resources"', '"Vasyan Resources"'),
    ],
)

# 12. lang file: move dir and update keys
old_lang_dir = os.path.join(REPO_ROOT, "src/main/resources/assets/steve")
new_lang_dir = os.path.join(REPO_ROOT, "src/main/resources/assets/vasyan")
if os.path.exists(old_lang_dir):
    shutil.move(old_lang_dir, new_lang_dir)
    print(f"MOVED: {old_lang_dir} -> {new_lang_dir}")
lang_file = os.path.join(new_lang_dir, "lang/en_us.json")
if os.path.exists(lang_file):
    content = read(lang_file)
    content = content.replace('"key.categories.steve"', '"key.categories.vasyan"')
    content = content.replace('"key.steve.toggle_gui"', '"key.vasyan.toggle_gui"')
    content = content.replace('"argument.steve.steve_name.invalid"', '"argument.vasyan.vasyan_name.invalid"')
    content = content.replace('"Steve AI"', '"Vasyan"')
    content = content.replace('"Toggle Steve Panel"', '"Toggle Vasyan Panel"')
    content = content.replace('"Invalid Steve name', '"Invalid Vasyan name')
    write(lang_file, content)
    print(f"PATCHED: {lang_file}")

# 13. example config rename
old_config = os.path.join(REPO_ROOT, "config/steve-common.toml.example")
new_config = os.path.join(REPO_ROOT, "config/vasyan-common.toml.example")
if os.path.exists(old_config):
    shutil.move(old_config, new_config)
    content = read(new_config)
    content = content.replace("# Steve AI common config", "# Vasyan common config")
    write(new_config, content)
    print(f"MOVED/PATCHED: {old_config} -> {new_config}")

print("Task 3 identifier update done.")
