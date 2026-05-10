# L2 Agent Hooks

目标：一次性验证 Jugg agent hooks 的核心行为是否符合预期。本文验证 hook 脚本本身，不依赖真实 Android Studio、真实设备或真实 Jugg MCP 服务。

## 真相源

- Hook 脚本：`docs/skills/hooks/start.py`、`edit.py`、`command.py`、`stop.py`、`hook_common.py`
- Hook 安装说明：`docs/skills/install/agent_setup.md`
- 行为说明：`docs/ai_knowledge/04_engineering_ide.md`、`docs/ai_knowledge/08_mcp_tools_list.md`

## 覆盖点

| Case | 期望 |
|------|------|
| HOOK-1 | 修改 Android 源码后，`edit.py` 给软提醒，退出码为 0 |
| HOOK-2 | 有 Android 修改后直接调用 raw Gradle，`command.py` 第一次硬阻断，第二次放行并给 warning |
| HOOK-3 | 修改 Android 源码后没有调用 Jugg CLI 编译，`stop.py` 第一次硬阻断，第二次放行 |
| HOOK-4 | `hasBeenFullCompiled=false` 的工程放行，不触发提醒或阻断 |

## 执行规则

- 在仓库根目录执行。
- 不修改真实 `~/.jugg`，必须使用临时 `HOME`。
- 不改 hook 源码；失败只记录证据。
- 不直接运行真实 Gradle，不启动 Android Studio，不要求设备。
- 只接受 raw Gradle 命令被 `command.py` 阻断；`jugg gradle-build` 不应被识别为 raw Gradle。

## HOOKS-ALL: 一次性验证 Agent hooks

Prompt：请在仓库根目录一次性验证 Jugg agent hooks。不要修改 hook 源码，不要启动 Android Studio，不要使用真实 `~/.jugg`。直接执行下面脚本，并按输出判断结果。

```bash
set -euo pipefail

REPO_ROOT="$(pwd)"
HOOK_SRC="$REPO_ROOT/docs/skills/hooks"
WORK_DIR="$(mktemp -d)"
export HOME="$WORK_DIR/home"
export JUGG_HOOK_DEBUG_LOG="$WORK_DIR/jugg-hook-debug.log"
PROJECT_DIR="$WORK_DIR/project"
STATUS_FILE="$WORK_DIR/status.json"

mkdir -p "$HOME/.jugg/skills/hooks" "$HOME/.jugg/bin" "$PROJECT_DIR/app/src/main/java/com/example"
cp "$HOOK_SRC"/{start.py,stop.py,edit.py,command.py,hook_common.py} "$HOME/.jugg/skills/hooks/"

cat > "$HOME/.jugg/bin/jugg.py" <<'PY'
#!/usr/bin/env python3
import os
import sys
from pathlib import Path

status_file = os.environ["JUGG_FAKE_STATUS_FILE"]
if sys.argv[1:] == ["--console=json", "status"]:
    sys.stdout.write(Path(status_file).read_text(encoding="utf-8"))
    sys.exit(0)
sys.stderr.write("unexpected fake jugg args: " + repr(sys.argv[1:]) + "\n")
sys.exit(1)
PY
chmod +x "$HOME/.jugg/bin/jugg.py"
export JUGG_FAKE_STATUS_FILE="$STATUS_FILE"

write_status() {
  local has_full="$1"
  local modified="$2"
  local compiled="$3"
  local total="$4"
  cat > "$STATUS_FILE" <<JSON
{
  "status": "OK",
  "data": {
    "hasBeenFullCompiled": $has_full,
    "lastFileModifiedTime": "$modified",
    "lastCompileTime": "$compiled",
    "fileCounts": {"total": $total, "SOURCE": $total}
  }
}
JSON
}

run_hook() {
  local name="$1"
  local script="$2"
  local payload="$3"
  local stdout_file="$WORK_DIR/${name}.stdout"
  local stderr_file="$WORK_DIR/${name}.stderr"
  set +e
  (cd "$PROJECT_DIR" && printf '%s' "$payload" | python3 "$HOME/.jugg/skills/hooks/$script.py" --client codex >"$stdout_file" 2>"$stderr_file")
  local code=$?
  set -e
  echo "$code" > "$WORK_DIR/${name}.code"
}

assert_code() {
  local name="$1"
  local expected="$2"
  local actual
  actual="$(cat "$WORK_DIR/${name}.code")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $name expected exit $expected, got $actual"
    cat "$WORK_DIR/${name}.stderr" || true
    exit 1
  fi
}

assert_stderr_contains() {
  local name="$1"
  local needle="$2"
  if ! grep -Fq "$needle" "$WORK_DIR/${name}.stderr"; then
    echo "FAIL $name stderr missing: $needle"
    cat "$WORK_DIR/${name}.stderr" || true
    exit 1
  fi
}

assert_stderr_empty() {
  local name="$1"
  if [ -s "$WORK_DIR/${name}.stderr" ]; then
    echo "FAIL $name expected empty stderr"
    cat "$WORK_DIR/${name}.stderr" || true
    exit 1
  fi
}

# HOOK-1: Android edit emits a soft reminder once.
write_status true "" "" 0
run_hook "edit_first" "edit" '{"tool_input":{"file_path":"app/src/main/java/com/example/MainActivity.kt"}}'
assert_code "edit_first" 0
assert_stderr_contains "edit_first" "You modified Android source files."

run_hook "edit_second" "edit" '{"tool_input":{"file_path":"app/src/main/java/com/example/Other.kt"}}'
assert_code "edit_second" 0
assert_stderr_empty "edit_second"

# HOOK-2: raw Gradle is blocked once after Android edits, then allowed with warning.
run_hook "command_raw_gradle_first" "command" '{"tool_input":{"command":"./gradlew :app:assembleDebug"}}'
assert_code "command_raw_gradle_first" 2
assert_stderr_contains "command_raw_gradle_first" "Do not verify with raw Gradle here"

run_hook "command_raw_gradle_second" "command" '{"tool_input":{"command":"./gradlew :app:assembleDebug"}}'
assert_code "command_raw_gradle_second" 0
assert_stderr_contains "command_raw_gradle_second" "Allowing this repeated command attempt"

run_hook "command_jugg_gradle_build" "command" '{"tool_input":{"command":"jugg gradle-build"}}'
assert_code "command_jugg_gradle_build" 0

# HOOK-3: stop blocks once when files changed but Jugg compile was not invoked.
rm -f "$HOME/.jugg/hooks/.state/"*.json
write_status true "" "" 0
run_hook "start_baseline" "start" '{}'
assert_code "start_baseline" 0
assert_stderr_empty "start_baseline"

write_status true "2026-05-10 12:00:00" "" 1
run_hook "stop_first" "stop" '{}'
assert_code "stop_first" 2
assert_stderr_contains "stop_first" "Before stopping, you must enable the jugg-android-dev-loop skill"

run_hook "stop_second" "stop" '{}'
assert_code "stop_second" 0
assert_stderr_contains "stop_second" "allowing session stop after a repeated stop attempt"

# HOOK-4: projects without a full Jugg baseline are allowed.
rm -f "$HOME/.jugg/hooks/.state/"*.json
write_status false "2026-05-10 12:00:00" "" 1
run_hook "uncompiled_edit" "edit" '{"tool_input":{"file_path":"app/src/main/java/com/example/NeverBuilt.kt"}}'
assert_code "uncompiled_edit" 0
assert_stderr_empty "uncompiled_edit"

run_hook "uncompiled_command" "command" '{"tool_input":{"command":"./gradlew :app:assembleDebug"}}'
assert_code "uncompiled_command" 0
assert_stderr_empty "uncompiled_command"

run_hook "uncompiled_stop" "stop" '{}'
assert_code "uncompiled_stop" 0
assert_stderr_empty "uncompiled_stop"

echo "PASS all hook cases"
echo "Work dir: $WORK_DIR"
```

## 判定标准

执行成功时必须看到：

```text
PASS all hook cases
```

失败时报告以下信息：

```markdown
## Agent Hook Benchmark Result

| Case | Verdict | Evidence |
|------|---------|----------|
| HOOK-1 | PASS / FAIL | exit code + stderr 摘要 |
| HOOK-2 | PASS / FAIL | exit code + stderr 摘要 |
| HOOK-3 | PASS / FAIL | exit code + stderr 摘要 |
| HOOK-4 | PASS / FAIL | exit code + stderr 摘要 |

Debug log: `<脚本输出的 Work dir>/jugg-hook-debug.log`
Blocker:
```
