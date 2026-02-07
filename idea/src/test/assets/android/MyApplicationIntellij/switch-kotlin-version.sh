#!/bin/bash

# Kotlin version switch script
# Usage: ./switch-kotlin-version.sh [2.1|1.7|legacy]

set -e

GRADLE_PROPERTIES="gradle.properties"
GRADLE_WRAPPER="gradle/wrapper/gradle-wrapper.properties"
ROOT_BUILD_GRADLE="build.gradle"
APP_BUILD_GRADLE="app/build.gradle"
LIBRARY1_BUILD_GRADLE="library1/build.gradle"
BACKUP_DIR=".kotlin-version-backup"
BACKUP_VERSION_FILE="${BACKUP_DIR}/original-version"
BACKUP_FILES=(
    "$ROOT_BUILD_GRADLE"
    "$APP_BUILD_GRADLE"
    "$LIBRARY1_BUILD_GRADLE"
    "$GRADLE_PROPERTIES"
    "$GRADLE_WRAPPER"
)

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Kotlin 2.1.0 version config
KOTLIN_2_1_kotlinVersion="2.1.0"
KOTLIN_2_1_kspVersion="2.1.0-1.0.29"
KOTLIN_2_1_composeCompilerVersion="1.5.15"
KOTLIN_2_1_agpVersion="8.0.2"
KOTLIN_2_1_gradleVersion="8.0"

# Kotlin 1.7.21 version config
KOTLIN_1_7_kotlinVersion="1.7.21"
KOTLIN_1_7_kspVersion="1.7.21-1.0.8"
KOTLIN_1_7_composeCompilerVersion="1.4.0-alpha02"
KOTLIN_1_7_agpVersion="7.2.2"
KOTLIN_1_7_gradleVersion="7.3.3"

# Show current version
show_current_version() {
    echo -e "${GREEN}Current version configuration:${NC}"
    grep -E "^(kotlinVersion|kspVersion|composeCompilerVersion|agpVersion)=" "$GRADLE_PROPERTIES" || echo "Version configuration not found"
}

normalize_version() {
    local version="$1"
    case "${version}" in
        "1.7"|"kotlin1.7"|"legacy"|"1.7.21")
            echo "1.7"
            ;;
        "2.1"|"kotlin2.1"|"latest"|"2.1.0")
            echo "2.1"
            ;;
        *)
            if [[ "${version}" == 1.7* ]]; then
                echo "1.7"
            elif [[ "${version}" == 2.1* ]]; then
                echo "2.1"
            else
                echo "${version}"
            fi
            ;;
    esac
}

current_kotlin_version() {
    local raw_version
    raw_version=$(grep -E "^kotlinVersion=" "$GRADLE_PROPERTIES" | head -n 1 | cut -d'=' -f2- | tr -d '[:space:]')
    if [ -z "$raw_version" ]; then
        echo -e "${RED}Error: kotlinVersion is missing in ${GRADLE_PROPERTIES}${NC}"
        exit 1
    fi
    normalize_version "$raw_version"
}

ensure_backup_snapshot() {
    if [ -f "$BACKUP_VERSION_FILE" ]; then
        return 0
    fi

    local original_version
    original_version=$(current_kotlin_version)

    mkdir -p "$BACKUP_DIR"
    echo "$original_version" > "$BACKUP_VERSION_FILE"

    for file in "${BACKUP_FILES[@]}"; do
        if [ ! -f "$file" ]; then
            echo -e "${RED}Error: cannot create snapshot, missing file ${file}${NC}"
            exit 1
        fi
        mkdir -p "$BACKUP_DIR/$(dirname "$file")"
        cp "$file" "$BACKUP_DIR/$file"
    done

    echo -e "${BLUE}Snapshot created for original Kotlin version ${original_version}.${NC}"
}

original_snapshot_version() {
    if [ -f "$BACKUP_VERSION_FILE" ]; then
        tr -d '[:space:]' < "$BACKUP_VERSION_FILE"
    fi
}

clear_backup_snapshot() {
    rm -rf "$BACKUP_DIR"
}

restore_original_snapshot() {
    local original_version
    original_version=$(original_snapshot_version)
    if [ -z "$original_version" ]; then
        echo -e "${YELLOW}Warning: snapshot not found, fallback to template-based switch.${NC}"
        return 1
    fi

    echo -e "${BLUE}Restoring original Gradle files for Kotlin ${original_version}...${NC}"
    for file in "${BACKUP_FILES[@]}"; do
        if [ ! -f "$BACKUP_DIR/$file" ]; then
            echo -e "${RED}Error: snapshot file missing: ${BACKUP_DIR}/${file}${NC}"
            exit 1
        fi
        mkdir -p "$(dirname "$file")"
        cp "$BACKUP_DIR/$file" "$file"
    done
    clear_backup_snapshot
    echo -e "${GREEN}Original Gradle files restored.${NC}"
    return 0
}

# Update one property
update_property() {
    local key=$1
    local value=$2

    if grep -q "^${key}=" "$GRADLE_PROPERTIES"; then
        # Property exists, update it
        if [[ "$OSTYPE" == "darwin"* ]]; then
            # macOS
            sed -i '' "s/^${key}=.*/${key}=${value}/" "$GRADLE_PROPERTIES"
        else
            # Linux
            sed -i "s/^${key}=.*/${key}=${value}/" "$GRADLE_PROPERTIES"
        fi
    else
        # Property does not exist, append it
        echo "${key}=${value}" >> "$GRADLE_PROPERTIES"
    fi
}

# Update Gradle wrapper version
update_gradle_wrapper() {
    local gradle_version=$1
    local gradle_url="https\\\\://services.gradle.org/distributions/gradle-${gradle_version}-all.zip"

    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        sed -i '' "s|^distributionUrl=.*|distributionUrl=${gradle_url}|" "$GRADLE_WRAPPER"
    else
        # Linux
        sed -i "s|^distributionUrl=.*|distributionUrl=${gradle_url}|" "$GRADLE_WRAPPER"
    fi
}

# Switch build.gradle files
switch_build_gradle_files() {
    local version_suffix=$1

    echo -e "${BLUE}Switching build.gradle files to ${version_suffix}...${NC}"

    # Switch root build.gradle
    if [ -f "${ROOT_BUILD_GRADLE}.${version_suffix}" ]; then
        cp "${ROOT_BUILD_GRADLE}.${version_suffix}" "$ROOT_BUILD_GRADLE"
        echo -e "${GREEN}  Switched ${ROOT_BUILD_GRADLE}${NC}"
    else
        echo -e "${YELLOW}  Warning: ${ROOT_BUILD_GRADLE}.${version_suffix} not found${NC}"
    fi

    # Switch app/build.gradle
    if [ -f "${APP_BUILD_GRADLE}.${version_suffix}" ]; then
        cp "${APP_BUILD_GRADLE}.${version_suffix}" "$APP_BUILD_GRADLE"
        echo -e "${GREEN}  Switched ${APP_BUILD_GRADLE}${NC}"
    else
        echo -e "${YELLOW}  Warning: ${APP_BUILD_GRADLE}.${version_suffix} not found${NC}"
    fi

    # Switch library1/build.gradle
    if [ -f "${LIBRARY1_BUILD_GRADLE}.${version_suffix}" ]; then
        cp "${LIBRARY1_BUILD_GRADLE}.${version_suffix}" "$LIBRARY1_BUILD_GRADLE"
        echo -e "${GREEN}  Switched ${LIBRARY1_BUILD_GRADLE}${NC}"
    else
        echo -e "${YELLOW}  Warning: ${LIBRARY1_BUILD_GRADLE}.${version_suffix} not found${NC}"
    fi
}

# Update version
update_version() {
    local prefix=$1
    local target_version=$2

    ensure_backup_snapshot
    local original_version
    original_version=$(original_snapshot_version)
    if [ "$target_version" == "$original_version" ]; then
        restore_original_snapshot && return 0
    fi

    # Resolve version variables
    local kotlin_var="${prefix}_kotlinVersion"
    local ksp_var="${prefix}_kspVersion"
    local compose_var="${prefix}_composeCompilerVersion"
    local agp_var="${prefix}_agpVersion"
    local gradle_var="${prefix}_gradleVersion"

    echo -e "${YELLOW}Switching to Kotlin ${!kotlin_var}...${NC}"
    echo ""

    # Update each version property
    echo -e "${BLUE}Updating gradle.properties...${NC}"
    update_property "kotlinVersion" "${!kotlin_var}"
    update_property "kspVersion" "${!ksp_var}"
    update_property "composeCompilerVersion" "${!compose_var}"
    update_property "agpVersion" "${!agp_var}"

    # Update Gradle wrapper version
    echo -e "${BLUE}Updating Gradle wrapper to ${!gradle_var}...${NC}"
    update_gradle_wrapper "${!gradle_var}"

    # Switch build.gradle files
    local version_suffix
    if [ "$prefix" == "KOTLIN_2_1" ]; then
        version_suffix="kotlin2.1"
    else
        version_suffix="kotlin1.7"
    fi
    switch_build_gradle_files "$version_suffix"

    echo ""
    echo -e "${GREEN}Version switch successful!${NC}"
    echo ""
    show_current_version
    echo -e "${GREEN}  Gradle: ${!gradle_var}${NC}"
    echo ""
    echo -e "${YELLOW}Tip: run the following commands to clean build cache:${NC}"
    echo "  ./gradlew clean"
    echo "  rm -rf .gradle build app/build library1/build buildSrc/build"
}

# Main logic
case "${1:-}" in
    "2.1"|"kotlin2.1"|"latest")
        update_version "KOTLIN_2_1" "2.1"
        ;;
    "1.7"|"kotlin1.7"|"legacy")
        update_version "KOTLIN_1_7" "1.7"
        ;;
    "show"|"current"|"")
        show_current_version
        ;;
    "help"|"-h"|"--help")
        echo "Kotlin version switch script"
        echo ""
        echo "Usage: $0 [option]"
        echo ""
        echo "Options:"
        echo "  2.1, latest       Switch to Kotlin 2.1.0 (use new Compose plugin)"
        echo "  1.7, legacy       Switch to Kotlin 1.7.21 (legacy version)"
        echo "  show, current     Show current version"
        echo "  help              Show this help message"
        echo ""
        echo "Examples:"
        echo "  $0 2.1            # Switch to Kotlin 2.1"
        echo "  $0 legacy         # Switch back to Kotlin 1.7.21"
        echo "  $0 show           # Show current version"
        echo ""
        echo "Notes:"
        echo "  - Kotlin 2.1.0 uses the new Compose compiler plugin (org.jetbrains.kotlin.plugin.compose)"
        echo "  - Kotlin 1.7.21 uses the legacy composeOptions.kotlinCompilerExtensionVersion setting"
        echo "  - Switching versions automatically replaces the corresponding build.gradle files"
        ;;
    *)
        echo -e "${RED}Error: unknown option '$1'${NC}"
        echo "Run '$0 help' for help"
        exit 1
        ;;
esac
