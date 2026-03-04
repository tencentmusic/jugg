# Tool Cards: Build & Deploy

Use this file when executing compile/deploy actions or handling compile/deploy failures.

## Default Path

1. Default tool: `compile_and_deploy(projectDir)`.
2. If user asks compile-only or no device is available: `compile_only(projectDir)`.
3. If incremental path is likely insufficient: `force_gradle_compile(projectDir)`.

## Async Compile Rule (Mandatory)

For `compile_and_deploy` or `force_gradle_compile`:

- If `isFinal=false`, immediately delegate polling to an `awaiter` sub-agent (must have MCP access; if unavailable, poll in main agent).
- Poll with `get_compile_status(jobId)` and follow `pollIntervalSuggestedMs` when present.
- Determine result only by terminal compile status.
- If `status=unknown`, treat as invalid job/context; stop and re-check `jobId` source.

## Fallback Chain

Use this order for compile/deploy failures:

1. Parse `status/message/errorCode/data/artifacts`.
2. Retry `compile_and_deploy` up to 3 times.
3. If still no way to fix the expected error use `force_gradle_compile` (heavy), finish async polling, and retry `compile_and_deploy`. On final failure inspect `${projectDir}/build/jugg/log/compile_latest.log`.
4. If still broken, try `clean_reinstall_apk`.
5. If still unclear, stop and confirm with user.
6. Remote troubleshooting (`request_remote_ssh_info`) requires explicit user consent.

Special case:

- `MCP_NO_DEVICE`: stop and ask user to connect/start device, or switch to `compile_only`.

## Build Tool Quick Cards

### `compile_and_deploy`
- Purpose: compile modified code and deploy to device.
- Required input: `projectDir`.
- Primary path for Android modify+verify loop.

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
