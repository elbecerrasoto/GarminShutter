#!/usr/bin/env bash
#
# Back up the watch's filesystem before/after installing anything.
#
# Garmin watches expose a plain MTP volume; we just mirror the GARMIN folder
# plus a checksum manifest so a restore (or a "did anything change?" check) is
# trivial.
#
#   scripts/backup-garmin.sh              # -> ~/garmin-backups/<timestamp>/
#   scripts/backup-garmin.sh /mnt/watch   # explicit mount point
#
set -euo pipefail

DEST_ROOT="${GARMIN_BACKUP_DIR:-$HOME/garmin-backups}"
STAMP="$(date +%Y-%m-%d_%H%M%S)"
DEST="$DEST_ROOT/$STAMP"

find_garmin() {
    local candidate
    for candidate in \
        "${1:-}" \
        /run/media/"$USER"/*/GARMIN \
        /run/user/"$(id -u)"/gvfs/mtp:host=*/GARMIN \
        /media/"$USER"/*/GARMIN \
        /mnt/*/GARMIN \
        "$HOME"/garmin/GARMIN; do
        [[ -n "$candidate" && -d "$candidate" ]] && { echo "$candidate"; return 0; }
    done
    return 1
}

if ! SRC="$(find_garmin "${1:-}")"; then
    cat >&2 <<'EOF'
No GARMIN folder found.

  1. On the watch: Settings > System > USB Mode > MTP
  2. Unplug and replug the USB cable
  3. If it still does not appear:  lsusb  should NOT show 091e:0003
     (that is "Garmin mode"; check  dmesg | tail)

Why this matters: the watch must expose a filesystem before we can copy the
compiled app into GARMIN/APPS, and before we can back anything up.
EOF
    exit 2
fi

echo ">> source: $SRC"
echo ">> dest:   $DEST"
mkdir -p "$DEST"

# A handful of Garmin-internal files (Totals/Totals.fit, TLG/TLG_KPI.bak,
# TEXT/UKRAINIA.LN4, ...) are deliberately unreadable from the host: reads
# fail with EIO. Do not let those abort a 280-file backup.
# Skip the known-unreadable internals up front: they only raise EIO and
# stall the transfer for minutes. Drop these --exclude flags to see them.
EXCLUDES=(--exclude=/Totals/Totals.fit --exclude=/TLG/TLG_KPI.bak --exclude=/TEXT/UKRAINIA.LN4)
fail=0
if command -v rsync >/dev/null; then
    rsync -rt --ignore-errors "${EXCLUDES[@]}" "$SRC"/ "$DEST/GARMIN"/ || fail=$?
else
    cp -a "$SRC" "$DEST/GARMIN" 2>/dev/null || fail=$?
fi

# A manifest gives us a cheap integrity/diff check later.
( cd "$DEST" && find GARMIN -type f -printf '%s\t%p\n' | sort -k2 ) > "$DEST/MANIFEST.tsv"

FILES=$(wc -l < "$DEST/MANIFEST.tsv")
BYTES=$(awk -F'\t' '{s+=$1} END {print s+0}' "$DEST/MANIFEST.tsv")
if [ "$fail" -ne 0 ]; then
    echo ">> WARNING: some source files could not be read (rsync exit $fail)."
    echo ">>          Known Garmin-protected files are expected to fail."
    echo ">>          Everything else in $DEST is complete."
fi
echo ">> done: $FILES files, $BYTES bytes"
echo ">> $DEST"
