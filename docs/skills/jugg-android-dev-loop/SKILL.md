---
name: jugg-android-dev-loop
description: >-
  Use Jugg for Android app modify/deploy/verify development loop.
  Trigger when ANY of the following is true:
  (1) User explicitly mentions Jugg, or asks to build, deploy, or verify Android App on device.
  (2) Android source code (Java/Kotlin/XML layouts/AndroidManifest) was just modified.
metadata:
  pattern: scenario-routing
  toolset: references/
---

# Jugg Android Dev Loop

CLI-driven development loop for Android: modify → build → deploy → verify → iterate.

---

## CLI Quick Reference

### Entry

```
python3 {SKILL_DIR}/scripts/jugg.py <subcommand> [options]
```

### Output Format

All commands print JSON to stdout:

```json
{"status": "OK|ERROR", "message": "...", "isFinal": true|false}
```

- `status: OK` + `isFinal: true` → succeeded, terminal result.
- `status: OK` + `isFinal: false` → intermediate; re-run same command.
- `status: ERROR` → failed; read `message` for cause.

### Build & Deploy Commands

All build commands **block** until completion; no polling needed.

| Command | Purpose | When to Use |
|---------|---------|-------------|
| `compile` | Compile modified sources, no deploy | No device, or user requests compile-only |
| `deploy` | Compile + deploy to device | **Default path** |
| `gradle-build` | Full Gradle compile fallback | After `deploy` retries exhausted; produces artifact only, follow with `deploy` |
| `clean-reinstall` | Clear app data + reinstall APK | **Only** for install-state corruption or signature conflict |

```
python3 {SKILL_DIR}/scripts/jugg.py compile
python3 {SKILL_DIR}/scripts/jugg.py deploy
python3 {SKILL_DIR}/scripts/jugg.py gradle-build
python3 {SKILL_DIR}/scripts/jugg.py clean-reinstall
```

### Runtime Basic Commands

```
python3 {SKILL_DIR}/scripts/jugg.py restart              # restart app
python3 {SKILL_DIR}/scripts/jugg.py activity-stack       # show current Activity stack
python3 {SKILL_DIR}/scripts/jugg.py devices              # list connected devices
python3 {SKILL_DIR}/scripts/jugg.py crash-report         # collect latest crash report
```

`crash-report` key output fields: `hasCrash`, `crashLogs`, `isProcessAlive`, `relatedActivity`.
- `hasCrash=true` → read `crashLogs` for stack trace.
- `hasCrash=false` + `isProcessAlive=true` → no crash, skip crash path.
- `hasCrash=false` + `isProcessAlive=false` → process died without crash log; check `adb logcat`.

### Build Fallback Chain

On compile/deploy failure, follow this order:

1. Parse `status`/`message` from JSON output.
2. Retry `deploy` up to 3 times.
3. If still failing → `gradle-build`.
4. If still failing → inspect `${projectDir}/build/jugg/log/compile_latest.log`.
5. Only on install-state corruption → `clean-reinstall`.
6. Still unclear → stop, ask user.
7. `ssh-info` requires explicit user consent.

### Advanced Commands

For commands with complex parameters (tap, view-locate, view-inspect, layout-dump, screenshot, recording, ssh-info) → see `cli_manual.md`.

**Flag naming**: all flags accept both kebab-case (e.g. `--resource-id`) and camelCase (e.g. `--resourceId`). camelCase = the MCP parameter name. Examples in `cli_manual.md` use kebab-case.

---

## Phase 0 — Context Interview

Collect mandatory variables before any action.

| Variable | Source | Fallback |
|----------|--------|----------|
| `projectDir` | CLI auto-resolved from `$PWD` | Ask user only if CLI reports "not under any Jugg project" |
| `hasAutoRunEntry` | Whether project has an **auto-run entry** — a method guaranteed to execute after app launch, where Agent can place debug/verification code. User provides entry code/file, or project already has one configured. | Default `false` |

---

## Phase 1 — Scenario Route & Load

Route based on context, then load primary reference:

```
if user asks to install jugg CLI (e.g. "install jugg", "add jugg to PATH"):
  → guide_install_cli.md
elif user says "compile only" or "no deploy":
  → flow_no_auto_run.md §compile-only
elif hasAutoRunEntry == false AND user requests verification:
  → output: "⚠️ Auto-run entry not configured. → see guide_auto_run_entry.md §quick-start"
elif hasAutoRunEntry == false:
  → flow_no_auto_run.md
elif hasAutoRunEntry == true:
  → flow_with_auto_run.md
```

| Scenario | Primary Reference | Supplementary (on-demand) |
|----------|-------------------|---------------------------|
| install jugg CLI | `guide_install_cli.md` | — |
| compile-only | `flow_no_auto_run.md` | `error_patterns.md`, `policy_incremental_compile_limits.md` |
| no auto-run entry (deploy) | `flow_no_auto_run.md` | `error_patterns.md`, `policy_incremental_compile_limits.md` |
| with auto-run entry | `flow_with_auto_run.md` | `guide_auto_run_entry.md`, `error_patterns.md` |

Supplementary references load on-demand at the step that needs them.

---

## Mandatory Rules

| # | Rule |
|---|------|
| 1 | Route by scenario first; do not mix flows |
| 2 | Any source modification must pass compilation before task is done |
