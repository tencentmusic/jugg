# MCP Tool Argument List

> Last checked: 2026-09-10
> Consistency rule: when documentation conflicts with code, code is authoritative.

---

## MCP service information

- Port range: `12320..12329`
- Path: `/jugg-mcp`
- Protocol: JSON-RPC `2.0`
- Supported request header: `MCP-Protocol-Version` (`2025-06-18`, `2025-11-25`)

---

## MCP response convention

Uniform fields in `tools/call` `structuredContent`:

```json
{
  "status": "OK|ERROR",
  "message": "string",
  "data": {},
  "artifacts": [],
  "errorCode": "string|null"
}
```

---

## Registered MCP tools (per `McpToolActionRegistry`)

There are **20** registered tools, listed in registration order.

The following device-related tools expose optional `serial: string`: `restart`, `deploy`, `clean-reinstall`, `gradle-build`, `instrument`, `devices`, `layout-dump`, `view-locate`, `view-inspect`, `activity-stack`, `tap`, `status`, `wait-logs`, and `report-prepare`. Except for `report-prepare`, explicit serial exactly and case-sensitively matches an online device, overrides IDEA's selected device and standalone `ANDROID_SERIAL`, and affects only this request; a miss must not fall back to another device. With serial, `devices` returns only that online device, or `NO_DEVICE` on a miss. `report-prepare` accepts but ignores serial for compatibility.

Without serial, `compile`, `status`, and `devices` do not require a unique device; `deploy`, `clean-reinstall`, and `instrument` process all target devices; `restart` restarts them all. Single-device tools such as `layout-dump`, `view-locate`, `view-inspect`, `activity-stack`, `tap`, and `wait-logs` return `MULTIPLE_DEVICE` for multiple targets rather than throwing HTTP 500. `report-prepare` always collects error logcat from all target devices on a best-effort basis.

### `version`

Returns current Jugg Runtime version, type, and capabilities while retaining the plugin-version compatibility field.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| (none) | — | — | — |

**Response data**:
- `pluginVersion`: highest plugin version among all projects, or their shared version.
- `projects` (optional): `projectDir -> version` map when project versions differ.
- `runtimeType`: `idea` / `standalone` / `ci` / `unknown`.
- `runtimeVersion`: actual Runtime version of this process.
- `capabilities`: MCP capability names declared available by this process's `McpToolRegistry`, consistent with `tools/list` and action dispatch. Standalone Step 11 includes `version`, `list-projects`, `compile`, `deploy`, `gradle-build`, `get-compile-status`, `status`, `restart`, `report-prepare`, `report-upload`, and `devices`.

---

### `list-projects`

Lists initialized projects in the current IDEA or standalone Runtime process. This global tool does not auto-register a standalone project; an unknown project registers only when the first valid project-level request arrives.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| (none) | — | — | — |

**Response data**:
- `projects`: array of projects, each containing:
  - `projectDir`: absolute project path.
  - `initialized`: whether Jugg initialization finished (always `true` for listed projects).
  - `hasBeenFullCompiled`: whether a complete Jugg full-build baseline exists, aligned with `DeployHistoryManager.hasBeenFullCompiled` semantics.

---

### `restart`

Restarts the target app.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path (pattern: `^/.+`). |
| `serial` | string | no | adb serial for this request. |
| `waitAppReadyAfterSuccess` | boolean | no | Wait for app readiness after a successful restart when `true`; default `false`, with no post-success readiness wait. |

**Additional behavior**: without serial, restart all target devices; with explicit serial, only the specified online device. By default, success confirms only that the restart command completed. Pass `waitAppReadyAfterSuccess=true` when app readiness must be part of tool success.

The launch target falls back from launch Activity to HOME Activity. If no APK has either, the tool only runs `am force-stop <package>` and prints a warning; it does not start another Activity. A successful stop command still counts as tool success, but the app cannot become ready, so explicitly passing `waitAppReadyAfterSuccess=true` returns failure. See `03_deploy_core.md` §4.4.

---

### `compile`

Compiles without deploying.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |

**No pending files**: when compilation succeeds with no files to compile, the success message explicitly says `No pending file changes`. No new compile artifact is produced, no deployment occurs, and no deployment history is attached. Initial-call completion and `get-compile-status` polling use the same final message.

**Device boundary**: compile does not deploy, but refreshes unified deployment state to choose incremental compilation or Gradle fallback. Device selection safely handles multiple devices, so multiple online devices alone do not cause failure. Build-file changes requiring a rebuild, a failed previous Gradle build, or another state requiring a full build still trigger automatic Gradle fallback.

---

### `deploy`

Compiles and deploys, possibly asynchronously.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request. |
| `alwaysRestartApp` | boolean | no | `true` (default) forces app restart after deployment (HOT_FIX behavior); `false` restarts only for class-structure changes (permits HOT RELOAD). |
| `waitAppReadyAfterSuccess` | boolean | no | Wait for app readiness after successful deployment when `true`; default `false`. |

**Asynchronous response**: `isFinal=false` returns a `jobId` to poll through `get-compile-status`.

**Device boundary**: without serial, process all target devices; with explicit serial, only the specified online device.

**No pending files**: the success message explicitly says every change currently detected by Jugg has been deployed. It includes the latest successful deployment with file changes in this IDE session: absolute time, relative time, and project-relative paths. At most 20 files are shown, followed by a remainder count; if an IDE restart erased the session record, the message explicitly says details are unavailable. Initial-call completion and `get-compile-status` polling use the same final message.

---

### `clean-reinstall`

Uninstalls and reinstalls APKs (clears data and redeploys).

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request. |
| `waitAppReadyAfterSuccess` | boolean | no | Wait for app readiness after a successful reinstall when `true`; default `false`. |

---

### `gradle-build`

Forces a Gradle build, possibly asynchronously.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request; standalone deploys to it after a successful build. |
| `waitAppReadyAfterSuccess` | boolean | no | Wait for app readiness after successful build/install when `true`; default `false`. |

**Asynchronous response**: same as `deploy`.

**Additional behavior**: IDEA and standalone `gradle-build` both continue into installation/start after Gradle, with actual `isCompileSuccess` and `isDeploySuccess` values in the terminal state. Without serial, standalone deploys to every online device and returns failed if no device is online or deployment fails. With a remote configuration, Gradle full builds/fallback reuse IDEA's SSH/iFT remote client; local project-info dry runs, incremental compilation, and device operations still run on the standalone host. Standalone has no interactive authentication UI: missing SSH credentials or iFT authentication produce a failed state with explicit instructions.

**Failure details**: failed terminal data includes `detail` / `detailLength` / `detailTruncated` when available, summarized from this Gradle build plus install/start logs. Asynchronous callers receive the same details through `get-compile-status`. Long-log preview is capped at 8 KB, taking 4 KB from the beginning and 4 KB from the end so the root cause is not displaced by the stack/footer.

### `instrument`

Runs class/method-level tests anchored to an androidTest source file, internally reusing Jugg compile/deploy.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request. |
| `sourcePath` | string | **yes** | androidTest source-file path used to resolve module and Test APK. |
| `class` | string | no | Test class within the file; optional for a single-class file. |
| `method` | string | no | Test method; requires a uniquely identified class. |
| `runner` | string | no | Instrumentation runner override. |
| `extras` | object | no | Additional `-e key value` arguments; values must be strings. |

**Additional behavior**:
- `package` / `testsRegex` are no longer target entry points; multiple Test APKs require `sourcePath` to select a target.
- The MCP layer first normalizes arguments, resolves `sourcePath`, and prechecks the AndroidTest full-build baseline; the androidTest source resolver completes target resolution.
- Internally, this uses `BuildTarget.ANDROID_TEST` and maps arguments to `AndroidTestRunSpec`.
- Without an AndroidTest full-build baseline, it returns `status=ERROR`, `errorCode=INVALID_PARAMS`, and a `message` containing `enabledAndroidTest=false`. The instructions tell the user to open the Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run one full build / `gradle-build`, and recheck `status.data.enabledAndroidTest=true`.

---

### `get-compile-status`

Queries asynchronous compile-job state.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `jobId` | string | **yes** | Job ID returned by the asynchronous compile tool. |
| `waitTimeoutMs` | integer | no | Blocking wait for state change in milliseconds, `[0, 10000]`, default `0` (nonblocking). |

**Response data**: `jobId`, `status` (running/success/failed/canceled/unknown), `executionType` (local/remote), and `message`. While running, includes `pollIntervalSuggestedMs` and, when current IDE progress text is nonempty, `indicator.text` for a lightweight CLI/Agent heartbeat. Terminal state includes `isCompileSuccess` (boolean compile success, absent for unknown) and `isDeploySuccess` (boolean deployment success, absent for unknown; normally `false` for compile-only). For `failed` / `canceled` with diagnostic output, includes `detail` / `detailLength` / `detailTruncated`; successful terminal state omits `detail`.

**Behavior**:
- With `waitTimeoutMs > 0` while running, the server blocks until the state changes, the job terminates, or the timeout elapses.
- This reduces the window where a job has finished but the client's next poll has not yet arrived.

---

### `ssh-info`

Requests remote SSH troubleshooting information (requires explicit user consent).

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `reason` | string | **yes** | Why SSH information is needed. |
| `requestedBy` | string | no | Requester identity, default `mcp_agent`. |

### `report-prepare`

Creates the final redacted diagnostic ZIP for review without making a network request. Both IDEA and standalone register this tool.

Device error logs are collected on a best-effort basis. The tool ignores any caller-provided serial and collects logs from all target devices. If reading logcat from one device fails, only its entry is omitted; other diagnostics and device logs are still produced.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |

**Response data**: `reportId`, `filePath`, `size`, `sha256`, fixed `uploadUrl`, and `entries`. Each entry has `path`, `size`, `sensitivity`, and `redaction`, exactly matching the final ZIP manifest.

### `report-upload`

Uploads a diagnostic ZIP the user has reviewed and confirmed. The server rereads the bundle from the project's diagnostics directory and verifies report ID, directory boundary, manifest, ZIP entries, and SHA-256. Any content change fails before a network request.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `reportId` | string | **yes** | Eight-digit hexadecimal ID from `report-prepare`. |
| `sha256` | string | **yes** | ZIP SHA-256 from `report-prepare`. |

**Success response**: message matches IDE, `Report uploaded. Jugg Report ID: <reportId>`; data retains only `reportId`, without diagnostic entries, temporary local paths, or a file artifact.

---

### `devices`

Lists connected devices and marks the selected one. Both IDEA and standalone register this tool. Standalone reads online devices from project-level `IDeployTargetManager`; without serial it does not fail on multiple devices. If reading the Host's current selection fails, the tool still returns online devices on a best-effort basis, marked unselected, rather than letting stale `ANDROID_SERIAL` break the list.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | Return only this online device; `NO_DEVICE` if unmatched. |

---

### `layout-dump`

Exports the UI hierarchy. Public output is HTML (`data.file`); structured JSON remains only an internal implementation detail of `LayoutDumpHelper.dumpInternal()` and is not exposed to Agents.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request. |
| `rootLayout` | string | no | Node ID; return only that subtree (prefer a short ID such as `"content"`); searches across windows automatically. |
| `includeGone` | boolean | no | Include GONE nodes when `true` (default `false`). |
| `allWindows` | boolean | no | Export every window when `true` (default `false`, top window only). |

**Behavior**:
- Uses in-app `ViewHierarchyServer` (LocalSocket); **does not fall back to uiautomator**.
- Dragonfly provides app-side node data. Conventional Android View and Compose nodes adapt into the existing `windows/root/children` JSON; the public MCP/HTML format is unchanged. If Dragonfly cannot enumerate windows, the window-root list falls back on a best-effort basis to the old `ActivityThread` / `WindowManagerGlobal` reflection path; Dragonfly still extracts root nodes.
- HTML trims structural virtual nodes with no semantic content.
- Jugg snapshot pruning uses `MAX_DEPTH=60` and `MAX_NODE_COUNT=5000`. These limits apply after normalizing Dragonfly output; dump, selector, tap, inspect, and verify can access only that range, with `truncated:true` on overflow. If raw Dragonfly extraction fails first, `truncated:true` cannot be returned.
- All `bounds`/`padding` are in dp (`dp = (int)(px / density)`).
- Virtual IDs use `_vir_id_<hash>`. They are stable across requests while Dragonfly window/child traversal and UI structure remain unchanged, and may be reused in later selectors; they are not guaranteed to identify the same business node after list reordering or page restructuring.
- `className` retains only the simple class name; `id` drops the package prefix.
- `KuiklyViewResolver` reflectively extracts text from Kuikly framework controls such as `KRRichTextView`.
- Dragonfly bundles private Kotlin/coroutine runtimes, so pure Java projects no longer return `FEATURE_NOT_SUPPORTED` for missing host Kotlin. Dragonfly locally handles incompatible Compose runtime/tooling rather than switching to the old node source.
- If the socket cannot connect: try `restart` once, then `gradle-build` → `deploy` → `restart` → retry if still failing.

---

### `view-locate`

Finds UI elements in an in-app live Dragonfly snapshot. Multiple nonempty selectors use AND, and a candidate budget limits the returned count.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request; internal layout dump uses the same device. |
| `target` | object | **yes** | Element selector: `text`/`resourceId`/`contentDesc`. |
| `figmaNode` | object | no | Reserved; current public implementation still uses exact `target` text/resourceId/contentDesc matching. |

**Response data**: `matchCount` is all matches, `returnedCount` is returned matches, `truncated` indicates budget truncation, and `matches[]` contains candidate summaries. On exactly one match, top-level `bounds` (`[l,t,r,b]`), `position` (`{x,y}`), `size` (`{width,height}`), `className`, and `resourceId` are also returned. Multiple matches do not silently select the first node. All coordinates are dp. When available from the runtime, candidates and a unique top-level match include `source: {file?, line?}`.

---

### `view-inspect`

Reads raw, read-only node properties in a live Dragonfly snapshot through reflection. Android nodes are queried through the original View; Compose nodes through the Dragonfly node object.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request. |
| `target` | object | **yes** | Element selector: `resourceId`/`text`/`contentDesc`/`className` (AND). |
| `expressions` | array\<string\> | **yes** | 1–20 getter expressions, such as `getText()`, `getCurrentTextColor()`, and `getMaxLines()`. |

**Behavior**:
- Explicit `foo()` is limited to getter/query allowlist (`get*`/`is*`/`has*`/`can*`/`should*` plus `toString`/`length`, etc.).
- A bare identifier reads a public field first, then resolves Kotlin/Java getters. If it already starts with `get*`/`is*`, call it directly; otherwise try `getXxx()` / `isXxx()`.
- Returns `data.values[]` with `expression`/`value`/`type`/`error` per item.
- Returns device pixel density as `data.density` for px-to-dp conversion.
- Returns `data.source: {file?, line?}` when available to connect verification evidence to source location.
- Properties of hidden nodes still in the View tree can be read; hidden nodes should not be click targets.
- A Compose node supports only getters its runtime object actually exposes; Android-View-specific getters produce an error for that expression.
- Use `view-locate` for coordinates and `view-inspect` for properties.

---

### `activity-stack`

Reads the Activity stack.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request. |

**Response data**: `topActivity`, `activities[]`, `dumpFile`, and `sourceCommand`.

---

### `tap`

Performs screen touch (tap/long-press/swipe).

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | adb serial for this request. |
| `action` | string | no | `tap` (default) / `long-press` / `swipe`. |
| `x` | number | no | Start X in coordinate mode (min: 0). |
| `y` | number | no | Start Y in coordinate mode (min: 0). |
| `endX` | number | no | Swipe end X in coordinate mode (min: 0). |
| `endY` | number | no | Swipe end Y in coordinate mode (min: 0). |
| `xPercent` | number | no | Start X percentage (0–100). |
| `yPercent` | number | no | Start Y percentage (0–100). |
| `endXPercent` | number | no | Swipe end X percentage (0–100). |
| `endYPercent` | number | no | Swipe end Y percentage (0–100). |
| `duration` | number | no | Duration in ms (swipe default 300; long-press default 500; min: 50). |
| `text` | string | no | Exact element-text selector. |
| `resourceId` / `id` | string | no | Exact resource ID (prefer short ID); `id` aliases `resourceId`. |
| `contentDesc` / `desc` | string | no | Exact content description; `desc` aliases `contentDesc`. |
| `className` / `class` | string | no | AND class-name filter; `class` aliases `className`. |

**Mode priority**: coordinate > percent > element.

**Behavior**:
- `swipe` supports coordinate and percentage modes only, not element mode.
- Multiple matches in element mode prevent execution and return `ERROR` plus match summaries.
- Element mode uses a live Dragonfly snapshot. Android nodes prefer `View.performClick()`. Compose nodes currently dispatch a MotionEvent at their bounds center to the owning root View; this is not guaranteed equivalent to a Semantics action and cannot reliably judge disabled/stale nodes.
- Before action, check `topActivity` stability (two consecutive identical readings and onResume, waiting at most 5 seconds).
- Clamp percentage-derived coordinates to `[0, size-1]`.
- Recommended interaction order: `layout-dump + element tap` → `layout-dump + coordinate tap` → external screenshot evidence, if available, plus percent/coordinate tap. MCP `screenshot` action is currently unregistered and is not a default public tool.

---

### `status`

Queries current Jugg deployment state and a summary of uncompiled files.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `projectDir` | string | **yes** | Absolute project path. |
| `serial` | string | no | Return this device's deploy state; if unmatched, `hasDevice=false` and `stateMessage` explains why. |
| `refreshChanges` | boolean | no | Refresh Git-tracked changed files before reading; default `true`, pass `false` to skip. |
| `fullInfo` | boolean | no | Return full state; default `false`, pass `true` to return all uncompiled paths in `files`. |

**Response data**:
- `hasDevice`: boolean, `true` when a device is connected.
- `needFallback`: boolean, `true` when a Gradle full build is needed.
- `executionType`: `local` / `remote`, the Gradle-fallback environment of the current Jugg run configuration; in `remote`, the AI command hook first blocks a raw Gradle command once.
- `stateMessage`: human-readable current-state reason.
- `pendingModifiedFiles`: `{ total: number, <Type>: number, ... }`, uncompiled-file counts by `CompileFile.Type`.
- `files`: absolute uncompiled-file paths; at most 20 by default, all with `fullInfo=true`.
- `detail`: empty string when untruncated, otherwise natural-language truncation explanation suggesting `fullInfo=true`, e.g. `"Showing 20 of 25 files. Set fullInfo=true to return full status information, including all 25 file paths."`
- `lastFileModifiedTime`: local readable timestamp of latest uncompiled file (`yyyy-MM-dd HH:mm:ss`), or empty string with none.
- `lastCompileTime`: local readable timestamp of latest `compile` / `deploy` / `gradle-build` invocation (`yyyy-MM-dd HH:mm:ss`), or empty string with no record. AI hooks use it to decide whether Jugg verification covered writes in this Agent session.
- `hasBeenFullCompiled`: whether a complete Jugg full-build baseline exists. AI hooks enable raw Gradle and stop guards only when `true`. For `executionType=remote`, the command hook skips session-write and pending-file coverage checks but still applies "block once, allow repeat".
- `enabledAndroidTest`: whether the latest full-build baseline initialized an AndroidTest target (`true` means `enableAndroidTest` was enabled then).
- `isCompiling`: boolean, whether a Jugg compile/deploy task is active, aligned with `JuggConfigurationRunner.isCompiling`.

When idle and able to acquire the project lock immediately, `status` restores Runtime ownership and optionally refreshes Git under the lock. If the same Runtime is compiling or another IDEA/standalone write transaction holds the project lock, it immediately returns the current real read-only snapshot. It skips refresh and state writes then, but still retains actual deployment state, fallback cause, pending files, baseline, timestamps, and `isCompiling`, without waiting on a long task or fabricating empty values.

---

### `wait-logs`

Blocks while waiting for app logs until a marker matches, a crash occurs, or timeout; returns the filtered log window.

| Argument | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `projectDir` | string | **yes** | — | Absolute project path (pattern: `^/.+`). |
| `serial` | string | no | — | adb serial for this request; prefer project-and-serial deploy/restart timestamps. |
| `marker` | string | **yes** | — | Stop-condition regex (Java Pattern dialect), matched against log message text. |
| `tags` | array[string] | no | `[]` | Exact tag allowlist (empty means no tag filtering). |
| `timeoutMs` | integer | no | `30000` | Hard timeout in milliseconds, `[1000, 300000]`. |

**Response data**:
- `stopReason`: `marker` / `crash` / `timeout`.
- `startTime`, `endTime`: logcat threadtime in `MM-dd HH:mm:ss.SSS`.
- `targetPids`: target-process PID list enumerated on stopping.
- `logs`: at most 100 filtered lines (native logcat threadtime format, separated by `\n`).
- `allLogsPath`: path of the saved complete raw log.
- `truncated`: whether `logs` was truncated.

**Error codes**: `INVALID_PARAMS`, `INVALID_REGEX`, `NO_DEPLOY_BASELINE`, `NO_DEVICE`, `MULTIPLE_DEVICE`, `INTERNAL_ERROR`.

---

## Implemented but unregistered MCP actions

The following actions exist in code but are **not registered** in the tool list for a narrower public surface; external callers cannot use them:

| Action file | Purpose |
|-------------|---------|
| `EmulatorListMcpToolAction.kt` | Emulator list. |
| `FigmaLayoutVerifyMcpToolAction.kt` | Figma layout verification. |
| `LayoutVerifyMcpToolAction.kt` | Old UI batch verification (removed from MCP registry; unavailable externally). |
| `ScreenshotMcpToolAction.kt` | Screenshot (`screenshot`). |
| `StartActivityMcpToolAction.kt` | Start an Activity. |
| `StartAppMcpToolAction.kt` | Start the app. |
| `StartEmulatorMcpToolAction.kt` | Start an emulator. |
| `StartRecordMcpToolAction.kt` | Begin screen recording (`record-start`). |
| `StopRecordMcpToolAction.kt` | End screen recording (`record-stop`). |

---

## General MCP behavior

### Waiting for the app to be online

- `restart`, `deploy`, `gradle-build`, and `clean-reinstall` uniformly use `waitAppReadyAfterSuccess` for the post-success app-readiness wait. Default `false`; only explicit `true` waits (checks every 200 ms, up to 10 seconds).
- `activity-stack`, `tap`, `layout-dump`, `view-locate`, and `view-inspect` wait for the app to be online before execution (checks every 100 ms, up to 10 seconds).
- If a runtime tool returns `INTERNAL_ERROR` or no error code, treat it as transient and retry automatically at most three times, two seconds apart. This covers the short window when the app is online but its in-process service, such as ViewHierarchyServer, is not yet accepting LocalSocket requests.
- After the first ViewHierarchy-related access failure, check device screen state and foreground Activity. A sleeping/noninteractive device returns `DEVICE_NOT_INTERACTIVE` immediately; a target app outside the foreground returns `APP_NOT_FOREGROUND`. Neither error is retried further.
- Runtime-tool order: argument validation → `projectDir` initialization-state validation → app-online check → business operation.

### Asynchronous compile calls

`deploy` and `gradle-build` may return `isFinal=false` plus `jobId`. Poll with `get-compile-status` at the `pollIntervalSuggestedMs` interval.

The `deploy` success message is derived from actual `RunResult` after task completion. When no source changes occurred but install, recovery, or another deployment action still completed, it reports only that no source was compiled this run; it must not infer changes were previously deployed. The latest deployment with file changes is available only within the current Runtime session; when absent, use Runtime-neutral wording.

Terminal data contains `isCompileSuccess` (boolean) and `isDeploySuccess` (boolean). On failure, if diagnostic output exists, it contains `detail` / `detailLength` / `detailTruncated`. `compile`, `gradle-build`, `deploy`, and `instrument` may also use `status` for finer success/failure judgments.

### Artifact cleanup

MCP fetch-tool artifacts are stored under `build/jugg/mcp_fetch/<toolName>/`. After IDE startup, background cleanup removes files older than 30 days.

---

## Common error codes

| Error code | Meaning |
|------------|---------|
| `INVALID_JSON_RPC` | Malformed JSON-RPC. |
| `METHOD_NOT_SUPPORTED` | Unsupported method. |
| `TOOL_NOT_FOUND` | Unregistered tool. |
| `INVALID_PARAMS` | Invalid arguments. |
| `PROJECT_NOT_INITIALIZED` | IDEA project not initialized, or standalone project auto-initialization failed. |
| `NO_DEVICE` | No available device. |
| `MULTIPLE_DEVICE` | A single-device operation found multiple target devices; specify serial explicitly. |
| `DEVICE_NOT_INTERACTIVE` | Device asleep or noninteractive; wake/unlock and retry. |
| `APP_NOT_FOREGROUND` | Target app outside foreground; return to it and retry. |
| `FEATURE_NOT_SUPPORTED` | Capability unsupported by this project or runtime environment. |
| `INTERNAL_ERROR` | Internal error. |

---

## Connectivity and troubleshooting

> Use these steps only for connectivity/context anomalies. Normal use does not require fixed `list-projects` / `devices` preflight calls.

1. For IDEA, confirm the IDE initialized this project (`list-projects`). For standalone, a first valid project request may auto-register it.
2. For argument anomalies, compare with `inputSchema` from `tools/list`.
3. Run `devices` only after a device-related tool fails.
4. For a stuck asynchronous compile task, inspect `get-compile-status` and `compile_latest.log`.
5. If `layout-dump` or element-mode `tap` returns `DEVICE_NOT_INTERACTIVE`, wake/unlock the device and retry. For `APP_NOT_FOREGROUND`, bring the target app forward with `restart` or `start-activity` and retry. If `ViewHierarchy server is unavailable` persists, follow `restart` → `gradle-build` → retry.

---

## Related documents

- CLI wrapper: `08_cli_tools_list.md`.
- Design: `08_mcp_design.md`.
- figma-layout-verify algorithm: `08_mcp_figma_layout_verify_internals.md`.
- UI verification checklist: `08_mcp_ui_verify_checklist.md`.
- Code paths: `98_code_map.md`.
