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

CLI reference → see `cli_manual.md`

---

## Phase 0 — Context Interview

Collect mandatory variables before any action.

| Variable | Source | Fallback |
|----------|--------|----------|
| `projectDir` | CLI auto-resolved from `$PWD` | Ask user only if CLI reports "not under any Jugg project" |
| `hasPlayground` | User explicitly provides playground code/file | Default `false` |
| `deviceReady` | `devices` result | No device → compile-only mode |

If `hasPlayground=true`, additionally collect:

| Variable | Source | Fallback |
|----------|--------|----------|
| `targetPage` | User description of target Activity/page | Ask user |
| `verifyMethod` | `ui` / `log` / `both` | Default `both` |

---

## Phase 1 — Scenario Router

Route to the correct flow based on collected context:

```
if user says "compile only" or "no deploy":
  → flow_no_playground.md §compile-only
elif hasPlayground == false AND user requests verification:
  → output playground setup guidance (below)
elif hasPlayground == false:
  → flow_no_playground.md
elif hasPlayground == true:
  → flow_with_playground.md
```

Playground setup guidance (when verification requested but no playground):

```
To enable on-device verification, configure a playground.
→ see guide_playground.md §quick-start
```

---

## Phase 2 — Read Gate

Before opening any reference, output a LoadDecision:

```
LoadDecision: scenario=<no_playground|with_playground|compile_only> load=<files> why=<sentence>
```

| Scenario | Primary Reference | Supplementary (on-demand) |
|----------|-------------------|---------------------------|
| compile-only | `flow_no_playground.md` | `error_patterns.md`, `policy_incremental_compile_limits.md` |
| no playground (deploy) | `flow_no_playground.md` | `error_patterns.md`, `policy_incremental_compile_limits.md` |
| with playground | `flow_with_playground.md` | `guide_playground.md`, `error_patterns.md` |

Load on-demand at the step that needs them. After loading: `✓ Loaded: [file]`.

---

## Mandatory Rules

| # | Rule |
|---|------|
| 1 | Route by scenario first; do not mix flows |
| 2 | Any source modification must pass compilation before task is done |
| 3 | `deploy` invalidates all prior runtime state; rerun verification from scratch |
| 4 | Retry budget: max 3 per failure category (`compile-error` / `deploy-error` / `verify-fail`) |
| 5 | Budget exhausted → `🛑 RETRY LIMIT: [category] exhausted after 3 attempts` → ask user |
| 6 | `MCP_NO_DEVICE` / `No device` → switch to compile-only, do not attempt deploy fix |
| 7 | On self-detected rule violation: `🚨 VIOLATION: [rule] — [what]` → roll back to last valid state |

---

## Error Reviewer

When build/deploy/runtime error occurs:

1. Match against `error_patterns.md` signatures.
2. Score: `confidence` × `scope` from pattern.
3. Output: `🔍 Error: pattern=<id> confidence=<n> scope=<s> auto_apply=<yes|no> evidence="<log quote>"`
4. Decision: `confidence≥0.8 AND scope=low` → auto-fix. Otherwise → propose and wait user.

---

## Report Template

At task completion, output structured summary:

```
# Jugg Dev Loop Report
Timestamp: {{ISO 8601}}
Scenario:  {{compile_only | no_playground | with_playground}}
Project:   {{projectDir}}

## Pipeline Trace
| Step | Status | Detail |
|------|--------|--------|

## Verdict: **{{PASS | FAIL | INCONCLUSIVE}}**
```

Status values: `✅ PASS` / `❌ FAIL` / `⏭ SKIP` / `🔄 RETRY(n)`.
