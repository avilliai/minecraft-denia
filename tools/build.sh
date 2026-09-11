#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Pasithea / mc-girlfriend — compile-verify the current code.
#
# Runs the Fabric/Loom build with the bundled JDK 21 (set via gradle.properties
# org.gradle.java.home) and prints a focused error summary at the end. This is a
# real build (writes build/ output, needs network for deps) — run it yourself.
# ---------------------------------------------------------------------------
cd "/c/Users/Administrator/WebstormProjects/untitled1/mc-girlfriend" || { echo "cd failed"; exit 1; }

LOG="/tmp/pasithea-build.log"
./gradlew --no-daemon build 2>&1 | tee "$LOG"

echo
echo "==================== ERROR / RESULT SUMMARY ===================="
# Java compile errors, Gradle task failures, and the final BUILD line.
grep -nE '\.java:[0-9]+: (error|warning):|error:|^> Task .*FAILED|FAILURE:|BUILD SUCCESSFUL|BUILD FAILED|What went wrong|Caused by:' "$LOG" | head -150
echo "==============================================================="
echo "(full log at $LOG)"
