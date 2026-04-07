---
name: jugg-android-dev-loop
description: >-
  Use Jugg MCP tools for Android app modify/deploy/verify loop.
  PREREQUISITE (must be true for any trigger): the project artifact is an APK/AAB installed on a device —
  NOT a Gradle plugin, IDE plugin, library, or build tool.
  Trigger when ANY of the following is true:
  (1) User explicitly mentions Jugg, or asks to build, deploy, or verify on device.
  (2) App source code (Java/Kotlin/XML layouts/resources/AndroidManifest) was just modified —
      immediately run compile_only to confirm the change compiles before considering the task done.
  (3) App source was modified and device verification is the next logical step.
  OUT OF SCOPE: screenshot / start_record / stop_record are NOT part of this Skill.
  Do NOT call them when this Skill is active.
metadata:
  pattern: pipeline+inversion
  steps: "5"
  toolset: references/
---

# Jugg MCP Android Dev Loop

Deterministic loop: `context-gather → modify → build/deploy → verify → evidence → iterate`. MCP-only; avoid raw adb.

---

## Phase 0 — Context Interview

Collect all mandatory variables before any code modification or tool invocation.

| Variable | Source | Fallback |
|----------|--------|----------|
| `projectDir` | Working directory or user-specified | Ask user |
| `targetPage` | User description or Figma ref | Ask user (skip if compile-only) |
| `navigationSeq` | User-provided tap sequence | Ask user; cache after first run |
| `designSource` | Figma JSON path/URL, or `none` | Assume `none` if not mentioned |
| `deviceReady` | `device_list` result | No device → compile-only mode |

Auto-resolve: if `projectDir` is unambiguous, fill silently. If compile-only, skip `targetPage`/`navigationSeq`/`designSource`. If unresolvable with no fallback → **stop and ask user**.

---

## Phase 1 — Read Gate

Read only this file first. Before opening any reference, output a LoadDecision:

```
LoadDecision: stage=<plan|execute|troubleshoot> intent=<phrase> load=<files|none> why=<sentence>
```

| Keyword Triggers | Reference File |
|-----------------|----------------|
| compile, deploy, failure, fallback | `tool_cards_build_deploy.md` |
| annotation, transform, unsupported | `policy_incremental_compile_limits.md` |
| error, crash, pattern, fix | `error_patterns.md` |
| tap, recording | `tool_cards_runtime_observe.md` |
| figma, verify, spacing, alignment, view_locate, view_inspect, layout_dump | `tool_cards_runtime_observe.md` + `guide_ui_verify_assertion.md` |
| device, project, ssh, crash | `tool_cards_troubleshoot.md` |

Load on-demand at the step that needs them, not pre-loaded. After loading: `✓ Loaded: [file]`.

**Skip Rule**: No APK/AAB compile/deploy/verify intent → do not execute loop or load references.

---

## Phase 2 — Pipeline (5-Step Loop)

Each step: **entry gate → action → exit checkpoint**. No advance until checkpoint satisfied.

### Step 1: Modify

- **Entry**: Phase 0 complete.
- **Action**: Edit source files. Output pre-modify table:

  | Component | Property | Expected | Selector |
  |-----------|----------|----------|----------|

- **Checkpoint ✓**: Files saved, table output.

### Step 2: Build & Deploy

- **Entry**: Step 1 passed.
- **Action**: `compile_and_deploy(projectDir)` → poll `get_compile_status(jobId)` until `isFinal=true`. Use `compile_only` if no device. → see `tool_cards_build_deploy.md`
- **On error**: Load `error_patterns.md` → apply Error Reviewer scoring (§below).
- **Checkpoint ✓**: `status=OK` + `isFinal=true`.
- **Checkpoint ✗**: Fix → return to Step 1 (not to failed step).
- **Mandatory**: Any source modification must pass compilation before task is done, even without deploy.

### Step 3: Runtime Verify

- **Entry**: Step 2 passed + device available.
- **Gate** (mandatory before any verification): call `activity_stack` → confirm target page.
  - Output mandatory gate result line:
    ```
    PageGate: stack=<ActivityName> target=<targetPage> match=<yes|no>
    ```
  - Missing this line = gate not executed; do NOT proceed.
  - On `match=no`: `restart_app(projectDir, tap_actions=navigationSeq)` → re-run gate.
- **Action by designSource** (all verification delegated to subagent — see Core Rules):

  | Scenario | Tool | Priority |
  |----------|------|----------|
  | Confirm element position in layout | `view_locate` | 1st |
  | Confirm displayed content details | `view_inspect` | 1st |
  | `view_locate` cannot satisfy the need | `layout_dump` | Fallback |

  See `guide_ui_verify_assertion.md` for assertion details.
- **Checkpoint ✓**: All checks pass or user acceptance.

### Step 4: Verdict

- **Entry**: Step 3 evidence collected.
- **PASS** → Step 5. **FAIL** → Step 1 (full re-verify, no partial). **INCONCLUSIVE** → gather more or ask user.
- Retry budget: max `3` per failure category.

### Step 5: Evidence Staging

- **Entry**: Step 4 = PASS.
- **Action**: Collect subagent verification report. Output structured pass/fail summary.
- **Checkpoint ✓**: Report output. → see `references/report_template.md`.

---

## Mandatory Rules

| # | Rule | Consequence |
|---|------|-------------|
| 1 | Steps execute 1→2→3→4→5; each checkpoint output before advancing; failure loops to Step 1 | No skip, no partial retry |
| 2 | Before any UI verification: call `activity_stack` and output `PageGate:` line. Missing `PageGate:` line = gate not executed; do NOT proceed to verification | Evidence without gate is invalid |
| 3 | Tool selection is scenario-based with priority: (1) `view_locate` — confirm element position in layout; (2) `view_inspect` — confirm displayed content details; (3) `layout_dump` — fallback only when `view_locate` cannot satisfy the need | Wrong tool = invalid result |
| 4 | Any deploy/restart invalidates all prior runtime observations; rerun gate immediately | Stale context = wrong verdict |
| 5 | On self-detected violation of Rules 1-4: stop → output `🚨 VIOLATION: [rule] — [what]` → roll back to last valid checkpoint | Self-correction does not reset retry budget |

---

## Error Reviewer

When build/deploy/runtime error occurs:

1. Match against `error_patterns.md` signatures.
2. Score: `confidence` (0-1, from pattern) × `scope` (low/med/high, from pattern).
3. Output diagnosis with **mandatory `evidence` field** (direct quote from log; paraphrasing = invalid match):

```
🔍 Error: pattern=<id> confidence=<n> scope=<s> auto_apply=<yes|no> evidence="<log quote>"
```

4. Decision: `confidence≥0.8 AND scope=low` → auto-fix. `confidence≥0.8 AND scope>low` → propose, wait user. `confidence<0.8 OR unknown` → stop, ask user.

---

## Core Rules

- `projectDir`: current working directory by default; multi-module uses root.
- Max retries: `3` per failure category.
- Evidence: UI verification needs tool-based assertion results; compile-only needs `status=OK` + `isFinal=true`.
- Tolerance: ±2dp absolute or ±5% relative (fixed, not configurable).
- **All UI verification (Step 3) must be delegated to a subagent**; the main agent only collects the subagent's structured report and produces the Step 4 verdict.
- Report template and examples → see `references/report_template.md`.
