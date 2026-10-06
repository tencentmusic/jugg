# Shared Runtime Utilities

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page follows shared services that compilation, deployment, IDEA, standalone, and MCP use across module boundaries: paths and locks, settings, logs, APK publication, backend/diagnostics, hot update, and Host capabilities. Their caller-specific compile/deploy flows remain in the corresponding topic pages.

## 2. Core Source Index

| Capability | Main entry | Cross-module responsibility |
|---|---|---|
| Project/global paths | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggPathManager.kt`, `JuggGlobalPathManager.kt` | Keep project cache separate from cross-project resources and choose an active writable global root |
| Project/global locks | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/ExecutionLockManager.kt` | Project Runtime lease and short global-resource mutation boundary |
| Settings and profiles | `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`, `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JsonRuntimeSettingsRepository.kt`, `CliRunConfiguration.kt` | Shared IDEA/standalone settings and per-project UUID build profiles |
| Logging | `main/src/main/java/com/sickworm/intellij/jugg/logger/JuggLogger.kt`, `FileLogger.kt`, `TimeLogger.kt` | Registered project logger, rolling compile files, and phase timing |
| APK publication | `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt`, `ResourceApkModifier.kt`, `CustomApkSignScriptRunner.kt` | Publish rewritten APK/resource APK only after the temporary artifact is valid |
| Host and devices | `main/src/main/java/com/sickworm/intellij/jugg/platform/IPlatformApi.kt`, `PlatformApi.kt`; `main/src/main/java/com/sickworm/intellij/jugg/deploy/IDeployTargetManager.kt` | Process Host capabilities versus project-scoped target devices |
| Git | `main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt`, `WorktreeFileRepository.kt` | Worktree-aware change and HEAD access |
| Backend and events | `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt`, `JuggServerChooser.kt`, `JuggEventLocalStore.kt` | Auxiliary reports, version checks, server choice, and local event history |
| Issue diagnostics | `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilder.kt`, `IssueReportBundleReader.kt`, `IssueReportUploader.kt` | Allowlisted/redacted bundle, manifest verification, and one upload destination |
| Hot update | `main/src/main/java/com/sickworm/intellij/jugg/server/JuggHotUpdateManager.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/server/IdeaHotUpdateCoordinator.kt` | Verified immutable JAR pool and separate IDEA/standalone load manifests |
| Resource lifecycle | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggResourceManager.kt`, `main/src/main/java/com/sickworm/intellij/jugg/project/ExpiredArtifactCleaner.kt` | Fixed global resources and independent expiry cleanup |

## 3. Paths, Locks, Settings, and Logs

```text
JuggPathManager(projectDir) -> build/jugg/{database,classpath,log,tmp,...}
JuggGlobalPathManager -> ~/.jugg, or ${java.io.tmpdir}/jugg-<user> if home is unwritable
  -> settings.json, action.db, resources, hot_update, CLI/skills/hooks and shared histories
project work -> Project Runtime Lock -> optional short Global Resource Lock commit
```

A Runtime's project lease coordinates IDEA, standalone, and CI processes; same-Runtime task ownership is coordinated separately. The global lock is a leaf: while holding it, do bounded synchronous local reads/writes only, and never wait for a Project Lock, network, process, future, or business callback. Settings, runtime resources, hot update, CLI files, and shared history acquire the lock at their own write boundary. A download or expensive verification happens outside it, followed by a short recheck and atomic publication. `JuggResourceManager` maps classpath resources into the fixed global `resources/` tree and atomically replaces validated files under that lock. Project compilation cache, logs, and deployment DB stay under `build/jugg`, even if the global root falls back to a temporary directory; if neither root is writable, initialization fails explicitly.

`JuggSettings` lazily reads the active global root's `settings.json` on first persisted access. Writes merge against the latest disk snapshot under the global lock, so different fields from IDEA and standalone do not overwrite each other. IDEA's `JuggManager.init()` calls `JuggSettings.migrateLegacyJuggSettings()` in `IdeaRuntimeSettingsMigration.kt`: legacy `PropertiesComponent` values fill only fields absent from JSON, and failure leaves legacy data for another startup. On confirmed Runtime-owner change or active global-root change, discard the in-process settings snapshot before the next read. Missing JSON uses defaults without creating the file. Project custom configuration separately prefers local `custom_config.json` over server `default_custom_config.json` and applies server, file, classpath, custom-compiler, and embedded-APK rules as one refresh.

Shared `CliRunConfiguration` profiles live in `build/jugg/config/run_configurations/<UUID>.json`; `current_run_configuration.json` contains the selected ID. IDEA synchronization and default inference are in `04_engineering_ide.md`. Profiles can contain remote SSH credentials: store writes a temporary file, atomically replaces the target, and attempts owner-only POSIX permissions for files and directory. `CliRunConfiguration.toString()` reports password presence rather than its value. Remote environment transmission is unchanged, but `RemoteGradleCompileClient` logs full values only for `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `GRADLE_USER_HOME`; it marks `PATH` configured and gives other variable names/count. A PTY shell must disable input echo before sending a command with environment values; failure stops that remote command. Remote user-command bodies appear in their Run Content, not persistent Jugg command logs. `JuggSettings` keeps the latest ten distinct remote commands per user/host/port/project target; corrupt history or save failure does not block command execution.

A project key must be registered before `JuggLogger.getInstance()`; failure indicates initialization order rather than a valid empty logger. IDEA logs use `build/jugg/log/`, standalone uses `build/jugg/log/standlone_cli/`. `FileLogger.resetLatestCompileLog()` closes the old file handler, updates the best-effort `compile_latest.log` / `compile_latest-1.log` shortcuts, and opens a new rolling `compile_*.log`; each directory retains at most ten rolling files. IDEA and standalone reach this boundary through `JuggLogger.resetLatestCompileLog()`. Absence of a shortcut does not prove absence of the run log. `TimeLogger.start/end` pair by string tag, so one tag reused across simultaneous phases distorts timing. `TaskRunnerManager.runTaskSafe` reports background-task failure, not every successful task.

## 4. APK and Resource Publication

```text
ApkFileModifier.insertAndResign()
  -> copy APK to same-directory temporary file
  -> insert/replace entries, zipalign, sign with default keystore or custom script
  -> verify with the JDK environment that signed
  -> replace original only after every step succeeds
ResourceApkModifier
  -> update a fresh same-directory temporary resource APK and close ZipFS
  -> publish atomically where supported, otherwise replace after complete write
```

A custom APK signing script replaces only the signing step; it may run without a local `SigningConfig`, then joins common verification and atomic publication. Its command appends a shell-escaped absolute APK path and is marked secure in debug logs. Default zipalign/apksigner arguments are escaped as individual host-shell arguments, preserving SDK/APK/keystore paths with spaces or special characters. `ApkFileModifier` obtains Host JDK candidates through `PlatformApi.allAvailableJavaHomes()`; default signing retries with a different candidate by replacing any prior `JAVA_HOME` entry, then verifies with the successful environment. On failure, the original APK remains usable. `ResourceApkModifier`'s entry/content/APK/heap measurements distinguish ZIP-generation pressure from later deployer payload pressure. System-app installation and custom signing policy are in `03_deploy_system_app.md`.

## 5. Backend, Diagnostics, and Hot Update

`JuggServer` receives `RuntimeInfo` from its Host instead of inferring IDEA/plugin identity in shared code. Backend-compatible `version` / `ide_version` come from runtime/host versions; runtime type identifies lock ownership, not an event field. `JuggServer.report()` best-effort appends `action.db` before remote send: missing server or remote failure leaves the local record, while a local write failure does not stop remote reporting. Ordinary plugin builds omit ignored `config/servers.json`, so automatic server selection is unavailable without bundled configuration; an explicitly configured Custom Server may still be used. Server update checks, event reports, and custom-compiler downloads are auxiliary children of a Runtime `SupervisorJob`; one failure must not cancel compilation or deployment.

Issue reporting has a separate destination from server failover: it uploads only to fixed `https://jugg.sickworm.com/report_issue`. `IssueReportBundleBuilder` selects allowlisted generated data, redacts known secrets and sensitive project-info values, writes a manifest, and verifies ZIP entries and sizes; `IssueReportBundleReader` checks report ID and SHA-256 before accepting it. Existing primary/included project-info snapshots named by the current included-build list are optional removable candidates, not a directory dump; signing credentials, Manifest placeholders, processor arguments and generic sensitive-key values are replaced while fields such as `applicationId` remain useful. A malformed snapshot omits only that entry. Required Jugg logs remain, and the latest ten source log files retain the standalone subdirectory in the bundle. The report asks the project `IDeployTargetManager.dumpErrorLogs()` for device logcat without narrowing by a requested serial; one missing device log does not invalidate other entries. Temporary issue diagnostics expire after seven days; fetched MCP artifacts after 30 days, with separate cleanup failures contained locally.

`JuggHotUpdateManager` verifies downloads and publishes immutable JARs/metadata under the global lock; IDEA and standalone use separate load manifests over the shared pool. A compatible update publishes the relevant active manifests. An update requiring reinstall saves candidates but leaves active manifests alone; after the new plugin starts, the standalone candidate activates only on exact `releaseBuildId` match. Old nullable manifest fields read with safe defaults. Unreferenced hot-update JARs are retained for 90 days. Loader/bootstrap reads its own manifest, so a downloaded candidate is not evidence that the current Runtime loaded it.

## 6. Host, Device, Git, and Remote Sync Boundaries

`PlatformApi.impl` is injected by the Host; `main` must not reach around it to call IDEA APIs. On hot update, `PlatformApi`, its interface/implementation, and direct Jugg descriptor types must remain Host-loaded together to avoid duplicate static state and loader-constraint failures. `IDeployTargetManager` owns project device enumeration, selection, and `IDevice`→ADB conversion, preventing one standalone project's target state from leaking into another. Git HEAD operations use worktree-local HEAD through `WorktreeFileRepository`.

Remote source sync has fixed `.gradle`/`build` include-exclude groups that retain required Jugg files. Before user customization, configurable defaults exclude local IDE, Git object/module, native cache, and `local.properties` inputs. Once customized, only saved configurable patterns apply; explicitly empty means none. Rsync patterns are transfer-root globs, not gitignore rules: `.git/` matches at any depth and `/.git/` only the transfer root. Patterns are separated by semicolon/newline (comma for input compatibility); unsafe parent traversal, quotes, and Windows absolute paths are rejected. `RemoteUserCommand` uses a unique completion marker and secure command logging so user text cannot interfere with exit parsing or enter persistent logs.

## 7. Diagnostic Boundaries

| Observation | What it establishes | Next evidence |
|---|---|---|
| `compile_latest.log` is stale or absent | The shortcut may have failed independently of rolling logs | Project key registration, current `compile_*.log`, `compile_latest-1.log`, Runtime log directory |
| APK rewrite/signing failed | The original should remain; the failed stage is still unknown | Temporary-file stage, Java-home candidates, signer output, common verification |
| Resource APK changed but device did not | ZIP publication alone does not prove deployer/runtime refresh | Published resource APK, deploy payload stats, deployment flow |
| Issue bundle lacks one snapshot or device log | One optional parse/collection failed; other entries can remain valid | Current included-build list, source JSON parse, per-device logcat result, bundle manifest |
| Hot-update download succeeded but next startup uses old code | Candidate download does not imply manifest activation or Loader selection | `isNeedReinstall`, active IDEA/standalone manifest, build-time/`releaseBuildId` match |
| Remote service target differs across runs | Server choice may have no bundled config and may depend on Custom Server | `JuggServerChooser`, effective settings, plugin build type |
| A utility fails only under standalone or hot update | Host or ClassLoader ownership may differ | `PlatformApi` injection, RuntimeInfo, device manager, loader descriptor types |

## 8. Related Documents

- Compilation and project model: `02_compile_core.md`, `04_engineering_project.md`
- Deployment and signing: `03_deploy_core.md`, `03_deploy_system_app.md`
- IDE/standalone: `04_engineering_ide.md`, `04_engineering_compat.md`
- MCP and runtime investigation: `08_mcp_design.md`, `09_plugin_runtime_debug.md`
