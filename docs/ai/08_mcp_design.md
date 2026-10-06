# MCP Design and Runtime Boundaries

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page describes routing, validation, response semantics, and host capability boundaries. Tool discovery and key call contracts are in `08_mcp_tools_list.md`; the connected Runtime's `tools/list.inputSchema` gives exact arguments. CLI mapping belongs to `08_cli_tools_list.md`. The Standalone daemon, project registration, and lock hierarchy are in `04_engineering_compat.md`.

## 2. Source Owners

| Boundary | Owner |
|---|---|
| HTTP and JSON-RPC envelope | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpLocalServer.kt`, `McpJsonRpcModels.kt` |
| Common methods, project tool dispatch, result shape | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/` (`McpBaseInvoker.kt`, `McpToolInvoker.kt`, `McpResultMapper.kt`) |
| Schema gate and discoverable tools | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpRequestValidator.kt`, `McpToolRegistry.kt`, `actions/McpToolActionRegistry.kt` |
| Host routing | `idea/src/main/java/com/sickworm/intellij/jugg/ai/mcp/IdeaMcpRuntime.kt`, `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneProjectRegistry.kt` |
| Host-neutral action contract and asynchronous jobs | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/IMcpRuntime.kt`, `actions/CompileJobManager.kt` |
| Device and app observation | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/DeviceSelectionResolver.kt`, `actions/McpAppReadyGuard.kt`, `viewhierarchy/ViewHierarchyClient.kt` |

## 3. Request Routing and Protocol

`McpLocalServer` serves `/jugg-mcp` over local HTTP on the first free port in `12320..12329`; IDEA and Standalone can therefore have different ports. It accepts POST JSON-RPC 2.0 objects, not batches. An absent `MCP-Protocol-Version` header is allowed; supplied versions must be `2025-06-18` or `2025-11-25`. `initialize` advertises `2025-06-18`; ping, initialized notification, and empty prompt/resource listings are common methods. Notifications and envelopes without `method` return HTTP 202 without a response body. The server's external-activity callback runs before request parsing; Standalone uses it to extend its idle deadline, including for invalid requests.

```text
HTTP request
  -> McpLocalServer envelope checks
  -> PlatformApi host routing
  -> McpBaseInvoker for common methods and global tools
  -> host project lookup for project-scoped tools
  -> McpRequestValidator schema/project gate
  -> McpToolInvoker action.execute(arguments, runtime)
  -> McpResultMapper response
```

`McpToolActionRegistry.defaultActions()` defines implemented actions. `McpToolRegistry` restricts both `tools/list` and dispatch to the current host's capability list; discovery is therefore a runtime contract, not proof that every source action is public. `tools/list` omits output schemas from its compact response. `version` reports process `runtimeType`, `runtimeVersion`, and the registry capabilities; `list-projects` shows only projects initialized in that process. Both are the only `noProjectDirTools` and do not register a Standalone project.

## 4. Validation and Error Ownership

`tools/call` reads `params.name` and `params.arguments`. The validator checks tool availability, applies top-level schema defaults, rejects unknown arguments when `additionalProperties=false`, and validates required fields, types, bounds, enums, patterns, and nested object/array schemas. For project tools it normalizes `projectDir` with `ProjectDirNormalizer` before schema and initialized-project checks. The normalizer handles native paths and Windows drive paths expressed through MSYS, Cygwin, or WSL. Host routers use the same normalized identity for lookup. Actions retain cross-field and runtime checks, such as baseline/app readiness, that a schema cannot decide.

IDEA routes a project tool only to an already initialized `JuggInitializer` manager; an unknown project returns `PROJECT_NOT_INITIALIZED` with initialized-project context. Standalone validates the tool, schema, and project directory first, then registers an unknown valid project under its canonical path and invokes it. Concurrent initialization of that same project shares completion; failure returns `PROJECT_NOT_INITIALIZED` and allows a later retry. Invalid/global requests do not create project state. These host rules explain why the same valid project path can be rejected by IDEA yet initialized by Standalone.

| Failure origin | Wire result | Client decision |
|---|---|---|
| Invalid JSON-RPC envelope, unsupported method, parse or server failure | Top-level JSON-RPC `error` (HTTP error for HTTP-layer failures) | Treat as protocol/transport failure. |
| Tool lookup, argument, or project routing failure before action execution | Tool result with `isError=true`, `structuredContent.status=ERROR`, and `errorCode` | Correct the request or project/host selection. |
| Action executes and reports a business failure | Tool result with `isError=false`, `structuredContent.status=ERROR`; `message`, `data`, `artifacts`, and `errorCode` are retained | Read `structuredContent.status`; do not infer success from `isError=false`. |

`McpToolInvoker` deliberately uses the last form even when an action returns `ERROR`, so MCP clients preserve diagnostic data and artifacts. The global invoker can return `isError=true` for a global action failure. `McpResultMapper` owns response and ID normalization; bypassing it risks inconsistent envelopes.

## 5. Host Capabilities and Project State

The Standalone registry currently exposes `version`, `list-projects`, `compile`, `deploy`, `gradle-build`, `get-compile-status`, `status`, `restart`, `report-prepare`, `report-upload`, and `devices`. IDEA uses the full registered action set. `stop` is a local CLI lifecycle command, not an MCP capability. Actions such as `layout-dump`, `view-locate`, `view-inspect`, `tap`, `wait-logs`, `activity-stack`, `clean-reinstall`, and `instrument` are not exposed by Standalone. In particular, its AndroidTest launch path is not implemented, so adding `instrument` to the allowlist would misrepresent deployment as a completed test run; `clean-reinstall` also needs project-scoped marker ownership before exposure. Check `McpToolRegistry.listCapabilities()` and `tools/list` before assuming a source action is reachable.

`IMcpRuntime` supplies the nonempty project path, target/device services, compile/deploy services, and project-state lock operations. Actions do not infer ownership from an IDE `Project.basePath`. A supplied `serial` is an exact online-device target and must not silently fall back. Without one, actions may apply different policies: compile/deploy/restart can handle their target set, while a single-device UI or log operation can return `MULTIPLE_DEVICE`. The project `IDeployTargetManager` owns selection and creates its ADB adapter in the same domain. `report-prepare` retains but ignores `serial` for compatibility and gathers available device logs best-effort.

`status` first checks whether compilation is active; otherwise it tries the project-state lock without waiting. The acquired path may refresh Git and deploy state after runtime-owner recovery. A busy response reads existing memory/persisted state without refresh or mutation; it is a real snapshot, not a fabricated empty result. Use lock-owner evidence from `04_engineering_compat.md` when a status appears stale during a build.

## 6. Long-Running Work and Diagnostic Output

`CompileJobManager` owns asynchronous compile, deploy, Gradle-build, and instrument job state. A trigger can return `data.status=running`, `jobId`, and `isFinal=false` after its soft wait; `get-compile-status(projectDir, jobId, waitTimeoutMs)` reads that job without re-executing it. Its bounded wait is at most 10 seconds; terminal states provide compile/deploy success fields when known and failure detail when available. The CLI may poll automatically, while direct MCP clients can poll themselves. Read the returned log path and `build/jugg/log/compile_latest.log` for compile/deploy diagnosis; a running response is not a failure.

MCP fetch artifacts live under `build/jugg/mcp_fetch/<toolName>/`; IDEA schedules `ExpiredArtifactCleaner` to remove files after 30 days. `wait-logs` starts from per-project/per-device deploy or restart timestamps, falling back to a prior project-level record only when the device record is absent. The IDE Control Panel records `MCP request` and `MCP response`; it omits `projectDir` recursively and redacts sensitive key names before display, capping each detail at 4096 characters. That display transformation does not alter the actual request or response, so diagnostic exports require their own redaction boundary.

## 7. ViewHierarchy Boundary

Public `layout-dump`, `view-locate`, `view-inspect`, and `tap` use the app-side ViewHierarchy LocalSocket through `ViewHierarchyClient` and project device transport. There is no uiautomator fallback. The client attempts main-process PID sockets, remaining process sockets, then the legacy `jugg_vh` name. The app server response carries a version; the current client warns on mismatch. A breaking field, type, or semantic change requires a server protocol bump and matching client/documentation update. `layout-dump` publishes HTML; implementation JSON is not a public artifact. Dragonfly supplies both View and Compose nodes; `_vir_id_` for id-less nodes is stable only while hierarchy ordering and structure remain stable.

`LayoutVerifyMcpToolAction` and `FigmaLayoutVerifyMcpToolAction` exist internally but are absent from `defaultActions()`. Do not advertise them in public MCP/CLI lists until registration, capability exposure, and their documentation agree. The Figma algorithm belongs to `08_mcp_figma_layout_verify_internals.md`.

## 8. Change Checklist

For a public tool change, update the action definition, `defaultActions()`, `noProjectDirTools` when global, and each host's capability allowlist. Then compare `tools/list`, `08_mcp_tools_list.md`, CLI `jugg.py`/help/command mapping and `08_cli_tools_list.md`, and installed skill instructions under `docs/skills/jugg-android-dev-loop/`. A changed CLI script or skill body also needs its version/date bump so `JuggCliAutoUpdater` installs it. Keep protocol errors, pre-action tool errors, and executed business failures distinct in verification.

For skill synchronization, check `SKILL.md` and `references/flow_compile_deploy.md` / `flow_with_auto_run.md` after command or app-state changes, `references/cli_manual.md` after argument changes, and `references/error_patterns.md` after error-code or message changes. These are separate consumers of the public contract; updating only the MCP catalog does not update installed Agent guidance.

## 9. Related Documents

- `08_mcp_tools_list.md` and `08_cli_tools_list.md` — public contracts.
- `04_engineering_compat.md` — Standalone registration, locks, and idle lifecycle.
- `08_mcp_layout_verify_design.md` and `08_mcp_figma_layout_verify_internals.md` — UI verification internals.
- `09_plugin_runtime_debug.md` — log interpretation.
