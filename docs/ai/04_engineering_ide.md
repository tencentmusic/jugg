# Engineering: IDE Plugin Layer

> Last checked: 2026-10-07
> If this page conflicts with implementation, follow the code.

## Scope and entry points

The stable IDE layer owns project and Android Studio extension lifecycles. `JuggInitializer.init()` registers one `JuggLoader` per project and stops the local MCP server after the last project closes. `JuggLoader` isolates the replaceable implementation; `JuggManagerCreator.create()` constructs `JuggManager`. Its `init()` sets up project state, and `recoverDeployContext()` restores a usable baseline from history when available. Start with `idea/src/ide_entry/java/com/sickworm/intellij/jugg/loader/` and `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`.

Shared compile, deploy, project-model, and test contracts live in `02_compile_core.md`, `03_deploy_complete.md`, `04_engineering_project.md`, and `06_testing.md`. The Debug executor's attach lifecycle is in `04_engineering_debug_attach.md`. MCP and standalone entry points are in `08_mcp_design.md` and `08_cli_tools_list.md`.

## Project lifecycle and recovery

`project open → JuggInitializer / JuggLoader → JuggManager.init() → settings and host initialization → Run Configuration import/creation → deploy-history recovery → file monitoring and background work`.

`JuggManager.recoverDeployContextFromDisk()` needs recoverable deployment history. Without it, incremental compilation remains uninitialized and a full Gradle build is required. After an IDEA/standalone Runtime owner change, Jugg reloads the source project model and history, invalidates the deployment cache, refreshes Git changes, and clears stale compiler/APK state when recovery fails. Rebinding a Compile Context updates deploy files, compiler, file handlers/monitor, and custom compilers together. `ConstRefEngine` initializes SQLite and impact-analysis resources lazily; failure there degrades ConstRef without blocking manager creation.

The hot-update ClassLoader boundary keeps IDE extensions and bridge DTOs stable while loading `JuggManagerCreator` and business logic from selected JARs. Failed hot load falls back to embedded JARs. Publishing a verified load manifest affects subsequent project opens/reopens, never an already-created manager. Standard plugin installation remains a restart path. `JuggControlPanelHost` holds only a `JComponent` and fetches the current implementation from `JuggInitializer`, avoiding implementation types in the stable layer.

## Sync, host environment, and file changes

`JuggProjectManagerListener` registers one reflective, three-argument `GradleSyncState.subscribe` listener per project, bound to disposal. This preserves the 211 entry point when newer Android Studio forwards it to root-aware topics; subscribing to both duplicates events. `JuggManager.onSyncEvent()` handles `SUCCEEDED` by refreshing project info, resetting `hasRun`, and reconciling Run Configurations; `SKIPPED` reconciles without treating Sync as successful. `STARTED` and `FAILED` update dependency-sync state. `CompileContextManager.updateCompileContext()` merges the `IdeaProjectModelSource` host model with distinct Gradle snapshots before `JuggManager.rebindCompileContext()` reconnects consumers.

`IdeaCompileEnvironmentSource` reads the current Android SDK and compile environment when a context or local Gradle fetch needs them. `IdeaPlatformApi` loads IntelliJ's shell environment, including `PATH` for GUI launches, and falls back to the IDE process environment on failure. `JAVA_HOME` prefers the linked project's configured Gradle JVM resolved to a real path, then a Java SDK from the root or another module, then process `JAVA_HOME`; an Android SDK is not a Java SDK. `ANDROID_HOME` prefers the IDE Android SDK. The same host environment is used for the local project-info dry run before remote compilation. Final assembly is in `LocalGradleCompileClient.buildCompileEnv()`; wrapper repair is a shared compile preflight described in `02_compile_core.md`.

`IdeaFileChangeMonitor` converts VFS events into shared `FileChangeManager` batches. The manager serializes deployment-file and dependency updates per Runtime; `DeployStateManager` blocks compilation while a batch is persisted. Background `source_files.db` writes must enter the Project Runtime Lock so a displaced Runtime cannot continue writing project state. Directory events are pruned against the project and participating module roots before recursion, including modules outside the IDE project directory. Local build directories are excluded using module paths, not remote Compile Context build paths. Git checkout/pull compensation lives in the shared manager; IDE still selects compile-on-save and makes the final compile call.

## Run Configuration reconciliation

`IdeaCliRunConfigurationManager` bridges IDEA Jugg Run Configurations and shared `CliRunConfigurationStore`. Startup imports existing non-default IDEA configurations by stable UUID and can create Android-model suggestions; it does not create a ProjectInfo fallback before Sync. After `SUCCEEDED` or `SKIPPED`, it refreshes project info, imports each configuration independently, creates missing suggestion targets, and may create one deterministic ProjectInfo fallback. If no runnable configuration exists, `JuggManager` retries with bounded exponential backoff. Selection/edit listeners save current IDEA settings and pointer, so rename or remote-field changes do not leave an old shared snapshot.

A suggestion is usable only when a nonempty variant matches one unambiguous generated `./gradlew :modulePath:assembleVariant` task. Full module paths preserve dotted names and included-build identity. Deduplication uses a normalized task only when both commands identify one; ambiguous or custom commands require exact equality. A stable ID occupied by another command is never overwritten. New APK patterns use the Android model's actual build folder, including centralized project-root outputs; Sync does not rewrite existing command, APK, or remote settings.

Changing IDEA's active variant can change Jugg selection only when current and suggested commands have exact generated identities for the same full module path, the suggestion is unique, and the target is not customized. Otherwise user selection stays. A successful Gradle build writes actual task, APK pattern, remote options, and current pointer back to the shared profile. When no confirming identity exists, the IDEA configuration remains runnable but shared import/writeback is skipped. Ordinary Android Run Configurations are suggestion inputs, not shared profiles. Store paths and profile safety are in `05_utilities.md` §3.

## Run and UI boundaries

`JuggManager.runTask()` creates `JuggCompileUiHandler`, then `JuggConfigurationRunner` schedules `JuggRunningTask` in the background. Its project write transaction joins compile, per-device deploy, fallback, cancellation, and terminal events under one task ID; the Run entry does not wait for the Project Runtime Lock on EDT. androidTest must pass run spec, executor, and run profile together to retain Test Results, source navigation, and rerun-failed behavior. `HostTaskExecutor` adapts shared tasks to IDEA progress and EDT state.

The stable Tool Window hosts the replaceable Control Panel. Its model retains one task timeline, bounded Recent Runs, and structured events; Sync, MCP, and App events add history without replacing the running task. Logs render structured events, not a tail of `compile_latest.log`. Compile start is emitted after preprocessing chooses the actual path; a run enters Recent Runs only at terminal state. MCP event details remove `projectDir`, redact sensitive fields, and bound output length. Actions and Settings reuse the project manager. The panel can open during IDE indexing.

Settings expose compile/deploy switches only when their underlying capability is available. In particular, changing Backup classpath requires confirmation and deletes deployment history because the old baseline cannot be reused under the new classpath mode.

The `Clear app data` action confirms with the user, then requests a full Gradle build that clears app data before reinstalling the selected app. `Clear Jugg Build` deletes project `build/jugg` state and reopens projects so the manager rebuilds its context; these actions are not ordinary incremental redeploys.

IDE Runtime CLI/MCP calls prefer the selected Jugg configuration. If absent, they resolve latest successful full-build command and target, then command alone, then the first configuration; final fallback logs a warning. `Exec remote CMD` is stricter: it requires the selected remote Jugg configuration, opens separate Run Content and SSH process, and does not enter ordinary compile/deploy. `JuggSettings` scopes recent commands to user, host, port, and remote path. Credential and log-redaction boundaries belong in `05_utilities.md`.

Update checks and Control Panel use the same manager. Invalid update responses prompt for a custom server; “already latest” requires an explicit response. A nondefault server URL requires capability-trust confirmation. Installed IDEA-managed standalone runtime refreshes on tooling build changes; externally managed runtime is untouched. Diagnostics reporting presents redacted candidate files and the exact upload destination before upload or local bundle creation; its data contract belongs in `05_utilities.md`.

`Install Jugg Skills` joins an IDEA dialog with shared skill, CLI, and hook installers. CLI/hooks installation requires Python 3.7+ before writing their configuration. Codex selection installs the bundled CLI permission rule; Claude hook installation may offer a CC Switch Common Config export after success, without editing CC Switch directly. The installed setup guide is exported under `~/.jugg/skills/install/agent_setup.md`. Startup CLI/skill auto-refresh is gated by an installed CLI and compares the bundled skill's `version:` with the installed `SKILL.md`, not `CLI_VERSION`; see `08_cli_tools_list.md` for the version contract.

The IDE diagnostics dialog merges the ten newest Jugg logs from IDE and standalone locations, pins those logs, and lets users remove higher-sensitivity IDE/Gradle/project-info candidates after redaction. It displays the exact fixed upload URL and supports saving a local ZIP instead. This UI selection and the shared bundle allowlist are separate checks; the latter is specified in `05_utilities.md`.

## Investigation guide

| Symptom | Inspect |
|---|---|
| Manager missing or stale after update | `JuggInitializer.instanceSet`, `JuggLoader` load list/fallback, and whether project reopened. |
| Configuration absent or wrong variant | Sync event, Android suggestions, `IdeaCliRunConfigurationManager` identity/selection guards, shared current pointer. |
| IDE sees no file changes | VFS batch, scope pruning, file-processing barrier, Runtime owner recovery. |
| Local Gradle works in shell but not GUI IDE | `IdeaPlatformApi` shell environment, resolved `JAVA_HOME`, `ANDROID_HOME`, and `PATH`. |
| Panel and Run disagree | Structured task ID and terminal event; inspect `compile_latest.log` separately for raw compiler detail. |
| Debug output exists but breakpoints fail | `04_engineering_debug_attach.md`; Run Content and Java Debug session are separate. |

For plugin-runtime logs and root-cause procedure, use `09_plugin_runtime_debug.md`.
