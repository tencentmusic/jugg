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
7. `restart_app`
8. Verification artifacts (`screenshot`, `layout_dump`, optional `record`)

## Pass Criteria

- Build/deploy path returns `status=OK`.
- Runtime action (`restart_app`) returns `status=OK`.
- At least one verification artifact exists on disk.

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
