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
- Optional input: `serial`.
- Success output: `status="OK"`.
- On failure: classify by `errorCode` (`MCP_NO_DEVICE`, `MCP_INTERNAL_ERROR`).

## `device_list`

- Purpose: check available target devices before deploy.
- Required input: `projectDir`.
- Success output: non-empty device list with `serial`, `name`, `isOnline`, `api`, `isSelected`.
- On failure: `MCP_NO_DEVICE`; try start-first-local-emulator once, then rerun `device_list`. If still empty, stop and ask user to connect emulator/phone.

## `compile_only`

- Purpose: compile-only validation — checks that source code compiles without deploying to device.
- Required input: `projectDir`.
- When to use: no device connected, or only need to verify compilation passes.
- Success output: `status="OK"`, optional build artifact info.
- On failure: parse `message` and `data` for root cause; classify (`SOURCE_ERROR`, `RESOURCE_MERGE`, `MANIFEST_MERGE`, `MCP_INTERNAL_ERROR`).
- Fallback: `force_gradle_compile` -> retry `compile_only`.

## `compile_and_deploy`

- Purpose: compile source files then deploy changed artifacts to device. This is the default path for normal iteration.
- Required input: `projectDir`.
- When to use: normal development — code changes must take effect on device.
- Success output: `status="OK"`.
- On failure: classify (`SOURCE_ERROR`, `INSTALL_CONFLICT`, `SIGNATURE_MISMATCH`, `MCP_NO_DEVICE`, `MCP_INTERNAL_ERROR`).
- Fallback: `clean_reinstall_apk` when policy allows.

## `clean_reinstall_apk`

- Purpose: uninstall app then perform full Gradle build and reinstall APK. Clears app data including Jugg incremental patches stored in code_cache.
- Required input: `projectDir`.
- Success output: build+install success.
- On failure: stop automatic retries and ask user with diagnosis summary.
- When to use: incremental deploy state is corrupted, signature mismatch, or Jugg patches in code_cache are stale.

## `force_gradle_compile`

- Purpose: Gradle fallback compile when Jugg incremental build repeatedly fails.
- Required input: `projectDir`.
- Success output: `status="OK"`, `data.triggered=true`.
- On failure: stop and report; do not retry `force_gradle_compile` itself.

## `app_start`

- Purpose: start target activity for runtime verification.
- Required input: `projectDir`.
- Optional input: `serial`, `packageName`, `activity`.
- Success output: `status="OK"` with `packageName`, `activity`, `component` in data.
- On failure: classify as `APP_START_FAIL`; verify package/activity mapping.

## `tap`

- Purpose: deterministic UI trigger for post-launch flow.
- Required input: `projectDir`, `x`, `y`.
- Optional input: `serial`.
- Success output: `status="OK"`.
- On failure: retry within budget using delay/interval tuning.

## `record`

- Purpose: record device screen video, optionally with in-record app_start + tap actions.
- Required input: `projectDir`.
- Optional input: `serial`, `durationSec` (default 10, range 1-180), `packageName`, `activity`, `tapX`+`tapY` (must be provided together), `preTapDelaySec`, `tapRepeat` (default 1), `tapIntervalSec`, `recordStartDelaySec`.
- Success output: recording artifact path in `artifacts`.
- On failure: one retry allowed, then degrade to `app_start` + `tap` + `screenshot`.

## `screenshot`

- Purpose: visual proof for final validation.
- Required input: `projectDir`.
- Optional input: `serial`.
- Success output: absolute screenshot path in `artifacts`.
- On failure: collect at least `layout_dump`; if both fail => validation fail.
- Final staging rule: when task is accepted, clear `${projectDir}/build/mcp_fetch/final` and copy final screenshot as `final_screenshot.png`.

## `layout_dump`

- Purpose: UI hierarchy evidence and structural verification.
- Required input: `projectDir`.
- Optional input: `serial`.
- Success output: absolute dump path in `artifacts`.
- On failure: require `screenshot` success; if none exists => validation fail.

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
