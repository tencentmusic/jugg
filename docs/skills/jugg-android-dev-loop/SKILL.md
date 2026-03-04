---
name: jugg-android-dev-loop
description: Use Jugg MCP tools for a deterministic Android modify/verify closed-loop (no runner scripts). Trigger ONLY when ALL conditions are met - (1) current project is an Android application project AND (2) user explicitly asks to build/deploy/verify on device, OR Android app source code/resources were modified and verification is the logical next step. Do NOT trigger for non-Android-app codebase even if they contain Kotlin/Java files or Android-related tooling code.
---

# Jugg MCP Android Dev Loop (Compact Router)

## Objective

Use Jugg MCP tools to finish Android tasks with a deterministic loop:

`modify -> build/deploy -> runtime verify -> collect evidence -> iterate`

Default to MCP-only flow and avoid raw adb in normal execution.

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

Intent -> reference file mapping:

- compile, deploy, or handle compile/deploy failure -> `references/tool_cards_build_deploy.md`
- interact with running app or collect runtime evidence (tap, layout, screenshot, recording) -> `references/tool_cards_runtime_observe.md`
- device/project context problem (`MCP_NO_DEVICE`, `MCP_PROJECT_NOT_INITIALIZED`, crash, unknown runtime state) -> `references/tool_cards_troubleshoot.md`
- changes has no effects, decide whether Jugg incremental compile can handle current change (annotation processors, transforms, unknown bugs) -> `references/policy_incremental_compile_limits.md`
- match a specific error message/errorCode to a known fix -> `references/error_patterns.md`

Skip rule: if no Android source code needs to be compiled, deployed, or verified on device, do not execute the loop or load any reference file.

## 5-Step Loop

1. Modify sources.
2. Build/deploy (load `references/tool_cards_build_deploy.md` when details are needed).
3. Runtime actions & evidence (load `references/tool_cards_runtime_observe.md` when details are needed).
4. Verdict:
   - **PASS** -> step 5.
   - **FAIL** -> back to step 1 (fix source), respect retry budget.
   - **INCONCLUSIVE** -> gather more evidence or ask user when exhausted all available methods.
5. Final staging:
   - clear `${projectDir}/build/mcp_fetch/final`
   - copy final artifacts as `final_screenshot.png` / `final_record.mp4`

## UI Navigation Prerequisite

When a task involves **UI verification** (runtime observe, screenshot comparison, layout check, etc.), the agent **must** know how to navigate from app launch to the target debug page before starting any work.

### Required: Navigation Tap Sequence

A navigation tap sequence is an ordered list of tap actions (by `resourceId` or `text`) that brings the app from its launch screen to the target page. Example:

```
1. tap text="Settings"
2. tap resourceId="menu_advanced"
3. tap text="Debug Panel"
```

### Workflow

1. **Check**: before starting a UI-related task, determine whether a navigation tap sequence to the target page is available.
2. **Ask if missing**: if the user has not provided one, **stop and ask** the user to supply the tap sequence (id or text based). Do not guess or skip this step.
3. **Verify first**: once obtained, execute the tap sequence on the device to confirm it reaches the expected page (use `layout_dump` or `screenshot` to verify). Only proceed with the actual task after verification passes.
4. **Retry on failure**: if the navigation sequence fails (element not found, wrong page reached), report the failure details to the user and ask for a corrected sequence.

### When This Applies

- Any task that requires observing or verifying UI state on device.
- Any task where the app will be restarted (deploy implies restart).
- Does **not** apply to pure code modification tasks without runtime verification.

## Core Rules

- `projectDir`: use current working directory by default.
- Max autonomous retries for same failure category: `3`.
- Never claim success without artifact evidence.
- Never tap with guessed coordinates; prefer element mode (`resourceId`/`text`/`contentDesc`) over manual coordinates.
- Runtime interaction strategy: prefer `element tap`; if element mode is not suitable, use `layout_dump + coordinate tap`; use `screenshot + percent tap` only when ViewHierarchy path is clearly unavailable.
- Unknown/high-risk failure: stop and ask user.

## Observe Delegation Policy

`layout_dump` / `screenshot` / `recording` produce large context (layout JSON, images, video). Isolate observation from main agent context when possible.

Delegate (prefer sub-agent, fallback to main-agent-with-summarize) when **any** condition is true:

1. **>=2 large context tool calls**.
2. **Analysis required on heavy output**.

Direct call from main agent only when: single tool call **and** result is used as-is without analysis.

**Default**: when unsure, delegate.

Sub-agent **must** have MCP tool access (use the agent type with full/all tool access, e.g. `general-purpose` in Claude Code). If unavailable, execute in main agent instead.

Sub-agent returns only `{verdict, summary, artifacts, issues}`, never raw layout JSON/image/video.

## Minimal MCP Result Contract

Use these fields for decisions:

- `status`: `OK | ERROR`
- `message`: concise result/error
- `data`: tool-specific structured output
- `artifacts`: produced files
- `errorCode`: stable failure code or `null`

## Error Handling Gate

- Known pattern + low-risk fix: auto apply.
- Unknown pattern or confidence `< 0.8`: ask user before large changes.

## Response Checklist

Each result summary should include:

- `projectDir` used
- build path used
- key steps and statuses
- artifact absolute paths
- final pass/fail verdict
- next action on failure
