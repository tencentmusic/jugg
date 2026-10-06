# MCP Tool Catalog

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Discovery and Common Contract

`McpToolActionRegistry.defaultActions()` registers 20 actions for the IDEA runtime. A host's `McpToolRegistry` limits both `tools/list` and dispatch to its capabilities; **the connected Runtime's `tools/list` is authoritative** for public availability and its current `inputSchema`. The Standalone Runtime exposes only the 11 tools marked **Both** below. A source action, name constant, or CLI command alone does not make a tool callable over that Runtime's MCP endpoint. CLI availability and argument spelling are in `08_cli_tools_list.md`.

The server uses JSON-RPC 2.0 over HTTP POST at `/jugg-mcp`, on the first free port in `12320..12329`. Optional `MCP-Protocol-Version` accepts `2025-06-18` and `2025-11-25`; `initialize` advertises `2025-06-18`. `version` and `list-projects` are global tools with no arguments. Every other tool requires an absolute `projectDir` after host path normalization. IDEA requires an initialized project; a valid project-scoped Standalone call can initialize one on demand. An unknown argument is rejected when the schema disallows additional properties; inspect `tools/list` for exact defaults, nested shapes, and bounds.

Every completed tool response has `structuredContent.status` (`OK` or `ERROR`), `message`, `data`, `artifacts`, and optional `errorCode`. An action can report `status=ERROR` while MCP `isError=false`, retaining its diagnostic data and artifacts; read `status`, not just `isError`. Request validation before action execution instead uses `isError=true`. JSON-RPC envelope failures use top-level `error`. See `08_mcp_design.md` §4.

Where offered, `serial` targets exactly one online device for that request and does not fall back to IDEA selection or Standalone `ANDROID_SERIAL` on a miss. `report-prepare` accepts but ignores it for compatibility. A missing `serial` follows each action's target policy: multi-target compile/deploy/restart paths can continue, while tools requiring one device return `MULTIPLE_DEVICE`. For `devices`, an explicit missing serial returns `NO_DEVICE`.

## 2. Registered Actions

The table follows `McpToolActionRegistry.defaultActions()` order. “Input beyond projectDir” lists caller-significant fields; the live `tools/list` schema owns the complete argument definition.

| Tool | Runtime | Input beyond `projectDir` | Result or boundary |
|---|---|---|---|
| `list-projects` | Both | None; global | Initialized projects in this process, with `hasBeenFullCompiled`; does not register a project. |
| `restart` | Both | `serial?`, `waitAppReadyAfterSuccess?` | Restarts target app; readiness wait is opt-in. See §4 for stop-only fallback. |
| `compile` | Both | None | Compile without deploy; may choose full Gradle fallback from deployment state. |
| `deploy` | Both | `serial?`, `alwaysRestartApp?`, `waitAppReadyAfterSuccess?` | Compile/deploy with possible asynchronous job; `alwaysRestartApp` defaults to `true`. |
| `instrument` | IDEA | `sourcePath`, `serial?`, `class?`, `method?`, `runner?`, `extras?` | AndroidTest source selects Test APK; requires an AndroidTest full-build baseline. |
| `clean-reinstall` | IDEA | `serial?`, `waitAppReadyAfterSuccess?` | Uninstall/reinstall and clear app data. |
| `gradle-build` | Both | `serial?`, `waitAppReadyAfterSuccess?` | Full Gradle build then install/start; may return an asynchronous job. |
| `get-compile-status` | Both | `jobId`, `waitTimeoutMs?` | Read job state without repeating the operation. |
| `ssh-info` | IDEA | `reason`, `requestedBy?` | Remote SSH troubleshooting request with explicit user-consent flow. |
| `report-prepare` | Both | `serial?` (ignored) | Build a redacted diagnostics ZIP locally for review; no upload. |
| `report-upload` | Both | `reportId`, `sha256` | Verify and upload only the reviewed ZIP. |
| `devices` | Both | `serial?` | Online device list and selected marker, or one exact match. |
| `layout-dump` | IDEA | `serial?`, `rootLayout?`, `includeGone?`, `allWindows?` | Public HTML hierarchy artifact; no uiautomator fallback. |
| `view-locate` | IDEA | `serial?`, `target`, `visibleOnly?`, `maxResults?`, `figmaNode?` | Live selector matches and dp coordinates; `figmaNode` is ignored compatibility input. |
| `view-inspect` | IDEA | `serial?`, `target`, `expressions` | Read-only getter/field values for one node. |
| `activity-stack` | IDEA | `serial?` | Activity stack, dump path, and source command. |
| `tap` | IDEA | `serial?`, `action?`, coordinates/percentages or selectors, `duration?` | Tap, long press, or swipe; see §5 for mode rules. |
| `status` | Both | `serial?`, `refreshChanges?`, `fullInfo?` | Current deployment and pending-file snapshot; see §3. |
| `version` | Both | None; global | `pluginVersion`, `runtimeType`, `runtimeVersion`, and host capability list. |
| `wait-logs` | IDEA | `serial?`, `marker`, `tags?`, `timeoutMs?` | Wait for marker/crash/timeout and return a bounded log window. |

`layout-verify`, `figma-layout-verify`, `screenshot`, recording, emulator, and start-app/activity action classes exist but are absent from `defaultActions()`; they are not callable public MCP tools. Do not infer registration from class existence or a `ToolNames` constant. Standalone also excludes registered IDEA UI/log actions such as `wait-logs` and `activity-stack`. `stop` is a local CLI lifecycle command, not an MCP action.

When initialized projects report different plugin versions, `version.data.pluginVersion` is the highest and `version.data.projects` maps each `projectDir` to its version. Without a mismatch, `projects` is omitted; `runtimeVersion` identifies the connected process independently of these project versions.

## 3. Build, Deployment, and Status Decisions

`deploy` and `gradle-build` can return `data.isFinal=false`, `jobId`, and `status=running`; `compile` and `instrument` use the same job manager when their work outlasts the soft wait. Poll `get-compile-status` with that `jobId`; `waitTimeoutMs` accepts `0..10000` ms and defaults to a nonblocking read. Job state is `running`, `success`, `failed`, `canceled`, or `unknown`. Poll data includes `executionType` (`local`/`remote`); while running, it also includes `pollIntervalSuggestedMs` and nonempty `indicator.text` when the IDE reports progress. Terminal results expose `isCompileSuccess` and `isDeploySuccess` when known. Failure details use `detail`, `detailLength`, and `detailTruncated`, with the initial and polled result using the same final message. An unknown job ID is an `INVALID_PARAMS` tool failure, not a new build. For full Gradle failure, the detail preview keeps both the beginning and end of available diagnostics; inspect `build/jugg/log/compile_latest.log` for the full context.

`compile` does not deploy, even though it reads deployment state to choose incremental work or Gradle fallback. A successful no-change compile says `No pending file changes` and creates no new deployment artifact. `deploy` with no source compilation must not imply a new deployment occurred: its message reflects the actual Run result and can mention the latest file-changing deployment only when session data exists. `alwaysRestartApp=false` permits hot reload when class structure allows it; the default `true` requests restart after deployment. `waitAppReadyAfterSuccess` defaults to `false` for restart, deploy, Gradle build, and clean reinstall; set it when app readiness must be part of success. Standalone `gradle-build` also performs device installation/start and can fail if no online target exists. Its remote Gradle execution still leaves local project-info reads, incremental work, and device operations on the Standalone host.

`status` returns `hasDevice`, `needFallback`, `executionType`, `stateMessage`, pending-file counts and paths, last file/compile times, `hasBeenFullCompiled`, `enabledAndroidTest`, and `isCompiling`. By default `files` includes at most 20 paths; `fullInfo=true` requests all, while `refreshChanges=false` skips optional Git refresh. When compilation or another project write holds the lock, status returns an immediate read-only snapshot without refreshing Git or deploy state. Treat a busy snapshot as current recorded state, not proof that a running build has finished. `enabledAndroidTest=false` is relevant to `instrument`: build the AndroidTest target once before retrying a test-source call. The `sourcePath` must resolve to the intended androidTest file; `method` requires an unambiguous class, and `extras` values are strings.

## 4. Devices, Reports, and Logs

`devices` returns online devices even if the Host's selected-device lookup fails; selected markers can then be absent. A `restart` without a launch or HOME Activity can only force-stop the package and warn; enabling `waitAppReadyAfterSuccess` then fails because the app cannot become ready. Without `serial`, restart targets all selected/eligible devices. Device-related actions that need one device return structured `MULTIPLE_DEVICE` instead of silently choosing one.

`report-prepare` collects available device error logs best-effort and returns a ZIP artifact with `reportId`, path, size, SHA-256, upload URL, and entry/redaction manifest for review. `report-upload` rereads the project diagnostics bundle and verifies report ID (eight lowercase hex digits), directory boundary, manifest, entries, and caller SHA-256 (64 hex digits) before network access; any changed content fails. Its success data contains only `reportId`, without local diagnostic paths. These two tools are separate so callers can review the exact archive before upload.

`wait-logs` requires a Java-regex `marker`; optional `tags` are an exact allowlist and `timeoutMs` is `1000..300000` (default 30000). It starts from a project/device deploy or restart timestamp when available and stops on marker, crash, or timeout. The result contains at most 100 filtered lines plus `allLogsPath` for the full raw window. `NO_DEPLOY_BASELINE`, `INVALID_REGEX`, `NO_DEVICE`, and `MULTIPLE_DEVICE` distinguish the common setup failures.

## 5. UI Observation and Interaction

The IDEA-only layout tools require a ready, single-device app and use the in-app Dragonfly ViewHierarchy LocalSocket. The precheck polls readiness for up to 10 seconds; after it succeeds, `INTERNAL_ERROR` or a missing error code permits at most three operation retries, two seconds apart, while the in-app service comes online. A sleeping device or app outside the foreground returns `DEVICE_NOT_INTERACTIVE` or `APP_NOT_FOREGROUND` and needs that condition corrected rather than another automatic retry. `LayoutDumpHelper.dump()` publishes `layout-dump` HTML (`data.file`); `dumpInternal()` supplies JSON to internal consumers and is not a public artifact. A `rootLayout` selects a subtree, `allWindows=true` includes other windows, and `includeGone=true` includes GONE nodes. Output is bounded by depth/node limits; a truncated snapshot cannot establish that an omitted node is absent. Coordinates and padding are in dp; `_vir_id_` identifiers can change after UI reordering.

`view-locate.target` matches nonempty `text`, `resourceId`, `contentDesc`, and `className` fields together. `visibleOnly` defaults to `true`; `maxResults` defaults to 10 and accepts `1..100`. `matchCount` covers all matches, `returnedCount` covers the bounded list, and `truncated` marks a shortened result. Top-level bounds/position/size appear only for a unique match; multiple matches do not select the first. `view-inspect` accepts one `target` and 1–20 read-only `expressions`; each value carries its own result or error. Android View getters use the original View, while Compose queries depend on getters exposed by the Dragonfly node. Use `view-locate` for position and `view-inspect` for internal properties.

`tap` mode priority is coordinate (`x`,`y`) → percent (`xPercent`,`yPercent`) → element (`text`/`resourceId`/`contentDesc` with optional `className`); `id`, `desc`, and `class` are aliases. `swipe` needs both start and end coordinate or percent pairs and does not support element mode. Element ambiguity is an error. Android nodes prefer `View.performClick()`; Compose dispatches a MotionEvent at the node center, which does not guarantee Semantics behavior or disabled-state handling. Percentage positions are clamped to the screen. The action checks foreground Activity stability before interaction. `screenshot` is not a registered MCP tool.

## 6. Error and Evidence Start Points

| Result | Next evidence |
|---|---|
| `INVALID_JSON_RPC` | Top-level JSON-RPC error data: inspect JSON syntax, request object shape, and `MCP-Protocol-Version` before tool arguments. |
| `METHOD_NOT_SUPPORTED` | Top-level protocol or HTTP method error: inspect `method`, POST transport, and supported MCP methods; this is distinct from an unregistered tool. |
| `TOOL_NOT_FOUND` | Current Runtime's `tools/list`, then its capability allowlist; IDEA registration alone is insufficient for Standalone. |
| `INVALID_PARAMS` | `tools/list` `inputSchema`, especially `projectDir`, required nested fields, and bounds. |
| `PROJECT_NOT_INITIALIZED` | IDEA `list-projects`; on Standalone, inspect valid path and initialization failure detail. |
| `NO_DEVICE` / `MULTIPLE_DEVICE` | `devices` after the failed device operation; specify one exact online `serial` if required. |
| `DEVICE_NOT_INTERACTIVE` / `APP_NOT_FOREGROUND` | Wake/unlock device or bring target app forward, then retry UI observation. |
| `FEATURE_NOT_SUPPORTED` | Check the Host capability and project/runtime prerequisites; do not assume a different action will inherit support. |
| `INTERNAL_ERROR` | Preserve `structuredContent` and the relevant compile or plugin log; discriminate app readiness/socket failure from compilation failure. |

For connectivity or context anomalies, use `version` to identify the connected Runtime, `list-projects` to inspect IDEA initialization, and `tools/list` to inspect the live schema. A normal tool call needs no fixed preflight sequence. MCP fetch artifacts live under `build/jugg/mcp_fetch/<toolName>/`; IDEA schedules cleanup for files older than 30 days.

## 7. Related Documents

- `08_mcp_design.md` — validation, routing, and response ownership.
- `08_cli_tools_list.md` — CLI wrapper mapping and versions.
- `08_mcp_ui_verify_checklist.md` — UI observation workflow.
- `03_deploy_core.md` and `06_android_test.md` — deployment and test prerequisites.
