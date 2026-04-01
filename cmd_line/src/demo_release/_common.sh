#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
WORK_PROJECT_DIR="$SCRIPT_DIR/demo_project"
OUTPUT_DIR="$SCRIPT_DIR/outputs"
BACKUP_DIR="$SCRIPT_DIR/backups"
DEMO_PROJECT_ZIP="$SCRIPT_DIR/demo_project.zip"
REPO_SOURCE_PROJECT_DIR="$SCRIPT_DIR/../../../android_demo_project"
APP_COMPONENT="com.example.myapplication/.MainActivity"

if [ -x "$SCRIPT_DIR/../bin/cmd_line" ]; then
    CMD_LINE_BIN="$SCRIPT_DIR/../bin/cmd_line"
elif [ -x "$SCRIPT_DIR/../../bin/cmd_line" ]; then
    CMD_LINE_BIN="$SCRIPT_DIR/../../bin/cmd_line"
else
    CMD_LINE_BIN="$SCRIPT_DIR/../bin/cmd_line"
fi

if [ -f "$SCRIPT_DIR/custom_compilers/custom_compiler_instrument-1.0.jar" ] && [ -f "$SCRIPT_DIR/custom_compilers/dependency.jar" ]; then
    CUSTOM_COMPILER_DIR="$SCRIPT_DIR/custom_compilers"
elif [ -f "$SCRIPT_DIR/../demo/custom_compilers/custom_compiler_instrument-1.0.jar" ] && [ -f "$SCRIPT_DIR/../demo/custom_compilers/dependency.jar" ]; then
    CUSTOM_COMPILER_DIR="$SCRIPT_DIR/../demo/custom_compilers"
else
    CUSTOM_COMPILER_DIR="$SCRIPT_DIR/custom_compilers"
fi

CUSTOM_COMPILER_JARS="$CUSTOM_COMPILER_DIR/custom_compiler_instrument-1.0.jar:$CUSTOM_COMPILER_DIR/dependency.jar"
CUSTOM_COMPILER_JAR_1="$CUSTOM_COMPILER_DIR/custom_compiler_instrument-1.0.jar"
CUSTOM_COMPILER_JAR_2="$CUSTOM_COMPILER_DIR/dependency.jar"

MAIN_ACTIVITY_FILE="$WORK_PROJECT_DIR/app/src/main/java/com/example/myapplication/MainActivity.kt"
MAIN_LAYOUT_FILE="$WORK_PROJECT_DIR/app/src/main/res/layout/activity_main.xml"

RELEASE_APK_GLOB="app/build/outputs/bundle/release/duplicated-app.apk"
RELEASE_OUTPUT_APK="$OUTPUT_DIR/duplicated-app.apk"
RELEASE_MAPPING_FILE="$WORK_PROJECT_DIR/app/build/outputs/mapping/release/mapping.txt"
RELEASE_USAGE_FILE="$WORK_PROJECT_DIR/app/build/outputs/mapping/release/usage.txt"

ensure_exists() {
    target_path="$1"
    if [ ! -e "$target_path" ]; then
        echo "Required path not found: $target_path"
        exit 1
    fi
}

require_cmd() {
    command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command not found: $command_name"
        exit 1
    fi
}

print_env() {
    echo "JAVA_HOME: ${JAVA_HOME:-}"
    echo "ANDROID_HOME: ${ANDROID_HOME:-}"
    echo "CMD_LINE_BIN: $CMD_LINE_BIN"
}

prepare_demo_project() {
    rm -rf "$WORK_PROJECT_DIR"

    if [ -f "$DEMO_PROJECT_ZIP" ]; then
        require_cmd unzip
        echo "Preparing demo project from zip: $DEMO_PROJECT_ZIP"
        unzip -q "$DEMO_PROJECT_ZIP" -d "$SCRIPT_DIR"
        ensure_exists "$WORK_PROJECT_DIR"
        return
    fi

    ensure_exists "$REPO_SOURCE_PROJECT_DIR"
    echo "Preparing demo project from source: $REPO_SOURCE_PROJECT_DIR"
    mkdir -p "$WORK_PROJECT_DIR"

    if command -v rsync >/dev/null 2>&1; then
        rsync -a --delete \
            --exclude build \
            --exclude .gradle \
            --exclude .idea \
            "$REPO_SOURCE_PROJECT_DIR/" "$WORK_PROJECT_DIR/"
    else
        cp -R "$REPO_SOURCE_PROJECT_DIR/." "$WORK_PROJECT_DIR/"
        rm -rf "$WORK_PROJECT_DIR/build" "$WORK_PROJECT_DIR/.gradle" "$WORK_PROJECT_DIR/.idea"
    fi
}

print_apk_outputs() {
    echo "Output APKs:"
    if [ ! -d "$OUTPUT_DIR" ]; then
        echo "  (output directory not found)"
        return
    fi

    set -- "$OUTPUT_DIR"/*.apk
    if [ ! -e "$1" ]; then
        echo "  (no apk found)"
        return
    fi

    for apk_file in "$@"; do
        echo "$apk_file"
    done
}
