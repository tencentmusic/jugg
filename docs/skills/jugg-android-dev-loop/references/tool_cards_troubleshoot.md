# Tool Cards: Troubleshoot

Use this file only when normal execution cannot proceed due to context/device/runtime uncertainty.

Boundary rule:

- Default path is MCP-only.
- Use adb fallback only when the corresponding MCP tool is unavailable or returns empty/unusable results.
- When adb fallback is used, explicitly state "fallback path used: adb".

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

## `crash_report`

- Use when runtime behavior is abnormal (unexpected activity, dead process, missing target UI evidence).
- Required input: `projectDir`.
- Key output fields:
  - `data.hasCrash`
  - `data.crashLogs`
  - `data.reason` (when `hasCrash=false`)
  - `data.relatedActivity`
  - `data.allErrorLogPath`
  - `data.isProcessAlive`

## Crash Triage Loop

When runtime evidence is abnormal (unexpected activity, dead process, missing target UI):

1. Call `crash_report(projectDir)` to identify likely cause.
2. If `crash_report` is unavailable or returns empty/unusable result, fallback to `adb logcat` for current session.
3. If adb fallback is used, include explicit marker: `fallback path used: adb`.
4. Route by result:
   - `hasCrash=true`: locate crash source from `crashLogs` top stack frame, apply fix, return to 5-step loop step 1.
   - `hasCrash=false` + `isProcessAlive=false`: restart app and rerun Target Page Context Gate; if recurring, stop and ask user.
   - `hasCrash=false` + `isProcessAlive=true`: page state issue — refresh `layout_dump` and retry navigation within retry budget.
