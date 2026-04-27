---
name: jugg-android-dev-loop
version: 1.0.5
date: 2026-04-23
description: >-
  Use when editing source files (Java/Kotlin/XML/layout/AndroidManifest/Gradle)
  in a Android project, or when user asks to build/deploy/verify an Android app.
  Also trigger when the user says phrases like "start coding", "开始实现",
  "implement it", "实现", "fix", "修复", "modify", "修改", "create", "add", "新增", "refactor", "重构".
---

# Jugg Android Dev Loop

**What is Jugg?** Jugg is an Android tools that bypasses full Gradle builds via incremental compilation and deployment, reducing build frequency while preserving Gradle artifacts.

This skill use Jugg CLI to drive development loop for Android: modify → build → deploy → verify → iterate.

---

## Definition - Auto-Run Entry

An **auto-run entry** is a user-designated method (e.g. `com.myapp.Test.run`) that runs automatically after app launch. Agent writes verification code into it and inspects logs/UI to confirm behavior. It is the primary verification mechanism in the Jugg dev loop.

> **⚠️ Entry location is NOT auto-discoverable.** The user must declare the fully-qualified method name in the prompt (中文或 English 均可). If not declared and not visible in context, **stop and ask** — do not guess or search the codebase. To author the entry body, see `references/guide_write_auto_run_entry_code.md`.

---

## Phase 0 — Context Interview

Collect mandatory variables before any action.

| Variable | Source | Fallback |
|----------|--------|----------|
| `projectDir` | CLI auto-resolved from `$PWD` | Ask user only if CLI reports "not under any Jugg project" |
| `hasAutoRunEntry` | `true` only when the user has **explicitly declared** the entry's fully-qualified method (e.g. `com.myapp.Test.run`) in the prompt or current context. See **§ Auto-Run Entry**. | Default `false`. Never infer from code search. |

---

## Phase 1 — Scenario Route & Load

Route based on context, then load primary reference:

```
if user asks to install jugg CLI (e.g. "install jugg cli", "add jugg cli to PATH"):
  → references/guide_install_cli.md
elif user says "no deploy":
  → references/flow_no_auto_run.md §compile-only
elif hasAutoRunEntry == false AND user requests verification:
  → ask user to declare auto-run entry (fully-qualified method, e.g. `com.myapp.Test.run`); then route to references/flow_with_auto_run.md (see references/guide_write_auto_run_entry_code.md for entry body)
elif hasAutoRunEntry == false:
  → references/flow_no_auto_run.md
elif hasAutoRunEntry == true:
  → references/flow_with_auto_run.md
```

| Scenario | Primary Reference | Supplementary (on-demand) |
|----------|-------------------|---------------------------|
| install jugg CLI | `references/guide_install_cli.md` | — |
| no-deploy | `references/flow_no_auto_run.md` | `references/error_patterns.md`, `references/policy_incremental_compile_limits.md` |
| no auto-run entry (deploy) | `references/flow_no_auto_run.md` | `references/error_patterns.md`, `references/policy_incremental_compile_limits.md` |
| with auto-run entry | `references/flow_with_auto_run.md` | `references/guide_write_auto_run_entry_code.md`, `references/error_patterns.md` |

Supplementary references load on-demand at the step that needs them.

---

## Mandatory Rules

| # | Rule |
|---|------|
| 1 | Route by scenario first; do not mix flows |
| 2 | Complete **all** source file edits first, then trigger compile/deploy **once**. Never compile after each individual file edit. |
| 3 | Any source modification must pass compilation before task is done |

---

## CLI Quick Reference

### Entry

```
python3 {SKILL_DIR}/scripts/jugg.py <subcommand> [options]
```

### Output Format

All commands print JSON to stdout:

```json
{"status": "OK|ERROR", "message": "..."}
```

- `status: OK` → succeeded.
- `status: ERROR` → failed; read `message` for cause.

### Build & Deploy Commands

All build commands **block** until completion; no polling needed.

| Command | Purpose | When to Use |
|---------|---------|-------------|
| `deploy` | Compile + deploy to device | **Default path** |
| `compile` | Compile modified sources, no deploy | Should always use `deploy` by default. Use `compile` **Only** when user **explicitly** says "no deploy", "don't deploy", "compile only" — never inferred by agent |
| `gradle-build` | Full Gradle compile fallback | After `deploy` **retries exhausted and still failed** |
| `clean-reinstall` | Clear app data + reinstall APK | **Only** for clean data situation |

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
python3 {SKILL_DIR}/scripts/jugg.py wait-logs --marker '\[JUGG_AR\] DONE'  # --marker: Java Pattern regex matched against log message; block until marker/crash/timeout
```

```
python3 {SKILL_DIR}/scripts/jugg.py wait-logs --marker '<regex>' [--tags t1,t2] [--timeout-ms ms]
# stopReason: marker → parse logs; crash → FAIL; timeout → INCONCLUSIVE → see references/cli_manual.md §wait-logs for flags
```

### UI Commands (low-frequency)

For UI interaction/inspection (tap, view-locate, view-inspect, layout-dump) → load `references/cli_manual.md`.

## Build Fallback Chain

On compile/deploy failure, follow this order:

1. Read error detail.
2. Modified source and retry `deploy` up to 3 times.
3. If still failing → `gradle-build`.
4. Call `ssh-info` (requires explicit user consent) when `gradle-build` is remote compile and still failing.
5. Still unclear → stop, ask user.
