# Engineering: IDE Plugin Layer

> Last checked: 2026-09-08
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Scope

This page describes how the IDE starts Jugg, maintains project/compile/deploy contexts, and routes Run, androidTest, MCP, and tool entry points into shared task orchestration.

For compilation stages, deployment state machine, MCP tool schemas, and hook scripts, see `02_compile_core.md`, `03_deploy_complete.md`, `08_mcp_design.md`, and `08_cli_tools_list.md`, respectively.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `JuggInitializer` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggInitializer.kt` | Registers and releases project-level plugin instances, forwards Sync events, and owns the MCP local-server lifecycle. |
| `JuggProjectManagerListener` / `JuggGradleSyncListener` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/` | After project opening, subscribes once to old `GradleSyncListener` semantics through `GradleSyncState`, bound to project disposable. |
| `JuggLoader` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggLoader.kt` | Loads Jugg manager in isolation, supporting hot update and embedded-JAR fallback. |
| `JuggManagerCreator` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/JuggManagerCreator.kt` | Sets `PlatformApi.impl`, registers project logging, and creates/releases `JuggManager`. |
| `JuggManager` | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | IDEA project coordination entry. Injects IDEA runtime metadata; handles configuration refresh, history recovery, Compile Context binding, monitor wiring, Run/UI/MCP, and cleanup. File changes and control plane are delegated to shared managers. |
| `FileChangeManager` / `IdeaFileChangeMonitor` | `main/.../project/change/FileChangeManager.kt`, `idea/.../project/change/IdeaFileChangeMonitor.kt` | Shared changed/deleted/build-file/Git/pending-barrier processing; IDEA only adapts VFS events to monitor contract. |
| `CompileUiHandler` / `JuggCompileUiHandler` | `main/.../compiler/CompileUiHandler.kt`, `idea/.../compiler/JuggCompileUiHandler.kt` | Host interaction boundary for compilation. IDEA reuses the dependency dialog; manager only applies its confirmed result. |
| `HostTaskExecutor` | `idea/src/main/java/com/sickworm/intellij/jugg/runtime/HostTaskExecutor.kt` | IDEA execution adapter for `TaskRunnerManager`, associating `Task.Backgroundable`, ProgressIndicator, and EDT state. |
| `DeployStateManager` / `IdeaHostDeployStateResolver` | `main/.../deploy/DeployStateManager.kt`, `idea/.../deploy/IdeaHostDeployStateResolver.kt` | Shared deployment-state computation with isolated Android Studio device-state reads. |
| `JuggRunningTask` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | Background task after Run button, joining compilation, deployment, state writeback, and Run tool window. |
| `JuggDebugProgramRunner` / `JuggDebugSessionManager` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggDebugProgramRunner.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` | Takes over Jugg + Debug executor. Jugg compile/deploy output stays in Run tool window; after success it enforces one device and attaches Java debugger through compatibility layer. |
| `JuggConfigurationRunner` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt` | Creates and runs `JuggRunningTask`, tracking compilation and a forced reinstall on the next round. |
| `RemoteCommandRunner` / `RemoteCommandDialog` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/RemoteCommandRunner.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/RemoteCommandDialog.kt` | Runs a noninteractive command against the currently selected remote Jugg Configuration and streams output in separate Run Content. |
| `JuggCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | Shared IDEA/standalone decision between incremental compilation and Gradle fallback, and compilation entry. |
| `JuggDeployerHelper` / `IdeaDeployEnvironment` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/IdeaDeployEnvironment.kt` | Shared helper selects deployment path; IDEA Host environment provides device, ADB, prompts, debugger, and AndroidTest UI. |
| `JuggControlPanelHost` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggControlPanelHost.kt` | Tool Window host in stable ClassLoader that retains only `JComponent`; obtains hot-updated implementation through `JuggInitializer.getManager(project)`. |
| `JuggControlPanelModel` / `JuggEvent` | `main/src/main/java/com/sickworm/intellij/jugg/ide/controlpanel/` | Project facts, task state, and structured core events without Project/Swing dependencies. Only two entry classes are public; projections and enums are nested for IDE, MCP, and future CLI reuse. |
| `JuggControlPanelController` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` | Project-level owner of Model/Panel in hot-update layer; refreshes IDE facts, coordinates Sync/App events and Panel actions, and clears stable Host on manager disposal. |
| `CompileContextManager` / `IProjectModelSource` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/context/CompileContextManager.kt`, `main/src/main/java/com/sickworm/intellij/jugg/project/info/ProjectModelSource.kt` | Shared effective project model and Compile Context lifecycle. |
| `IdeaProjectModelSource` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/IdeaProjectModelSource.kt` | IDEA module/JDK/source-root reads and merge inputs for IDE + Gradle project info. |
| `IdeaCompileEnvironmentSource` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/IdeaCompileEnvironmentSource.kt` | Reads current Android SDK and Gradle environment when creating Compile Context or running a local Gradle fetch. |
| `IdeaCliRunConfigurationManager` | `idea/src/main/java/com/sickworm/intellij/jugg/project/runtime/IdeaCliRunConfigurationManager.kt` | Creates IDEA Jugg Run Configurations from Android model suggestions as an independent source, deduplicates by Gradle task, imports shared CLI configurations one by one on a best-effort basis, and maintains stable IDs, current pointer, and actual settings after successful Gradle builds. |
| `JuggControlPanel` / `JuggToolWindowFactory` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/` | Creates the right-side `Jugg Running Panel` Tool Window only when a valid Jugg Run Configuration exists. Overview/Logs/Settings share one Panel instance; Run Configuration `More options` opens Settings directly. |

---

## 3. Core State Model

| State | Owner | Lifecycle |
|---|---|---|
| `instanceSet` | `JuggInitializer` | Maps project basePath to `JuggLoader`; stops `McpLocalServer` after the last project is released. |
| `JuggPathManager` | `JuggManagerCreator` / `JuggManager` | Project root for `build/jugg` paths, logs, database, classpath, and MCP fetch cache. |
| `CompileContext` | `CompileContextManager` | Rebuilt after Gradle/project-info updates; consumed by compiler, deploy-file manager, and custom compilers. |
| Effective project model | `CompileContextManager` | In-memory merger of source model and module custom classpath; currently persists no additional identity state. |
| Deploy history/state | `DeployHistoryManager` / `DeployStateManager` | Initialized after a full build, committed after successful incremental deployment, recoverable from history at startup. |
| hasRun / selected devices | `JuggRunningTaskStatusManager` | Controls “first run,” resetting after stop/cancel, and hook/status semantics. |
| Run UI process handler | `CompileUiHandler` / `JuggRunningTask` | Carries logs, progress, and cancellation state; androidTest connects a Test Results console. |
| File-change / Run Configuration locks | `JuggManager` | File-change handling and Run Configuration creation are serialized separately; do not use the `JuggManager` instance lock to block unrelated domains. |
| Control-panel snapshot | `JuggControlPanelModel` | Project-level `JuggControlPanelController` retains pending files, current phase, raw compile/deploy facts, session success statistics, bounded Recent Runs, and latest 200 core events. MCP, Sync, and App events enter event history only, without replacing a running task. |
| CLI Run Configuration collection | `CliRunConfigurationStore` / `IdeaCliRunConfigurationManager` | `build/jugg/config/run_configurations/<id>.json` stores independent configurations; `current_run_configuration.json` stores current UUID. IDEA configuration persists the same stable ID. `CliRunConfiguration` additively stores `remoteSyncExcludePatterns` and `isRemoteSyncExcludePatternsCustomized`; old schema-version-1 JSON lacking them is read as not customized. |

---
## 4. Core Call Chain

### 4.1 Plugin Initialization and Project-Context Recovery

```text
IDE project opened
  -> JuggProjectManagerListener.projectOpened(project)
     -> JuggInitializer.init(project)
        create JuggLoader, register in instanceSet, start McpLocalServer
     -> reflectively call GradleSyncState.subscribe(project, JuggGradleSyncListener, project)
        subscribe once per project lifecycle; disconnect on project disposal
  -> JuggManagerCreator.create()
     set IdeaPlatformApi, create JuggPathManager, register JuggLogger
  -> JuggManager.init()
     create IDEA RuntimeInfo; first Init Jugg background task converts and migrates old PropertiesComponent fields, retrying next startup on failure; explicitly initialize Host-neutral JuggServer; load settings on first access
     refresh custom config through ProjectCustomConfigManager; initialize AsDeployerCompat, min API, project info, and history directory; with existing non-default Jugg Run Configuration, mark ready and import shared profiles one by one; otherwise create from Android model suggestion if available, without ProjectInfo fallback at startup
  -> JuggManager.recoverDeployContext()
     restore compile context, APKs, and changed files from deployment history to avoid an unnecessary full build
  -> background tasks
     preinitialize deployment service and check updates; if CLI installed, first refresh IDEA-managed standalone runtime according to embedded tooling build, then auto-update CLI/skills; MCP fetch cleanup stays a normal background task
```

`recoverDeployContext()` applies only with recoverable deployment history. Without history, prompt for Gradle/full compilation rather than fabricating incremental context.

`JuggCliAutoUpdater` runs only when `~/.jugg/bin` exists. It compares `version:` of bundled `docs-skills.zip` and `~/.jugg/skills/jugg-android-dev-loop/SKILL.md`, replacing CLI and installed skill only when bundled version is higher. The trigger is `SKILL.md` version, not `CLI_VERSION`. See `08_cli_tools_list.md` §3.7 for change rules.

Plugin hot update depends on a deliberately narrow ClassLoader boundary. `JuggLoader` chooses embedded or hot-update JARs by load list and proxies `IJuggManagerCreator` / `IJuggManagerCaller` calls back across the stable ClassLoader. If creating a hot-updated instance fails, it falls back immediately to embedded JARs so the project still opens. `loader`, `ide`, IntelliJ API, and a few cross-boundary DTOs stay under the original ClassLoader to preserve class identity of registered IDE extensions/actions. `JuggManagerCreator` is the exception loaded by the hot-update ClassLoader, so main business behavior can change.

Update downloads use two channels: hot load and standard installation. Download only missing JARs, verify md5 individually, and replace metadata through temporary files once all files exist. For a compatible hot update, then switch the load list so subsequently opened/reopened projects use the new ClassLoader. Whether or not hot update is possible, bundle JARs as a plugin ZIP and reflectively probe both known signatures of `PluginInstaller.installAfterRestart()` to install the standard version on the next IDE start without static linkage to an internal API. If the server requires reinstall, do not update the load list; use cold install only. Hot update does not replace classes inside the current manager and cannot make new methods/types on the stable boundary take effect automatically.

Current Compile Context consumers are rebound by `JuggManager` in this order: `DeployFileManager → JuggCompiler → FileChangesHandler → FileChangeManager/GitFileChangesDetector → CustomCompilerManager`. `JuggManager.dispose()` closes the local Gradle project-info executor and releases the custom-compiler classloader, deploy-file runtime, TaskRunner, and coroutine scope.

`CompileContextManager` and `GradleProjectInfoLocalFetchManager` now live in `main`. IDEA supplies host model through `IdeaProjectModelSource` and reads Android SDK/Gradle environment at use time through `IdeaCompileEnvironmentSource`. Local Gradle project-info fetch retains project locking, background-task, and progress semantics through shared `TaskRunnerManager` rather than owning IDEA `Project`.

`DeployFileManager` may construct a `ConstRefEngine` object immediately, but the `ConstRefEngine` constructor must not initialize SQLite database, repo fingerprint store, or impact resolver; corrupted global SQLite cache must not prevent manager creation. `ConstRefEngine` lazily initializes these runtime resources when first needed for `updateModuleInfos()`, source-change events, precompile readiness, on-demand analysis, impact query, or commit acknowledgment. Failure degrades ConstRef to no-op; main initialization, compilation, and deployment continue.

After `CompileContext` initialization, `FileChangesHandler` scans directories within the IDE project directory and roots of all participating compilation modules. Before `listFiles()` on a directory event, it checks whether the directory has an ancestor/descendant relationship with that scope. Unrelated global directories are not recursively expanded, while compilation modules outside the project directory remain discoverable along their parent branches. For each module, restore its actual local build directory from `ModuleInfo.projectRootDir/moduleRootDir` and `buildDirRelativePath`, and exclude both it and conventional `${moduleRootDir}/build`. Do not use remote Compile Context `buildPathInfo.buildDir`, which may map to a classpath-backup directory. Prune directory events before recursion and filter ordinary changed files before type recognition. Delete events only remove previously registered paths and do not repeat this filter. This boundary works whether or not build directory lies within module root, and does not retroactively clean changes already in memory.

### 4.2 From Gradle Sync to Context Rebuild

After project opening, `JuggProjectManagerListener` calls three-argument `GradleSyncState.subscribe`, registering just one `JuggGradleSyncListener` bound to project disposable. This static entry exists in 211; Android Studio internally forwards it to the root-aware topic on 221+. Do not register both topics or report one event twice. Because `GradleSyncState` changes class/interface shape within supported versions, invoke it reflectively so published bytecode does not directly link the type.

```text
JuggGradleSyncListener
  -> JuggInitializer.onSyncEvent(project, syncEvent)
  -> JuggManager.onSyncEvent()
     SUCCEEDED: updateProjectInfo(isAfterSync = true), then tryCreateRunConfigurations(isSyncFinished = true)
     SKIPPED: updateProjectInfo(isAfterSync = false), then same creation/reconciliation entry
     STARTED/FAILED: notify dependencyChangeManager
  -> CompileContextManager.updateCompileContext()
  -> IdeaProjectModelSource + JuggProjectInfoMerger
  -> GradleProjectInfoLocalFetchManager.runUpdateIfNeeded()
  -> JuggManager.rebindCompileContext()
     update DeployFileManager, JuggCompiler, FileChangesHandler, FileChangeManager/GitFileChangesDetector, CustomCompilerManager
```

Successful Sync resets hasRun so stale run state cannot contaminate the next “no file changes” decision.

After Sync succeeds or IDE marks it `SKIPPED`, update effective `JuggProjectInfo` first. Then read the latest Android model suggestions for ordinary Android Run Configurations and perform import, creation, and Active Build Variant selection under one project write lock.

Suggestion is an independent creation source. `IdeaCliRunConfigurationManager.reconcileActiveBuildVariants()` imports existing Jugg configurations one by one, then creates a standard `assembleVariant` configuration for each resolvable suggestion. A suggestion must resolve exactly to one task `./gradlew :modulePath:assemble{Variant}`, with valid module path and command variant matching nonempty `variantName`; otherwise skip it without inventing stable identity. Deduplicate by normalized task only when both sides uniquely identify one Gradle task (`assembleDebug --offline` equals a standard suggestion; `deployDebug` / `uploadDebug` do not). If either side has multiple tasks, cannot be identified uniquely, or uses unsupported format, compare entire commands exactly. If a suggestion-generated stable ID is held by an existing configuration whose command differs from the exact standard command, skip creation and retain that configuration and shared Store. Name the first module configuration `jugg:<module>`; when the base name is already used in that module, use `jugg:<module>:<variant>`, then resolve conflicts through `RunManager.suggestUniqueName()`. IDEA name and shared Store `CliRunConfiguration.name` must match.

Selection consumes only the resulting IDEA settings and suggestions, without traversing project info to complete configurations or writing suggestions back to CompileContext. Switch only if selected and suggestion commands both exactly follow the generated single-task form, their complete Gradle module paths match, suggestion command matches `variantName`, and that module path has exactly one suggestion. Parse module path and variant directly from command, so a dot within `:zxphone5.0` is not mistaken for hierarchy. An existing custom target whose command is not the target's standard generated command vetoes switching, even if a standard suggestion configuration was just created. Otherwise prefer a target with stable configuration ID and exact command, then a unique legacy configuration exactly matching suggestion command and APK output. Create a stable target only if neither exists. Additional Gradle arguments, multiple tasks, custom `deployDebug` / `packageDebug` / `uploadDebug` / `happyBuild`, a same-variant custom target alone, or missing/conflicting suggestions all preserve user selection. Missing a switch is acceptable; do not infer user intent from simple module name or task suffix. Ordinary Android Run Configuration supplies only current active variant and full Gradle module identity at Sync; it is not imported into shared CLI profiles.

Import and creation are isolated per configuration. When identity of one configuration cannot be established, skip only its shared import; keep it runnable in IDEA and continue others. Identity resolution does not throw and degrades through exact standard command successfully built this run, confirmed identity in current/historical shared configurations, then available ProjectInfo. With no confirming source, do not write shared Store or invent module/variant. Thus even if every project-info module has `moduleType=Unknown`, standard generated commands still permit import and writeback.

If all suggestions are unavailable, there are no non-default Jugg configurations, and project info identifies an application module, `ensureFallbackConfiguration()` creates one deterministic ProjectInfo fallback. If no runnable configuration remains, use existing exponential-backoff retry up to seven times, rereading suggestions and project info each time. Success means RunManager holds a non-default Jugg configuration; make Jugg Tool Window available then.

Generate suggestion APK-output pattern from Android Studio Android model's actual build folder, supporting `${moduleDir}/build` and centralized project-root `build/${moduleName}`. It is used only for new Jugg Configurations. Sync must not change an existing configuration's APK-output pattern or delete/overwrite its command, APK output, or remote fields.

Suggestion-created configurations use its complete Gradle module path, variant, and APK output directly. Only ProjectInfo fallback uses `moduleStdPath + buildVariant`. Preserve original segments in Android model Gradle paths; dotted module names are not split into hierarchy, and included builds retain build identity.

`IdeaFileChangeMonitor` converts IDEA VFS events into changed/delete batches for `FileChangeManager`. The shared manager serializes deploy-file and dependency-state updates with a Runtime-instance lock. Batch handling itself holds no project write lock, but background writes for added/deleted `source_files.db` entries submitted by `DeployFileManager` must enter Project Runtime Lock so an old Runtime cannot keep writing the project database after owner switch. `DeployStateManager.beginFileProcessing/endFileProcessing` prevents compilation from racing ahead of event persistence. Git checkout/pull compensation detection also lives in `main`. Compile-on-save setting reads and final compile calls remain temporarily in `JuggManager`; shared manager reports only whether this batch has a valid change.

### 4.3 Run Through Compilation and Deployment

```text
JuggRunConfiguration / JuggAndroidTestRunConfiguration
  -> JuggManager.runTask(options, executor, runProfile, androidTestRunSpec)
  -> JuggConfigurationRunner.runTask()
  -> JuggRunningTask.run()
     refresh custom config inside background Run Jugg project write transaction; Run entry schedules background task without waiting for Project Runtime Lock on EDT
     dependency start, Run tool-window state, JuggLogger listener, server report, structured task event
  -> JuggCompilerHelper.compile()
     incremental or Gradle fallback
  -> JuggDeployerHelper.deploy()
     deploy devices one by one and aggregate deploy type and fallback eligibility
  -> compileUiHandler.onEnd()
     write back hasRun, stop log listener, update UI
```

One Run uses a unique taskId. Compile, each device Deploy, fallback, cancellation, exceptions, and aggregate terminal result enter the same event system. `JuggControlPanelModel` accepts only one terminal state; Current Task, Timeline, Last Deploy, Recent Activity, and Logs maintain no second task state.

For androidTest, pass `androidTestRunSpec`, `executor`, and `runProfile` together to `JuggManager.runTask()` or Test Results console, source navigation, and rerun-failed support cannot be fully connected.

Debug executor covers ordinary Jugg RunConfiguration only, not androidTest. Debug first reuses Jugg's compile/deploy path. `JuggManager.runTask()` sets `isAlwaysRestartApp=true` and `isDebugRun=true`; after deployment it restarts with `am start -D -S` so the app waits for debugger during startup. Compatibility layer then asks Android Studio's native attach flow to create/activate `XDebugSession`. See `04_engineering_debug_attach.md` for full state model, AS internal API boundary, and breakpoint investigation.

---

## 5. UI and Tool Entry Points

- IDEA configuration discovery has two sources. `SuggestRunConfiguration` carries full Android model Gradle identity and current active variant as independent input for creation/switching. `CliRunConfigurationGenerator` infers a single fallback from Gradle project info only when no usable suggestion exists (prefer an application module named `app`, otherwise stable ordering; use current `buildVariant`, default `debug`). Startup does not use ProjectInfo fallback, avoiding premature configurations with wrong module path or stale variant. IDEA imports Jugg Run Configurations only. Existing profiles import one by one on a best-effort basis and update the current pointer; later selection/edit events continue syncing under project lock so a stable ID does not retain parameters from the previous IDE exit.
- IDEA Runtime CLI/MCP Gradle calls prefer the currently selected Jugg Run Configuration. If none is selected, fall back in order to exact command + target from the latest successful full build, exact command, then first configuration in the list. Once Gradle build succeeds and APK is confirmed, write back actual task, APK pattern, remote fields (including `isRemoteSyncExcludePatternsCustomized`), and current pointer. Choose writeback baseline from current pointer, selected configuration, then ProjectInfo single-configuration fallback. If none is available, skip writeback without throwing or inventing identity.
- Run Configuration `More options` saves configuration and opens `Jugg Running Panel` Settings. The stable bridge retains a compatibility method returning an empty ActionGroup; it no longer creates the old dropdown.
- Stable layer of `Jugg Running Panel` creates only `JuggControlPanelHost`. Through `IJuggManagerCaller.getJuggControlPanel(page): JComponent`, Host mounts the actual Panel created by current Jugg ClassLoader. Model, Snapshot, Event, Controller, and concrete Panel types stay outside `ide_entry` bridge interfaces, letting fields and UI change under a new ClassLoader.
- `OpenJuggControlPanelAction` lives in `ide_entry` and calls only Host. `JuggInitializer` does not reference Host. On manager disposal, Controller clears Host; `JuggManager` itself retains no Panel, event enum, or Sync taskId.
- Overview is the compilation cockpit, always showing Run Status, Changed Files, Quick Actions grouped by Build / Device / Jugg Plugin, This Session, and Recent Runs. After preprocessing determines the actual compile path, `JuggCompilerHelper` emits a domain notification through `CompileUiHandler.onCompileStarted()`; `JuggRunningTask` projects it into Control Panel events and captures the undeployed-input snapshot. Do not record an uninformative `Jugg task started`. A task enters Recent Runs only after terminal state. Structured events convey raw compile mode, deploy type, terminal category, fallback, and phase durations; Panel only maps them for display. Compile events distinguish started/completed/failed/canceled for `Incremental compile` and `Gradle compile`; an incremental run without actual compilation displays `No compile needed`. Each Recent Runs row always shows compile mode, final result, total duration, and status, with specific result text for compile-only, compile failure, deployment failure, and no device; successful deployment shows actual deploy type. Selecting a row reveals Compile / Deploy / Total phase detail. Changed Files and Recent Runs use native IDE selectable lists; double-clicking a changed file opens it. A Swing Timer refreshes elapsed run time each second without writing Model.
- Logs displays only structured core events from Sync, compile, deploy, app, user actions, CLI/MCP, and similar sources; it does not read or poll `compile_latest.log`. Source filter `ALL` shows every event by default, `IDE` keeps only `source=IDE`, and `CLI / MCP` filters by CLI/MCP source or category. Level dropdown, current-task and Follow checkboxes, and search compose with source filtering. Log list supports multiselection and platform copy shortcuts.
- MCP lifecycle always records `MCP request` / `MCP response`. Request detail retains concrete arguments after removing `projectDir`; response detail retains status/message/data/artifacts/errorCode. Panel content recursively removes `projectDir`, redacts sensitive fields, and enforces a maximum length.
- Model retains Context/Health facts such as Run Configuration, selected devices, package, changed files, baseline, and deployment history; Overview does not display a context summary. Settings uses native groups, checkboxes, and text actions. Quick deploy, Embed APK, Project Kotlin, and per-device compat appear only with Gradle injection enabled; Backup classpath appears only when available in the current environment. Embed APK and Backup classpath keep confirmation; successful Backup classpath toggling deletes deployment history.
- Settings Deployment lists forced compat deployment dynamically by connected device and refreshes device list on every entry or reopen. Integrations offers custom server URL; Advanced retains mark synced / mark Gradle compiled test actions. These entries reuse project Manager and Controller rather than independent menu state.
- Overview Quick Actions are grouped by Build, Device, and Jugg Plugin. Quick Actions, Settings text actions, setting switches, and confirmed `Clear app data` record User Action events; browsing Tab, log filters, and list selections do not. `Clear app data` uses the common confirmation dialog and only then clears app data, runs a full Gradle build, and reinstalls. `Clear Jugg Build` keeps its existing behavior of clearing project Jugg build data and reinitializing the project.
- `Exec remote CMD` at the bottom of Build Quick Actions accepts only the currently selected remote Jugg Configuration; it does not fall back to full-build history or the first configuration. The dialog shows SSH target and `remoteProjectPath`. Empty command merely disables Run without an error. Users can select and refill from the target's latest 10 commands; `JuggSettings` isolates history by `user + host + port + remoteProjectPath`. Execution creates separate `Jugg Remote Command` Run Content, ProcessHandler, and SSH client; it does not enter `JuggConfigurationRunner` / `JuggRunningTask`. Stop cancels only this command and closes Run Content with nonzero status after background cancellation confirmation.
- `MockJuggControlPanelModel` constructs test scenarios only through real Model APIs. Panel uses the same subscription/render path for real and mock models, without embedded UI `MockData`.
- `JuggToolWindowFactory` and `OpenJuggControlPanelAction` implement `DumbAware`; Panel does not depend on indexes and can be created/opened during IDE indexing/dumb mode.
- Run Configuration keeps the name `More options`; clicking activates `Jugg Running Panel` on Settings. Global compat deployment is always enabled with no switch; Settings retains only per-device forced compat. Independent Tools-menu actions still open from Overview.
- `Check Jugg Update` action and Settings both call `JuggManager.checkUpdates()`. If update request has no valid backend response, prompt for Custom Server; show “already latest” only when server explicitly returns `isNeedUpdate=false`. On update initiated from Run Configuration, close both update dialog and outer Run Configuration before `Reopen IDE` / `Reopen projects` to avoid modal windows blocking reopening.
- Shared `JuggHotUpdateManager` handles hot-update downloads, MD5 verification, metadata/load-manifest publishing, and stale cleanup. Only actual IDEA hot-update publishing writes `load_manifest.json`; built-in IDEA JARs and standalone CLI installation do not. `IdeaHotUpdateCoordinator` retains IDEA scheduled checks, rate limiting, notifications, plugin install/restart, and project reopen. Before Loader creates a hot-update ClassLoader, `JuggHotUpdateBootstrap` reads manifest lock-free; its cross-ClassLoader API exposes JDK platform types only and must not return hot-update Runtime DTOs.
- `Set custom server URL` is reused by `JuggManager` and Control Panel Controller, delegating to `JuggServerChooser` for a remote-capability trust confirmation before saving a nonempty URL. Canceling confirmation leaves original configuration; an empty URL restores the default server directly. `Clean and reset Jugg` retains direct deletion of project state followed by project reopen.
- `Install Jugg Skills` invokes `JuggSkillInstaller` from `InstallJuggSkillsDialog` to install bundled skills, CLI, and hooks. Before CLI or hooks installation, detect Python 3.7+ (`python3` first, `python` fallback); without it, write no CLI or hook configuration. After successful Claude-hook installation, if a CC Switch configuration directory exists, prompt to export Common Config JSON when install result closes. There is no separate CC Switch install option and no direct CC Switch configuration edit. Selecting Codex skill additionally writes a Jugg CLI `prefix_rule` through `CodexPermissionRuleInstaller` to `rules/default.rules` under Codex home (`CODEX_HOME` preferred, otherwise `~/.codex`) so local-port probes do not repeatedly seek elevation. Install log records rules file, prefix, and installed/already_installed/fail status. On completion, export `~/.jugg/skills/install/agent_setup.md`. For hook/CLI details, use `docs/skills` and `08_cli_tools_list.md`.
- Every manual installation of the bundled standalone Bundle replaces active runtime, allowing version downgrade or channel switch, and makes `~/.jugg/bin/jugg.py` executable. IDEA startup background tasks auto-refresh only installed `managedBy=idea` runtime, using `toolingReleaseBuildId` to detect embedded tooling changes. Compatible hot updates on unchanged tooling and `managedBy=external` runtime remain untouched. Auto-refresh failure only warns and does not block CLI/skills updates; manual-install failure preserves process output and opens an `Install Failed` error window.
- CLI/MCP/RPC read current IDE selection, Jugg configuration list, and options on EDT. Prefer selected Jugg configuration. If unavailable or not Jugg, try exact `compileCommand + buildTarget` from latest successful Gradle full build, then exact `compileCommand`, then first listed Jugg configuration. Use the first match at a tier. Final first-item fallback logs `warn`, plus concise `debug` details for selected, full build, resolution source, and chosen configuration. Runtime creates corresponding Run Content without activating Run tool window by default; show it explicitly when a failure or other event needs user attention.
- `reportIssue()` displays modal progress while preparing and uploading diagnostics. After creating redacted allowlisted candidates, the confirmation explains that runtime logs are redacted for issue analysis. It shows paths and KB/MB sizes for the 10 newest Jugg logs, merged by modification time from IDEA `log/` and standalone `log/standlone_cli/`; all selected by default, with Jugg logs pinned and selection locked. IDE, Gradle, and current included-build project-info snapshots are default-selected but removable high-sensitivity candidates; after structured redaction they go under `diagnostics/project-info/`. Uploaded standalone logs retain `diagnostics/logs/standlone_cli/` hierarchy. Upload button reads `Upload logs`; save-only changes it to `Create Diagnostics Bundle`, then selects the ZIP in the system file manager. Report ID stays eight lowercase hexadecimal characters. Upload always goes to `https://jugg.sickworm.com/report_issue`; do not display or persist the upload URL. The result page does not show temporary ZIP path. Files under `build/jugg/tmp/diagnostics` at least seven days old are cleaned separately during delayed cleanup after project startup.

---
## 6. Investigation Entry Points

| Symptom | Start with |
|---|---|
| No manager after plugin initialization | `JuggInitializer.instanceSet`, `JuggLoader`, `JuggManagerCreator.create()`. |
| Long stall during startup | `09_plugin_runtime_debug.md`, then `JuggManager.init()` background tasks and `ConstRefEngine` startup scan. |
| SQLite corruption during startup | `ConstRefEngine` constructor should not initialize SQLite runtime; inspect `ConstRefCacheDatabase` rebuild and no-op fallback logs. |
| Default Run Configuration absent or current pointer wrong | `JuggManager.tryCreateRunConfigurations()`, `IdeaCliRunConfigurationManager`, `build/jugg/config/run_configurations/`, and `current_run_configuration.json`. |
| Project info/dependency state wrong after Sync | `JuggManager.onSyncEvent()`, `updateProjectInfo()`, `CompileContextManager.updateCompileContext()`. |
| Run UI state confused, or next run misjudged after cancellation | hasRun/processHandler/logger-listener cleanup in `JuggRunningTask.run()` finally. |
| Panel data does not refresh, or old component remains after hot update | `JuggControlPanelHost`, `JuggInitializer.getManager(project)`, `JuggManager.getJuggControlPanel()`, and Panel subscription disposal. |
| Current project still runs old implementation after downloading update | Current manager does not swap ClassLoader in place. Reopen project; restart IDE if update requires reinstall. |
| Panel Logs unreadable or missing events | Structured-event producers in `JuggManager.onSyncEvent()`, `JuggRunningTask`, and `McpToolInvoker`; Panel must not read raw logs. |
| Breakpoints fail after Jugg Debug attach | `04_engineering_debug_attach.md`; verify WAITING, `Connected to the target VM`, and `XDebugSession`. |
| androidTest has results but incomplete Test Results | Check `JuggManager.runTask()` arguments: `executor`, `runProfile`, and `androidTestRunSpec` must all be nonnull. |
| Skill/hook installation entry fails | `JuggControlPanelController`, `InstallJuggSkillsDialog`, `JuggSkillInstaller`. |
| CLI/skill remains old after plugin update | `JuggCliAutoUpdater` and bundled `SKILL.md` version; see `08_cli_tools_list.md` §3.7. |
| MCP local server does not start/stop | `McpLocalServer.start()` / `stop()` calls in `JuggInitializer.init()` / `release()`. |

---

## 7. Related Documents

- Architecture: `01_architecture.md`
- Project model: `04_engineering_project.md`
- Compatibility layer: `04_engineering_compat.md`
- Jugg Debug attach: `04_engineering_debug_attach.md`
- Deployment flow: `03_deploy_complete.md`
- Plugin runtime investigation: `09_plugin_runtime_debug.md`
- MCP: `08_mcp_design.md`, `08_mcp_tools_list.md`
- CLI/skill auto-refresh: `08_cli_tools_list.md` §3.7
