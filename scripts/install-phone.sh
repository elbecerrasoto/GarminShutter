#!/usr/bin/env bash
#
# Build and install the Android companion app onto the connected phone.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdks/jdk-17.0.20.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT/android"

if [[ ! -f local.properties ]]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

./gradlew assembleDebug

APK=app/build/outputs/apk/debug/app-debug.apk
adb wait-for-device
adb install -r "$APK"
echo ">> installed. Open 'Remote Shutter' on the phone."
