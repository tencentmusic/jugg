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
- Property verification: figma_layout_verify > ui_find + manual compare > eval_view > screenshot

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
- UI verification + design-spec assertions -> `tool_cards_runtime_observe.md` + `guide_ui_verify_assertion.md`

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
   - **If Figma JSON is available**: use `figma_layout_verify(figmaJson, dpr)` for automated batch verification (see `guide_ui_verify_assertion.md §2`). MCP auto-extracts relations and validates against Android layout.
   - **If no Figma**: use `ui_find` to query elements, then manually compare values (see `guide_ui_verify_assertion.md §3`).
   - Run **Target Page Context Gate** first.
   - Use `eval_view` for properties not covered by ui_find (maxLines, ellipsize, custom getters).
   - Collect screenshot/recording evidence only after the gate passes.
4. Verdict:
   - **If Figma JSON is available**: `figma_layout_verify` returns structured report with summary/results/unmatched. Present to user for review.
   - **PASS** -> step 5.
   - **FAIL** -> back to step 1 (fix source), then **re-execute the complete verification flow**. Partial re-checks of only the failed items are not acceptable. Respect retry budget.
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
  - **With Figma JSON**: prefer `figma_layout_verify` — MCP auto-extracts relations and validates with IoU matching.
  - **Without Figma**: use `ui_find` to query element bounds, then manually calculate and compare values.
  - Use `eval_view` for properties not covered by ui_find (maxLines, ellipsize, custom getters).
  - Fixed tolerance: ±2dp absolute or ±5% relative (not configurable).
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
3. Gate:   restart_app(projectDir, tap_actions=[{text:"Settings"}]) → activity_stack → confirm page.
4. Verify: ui_find(target={resourceId:"btn_submit"}) → check matched.selector.text == "Confirm" → PASS.
5. Evidence: screenshot(projectDir) → copy to ${projectDir}/build/mcp_fetch/final/final_screenshot.png.
```

Verdict: **PASS** — text changed, screenshot artifact collected.

## Quick Example: Verify Against Figma Design

Scenario: verify page layout matches Figma design spec.

```
1. Gate:   restart_app(projectDir) → activity_stack → confirm page.
2. Verify: figma_layout_verify(figmaJson="design.json", dpr=1)
   → Returns: {summary: {total:15, passed:12, failed:3}, results:[...], unmatched:[...]}
3. Analyze: Check failed items (spacing/alignment mismatches), review unmatched Figma nodes.
4. Fix:    Modify code to fix spacing/alignment issues.
5. Re-verify: figma_layout_verify again → all passed.
6. Evidence: screenshot(projectDir) → copy to final.
```

Verdict: **PASS** — layout matches design spec.
