---
name: jugg-android-dev-loop
description: >-
  Use Jugg MCP tools for Android app modify/deploy/verify loop. Trigger ONLY
  when: (A) the project artifact is an APK/AAB installed on a device (NOT an
  IDE plugin, Gradle plugin, library, or tool); AND (B) user explicitly asks
  to build/deploy/verify on device, OR app source was just modified and device
  verification is the next step. Never trigger just because the codebase
  contains Android/Kotlin/Java code.
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
- verify UI properties/relations OR convert design intent into layout_verify assertions -> `references/tool_cards_runtime_observe.md` + `references/guide_layout_verify_assertion.md` (load both)
- query single View runtime properties via reflection (eval_view: textColor, textSize, maxLines, ellipsize, custom getters) -> `references/tool_cards_runtime_observe.md`
- device/project context problem (`MCP_NO_DEVICE`, `MCP_PROJECT_NOT_INITIALIZED`, crash, unknown runtime state) -> `references/tool_cards_troubleshoot.md`
- changes has no effects, decide whether Jugg incremental compile can handle current change (annotation processors, transforms, unknown bugs) -> `references/policy_incremental_compile_limits.md`
- match a specific error message/errorCode to a known fix -> `references/error_patterns.md`

Skip rule: if no Android source code needs to be compiled, deployed, or verified on device, do not execute the loop or load any reference file.

## 5-Step Loop

1. Modify sources.
2. Build/deploy (load `references/tool_cards_build_deploy.md` when details are needed).
3. Runtime actions & evidence (load `references/tool_cards_runtime_observe.md` when details are needed).
   - Run **Target Page Context Gate** first.
   - Use `layout_verify` for property/relation acceptance checks (verify-first, see Core Rules).
   - Use `eval_view` for properties `layout_verify` cannot query (maxLines, ellipsize, custom getters).
   - Collect screenshot/recording evidence only after the gate passes.
4. Verdict:
   - **PASS** -> step 5.
   - **FAIL** -> back to step 1 (fix source), respect retry budget.
   - **INCONCLUSIVE** -> gather more evidence or ask user when exhausted all available methods.
5. Final staging:
   - clear `${projectDir}/build/mcp_fetch/final`
   - copy final artifacts as `final_screenshot.png` / `final_record.mp4`

## UI Navigation Prerequisite

When a task involves **UI verification** (runtime observe, screenshot comparison, layout check, etc.), enforce navigation-first verification:

1. Ensure a navigation sequence to target page exists (ask user if missing).
2. Execute runtime context verification before evidence collection.
3. Collect final screenshot/recording only after context verification passes.

Detailed rules (context gate, retry policy, no-early-evidence, fast profile) are defined in `references/tool_cards_runtime_observe.md`.

### When This Applies

- Any task that requires observing or verifying UI state on device.
- Any task where the app will be restarted (deploy implies restart).
- Does **not** apply to pure code modification tasks without runtime verification.

## Core Rules

- `projectDir`: use current working directory by default.
- Max autonomous retries for same failure category: `3`.
- Never claim success without artifact evidence.
  - **UI verification tasks**: require screenshot or recording artifact as evidence.
  - **compile_only tasks** (no UI verification): `status=OK` with `isFinal=true` and `logPath` is sufficient evidence; screenshot/recording is not required.
- **Verify-first strategy**: for UI property/relation acceptance checks (text, visibility, bounds, spacing, alignment, etc.), prefer `layout_verify` over manual `layout_dump` JSON parsing or `screenshot` visual inspection. For properties not supported by `layout_verify` (maxLines, ellipsize, cornerRadius, custom View getters), use `eval_view`. Default flow is `layout_verify` (auto snapshot) → `eval_view` (if needed) → `screenshot`. All numeric values (bounds/padding/spacing) are always in dp — no `unit` parameter needed.
- Never tap with guessed coordinates; prefer element mode (`resourceId`/`text`/`contentDesc`) over manual coordinates.
- Runtime interaction strategy: prefer `element tap`; if element mode is not suitable, use `layout_dump + coordinate tap`; use `screenshot + percent tap` only when ViewHierarchy path is clearly unavailable.
- Unknown/high-risk failure: stop and ask user.
- Any deploy (`compile_and_deploy`) or app restart invalidates previous page context; rerun Target Page Context Gate immediately after deploy completes. If gate confirms same page, continue verification; if page changed, re-execute navigation sequence to return to target page.
- Reuse validated navigation sequence and page anchors within the same session to avoid repeated user queries.

## Observe Delegation Policy

`layout_dump` / `screenshot` / `recording` produce large context (layout JSON, images, video). Isolate observation from main agent context when possible.

If runtime supports MCP-capable sub-agents, delegate observation when **any** condition is true:

1. **>=2 large context tool calls**.
2. **Analysis required on heavy output**.

Use main-agent direct call when: single tool call **and** result is used as-is without analysis.

If runtime does not support MCP-capable sub-agents, execute in main agent and summarize heavy outputs instead of copying raw payloads.

**Default**: when unsure, prefer delegation if available.

Sub-agent must have MCP tool access. Claude Code example: use `general-purpose` (has MCP access). In other environments, use any agent type that exposes MCP tools.

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
