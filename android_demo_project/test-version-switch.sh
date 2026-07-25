#!/bin/bash

set -e

BACKUP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/jugg-version-switch.XXXXXX")
FILES=(
    build.gradle
    app/build.gradle
    library1/build.gradle
    kmpCompose/build.gradle
    gradle.properties
    gradle/wrapper/gradle-wrapper.properties
)

restore() {
    for file in "${FILES[@]}"; do
        cp "$BACKUP_DIR/$file" "$file"
    done
    rm -rf "$BACKUP_DIR" .kotlin-version-backup
}
trap restore EXIT

for file in "${FILES[@]}"; do
    mkdir -p "$BACKUP_DIR/$(dirname "$file")"
    cp "$file" "$BACKUP_DIR/$file"
done

assert_contains() {
    local file=$1
    local expected=$2
    if ! grep -Fq "$expected" "$file"; then
        echo "ERROR: $file does not contain: $expected"
        exit 1
    fi
}

assert_fixture_enabled() {
    assert_contains settings.gradle "include ':kmpCompose'"
    assert_contains gradle.properties "excludeKmpCompose=false"
    if grep -Rq "enableKmpComposeFixture" settings.gradle app/build.gradle kmpCompose/build.gradle; then
        echo "ERROR: Compose fixture is still guarded by enableKmpComposeFixture"
        exit 1
    fi
    assert_contains app/build.gradle "implementation project(':kmpCompose')"
}

verify_kotlin_1_7_profile() {
    ./switch-kotlin-version.sh 1.7

    assert_contains gradle.properties "kotlinVersion=1.7.21"
    assert_contains gradle.properties "kspVersion=1.7.21-1.0.8"
    assert_contains gradle.properties "composeCompilerVersion=1.4.0-alpha02"
    assert_contains gradle.properties "agpVersion=7.2.2"
    assert_contains gradle.properties "excludeKmpCompose=true"
    assert_contains gradle/wrapper/gradle-wrapper.properties "gradle-7.3.3-all.zip"
    if grep -Fq "org.jetbrains.compose" build.gradle; then
        echo "ERROR: Kotlin 1.7 must not apply the Compose Multiplatform plugin"
        exit 1
    fi
    if grep -Fq "project(':kmpCompose')" app/build.gradle; then
        echo "ERROR: Kotlin 1.7 app must not depend on kmpCompose"
        exit 1
    fi
}

verify_profile() {
    local option=$1
    local kotlin_version=$2
    local compose_version=$3
    local agp_version=$4
    local gradle_version=$5
    local uses_kotlin_compose_plugin=$6

    ./switch-kotlin-version.sh "$option"

    assert_contains gradle.properties "kotlinVersion=$kotlin_version"
    assert_contains gradle.properties "composeVersion=$compose_version"
    assert_contains gradle.properties "agpVersion=$agp_version"
    assert_contains gradle/wrapper/gradle-wrapper.properties "gradle-$gradle_version-all.zip"
    assert_contains build.gradle "id 'org.jetbrains.compose' version \"\$compose_version\" apply false"
    assert_contains kmpCompose/build.gradle "id 'org.jetbrains.compose'"
    if [ "$uses_kotlin_compose_plugin" = true ]; then
        assert_contains kmpCompose/build.gradle "id 'org.jetbrains.kotlin.plugin.compose'"
    elif grep -Fq "org.jetbrains.kotlin.plugin.compose" kmpCompose/build.gradle; then
        echo "ERROR: Kotlin $kotlin_version must use the legacy Compose compiler integration"
        exit 1
    fi
    assert_fixture_enabled
}

verify_kotlin_1_7_profile
verify_profile 1.9 1.9.22 1.6.0 7.3.1 7.4 false
verify_profile 2.1 2.1.0 1.7.3 8.0.2 8.0 true
verify_profile 2.3 2.3.20 1.10.3 8.13.2 8.13 true

echo "All Kotlin version switch profiles passed."
