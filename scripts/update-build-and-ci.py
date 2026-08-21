#!/usr/bin/env python3
"""
Task 4: Update build files, scripts, and CI.
Run from repo root.
"""
import os
import shutil

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def write(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

# settings.gradle
path = os.path.join(REPO_ROOT, "settings.gradle")
content = read(path)
content = content.replace("rootProject.name = 'steve'", "rootProject.name = 'vasyan'")
write(path, content)
print(f"PATCHED: {path}")

# build.gradle
path = os.path.join(REPO_ROOT, "build.gradle")
content = read(path)
content = content.replace("group = 'com.steve.ai'", "group = 'ru.pravets.vasyan'")
content = content.replace("archivesName = 'steve-ai-mod'", "archivesName = 'vasyan'")
content = content.replace("Specification-Title': 'Steve AI Mod'", "Specification-Title': 'Vasyan'")
content = content.replace("Specification-Vendor': 'Steve AI'", "Specification-Vendor': 'Iosif Pravets'")
content = content.replace("Implementation-Vendor': 'Steve AI'", "Implementation-Vendor': 'Iosif Pravets'")
content = content.replace("mods {\n                steve {", "mods {\n                vasyan {")
write(path, content)
print(f"PATCHED: {path}")

# run_steve.sh -> run_vasyan.sh
old = os.path.join(REPO_ROOT, "scripts/run_steve.sh")
new = os.path.join(REPO_ROOT, "scripts/run_vasyan.sh")
if os.path.exists(old):
    shutil.move(old, new)
    content = read(new)
    content = content.replace("Steve AI Mod", "Vasyan")
    content = content.replace("🎮 Steve AI Mod - Launcher", "🎮 Vasyan - Launcher")
    write(new, content)
    print(f"RENAMED/PATCHED: {old} -> {new}")

# behavior setup_server.sh
path = os.path.join(REPO_ROOT, "scripts/behavior/setup_server.sh")
content = read(path)
content = content.replace('rcon.password=steve_test', 'rcon.password=vasyan_test')
content = content.replace('motd=steve behavior test', 'motd=vasyan behavior test')
content = content.replace('cat > config/steve-common.toml', 'cat > config/vasyan-common.toml')
write(path, content)
print(f"PATCHED: {path}")

# behavior_test.py
path = os.path.join(REPO_ROOT, "scripts/behavior/behavior_test.py")
content = read(path)
content = content.replace('RCON_PASSWORD = "steve_test"', 'RCON_PASSWORD = "vasyan_test"')
content = content.replace('"Steve AI Mod - Launch Script"', '"Vasyan - Launch Script"')
# Command literal /steve -> /vasyan (only when used as command, not in text)
content = content.replace('rcon.command(f"steve ', 'rcon.command(f"vasyan ')
content = content.replace('rcon.command("steve ', 'rcon.command("vasyan ')
content = content.replace('rcon.command("stop"', 'rcon.command("stop"')  # no change
content = content.replace('list_resp = rcon.command("steve list")', 'list_resp = rcon.command("vasyan list")')
content = content.replace('"  steve list after restart:', '"  vasyan list after restart:')
content = content.replace('"  steve list after restart:', '"  vasyan list after restart:')
content = content.replace("steve list after restart", "vasyan list after restart")
content = content.replace("steve spawn", "vasyan spawn")
content = content.replace("steve tell", "vasyan tell")
content = content.replace("steve remove", "vasyan remove")
content = content.replace("steve stop", "vasyan stop")
content = content.replace('SteveNameArgumentType', 'VasyanNameArgumentType')
content = content.replace('argument.steve.steve_name.invalid', 'argument.vasyan.vasyan_name.invalid')
content = content.replace('Spawned Steve', 'Spawned Vasyan')
content = content.replace('spawned a Steve', 'spawned a Vasyan')
content = content.replace('Steve name already exists', 'Vasyan name already exists')
content = content.replace('a Steve teleported', 'a Vasyan teleported')
content = content.replace('Steve worked in', 'Vasyan worked in')
content = content.replace('path to steve mod jar', 'path to vasyan mod jar')
content = content.replace('steve-ai-mod-', 'vasyan-')
content = content.replace('build/libs/steve-ai-mod-', 'build/libs/vasyan-')
write(path, content)
print(f"PATCHED: {path}")

# build.yml
path = os.path.join(REPO_ROOT, ".github/workflows/build.yml")
content = read(path)
content = content.replace('steve-ai-mod-${VERSION}-all.jar', 'vasyan-${VERSION}-all.jar')
content = content.replace('steve-ai-mod-${VERSION}.jar', 'vasyan-${VERSION}.jar')
content = content.replace('name: steve-ai-mod', 'name: vasyan')
content = content.replace('Steve AI Mod', 'Vasyan')
write(path, content)
print(f"PATCHED: {path}")

# behavior-tests.yml
path = os.path.join(REPO_ROOT, ".github/workflows/behavior-tests.yml")
content = read(path)
content = content.replace('steve-ai-mod-1.0.0-all.jar', 'vasyan-1.0.0-all.jar')
content = content.replace('build/libs/steve-ai-mod-', 'build/libs/vasyan-')
content = content.replace('a Steve teleported', 'a Vasyan teleported')
content = content.replace('Steve teleported far away', 'Vasyan teleported far away')
write(path, content)
print(f"PATCHED: {path}")

# README mention (Task 5, but do minimal if needed)
readme = os.path.join(REPO_ROOT, "README.md")
if os.path.exists(readme):
    content = read(readme)
    if "Steve AI Mod" in content or "steve-ai-mod" in content:
        print(f"NOTE: {readme} still has Steve branding; Task 5 will update it fully")

print("Task 4 build/CI/script update done.")
