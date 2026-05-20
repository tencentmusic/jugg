#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  tools/constref_resource_capture.sh --project <JOOX project dir> --label <name> [options]

Options:
  --pid <pid>                 Android Studio / IntelliJ process id. If omitted, the script tries to auto-detect one.
  --out-root <dir>            Output root directory. Default: /tmp/jugg-constref-resource
  --interval <seconds>        Sampling interval. Default: 1
  --duration <seconds>        Stop after this many seconds. Default: wait until const-ref full scan final=true.
  --reset-cache               Move ~/.jugg/const_ref aside before sampling, for cold-cache tests.
  --powermetrics              Also run sudo powermetrics --samplers cpu_power.
  --no-wait-final             Do not wait for ConstRefEngine full scan final=true; use with --duration or Ctrl-C.
  -h, --help                  Show this help.

Examples:
  tools/constref_resource_capture.sh --project /Users/wormchen/IdeaProjects/joox/JOOX_Android --label after-cold --reset-cache --powermetrics
  tools/constref_resource_capture.sh --project /Users/wormchen/IdeaProjects/joox/JOOX_Android --label after-warm --duration 600
EOF
}

PROJECT_DIR=""
LABEL=""
PID=""
OUT_ROOT="/tmp/jugg-constref-resource"
INTERVAL="1"
DURATION=""
RESET_CACHE=0
POWERMETRICS=0
WAIT_FINAL=1

detect_ide_pid() {
  ps ax -o pid=,command= \
    | awk '
        /\/Android Studio\.app\/Contents\/MacOS\/studio($| )/ ||
        /\/studio64($| )/ ||
        /com\.intellij\.idea\.Main/ ||
        /idea\.Main/ {
          print $1
        }
      ' \
    | tail -n 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project)
      PROJECT_DIR="$2"
      shift 2
      ;;
    --label)
      LABEL="$2"
      shift 2
      ;;
    --pid)
      PID="$2"
      shift 2
      ;;
    --out-root)
      OUT_ROOT="$2"
      shift 2
      ;;
    --interval)
      INTERVAL="$2"
      shift 2
      ;;
    --duration)
      DURATION="$2"
      shift 2
      ;;
    --reset-cache)
      RESET_CACHE=1
      shift
      ;;
    --powermetrics)
      POWERMETRICS=1
      shift
      ;;
    --no-wait-final)
      WAIT_FINAL=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$PROJECT_DIR" || -z "$LABEL" ]]; then
  usage >&2
  exit 2
fi

if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "Project directory does not exist: $PROJECT_DIR" >&2
  exit 1
fi

if [[ -z "$PID" ]]; then
  PID="$(detect_ide_pid || true)"
fi

if [[ -z "$PID" ]]; then
  echo "Cannot auto-detect IDE pid. Pass --pid <pid>." >&2
  exit 1
fi

if ! ps -p "$PID" >/dev/null 2>&1; then
  echo "Process does not exist: $PID" >&2
  exit 1
fi

RUN_ID="$(date '+%Y%m%d-%H%M%S')-$LABEL"
OUT_DIR="$OUT_ROOT/$RUN_ID"
mkdir -p "$OUT_DIR"

LOG_FILE="$PROJECT_DIR/build/jugg/log/compile_latest.log"
CONST_REF_DIR="$HOME/.jugg/const_ref"
CACHE_BACKUP=""
CACHE_RESET_STATUS="not_requested"

if [[ "$RESET_CACHE" -eq 1 && -e "$CONST_REF_DIR" ]]; then
  CACHE_BACKUP="$HOME/.jugg/const_ref.backup.$RUN_ID"
  mv "$CONST_REF_DIR" "$CACHE_BACKUP"
  CACHE_RESET_STATUS="moved"
elif [[ "$RESET_CACHE" -eq 1 ]]; then
  CACHE_RESET_STATUS="missing"
fi

cat > "$OUT_DIR/metadata.env" <<EOF
run_id=$RUN_ID
label=$LABEL
project_dir=$PROJECT_DIR
pid=$PID
out_dir=$OUT_DIR
interval=$INTERVAL
duration=${DURATION:-}
wait_final=$WAIT_FINAL
reset_cache=$RESET_CACHE
cache_reset_status=$CACHE_RESET_STATUS
const_ref_cache_backup=$CACHE_BACKUP
start_epoch=$(date '+%s')
start_time=$(date '+%F %T')
log_file=$LOG_FILE
EOF

PIDS=()
STOP_FILE="$OUT_DIR/.stop"

cleanup() {
  touch "$STOP_FILE"
  for child in "${PIDS[@]:-}"; do
    if kill -0 "$child" >/dev/null 2>&1; then
      kill "$child" >/dev/null 2>&1 || true
    fi
  done
  wait >/dev/null 2>&1 || true
  {
    echo "end_epoch=$(date '+%s')"
    echo "end_time=$(date '+%F %T')"
  } >> "$OUT_DIR/metadata.env"
  echo "Output: $OUT_DIR"
}
trap cleanup EXIT INT TERM

sample_ps() {
  while [[ ! -f "$STOP_FILE" ]]; do
    local epoch iso line
    epoch="$(date '+%s')"
    iso="$(date '+%F %T')"
    line="$(ps -p "$PID" -o pid=,%cpu=,%mem=,rss=,time=,etime= 2>/dev/null || true)"
    if [[ -z "$line" ]]; then
      echo "$epoch $iso PROCESS_EXITED" >> "$OUT_DIR/ps.log"
      touch "$STOP_FILE"
      break
    fi
    echo "$epoch $iso $line" >> "$OUT_DIR/ps.log"
    sleep "$INTERVAL"
  done
}

sample_ps &
PIDS+=("$!")

iostat -d "$INTERVAL" > "$OUT_DIR/iostat.log" 2>&1 &
PIDS+=("$!")

if [[ "$POWERMETRICS" -eq 1 ]]; then
  sudo powermetrics --samplers cpu_power -i "$((INTERVAL * 1000))" > "$OUT_DIR/powermetrics.log" 2>&1 &
  PIDS+=("$!")
fi

mkdir -p "$(dirname "$LOG_FILE")"
touch "$LOG_FILE"
tail -n 0 -F "$LOG_FILE" > "$OUT_DIR/compile_tail.log" 2>&1 &
PIDS+=("$!")

if [[ -n "$DURATION" ]]; then
  sleep "$DURATION"
elif [[ "$WAIT_FINAL" -eq 1 ]]; then
  echo "Waiting for ConstRefEngine full scan final=true. Start or restart the IDE/plugin now if needed."
  while [[ ! -f "$STOP_FILE" ]]; do
    if grep -q 'ConstRefEngine full scan progress, final=true' "$OUT_DIR/compile_tail.log"; then
      break
    fi
    sleep 1
  done
else
  echo "Sampling until Ctrl-C. Output: $OUT_DIR"
  while [[ ! -f "$STOP_FILE" ]]; do
    sleep 1
  done
fi
