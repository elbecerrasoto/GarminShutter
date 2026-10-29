#!/usr/bin/env bash
#
# Run both test suites.
#
#   1. Phone  : Protocol unit tests on the JVM (fast, no emulator, no phone).
#   2. Watch  : ReplyProtocol unit tests in the Connect IQ simulator.
#
# The phone suite is the one to run constantly; the watch suite needs the
# Connect IQ simulator binary, which is a GUI process.
#
#   scripts/run-tests.sh              # both
#   scripts/run-tests.sh phone        # only the JVM suite
#   scripts/run-tests.sh watch        # only the simulator suite
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdks/jdk-17.0.20.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
DEVICE="${DEVICE:-instinct2s}"
WHICH="${1:-all}"

SDK="$(tr -d '[:space:]' < "$HOME/.Garmin/ConnectIQ/current-sdk.cfg" 2>/dev/null || true)"
KEY="$ROOT/tools/devkey.der"

rc=0

run_phone() {
    echo "== phone: Protocol unit tests (JVM) =="
    ( cd "$ROOT/android" && ./gradlew --no-daemon testDebugUnitTest --console=plain ) || return 1
    local xml="$ROOT/android/app/build/test-results/testDebugUnitTest"
    if [ -d "$xml" ]; then
        grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$xml"/*.xml 2>/dev/null \
            | sed 's/^/   /'
    fi
    echo "   report: android/app/build/reports/tests/testDebugUnitTest/index.html"
}

run_watch() {
    echo "== watch: ReplyProtocol unit tests (simulator) =="
    if [ -z "${SDK:-}" ] || [ ! -x "$SDK/bin/monkeyc" ]; then
        echo "   SKIP: no Connect IQ SDK found"; return 0
    fi
    if [ ! -f "$KEY" ]; then
        echo "   SKIP: missing $KEY (run scripts/setup-toolchain.sh)"; return 0
    fi
    mkdir -p "$ROOT/build"
    "$SDK/bin/monkeyc" -f "$ROOT/watch/test.jungle" \
        -o "$ROOT/build/test-$DEVICE.prg" -y "$KEY" -d "$DEVICE" -t -w || return 1

    # The simulator must be up; monkeydo talks to it over a socket.
    if ! pgrep -f "$SDK/bin/simulator" >/dev/null 2>&1; then
        echo "   starting simulator..."
        ( LD_LIBRARY_PATH="$ROOT/tools/jxlshim" "$SDK/bin/simulator" >/tmp/ciq-simulator.log 2>&1 & )
        sleep 10
    fi

    local out
    out="$("$SDK/bin/monkeydo" "$ROOT/build/test-$DEVICE.prg" "$DEVICE" -t 2>&1 || true)"
    printf '%s\n' "$out" | sed -n '/^RESULTS/,$p'
    if printf '%s\n' "$out" | grep -q 'PASSED'; then
        return 0
    fi
    printf '%s\n' "$out" | tail -20
    return 1
}

case "$WHICH" in
    phone) run_phone || rc=1 ;;
    watch) run_watch || rc=1 ;;
    all)
        run_phone || rc=1
        echo
        run_watch || rc=1
        ;;
    *) echo "usage: $0 [phone|watch|all]" >&2; exit 2 ;;
esac

echo
if [ "$rc" -eq 0 ]; then echo "ALL TESTS PASSED"; else echo "TESTS FAILED"; fi
exit "$rc"
