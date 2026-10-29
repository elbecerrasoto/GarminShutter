#!/usr/bin/env bash
#
# Reproduce this project's build toolchain on a fresh Manjaro box.
# Everything lands under $HOME: no root, no Android Studio.
# Idempotent: safe to re-run.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_DIR="$HOME/.local/share/jdks"
JDK="$JDK_DIR/jdk-17.0.20.1+1"
SDK="$HOME/android-sdk"

ok()   { printf '  [ok]   %s\n' "$*"; }
todo() { printf '  [todo] %s\n' "$*"; }

echo "== JDK 17 =="
if [[ -x "$JDK/bin/java" ]]; then
    ok "$JDK"
else
    mkdir -p "$JDK_DIR"
    curl -sL -o /tmp/jdk17.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    tar xzf /tmp/jdk17.tgz -C "$JDK_DIR"
    rm -f /tmp/jdk17.tgz
    ok "$JDK"
fi
# AGP 8.6 does not support the JDK 26 that Manjaro ships by default, which is
# why every script exports JAVA_HOME explicitly.

echo "== Android SDK =="
if [[ -x "$SDK/platforms/android-35/android.jar" && -d "$SDK/build-tools/35.0.0" ]]; then
    ok "$SDK"
else
    PKG="$SDK/cmdline-tools/latest/bin/sdkmanager"
    if [[ ! -x "$PKG" ]]; then
        mkdir -p "$SDK/cmdline-tools"
        curl -sL -o /tmp/cmdline-tools.zip \
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        unzip -q -o /tmp/cmdline-tools.zip -d "$SDK/cmdline-tools"
        mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
    fi
    printf 'y\n%.0s' {1..20} > /tmp/yes.txt
    JAVA_HOME="$JDK" "$PKG" --sdk_root="$SDK" \
        "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
        < /tmp/yes.txt > /dev/null
    ok "$SDK"
fi
echo "sdk.dir=$SDK" > "$ROOT/android/local.properties"

echo "== Garmin signing key =="
if [[ -f "$ROOT/tools/devkey.der" ]]; then
    ok "tools/devkey.der"
else
    mkdir -p "$ROOT/tools"
    openssl genrsa -out "$ROOT/tools/devkey.pem" 4096 2>/dev/null
    openssl pkcs8 -topk8 -inform PEM -outform DER \
        -in "$ROOT/tools/devkey.pem" -out "$ROOT/tools/devkey.der" -nocrypt
    rm -f "$ROOT/tools/devkey.pem"
    ok "tools/devkey.der (generated; keep it, it is the app's identity)"
fi

echo "== Connect IQ SDK =="
if [[ -f "$HOME/.Garmin/ConnectIQ/current-sdk.cfg" ]]; then
    ok "$(cat "$HOME/.Garmin/ConnectIQ/current-sdk.cfg")"
else
    todo "run 'connectiq-sdk-manager' and install an SDK"
fi

echo "== Connect IQ device library =="
DEVICES="$HOME/.Garmin/ConnectIQ/Devices"
if compgen -G "$DEVICES/*" > /dev/null; then
    ok "$(ls "$DEVICES" | wc -l) device(s)"
else
    todo "in connectiq-sdk-manager open the 'Devices' tab and download the"
    todo "Instinct 2 family -- monkeyc cannot build without it"
fi

echo
echo "== Next =="
echo "  scripts/install-phone.sh           # build + adb install the APK"
echo "  scripts/install-watch.sh instinct2 # build + copy the .prg over MTP"
echo "  scripts/backup-garmin.sh           # mirror GARMIN/ to ~/garmin-backups"
