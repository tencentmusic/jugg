# Tool Cards: Build & Deploy

Use this file when executing compile/deploy actions or handling compile/deploy failures.

## Default Path

1. Default tool: `compile_and_deploy(projectDir)`.
2. If user asks compile-only or no device is available: `compile_only(projectDir)`.
3. If incremental path is likely insufficient: `force_gradle_compile(projectDir)`.

## Async Compile Rule (Mandatory)

For `compile_and_deploy`, `compile_only`, or `force_gradle_compile`:

- If `isFinal=false`, prefer delegating polling to an `awaiter` sub-agent when runtime supports MCP-capable sub-agents; otherwise poll in main agent.
- Poll with `get_compile_status(jobId)` and follow `pollIntervalSuggestedMs` when present.
- Determine result only by terminal compile status.
- If `status=unknown`, treat as invalid job/context; stop and re-check `jobId` source.

## Fallback Chain

Use this order for compile/deploy failures:

1. Parse `status/message/errorCode/data/artifacts`.
2. Retry `compile_and_deploy` up to 3 times.
3. If still failing, use `force_gradle_compile` (heavy).
   - `force_gradle_compile` produces the compiled artifact only (no deploy). After async polling completes, use `compile_and_deploy` to push the artifact to device (compile phase will be skipped because output is already up-to-date).
   - On final failure, inspect `${projectDir}/build/jugg/log/compile_latest.log`.
4. Retry `compile_and_deploy` once after `force_gradle_compile`.
5. If still broken, inspect `${projectDir}/build/jugg/log/compile_latest.log`.
6. Only when install-state corruption or signature conflict is likely, run `clean_reinstall_apk` as a post-step.
7. If still unclear, stop and confirm with user.
8. Remote troubleshooting (`request_remote_ssh_info`) requires explicit user consent.

Important:

- Do not place `clean_reinstall_apk` before `force_gradle_compile`.
- `clean_reinstall_apk` is conditional recovery, not a general retry.

Special case:

- `MCP_NO_DEVICE`: stop and ask user to connect/start device, or switch to `compile_only`.

## Build Tool Quick Cards

### `compile_and_deploy`
- Purpose: compile modified code and deploy to device.
- Required input: `projectDir`.
- Primary path for Android modify+verify loop.
- Post-deploy: page state may change (app restart or activity recreation). Always rerun Target Page Context Gate after deploy succeeds before continuing verification. If gate confirms same page, continue verification. If page changed, re-execute navigation sequence to return to target page.

### `compile_only`
- Purpose: compile modified sources without deployment.
- Required input: `projectDir`.
- Use for no-device scenarios or compile-only requests.

### `force_gradle_compile`
- Purpose: force full Gradle compile fallback.
- Required input: `projectDir`.
- Use when incremental compile limitations are hit.

### `get_compile_status`
- Purpose: query async compile/deploy job status.
- Required input: `projectDir`, `jobId`.

### `clean_reinstall_apk`
- Purpose: clear app data and reinstall APK.
- Required input: `projectDir`.
- Use after deploy/install state corruption or signature mismatch issues.
