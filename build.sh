#!/bin/bash
# Build QuestTime VR. Everything it needs lives under toolchain/ - nothing is
# installed system-wide and nothing is assumed to be on PATH.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="$ROOT/toolchain/jdk/Contents/Home"
export ANDROID_HOME="$ROOT/toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Gradle's own cache belongs here too. It defaults to ~/.gradle, which is the one
# part of this build that was still system-wide - and it grows without limit:
# dependency jars, build caches, and Robolectric's android-all runtimes, which are
# hundreds of megabytes each. That filled a boot volume with 117 MB left, and the
# symptom was not a disk error but Gradle failing to release a lock on its own
# cache, which is a confusing thing to debug. Keeping it beside the toolchain it
# belongs to makes "everything needed to build is in this folder" actually true.
export GRADLE_USER_HOME="$ROOT/toolchain/gradle-home"

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
