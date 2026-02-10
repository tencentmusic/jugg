---
name: jugg-android-dev-loop
description: Teach Agent to use Jugg MCP tools directly for deterministic Android modify/verify closed-loop. No runner scripts needed.
---

# Jugg MCP Android Dev Loop

## Objective

Teach agent to reliably finish Android engineering tasks with a deterministic loop:

- modify (if needed) -> deploy -> observe -> validate -> iterate
- return structured evidence (`status/errorCode/data/artifacts`)
- stop safely on unknown/high-risk failures

Strongly prefer MCP tools and avoid direct external adb commands in normal flow.

## Prerequisites

Before starting the closed-loop, ensure:

1. **Jugg is initialized** — the project appears in `list_projects` output.
2. **MCP server is reachable** — jugg-mcp is available.
3. **Device is connected** — at least one online device/emulator visible in `device_list`.

## Core Workflow (5-Step Closed-Loop SOP)

```
Step 1: list_projects             → confirm projectDir
Step 2: device_list               → confirm online device
Step 3: compile_and_deploy        → build and push artifacts to device
Step 4: start_app + tap                → launch app and interact
Step 5: screenshot/layout_dump    → collect verification evidence
```

### Step 1: Confirm Project

Call `list_projects` (no arguments). Pick the matching `projectDir` from the response.

### Step 2: Confirm Device

Call `device_list` with `projectDir`. Verify at least one device has `isOnline=true`. Use `serial` from response for subsequent device-targeting calls.

If no device is online:

1. Try to start the first available local Android emulator (AVD) once.
2. Wait for device boot and rerun `device_list`.
3. If still no device, stop and ask user to prepare a device.

### Step 3: Build and Deploy

- **Default path**: `compile_and_deploy` with `projectDir` — compiles then deploys to device.
- **Compile-only path**: `compile_only` with `projectDir` — when no device is available or you only need to verify compilation.
- **Heavy fallback (use sparingly)**: `force_gradle_compile` with `projectDir` only after `compile_and_deploy` fails 3 consecutive retries.

### Step 4: Runtime Actions

- `start_app` with `projectDir` (and optional `packageName`, `serial`) for default entry. This is the default runtime path.
- `start_activity` is advanced-only and should not be the default in this skill flow.
- `tap` with `projectDir`, `x`, `y` for UI interaction.
- Use `layout_dump` before `tap` to discover element coordinates.

### Step 5: Collect Evidence

- `screenshot` for visual proof.
- `layout_dump` for structural verification.
- Optional: `record` for video trace with in-record start_app + tap.

### Step 6: Final Artifact Staging

After task verification is complete:

1. Fully clear `${projectDir}/build/mcp_fetch/final` first.
2. Copy the final screenshot and final recording into that directory.
3. Keep stable filenames (`final_screenshot.png`, `final_record.mp4`) to avoid ambiguity.
4. Optionally keep original timestamped filenames in the same directory for traceability.

## Hard Guardrails

- Max autonomous retries: `3` (same failure category).
- Unknown failure category: stop and ask user.
- Potentially destructive change (manifest/signing/gradle-wide refactor): ask user before apply.
- Never claim success without artifact evidence.
- For `start_emulator`, never treat unit-test pass as runtime success; require runtime evidence (`adb devices` online or `device_list` online device).
- Never reuse stale final artifacts: `build/mcp_fetch/final` must be emptied before copy.
- `force_gradle_compile` is very heavy; do not use it before 3 consecutive `compile_and_deploy` failures.

## Decision Rules

- Prefer `compile_and_deploy` for normal iteration (compiles then deploys).
- Use `compile_only` when no device is connected or only compilation check is needed.
- Switch to `clean_reinstall_apk` when incremental deploy state is corrupted or signatures mismatch.
- If `compile_and_deploy` fails, retry `compile_and_deploy` up to 3 times first.
- Only after 3 consecutive failures, Agent may try `force_gradle_compile` -> retry `compile_and_deploy` -> `clean_reinstall_apk`.
- Treat missing devices as conditional failure (errorCode `MCP_NO_DEVICE`): attempt first-local-emulator startup once, then ask user if none is available or startup fails.
- Strongly prefer MCP-only execution and avoid raw adb in normal flow.

## MCP Response Format

All MCP tools return `structuredContent` with this shape (aligned with `McpToolResult`):

```json
{
  "status": "OK | ERROR",
  "message": "concise human-readable message",
  "data": { /* tool-specific structured payload */ },
  "artifacts": [{"type": "image", "path": "/abs/path.png"}],
  "errorCode": "MCP_INTERNAL_ERROR | null"
}
```

Fields:

| Field | Type | Description |
|-------|------|-------------|
| `status` | `"OK"` \| `"ERROR"` | Whether the tool call succeeded |
| `message` | `string` | Human-readable result or error description |
| `data` | `object` | Tool-specific structured data for next decision |
| `artifacts` | `array` | File artifacts produced (each has `type` and `path`) |
| `errorCode` | `string \| null` | Stable error code on failure; `null` on success |

## Error Codes Quick Reference

| Error Code | Meaning | Agent Action |
|-----------|---------|-------------|
| `MCP_PROJECT_NOT_INITIALIZED` | Project not opened/initialized in IDE | Ask user to open project |
| `MCP_NO_DEVICE` | No online device connected | Ask user to connect device |
| `MCP_INVALID_PARAMS` | Bad tool arguments | Fix arguments and retry |
| `MCP_TOOL_NOT_FOUND` | Tool name not recognized | Check available tools |
| `MCP_INTERNAL_ERROR` | Internal runtime failure | Try fallback path |
| `MCP_INVALID_JSON_RPC` | Malformed JSON-RPC request | Fix request format |
| `MCP_METHOD_NOT_SUPPORTED` | Unsupported MCP method | Use supported method |

## Failure Handling Strategy

When compile/deploy fails, follow this strict order:

1. **Parse error first**: read `structuredContent.message`, `structuredContent.data`, and `structuredContent.errorCode` for root-cause clues.
2. **Try deterministic diagnosis**: match against known error patterns in `references/error_patterns.md`.
3. **Use auto downgrade when applicable**:
   - `compile_and_deploy` fails -> retry `compile_and_deploy` (up to 3 consecutive attempts)
   - still failing after 3 retries -> `force_gradle_compile` -> retry `compile_and_deploy`
   - Still fails -> `clean_reinstall_apk`
   - `clean_reinstall_apk` fails -> stop and report
4. **Stop and confirm with user** when root cause is still unclear.

Do not silently loop retries without diagnosis.

## State Machine (SOP)

Use this lightweight state machine for all tasks:

- `IDLE -> ANALYZING -> MODIFYING -> COMPILING -> DEPLOYING -> OBSERVING -> VALIDATING`
- failure branch: `ANY -> RECOVERING -> (resume previous state or stop)`

Detailed transitions and per-state allowed actions:

- `references/state_machine.md`

## Atomic Tool Cards

Per-tool decision guidance for direct MCP calls:

- `references/tool_cards.md`

Each card defines:

- when to use
- required/optional input schema
- success output for next step
- failure category and recovery

## Error Pattern Library

Use/extend known patterns before free-form fixes:

- `references/error_patterns.md`

Rules:

- known pattern + low-risk fix: auto apply
- unknown pattern or confidence < `0.8`: ask user before large changes
- always append latest pattern outcome into response summary

## Examples

Step-by-step examples of common scenarios:

- `examples/01_fix_compile_error.md` — compile error diagnosis and auto-fix
- `examples/02_ui_change_verify.md` — UI modification with layout_dump + tap + screenshot verification
- `examples/03_deploy_fallback.md` — deploy failure with automatic downgrade chain

## Resources

### references/

- `references/closed_loop.md`: execution policy and troubleshooting guidance.
- `references/state_machine.md`: lightweight state machine and transition rules.
- `references/tool_cards.md`: atomic tool usage cards.
- `references/error_patterns.md`: known failure signatures and fix strategies.
