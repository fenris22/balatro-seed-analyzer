#!/bin/sh
# Self-extracting seed finder. Upload this one file anywhere and run:
#
#   sh seedfinder.run --help
#
# The first run unpacks the bundled Java runtime and program into a cache folder
# ($SEEDFINDER_HOME if set, otherwise ~/.cache/seedfinder). Later runs start straight
# from there. A newer build unpacks next to it and the old copy is removed.
set -e

BUILD_ID="@BUILD_ID@"
CACHE="${SEEDFINDER_HOME:-${XDG_CACHE_HOME:-$HOME/.cache}/seedfinder}"
DIR="$CACHE/$BUILD_ID"
LAUNCHER="$DIR/seedfinder/bin/seedfinder"

if [ ! -f "$LAUNCHER" ]; then
    echo "Unpacking seed finder into $DIR (first run only)..." >&2
    mkdir -p "$CACHE"
    TMP="$(mktemp -d "$CACHE/.unpack.XXXXXX")"
    SKIP=$(awk '/^__PAYLOAD_BELOW__$/ { print NR + 1; exit 0 }' "$0")
    tail -n +"$SKIP" "$0" | tar xzf - -C "$TMP"
    # Some uploads strip execute bits; make sure the launcher and java can run.
    chmod +x "$TMP/seedfinder/bin/"* "$TMP/seedfinder/runtime/bin/"*
    # Unpacked in a temp folder and moved into place, so an interrupted first run never
    # leaves a half-unpacked copy behind. If another run got there first, keep theirs.
    mv -T "$TMP" "$DIR" 2>/dev/null || rm -rf "$TMP"
    # Remove copies left by older builds.
    for old in "$CACHE"/*; do
        [ "$old" != "$DIR" ] && rm -rf "$old"
    done
fi

exec sh "$LAUNCHER" "$@"
__PAYLOAD_BELOW__