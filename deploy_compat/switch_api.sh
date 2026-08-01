#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
config="$root_dir/deploy_compat/local.properties"
mode="${1:-}"

case "$mode" in
  stub)
    printf 'mode=stub\n' > "$config"
    echo "Compat modules now use committed Stub API JARs."
    ;;
  real)
    jar_dir="${2:-}"
    [[ -d "$jar_dir" ]] || {
      echo "Usage: $0 real <android-studio-jar-dir>" >&2
      exit 1
    }
    printf 'mode=real\njarDir=%s\n' "$jar_dir" > "$config"
    echo "Compat modules now use real JARs from $jar_dir."
    ;;
  *)
    echo "Usage: $0 <stub|real> [android-studio-jar-dir]" >&2
    exit 1
    ;;
esac
