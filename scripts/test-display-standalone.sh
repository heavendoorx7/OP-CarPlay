#!/usr/bin/env bash
set -euo pipefail

# Runs the Android-independent regression tests without downloading an SDK/NDK.
: "${JAVA_HOME:?Set JAVA_HOME to a JDK}"
: "${KOTLIN_HOME:?Set KOTLIN_HOME to the Kotlin compiler directory}"
: "${JUNIT_JAR:?Set JUNIT_JAR to JUnit 4.13.2}"
: "${HAMCREST_JAR:?Set HAMCREST_JAR to hamcrest-core 1.3}"
task_root="$(cd "$(dirname "$0")/.." && pwd)"
task_output="$(mktemp -d)"
trap 'rm -rf "$task_output"' EXIT
cd "$task_root"

"$KOTLIN_HOME/bin/kotlinc" -jvm-target 11 \
    common/src/main/java/com/shilapi/xcertplay/DisplayResizeState.kt \
    common/src/test/java/com/shilapi/xcertplay/DisplayResizeStateTest.kt \
    shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayConfig.kt \
    shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayDisplaySettings.kt \
    shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayDisplayScale.kt \
    shared/src/main/java/com/shilapi/xcertplay/airplay/P7DisplayProfile.kt \
    shared/src/test/java/com/shilapi/xcertplay/airplay/AirPlayDisplaySettingsTest.kt \
    shared/src/test/java/com/shilapi/xcertplay/airplay/CarPlayDisplayScaleTest.kt \
    shared/src/test/java/com/shilapi/xcertplay/airplay/P7DisplayProfileTest.kt \
    -classpath "$JUNIT_JAR:$HAMCREST_JAR" -include-runtime -d "$task_output/tests.jar"

"$JAVA_HOME/bin/java" -cp "$task_output/tests.jar:$JUNIT_JAR:$HAMCREST_JAR" \
    org.junit.runner.JUnitCore \
    com.shilapi.xcertplay.DisplayResizeStateTest \
    com.shilapi.xcertplay.airplay.AirPlayDisplaySettingsTest \
    com.shilapi.xcertplay.airplay.CarPlayDisplayScaleTest \
    com.shilapi.xcertplay.airplay.P7DisplayProfileTest
