#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"
. "$SCRIPT_DIR/_common.sh"

prepare_demo_project
ensure_exists "$MAIN_ACTIVITY_FILE"
ensure_exists "$MAIN_LAYOUT_FILE"

sed -i '' '/super\.onCreate/a\
        android.widget.Toast.makeText(this, "Hello Jugg release cmd line!", android.widget.Toast.LENGTH_SHORT).show()
' "$MAIN_ACTIVITY_FILE"

if grep -q 'Hello Jugg release cmd line' "$MAIN_ACTIVITY_FILE"; then
    echo "Updated file: $MAIN_ACTIVITY_FILE"
else
    echo "Failed to update file: $MAIN_ACTIVITY_FILE"
    exit 1
fi

perl -0777 -i -pe 's/android:text="Hello World Jugg!"/android:text="Hello Release World for Jugg cmd line!"/' "$MAIN_LAYOUT_FILE"

if grep -q 'Hello Release World for Jugg cmd line' "$MAIN_LAYOUT_FILE"; then
    echo "Updated file: $MAIN_LAYOUT_FILE"
else
    echo "Failed to update file: $MAIN_LAYOUT_FILE"
    exit 1
fi
