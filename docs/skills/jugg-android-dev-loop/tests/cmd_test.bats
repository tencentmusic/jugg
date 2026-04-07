#!/usr/bin/env bats
# Tests for jugg subcommand argument parsing and dispatch logic

SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)"
JUGG_BIN="$SCRIPT_DIR/bin/jugg"

setup() {
  export JUGG_CACHE_DIR
  JUGG_CACHE_DIR="$(mktemp -d)"
  export JUGG_PORT_CACHE="$JUGG_CACHE_DIR/port"
  export JUGG_RECORD_SESSION="$JUGG_CACHE_DIR/record_session"
  # Pre-set port so tests skip network scan
  echo "12320" > "$JUGG_PORT_CACHE"

  # Mock jugg_call to capture calls instead of making real HTTP requests
  export JUGG_MOCK_CALLS_FILE="$JUGG_CACHE_DIR/calls.txt"
  export JUGG_MOCK_RESPONSE='{"status":"OK","data":{}}'
}

teardown() {
  rm -rf "$JUGG_CACHE_DIR"
}

# ─── unknown subcommand ──────────────────────────────────────────────────────

@test "jugg with no args prints usage" {
  run "$JUGG_BIN"
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "usage\|subcommand\|command"
}

@test "jugg unknown-cmd prints error" {
  run "$JUGG_BIN" unknown-cmd
  [ "$status" -ne 0 ]
}

# ─── tap argument parsing ────────────────────────────────────────────────────

@test "jugg tap requires at least one selector or coordinate" {
  # Source only tap parse function; we test the parse, not the HTTP call
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/tap.sh"
  run _tap_parse_args
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "selector\|required\|option"
}

@test "jugg tap --text parses element mode" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/tap.sh"
  run _tap_build_params --text "Login"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"text"'
  echo "$output" | grep -q '"Login"'
}

@test "jugg tap --x --y parses coordinate mode" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/tap.sh"
  run _tap_build_params --x 540 --y 960
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"x"'
  echo "$output" | grep -q '540'
}

@test "jugg tap --xp --yp parses percent mode" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/tap.sh"
  run _tap_build_params --xp 50 --yp 80
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"xPercent"'
  echo "$output" | grep -q '50'
}

@test "jugg tap swipe requires end coords" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/tap.sh"
  run _tap_build_params --x 540 --y 960 --action swipe
  [ "$status" -ne 0 ]
}

@test "jugg tap swipe with full coords succeeds" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/tap.sh"
  run _tap_build_params --x 540 --y 960 --end-x 540 --end-y 200 --action swipe
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"action"'
  echo "$output" | grep -q '"swipe"'
}

# ─── restart --tap step parsing ──────────────────────────────────────────────

@test "restart --tap step tap:text=Login parses correctly" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/restart.sh"
  run _restart_parse_tap_step "tap:text=Login"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"action"'
  echo "$output" | grep -q '"tap"'
  echo "$output" | grep -q '"text"'
  echo "$output" | grep -q '"Login"'
}

@test "restart --tap step tap:id=btn-confirm parses correctly" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/restart.sh"
  run _restart_parse_tap_step "tap:id=btn-confirm"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"resourceId"'
  echo "$output" | grep -q '"btn-confirm"'
}

@test "restart --tap step tap:desc=close parses correctly" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/restart.sh"
  run _restart_parse_tap_step "tap:desc=close"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"contentDesc"'
  echo "$output" | grep -q '"close"'
}

@test "restart --tap step tap:50%,80% parses percent coords" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/restart.sh"
  run _restart_parse_tap_step "tap:50%,80%"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"xPercent"'
  echo "$output" | grep -q '50'
  echo "$output" | grep -q '80'
}

@test "restart --tap step tap:540,960 parses absolute coords" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/restart.sh"
  run _restart_parse_tap_step "tap:540,960"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"x"'
  echo "$output" | grep -q '540'
}

@test "restart --tap step swipe:50%,80%,50%,20% parses swipe" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/restart.sh"
  run _restart_parse_tap_step "swipe:50%,80%,50%,20%"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"swipe"'
  echo "$output" | grep -q '"xPercent"'
  echo "$output" | grep -q '"endXPercent"'
}

@test "restart --tap step long-press:text=Menu parses long press" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/restart.sh"
  run _restart_parse_tap_step "long-press:text=Menu"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"longPress"'
  echo "$output" | grep -q '"Menu"'
}

@test "restart --tap invalid step fails" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/restart.sh"
  run _restart_parse_tap_step "unknown:garbage"
  [ "$status" -ne 0 ]
}

# ─── view-locate selector validation ────────────────────────────────────────

@test "view-locate requires at least one selector" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/view-locate.sh"
  run _view_locate_build_params
  [ "$status" -ne 0 ]
}

@test "view-locate --text builds correct params" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/view-locate.sh"
  run _view_locate_build_params --text "Avatar"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"text"'
  echo "$output" | grep -q '"Avatar"'
}

@test "view-locate --id builds correct params" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/view-locate.sh"
  run _view_locate_build_params --id "btn_login"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"resourceId"'
  echo "$output" | grep -q '"btn_login"'
}

# ─── view-inspect selector + expressions ────────────────────────────────────

@test "view-inspect requires selector and at least one expression" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/view-inspect.sh"
  run _view_inspect_build_params
  [ "$status" -ne 0 ]
}

@test "view-inspect --text with expr builds correct params" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/view-inspect.sh"
  run _view_inspect_build_params --text "Label" "getTextSize()" "isEnabled()"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"text"'
  echo "$output" | grep -q '"Label"'
  echo "$output" | grep -q 'getTextSize'
  echo "$output" | grep -q 'isEnabled'
}

# ─── layout-dump optional flags ──────────────────────────────────────────────

@test "layout-dump no flags builds minimal params" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/layout-dump.sh"
  run _layout_dump_build_params
  [ "$status" -eq 0 ]
}

@test "layout-dump --root sets rootLayout" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/layout-dump.sh"
  run _layout_dump_build_params --root "my_root_id"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"rootLayout"'
  echo "$output" | grep -q '"my_root_id"'
}

@test "layout-dump --include-gone sets flag" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/layout-dump.sh"
  run _layout_dump_build_params --include-gone
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"isIncludeGone"'
  echo "$output" | grep -q 'true'
}

@test "layout-dump --all-windows sets flag" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/layout-dump.sh"
  run _layout_dump_build_params --all-windows
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"isAllWindows"'
  echo "$output" | grep -q 'true'
}

# ─── ssh-info requires --reason ─────────────────────────────────────────────

@test "ssh-info requires --reason" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/ssh-info.sh"
  run _ssh_info_build_params
  [ "$status" -ne 0 ]
}

@test "ssh-info --reason builds correct params with userConsent=true" {
  source "$SCRIPT_DIR/bin/_lib.sh"
  source "$SCRIPT_DIR/bin/cmd/ssh-info.sh"
  run _ssh_info_build_params --reason "investigating crash"
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '"reason"'
  echo "$output" | grep -q '"investigating crash"'
  echo "$output" | grep -q '"userConsent"'
  echo "$output" | grep -q 'true'
}
