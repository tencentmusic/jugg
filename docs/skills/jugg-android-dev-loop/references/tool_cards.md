# Tool Cards (Atomic Usage Guide)

Use this file for per-tool decision guidance when calling Jugg MCP tools directly.

Default context rule:

- Use current working directory as `projectDir` by default.
- Treat `list_projects`/`device_list` as troubleshooting tools, not mandatory preflight in normal flow.

## `list_projects`

- Purpose: verify available projects before selecting `projectDir`.
- When to use: troubleshooting only (project initialization/context mismatch), not mandatory at task start.
- Required input: none (no arguments required).
- Success output: project list with `projectDir` and `initialized` fields.
- Important semantic: this tool normally returns `status="OK"`; treat `data.projects=[]` as "no initialized project available", then ask user to open/init IDE project.

## `restart_app`

- Purpose: restart the app process without reinstall.
- Required input: `projectDir`.
- Success output: `status="OK"`.
- On failure: classify by `errorCode` (`MCP_NO_DEVICE`, `MCP_INTERNAL_ERROR`).

## `device_list`

- Purpose: check available target devices before deploy.
- Required input: `projectDir`.
- Success output: non-empty device list with `serial`, `name`, `isOnline`, `api`, `isSelected`.
- On failure: stop and ask user to connect/start device manually.

## `compile_only`

- Purpose: compile-only validation — checks that source code compiles without deploying to device.
- Required input: `projectDir`.
- When to use: no device connected, or only need to verify compilation passes.
- Success output: `status="OK"`, optional build artifact info.
- On failure: parse `message` and `data` for root cause; classify (`SOURCE_ERROR`, `RESOURCE_MERGE`, `MANIFEST_MERGE`, `MCP_INTERNAL_ERROR`).
- Fallback: retry `compile_only` up to 3 times first; only then use `force_gradle_compile` -> retry `compile_only`.

## `compile_and_deploy`

- Purpose: compile source files then deploy changed artifacts to device. This is the default path for normal iteration.
- Required input: `projectDir`.
- When to use: normal development — code changes must take effect on device.
- Success output:
  - Immediate final: `data.isFinal=true` with `data.status=success|failed|canceled`（通常带 `runResult`）
  - Long task: `data.isFinal=false`, `data.status=running`, and `data.jobId`
- On failure: classify (`SOURCE_ERROR`, `INSTALL_CONFLICT`, `SIGNATURE_MISMATCH`, `MCP_NO_DEVICE`, `MCP_INTERNAL_ERROR`).
- Diagnostics priority:
  1) parse `message` + `data.detail`,
  2) if `data.detailTruncated=true`, read `artifacts` log file,
  3) then read `${projectDir}/build/jugg/log/compile_latest.log` when needed.
- Fallback order:
  1) retry `compile_and_deploy` up to 3 consecutive attempts,
  2) then `force_gradle_compile` (heavy fallback),
  3) retry `compile_and_deploy`,
  4) then `clean_reinstall_apk` when policy allows.
- Follow-up rule: if `isFinal=false`, immediately delegate polling to an `awaiter` sub-agent; poll `get_compile_status(jobId)` using `pollIntervalSuggestedMs` until terminal.

## `clean_reinstall_apk`

- Purpose: uninstall app then perform full Gradle build and reinstall APK. Clears app data including Jugg incremental patches stored in code_cache.
- Required input: `projectDir`.
- Success output: build+install success.
- On failure: stop automatic retries and ask user with diagnosis summary.
- When to use: incremental deploy state is corrupted, signature mismatch, or Jugg patches in code_cache are stale.

## `force_gradle_compile`

- Purpose: Gradle fallback compile when Jugg incremental build repeatedly fails.
- Cost note: very heavy operation; can be 100x+ slower than `compile_and_deploy`.
- Required input: `projectDir`.
- Success output:
  - Immediate final: `data.isFinal=true`, `data.status=success|failed|canceled`
  - Long task: `data.isFinal=false`, `data.status=running`, and `data.jobId`
- On failure: stop and report; do not retry `force_gradle_compile` itself.
- Usage rule: only invoke after 3 consecutive `compile_and_deploy` failures.
- Follow-up rule: if `isFinal=false`, immediately delegate polling to an `awaiter` sub-agent; poll `get_compile_status(jobId)` using `pollIntervalSuggestedMs` until `success|failed|canceled`.
- Troubleshooting: when final status is `failed`, read `${projectDir}/build/jugg/log/compile_latest.log`.

## `get_compile_status`

- Purpose: query async status for compile tools that returned `isFinal=false` (`force_gradle_compile` / `compile_and_deploy`).
- Required input: `projectDir`, `jobId`.
- Success output: `data.status` in `running|success|failed|canceled|unknown`, plus `executionType`.
- Running state may include `pollIntervalSuggestedMs`; honor this value when polling.
- When to stop polling: `status` becomes `success|failed|canceled|unknown`.
- Special rule: if `status=unknown` (usually paired with `MCP_INVALID_PARAMS`), treat as invalid `jobId`/context, stop polling, then re-check `jobId` source and re-trigger compile if needed.
- Delegation rule: `get_compile_status` polling should run in an `awaiter` sub-agent whenever entered from `isFinal=false`.
- Reporting rule: polling sub-agent reports only state transitions and terminal status back to main agent.

## `tap`

- Purpose: deterministic UI trigger for post-launch flow.
- Required input: `projectDir`, `x`, `y`.
- Success output: `status="OK"`.
- On failure: retry within budget using delay/interval tuning.

## `start_record`

- Purpose: start device screen recording asynchronously.
- Required input: `projectDir`.
- Success output: `data.sessionId`, `data.file`, `data.serial`.
- On failure:
  - if `errorCode=MCP_INVALID_PARAMS` and response `data` contains existing `sessionId`, call `stop_record(existingSessionId)` first, then retry `start_record`.
  - otherwise one retry allowed; if still failing, degrade to `screenshot` + `layout_dump`.

## `stop_record`

- Purpose: stop a running recording session and fetch mp4 artifact.
- Required input: `projectDir`, `sessionId`.
- Success output: video artifact path in `artifacts`.
- On failure: keep `sessionId` and retry once; if still failing, report and continue with screenshot evidence.

## `screenshot`

- Purpose: visual proof for final validation.
- Required input: `projectDir`.
- Success output: absolute screenshot path in `artifacts`.
- On failure: collect at least `layout_dump`; if both fail => validation fail.
- Final staging rule: when task is accepted, clear `${projectDir}/build/mcp_fetch/final` and copy final screenshot as `final_screenshot.png`.

## `layout_dump`

- Purpose: UI hierarchy evidence and structural verification.
- Required input: `projectDir`.
- Success output: absolute dump path in `artifacts`.
- On failure: require `screenshot` success; if none exists => validation fail.

## `activity_stack`

- Purpose: capture current runtime Activity stack and top activity for page-awareness decisions.
- Required input: `projectDir`.
- Success output: `data.topActivity`, `data.activities` (component string array from top to bottom), and a full raw dump text path in `artifacts`.
- On failure: classify by `errorCode` (`MCP_NO_DEVICE`, `MCP_INTERNAL_ERROR`); fallback to `restart_app` + `layout_dump` for coarse context.

## Final Artifacts

- Purpose: prevent user confusion from stale outputs.
- Required action: clear `${projectDir}/build/mcp_fetch/final` before any copy.
- Clarification: `${projectDir}/build/mcp_fetch/final` is agent staging only; tool default outputs usually live under `${projectDir}/build/jugg/mcp_fetch/<toolName>`.
- Required files:
  - final screenshot -> `final_screenshot.png`
  - final recording -> `final_record.mp4`
- Optional: also copy source timestamped files for traceability.

## `request_remote_ssh_info`

- Purpose: request remote SSH login info for deep troubleshooting after local fallback paths are exhausted.
- Required input: `projectDir`, `reason`, `userConsent`.
- Optional input: `requestedBy`.
- Safety gate: must have explicit user consent (`userConsent=true`) before call.
- Success output: `data.user`, `data.ip`, `data.port`, `data.password`, `data.sshLoginCommand`.
- On failure: if consent missing/invalid params -> ask user to confirm consent and reason; otherwise report internal approval/runtime error.

## Output Contract (Per MCP Tool Response)

Each MCP tool returns `structuredContent` with these fields:

- `status`: `"OK"` | `"ERROR"`
- `message`: concise human-readable diagnosis
- `data`: structured payload for next decision (tool-specific)
- `artifacts`: array of `{type, path}` objects (absolute paths)
- `errorCode`: stable error code string or `null` on success

## `layout_dump` Practical Notes

- If XML is a single line, use `rg "<text>|resource-id|bounds" <dump.xml>` to locate target node quickly.
- Prefer locating target with `resource-id` first, then `text`, then `bounds` center for `tap`.
- After any XML edit, do not trust patch success alone; require `layout_dump` node presence before recording.

## `record` Compact Guidance

- Default: skip recording; use `screenshot + layout_dump` for static end-state checks.
- Prefer `start_record`/`stop_record` for time-based behavior: animation, navigation, async changes, transient UI, multi-step flows.
- Must produce recording when user explicitly asks for video evidence.
- Heuristic: prove **how** -> record; prove **what** -> optional.
- Minimal flow: `start_record` -> runtime operations (`restart_app`/`tap`) -> `stop_record` -> post-record screenshot.
