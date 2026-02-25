# Tool Cards (Atomic Usage Guide)

Use this file for per-tool decision guidance when calling Jugg MCP tools directly.

## `list_projects`

- Purpose: verify available projects before selecting `projectDir`.
- Required input: none (no arguments required).
- Success output: project list with `projectDir` and `initialized` fields.
- On failure: classify as `MCP_PROJECT_NOT_INITIALIZED`; ask user to open/init IDE project.

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
- Fallback order:
  1) retry `compile_and_deploy` up to 3 consecutive attempts,
  2) then `force_gradle_compile` (heavy fallback),
  3) retry `compile_and_deploy`,
  4) then `clean_reinstall_apk` when policy allows.
- Follow-up rule: if `isFinal=false`, poll `get_compile_status(jobId)` until terminal.

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
- Follow-up rule: if `isFinal=false`, poll `get_compile_status(jobId)` until `success|failed|canceled`.
- Troubleshooting: when final status is `failed`, read `${projectDir}/build/jugg/log/compile_latest.log`.

## `get_compile_status`

- Purpose: query async status for compile tools that returned `isFinal=false` (`force_gradle_compile` / `compile_and_deploy`).
- Required input: `projectDir`, `jobId`.
- Success output: `data.status` in `running|success|failed|canceled|unknown`, plus `executionType`.
- When to stop polling: `status` becomes `success|failed|canceled|unknown`.

## `start_activity`

- Purpose: advanced explicit activity start for runtime verification.
- Required input: `projectDir`.
- Optional input: `packageName`, `activity`.
- Safety note: use this only when you clearly know the required launch intent context/params; otherwise prefer `start_app`.
- Success output: `status="OK"` with `packageName`, `activity`, `component` in data.
- On failure: classify as `START_ACTIVITY_FAIL`; verify package/activity mapping.

## `tap`

- Purpose: deterministic UI trigger for post-launch flow.
- Required input: `projectDir`, `x`, `y`.
- Success output: `status="OK"`.
- On failure: retry within budget using delay/interval tuning.

## `start_record`

- Purpose: start device screen recording asynchronously.
- Required input: `projectDir`.
- Success output: `data.sessionId`, `data.file`, `data.serial`.
- On failure: one retry allowed; if still failing, degrade to `screenshot` + `layout_dump`.

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
- On failure: classify by `errorCode` (`MCP_NO_DEVICE`, `MCP_INTERNAL_ERROR`); fallback to `start_app` + `layout_dump` for coarse context.

## Final Artifacts

- Purpose: prevent user confusion from stale outputs.
- Required action: clear `${projectDir}/build/mcp_fetch/final` before any copy.
- Required files:
  - final screenshot -> `final_screenshot.png`
  - final recording -> `final_record.mp4`
- Optional: also copy source timestamped files for traceability.

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
- Minimal flow: `start_record` -> runtime operations (`start_app`/`tap`) -> `stop_record` -> post-record screenshot.
