#!/bin/bash

set -e

GRADLE_PROPERTIES="gradle.properties"
GRADLE_WRAPPER="gradle/wrapper/gradle-wrapper.properties"
BACKUP_DIR=".kotlin-version-backup"
BACKUP_VERSION_FILE="$BACKUP_DIR/original-version"
BUILD_FILES=(
    "build.gradle"
    "app/build.gradle"
    "library1/build.gradle"
    "kmpCompose/build.gradle"
)
BACKUP_FILES=("${BUILD_FILES[@]}" "$GRADLE_PROPERTIES" "$GRADLE_WRAPPER")

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

KOTLIN_1_9_kotlinVersion="1.9.22"
KOTLIN_1_9_kspVersion="1.9.22-1.0.17"
KOTLIN_1_9_composeVersion="1.6.0"
KOTLIN_1_9_composeCompilerVersion="1.5.8"
KOTLIN_1_9_agpVersion="7.3.1"
KOTLIN_1_9_gradleVersion="7.4"
KOTLIN_1_9_excludeKmpCompose="false"

KOTLIN_1_7_kotlinVersion="1.7.21"
KOTLIN_1_7_kspVersion="1.7.21-1.0.8"
KOTLIN_1_7_composeVersion=""
KOTLIN_1_7_composeCompilerVersion="1.4.0-alpha02"
KOTLIN_1_7_agpVersion="7.2.2"
KOTLIN_1_7_gradleVersion="7.3.3"
KOTLIN_1_7_excludeKmpCompose="true"

KOTLIN_2_1_kotlinVersion="2.1.0"
KOTLIN_2_1_kspVersion="2.1.0-1.0.29"
KOTLIN_2_1_composeVersion="1.7.3"
KOTLIN_2_1_composeCompilerVersion="1.5.15"
KOTLIN_2_1_agpVersion="8.0.2"
KOTLIN_2_1_gradleVersion="8.0"
KOTLIN_2_1_excludeKmpCompose="false"

KOTLIN_2_3_kotlinVersion="2.3.20"
KOTLIN_2_3_kspVersion="2.3.9"
KOTLIN_2_3_composeVersion="1.10.3"
KOTLIN_2_3_composeCompilerVersion="2.3.20"
KOTLIN_2_3_agpVersion="8.13.2"
KOTLIN_2_3_gradleVersion="8.13"
KOTLIN_2_3_excludeKmpCompose="false"

KOTLIN_2_3_AGP9_kotlinVersion="2.3.0"
KOTLIN_2_3_AGP9_kspVersion="2.3.4"
KOTLIN_2_3_AGP9_composeVersion="1.10.3"
KOTLIN_2_3_AGP9_composeCompilerVersion="2.3.0"
KOTLIN_2_3_AGP9_agpVersion="9.0.0"
KOTLIN_2_3_AGP9_gradleVersion="9.4.0"
KOTLIN_2_3_AGP9_excludeKmpCompose="false"

show_current_version() {
    echo -e "${GREEN}Current version configuration:${NC}"
    grep -E "^(kotlinVersion|kspVersion|composeVersion|composeCompilerVersion|agpVersion|excludeKmpCompose)=" "$GRADLE_PROPERTIES" || true
}

normalize_version() {
    case "$1" in
        1.7|kotlin1.7|legacy|1.7.*) echo "1.7" ;;
        1.9|kotlin1.9|1.9.*) echo "1.9" ;;
        2.1|kotlin2.1|2.1.*) echo "2.1" ;;
        2.3-agp9|kotlin2.3-agp9) echo "2.3-agp9" ;;
        2.3|kotlin2.3|latest|2.3.*) echo "2.3" ;;
        *) echo "$1" ;;
    esac
}

current_kotlin_version() {
    local version
    version=$(grep -E '^kotlinVersion=' "$GRADLE_PROPERTIES" | head -n 1 | cut -d'=' -f2- | tr -d '[:space:]')
    if [ -z "$version" ]; then
        echo -e "${RED}Error: kotlinVersion is missing in $GRADLE_PROPERTIES${NC}" >&2
        exit 1
    fi
    normalize_version "$version"
}

ensure_backup_snapshot() {
    [ -f "$BACKUP_VERSION_FILE" ] && return
    mkdir -p "$BACKUP_DIR"
    current_kotlin_version > "$BACKUP_VERSION_FILE"
    for file in "${BACKUP_FILES[@]}"; do
        mkdir -p "$BACKUP_DIR/$(dirname "$file")"
        cp "$file" "$BACKUP_DIR/$file"
    done
}

restore_original_snapshot() {
    for file in "${BACKUP_FILES[@]}"; do
        cp "$BACKUP_DIR/$file" "$file"
    done
    rm -rf "$BACKUP_DIR"
    echo -e "${GREEN}Original Gradle files restored.${NC}"
}

update_property() {
    local key=$1
    local value=$2
    if grep -q "^${key}=" "$GRADLE_PROPERTIES"; then
        if [[ "$OSTYPE" == darwin* ]]; then
            sed -i '' "s/^${key}=.*/${key}=${value}/" "$GRADLE_PROPERTIES"
        else
            sed -i "s/^${key}=.*/${key}=${value}/" "$GRADLE_PROPERTIES"
        fi
    else
        echo "${key}=${value}" >> "$GRADLE_PROPERTIES"
    fi
}

remove_property() {
    local key=$1
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' "/^${key}=/d" "$GRADLE_PROPERTIES"
    else
        sed -i "/^${key}=/d" "$GRADLE_PROPERTIES"
    fi
}

update_gradle_wrapper() {
    local url="https\\://services.gradle.org/distributions/gradle-$1-all.zip"
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' "s|^distributionUrl=.*|distributionUrl=${url}|" "$GRADLE_WRAPPER"
    else
        sed -i "s|^distributionUrl=.*|distributionUrl=${url}|" "$GRADLE_WRAPPER"
    fi
}

switch_build_gradle_files() {
    local suffix=$1
    local files=("build.gradle" "app/build.gradle" "library1/build.gradle")
    if [ "$suffix" != "kotlin1.7" ]; then
        files+=("kmpCompose/build.gradle")
    fi
    for file in "${files[@]}"; do
        local template="${file}.${suffix}"
        if [ ! -f "$template" ]; then
            echo -e "${RED}Error: missing Gradle template $template${NC}" >&2
            exit 1
        fi
        cp "$template" "$file"
        echo -e "${GREEN}Switched $file${NC}"
    done
}

update_version() {
    local prefix=$1
    local suffix=$2
    ensure_backup_snapshot
    local original
    original=$(cat "$BACKUP_VERSION_FILE")
    if [ "$suffix" = "$original" ]; then
        restore_original_snapshot
        return
    fi

    for property in kotlinVersion kspVersion composeCompilerVersion agpVersion excludeKmpCompose; do
        local variable="${prefix}_${property}"
        update_property "$property" "${!variable}"
    done
    local compose_variable="${prefix}_composeVersion"
    if [ -n "${!compose_variable}" ]; then
        update_property composeVersion "${!compose_variable}"
    else
        remove_property composeVersion
    fi
    local gradle_variable="${prefix}_gradleVersion"
    update_gradle_wrapper "${!gradle_variable}"
    switch_build_gradle_files "kotlin${suffix}"
    show_current_version
    echo -e "${GREEN}Gradle: ${!gradle_variable}${NC}"
}

case "${1:-}" in
    1.7|kotlin1.7|legacy) update_version KOTLIN_1_7 1.7 ;;
    1.9|kotlin1.9) update_version KOTLIN_1_9 1.9 ;;
    2.1|kotlin2.1) update_version KOTLIN_2_1 2.1 ;;
    2.3|kotlin2.3|latest) update_version KOTLIN_2_3 2.3 ;;
    2.3-agp9|kotlin2.3-agp9) update_version KOTLIN_2_3_AGP9 2.3-agp9 ;;
    show|current|"") show_current_version ;;
    help|-h|--help)
        echo "Usage: $0 [1.7|1.9|2.1|2.3|2.3-agp9|show]"
        ;;
    *)
        echo -e "${RED}Error: unknown option '$1'${NC}" >&2
        exit 1
        ;;
esac
