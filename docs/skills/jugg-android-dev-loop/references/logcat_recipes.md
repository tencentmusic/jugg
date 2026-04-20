# Logcat Recipes

> **优先使用 `wait-logs`**：对于 Jugg auto-run 场景，优先调用 `jugg wait-logs --marker '...'`（参见 `cli_manual.md §wait-logs`）。本文档的食谱适用于 `wait-logs` 无法覆盖的长尾场景（无 deploy 基线、自定义多 tag 采集、纯快照排查等）。

Jugg does not wrap `adb logcat`. Use `adb` directly with the templates below.

---

## General Principles

- Always bound with `timeout` to avoid hanging the agent turn.
- Clear the buffer with `-c` before reproducing, so output only contains the new run.
- Scope by `--pid` whenever possible (cheaper + less noisy than tag filters).

```bash
# Resolve pid of the app under test
PID=$(adb shell pidof <applicationId>)
```

---

## 1. Capture Logs for a Single Run (Bounded Window)

```bash
adb logcat -c
# ... trigger the scenario (deploy / restart / tap) ...
timeout 15 adb logcat -d --pid=$PID
```

Use `-d` (dump & exit) once the scenario finishes. Keep the window short; Jugg tests rarely need >30s of logs.

---

## 2. Filter by Tag

```bash
# Single tag, silence the rest
timeout 15 adb logcat -d -s MyTag:*

# Multiple tags
timeout 15 adb logcat -d -s MyTag:* OtherTag:W *:S

# Tag + pid
timeout 15 adb logcat -d --pid=$PID -s MyTag:*
```

`-s` = "silent default + whitelist". Level suffix (`:V/D/I/W/E`) works as usual.

---

## 3. Grep / Regex Filtering

```bash
# Error-level lines for current app
timeout 15 adb logcat -d --pid=$PID *:E

# Regex across any tag
timeout 15 adb logcat -d --pid=$PID | grep -E 'LoginResult|Token(Expired|Invalid)'
```

Prefer level filter before piping to grep — reduces adb traffic.

---

## 4. Wait Until a Marker Appears, With Timeout

Use this when verification depends on an async log line (e.g. auto-run entry prints `VERIFY_DONE`).

```bash
# Exit as soon as marker is seen, or after 20s
timeout 20 adb logcat --pid=$PID | grep -m1 -E 'VERIFY_DONE|VERIFY_FAIL'
```

`-m1` makes grep exit after first hit; `timeout` caps the wait.

---

## 5. Auto-Stop on Crash + Return Crash Block

```bash
adb logcat -c
# ... reproduce ...
timeout 30 adb logcat --pid=$PID \
  | awk '
      /FATAL EXCEPTION|ANR in|tombstoned|signal [0-9]+ \(SIG/ {hit=1}
      hit {print}
      /^$/ && hit {exit}
    '
```

If no crash happens within 30s, `timeout` ends the pipe and returns clean. For post-mortem (app already crashed), see Recipe 8.

---

## 6. Watch ANR / Native Crash Signals Only

```bash
timeout 30 adb logcat -d \
  -s AndroidRuntime:E ActivityManager:E DEBUG:* libc:F *:S
```

Covers Java crash (`AndroidRuntime`), ANR (`ActivityManager`), native tombstone (`DEBUG`, `libc`).

---

## 7. Follow-and-Tee for Long Sessions

When you must watch live but also keep a file for later grep:

```bash
adb logcat -c
adb logcat --pid=$PID | tee /tmp/run.log &
LOGCAT_BG=$!
# ... do things ...
kill $LOGCAT_BG
grep -E 'pattern' /tmp/run.log
```

Only use this when a bounded `-d` snapshot is insufficient.

---

## 8. Post-Mortem Crash Collection (App Already Crashed)

When the app has already crashed, there is no live pid. Use the crash buffer directly:

```bash
# Step 1: try crash buffer first (persists across reboots, already filtered for fatals)
adb logcat -d -b crash -v threadtime | grep -A 80 "FATAL EXCEPTION\|Fatal signal"

# Step 2: if no output, fall back to main buffer
adb logcat -d -b main -v threadtime | grep -A 80 "FATAL EXCEPTION\|Fatal signal"
```

To scope to the target package when the process has already exited:

```bash
adb logcat -d -b crash -v threadtime | grep -E "<packageName>|FATAL EXCEPTION|Fatal signal" | tail -50
```

Always try `-b crash` first — it is pre-filtered for fatal signals and persists longer than `-b main`.
