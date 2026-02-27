---
name: jugg-android-dev-loop
description: Teach Agent to use Jugg MCP tools directly for deterministic Android modify/verify closed-loop. No runner scripts needed.
---

# Jugg MCP Android Dev Loop (Compact Router)

## Objective

Use Jugg MCP tools to finish Android tasks with a deterministic loop:

`modify -> build/deploy -> runtime verify -> collect evidence -> iterate`

Default to MCP-only flow and avoid raw adb in normal execution.

## Read Gate (Single Source of Truth)

This section is the only entry gate for loading extra docs.

Read only this file first, then output a `LoadDecision` before opening any reference file.

`LoadDecision` format:

- `stage`: `plan | execute | troubleshoot`
- `intent`: one short phrase
- `load`: file path list, or `none`
- `why`: one sentence

Reference load rules:

- Load only files required by current intent.
- Never bulk-load `references` or `examples`.
- If `load=none`, continue execution with this file only.

Intent -> first file mapping:

- compile/deploy decision -> `references/tool_cards_compile.md`
- runtime/evidence decision -> `references/tool_cards_runtime_observe.md`
- context troubleshooting decision -> `references/tool_cards_troubleshoot.md`
- pass criteria/stop criteria/retry policy -> `references/closed_loop.md`
- failure signature matching -> `references/error_patterns.md`
- concrete walkthrough asked by user -> `examples/*.md`

## 5-Step Loop

1. Modify sources.
2. Build/deploy:
   - default: `compile_and_deploy(projectDir)`
   - no device or compile-only ask: `compile_only(projectDir)`
3. Runtime actions (if needed):
   - `restart_app(projectDir)`
   - `layout_dump` before `tap(projectDir, x, y)` when coordinates are unknown
4. Collect evidence (light -> heavy):
   - `activity_stack`, `layout_dump`, `screenshot`
   - optional video: `start_record` -> actions -> `stop_record`
5. Final staging:
   - clear `${projectDir}/build/mcp_fetch/final`
   - copy final artifacts as `final_screenshot.png` / `final_record.mp4`

## Core Rules

- `projectDir`: use current working directory by default.
- Do not preflight with `list_projects`; call it only on project-context errors.
- Max autonomous retries for same failure category: `3`.
- Never claim success without artifact evidence.
- Never reuse stale files in `build/mcp_fetch/final`.
- Unknown/high-risk failure: stop and ask user.

## Async Compile Rule (Mandatory)

For `compile_and_deploy` or `force_gradle_compile`:

- If `isFinal=false`, immediately delegate polling to an `awaiter` sub-agent.
- Poll with `get_compile_status(jobId)` and follow `pollIntervalSuggestedMs` when present.
- Determine result only by terminal compile status.
- If `status=unknown`, treat as invalid job/context; stop and re-check `jobId` source.

## Fallback Chain

Use this order for compile/deploy failures:

1. Parse `status/message/errorCode/data/artifacts`.
2. Retry `compile_and_deploy` up to 3 times.
3. If still no way to fix the expected error use `force_gradle_compile` (heavy), finish async polling, and retry `compile_and_deploy`.
4. If still broken, try `clean_reinstall_apk`.
5. If still unclear, stop and confirm with user.
6. Remote troubleshooting (`request_remote_ssh_info`) requires explicit user consent.

Special case:

- `MCP_NO_DEVICE`: stop and ask user to connect/start device, or switch to `compile_only`.

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
- For detailed mapping and signatures, load `references/error_patterns.md`.

## Reference Entry Points

- Policy/troubleshooting: `references/closed_loop.md`
- Compile/deploy cards: `references/tool_cards_compile.md`
- Runtime/observe cards: `references/tool_cards_runtime_observe.md`
- Troubleshoot cards: `references/tool_cards_troubleshoot.md`
- Error signatures/fixes: `references/error_patterns.md`
- Walkthroughs:
  - `examples/01_fix_compile_error.md`
  - `examples/02_ui_change_verify.md`
  - `examples/03_deploy_fallback.md`
