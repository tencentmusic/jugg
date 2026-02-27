# Tool Cards: Troubleshoot

Use this file only when normal execution cannot proceed due to context/device/runtime uncertainty.

## `list_projects`

- Use only for project-context errors (`MCP_PROJECT_NOT_INITIALIZED`, IDE drift suspicion).
- Not a start-of-task preflight.

## `device_list`

- Use only when device state is suspicious or `MCP_NO_DEVICE` appears.
- Not a mandatory preflight.

## `request_remote_ssh_info`

- Use only after local fallback paths are exhausted.
- Requires explicit user consent (`userConsent=true`).
- Required input: `projectDir`, `reason`, `userConsent`.
- Optional input: `requestedBy`.
