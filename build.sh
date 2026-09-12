#!/bin/bash
# Build QuestTime VR. Everything it needs lives under toolchain/ - nothing is
# installed system-wide and nothing is assumed to be on PATH.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="$ROOT/toolchain/jdk/Contents/Home"
export ANDROID_HOME="$ROOT/toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Gradle reads the SDK location from local.properties, which holds an absolute
# path and so cannot be checked in. Write it on first run rather than making a
# fresh clone fail on a missing file.
if [ ! -f "$ROOT/android/local.properties" ]; then
    echo "sdk.dir=$ANDROID_HOME" > "$ROOT/android/local.properties"
fi

if [ ! -x "$ROOT/toolchain/gradle/bin/gradle" ]; then
    echo "toolchain/ is missing - see README for what to put there." >&2
    exit 1
fi

cd "$ROOT/android"
exec "$ROOT/toolchain/gradle/bin/gradle" --no-daemon "${@:-assembleDebug}"
