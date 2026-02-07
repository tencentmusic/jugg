#!/bin/bash

# Kotlin version switch script
# Usage: ./switch-kotlin-version.sh [2.1|1.7|legacy]

set -e

GRADLE_PROPERTIES="gradle.properties"
GRADLE_WRAPPER="gradle/wrapper/gradle-wrapper.properties"
ROOT_BUILD_GRADLE="build.gradle"
APP_BUILD_GRADLE="app/build.gradle"
LIBRARY1_BUILD_GRADLE="library1/build.gradle"

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
        update_version "KOTLIN_2_1"
        ;;
    "1.7"|"kotlin1.7"|"legacy")
        update_version "KOTLIN_1_7"
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
