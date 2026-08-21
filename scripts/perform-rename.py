#!/usr/bin/env python3
"""
Mechanical rename Steve -> Vasyan for Java sources.
Run from repo root. Uses git mv to preserve history.
"""
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

OLD_PACKAGE = "com.steve.ai"
NEW_PACKAGE = "ru.pravets.vasyan"

# Read rebrand map
REBRAND_MAP = {}
with open(os.path.join(REPO_ROOT, "scripts", "rebrand-map.txt"), "r") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "->" in line:
            old, new = [x.strip() for x in line.split("->", 1)]
            REBRAND_MAP[old] = new

# Files to move
java_dirs = ["src/main/java", "src/test/java"]

# Step 1: git mv all files from old package tree to new package tree, preserving structure
for base in java_dirs:
    old_base = os.path.join(REPO_ROOT, base, "com", "steve", "ai")
    new_base = os.path.join(REPO_ROOT, base, "ru", "pravets", "vasyan")
    if not os.path.isdir(old_base):
        continue
    os.makedirs(new_base, exist_ok=True)
    for root, dirs, files in os.walk(old_base):
        rel = os.path.relpath(root, old_base)
        dest_dir = os.path.join(new_base, rel)
        os.makedirs(dest_dir, exist_ok=True)
        for d in dirs[:]:
            # We will create dirs as we walk; don't recurse into new tree
            pass
        for fname in files:
            src = os.path.join(root, fname)
            dst = os.path.join(dest_dir, fname)
            subprocess.run(["git", "mv", src, dst], cwd=REPO_ROOT, check=True)

# Remove empty old dirs
for base in java_dirs:
    old_base = os.path.join(REPO_ROOT, base, "com", "steve", "ai")
    if os.path.isdir(old_base):
        subprocess.run(["find", old_base, "-type", "d", "-empty", "-delete"], cwd=REPO_ROOT, check=False)
        for parent in [old_base, os.path.dirname(old_base), os.path.dirname(os.path.dirname(old_base)), os.path.dirname(os.path.dirname(os.path.dirname(old_base)))]:
            if os.path.isdir(parent) and not os.listdir(parent):
                os.rmdir(parent)

# Step 2: rename files Steve*.java -> Vasyan*.java
for base in java_dirs:
    new_base = os.path.join(REPO_ROOT, base, "ru", "pravets", "vasyan")
    for root, dirs, files in os.walk(new_base):
        for fname in files:
            if fname.startswith("Steve") and fname.endswith(".java"):
                new_fname = "Vasyan" + fname[len("Steve"):]
                src = os.path.join(root, fname)
                dst = os.path.join(root, new_fname)
                subprocess.run(["git", "mv", src, dst], cwd=REPO_ROOT, check=True)

# Step 3: rewrite content in all Java files under new package tree
# Apply replacements in order: longest identifiers first to avoid partial matches
java_files = []
for base in java_dirs:
    new_base = os.path.join(REPO_ROOT, base, "ru", "pravets", "vasyan")
    if os.path.isdir(new_base):
        for root, _, files in os.walk(new_base):
            for fname in files:
                if fname.endswith(".java"):
                    java_files.append(os.path.join(root, fname))

# Sort replacements by length desc
replacements = sorted(REBRAND_MAP.items(), key=lambda x: len(x[0]), reverse=True)

for path in java_files:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    # Package/import declaration
    content = content.replace(f"package {OLD_PACKAGE}", f"package {NEW_PACKAGE}")
    content = content.replace(f"import {OLD_PACKAGE}", f"import {NEW_PACKAGE}")
    # Identifier references
    for old, new in replacements:
        # Only replace whole identifiers
        content = re.sub(rf"\b{re.escape(old)}\b", new, content)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

print(f"Renamed {len(java_files)} Java files.")
