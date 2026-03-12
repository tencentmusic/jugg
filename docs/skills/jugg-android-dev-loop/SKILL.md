---
name: jugg-android-dev-loop
description: >-
  Use Jugg MCP tools for Android app modify/deploy/verify loop. Trigger ONLY when both (A) and (B) are true:
  (A) the project artifact is an APK/AAB installed on a device (NOT an IDE plugin, Gradle plugin, library, or tool); 
  (B) user explicitly mention Jugg, or asks to build/deploy/verify on device, OR app source was just modified and device
  verification is the next step.
---

# Jugg MCP Android Dev Loop (Compact Router)

## Objective

Use Jugg MCP tools to finish Android tasks with a deterministic loop:

`modify -> build/deploy -> runtime verify -> collect evidence -> iterate`

Default to MCP-only flow and avoid raw adb in normal execution.

## ⚠️ Mandatory Execution Rules (violation = task failure)

### Rule 1: Read After LoadDecision
- If LoadDecision's load field is not none
- Must immediately Read all declared documents
- Output `✓ Loaded: [filename]`

### Rule 2: Output Step 0 Table Before Step 3
- Before code modification, must output suspicious area table
- Format: | Component | Property | Expected | Selector |

### Rule 3: activity_stack After Deploy
- After any restart_app / compile_and_deploy
- Must run activity_stack to confirm page first
- Direct screenshot is FORBIDDEN

### Rule 4: Tool Priority
- Page confirmation: activity_stack > layout_dump > screenshot
- Property verification: layout_verify > eval_view > screenshot

## Read Gate (Single Source of Truth)

Read only this file first, then output a `LoadDecision` before opening any reference file.

`LoadDecision` format:

- `stage`: `plan | execute | troubleshoot`
- `intent`: one short phrase
- `load`: file path list, or `none`
- `why`: one sentence

Reference load rules:

- Load only files required by current intent.
- Never bulk-load all `references`.

Intent -> reference file mapping (3 categories):

**Build/Deploy**:
- compile/deploy/failure handling -> `tool_cards_build_deploy.md`
- incremental compile limits -> `policy_incremental_compile_limits.md`
- error pattern matching -> `error_patterns.md`

**Runtime Observe**:
- tap/layout/screenshot/recording -> `tool_cards_runtime_observe.md`
- UI verification + design-spec assertions -> `tool_cards_runtime_observe.md` + `guide_layout_verify_assertion.md`

**Troubleshoot**:
- device/project context issues -> `tool_cards_troubleshoot.md`

Skip rule: if no Android source code needs to be compiled, deployed, or verified on device, do not execute the loop or load any reference file. Common non-trigger cases:

- Modifying Gradle plugin, IDE plugin, or build tool source (not app code).
- Modifying library/SDK source that is not directly installed as APK on device.
- Code review, documentation, or refactoring tasks without device verification intent.

## 5-Step Loop

1. Modify sources.
2. Build/deploy (load `references/tool_cards_build_deploy.md` when details are needed).
3. Runtime actions & evidence (load `references/tool_cards_runtime_observe.md` when details are needed).
   - **If structured design data is available**: convert design spec into `layout_verify` / `eval_view` assertions first (see `guide_layout_verify_assertion.md §6`). Do not proceed to runtime verification until assertion set is complete.
   - Run **Target Page Context Gate** first.
   - Use `layout_verify` for property/relation acceptance checks (verify-first, see Core Rules).
   - Use `eval_view` for properties `layout_verify` cannot query (maxLines, ellipsize, custom getters).
   - Collect screenshot/recording evidence only after the gate passes.
4. Verdict:
   - **If structured design data is available**: produce a verification report (`guide_layout_verify_assertion.md §7`) before changing code. Present to user for review.
   - **PASS** -> step 5.
   - **FAIL** -> back to step 1 (fix source), then **re-execute the complete verification flow** (Step 0 → Step 7, or §6+§7 if design-spec-driven). Partial re-checks of only the failed items are not acceptable. Respect retry budget.
   - **INCONCLUSIVE** -> gather more evidence or ask user when exhausted all available methods.
5. Final staging:
   - clear `${projectDir}/build/mcp_fetch/final`
   - copy final artifacts as `final_screenshot.png` / `final_record.mp4`


## Core Rules

- `projectDir`: use current working directory by default. For multi-module projects, use root project directory.
- Max autonomous retries: `3` per failure category.
- Evidence requirement:
  - UI verification: screenshot/recording artifact required.
  - compile_only: `status=OK` + `isFinal=true` + `logPath` sufficient.
- Verification strategy (see reference files for details):
  - Prefer `layout_verify` over `layout_dump` parsing or `screenshot` inspection.
  - Use `eval_view` for properties `layout_verify` cannot query (maxLines, ellipsize, custom getters).
  - Walk selector fallback chain on match failure (see `guide_layout_verify_assertion.md §1.3.1`).
  - Design-spec-driven: convert spec to assertions first (§6), produce report before code changes (§7).
- Tap strategy: prefer element mode (`resourceId`/`text`/`contentDesc`) → `layout_dump + coordinate` → `screenshot + percent`.
- UI verification: ensure navigation sequence exists, run Target Page Context Gate before evidence collection. Any deploy/restart invalidates context; rerun gate immediately.
- Unknown/high-risk failure: stop and ask user.

## Observe Delegation

Delegate `layout_dump`/`screenshot`/`recording` to MCP-capable sub-agent when:
- >=2 large context calls, OR
- Analysis required on heavy output

Sub-agent returns `{verdict, summary, artifacts, issues}` only, never raw payloads.

## Error Handling

- Known pattern + low-risk fix: auto apply.
- Unknown pattern or confidence `< 0.8`: ask user before large changes.
- MCP result fields: `status` (OK/ERROR), `message`, `data`, `artifacts`, `errorCode`.

## Response Checklist

Each result summary should include:

- `projectDir` used
- build path used
- key steps and statuses
- artifact absolute paths
- final pass/fail verdict
- next action on failure

## Quick Example: Change a TextView Label

Scenario: change button text from "Submit" to "Confirm" in `activity_main.xml`, verify on device.

```
1. Modify: edit android:text="Submit" → "Confirm" in activity_main.xml.
2. Build:  compile_and_deploy(projectDir) → poll get_compile_status(jobId) until isFinal=true.
3. Gate:   restart_app(projectDir, tap_actions=[{text:"Settings"}]) → layout_dump → confirm anchor.
4. Verify: layout_verify(target={resourceId:"btn_submit"}, checks=[{type:"property", property:"text", op:"eq", value:"Confirm"}]) → PASS.
5. Evidence: screenshot(projectDir) → copy to ${projectDir}/build/mcp_fetch/final/final_screenshot.png.
```

Verdict: **PASS** — text changed, screenshot artifact collected.
