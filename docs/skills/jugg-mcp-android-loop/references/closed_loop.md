# Closed-loop Policy (Jugg MCP)

## Goal

Guarantee the agent can finish Android compile/deploy/verify work in one run and provide objective evidence.

## Required MCP Steps

1. `initialize`
2. `notifications/initialized`
3. `tools/list`
4. `list_projects`
5. `device_list`
6. Build/deploy path (`compile + deploy` or `clean_reinstall`)
7. Runtime actions (`app_start` then `tap`)
8. Verification artifacts (`screenshot`, `layout_dump`, optional `record`)

## Pass Criteria

- Build/deploy path returns `status=OK`.
- Runtime actions (`app_start`, `tap`) return `status=OK`.
- At least one verification artifact exists on disk.

## MCP-only Policy

- Strongly prefer MCP toolchain end-to-end: `app_start`, `tap`, `layout_dump`, `screenshot`, `record`.
- Avoid direct external adb commands in normal closed-loop flow.
- If interaction is flaky, add short pre-tap delay and repeat tap (for example: 2 taps with 1-2s interval).

## Compile Failure Triage Policy

When compile/deploy fails, use this sequence:

1. Parse `structuredContent.message` and `structuredContent.data` from MCP response.
2. Try to classify the failure category:
   - `MCP_PROJECT_NOT_INITIALIZED`
   - `MCP_NO_DEVICE`
   - source compile errors (unresolved reference/syntax/import)
   - AndroidManifest/resource merge errors
   - deploy stage errors
3. If classification succeeds, provide concrete next action.
4. If classification fails and no approved auto-fallback exists, stop and ask user for confirmation.

## Auto Downgrade Policy

- Auto downgrade runs when fallback is explicitly enabled (for example `--fallback-clean-reinstall`) or user has explicitly approved automatic downgrade in prior conversation.
- Preferred downgrade path: try `force_gradle_compile` first (if available), then `clean_reinstall`.
- Avoid unbounded retry loops.

## Capability Requirements (for MCP/Tools)

1. **Error passthrough to MCP client**
   - Return rich failure details so agent can diagnose without guessing.
2. **Downgrade tool(s)**
   - Provide explicit fallback tooling for deterministic downgrade execution.

## Failure Handling

- `MCP_PROJECT_NOT_INITIALIZED`: ensure IDE project is opened and Jugg initialized.
- `MCP_NO_DEVICE`: connect device/emulator, re-check `device_list`.
- `MCP_INTERNAL_ERROR` during incremental path: rerun with `clean_reinstall` mode.

## Agent Response Template

Always include:

- used endpoint and projectDir
- selected mode (`compile_deploy` / `clean_reinstall`)
- step-by-step status summary
- artifact absolute paths
- clear next action on failure
