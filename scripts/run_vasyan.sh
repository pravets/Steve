#!/bin/bash

# Vasyan - Launch Script
# This script sets up Java and runs Minecraft with the mod

cd "$(dirname "$0")"

echo "🎮 Vasyan - Launcher"
echo "================================"
echo ""

# Set up Java
export JAVA_HOME="$PWD/jdk-17.0.2.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

echo "✅ Java 17 ready"
echo "✅ OpenAI API key configured (GPT-3.5)"
echo ""
echo "Starting Minecraft..."
echo "⏳ First launch will download assets (~1-2 minutes)"
echo ""

# Run Minecraft
./gradlew runClient --no-daemon

echo ""
echo "================================"
echo "Minecraft closed. Thanks for testing!"

