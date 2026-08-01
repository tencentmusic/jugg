#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <version> [parent-version]" >&2
  exit 1
fi

version="${1#v_}"
parent="${2:-}"
parent="${parent#v_}"
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
module_dir="$root_dir/deploy_compat/v_${version}"
settings="$root_dir/settings.gradle"

[[ ! -e "$module_dir" ]] || { echo "Module already exists: $module_dir" >&2; exit 1; }
mkdir -p "$module_dir/src/main/java/com/sickworm/intellij/jugg/deploy/run"

cp "$root_dir/deploy_compat/v_quail/build.gradle" "$module_dir/build.gradle"
if [[ -n "$parent" ]]; then
  sed -i.bak "/implementation project(':deploy_compat:interface')/a\\
    api project(':deploy_compat:v_${parent}')
" "$module_dir/build.gradle"
  rm "$module_dir/build.gradle.bak"
fi

printf "include ':deploy_compat:v_%s'\n" "$version" >> "$settings"
echo "Created deploy_compat/v_${version}. Add the implementation, switch to real JARs, and compile it."
