#!/bin/bash
# Signs an APK in place, used to test the Jugg "Enable custom APK sign script" option.
# Usage: ./scripts/sign-system-apk.sh /absolute/path/to/app.apk
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <apk-path>" >&2
    exit 2
fi

apk_path="$1"
if [ ! -f "$apk_path" ]; then
    echo "APK not found: $apk_path" >&2
    exit 1
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(dirname "$script_dir")"

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk_dir" ] && [ -f "$project_dir/local.properties" ]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$project_dir/local.properties" | tail -n 1)"
fi
if [ ! -d "$sdk_dir" ]; then
    echo "Android SDK not found, set ANDROID_HOME or sdk.dir in local.properties." >&2
    exit 1
fi

apksigner="$(ls -1 "$sdk_dir"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1)"
if [ ! -x "$apksigner" ]; then
    echo "apksigner not found in $sdk_dir/build-tools." >&2
    exit 1
fi

keystore="${HOME}/.android/debug.keystore"
if [ ! -f "$keystore" ]; then
    echo "Debug keystore not found: $keystore" >&2
    exit 1
fi

echo "Signing APK with $(basename "$apksigner"): $apk_path"
"$apksigner" sign \
    --ks "$keystore" \
    --ks-pass pass:android \
    --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    "$apk_path"
