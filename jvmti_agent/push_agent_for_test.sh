#!/bin/bash

cd "$(dirname "$0")"

# check arguments
if [ $# -eq 0 ]; then
    echo "Usage: $0 <package-name>"
    exit 1
fi
PACKAGE_NAME=$1

# get agent version
DEPLOY_DIR="build/outputs/bundle/deploy"
VERSION_FILE=$(ls "$DEPLOY_DIR" | grep -E 'jugg-agent-bundle-(.*).zip' | head -1)

if [ -z "$VERSION_FILE" ]; then
    echo "Error: No version file found in $DEPLOY_DIR"
    exit 2
fi

VERSION=$(echo "$VERSION_FILE" | sed -E 's/jugg-agent-bundle-(.*).zip/\1/')
echo "Detected version: $VERSION"

# push jugg-instruments.jar
REMOTE_TMP_DIR="/data/local/tmp/jugg/$VERSION"
adb shell "mkdir -p $REMOTE_TMP_DIR"
adb push "build/outputs/jugg-instruments.jar" "$REMOTE_TMP_DIR/"

# push jugg_jvmti_agent.so（need run-as permission）
AGENT_SRC="build/intermediates/merged_native_libs/release/out/lib/arm64-v8a/jugg_jvmti_agent.so"
REMOTE_AGENT_DIR="/data/data/$PACKAGE_NAME/code_cache/startup_agents"
REMOTE_AGENT_PATH="$REMOTE_AGENT_DIR/${VERSION}-jugg_jvmti_agent.so"

adb shell "run-as $PACKAGE_NAME mkdir -p $REMOTE_AGENT_DIR"
adb push "$AGENT_SRC" "/data/local/tmp/jugg/tmp_agent.so"
adb shell "run-as $PACKAGE_NAME cp /data/local/tmp/jugg/tmp_agent.so \"$REMOTE_AGENT_PATH\""
adb shell "rm /data/local/tmp/jugg/tmp_agent.so"

# clear old agents
CLEAN_CMD="find $REMOTE_AGENT_DIR -name '*jugg_jvmti_agent.so' ! -name '${VERSION}*' -delete"
adb shell "run-as $PACKAGE_NAME sh -c \"$CLEAN_CMD\""

echo "Deployment completed successfully"