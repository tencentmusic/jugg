# MCP Design

> Last checked: 2026-09-08
> Consistency rule: when documentation conflicts with code, code is authoritative.

---

## 1. Scope

This page describes MCP implementation layers, validation boundaries, the asynchronous model, and synchronization rules for extensions. It does not provide the complete tool-argument table.

For public tools and schemas, consult [`08_mcp_tools_list.md`](08_mcp_tools_list.md), `McpToolActionRegistry.defaultActions()`, and runtime `tools/list`. The CLI wrapper is documented in [`08_cli_tools_list.md`](08_cli_tools_list.md).

---

## 2. Core source index

| Class/interface | File | Role |
|-----------------|------|------|
| `McpLocalServer` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpLocalServer.kt` | Local HTTP server entry point; listens on a port and handles `/jugg-mcp` requests. |
| `McpBaseInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpBaseInvoker.kt` | Handles general JSON-RPC methods such as initialize/ping/tools/list. |
| `McpToolInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpToolInvoker.kt` | Dispatches `tools/call` to an action and maps its result. |
| `McpRequestValidator` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpRequestValidator.kt` | Schema validation, default filling, unknown-argument rejection, and projectDir validation. |
| `McpToolActionRegistry` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt` | Registers public MCP tools; `noProjectDirTools` is the global-tool allowlist. |
| `McpToolSchemas` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolSchemas.kt` | Reusable schema fragments. |
| `IMcpRuntime` / `IdeaMcpRuntime` / `StandaloneProjectRuntime` | `main/.../ai/mcp/IMcpRuntime.kt`, `idea/.../ai/mcp/IdeaMcpRuntime.kt`, `cmd_line/.../standalone/StandaloneProjectRuntime.kt` | Connect actions to IDEA or standalone project capabilities through a nonempty host-neutral `projectDir`. The Host explicitly implements every capability, including unsupported and project-state lock semantics; actions no longer read `Project.basePath`. |
| `ViewHierarchyClient` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/viewhierarchy/ViewHierarchyClient.kt` | Client for the in-app ViewHierarchy LocalSocket. |
| `LayoutDumpHelper` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpHelper.kt` | Shared dump capability for `layout-dump`, `view-locate`, and internal layout verification. |
| `McpAppReadyGuard` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpAppReadyGuard.kt` | Pre/post app-readiness checks for runtime observe/mutate tools. |

---

## 3. Layers

```text
McpLocalServer
  -> McpBaseInvoker
       -> initialize / ping / tools/list / global tools
  -> McpRequestValidator
       -> JSON-RPC params parsing
       -> inputSchema validation
       -> required and initialized projectDir validation
  -> McpToolInvoker
       -> McpToolActionRegistry.getAction(toolName)
       -> action.execute(arguments, runtime)
       -> McpResultMapper emits JSON-RPC response
```

Runtime observation tools continue into:

```text
McpToolAction
  -> McpAppReadyGuard / DeviceSelectionResolver
  -> ViewHierarchyClient or compile/deploy runtime
  -> structuredContent(status/message/data/artifacts/errorCode)
```

---

## 4. Protocol and response model

- Transport: HTTP + JSON-RPC 2.0.
- Main endpoint: `/jugg-mcp`.
- Protocol-level failures use JSON-RPC `error`.
- Business failures normally remain tool results with `structuredContent.status=ERROR` and retained `message/data/artifacts/errorCode`.

Clients must not mistake a business failure for a protocol failure. An Agent must read `structuredContent.status` and corresponding business fields to decide whether a command succeeded.

---

## 5. Argument-validation boundary

`McpRequestValidator` is the common gate for MCP arguments:

- Unknown arguments are rejected.
- Missing `required` fields return `INVALID_PARAMS`.
- Nested objects also enforce schema `additionalProperties`.
- Only tools in `McpToolActionRegistry.noProjectDirTools` omit `projectDir`; currently `list-projects` and `version`.
- IDEA non-global tools validate project initialization. For an unknown standalone project, the tool and schema are validated first; a valid request then auto-initializes and routes it.
- Before schema validation, `ProjectDirNormalizer.normalizeProjectDir` canonicalizes `projectDir` (`/` separators, Windows drive paths, MSYS `/d/...`, Cygwin `/cygdrive/d/...`, WSL `/mnt/d/...`). `list-projects` output and `JuggInitializer.getManager` lookup use the same canonical form.

Actions retain only business-combination checks, such as `instrument` sourcePath/baseline checks and runtime-observe app-readiness checks. An unregistered action such as `layout-verify` is not a public MCP capability even if internal validation remains.

IDEA and standalone may listen on different ports within the same range. `version` returns this process's `runtimeType`, `runtimeVersion`, and `capabilities`; `list-projects` lists only projects already initialized in this process. Standalone `version` and `list-projects` do not trigger registration. A valid project-level request for an unknown project is auto-initialized by `StandaloneProjectRegistry`, then executed; invalid tool, schema, path, or initialization does not pollute the registry. Initialization shares completion by canonical projectDir without holding a registry-global construction lock across different projects. Failure closes created project resources and permits retry, so a slow or failed project does not affect another initialized project. The process-level `McpToolRegistry` supplies capabilities and constrains both `tools/list` and action dispatch; they are not owned by `RuntimeInfo` or the platform interface. Standalone Step 11 registers `version`, `list-projects`, `compile`, `deploy`, `gradle-build`, `get-compile-status`, `status`, `restart`, `report-prepare`, `report-upload`, and `devices`. The standalone compile path initializes build profiles on demand; it is not a public MCP action.

Device selection uses request-level context rather than a server-global "current device". Schemas supporting a targeted device expose optional `serial`; an explicit value exactly matches an online device and cannot fall back. Project-level `IDeployTargetManager` owns device selection and online devices, then creates an ADB adapter in the same project domain through `createDeviceAdb()`; MCP actions do not translate devices through process-level `PlatformApi`. Compile refreshes unified deployment state to choose incremental compilation or Gradle fallback but does not deploy. Without serial, device selection can return all online devices and must not fail solely on multiplicity. Deploy, reinstall, and instrument process all target devices; restart restarts all targets. UI, Activity, and log tools that require one device return structured `MULTIPLE_DEVICE` for multiple targets. For compatibility, `report-prepare` retains but ignores serial and collects error logcat from all target devices on a best-effort basis. Standalone uses serial selection in its registered `deploy`, `gradle-build`, `status`, `restart`, and `devices` capabilities; `report-prepare` uses all online devices, and other UI, log, and runtime-control capabilities remain unavailable.

### 5.1 IDEA and standalone capability boundary

The capability table means an action can be discovered and dispatched, not that the Host fully implements its runtime semantics. Handle remaining standalone differences as follows:

| Capability | Current boundary | Prerequisite for alignment | Expected effort |
|------------|------------------|----------------------------|-----------------|
| `stop` | Local CLI lifecycle command. | Call standalone launcher directly, outside MCP capability. | Keep difference. |
| `ssh-info` | IDEA only. | Standalone is noninteractive; design user authorization and credential-safety boundary first. | Keep difference. |
| `activity-stack`, `wait-logs` | IDEA only. | Actions already use host-neutral ADB; add targeted standalone tests and real-device verification before exposure, covering stream cancellation and resource release. | Small. |
| `layout-dump`, `view-locate`, `view-inspect`, `tap` | IDEA only. | Verify ViewHierarchy Server after standalone deployment, `adb forward`, app readiness, and multi-device `MULTIPLE_DEVICE`. | Medium. |
| `clean-reinstall` | IDEA only. | Current action writes a process-level clean-reinstall marker that standalone runner does not consume; move to project-level request state and verify data clearing first. | Medium. |
| `instrument` | IDEA only. | `StandaloneDeployEnvironment.launchAndroidTest()` does not yet execute instrumentation; exposing it now would falsely report success after deployment without running tests. | Large. |

Standalone registers `devices` because its action depends only on online-device and selection results from project-level `IDeployTargetManager`; it needs no app readiness, ViewHierarchy, or test-launch capability. If Host selection lookup fails, the action retains online devices with no selected marker, avoiding HTTP 500 from stale standalone `ANDROID_SERIAL`. Its capability, `tools/list`, and action dispatch remain constrained by one `McpToolRegistry` allowlist.

`McpLocalServer` invokes an external-activity callback on any HTTP request. IDEA uses a default no-op callback; standalone uses it to refresh a four-hour idle deadline. Request-parsing failures do not change this activity behavior.

`status` has a nonblocking read boundary on the project lock. On immediate acquisition it restores Runtime ownership and optionally refreshes Git, then returns a consistent snapshot. If the same Runtime is compiling or another project write transaction holds the lock, it immediately returns a real read-only snapshot from memory and persisted state; it does not refresh Git, mutate `DeployFileManager`, or fabricate empty files/default deployment state. This avoids reading half-committed state without blocking CLI waits or heartbeats.

---

## 6. Asynchronous compile model

`CompileJobManager` hosts asynchronous compile/deploy/gradle-build/instrument jobs:

```text
compile/deploy/gradle-build/instrument
  -> may return data.status=running + jobId
  -> get-compile-status(projectDir, jobId, waitTimeoutMs)
  -> terminal status/message/logPath/isCompileSuccess/isDeploySuccess
```

Constraints:

- `compile_latest.log` is the common log output path.
- `get-compile-status` closes over job state and must not retrigger the business operation.
- CLI auto-polling is only a wrapper; an MCP client may still poll itself.

---

## 7. ViewHierarchy and UI-tool constraints

- `layout-dump` publicly returns an HTML artifact; internal JSON files serve implementations such as `view-locate` only.
- `ViewHierarchyClient` uses the in-app LocalSocket server-only channel, without uiautomator fallback.
- `DragonflyHierarchySource` uniformly extracts Android View and Compose nodes for in-app ViewHierarchy. Dump, selectors, element clicks, getter queries, and old layout verification no longer maintain separate ViewTree sources; MCP arguments, response envelope, and HTML artifact remain unchanged.
- Android nodes use the original View exposed by Dragonfly for `performClick` and getters. Compose nodes currently click via a MotionEvent at bounds center and query the Dragonfly node object; missing capabilities return explicit errors.
- Id-less nodes use deterministic `_vir_id_<hash>`, stable across requests only while Dragonfly window/child ordering and UI structure remain unchanged; it is not a stable business identity.
- Multi-process socket attempts are ordered "main process → remaining PIDs → `jugg_vh` compatibility name".
- `ElementFinder` filters non-actionable nodes before selector matches: visible, shown, nonzero size, and valid bounds.
- The ViewHierarchy socket response includes `version`; the current client warns only on mismatch.
- A breaking change (field removal, type change, incompatible semantics) must increment server protocol version and synchronize the client constant and documentation.

Public UI verification-tool boundary:

| Tool | Current public state | Entry point |
|------|----------------------|-------------|
| `layout-dump` | Public MCP + CLI | `LayoutDumpMcpToolAction` |
| `view-locate` | Public MCP + CLI | `UiFindMcpToolAction` |
| `view-inspect` | Public MCP + CLI | `EvalViewMcpToolAction` |
| `tap` | Public MCP + CLI | `TapMcpToolAction` |
| `layout-verify` | Action class exists, but absent from `defaultActions()`; not currently public. | `LayoutVerifyMcpToolAction` |
| `figma-layout-verify` | Action class exists, but absent from `defaultActions()`; not currently public. | `FigmaLayoutVerifyMcpToolAction` |

Do not put `layout-verify` or `figma-layout-verify` in the public tool list before registering them in `McpToolActionRegistry.defaultActions()` and synchronizing `tools/list` and CLI/skill documentation. See [`08_mcp_figma_layout_verify_internals.md`](08_mcp_figma_layout_verify_internals.md) for the internal `figma-layout-verify` algorithm.

---

## 8. Artifacts and logs

- MCP fetch-tool artifacts live in `build/jugg/mcp_fetch/<toolName>/`.
- Project-level `ExpiredArtifactCleaner` removes MCP fetch artifacts older than 30 days.
- For compile/deploy logs, first inspect `build/jugg/log/compile_latest.log`.
- `wait-logs` uses deploy/restart timestamps as its app-log starting point. `LastDeployTimestampRegistry` isolates records by `projectDir + serial`, falling back to old project-level records only when a device record is missing.
- Control Panel records `MCP request` / `MCP response` for every `tools/call`. Request details show arguments without `projectDir`; response details show `status/message/data/artifacts/errorCode`. Nested `projectDir` fields are removed, and sensitive fields such as password/token/secret/authorization/apiKey/privateKey/credential/environmentVariables display `[REDACTED]`; one entry is capped at 4096 characters. Display redaction does not alter the actual MCP request or response.

---

## 9. Extending the tool set

1. Add a `McpToolAction` implementation and define `McpToolDefinition`.
2. Register it in `McpToolActionRegistry.defaultActions()`; synchronize `noProjectDirTools` if it does not need `projectDir`.
3. Add action-level argument-combination validation and success/failure tests.
4. Synchronize [`08_mcp_tools_list.md`](08_mcp_tools_list.md).
5. If exposing it through CLI, synchronize `jugg.py::COMMANDS`, `help_registry.py`, the corresponding `cmd_*.py`, and [`08_cli_tools_list.md`](08_cli_tools_list.md).
6. Check whether the skill, CLI manual, and error patterns in `docs/skills/jugg-android-dev-loop/` still describe old behavior.
7. If CLI scripts or skill content changed, increment `CLI_VERSION` and the `SKILL.md` `version`/`date`; otherwise `JuggCliAutoUpdater` will not refresh installed CLI/skill. See [`08_cli_tools_list.md`](08_cli_tools_list.md) §3.7.

---

## 10. Skill synchronization for MCP/CLI changes

| Change type | Skill documents to inspect/update |
|-------------|-----------------------------------|
| Behavior change in `deploy`, `compile`, `restart`, etc. (default arguments, restart policy, blocking/asynchronous operation) | `SKILL.md` §Build & Deploy Commands, `flow_compile_deploy.md`, `flow_with_auto_run.md` |
| New or changed MCP/CLI arguments | `SKILL.md` §Advanced Commands, `references/cli_manual.md` |
| Error-code or message changes | `references/error_patterns.md` |
| CLI script / skill-content changes | `CLI_VERSION` in `cmd_version.py`, `version`/`date` in `SKILL.md`; otherwise post-startup `JuggCliAutoUpdater` will not refresh. |
| App state after deployment (restart or preserved runtime state) | `SKILL.md` §Mandatory Rules, `flow_with_auto_run.md` Step 3 |

After making a change, search the corresponding Skill documents in the table for old-behavior descriptions; update or remove any matches.

---

## 11. Related documents

- MCP tool argument list: `08_mcp_tools_list.md`.
- CLI arguments and MCP mapping: `08_cli_tools_list.md`.
- UI layout-verification design: `08_mcp_layout_verify_design.md`.
- figma-layout-verify internals: `08_mcp_figma_layout_verify_internals.md`.
- Code paths: `98_code_map.md`.
