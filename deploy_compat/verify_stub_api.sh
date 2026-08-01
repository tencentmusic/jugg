#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <real-api-jugg-repo>" >&2
  exit 1
fi

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
real_root="$(cd "$1" && pwd)"
output_dir="$root_dir/build/stub-api-verify"

[[ -x "$real_root/gradlew" ]] || { echo "Invalid Jugg repository: $real_root" >&2; exit 1; }
[[ "$real_root" != "$root_dir" ]] || { echo "Real API repository must differ from $root_dir" >&2; exit 1; }

modules=()
for module_dir in "$root_dir"/deploy_compat/v_*; do
  [[ -d "$module_dir/src" ]] || continue
  module="${module_dir##*/}"
  [[ -d "$real_root/deploy_compat/$module/src" ]] || {
    echo "Missing real API module: $real_root/deploy_compat/$module" >&2
    exit 1
  }
  modules+=("$module")
done

[[ ${#modules[@]} -gt 0 ]] || { echo "No compat modules found in $root_dir" >&2; exit 1; }
mkdir -p "$output_dir"
find "$output_dir" -mindepth 1 -delete

for module in "${modules[@]}"; do
  if ! diff -qr "$root_dir/deploy_compat/$module/src" "$real_root/deploy_compat/$module/src" \
      > "$output_dir/$module.source.diff"; then
    echo "Source warning for $module; see $output_dir/$module.source.diff" >&2
  fi
done

build_modules() {
  local repo="$1"
  local tasks=()
  local module
  for module in "${modules[@]}"; do
    tasks+=(":deploy_compat:$module:clean")
  done
  for module in "${modules[@]}"; do
    tasks+=(":deploy_compat:$module:jar")
  done
  "$repo/gradlew" -p "$repo" "${tasks[@]}"
}

latest_jar() {
  local repo="$1"
  local module="$2"
  find "$repo/deploy_compat/$module/build/libs" -maxdepth 1 -type f -name '*.jar' \
      -exec stat -f '%m %N' {} \; | sort -nr | head -1 | cut -d' ' -f2-
}

extract_manifest() {
  local jar_path="$1"
  local prefix="$2"
  local class_name
  : > "$prefix.calls.unsorted"
  jar tf "$jar_path" | grep '\.class$' | sort > "$prefix.classes"
  while read -r class_name; do
    class_name="${class_name%.class}"
    class_name="${class_name//\//.}"
    javap -classpath "$jar_path" -c -p -s "$class_name" | awk -v class_name="$class_name" '
      /^  (public|private|protected|static)/ {
        method=$0
        gsub(/[[:space:]]+/, " ", method)
        sub(/^ /, "", method)
      }
      /\/\/ (Method|InterfaceMethod|Field|class) (com\/android|com\/intellij|org\/jetbrains\/android)/ {
        line=$0
        gsub(/[[:space:]]+/, " ", line)
        sub(/^ /, "", line)
        sub(/^[0-9]+: /, "", line)
        sub(/#[0-9]+(, +[0-9]+)? +/, "# ", line)
        print class_name " | " method " | " line
      }
    ' >> "$prefix.calls.unsorted"
  done < "$prefix.classes"
  sort "$prefix.calls.unsorted" > "$prefix.calls"
  rm "$prefix.calls.unsorted"
}

echo "Building Stub API repository: $root_dir"
build_modules "$root_dir"
echo "Building real API repository: $real_root"
build_modules "$real_root"

failed=0
for module in "${modules[@]}"; do
  stub_jar="$(latest_jar "$root_dir" "$module")"
  real_jar="$(latest_jar "$real_root" "$module")"
  [[ -n "$stub_jar" && -n "$real_jar" ]] || { echo "Missing JAR for $module" >&2; exit 1; }

  extract_manifest "$stub_jar" "$output_dir/$module.stub"
  extract_manifest "$real_jar" "$output_dir/$module.real"

  module_failed=0
  if ! diff -u "$output_dir/$module.real.classes" "$output_dir/$module.stub.classes" \
      > "$output_dir/$module.classes.diff"; then
    module_failed=1
  fi
  if ! diff -u "$output_dir/$module.real.calls" "$output_dir/$module.stub.calls" \
      > "$output_dir/$module.calls.diff"; then
    module_failed=1
  fi
  if [[ $module_failed -eq 0 ]]; then
    echo "$module MATCH ($(wc -l < "$output_dir/$module.stub.calls" | tr -d ' ') AS API references)"
  else
    echo "$module DIFF; see $output_dir/$module.*.diff" >&2
    failed=1
  fi
done

[[ $failed -eq 0 ]] || exit 1
echo "All ${#modules[@]} compat modules match."
