# Tool Cards: Compile & Deploy

Use this file when intent is compile/deploy path selection or fallback execution.

## `compile_and_deploy`

- Purpose: default compile + deploy path.
- Required input: `projectDir`.
- Success key:
  - `data.isFinal=true`: terminal result available.
  - `data.isFinal=false`: async job started, use `data.jobId`.
- Mandatory follow-up:
  - if `isFinal=false`, delegate polling to `awaiter` via `get_compile_status(jobId)`.
- Failure order:
  1. parse `message/errorCode/data.detail`
  2. retry `compile_and_deploy` up to 3 consecutive times
  3. then `force_gradle_compile`
  4. retry `compile_and_deploy`
  5. then `clean_reinstall_apk`
- Special case: `MCP_NO_DEVICE` -> stop and ask user, or switch to `compile_only`.

## `compile_only`

- Purpose: compile validation without deploy.
- Required input: `projectDir`.
- Use when: no device or compile-check-only task.
- Failure handling: same triage style as `compile_and_deploy`; skip runtime verification.

## `force_gradle_compile`

- Purpose: heavy fallback compile.
- Required input: `projectDir`.
- Use gate: only after 3 consecutive `compile_and_deploy` failures, unless user explicitly requests fallback compile.
- Mandatory follow-up:
  - if `isFinal=false`, delegate polling to `awaiter`.
- On final failed: inspect `${projectDir}/build/jugg/log/compile_latest.log` and stop auto-loop.

## `get_compile_status`

- Purpose: query async compile status.
- Required input: `projectDir`, `jobId`.
- Polling rule:
  - honor `pollIntervalSuggestedMs` when present.
  - terminal states: `success|failed|canceled`.
  - `unknown` means invalid job/context; stop polling and re-check `jobId` source.
- Noise rule: polling agent reports only state transitions + terminal result.

## `clean_reinstall_apk`

- Purpose: uninstall + reinstall to reset runtime/install state.
- Required input: `projectDir`.
- Use when: install conflict/signature mismatch/stale code_cache suspected.
- On failure: stop automatic retries and ask user with diagnosis summary.
