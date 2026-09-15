#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
jarjar_version="1.14.1"
jarjar_sha256="234081e26652f04740a02bd8ae030a167ab40258db2f8c4d166e2598bf4d57ec"
jarjar_url="https://repo.maven.apache.org/maven2/com/eed3si9n/jarjarabrams/jarjar-abrams-assembly_2.13/${jarjar_version}/jarjar-abrams-assembly_2.13-${jarjar_version}.jar"
kotlin_stdlib_version="2.0.0"
kotlin_stdlib_sha256="240938c4aab8e73e888703e3e7d3f87383ffe5bd536d6d5e3c100d4cd0379fcf"
kotlin_stdlib_url="https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/${kotlin_stdlib_version}/kotlin-stdlib-${kotlin_stdlib_version}.jar"
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

kotlin_stdlib_file="$task_temp_dir/kotlin-stdlib.jar"
curl -fsSL "$kotlin_stdlib_url" -o "$kotlin_stdlib_file"
actual_sha256="$(shasum -a 256 "$kotlin_stdlib_file" | awk '{print $1}')"
if [[ "$actual_sha256" != "$kotlin_stdlib_sha256" ]]; then
  echo "Unexpected Kotlin stdlib SHA-256: $actual_sha256" >&2
  exit 1
fi
kotlin_stdlib_relocated_jar="$task_temp_dir/kotlin-stdlib-jugg.jar"
kotlin_stdlib_relocated_dir="$kotlin_stdlib_relocated_jar.dir"
java -jar "$jarjar_file" process "$script_dir/jarjar-rules.txt" \
  "$kotlin_stdlib_file" "$kotlin_stdlib_relocated_jar"
mkdir "$kotlin_stdlib_relocated_dir"
unzip -q "$kotlin_stdlib_relocated_jar" -d "$kotlin_stdlib_relocated_dir"

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

relocate() {
  local source_jar="$1"
  local output_jar="$2"
  local expected_class="$3"
  local source_type="$4"
  local relocated_jar="$task_temp_dir/$(basename "$output_jar").raw"
  local relocated_jar_dir="$relocated_jar.dir"
  local normalized_jar="$task_temp_dir/$(basename "$output_jar")"
  local normalized_entries="$normalized_jar.entries"

  java -jar "$jarjar_file" process "$script_dir/jarjar-rules.txt" "$source_jar" "$relocated_jar"

  mkdir "$relocated_jar_dir"
  unzip -q "$relocated_jar" -d "$relocated_jar_dir"
  if [[ "$source_type" == "dex" ]]; then
    local dexlib2_dir="$relocated_jar_dir/com/sickworm/intellij/jugg/internal/dragonfly/runtime/dexlib2"
    local kotlin_dir="$relocated_jar_dir/com/sickworm/intellij/jugg/internal/dragonfly/runtime/kotlin"
    if [[ -d "$dexlib2_dir" ]]; then
      find "$dexlib2_dir" -depth -delete
    fi
    if [[ -d "$kotlin_dir" ]]; then
      find "$kotlin_dir" -depth -delete
    fi

    # dex2jar emits Java 8 class files without StackMapTable. Java 6 class version keeps D8 verification warning-free.
    find "$relocated_jar_dir" -type f -name '*.class' -exec perl -0777 -pi -e '
      die "Invalid class file: $ARGV\n" unless substr($_, 0, 4) eq "\xca\xfe\xba\xbe";
      my $major = unpack("n", substr($_, 6, 2));
      die "Unexpected dex2jar class version $major: $ARGV\n" unless $major == 52;
      substr($_, 6, 2) = pack("n", 50);
    ' {} +

    local kotlin_source_dir="$kotlin_stdlib_relocated_dir/com/sickworm/intellij/jugg/internal/dragonfly/runtime/kotlin"
    mkdir -p "$(dirname "$kotlin_dir")"
    cp -R "$kotlin_source_dir" "$kotlin_dir"
  elif [[ "$source_type" == "class" ]]; then
    local jvmti_bridge_dir="$relocated_jar_dir/com/sickworm/intellij/jugg/internal/dragonfly/jvmti"
    if [[ -d "$jvmti_bridge_dir" ]]; then
      find "$jvmti_bridge_dir" -type f \
        \( -name 'DragonflyJvmtiBridge.class' -o -name 'DragonflyJvmtiBridge$BridgeMethod.class' \) \
        -delete
    fi
  else
    echo "Unsupported Dragonfly source type: $source_type" >&2
    exit 1
  fi

  (cd "$relocated_jar_dir" && jar cMf "$normalized_jar" .)
  jar tf "$normalized_jar" > "$normalized_entries"

  if grep -Eq '^(top/kokomi/dragonfly|kotlin|kotlinx/coroutines|com/google/common|org/jf|_COROUTINE)/' "$normalized_entries"; then
    echo "Unrelocated Dragonfly runtime classes remain in $normalized_jar" >&2
    exit 1
  fi
  if ! grep -q "$expected_class" "$normalized_entries"; then
    echo "Expected class $expected_class is missing from $normalized_jar" >&2
    exit 1
  fi
  if grep -q '^com/sickworm/intellij/jugg/internal/dragonfly/jvmti/DragonflyJvmtiBridge' "$normalized_entries"; then
    echo "Unsupported Dragonfly JVMTI bridge classes remain in $normalized_jar" >&2
    exit 1
  fi
  if grep -q '^com/sickworm/intellij/jugg/internal/dragonfly/runtime/dexlib2/' "$normalized_entries"; then
    echo "Optional dexlib2 classes remain in $normalized_jar" >&2
    exit 1
  fi
  if [[ "$source_type" == "dex" ]]; then
    local kotlin_class
    for kotlin_class in UInt.class text/UStringsKt.class; do
      if ! grep -q "^com/sickworm/intellij/jugg/internal/dragonfly/runtime/kotlin/$kotlin_class$" "$normalized_entries"; then
        echo "Source-compiled Kotlin runtime class $kotlin_class is missing from $normalized_jar" >&2
        exit 1
      fi
    done
  fi

  mv "$normalized_jar" "$output_jar"
}

relocate \
  "$script_dir/dragonfly_0.jar" \
  "$script_dir/dragonfly_0-jugg.jar" \
  '^com/sickworm/intellij/jugg/internal/dragonfly/Dragonfly.class$' \
  "class"

implementation_classes_jar="$task_temp_dir/implementation_0-dex2jar.jar"
"$dex2jar_command" -f -o "$implementation_classes_jar" "$script_dir/implementation_0.jar"
relocate \
  "$implementation_classes_jar" \
  "$script_dir/implementation_0-jugg.jar" \
  '^com/sickworm/intellij/jugg/internal/dragonfly/runtime/kotlin/LazyThreadSafetyMode.class$' \
  "dex"

shasum -a 256 \
  "$script_dir/dragonfly_0-jugg.jar" \
  "$script_dir/implementation_0-jugg.jar"
