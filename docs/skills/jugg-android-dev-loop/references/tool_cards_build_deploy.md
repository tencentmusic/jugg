# Tool Cards: Build & Deploy

Use this file when executing compile/deploy actions or handling compile/deploy failures.

## Default Path

1. Default command: `jugg deploy` (compile + deploy, waits for completion).
2. If user asks compile-only or no device is available: `jugg compile`.
3. If incremental path is likely insufficient: `jugg gradle-build`.

## Async Compile Rule (Mandatory)

The CLI handles polling internally — `jugg deploy` and `jugg gradle-build` block until completion and print progress to stderr. No manual `get_compile_status` polling needed.

- Determine result by CLI exit code (0 = success, non-zero = failure) and `status: OK/ERROR` in output.
- If `status: ERROR` is printed, treat as failure and follow Fallback Chain below.

## Fallback Chain

Use this order for compile/deploy failures:

1. Parse `status`/`message` from CLI output.
2. Retry `jugg deploy` up to 3 times.
3. If still failing, use `jugg gradle-build` (heavy).
   - `jugg gradle-build` produces the compiled artifact only (no deploy). After it completes, run `jugg deploy` to push the artifact to device (compile phase will be skipped because output is already up-to-date).
   - On final failure, inspect `${projectDir}/build/jugg/log/compile_latest.log`.
4. Retry `jugg deploy` once after `jugg gradle-build`.
5. If still broken, inspect `${projectDir}/build/jugg/log/compile_latest.log`.
6. Only when install-state corruption or signature conflict is likely, run `jugg reinstall` as a post-step.
7. If still unclear, stop and confirm with user.
8. Remote troubleshooting (`jugg ssh-info --reason <reason>`) requires explicit user consent.

Important:

- Do not place `jugg reinstall` before `jugg gradle-build`.
- `jugg reinstall` is conditional recovery, not a general retry.

Special case:

- `MCP_NO_DEVICE` / `No device`: stop and ask user to connect/start device, or switch to `jugg compile`.

## Build Command Quick Cards

### `jugg deploy`
- Purpose: compile modified code and deploy to device.
- No arguments required; `projectDir` auto-resolved from `$PWD`.
- Primary path for Android modify+verify loop.
- Post-deploy: page state may change (app restart or activity recreation). Always rerun Target Page Context Gate after deploy succeeds before continuing verification.

### `jugg compile`
- Purpose: compile modified sources without deployment.
- No arguments required; `projectDir` auto-resolved from `$PWD`.
- Use for no-device scenarios or compile-only requests.

### `jugg gradle-build`
- Purpose: force full Gradle compile fallback.
- No arguments required; `projectDir` auto-resolved from `$PWD`.
- Use when incremental compile limitations are hit.

### `jugg reinstall`
- Purpose: clear app data and reinstall APK.
- No arguments required; `projectDir` auto-resolved from `$PWD`.
- Use after deploy/install state corruption or signature mismatch issues.
