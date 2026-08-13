#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
jarjar_version="1.14.1"
jarjar_sha256="234081e26652f04740a02bd8ae030a167ab40258db2f8c4d166e2598bf4d57ec"
jarjar_url="https://repo.maven.apache.org/maven2/com/eed3si9n/jarjarabrams/jarjar-abrams-assembly_2.13/${jarjar_version}/jarjar-abrams-assembly_2.13-${jarjar_version}.jar"
dex2jar_version="2.4"
dex2jar_sha256="ee7c45eb3c1d2474a6145d8d447e651a736a22d9664b6d3d3be5a5a817dda23a"
dex2jar_url="https://github.com/pxb1988/dex2jar/releases/download/v${dex2jar_version}/dex-tools-v${dex2jar_version}.zip"
task_temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/jugg-dragonfly.XXXXXX")"

cleanup() {
  find "$task_temp_dir" -depth -delete
}
trap cleanup EXIT

jarjar_file="$task_temp_dir/jarjar.jar"
curl -fsSL "$jarjar_url" -o "$jarjar_file"
actual_sha256="$(shasum -a 256 "$jarjar_file" | awk '{print $1}')"
if [[ "$actual_sha256" != "$jarjar_sha256" ]]; then
  echo "Unexpected Jar Jar Abrams SHA-256: $actual_sha256" >&2
  exit 1
fi

dex2jar_zip="$task_temp_dir/dex2jar.zip"
curl -fsSL "$dex2jar_url" -o "$dex2jar_zip"
actual_sha256="$(shasum -a 256 "$dex2jar_zip" | awk '{print $1}')"
if [[ "$actual_sha256" != "$dex2jar_sha256" ]]; then
  echo "Unexpected dex2jar SHA-256: $actual_sha256" >&2
  exit 1
fi
unzip -q "$dex2jar_zip" -d "$task_temp_dir/dex2jar"
dex2jar_command="$task_temp_dir/dex2jar/dex-tools-v${dex2jar_version}/d2j-dex2jar.sh"
chmod +x "$dex2jar_command"

preprocess() {
  local source_dex_jar="$1"
  local output_jar="$2"
  local expected_class="$3"
  local source_jar="$task_temp_dir/$(basename "$source_dex_jar" .jar)-classes.jar"
  local relocated_jar="$task_temp_dir/$(basename "$output_jar")"
  local relocated_entries="$relocated_jar.entries"

  "$dex2jar_command" -f -o "$source_jar" "$source_dex_jar"
  java -jar "$jarjar_file" process "$script_dir/jarjar-rules.txt" "$source_jar" "$relocated_jar"
  jar tf "$relocated_jar" > "$relocated_entries"

  if grep -Eq '^(top/kokomi/dragonfly|kotlin|kotlinx/coroutines|com/google/common|org/jf|_COROUTINE)/' "$relocated_entries"; then
    echo "Unrelocated Dragonfly runtime classes remain in $relocated_jar" >&2
    exit 1
  fi
  if ! grep -q "$expected_class" "$relocated_entries"; then
    echo "Expected class $expected_class is missing from $relocated_jar" >&2
    exit 1
  fi

  mv "$relocated_jar" "$output_jar"
}

preprocess \
  "$script_dir/dragonfly_0.jar" \
  "$script_dir/dragonfly_0-jugg.jar" \
  '^com/sickworm/intellij/jugg/internal/dragonfly/Dragonfly.class$'
preprocess \
  "$script_dir/implementation_0.jar" \
  "$script_dir/implementation_0-jugg.jar" \
  '^com/sickworm/intellij/jugg/internal/dragonfly/runtime/kotlin/LazyThreadSafetyMode.class$'

shasum -a 256 \
  "$script_dir/dragonfly_0-jugg.jar" \
  "$script_dir/implementation_0-jugg.jar"
