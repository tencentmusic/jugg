# Jugg Architecture (AI Task Edition)

> Last verified: 2026-07-21
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page answers only three questions:
- How the system is layered
- How the main runtime flows work
- Where to begin when changing a particular kind of behavior

---

## 2. Layered Architecture (Current Code)

| Layer | Directory | Main responsibility |
|-------|-----------|---------------------|
| IDE entry layer | `idea/src/ide_entry` | Plugin loading, initialization, Run Configuration, Sync events, and a stable JComponent UI bridge |
| IDE business layer | `idea/src/main` | Compilation/deployment task orchestration, UI, and runtime policy |
| Core logic layer | `main/src/main/java/com/sickworm/intellij/jugg` | Compilation, deployment, project model, Gradle, MCP, and utility capabilities |
| Project information domain | `main/.../project/info` + `idea/.../compiler/context/IdeaProjectModelSource.kt` | Project-model source boundary, serialization/merge, and IDEA host-model reading |
| Project change domain | `main/.../project/change` + `idea/.../project/change` | File-change detection contracts, filtering, and IDE VFS/Git detectors |
| Task and lock domain | `main/.../project/runtime/TaskRunnerManager.kt`, `ExecutionLockManager.kt` | `TaskRunnerManager` serializes blocking project tasks within the same Runtime; the Project Runtime lease excludes only different Runtimes. It also manages global cross-process locks, background Jobs, completion events, and disposal; IDEA only supplies a task-display adapter |
| Compile context domain | `main/.../compiler/context` | Shared Compile Context lifecycle, full-build path overrides, and Gradle-only contexts; independent of the IDEA model API |
| Compatibility layer | `deploy_compat/*` | Android Studio version API adapters; shared deployment calls use the project's own `deploy.api` types, converting external types only at the boundary |
| Platform stub layer | `platform_compat/base_api` | Minimal IntelliJ/log4j implementation supporting non-IDE compilation and serving as a CLI runtime stub; supplies no Android runtime classes |
| Runtime layer | `jvmti_agent/src/main/cpp` | JVMTI agent and compatible-deployment support |

---

## 3. Core Flows

### 3.1 Startup and Initialization

1. `JuggLoader` / `JuggInitializer` triggers initialization.
2. `JuggManager` assembles the current IDEA-side project collaborators and handles configuration refresh, history restoration, Compile Context association, and resource cleanup.
3. `JuggManager` receives initialization, Sync, Run, UI, and MCP requests; capabilities already moved down use domain implementations in `main` directly.
4. Sync events enter `JuggManager.onSyncEvent` via `JuggGradleSyncListener`; `IdeaProjectModelSource → CompileContextManager` updates the effective model and Compile Context.

### 3.2 Main Run Flow

1. `JuggRunningTask.run` enters the unified execution chain.
2. `JuggCompilerHelper.compile` chooses “incremental or Gradle fallback.”
3. Incremental path: `IncrementalCompilerHelper` -> `JuggCompiler`.
4. Deployment path: `JuggDeployerHelper.deploy` -> `JuggDeployTask` -> `JuggDeployer`.
5. Results are written back to state and history (deploy/history/status managers).
6. `JuggRunningTask` records key compile/deploy steps in `JuggControlPanelModel`; `JuggControlPanelController` records Sync/App events and owns the project Model/Panel, while `JuggManager` only assembles and delegates thinly.

### 3.3 Main MCP Flow

1. `McpLocalServer` serves the `/jugg-mcp` HTTP endpoint.
2. `McpBaseInvoker` handles common methods such as initialize/ping/tools/list.
3. `McpToolInvoker` validates parameters and routes calls to `ai/mcp/actions/*`.
4. Business results map consistently to `structuredContent`; lifecycle events are recorded as `MCP request` / `MCP response` core events.
5. `McpToolInvoker` records both the start and one terminal event; it does not parse the raw log.

---

## 4. Key Design Choices

- **Prefer incremental work, with fallback on failure**: take the side-path incremental flow first and fall back to Gradle when needed.
- **Decouple `main` and `idea`**: `main` provides core logic, while `idea` injects platform implementations.
- **Defer the Runtime aggregate**: create a shared Runtime aggregate only after the project model, file changes, configuration, and compile/deploy orchestration have become reusable concrete domain implementations. Do not prebuild lifecycle, binder, or controller interfaces for the current single IDEA implementation.
- **Isolate device state**: shared `DeployStateManager` depends on `IHostDeployStateResolver`; `IdeaHostDeployStateResolver` reads IDEA device state.
- **Isolate compatibility**: concentrate AS version differences in `deploy_compat` to keep business logic clean.
- **Isolate deployment types**: the shared layer retains existing call surfaces such as `IDevice`, `Apk`, and `ApkEntry`, but these types belong to `com.sickworm.intellij.jugg.deploy.api`; IDEA compat and the standalone executor convert real Android types.
- **Keep the protocol cohesive**: MCP has separate layers in `main/.../ai/mcp` and is not tightly coupled to IDE UI logic.
- **Stable UI bridge**: `ide_entry` mounts the hot-update Panel only through `IJuggManagerCaller.getJuggControlPanel(page): JComponent`, without exposing the Model, Event, or UI DTO.
- **Unified event model**: `main/.../event` holds snapshots and core events without Project/Swing dependencies; leaf compilers/deployers still use logs, while orchestration boundaries record user-readable events.

---

## 5. Extension Points

- Custom compiler: `ICompilerCreator` + `CustomCompilerManager`.
- Platform capability injection: `PlatformApi`.
- New MCP tool: add a `McpToolAction` and register it in `McpToolActionRegistry`.
- New compatibility version: add the corresponding implementation to `deploy_compat` and connect it through `AsDeployerCompat`.

---

## 6. Common Investigation Entry Points

- “Why did it fall back to Gradle?”: `main/.../JuggCompilerHelper.kt`.
- “Why did deployment fail?”: `main/.../JuggDeployerHelper.kt`.
- “Why did a class hot update fail?”: `idea/.../deploy/run/applychanges/JuggDeployer.kt` + `main/.../runtime/jvmti/*`.
- “Why is an MCP parameter invalid?”: `main/.../ai/mcp/McpRequestValidator.kt`.

---

## 7. Related Documents

- Compilation: `02_compile_core.md`
- Deployment: `03_deploy_core.md`, `03_deploy_complete.md`
- IDE: `04_engineering_ide.md`
- MCP: `08_mcp_design.md`, `08_mcp_tools_list.md`
