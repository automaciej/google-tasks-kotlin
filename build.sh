#!/usr/bin/env bash
# Wrapper around Gradle that sets up the Android Studio JDK.
# Usage: ./build.sh <gradle tasks and flags>
# Examples:
#   ./build.sh :composeApp:testDebugUnitTest
#   ./build.sh :androidApp:assembleDebug
#   ./build.sh --help

set -euo pipefail

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

exec ./gradlew "$@"
