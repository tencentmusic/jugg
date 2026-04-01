#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"
. "$SCRIPT_DIR/_common.sh"

require_cmd adb
ensure_exists "$RELEASE_OUTPUT_APK"

if adb install -r "$RELEASE_OUTPUT_APK"; then
    echo "Install success."
else
    echo "Install failed."
    exit 1
fi

if adb shell am start -n "$APP_COMPONENT"; then
    echo "Launch success."
else
    echo "Launch failed."
    exit 1
fi
