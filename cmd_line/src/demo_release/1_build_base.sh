#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"
. "$SCRIPT_DIR/_common.sh"

ensure_exists "$CMD_LINE_BIN"
prepare_demo_project
rm -rf "$OUTPUT_DIR" "$BACKUP_DIR"
mkdir -p "$BACKUP_DIR"

print_env

if "$CMD_LINE_BIN" \
    cmd=buildGradleBase \
    "baseBuildProjectDir=$WORK_PROJECT_DIR" \
    "gradleCompileTask=bundleReleaseToApk" \
    "gradleOutputApkPath=$RELEASE_APK_GLOB" \
    logLevel=debug \
    "outputApkDir=$OUTPUT_DIR"; then
    echo "Release base build success."
else
    echo "Release base build failed."
    exit 1
fi

ensure_exists "$WORK_PROJECT_DIR/build/jugg"
rm -rf "$BACKUP_DIR/jugg_bak"
cp -R "$WORK_PROJECT_DIR/build/jugg" "$BACKUP_DIR/jugg_bak"
ensure_exists "$BACKUP_DIR/jugg_bak"
ensure_exists "$RELEASE_MAPPING_FILE"

echo "Jugg backup dir: $BACKUP_DIR/jugg_bak"
echo "Mapping file: $RELEASE_MAPPING_FILE"
if [ -f "$RELEASE_USAGE_FILE" ]; then
    echo "Usage file: $RELEASE_USAGE_FILE"
else
    echo "Usage file not found. Incremental release can still continue without deleted-method stubs."
fi

print_apk_outputs
