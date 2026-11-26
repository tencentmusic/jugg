# L2 SSH, Connectivity, and Devices

Goal: Check whether the agent probes the basic CLI from the correct Android `projectDir` and distinguishes no MCP, no device, and SSH authorization outcomes.

## CONNECT-1: Confirm CLI and Plugin Versions

Prompt: Confirm the current Jugg CLI and IDE plugin versions, and record structured evidence.

Expected:
- Run inside `android_demo_project` or one of its subdirectories.
- Select `version`.
- For JSON evidence, place the global `--console=json` before `version`.
- Report the CLI and plugin versions; record `SKIP: no MCP port` if no MCP endpoint is available.

## CONNECT-2: Inspect Current Status

Prompt: Inspect Jugg status to determine whether a device is available, fallback is required, and the latest full-build baseline enabled androidTest.

Expected:
- Select `status`.
- Record visible fields such as `hasDevice`, `needFallback`, `enabledAndroidTest`, and `stateMessage`.
- Do not mistake a status failure for a device failure; first distinguish whether an MCP endpoint is available.

## CONNECT-3: List Devices

Prompt: List available devices and say whether one is selected.

Expected:
- Select `devices`.
- When devices exist, record the list and selected marker.
- An empty list is still a normal `devices` result; do not fail solely because there is no device.

## CONNECT-4: Starting in the Wrong Directory

Prompt: Start at the repository root and run a command requiring `projectDir`; check whether the agent recognizes that it should move to the Android project.

Expected:
- Do not treat the repository root as the Android `projectDir`.
- Change to `android_demo_project` before running `status` or `devices`.
- If the agent insists on running at the repository root and calls the result a CLI bug, award no more than 2.

## SSH-1: Request SSH Information

Prompt: Request remote troubleshooting SSH information for a benchmark connectivity check.

Expected:
- Select `ssh-info`.
- Supply `--reason`; do not omit the reason.
- User consent, refusal, or remote compilation being disabled are all valid outcomes; record the actual output and authorization result.

## SSH-2: Missing SSH Reason

Prompt: Verify that the agent does not treat an `ssh-info` request without a reason as valid.

Expected:
- State that `ssh-info` requires `--reason`.
- If actually running the command without it, classify a nonzero exit or error output as the expected failure.
