#!/bin/bash

# Test script for Kotlin version switching
# Usage: ./test-version-switch.sh

set -e

echo "=========================================="
echo "Kotlin version switch test"
echo "=========================================="
echo ""

# Save current version
CURRENT_VERSION=$(grep "^kotlinVersion=" gradle.properties | cut -d'=' -f2)
echo "Current version: Kotlin $CURRENT_VERSION"
echo ""

# Test switching to Kotlin 1.7
echo "=========================================="
echo "Test 1: switch to Kotlin 1.7.21"
echo "=========================================="
./switch-kotlin-version.sh 1.7
echo ""
echo "Validating configuration files..."
if grep -q "org.jetbrains.kotlin.plugin.compose" build.gradle; then
    echo "ERROR: build.gradle still contains the Kotlin 2.1 compose plugin"
    exit 1
fi
if ! grep -q "composeOptions" app/build.gradle; then
    echo "ERROR: app/build.gradle is missing composeOptions configuration"
    exit 1
fi
echo "OK: Kotlin 1.7.21 configuration is correct"
echo ""

# Test switching to Kotlin 2.1
echo "=========================================="
echo "Test 2: switch to Kotlin 2.1.0"
echo "=========================================="
./switch-kotlin-version.sh 2.1
echo ""
echo "Validating configuration files..."
if ! grep -q "org.jetbrains.kotlin.plugin.compose" build.gradle; then
    echo "ERROR: build.gradle is missing the Kotlin 2.1 compose plugin"
    exit 1
fi
if grep -q "composeOptions" app/build.gradle; then
    echo "ERROR: app/build.gradle still contains legacy composeOptions configuration"
    exit 1
fi
echo "OK: Kotlin 2.1.0 configuration is correct"
echo ""

# Restore original version
if [ "$CURRENT_VERSION" != "2.1.0" ]; then
    echo "=========================================="
    echo "Restore original version: Kotlin $CURRENT_VERSION"
    echo "=========================================="
    if [ "$CURRENT_VERSION" == "1.7.21" ]; then
        ./switch-kotlin-version.sh 1.7
    fi
    echo ""
fi

echo "=========================================="
echo "All tests passed!"
echo "=========================================="
echo ""
echo "Tip: remember to clean build cache after switching versions:"
echo "  ./gradlew clean"
echo "  rm -rf .gradle build app/build library1/build buildSrc/build"
