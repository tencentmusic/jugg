#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"
. "$SCRIPT_DIR/_common.sh"

ensure_exists "$CMD_LINE_BIN"
ensure_exists "$BACKUP_DIR/jugg_bak"
ensure_exists "$MAIN_ACTIVITY_FILE"
ensure_exists "$MAIN_LAYOUT_FILE"
ensure_exists "$WORK_PROJECT_DIR"
ensure_exists "$CUSTOM_COMPILER_JAR_1"
ensure_exists "$CUSTOM_COMPILER_JAR_2"

rm -rf "$OUTPUT_DIR" "$BACKUP_DIR/jugg_bak_checkout"
cp -R "$BACKUP_DIR/jugg_bak" "$BACKUP_DIR/jugg_bak_checkout"

print_env

if "$CMD_LINE_BIN" \
    cmd=buildIncrementalApk \
    "baseBuildJuggRootDir=$BACKUP_DIR/jugg_bak_checkout" \
    "sourceProjectDir=$WORK_PROJECT_DIR" \
    "customCompilerJars=$CUSTOM_COMPILER_JARS" \
    logLevel=debug \
    "outputApkDir=$OUTPUT_DIR" \
    "changedFiles=$MAIN_ACTIVITY_FILE:$MAIN_LAYOUT_FILE"; then
    echo "Release incremental build success."
else
    echo "Release incremental build failed."
    exit 1
fi

print_apk_outputs
