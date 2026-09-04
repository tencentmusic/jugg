#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR=""
OUTPUT_ROOT="$SCRIPT_DIR"
INCLUDE_APKS="yes"
SKIP_ADB=false
OPEN_FINDER=true
PACKAGE_NAME=""
DEVICE_SERIAL=""
ADB_BIN=""
ADB_RESOLUTION_SOURCE=""
ADB_RESOLUTION_SOURCES=()
ADB_RESOLUTION_CANDIDATES=()
ADB_TARGET_ARGS=()
ADB_TARGET_SERIALS=()
DEVICE_OUTPUT_DIR="device"
INTERACTIVE_MODE=false
MAKE_ZIP=false
ZIP_PATH=""
PYTHON_BIN=""

usage() {
  cat <<'USAGE'
Usage:
  collect_jugg_scene.command [project_dir] [--output-root DIR] [--include-apks yes|no] [--package-name NAME] [--device-serial SERIAL] [--skip-adb] [--no-open] [--zip]

Double-click usage:
  Run this .command file, input the Android project directory, then find the
  generated jugg_scene_* folder beside this script.

Notes:
  APK files are copied by default. Use --include-apks no only when size matters.
  Device collection uses adb when available, including pm path, installed APK
  hashes, and dex files under code_cache/.overlay.
  When multiple devices are connected, pass --device-serial SERIAL or set
  ANDROID_SERIAL to collect one device. Otherwise all online devices from
  adb devices are collected.
  Use --zip to write a sibling .zip and reveal it in Finder, Explorer, or the
  file manager. Git Bash and WSL can run this script on Windows.
USAGE
}

pause_if_interactive() {
  if [[ "$INTERACTIVE_MODE" == "true" ]]; then
    echo
    read -r -p "Press Enter to exit..." _
  fi
}

fail() {
  echo "Error: $*" >&2
  pause_if_interactive
  exit 1
}

clean_input_path() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  value="${value%\"}"
  value="${value#\"}"
  value="${value%\'}"
  value="${value#\'}"
  if [[ "$value" == "~" ]]; then
    value="$HOME"
  elif [[ "$value" == "~/"* ]]; then
    value="$HOME/${value#"~/"}"
  fi
  printf '%s' "$value"
}

to_unix_path() {
  local value="$1"
  [[ -n "$value" ]] || return 0
  if command -v cygpath >/dev/null 2>&1 && [[ "$value" == [A-Za-z]:* || "$value" == *\\* ]]; then
    cygpath -u "$value"
  elif command -v wslpath >/dev/null 2>&1 && [[ "$value" == [A-Za-z]:* || "$value" == *\\* ]]; then
    wslpath -u "$value"
  else
    printf '%s' "$value"
  fi
}

to_native_path() {
  local value="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$value"
  elif command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$value"
  else
    printf '%s' "$value"
  fi
}

unescape_sdk_dir() {
  local sdk_dir="$1"
  sdk_dir="${sdk_dir%$'\r'}"
  sdk_dir="${sdk_dir//\\:/:}"
  sdk_dir="${sdk_dir//\\//}"
  to_unix_path "$sdk_dir"
}

resolve_python() {
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
  elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
  fi
}

file_sha256() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$file" | awk '{print $NF}'
  else
    printf 'unavailable'
    return 1
  fi
}

file_sha256_line() {
  local file="$1"
  local display="$2"
  local hash
  if hash="$(file_sha256 "$file")"; then
    printf '%s  %s\n' "$hash" "$display"
  else
    printf 'sha256 unavailable: %s\n' "$display"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-root)
      [[ $# -ge 2 ]] || fail "--output-root requires a directory"
      OUTPUT_ROOT="$2"
      shift 2
      ;;
    --include-apks)
      [[ $# -ge 2 ]] || fail "--include-apks requires yes or no"
      INCLUDE_APKS="$2"
      shift 2
      ;;
    --package-name)
      [[ $# -ge 2 ]] || fail "--package-name requires a package name"
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --device|--device-serial)
      [[ $# -ge 2 ]] || fail "$1 requires a device serial"
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --skip-adb)
      SKIP_ADB=true
      shift
      ;;
    --no-open)
      OPEN_FINDER=false
      shift
      ;;
    --zip)
      MAKE_ZIP=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if [[ -z "$PROJECT_DIR" ]]; then
        PROJECT_DIR="$1"
        shift
      else
        fail "unknown argument: $1"
      fi
      ;;
  esac
done

if [[ -z "$PROJECT_DIR" ]]; then
  INTERACTIVE_MODE=true
  echo "Input the Android project directory to collect."
  read -r -p "Project directory: " PROJECT_DIR
fi

PROJECT_DIR="$(to_unix_path "$(clean_input_path "$PROJECT_DIR")")"
OUTPUT_ROOT="$(to_unix_path "$(clean_input_path "$OUTPUT_ROOT")")"
PACKAGE_NAME="$(clean_input_path "$PACKAGE_NAME")"
DEVICE_SERIAL="$(clean_input_path "$DEVICE_SERIAL")"
resolve_python

[[ -d "$PROJECT_DIR" ]] || fail "project directory does not exist: $PROJECT_DIR"
[[ -d "$OUTPUT_ROOT" ]] || fail "output root does not exist: $OUTPUT_ROOT"

JUGG_DIR="$PROJECT_DIR/build/jugg"
[[ -d "$JUGG_DIR" ]] || fail "missing build/jugg under project: $PROJECT_DIR"

case "$INCLUDE_APKS" in
  y|Y|yes|YES|true|TRUE) INCLUDE_APKS="yes" ;;
  n|N|no|NO|false|FALSE) INCLUDE_APKS="no" ;;
  *) fail "--include-apks must be yes or no" ;;
esac

project_name="$(basename "$PROJECT_DIR")"
timestamp="$(date '+%Y%m%d_%H%M%S')"
OUT_DIR="$OUTPUT_ROOT/jugg_scene_${project_name}_${timestamp}"
suffix=1
while [[ -e "$OUT_DIR" ]]; do
  OUT_DIR="$OUTPUT_ROOT/jugg_scene_${project_name}_${timestamp}_$suffix"
  suffix=$((suffix + 1))
done

mkdir -p "$OUT_DIR/meta"

copy_file() {
  local src="$1"
  local dest_rel="$2"
  [[ -f "$src" ]] || return 0
  mkdir -p "$OUT_DIR/$(dirname "$dest_rel")"
  cp -p "$src" "$OUT_DIR/$dest_rel"
}

copy_dir() {
  local src="$1"
  local dest_rel="$2"
  [[ -d "$src" ]] || return 0
  mkdir -p "$OUT_DIR/$dest_rel"
  cp -R -p "$src/." "$OUT_DIR/$dest_rel/"
}

run_capture() {
  local dest_rel="$1"
  shift
  mkdir -p "$OUT_DIR/$(dirname "$dest_rel")"
  {
    echo "$ $*"
    "$@"
  } >"$OUT_DIR/$dest_rel" 2>&1 || true
}

try_adb_candidate() {
  local source="$1"
  local candidate="$2"
  [[ -n "$candidate" ]] || return 1

  ADB_RESOLUTION_SOURCES+=("$source")
  ADB_RESOLUTION_CANDIDATES+=("$candidate")
  if [[ -x "$candidate" ]] || { [[ -f "$candidate" ]] && [[ "$candidate" == *.exe ]]; }; then
    ADB_BIN="$candidate"
    ADB_RESOLUTION_SOURCE="$source"
    return 0
  fi
  return 1
}

try_adb_pair() {
  local source="$1"
  local sdk_dir="$2"
  [[ -n "$sdk_dir" ]] || return 1
  sdk_dir="$(to_unix_path "$sdk_dir")"
  try_adb_candidate "$source" "$sdk_dir/platform-tools/adb" && return 0
  try_adb_candidate "$source" "$sdk_dir/platform-tools/adb.exe" && return 0
  return 1
}

resolve_adb() {
  local path_adb=""
  local sdk_dir=""

  if path_adb="$(command -v adb 2>/dev/null)" && try_adb_candidate "PATH" "$path_adb"; then
    return 0
  fi
  if path_adb="$(command -v adb.exe 2>/dev/null)" && try_adb_candidate "PATH" "$path_adb"; then
    return 0
  fi

  if try_adb_pair "ANDROID_SDK_ROOT" "${ANDROID_SDK_ROOT:-}"; then
    return 0
  fi
  if try_adb_pair "ANDROID_HOME" "${ANDROID_HOME:-}"; then
    return 0
  fi

  if [[ -f "$PROJECT_DIR/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_DIR/local.properties" | tail -n 1)"
    sdk_dir="$(unescape_sdk_dir "$sdk_dir")"
    if try_adb_pair "local.properties" "$sdk_dir"; then
      return 0
    fi
  fi

  if try_adb_pair "macOS default SDK" "$HOME/Library/Android/sdk"; then
    return 0
  fi
  if try_adb_pair "Linux default SDK" "$HOME/Android/Sdk"; then
    return 0
  fi
  try_adb_pair "Windows default SDK" "${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk"
}

write_adb_resolution() {
  local index
  {
    echo "Selected: ${ADB_BIN:-unavailable}"
    echo "Source: ${ADB_RESOLUTION_SOURCE:-unavailable}"
    echo "Candidates:"
    for ((index = 0; index < ${#ADB_RESOLUTION_CANDIDATES[@]}; index++)); do
      printf '  %s: %s\n' "${ADB_RESOLUTION_SOURCES[$index]}" "${ADB_RESOLUTION_CANDIDATES[$index]}"
    done
  } > "$OUT_DIR/meta/adb_resolution.txt"
}

write_summary() {
  cat > "$OUT_DIR/summary.txt" <<SUMMARY
Jugg scene bundle
CollectedAt: $(date '+%Y-%m-%d %H:%M:%S %z')
Project: $PROJECT_DIR
JuggDir: $JUGG_DIR
Output: $OUT_DIR
Zip: pending
IncludeApks: $INCLUDE_APKS
SkipAdb: $SKIP_ADB
OpenFinder: $OPEN_FINDER
MakeZip: $MAKE_ZIP
PackageName: ${PACKAGE_NAME:-auto}
DeviceSerial: ${DEVICE_SERIAL:-all-online}
SUMMARY
}

collect_logs() {
  local log_dir="$JUGG_DIR/log"
  [[ -d "$log_dir" ]] || return 0

  find "$log_dir" -maxdepth 1 -type f \( -name 'compile*.log' -o -name 'logcat*.log' -o -name '*.log' \) -print0 |
    while IFS= read -r -d '' file; do
      copy_file "$file" "log/$(basename "$file")"
    done
}

collect_jugg_state() {
  copy_dir "$JUGG_DIR/database" "database"
  copy_dir "$JUGG_DIR/build/staging" "staging"
  copy_dir "$JUGG_DIR/build/compiled" "compiled"
  copy_dir "$JUGG_DIR/config" "config"
  copy_dir "$JUGG_DIR/mcp_fetch" "mcp_fetch"
}

collect_classpath() {
  local classpath_dir="$JUGG_DIR/classpath"
  local inventory="$OUT_DIR/meta/classpath_inventory.txt"
  local r_jar_inventory="$OUT_DIR/meta/r_jar_inventory.txt"

  if [[ ! -d "$classpath_dir" ]]; then
    echo "No classpath directory: $classpath_dir" > "$inventory"
    echo "No classpath directory: $classpath_dir" > "$r_jar_inventory"
    return 0
  fi

  {
    echo "Classpath files:"
    find "$classpath_dir" -maxdepth 6 -type f -print | sort
    echo
    echo "APK hashes:"
    find "$classpath_dir" -type f -name '*.apk' -print0 |
      while IFS= read -r -d '' apk; do
        file_sha256_line "$apk" "$apk"
      done
  } > "$inventory"

  if [[ -d "$classpath_dir/root" ]]; then
    find "$classpath_dir/root" -type f \( -name 'mapping.txt' -o -name 'usage.txt' -o -name 'seeds.txt' \) -print0 |
      while IFS= read -r -d '' file; do
        local rel="${file#"$classpath_dir/"}"
        copy_file "$file" "classpath/$rel"
      done

    : > "$r_jar_inventory"
    find "$classpath_dir/root" -type f -name 'R.jar' -print0 |
      while IFS= read -r -d '' file; do
        local rel="${file#"$classpath_dir/"}"
        local size
        local modified_at
        local sha256="unavailable"
        size="$(wc -c < "$file" | tr -d '[:space:]')"
        if modified_at="$(stat -f '%m' "$file" 2>/dev/null)"; then
          :
        elif modified_at="$(stat -c '%Y' "$file" 2>/dev/null)"; then
          :
        else
          modified_at="unavailable"
        fi
        sha256="$(file_sha256 "$file" || true)"
        printf '%s\tsize=%s\tmtime=%s\tsha256=%s\n' "$rel" "$size" "$modified_at" "$sha256" >> "$r_jar_inventory"
        copy_file "$file" "classpath/$rel"
      done
  else
    echo "No classpath root directory: $classpath_dir/root" > "$r_jar_inventory"
  fi

  if [[ "$INCLUDE_APKS" == "yes" ]]; then
    copy_dir "$classpath_dir/apk" "apk"
  fi
}

collect_project_meta() {
  run_capture "meta/environment.txt" env
  run_capture "meta/project_tree.txt" find "$PROJECT_DIR/build/jugg" -maxdepth 3 -print

  if command -v git >/dev/null 2>&1 && git -C "$PROJECT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    run_capture "meta/git_head.txt" git -C "$PROJECT_DIR" rev-parse HEAD
    run_capture "meta/git_branch.txt" git -C "$PROJECT_DIR" branch --show-current
    run_capture "meta/git_status.txt" git -C "$PROJECT_DIR" status --short
    run_capture "meta/git_changed_files.txt" git -C "$PROJECT_DIR" diff --name-only
  else
    echo "Not a git worktree: $PROJECT_DIR" > "$OUT_DIR/meta/git_status.txt"
  fi
}

infer_package_names() {
  local candidates_file="$OUT_DIR/meta/package_candidates.txt"
  : > "$candidates_file"

  if [[ -n "$PACKAGE_NAME" ]]; then
    printf '%s\n' "$PACKAGE_NAME" >> "$candidates_file"
  fi

  if [[ -n "$PYTHON_BIN" ]]; then
    "$PYTHON_BIN" - "$JUGG_DIR" >> "$candidates_file" <<'PY' || true
import json
import re
import sys
from pathlib import Path

jugg_dir = Path(sys.argv[1])
json_paths = [
    jugg_dir / "database" / "project_infos.db" / "project_infos.json",
    jugg_dir / "database" / "project_infos.db" / "gradle_project_infos.json",
]
keys = {"applicationId", "packageName", "instrumentationTargetPackage"}
pattern = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$")

def walk(value, current_key=""):
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, key)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child, current_key)
    elif isinstance(value, str) and current_key in keys and pattern.match(value):
        yield value

for path in json_paths:
    if not path.is_file():
        continue
    try:
        data = json.loads(path.read_text())
    except Exception:
        continue
    for package_name in walk(data):
        print(package_name)
PY
  fi

  find "$JUGG_DIR/log" -maxdepth 1 -type f -name 'compile*.log' -print0 2>/dev/null |
    xargs -0 grep -Eho '(applicationId|packageName|packageName in APK|package name)[^A-Za-z0-9_.]+[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+' 2>/dev/null |
    sed -E 's/.*[^A-Za-z0-9_.]([A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+)$/\1/' >> "$candidates_file" || true

  sort -u "$candidates_file" -o "$candidates_file"
}

hash_files_under() {
  local src_dir="$1"
  local dest_rel="$2"
  mkdir -p "$OUT_DIR/$(dirname "$dest_rel")"
  if [[ ! -d "$src_dir" ]]; then
    echo "Missing directory: $src_dir" > "$OUT_DIR/$dest_rel"
    return 0
  fi
  find "$src_dir" -type f -name '*.apk' -print0 |
    while IFS= read -r -d '' file; do
      local rel="${file#"$OUT_DIR/"}"
      file_sha256_line "$file" "$rel"
    done | sort > "$OUT_DIR/$dest_rel"
}

pull_device_apks() {
  local package_name="$1"
  local package_dir="$OUT_DIR/$DEVICE_OUTPUT_DIR/packages/$package_name"
  local pm_path_file="$package_dir/pm_path.txt"
  mkdir -p "$package_dir/apk"

  run_capture "$DEVICE_OUTPUT_DIR/packages/$package_name/pm_path.txt" "$ADB_BIN" "${ADB_TARGET_ARGS[@]}" shell pm path "$package_name"

  sed -n 's/^package://p' "$pm_path_file" |
    while IFS= read -r remote_apk; do
      [[ -n "$remote_apk" ]] || continue
      local apk_name
      apk_name="$(basename "$remote_apk")"
      run_capture "$DEVICE_OUTPUT_DIR/packages/$package_name/apk/pull_${apk_name}.log" "$ADB_BIN" "${ADB_TARGET_ARGS[@]}" pull "$remote_apk" "$package_dir/apk/$apk_name"
    done
}

dump_device_overlay_dex() {
  local package_name="$1"
  local package_dir="$OUT_DIR/$DEVICE_OUTPUT_DIR/packages/$package_name"
  local overlay_dir="$OUT_DIR/$DEVICE_OUTPUT_DIR/packages/$package_name/overlay_dex"
  local listing_file="$OUT_DIR/$DEVICE_OUTPUT_DIR/packages/$package_name/overlay_dex_list.txt"
  local error_log="$package_dir/overlay_dex_pull_errors.log"
  local tmp_error="$package_dir/overlay_dex_pull.tmp"
  mkdir -p "$overlay_dir"
  rm -f "$error_log" "$tmp_error"

  "$ADB_BIN" "${ADB_TARGET_ARGS[@]}" shell run-as "$package_name" find code_cache/.overlay -type f -name '*.dex' -print \
    > "$listing_file" 2>&1 || true

  sed -n '/^code_cache\/.overlay\/.*\.dex$/p' "$listing_file" |
    while IFS= read -r remote_dex; do
      local rel="${remote_dex#code_cache/.overlay/}"
      local dest="$overlay_dir/$rel"
      mkdir -p "$(dirname "$dest")"
      if ! "$ADB_BIN" "${ADB_TARGET_ARGS[@]}" exec-out run-as "$package_name" cat "$remote_dex" > "$dest" 2>"$tmp_error"; then
        {
          echo "Failed to pull $remote_dex"
          cat "$tmp_error"
          echo
        } >> "$error_log"
        rm -f "$dest"
      fi
      rm -f "$tmp_error"
    done

  [[ -s "$error_log" ]] || rm -f "$error_log"
}

dump_device_overlay_resources() {
  local package_name="$1"
  local package_dir="$OUT_DIR/$DEVICE_OUTPUT_DIR/packages/$package_name"
  local overlay_dir="$package_dir/overlay_resources"
  local listing_file="$package_dir/overlay_files_list.txt"
  local hashes_file="$package_dir/overlay_resource_hashes.txt"
  local error_log="$package_dir/overlay_resource_pull_errors.log"
  local tmp_error="$package_dir/overlay_resource_pull.tmp"
  mkdir -p "$overlay_dir"
  rm -f "$hashes_file" "$error_log" "$tmp_error"

  "$ADB_BIN" "${ADB_TARGET_ARGS[@]}" shell run-as "$package_name" find code_cache/.overlay -type f -print \
    > "$listing_file" 2>&1 || true

  while IFS= read -r remote_file; do
    case "$remote_file" in
      */resource.ap_|*/resources.arsc|*/.jugg_compat_deploy_enable|*/id)
        ;;
      *)
        continue
        ;;
    esac

    local rel="${remote_file#code_cache/.overlay/}"
    local dest="$overlay_dir/$rel"
    mkdir -p "$(dirname "$dest")"
    if ! "$ADB_BIN" "${ADB_TARGET_ARGS[@]}" exec-out run-as "$package_name" cat "$remote_file" > "$dest" 2>"$tmp_error"; then
      {
        echo "Failed to pull $remote_file"
        cat "$tmp_error"
        echo
      } >> "$error_log"
      rm -f "$dest"
      rm -f "$tmp_error"
      continue
    fi

    file_sha256_line "$dest" "$rel" >> "$hashes_file"
    rm -f "$tmp_error"
  done < "$listing_file"

  [[ -s "$hashes_file" ]] || rm -f "$hashes_file"
  [[ -s "$error_log" ]] || rm -f "$error_log"
}

write_apk_consistency_report() {
  local report="$OUT_DIR/$DEVICE_OUTPUT_DIR/apk_consistency.txt"
  local local_hashes="$OUT_DIR/meta/local_apk_hashes.txt"
  local device_hashes="$OUT_DIR/$DEVICE_OUTPUT_DIR/device_apk_hashes.txt"
  mkdir -p "$OUT_DIR/$DEVICE_OUTPUT_DIR"

  hash_files_under "$OUT_DIR/apk" "meta/local_apk_hashes.txt"
  hash_files_under "$OUT_DIR/$DEVICE_OUTPUT_DIR" "$DEVICE_OUTPUT_DIR/device_apk_hashes.txt"

  {
    echo "APK consistency diagnosis"
    echo
    echo "Rule: this report compares exact sha256 of copied local APK files and pulled device APK files."
    echo "Result is diagnostic only. Split installs, resigned APKs, or missing pulled files require manual review."
    echo
    echo "Local APK hashes:"
    cat "$local_hashes"
    echo
    echo "Device APK hashes:"
    cat "$device_hashes"
    echo
    echo "Exact hash matches:"
    awk 'NR==FNR {local[$1]=local[$1] "\n  " $0; next} $1 in local {print $0 local[$1]}' "$local_hashes" "$device_hashes"
  } > "$report"
}

select_adb_devices() {
  local devices_file="$OUT_DIR/device/adb_devices.txt"
  local target_file="$OUT_DIR/meta/adb_targets.txt"
  local source=""
  local serial state rest
  ADB_TARGET_SERIALS=()
  mkdir -p "$OUT_DIR/meta"

  if [[ -n "$DEVICE_SERIAL" ]]; then
    source="--device-serial"
    ADB_TARGET_SERIALS+=("$DEVICE_SERIAL")
  elif [[ -n "${ANDROID_SERIAL:-}" ]]; then
    DEVICE_SERIAL="$ANDROID_SERIAL"
    source="ANDROID_SERIAL"
    ADB_TARGET_SERIALS+=("$DEVICE_SERIAL")
  else
    while read -r serial state rest; do
      [[ "$state" == "device" ]] || continue
      ADB_TARGET_SERIALS+=("$serial")
    done < "$devices_file"
    source="adb devices"
  fi

  {
    if [[ "${#ADB_TARGET_SERIALS[@]}" -gt 0 ]]; then
      echo "TargetCount: ${#ADB_TARGET_SERIALS[@]}"
      echo "SelectionSource: $source"
      printf 'TargetDevices:'
      for serial in "${ADB_TARGET_SERIALS[@]}"; do
        printf ' %s' "$serial"
      done
      printf '\n'
      if [[ "$source" == "adb devices" && "${#ADB_TARGET_SERIALS[@]}" -gt 1 ]]; then
        echo "Note: multiple online devices found; collecting all online devices. Pass --device-serial to choose one."
      fi
    else
      echo "TargetCount: 0"
      echo "Note: no online adb device found."
    fi
  } > "$target_file"
  cp "$target_file" "$OUT_DIR/meta/adb_target.txt"
}

collect_adb_for_device() {
  local serial="$1"
  local use_device_subdir="$2"

  ADB_TARGET_ARGS=(-s "$serial")
  if [[ "$use_device_subdir" == "true" ]]; then
    DEVICE_OUTPUT_DIR="device/devices/$serial"
  else
    DEVICE_OUTPUT_DIR="device"
  fi

  run_capture "$DEVICE_OUTPUT_DIR/logcat_crash.log" "$ADB_BIN" "${ADB_TARGET_ARGS[@]}" logcat -b crash -d
  run_capture "$DEVICE_OUTPUT_DIR/logcat_tail.log" "$ADB_BIN" "${ADB_TARGET_ARGS[@]}" logcat -d -t 20000

  while IFS= read -r package_name; do
    [[ -n "$package_name" ]] || continue
    if [[ ! "$package_name" =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]]; then
      echo "Skip invalid package name: $package_name" >> "$OUT_DIR/$DEVICE_OUTPUT_DIR/package_skip.txt"
      continue
    fi
    pull_device_apks "$package_name"
    dump_device_overlay_dex "$package_name"
    dump_device_overlay_resources "$package_name"
  done < "$OUT_DIR/meta/package_candidates.txt"

  write_apk_consistency_report
}

collect_adb_snapshot() {
  if [[ "$SKIP_ADB" == "true" ]]; then
    echo "Skipped by --skip-adb" > "$OUT_DIR/meta/adb_skipped.txt"
    return 0
  fi

  if ! resolve_adb; then
    write_adb_resolution
    echo "adb executable not found in PATH, Android SDK environment variables, project local.properties, or default SDK directories" > "$OUT_DIR/meta/adb_unavailable.txt"
    return 0
  fi
  write_adb_resolution

  run_capture "device/adb_devices.txt" "$ADB_BIN" devices -l
  select_adb_devices
  infer_package_names

  local use_device_subdir="false"
  if [[ "${#ADB_TARGET_SERIALS[@]}" -gt 1 ]]; then
    use_device_subdir="true"
  fi
  if [[ "${#ADB_TARGET_SERIALS[@]}" -eq 0 ]]; then
    DEVICE_OUTPUT_DIR="device"
    write_apk_consistency_report
    return 0
  fi

  local serial
  for serial in "${ADB_TARGET_SERIALS[@]}"; do
    collect_adb_for_device "$serial" "$use_device_subdir"
  done
}

write_manifest() {
  find "$OUT_DIR" -type f -print |
    sed "s#^$OUT_DIR/##" |
    sort > "$OUT_DIR/manifest.txt"
}

update_summary_zip() {
  local zip_value="${1:-unavailable}"
  local summary="$OUT_DIR/summary.txt"
  local tmp="$summary.tmp"
  awk -v zip="$zip_value" '
    BEGIN { done = 0 }
    /^Zip:/ { print "Zip: " zip; done = 1; next }
    { print }
    END { if (!done) print "Zip: " zip }
  ' "$summary" > "$tmp"
  mv "$tmp" "$summary"
}

package_zip() {
  [[ "$MAKE_ZIP" == "true" ]] || return 0
  ZIP_PATH="${OUT_DIR}.zip"
  rm -f "$ZIP_PATH"
  if command -v ditto >/dev/null 2>&1; then
    ditto -c -k --keepParent "$OUT_DIR" "$ZIP_PATH" || ZIP_PATH=""
  elif command -v zip >/dev/null 2>&1; then
    (
      cd "$(dirname "$OUT_DIR")"
      zip -r -q "$(basename "$ZIP_PATH")" "$(basename "$OUT_DIR")"
    ) || ZIP_PATH=""
  elif command -v tar >/dev/null 2>&1; then
    tar -a -cf "$ZIP_PATH" -C "$(dirname "$OUT_DIR")" "$(basename "$OUT_DIR")" || ZIP_PATH=""
  elif command -v powershell.exe >/dev/null 2>&1; then
    local win_src
    local win_zip
    win_src="$(to_native_path "$OUT_DIR")"
    win_zip="$(to_native_path "$ZIP_PATH")"
    powershell.exe -NoProfile -Command "Compress-Archive -Path \"$win_src\" -DestinationPath \"$win_zip\" -Force" || ZIP_PATH=""
  else
    ZIP_PATH=""
  fi
  if [[ -n "$ZIP_PATH" && -f "$ZIP_PATH" ]]; then
    update_summary_zip "$ZIP_PATH"
  else
    ZIP_PATH=""
    update_summary_zip "unavailable"
  fi
}

open_result() {
  [[ "$OPEN_FINDER" == "true" ]] || return 0
  local target="$OUT_DIR"
  if [[ -n "$ZIP_PATH" && -f "$ZIP_PATH" ]]; then
    target="$ZIP_PATH"
  fi
  if command -v open >/dev/null 2>&1; then
    if [[ -f "$target" ]]; then
      open -R "$target" || open "$OUT_DIR" || true
    else
      open "$OUT_DIR" || true
    fi
    return 0
  fi
  if command -v explorer.exe >/dev/null 2>&1; then
    local win_target
    win_target="$(to_native_path "$target")"
    MSYS_NO_PATHCONV=1 explorer.exe /select,"$win_target" >/dev/null 2>&1 || true
    return 0
  fi
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$OUT_DIR" >/dev/null 2>&1 || true
  fi
}

write_summary
collect_logs
collect_jugg_state
collect_classpath
collect_project_meta
collect_adb_snapshot
write_manifest
package_zip
write_manifest

echo
echo "Scene collected at: $OUT_DIR"
echo "Manifest: $OUT_DIR/manifest.txt"
if [[ -n "$ZIP_PATH" && -f "$ZIP_PATH" ]]; then
  echo "Zip: $ZIP_PATH"
fi
open_result
pause_if_interactive
