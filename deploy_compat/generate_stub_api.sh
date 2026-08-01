#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <version> <compiled-compat.jar> <android-studio-jar-dir>" >&2
  exit 1
fi

version="${1#v_}"
compiled_jar="$2"
jar_dir="$3"
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
output="$root_dir/deploy_compat/stub_api/v_${version}/stubapi.jar"

[[ -f "$compiled_jar" ]] || { echo "Compiled JAR does not exist: $compiled_jar" >&2; exit 1; }
[[ -d "$jar_dir" ]] || { echo "Android Studio JAR directory does not exist: $jar_dir" >&2; exit 1; }

"$root_dir/gradlew" -p "$root_dir" :tools:stub_api_generator:run \
  --args="--input '$compiled_jar' --classpath '$jar_dir' --output '$output'"

echo "Generated $output"
