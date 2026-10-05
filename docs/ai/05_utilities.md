# Shared Utilities Module

> Last checked: 2026-09-08
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Scope

This page records shared `main` capabilities reused by compilation, deployment, MCP, and remote operations: source entry points, cross-module data flow, hidden constraints, and first investigation steps. For main compile/deploy flows, see `02_compile_core.md`, `03_deploy_core.md`, and `08_mcp_design.md`.

---

## 2. Core Source Index

| Capability | Core entry | Role |
|------|----------|------|
| Logging | `main/src/main/java/com/sickworm/intellij/jugg/logger/JuggLogger.kt`, `FileLogger.kt`, `TimeLogger.kt` | Project/global log dispatch, `compile_latest.log` shortcut, phase timing. |
| Paths and temporary artifacts | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggPathManager.kt`, `JuggGlobalPathManager.kt`, `main/src/main/java/com/sickworm/intellij/jugg/project/ExpiredArtifactCleaner.kt` | Project `build/jugg`, stable `.gradle/jugg`, user-global root (`~/.jugg`, falling back to `${java.io.tmpdir}/jugg-<user>` if unwritable), and project-expired-artifact cleanup. |
| APK modification | `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt`, `ResourceApkModifier.kt` | APK insertion, replacement, zipalign, signing, and incremental resource-APK update. |
| APK signing script | `main/src/main/java/com/sickworm/intellij/jugg/apk/CustomApkSignScriptRunner.kt` | Replaces `ApkFileModifier` default keystore signing with a project script, passes absolute APK path, and forwards script output. |
| Git worktree | `main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt`, `WorktreeFileRepository.kt` | Detects Git changes; directs HEAD operations to worktree-local HEAD. |
| Platform bridge | `main/src/main/java/com/sickworm/intellij/jugg/platform/IPlatformApi.kt`, `PlatformApi.kt`, `idea/.../ide/logic/IdeaPlatformApi.kt` | Abstraction for process-level UI, Gradle, and MCP Host capabilities called by core. `IDeployTargetManager` owns project device selection and ADB adapters. During hot update, implementation classes and direct Jugg types in interface JVM descriptors stay host-loaded. |
| Remote service | `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt`, `JuggServerChooser.kt`, `JuggEventLocalStore.kt`, `JuggRemoteCompileApplier.kt` | Reporting, version check, server failover, global local event history, and remote compile apply. Without bundled configuration, only an explicitly set custom server can enable backend. |
| Issue diagnostics | `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilder.kt`, `IssueReportUploader.kt` | Allowlisted, redacted diagnostics bundle with manifest verification and upload to one HTTPS endpoint. |
| Runtime info | `project/runtime/RuntimeInfo.kt` | Explicit Host-provided runtime type/version, Host version, and build time for Server, locks, and hot update. |
| Hot update | `server/JuggHotUpdateManager.kt`, `idea/.../server/IdeaHotUpdateCoordinator.kt`, `idea/.../loader/JuggHotUpdateBootstrap.kt` | Shared download verification, atomic publication, and cleanup; IDEA check/install orchestration; read-only manifest before Loader startup. |
| Configuration model | `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`, `project/runtime/JsonRuntimeSettingsRepository.kt`, `ProjectCustomConfigManager.kt`, `JuggGradleCompileOptions.kt` | Shared IDEA/standalone settings, project custom-config lifecycle, runtime options, and derived Gradle tasks. |

---

## 3. Core Data Flow

```text
JuggManager initialization
  -> JuggPathManager defines project-local build/jugg, database, log, tmp, mcp_fetch
  -> IDEA Runtime registers pathManager.logDir; standalone Runtime registers pathManager.standaloneCliLogDir
  -> compilation / deployment / MCP share one Logger and path object
  -> FileLogger writes compile_*.log and maintains compile_latest.log / compile_latest-1.log
```

```text
core logic needs IDE/user interaction
  -> call PlatformApi
  -> PlatformApi only forwards to injected IPlatformApi Host implementation
  -> main avoids direct IDE dependency; tests can use platform_compat stubs
```

```text
core logic needs project device capabilities
  -> IDeployTargetManager returns selected and online devices for this project
  -> after selection, createDeviceAdb() builds an ADB adapter in the same project domain
  -> do not attach project devices or ADB adapter back to process-level PlatformApi
```

```text
Jugg-owned global files needed
  -> JuggGlobalPathManager prefers ~/.jugg, falling back to ${java.io.tmpdir}/jugg-<user> if home is unwritable
  -> centralize cross-project settings.json / action.db / resources / hot_update / skills / library_test_build_records
  -> CLI / skills / hooks / test_flag use the same active global root
  -> serialize writes through <global-root>/locks/global.lock; snapshot files use temporary file + atomic replacement
  -> project compilation cache, logs, and DB remain under build/jugg via JuggPathManager
```

```text
IDEA Runtime settings initialization
  -> JuggManager Init Jugg background task reads old PropertiesComponent and converts legacy fields without blocking init caller
  -> JuggSettings.migrateLegacyJuggSettings() reads old properties through IDEA adapter; fills only fields absent from settings.json; existing JSON wins
  -> after success, record migration complete in PropertiesComponent; failure does not block startup or clear old properties, and next startup retries
  -> first persisted setting get/set automatically loads JSON; later field changes persist synchronously

Standalone/CLI Runtime settings
  -> first persisted setting get/set reads the same settings.json through JsonRuntimeSettingsRepository
  -> missing file uses JuggSettings defaults without creating one
  -> on confirmed Runtime-owner change, TaskRunner discards in-process settings snapshot; next read by new owner uses latest disk values

Project custom config
  -> ProjectCustomConfigManager privately owns ProjectCustomConfigStore
  -> local custom_config.json outranks server default_custom_config.json
  -> refresh/updateDefaultConfig applies server, file rules, classpath, custom compiler, and embedded APK together
```

```text
Runtime info
  -> Host creates RuntimeInfo(runtimeType/runtimeVersion/hostVersion/buildTime)
  -> JuggServer consumes only injected info, without reading Project, plugin manifest, or PlatformApi
  -> TaskRunner uses runtime type/version for lock-owner identity
```

```text
Hot update
  -> JuggHotUpdateManager downloads, verifies MD5, and atomically publishes immutable JARs and hot_update_data.json under fixed global lock
  -> compatible update publishes IDEA load_manifest.json and standalone standalone_load_manifest.json separately
  -> isNeedReinstall=true saves only JARs, candidate Bundle, and metadata; active manifest stays unchanged
  -> after new plugin starts, activate candidate standalone snapshot only on exact releaseBuildId match
  -> IDEA JuggHotUpdateBootstrap and standalone StandaloneBootstrap read only their own manifests
```

---
## 4. Hidden Constraints

- `JuggLogger.getInstance(...)` requires the project key already registered. An unregistered key fails fast; investigate initialization order before adding an empty logger.
- `FileLogger`'s `compile_latest.log` is a best-effort shortcut. Real rolling files are `compile_yyyy-MM-dd_HH-mm-ss.%g.log`; for missing logs inspect both current main file and `compile_latest-1.log`.
- IDEA logs live under `build/jugg/log/`; standalone logs under `build/jugg/log/standlone_cli/`. Each directory keeps at most 10 files. Issue Report merges by modification time and includes the latest 10, retaining `standlone_cli/` hierarchy.
- `TimeLogger.start/end` pair by string tag. Reusing one tag across phases corrupts timing; check uniqueness before high-frequency instrumentation.
- `TaskRunnerManager.runTaskSafe` reports task name, duration, and exception only on background-task failure; success sends no event.
- Every `JuggServer.report()` best-effort writes global `action.db` first (default `~/.jugg/action.db`). Missing server or remote failure does not affect local record; local write failure does not prevent remote report.
- Ordinary `buildPlugin` excludes `config/servers.json`; only `buildPluginInternal` validates and packages this local ignored file. Without bundled configuration, historical automatic server-selection URLs are inactive; only an explicitly configured Custom Server remains usable.
- Issue reporting does not reuse server failover. Client uploads only an allowlisted, redacted ZIP to fixed `https://jugg.sickworm.com/report_issue`. Confirmation displays that one fixed HTTPS destination; do not persist the address or try fallback.
- Issue report ignores requested serial and best-effort collects logcat from all target devices. Failure on one device omits only its entry, not other device logs or diagnostics generation.
- Issue report offers existing `project_infos.json`, `gradle_project_infos.json`, and `include_build_*_gradle_project_infos.json` named by current `gradle_include_builds.txt` as default-selected removable high-sensitivity candidates. Their structured redacted copies live under `diagnostics/project-info/`. Keep diagnostic fields such as `applicationId`, but replace SigningConfig credentials, keystore, keyAlias, Manifest placeholders, APT/KAPT parameters, and generic sensitive-key values with placeholders. On JSON parse failure skip only that snapshot. Stray included-build files and other `project_infos.db` files do not enter bundle. Required Jugg logs remain first.
- MCP fetched artifacts live 30 days; temporary issue diagnostics live seven days. Separate background tasks after project startup call `ExpiredArtifactCleaner`; failure of one cleanup does not block the other.
- `JuggPathManager` exposes project-local and global roots. Compilation outputs, deployment cache, DB, and logs prefer project-local. Cross-project hot update, history, hooks, and resource files use `JuggGlobalPathManager` and writes under active global root's fixed lock. If `~/.jugg` probe fails, global root switches to `${java.io.tmpdir}/jugg-<user>`; later compilation should not fail again on home permissions.
- Write `settings.json` under fixed global lock with temporary file + atomic replacement. `JuggSettings` serializes same-process updates and applies field changes against latest disk snapshot under lock so different fields from two Runtimes do not overwrite one another. IDEA legacy migration only fills missing fields, never replaces existing JSON values. After Runtime-owner switch, discard in-process settings snapshot so IDEA/standalone takeover does not use compatibility records or switches from before another process's update. CLI forced backup classpath uses a process-level override without modifying shared settings. Changing `JuggGlobalPathManager.rootDir` causes `JuggSettings` to discard its old-root cache automatically, allowing tests to isolate real user settings with separate roots.
- `PlatformApi.impl` is a Host-injection boundary. Core must not call IDE/Android Studio API around it or `main` tests and CLI will fail. During hot update, `PlatformApi`, `IPlatformApi`, `IdeaPlatformApi`, and direct Jugg types in `IPlatformApi` JVM method descriptors must load from the same Host ClassLoader. `JuggLoader` derives this set from interface signatures, preventing cross-loader static copies and loader-constraint violations.
- `IDeployTargetManager` is the project device boundary. Device enumeration, request-level selection, and `IDevice` → `IDeviceAdb` conversion must use that project's Runtime manager, so standalone multi-project processes cannot misuse global device state.
- `JuggSettings` saves remote-command history by `user + host + port + remoteProjectPath`, keeping the latest 10 unique full commands per target. Corrupt history or write failure returns an empty history without blocking command execution. Never persist command bodies in Jugg logs. `RemoteUserCommand` encodes the body for a subshell and parses exit code using a unique completion marker per execution, so comments, `exit`, or command output cannot disrupt protocol.
- `JuggServer` Runtime identity is injected by Host through `RuntimeInfo`; shared Server must not infer plugin/IDE metadata for IDEA, CI, or standalone. Events retain backend-compatible `version/ide_version` fields populated from `runtimeVersion/hostVersion`; `runtimeType` is only for Runtime lock-owner identity and is not reported in events.
- `JuggServer` runs update checks, reports, and custom-compiler downloads as auxiliary tasks under a `SupervisorJob` attached to Runtime Scope. Runtime disposal cancels them, but an uncaught exception in one must not cancel compilation, deployment, or TaskRunner's shared Runtime Scope.
- Hot-update JAR and metadata writes must go through `JuggHotUpdateManager` global lock and atomic replacement. IDEA and standalone share an immutable JAR content pool but use separate manifests. `isNeedReinstall=true` must not change either active manifest. Activate candidate standalone snapshot only if new plugin `releaseBuildId` exactly matches it. Consume nullable standalone fields in old Gson JSON using `orEmpty()`.
- Keep unreferenced hot-update JARs 90 days; separately clean MCP fetch artifacts after 30. Standalone Deployer keeps one resource copy at `~/.jugg/resources/deployer/quail`, atomically overwriting under global write lock each preparation. After complete tooling install stops old daemon, remove historical `~/.jugg/runtime`. AAPT2 keeps `resources/tools/<os>/aapt2-inclink-<version>` and does not share deployer's overwrite policy.
- APK modification finds signing JDKs through `PlatformApi.allAvailableJavaHomes()`. Each retry removes existing `JAVA_HOME` and writes current candidate, truly switching JDK even if the original environment lacked that variable. On signing failure, inspect host Java-home list as well as apksigner output.
- `ApkFileModifier.insertAndResign()` inserts, aligns, signs, and verifies on a temporary copy in the same directory, reusing the JDK environment that actually signed for verification. Replace original APK only after every step succeeds. Retain original and best-effort delete temporary file on any failure.
- `ApkFileModifier` escapes each zipalign/apksigner argument for host shell with `shellEscapeArgument`. SDK, APK, or keystore paths with spaces, parentheses, or Unicode remain one argument. `CustomApkSignScriptRunner` uses the same rule to append `<configured command> '<absolute APK path>'`.
- Nullable `customApkSignScriptRunner` in `ApkFileModifier` chooses custom script versus default-keystore signing. With a script, `signConfig` may be empty; still run common `verifyApk()` and atomic replacement. Script command uses `isSecureCommand`, so `CmdExecutor` debug logs show `(secure)` only, never script text.
- Compatible resource-APK modification retains JVM 14+ ZipFS. `ResourceApkModifier` creates a unique same-directory temporary file for each write, replacing final `resource.ap_` atomically after close if supported, otherwise with ordinary replacement. This avoids stale ZipFS URI after exception and a partial cache contaminating later Run. Logs record entry count, total content bytes, largest entry, APK bytes, and heap before/after export, distinguishing ZIP-generation peaks from deployer-payload wrapping peaks.
- Remote compilation Exclude patterns configure exclusions in local-to-remote source sync. `.gradle` and `build` retain fixed include/exclude order: directories excluded by default while required `.gradle/jugg/**`, `build/jugg/config/**`, and similar Jugg files remain allowed. Users cannot remove these two fixed exclusions. Before customization, display and use `local.properties`, `.idea/`, `*.iml`, `.git/objects/`, `.git/modules/`, and `.cxx/`. After user edits, use only saved configurable patterns; explicitly empty means no configurable default exclusions. Old Additional exclude patterns lack a customization marker and are treated as unset on upgrade. Split configured rsync globs by semicolon or newline (comma only for input compatibility); pass each pattern verbatim to rsync in every sync mode, scoped to the actual transfer root. `.git/` matches a same-name directory at any level; `/.git/` matches only transfer root. These are not gitignore rules; `..`, quotes, and Windows absolute paths are always unsupported.

---
## 5. Investigation Entry Points

| Symptom | Start with |
|------|----------|
| `compile_latest.log` does not update or shows old logs | `JuggLogger.register/unregister`, `FileLogger.recreateIfDeleted()`, `FileLogger.resetLatestCompileLog()`. |
| Need to identify log Runtime | `build/jugg/log/` for IDEA or `build/jugg/log/standlone_cli/` for standalone; on lock contention check `Runtime lock contention`. |
| Phase timing absent from log | Whether call sites pair `TimeLogger.start/end`. |
| APK modification has no install effect or signing fails | `ApkFileModifier.insertAndResign()`, `alignApk()`, `signApk()`. |
| Worktree change detection wrong | `GitManager`, `WorktreeFileRepository`. |
| Platform capability fails in `main` tests | `PlatformApi.impl` injection and `platform_compat/base_api` stubs. |
| Remote service address wrong or changes repeatedly | `JuggServerChooser`, `JuggSettings.serverUrl/serverExpireTimeMill`. |
| Issue report lacks project info or logs | `ProjectInfoReader.printInfo()`, `DeployTargetManager.dumpErrorLogs()`, `JuggServer.reportAndUploadLogs()`. |
| Hot-update download succeeds but is not loaded next startup | `JuggHotUpdateManager.loadManifestFile`, `JuggHotUpdateBootstrap`, and whether runtime `buildTime` matches Loader's embedded build time. |

---

## 6. Related Documents

- Compilation: `02_compile_core.md`
- Deployment: `03_deploy_core.md`
- Engineering and paths: `04_engineering_project.md`
- Compatibility layer: `04_engineering_compat.md`
- MCP: `08_mcp_design.md`
