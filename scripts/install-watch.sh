#!/usr/bin/env bash
#
# Build and install the Connect IQ watch app.
#
#   scripts/install-watch.sh                  # build for instinct2s + install
#   scripts/install-watch.sh instinct2        # other product id
#   scripts/install-watch.sh instinct2s --build-only
#
# Installing is just: copy the .prg into GARMIN/APPS on the watch's volume,
# then it appears in the watch's app list. Garmin has no "adb install".
#
# The watch must be in MTP mode: Settings > System > USB Mode > MTP.
# Despite the menu name, the Instinct 2S then enumerates as USB *Mass Storage*
# (/dev/disk/by-label/GARMIN, 18 MB vfat). In "Garmin mode" there is no
# filesystem at all and nothing to copy into.
#
set -euo pipefail

DEVICE="${1:-instinct2s}"
EXTRA="${2:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdks/jdk-17.0.20.1+1}"

KEY="$ROOT/tools/devkey.der"
OUT="$ROOT/build/shutter-$DEVICE.prg"
LABEL=/dev/disk/by-label/GARMIN

find_sdk() {
    local cfg="$HOME/.Garmin/ConnectIQ/current-sdk.cfg"
    if [[ -f "$cfg" ]]; then
        local sdk
        sdk="$(tr -d '[:space:]' < "$cfg")"
        if [[ -x "$sdk/bin/monkeyc" ]]; then echo "$sdk"; return 0; fi
    fi
    if [[ -x "$ROOT/tools/connectiq/bin/monkeyc" ]]; then
        echo "$ROOT/tools/connectiq"; return 0
    fi
    return 1
}

SDK="$(find_sdk)" || { echo "error: no Connect IQ SDK found" >&2; exit 1; }
if [[ ! -f "$KEY" ]]; then
    echo "error: missing signing key $KEY (run scripts/setup-toolchain.sh)" >&2
    exit 1
fi

mkdir -p "$ROOT/build"
echo ">> sdk:    $SDK"
echo ">> device: $DEVICE"
"$SDK/bin/monkeyc" -f "$ROOT/watch/monkey.jungle" -o "$OUT" -y "$KEY" -d "$DEVICE" -w
echo ">> built $OUT ($(du -h "$OUT" | cut -f1))"

[[ "$EXTRA" == "--build-only" ]] && exit 0

# ---------------------------------------------------------------- install
if [[ ! -e "$LABEL" ]]; then
    cat >&2 <<MSG
error: no GARMIN volume found ($LABEL).

  1. On the watch: Settings > System > USB Mode > MTP
  2. Replug the cable
  3. lsusb must NOT show 091e:0003 (that is "Garmin mode")

If lsblk shows a 0-byte sda, the watch's USB stack is wedged: power-cycle
the watch (hold LIGHT), not just the cable.
MSG
    exit 2
fi

if ! findmnt -no TARGET "$LABEL" >/dev/null 2>&1; then
    echo ">> mounting $LABEL"
    udisksctl mount -b "$LABEL" 2>&1 | sed 's/^/   /' || true
fi

MOUNT="$(findmnt -no TARGET "$LABEL" 2>/dev/null || true)"
if [[ -z "$MOUNT" ]]; then
    cat >&2 <<MSG
error: could not mount $LABEL.

  udisksctl mount -b $LABEL

If that says "Error looking up object", udisksd has lost the device:
  sudo systemctl restart udisks2
MSG
    exit 2
fi

GARMIN="$MOUNT/GARMIN"
if [[ ! -d "$GARMIN/APPS" ]]; then
    echo "error: $GARMIN/APPS not found — is this the watch volume?" >&2
    exit 2
fi

echo ">> copy -> $GARMIN/APPS"
cp "$OUT" "$GARMIN/APPS/"
sync
echo ">> done. Launch 'Shutter' from the watch's app list."
