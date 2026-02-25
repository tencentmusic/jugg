---
name: jugg-android-dev-loop
description: Teach Agent to use Jugg MCP tools directly for deterministic Android modify/verify closed-loop. No runner scripts needed.
---

# Jugg MCP Android Dev Loop

## Objective

Teach agent to reliably finish Android engineering tasks with a deterministic loop:

- modify (if needed) -> build/deploy -> observe -> validate -> iterate
- return structured evidence (`status/errorCode/data/artifacts`)
- stop safely on unknown/high-risk failures

Strongly prefer MCP tools and avoid direct external adb commands in normal flow.

## Core Workflow (5-Step Closed-Loop SOP)

```
Step 1: Modify sources(not using jugg-mcp)
Step 2: compile_and_deploy/compile_only        → build (and deploy when device is ready)
Step 3: start_app + tap                         → launch app and interact (when runtime verification is required)
Step 4: layout_dump/activity_stack/screenshot/start_record+stop_record      → collect verification evidence.
    -> back to Step 1 if something is wrong.
Step 5: final_artifact_staging                  → save final screenshot or recording for user
```

### projectDir Default Rule

- Always use current working directory as `projectDir` by default.
- Do not call `list_projects` as normal-flow preflight.
- Only call `list_projects` when project-related errors appear (for example `MCP_PROJECT_NOT_INITIALIZED` or suspected IDE context drift).

### Step 1: Modify sources

The agent modifies the necessary source code files based on the tasks submitted by the user.

### Step 2: Build and Deploy

- **Default path**: `compile_and_deploy` with `projectDir` — compiles then deploys to default selected device. This tool may return `isFinal=false`; then poll `get_compile_status(jobId)` until terminal.
- **Compile-only path**: `compile_only` with `projectDir` — when no device is available or you only need to verify compilation.
- **Heavy fallback (use sparingly)**: `force_gradle_compile` with `projectDir` only after `compile_and_deploy` fails 3 consecutive retries. This tool may return `isFinal=false`; then poll `get_compile_status(jobId)` until terminal.

If `compile_and_deploy` returns `MCP_NO_DEVICE`:

1. stop automatic flow;
2. ask user whether to start/connect a device;
3. if user only needs compilation verification, continue with `compile_only`.

### Step 3: Runtime Actions

- `start_app` with `projectDir` (and optional `packageName`) for default entry. This is the default runtime path.
- `start_activity` is advanced-only and should not be the default in this skill flow.
- `tap` with `projectDir`, `x`, `y` for UI interaction.
- Use `layout_dump` before `tap` to discover element coordinates.

### Step 4: Collect Evidence

> Tools from light to heavy.

- `activity_stack` for activity state verification (`data.activities` is a component string array ordered top -> bottom; full details stay in `dumpFile`).
- `layout_dump` for structural verification.
- `screenshot` for visual proof.
- Optional: `start_record` + `stop_record` for video trace (run `start_app`/`tap` between the two calls).

### Step 5: Final Artifact Staging

After task verification is complete:

1. Fully clear `${projectDir}/build/mcp_fetch/final` first.
2. Copy the final screenshot and final recording into that directory.
3. Keep stable filenames (`final_screenshot.png`, `final_record.mp4`) to avoid ambiguity.
4. Optionally keep original timestamped filenames in the same directory for traceability.

Note: `build/mcp_fetch/final` is an **agent staging directory**. MCP tool default outputs are usually under `${projectDir}/build/jugg/mcp_fetch/<toolName>`.

## Hard Guardrails

- Max autonomous retries: `3` (same failure category).
- Unknown failure category: stop and ask user.
- Never claim success without artifact evidence.
- Never reuse stale final artifacts: `build/mcp_fetch/final` must be emptied before copy.
- `force_gradle_compile` is very heavy; do not use it before 3 consecutive `compile_and_deploy` failures, unless user says "fallback compile".
- For `compile_and_deploy`, success/failure must be determined by `get_compile_status(jobId)` when `isFinal=false`.
- For `force_gradle_compile`, success/failure must be determined by `get_compile_status(jobId)` when `isFinal=false`.
- For `get_compile_status`, `status=unknown` is not a normal terminal success/failure state; treat it as invalid job/context and stop to re-check `jobId` source.

## Decision Rules

- Prefer `compile_and_deploy` for normal iteration (compiles then deploys).
- Use `compile_only` when only compilation check is needed.
- Use `clean_reinstall_apk` when you need to clear app data.
- If `compile_and_deploy` fails, autonomous retry `compile_and_deploy` up to 3 times first.
- Only after 3 consecutive failures and still crash / no effects are observed, agent may try `force_gradle_compile`.
- Treat missing devices as conditional failure (errorCode `MCP_NO_DEVICE`): stop and ask user to prepare a device.
- If all standard fallback paths fail and remote troubleshooting is required, call `request_remote_ssh_info` only after explicit user consent (`userConsent=true`).
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
| `MCP_NO_DEVICE` | No online device connected | Ask user to connect/start device |
| `MCP_INVALID_PARAMS` | Bad tool arguments | Fix arguments and retry |
| `MCP_TOOL_NOT_FOUND` | Tool name not recognized | Check available tools |
| `MCP_INTERNAL_ERROR` | Internal runtime failure | Try fallback path |
| `MCP_INVALID_JSON_RPC` | Malformed JSON-RPC request | Fix request format |
| `MCP_METHOD_NOT_SUPPORTED` | Unsupported MCP method | Use supported method |

## Failure Handling Strategy

When compile/deploy fails, follow this strict order:

1. **Parse error first**: read `structuredContent.message`, `structuredContent.data`, and `structuredContent.errorCode` for root-cause clues.
   - For `compile_and_deploy`/`force_gradle_compile`, read `data.detail` first when present.
   - If `data.detailTruncated=true` or `artifacts` contains `type=log`, read the log artifact path for full diagnostics.
2. **Try deterministic diagnosis**: match against known error patterns in `references/error_patterns.md`.
   - Only if project/context looks wrong, call `list_projects` to verify current IDE initialized projects.
   - Only if device state is suspicious, call `device_list` to check online/selected target.
3. **Use auto downgrade when applicable**:
   - `compile_and_deploy` fails -> retry `compile_and_deploy` (up to 3 consecutive attempts)
   - still failing after 3 retries -> `force_gradle_compile` (poll `get_compile_status`) -> retry `compile_and_deploy`
   - Still fails -> `clean_reinstall_apk`
   - `clean_reinstall_apk` fails -> stop and report
4. **Stop and confirm with user** when root cause is still unclear.
5. For `status=failed` from `get_compile_status`, inspect `${projectDir}/build/jugg/log/compile_latest.log`.
6. For `status=unknown` from `get_compile_status` (typically with `MCP_INVALID_PARAMS`), stop polling and re-check `jobId` source; re-trigger compile flow if needed.
7. For `start_record` with `MCP_INVALID_PARAMS` and existing `sessionId` in response `data`, call `stop_record(existingSessionId)` first, then retry `start_record`.
8. If repeated failures still cannot be diagnosed locally, ask user whether to enable `request_remote_ssh_info` with explicit consent.

Do not silently loop retries without diagnosis.

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
- `references/tool_cards.md`: atomic tool usage cards.
- `references/error_patterns.md`: known failure signatures and fix strategies.
