#!/bin/bash
# ============================================================================
# capture_freeze.sh — Android Studio Freeze Snapshot Capture
#
# Usage:
#   ./capture_freeze.sh              # Auto-detect mode: monitor EDT responsiveness
#   ./capture_freeze.sh now          # Manual mode: capture snapshot immediately
#   ./capture_freeze.sh watch        # Watch mode: poll every N seconds, auto-capture on freeze
#
# Output: ~/jugg_freeze_dumps/<timestamp>/
# ============================================================================

set -euo pipefail

# ========================== Configuration ==========================
# How often to check (seconds) in watch mode
POLL_INTERVAL="${POLL_INTERVAL:-2}"
# Freeze threshold in milliseconds — if EDT doesn't respond within this, consider frozen
FREEZE_THRESHOLD_MS="${FREEZE_THRESHOLD_MS:-3000}"
# Number of thread dumps to capture per freeze event (spaced 2s apart)
DUMP_COUNT="${DUMP_COUNT:-3}"
# Interval between consecutive thread dumps (seconds)
DUMP_INTERVAL="${DUMP_INTERVAL:-2}"
# Output root directory
OUTPUT_ROOT="${OUTPUT_ROOT:-$HOME/jugg_freeze_dumps}"
# JBR jstack path (use the one bundled with Android Studio for best compatibility)
JSTACK="${JSTACK:-/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/jstack}"
JCMD="${JCMD:-/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/jcmd}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ========================== Functions ==========================

log_info()  { echo -e "${GREEN}[INFO]${NC}  $(date '+%H:%M:%S') $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $(date '+%H:%M:%S') $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $(date '+%H:%M:%S') $*"; }

find_studio_pid() {
    # Find Android Studio main process PID
    local pid
    pid=$(pgrep -f "Android Studio.app/Contents/MacOS/studio" 2>/dev/null | head -1)
    if [[ -z "$pid" ]]; then
        # Fallback: try com.intellij.idea.Main
        pid=$(pgrep -f "com.intellij.idea.Main" 2>/dev/null | head -1)
    fi
    if [[ -z "$pid" ]]; then
        # Fallback: jps
        pid=$(jps -l 2>/dev/null | grep -i "idea\|studio" | awk '{print $1}' | head -1)
    fi
    echo "$pid"
}

capture_snapshot() {
    local pid="$1"
    local reason="${2:-manual}"
    local ts
    ts=$(date '+%Y%m%d-%H%M%S')
    local out_dir="${OUTPUT_ROOT}/${ts}-${reason}"
    mkdir -p "$out_dir"

    log_info "Capturing freeze snapshot → ${out_dir}"

    # 1) Basic process info
    {
        echo "=== Capture Time: $(date '+%Y-%m-%d %H:%M:%S') ==="
        echo "=== Reason: ${reason} ==="
        echo "=== Studio PID: ${pid} ==="
        echo ""
        echo "--- ps info ---"
        ps -p "$pid" -o pid,ppid,%cpu,%mem,rss,vsz,etime,command 2>/dev/null || true
        echo ""
        echo "--- System load ---"
        uptime
        echo ""
        echo "--- Memory ---"
        vm_stat 2>/dev/null | head -20 || true
        echo ""
        echo "--- Disk I/O (if available) ---"
        iostat -c 3 2>/dev/null | head -10 || true
    } > "${out_dir}/system_info.txt" 2>&1

    # 2) Multiple thread dumps (to see thread state progression)
    for i in $(seq 1 "$DUMP_COUNT"); do
        local dump_file="${out_dir}/thread_dump_${i}.txt"
        log_info "  Thread dump ${i}/${DUMP_COUNT}..."
        {
            echo "=== Thread Dump #${i} at $(date '+%Y-%m-%d %H:%M:%S.%N') ==="
            "$JSTACK" -l "$pid" 2>&1 || echo "jstack failed with exit code $?"
        } > "$dump_file"

        if [[ $i -lt $DUMP_COUNT ]]; then
            sleep "$DUMP_INTERVAL"
        fi
    done

    # 3) JVM flags & heap summary (non-blocking)
    {
        echo "=== VM Flags ==="
        "$JCMD" "$pid" VM.flags 2>&1 || true
        echo ""
        echo "=== GC Heap Info ==="
        "$JCMD" "$pid" GC.heap_info 2>&1 || true
        echo ""
        echo "=== VM Uptime ==="
        "$JCMD" "$pid" VM.uptime 2>&1 || true
    } > "${out_dir}/jvm_info.txt" 2>&1

    # 4) Extract EDT stack from the first dump for quick diagnosis
    {
        echo "=== EDT (AWT-EventQueue) Stack — Quick View ==="
        echo ""
        # Extract AWT-EventQueue thread block
        awk '
            /^"AWT-EventQueue/{found=1}
            found{print}
            found && /^$/{found=0}
        ' "${out_dir}/thread_dump_1.txt"
    } > "${out_dir}/edt_stack_quick.txt" 2>&1

    # 5) Detect BLOCKED threads for quick diagnosis
    {
        echo "=== BLOCKED Threads Summary ==="
        echo ""
        awk '
            /^".*".*BLOCKED/{found=1; print; next}
            found && /^\t/{print; next}
            found && /^$/{found=0; print "---"; next}
            found{found=0; print "---"}
        ' "${out_dir}/thread_dump_1.txt"
    } > "${out_dir}/blocked_threads.txt" 2>&1

    # 6) Detect lock contention
    {
        echo "=== Lock Contention Analysis ==="
        echo ""
        echo "-- Threads waiting to lock --"
        grep -n "waiting to lock\|waiting on\|locked\|parking to wait" "${out_dir}/thread_dump_1.txt" 2>/dev/null || echo "(none)"
    } > "${out_dir}/lock_analysis.txt" 2>&1

    # 7) Copy recent idea.log tail
    local idea_log="$HOME/Library/Logs/Google/AndroidStudio2025.3.1/idea.log"
    if [[ -f "$idea_log" ]]; then
        tail -500 "$idea_log" > "${out_dir}/idea_log_tail.txt" 2>/dev/null || true
    fi

    log_info "✅ Snapshot saved: ${out_dir}"
    log_info "   Files: $(ls "$out_dir" | tr '\n' ' ')"

    # Print quick summary
    echo ""
    echo -e "${CYAN}=== Quick Diagnosis ===${NC}"
    if [[ -s "${out_dir}/edt_stack_quick.txt" ]]; then
        head -20 "${out_dir}/edt_stack_quick.txt"
    fi
    echo ""
    local blocked_count
    blocked_count=$(grep -c "BLOCKED" "${out_dir}/thread_dump_1.txt" 2>/dev/null || echo "0")
    echo -e "${CYAN}BLOCKED threads: ${blocked_count}${NC}"
    echo ""
}

check_edt_responsive() {
    # Use jcmd to check if EDT is responsive by sending a diagnostic command
    # If it hangs, the process is likely frozen
    local pid="$1"

    # Method: do a quick jstack and check if EDT is BLOCKED or in TIMED_WAITING
    # with known freeze patterns
    local quick_dump
    quick_dump=$("$JSTACK" "$pid" 2>/dev/null) || return 1

    # Check if EDT is BLOCKED
    if echo "$quick_dump" | grep -A 1 '"AWT-EventQueue' | grep -q "BLOCKED"; then
        echo "EDT_BLOCKED"
        return 0
    fi

    # Check if EDT is in known freeze patterns (synchronized lock wait)
    local edt_state
    edt_state=$(echo "$quick_dump" | awk '
        /^"AWT-EventQueue/{found=1}
        found && /java.lang.Thread.State:/{print; found=0}
    ')

    if echo "$edt_state" | grep -q "BLOCKED"; then
        echo "EDT_BLOCKED"
        return 0
    fi

    echo "OK"
    return 0
}

mode_manual() {
    local pid
    pid=$(find_studio_pid)
    if [[ -z "$pid" ]]; then
        log_error "Android Studio process not found!"
        exit 1
    fi
    log_info "Found Android Studio PID: ${pid}"
    capture_snapshot "$pid" "manual"
}

mode_watch() {
    log_info "🔍 Watch mode started (poll every ${POLL_INTERVAL}s, freeze threshold ${FREEZE_THRESHOLD_MS}ms)"
    log_info "   Press Ctrl+C to stop"
    log_info "   Output: ${OUTPUT_ROOT}/"
    echo ""

    local consecutive_blocked=0
    local already_capturing=false
    local last_capture_time=0

    while true; do
        local pid
        pid=$(find_studio_pid)
        if [[ -z "$pid" ]]; then
            log_warn "Android Studio not running, waiting..."
            sleep 5
            consecutive_blocked=0
            continue
        fi

        local status
        status=$(check_edt_responsive "$pid" 2>/dev/null) || status="JSTACK_FAILED"

        if [[ "$status" == "EDT_BLOCKED" ]]; then
            consecutive_blocked=$((consecutive_blocked + 1))
            local now
            now=$(date +%s)
            local cooldown_elapsed=$((now - last_capture_time))

            if [[ $consecutive_blocked -ge 2 && "$already_capturing" == "false" && $cooldown_elapsed -ge 15 ]]; then
                log_warn "🔴 EDT BLOCKED detected (${consecutive_blocked} consecutive checks)!"
                already_capturing=true
                capture_snapshot "$pid" "auto-edt-blocked"
                last_capture_time=$(date +%s)
                already_capturing=false
            elif [[ $consecutive_blocked -lt 2 ]]; then
                log_warn "⚠️  EDT appears blocked (check ${consecutive_blocked}/2, waiting to confirm...)"
            fi
        elif [[ "$status" == "JSTACK_FAILED" ]]; then
            log_warn "jstack failed — Studio may be unresponsive or exiting"
            consecutive_blocked=$((consecutive_blocked + 1))
            if [[ $consecutive_blocked -ge 3 ]]; then
                log_error "jstack failed 3 times — Studio might be completely hung"
                # Try to get at least OS-level info
                local ts
                ts=$(date '+%Y%m%d-%H%M%S')
                local out_dir="${OUTPUT_ROOT}/${ts}-jstack-failed"
                mkdir -p "$out_dir"
                {
                    echo "=== jstack failed — OS-level snapshot ==="
                    echo "=== Time: $(date '+%Y-%m-%d %H:%M:%S') ==="
                    echo ""
                    echo "--- Process info ---"
                    ps -p "$pid" -o pid,%cpu,%mem,rss,etime,command 2>/dev/null || echo "Process may have exited"
                    echo ""
                    echo "--- sample (1s) ---"
                    timeout 3 sample "$pid" 1 2>&1 | head -200 || echo "sample failed"
                } > "${out_dir}/os_snapshot.txt" 2>&1
                log_info "OS-level snapshot saved: ${out_dir}"
                consecutive_blocked=0
                sleep 10
                continue
            fi
        else
            if [[ $consecutive_blocked -gt 0 ]]; then
                log_info "✅ EDT responsive again (was blocked for ~${consecutive_blocked} checks)"
            fi
            consecutive_blocked=0
        fi

        sleep "$POLL_INTERVAL"
    done
}

show_help() {
    cat <<'EOF'

  ╔══════════════════════════════════════════════════════════════╗
  ║       Android Studio Freeze Snapshot Capture                ║
  ╚══════════════════════════════════════════════════════════════╝

  Usage:
    ./capture_freeze.sh              Start watch mode (auto-detect freezes)
    ./capture_freeze.sh now          Capture snapshot immediately
    ./capture_freeze.sh watch        Same as no argument — watch mode
    ./capture_freeze.sh help         Show this help

  Environment variables:
    POLL_INTERVAL=2          Check interval in seconds (watch mode)
    FREEZE_THRESHOLD_MS=3000 EDT freeze threshold in ms
    DUMP_COUNT=3             Number of thread dumps per freeze event
    DUMP_INTERVAL=2          Seconds between consecutive dumps
    OUTPUT_ROOT=~/jugg_freeze_dumps  Output directory

  Output structure:
    ~/jugg_freeze_dumps/
      └── 20260309-210730-auto-edt-blocked/
          ├── system_info.txt          # CPU, memory, disk I/O
          ├── thread_dump_1.txt        # Full JVM thread dump #1
          ├── thread_dump_2.txt        # Full JVM thread dump #2
          ├── thread_dump_3.txt        # Full JVM thread dump #3
          ├── jvm_info.txt             # VM flags, heap info
          ├── edt_stack_quick.txt      # EDT stack extracted for quick view
          ├── blocked_threads.txt      # All BLOCKED threads
          ├── lock_analysis.txt        # Lock contention summary
          └── idea_log_tail.txt        # Last 500 lines of idea.log

  Examples:
    # IDE is frozen RIGHT NOW — capture immediately
    ./capture_freeze.sh now

    # Start monitoring, auto-capture when freeze detected
    ./capture_freeze.sh

    # Custom: check every 1s, capture 5 dumps per event
    POLL_INTERVAL=1 DUMP_COUNT=5 ./capture_freeze.sh

EOF
}

# ========================== Main ==========================

main() {
    local mode="${1:-watch}"

    case "$mode" in
        now|manual|capture)
            mode_manual
            ;;
        watch|monitor|auto)
            mode_watch
            ;;
        help|-h|--help)
            show_help
            ;;
        *)
            log_error "Unknown mode: ${mode}"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
