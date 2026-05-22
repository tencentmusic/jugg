#!/usr/bin/env bash
# Monitor ADB device offline -> online recovery time.
# Matches Jugg deploy retry probes: poll adb get-state + adb shell true (500ms default).
#
# Usage:
#   ./tools/adb_offline_recovery_monitor.sh                    # auto-pick first device
#   ./tools/adb_offline_recovery_monitor.sh emulator-5554      # watch one serial
#   POLL_MS=500 MAX_WAIT_MS=3000 ./tools/adb_offline_recovery_monitor.sh emulator-5554
#
# While this runs, trigger Jugg deploy / reinstall on the same device.
# Each offline episode prints how long until transport is ready again.

set -euo pipefail

POLL_MS="${POLL_MS:-500}"
MAX_WAIT_MS="${MAX_WAIT_MS:-60000}"
ADB="${ADB:-adb}"

SERIAL="${1:-}"

log() {
  local ts
  ts="$(python3 - <<'PY'
import datetime
print(datetime.datetime.now().strftime('%H:%M:%S.') + f'{int(datetime.datetime.now().microsecond/1000):03d}')
PY
)"
  printf '[%s] %s\n' "$ts" "$*"
}

resolve_serial() {
  if [[ -n "$SERIAL" ]]; then
    echo "$SERIAL"
    return
  fi
  local picked
  picked="$("$ADB" devices | awk 'NR>1 && $2=="device" { print $1; exit }')"
  if [[ -z "$picked" ]]; then
    picked="$("$ADB" devices | awk 'NR>1 && $2=="offline" { print $1; exit }')"
  fi
  if [[ -z "$picked" ]]; then
    echo "No device or offline device found. Run 'adb devices' first." >&2
    exit 1
  fi
  echo "$picked"
}

get_state() {
  # device | offline | unauthorized | unknown | missing
  "$ADB" -s "$SERIAL" get-state 2>/dev/null || echo "missing"
}

shell_true_ok() {
  "$ADB" -s "$SERIAL" shell true >/dev/null 2>&1
}

is_transport_ready() {
  [[ "$(get_state)" == "device" ]] && shell_true_ok
}

now_ms() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

ms_to_human() {
  local ms="$1"
  awk -v ms="$ms" 'BEGIN {
    if (ms < 1000) { printf "%dms", ms; exit }
    sec = ms / 1000.0
    if (sec < 60) { printf "%.2fs", sec; exit }
    printf "%dm %.2fs", int(sec/60), sec % 60
  }'
}

wait_until_ready() {
  local start_ms="$1"
  local deadline_ms=$((start_ms + MAX_WAIT_MS))
  local polls=0

  while true; do
    polls=$((polls + 1))
    if is_transport_ready; then
      local end_ms
      end_ms="$(now_ms)"
      local elapsed=$((end_ms - start_ms))
      log "RECOVERED serial=$SERIAL elapsed=$(ms_to_human "$elapsed") polls=$polls state=$(get_state)"
      return 0
    fi

    local now
    now="$(now_ms)"
    if (( now >= deadline_ms )); then
      log "GIVE_UP serial=$SERIAL waited=$(ms_to_human "$((now - start_ms))") polls=$polls state=$(get_state) (max ${MAX_WAIT_MS}ms)"
      return 1
    fi

    sleep "$(awk -v ms="$POLL_MS" 'BEGIN { printf "%.3f", ms/1000 }')"
  done
}

SERIAL="$(resolve_serial)"
log "Watching serial=$SERIAL poll=${POLL_MS}ms max_wait=${MAX_WAIT_MS}ms (Ctrl+C to stop)"
log "Tip: run Jugg deploy / reinstall while this script is running."

was_ready=true
if ! is_transport_ready; then
  was_ready=false
  state="$(get_state)"
  log "OFFLINE detected at start state=$state"
  offline_start_ms="$(now_ms)"
  if wait_until_ready "$offline_start_ms"; then
    was_ready=true
  fi
fi

while true; do
  if is_transport_ready; then
    if [[ "$was_ready" == "false" ]]; then
      # recovered in inner loop already logged
      was_ready=true
    fi
  else
    if [[ "$was_ready" == "true" ]]; then
      state="$(get_state)"
      log "OFFLINE detected state=$state"
      offline_start_ms="$(now_ms)"
      was_ready=false
      wait_until_ready "$offline_start_ms" || true
      if is_transport_ready; then
        was_ready=true
      fi
    fi
  fi

  sleep "$(awk -v ms="$POLL_MS" 'BEGIN { printf "%.3f", ms/1000 }')"
done
