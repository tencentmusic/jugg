# Tool Cards: Troubleshoot

Use this file only when normal execution cannot proceed due to context/device/runtime uncertainty.

Boundary rule:

- Default path is CLI-only (`<subcommand>`).
- Use adb fallback only when the corresponding CLI command is unavailable or returns empty/unusable results.
- When adb fallback is used, explicitly state "fallback path used: adb".

## `devices`

- Use only when device state is suspicious or `No device` / `MCP_NO_DEVICE` appears.
- Not a mandatory preflight.

## `ssh-info --reason <reason>`

- Use only after local fallback paths are exhausted.
- CLI call itself constitutes user consent; `userConsent=true` is passed automatically.
- Required: `--reason <reason>` describing the troubleshooting context.

## `crash-report`

- Use when runtime behavior is abnormal (unexpected activity, dead process, missing target UI evidence).
- Key output fields:
  - `hasCrash`
  - `crashLogs`
  - `reason` (when `hasCrash=false`)
  - `relatedActivity`
  - `allErrorLogPath`
  - `isProcessAlive`

## Crash Triage Loop

When runtime evidence is abnormal (unexpected activity, dead process, missing target UI):

1. Run `crash-report` to identify likely cause.
2. If `crash-report` is unavailable or returns empty/unusable result, fallback to `adb logcat` for current session.
3. If adb fallback is used, include explicit marker: `fallback path used: adb`.
4. Route by result:
   - `hasCrash=true`: locate crash source from `crashLogs` top stack frame, apply fix, return to 5-step loop step 1.
   - `hasCrash=false` + `isProcessAlive=false`: run `restart` and rerun Target Page Context Gate; if recurring, stop and ask user.
   - `hasCrash=false` + `isProcessAlive=true`: page state issue — refresh `layout-dump` and retry navigation within retry budget.
