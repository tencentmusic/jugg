# Jugg Architecture: Runtime Boundaries

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page identifies Jugg's module boundaries and the first cross-layer handoffs for initialization, Run, and MCP requests. The topic pages below own their detailed state machines and failure policies.

## 2. Source Index and Layer Ownership

| Boundary | Owner and location | Responsibility |
|---|---|---|
| Stable IDE entry | `JuggLoader`, `JuggInitializer` in `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/` | Plugin loading and lifecycle entry across hot updates |
| IDEA Host | `JuggManager` in `idea/src/main/java/com/sickworm/intellij/jugg/` | Connects Sync, Run, UI, project model, monitor, and shared Runtime collaborators |
| Project/Compile Context | `CompileContextManager` in `main/src/main/java/com/sickworm/intellij/jugg/compiler/context/`; IDEA model adapters in `idea/.../compiler/context/` | Keeps effective project information and compile environment independent of IDEA model APIs |
| Change state | `FileChangeManager`, `FileChangesHandler` in `main/.../project/change/`; IDEA VFS adapter in `idea/.../project/change/` | Converts Host file events and Git reconciliation to shared changed-file state |
| Task ownership | `TaskRunnerManager`, `RuntimeTaskCoordinator` in `main/.../project/runtime/`; `HostTaskExecutor` in `idea/.../runtime/` | Serializes blocking work within one Runtime and bridges IDEA task display; a Project Runtime lease excludes other Runtimes |
| Compile and deploy | `JuggCompilerHelper` in `main/.../compiler/`; `JuggDeployerHelper` in `main/.../deploy/run/` | Own the shared fallback and deployment decisions; IDEA and standalone supply environment/device adapters |
| Device compatibility | `deploy_compat/interface` and versioned `deploy_compat/*` | Business code uses Jugg's own `deploy.api` types; version adapters convert Android Studio types at the boundary |
| MCP protocol | `McpLocalServer`, `McpToolInvoker` in `main/.../ai/mcp/` | Serve HTTP/JSON-RPC and dispatch registered actions without requiring IDE UI logic |
| Non-IDE runtime | `cmd_line/src/main`, `platform_compat/base_api` | Standalone Host and minimal IntelliJ/log4j stubs; the stub layer supplies no Android runtime classes |
| In-app runtime | `jvmti_agent/src/main` | JVMTI and app-side deployment support |

## 3. Cross-Layer Flows

### 3.1 Initialization and project refresh

```text
JuggLoader / JuggInitializer
  -> JuggManager assembles IDEA Host adapters and shared collaborators
  -> JuggGradleSyncListener reports Sync to JuggManager.onSyncEvent()
  -> IdeaProjectModelSource supplies the Host model to CompileContextManager
  -> effective Compile Context and FileChangesHandler scan scope update together
```

`JuggManager` owns the IDEA lifecycle, configuration refresh, history restoration, and disposal. Project information and file-change state live in `main`; IDE-specific reads and VFS notifications enter through Host adapters. When project metadata changes, updating only the Compile Context without the monitor's scope would leave new input roots invisible to subsequent Runs.

### 3.2 Run to deploy

```text
IDE JuggRunningTask.run()
  -> JuggCompilerHelper.compile(): choose incremental or Gradle using current state and Run options
  -> IncrementalCompilerHelper / JuggCompiler or Gradle compile client
  -> DeployFileManager stages successful outputs and tracks undeployed state
  -> JuggDeployerHelper.deploy() / JuggDeployTask / JuggDeployer select install or update
  -> deploy state/history commit after the device result
```

The incremental compiler is a side path around a full Gradle build, not a replacement for Gradle's complete artifact pipeline. An ordinary incremental source error can fail the current Run without starting Gradle immediately; the fallback policy belongs to `JuggCompilerHelper`, not to a generic “failure means Gradle” rule. Device feasibility comes from shared `DeployStateManager` through `IHostDeployStateResolver`; IDEA implements the device-state read in `IdeaHostDeployStateResolver`.

`JuggRunningTask` records user-readable compile/deploy events in `JuggControlPanelModel`. `JuggControlPanelController` owns the project Panel/Model and adds Sync/app events; `JuggManager` assembles and releases it. The stable `ide_entry` UI bridge passes only `JComponent` through `IJuggManagerCaller.getJuggControlPanel(page)`, leaving Model/Event/UI DTOs in the hot-update runtime.

### 3.3 MCP request

```text
McpLocalServer / McpBaseInvoker
  -> McpToolInvoker validates the request against the registered tool schema
  -> McpToolActionRegistry dispatches the action to shared or Host-provided capability
  -> McpResultMapper returns structuredContent and protocol status
```

`tools/list`, validation, and dispatch use the same registration boundary. An action-level error can remain a successful JSON-RPC tool call with an error status in `structuredContent`; validation failures and unknown tools take the error path selected by `McpRequestValidator`/`McpToolInvoker`. `McpToolInvoker` records one request and one terminal response event without parsing raw logs. See `08_mcp_design.md` for the protocol contract.

## 4. Architectural Constraints

- Keep Android Studio API variation in `deploy_compat`; `main` sees Jugg-owned `IDevice`, `Apk`, and related `deploy.api` types. The standalone executor and IDEA adapters translate real platform objects at their respective edges.
- `main` holds shared project, compile, deploy, event, and MCP behavior; Host integrations provide IDEA or standalone capabilities. Do not make a shared owner depend on IDEA UI solely to obtain a device, prompt, or project model.
- `platform_compat/base_api` is a non-IDE compile/runtime stub, not an Android compatibility layer. Android runtime APIs belong to the actual Host or device side.
- Cross-process project leases exclude distinct Runtimes; `RuntimeTaskCoordinator` handles logical owners within one Runtime. Route blocking tasks through `TaskRunnerManager` to preserve this ownership model.
- Control-panel core events and snapshots live in `main` without Project/Swing dependencies. Leaf compilers and deployers can log diagnostics; orchestration boundaries record the user-visible timeline.

## 5. First Investigation Hop

| Question | Start with |
|---|---|
| Why did this Run use Gradle? | `02_compile_core.md` and `JuggCompilerHelper.preprocessIncrementalCompile()` |
| Why did a device install/update fail? | `03_deploy_core.md` and `JuggDeployerHelper.deploy()` |
| Why did an IDE Sync change compilation inputs? | `04_engineering_project.md` and `CompileContextManager` |
| Why was an MCP request rejected? | `08_mcp_design.md` and `McpRequestValidator` |

## 6. Related Documents

- Compilation: `02_compile_core.md`
- Deployment: `03_deploy_core.md`, `03_deploy_complete.md`
- IDEA lifecycle and compatibility: `04_engineering_ide.md`, `04_engineering_compat.md`
- MCP: `08_mcp_design.md`, `08_mcp_tools_list.md`
