# Deployment System: Core Deployment Mechanisms

> Last verified: 2026-09-23
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page answers three questions:

- **Where are the core classes?** Give AI its first classes to read instead of blindly traversing the repository.
- **How does the main flow work?** Explain cross-class calls and state machines to reduce successive Go to Definition jumps.
- **What constraints are unobvious in code?** Describe boundaries around overlay IDs, Direct Overlay, multiple APKs, and retry/recovery.

It does not explain how compilation outputs are made. For effect analysis, see `03_deploy_data_generator.md`; for the end-to-end Run flow, see `03_deploy_complete.md`; for JVMTI details, see `03_runtime_jvmti.md`; for initial installation of a system app under `/system`, see `03_deploy_system_app.md`.

---

## 2. Core Source Index

Shared deployment orchestration uses `IDevice`, `Apk`, `ApkEntry`, `DexClass`, `ByteString`, `Deploy.Arch`, and `ILogger` from `com.sickworm.intellij.jugg.deploy.api` in `deploy_compat/interface`. These types retain their old call surfaces to minimize migration diffs, but do not link ddmlib, Android Studio deployer models, or shaded protobuf. Legacy/Quail compat instances and the standalone executor each convert real types at their boundary; the interface JAR holds neither a raw converter nor global adapter state.

| Class/interface | File | Role |
|---|---|---|
| `JuggDeployerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | Shared IDEA/standalone entry point. Chooses install / embedded / incremental and delegates per-device lifecycle for one run to the shared orchestrator. |
| `JuggDeployOrchestrator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt` | Shared device deployment lifecycle: deployment slicing, Apply Changes, agent, restart/start, and JVMTI check. `IDeployHost` injects Host differences. |
| `DeployStateRecover` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployStateRecover.kt` | Restores the baseline when device state is unknown or mismatched: direct check, dry deploy, or reinstall. |
| `DeployRetryHandler` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt` | Chooses retry, fallback HOT_FIX, compatible deployment, recovery then redeployment, or stop by failure cause. |
| `JuggDeployTask` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt` | One device's one-run deploy task. Groups by `applicationId`, scopes full `JuggDeployData` to APKs, then calls `JuggDeployer`. |
| `JuggDeployer` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | Wraps install, code swap, full swap, deployment cache, overlay ID, and Direct Overlay transport via `IApplyChangesExecutor`. |
| `CustomApkInstallScriptRunner` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/CustomApkInstallScriptRunner.kt` | Runs the current Run Configuration's custom ordinary-app APK install script from the local project root, forwards output, responds to cancellation, and validates package/APK checksum. |
| `CustomApkSignScriptRunner` | `main/src/main/java/com/sickworm/intellij/jugg/apk/CustomApkSignScriptRunner.kt` | Runs the current Run Configuration's custom APK signing script from the local project root, passing the temporary unsigned APK's absolute path as the last argument. |
| `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | Deployment-file facade. Maintains changed/compiled/staging/deployed state, generates `JuggDeployData`, and resets after reinstall. |
| `DeployDataPlanner` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployDataPlanner.kt` | Plans deployment data from staging + history, handling dex merge and compatible-deployment assembly. |
| `JuggDeployData` / `DeployItem` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | Final device-bound deployment-data model: deploy type, APK ownership, restart decision, split/filter, and this run's Flutter JIT runtime changes (`flutterJitRuntimeFiles`). |
| `FlutterJitCacheInvalidator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/flutter/FlutterJitCacheInvalidator.kt` | Deletes `res_timestamp-*` directly under target app `app_flutter` via `AppSandboxExecutor`, making Flutter unpack `flutter_assets` from the overlay on its next startup. |
| `DirectOverlaySwapTransport` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlaySwapTransport.kt` | Direct Overlay swap transport. Replaces only Apply Changes' overlay-update action, not deployment lifecycle. |
| `AppSandboxExecutor` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AppSandboxExecutor.kt` | Unified app-private-directory commands. Strictly probes Apply Changes' `run-as`, UID, and SELinux-label prerequisites; when incompatible, fixes ordinary shell, root adbd, or noninteractive `su` mode and the real `dataDir`. |
| `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | Atomically writes device `code_cache/.overlay` through the app sandbox and commits the new overlay ID last. |
| `DirectAppSandboxDeployTransport` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/DirectAppSandboxDeployTransport.kt` | Takes over incremental overlay payload before AS deployer for apps incompatible with `run-as`, combining Direct Overlay, Jugg JVMTI redefine, and restart fallback. If sandbox access is entirely unavailable, requests compatible redeployment or stages a rootless request. |
| `RootlessCompatDeployStaging` / `RootlessCompatImportConfirmer` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/RootlessCompatDeployStaging.kt` | Without sandbox access, stages a compatible payload under the app's external-files directory and waits for app import results by requestId. |
| `RootlessCompatDeployArchive` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/RootlessCompatDeployArchive.kt` | Defines the rootless pending archive, request metadata, payload SHA-256, and import-result protocol. |
| `RootlessCompatDeployImporter` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/RootlessCompatDeployImporter.java` | Imports a staged request early in app startup and atomically commits `code_cache/.overlay` after private staging. |
| `DirectOverlayStateChecker` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayStateChecker.kt` | Checks agreement among history/cache/device during recovery; before swap it checks only device overlay. |
| `DeployHistoryManager` / `JuggDeploymentService` / `JuggDeploymentCacheStore` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployHistoryManager.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/cache/JuggDeploymentCacheStore.kt` | Two checkpoint sources: Jugg's own deployment history and project-level deployment cache. Service invalidates Runtime memory objects when runtime owner, disk generation, or bound executor changes, and restores from snapshot through the current Apply Changes executor. |

---

## 3. Deployment State Model

### 3.1 From `JuggDeployData` to Deploy Type

| Condition | `DeployType` | Meaning |
|---|---|---|
| `JuggSettings.isEmbeddedToApk` | `EMBEDDED` | Write incremental files back into an APK, then install it. |
| `isInstall` | `INSTALL` | Install APK and write deployment cache / overlay ID. |
| `isWarmUp` | `WARM_UP` | Dry/warm-up payload that should cause no real business change. |
| `isCompatDeploy` | `COMPAT_HOT_FIX` | Compatible hot-fix path, usually with `isPushOverlayOnly=true`. |
| `isNeedRestartApp` | `HOT_FIX` | Takes effect after app restart. |
| Otherwise | `HOT_RELOAD` | Online Apply Changes, avoiding app restart where possible. |

`isNeedRestartApp` is determined by hot-fix classes, nonempty `isPushOverlayOnly`, APK-root overlays, nonempty Compose-resource compilation this run, nonempty Flutter JIT runtime changes this run, or follow-up replay after reinstall recovery. `isNeedRestartActivity` applies only to non-warm-up, nonempty payloads that do not require app restart.

Flutter JIT runtime changes are `assets/flutter_assets/kernel_blob.bin`, `vm_snapshot_data`, or `isolate_snapshot_data` genuinely compiled and deployed this run. `DeployDataPlanner` identifies them from current staging outputs whose type is `Asset`, source module includes `ExternalBuildType.Flutter`, and normalized deployment path matches one of those three files. Recognition must happen before `DeployDataGenerator` first expands the full-resource overlay; otherwise an old kernel pulled from the APK baseline could be mistaken for a current change. Results go into transient `flutterJitRuntimeFiles` (`List<DeployItem>`, scoped with `filterForApks()`, neither persisted nor written to deployment history). Warm-up and install data keep it empty.

Flutter Android embedding extracts JIT runtime files into the app-private `app_flutter` directory and uses `app_flutter/res_timestamp-<versionCode>-<lastUpdateTime>` to decide whether to extract again. An overlay update does not change the APK's `lastUpdateTime`; the timestamp must therefore be deleted explicitly so the restarted app re-extracts `flutter_assets` from the effective overlay.

For ordinary deployment, `DeployDataPlanner` detects `CompileFile.Type.ComposeResource` from `DeployFileStateTracker.getCompiledFiles()` and sets transient `isComposeResourceCompiled`. This state remains until commit and covers normal deployment and retry. There is no need to infer Compose identity from `CompileOutput.Type.Asset` or historical staging paths after their source information is lost. The Compose flag applies only to nonempty payloads, avoiding an empty restart when compilation succeeds but produces nothing.

For APK-root overlays, decide from final deployment paths. Any overlay outside `res/**`, `assets/**`, and `resources.arsc` requires a process restart, for example legacy Compose `values/strings.xml` or Java SPI `META-INF/services/**`. This is independent of compile-stage type, so recovered historical deployment data behaves the same. It permits a few harmless false positives; a classpath resource deliberately using an Android-specific path name may be a false negative.

All nonempty incremental deployments meeting `isNeedRestartActivity` currently use `APPLY_CHANGES_AND_RESTART_ACTIVITY` semantics. Android Studio transport calls `JuggDeployer.fullSwap()`. For `run-as`-incompatible apps, Direct app sandbox transport uses a request-level relaunch flag to recreate the Activity after class redefinition succeeds, without reentering `fullSwap/overlaySwap`. Both paths keep the app process alive and run `onCreate()` again. This differs from `Always restart app after deployment`, which additionally restarts the whole app process. `JuggDeployData.deployType=HOT_RELOAD` is Jugg's result category; it does not guarantee that transport uses an `APPLY_CHANGES` mode without Activity recreation.

### 3.2 File-State Transitions

```text
changed source
  -> DeployFileStateTracker.addChangedFiles()
  -> compile success: updateUncompiledFiles() + addStagingFiles()
  -> DeployDataPlanner.buildDeployData()
  -> deploy success: DeployFileManager.commit()
  -> deployed history

recover with reinstall
  -> DeployFileManager.resetAfterReinstall()
  -> clear deployed data / resource APK / staging state
```

Key constraint: `DeployFileManager.commit(deployData)` runs only after the whole deployment succeeds. APK-scoped data cut from the full payload inside `JuggDeployTask` must not be used for a global lifecycle commit.

In a multi-APK case, same-name resources in staging/deployed state are shadowed by “target APK + relative path,” not `relativeFile.path` alone. Otherwise, `resources.arsc` in both the main APK and androidTest could filter each other, making a full-resource push reread original APK resources.

Compiled outputs and reinstall-recovery history use the same logical identity when entering staging: a later output for the same “target APK + relative path” replaces the earlier one, while same-path outputs for different APKs coexist. Historical Dex lacking APK scope has lower priority than staging Dex with explicit scope, so history and new compilation directories do not retain duplicate definitions of one class.

The same shadow rule applies on successful deployment commit: before staging output enters deployed state, remove prior records with the same deploy key. A staging Dex with explicit APK scope also replaces an unscoped historical Dex at the same relative path. The deployed set no longer retains multiple records of one logical class simply because physical directories differ, preventing later automatic Dex merge from seeing duplicate types. On cleanup, `DeployFileStateTracker` writes one debug log with the destination, count, cause category, and first 20 old files. It writes no log when there is no shadow conflict.

---
## 4. Core Call Chain

### 4.1 Install Path

```text
JuggDeployerHelper.deploy(isInstall=true)
  -> deployInstall()
  -> JuggDeployData.forInstall(apks)
  -> JuggDeployOrchestrator.execute()
  -> JuggDeployTask.run()
  -> groupByApplicationId()
  -> JuggDeployer.install()
  -> ordinary app with custom script enabled: CustomApkInstallScriptRunner.run()
      -> inherit IDE/Gradle environment and add Android SDK platform-tools to PATH
      -> after script success, wait for ADB, confirm package exists, and dump APK to verify checksum
      -> when app sandbox is available, clear code_cache/.overlay so reinstall does not retain an old checkpoint
  -> otherwise: AsDeployerCompat.install()
  -> JuggDeploymentService.storeEntry()
  -> deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
```

The deployment cache is stored at `<projectDir>/build/jugg/database/deploy_cache.db` (one file alongside `source_files.db`). It serves only the incremental chain following the last successful Jugg install. When the project directory changes, it is removed with `database/`; Gradle install and recovery reinstall write a new base. Neither the old `build/jugg/deploy_cache/` nor `~/.jugg/deploy_cache` is migrated. A missing cache triggers recovery as a cache miss. `JuggDeploymentService` is a project Runtime instance, no longer a global singleton under `~/.jugg`. Within one Runtime, it reads `memoryCache` first, and writes update both memory and disk checkpoints. Project transactions serialize disk I/O, while a Project Runtime lease excludes different Runtimes. A write first flushes a temporary file, then atomically replaces the target.

The app is stopped before install so users do not see the confusing sequence of an install followed by an apparent stop. For install and incremental deployment failures, surface `AdbLogWrapper.realErrorMessage` first instead of editing a higher-level error message prematurely. An explicit device-side cause such as `run-as: package not debuggable` must override the deployer's generic failure message.

Script configuration flows from Run Configuration through `DeployOptions`, deploy/recovery requests, and `LaunchContextFactory` into `LaunchContext`. `JuggDeployTask` enables custom install only for ordinary apps in the INSTALL branch. `JuggDeployer` calls the IDEA-side `CustomApkInstallScriptRunner` through `IDeployHost`; test APKs continue to use the default installer. The script retains per-device, per-applicationId install granularity, while `JuggDeployer` remains responsible for checksum verification and cache updates.

The same custom-script entry point is used for Gradle install, embedded install, APK-update recovery, and reinstall recovery; androidTest APKs do not run the script. A nonzero script exit, cancellation, ADB not recovering, package not installed, or device APK checksum differing from the input APK fails explicitly. These failures do not trigger the default installer's transient retry, deploy retry, or Gradle fallback. After script verification, Jugg clears `code_cache/.overlay` when the app sandbox is available, then writes the new base deployment cache. If the sandbox is unavailable, only that extra cleanup is skipped. For install and incremental deployment failures, surface `AdbLogWrapper.realErrorMessage` before changing higher-level wording.

### 4.2 Incremental Deploy Path

```text
JuggDeployerHelper.deploy(isInstall=false)
  -> deployIncrementalChanges()
  -> DeployFileManager.getDeployData(isWarmUp, isNeedPushResourceApk)
  -> LibraryTestApkBackfillHelper.backfillIfNeeded()
  -> APK update needed: IncrementalDeployHelper.updateApk() + recoverDeployState()
  -> device not ready or **project switch** (`LastCompileProjectRegistry` + `isProjectSwitchedThisRun`): DeployStateRecover.recoverDeployState()
  -> optional quick fallback: JuggDeployData.toFallbackToHotFixData()
  -> runTask()
  -> JuggDeployTask.run()
  -> JuggDeployer.codeSwap() / fullSwap()
  -> updateInfoAfterIncDeploy()
```

The order in `updateInfoAfterIncDeploy()` must remain: update deployment history, then `DeployFileManager.commit(deployData)`, and finally write `lastDeployOverlayIds`. This advances file history and the overlay checkpoint together.

Compatible deployment retains the JVM 14+ ZipFS fast update of `resource.ap_`, but each initial generation or incremental modification uses a unique temporary file in the same directory. The final file is replaced only after ZipFS closes successfully. This avoids reopening a stale ZipFS URI after an exception and avoids publishing a partially generated cache file. Initial generation reads the full APK bytes only once when returning final deployment data.

`IncrementalDeployHelper.updateApk()` handles every APK update. With `DeployOptions.customApkSignScript` configured, `ApkFileModifier` first inserts files into a temporary copy and zipaligns it. `CustomApkSignScriptRunner` then runs the script to overwrite that temporary APK in place. The common path runs `apksigner verify` and atomically replaces the original APK only on success. A nonzero exit, cancellation, or verification failure aborts this update and retains the original APK; the same update does not fall back to local keystore signing.

### 4.3 Decisions Within runTask

```text
runTask()
  -> data.isInstall ? INSTALL
     : data.isNeedRestartActivity ? APPLY_CHANGES_AND_RESTART_ACTIVITY
     : APPLY_CHANGES
  -> stop app first for INSTALL
  -> determine asynchronously whether the JVMTI agent must be pushed
  -> delete rolled-back library dex
  -> LaunchContextFactory creates the base LaunchContext for this run
  -> reuse this run's sandbox capability to precheck whether ordinary Direct Overlay or Direct app sandbox can be tried
  -> deploy the whole batch when either Direct channel can be tried; otherwise slice at the original threshold
  -> derive a slice LaunchContext + JuggDeployTask for each deploy data
  -> after every slice succeeds: invalidate Flutter JIT extraction cache by applicationId
  -> push agent / restart app / start app / run androidTest as needed
  -> check JVMTI compatibility issues as needed
```

`LaunchContextFactory` creates deviceAdb, install session, installer metadata, Direct Overlay lifecycle facts, and deploy prompt/message callbacks together. Every capability on the IDE compatibility facade retains a fallback for known API linkage failures. The session records the executor that actually succeeded; `LaunchContext` subsequently uses that executor and the debugger it created directly, rather than dispatching stateful calls through the facade and mixing installer, overlay, cache, and redefiner across deployer ABIs.

Flutter cache invalidation runs only when `data.flutterJitRuntimeFiles` is nonempty, after all overlay slices and before `push_agent` and the final restart. Failure midway through slicing therefore leaves the current cache intact. If `AppSandboxExecutor` is unavailable, or a timestamp remains after deletion and verification, this deployment fails explicitly and does not restart. On success, this run's result is `HOT_FIX` and uses the existing `restartApp` / `restartAppForDebug` path to restart the entire process, rather than restarting only the Activity.

Before slicing, `canTry()` is reused for both transports. Ordinary Direct Overlay retains its switch, caller permission, and ready/force conditions. Direct app sandbox uses the sandbox capability cached in this run's `LaunchContext` on Android 8+ when any application in the target APK is incompatible with Apply Changes; it is not constrained by the ordinary Direct switch. When either channel can be tried for a non-install, nonempty payload, deploy the whole batch without entering `SliceDeployHelper`; official Apply Changes retains the existing slicing behavior. Direct adds no new slicing or aggregation of slice results. `JuggDeployTask` still groups whole-batch data by applicationId.

After slicing, only the first slice retains the except-overlay check. Later slices skip it because the overlay ID has already changed within the same deployment and would otherwise cause a self-conflict.

When the original deployment type is `APPLY_CHANGES_AND_RESTART_ACTIVITY`, nonfinal slices downgrade to `APPLY_CHANGES`; only the final slice may restart the Activity. This prevents a process start or reload from using an intermediate overlay. If a later slice fails after an earlier slice succeeded, before returning failure the deployment must run `run-as <applicationId> rm -rf code_cache/.overlay` for every applicationId involved in this run, removing partially committed device overlays.

---
## 5. Recovery / Retry State Machine

### 5.1 Recovery

```text
recoverDeployState()
  -> clean reinstall? run pm clear first
  -> isNeedDryDeployFirst?
      -> tryDryDeploy()
          -> pm path absent: APP_NOT_INSTALLED
          -> DirectOverlayStateChecker.checkRecover()
              -> except-overlay rule matches `JuggDeployer.optimisticSwap`: `exceptOverlayId != cache.sha` means MISMATCHED (including empty history with a cache value)
              -> `isSkipExceptOverlayCheck=true`: do not compare history with cache; check only cache + device
              -> MATCHED: SUCCESS
              -> MISMATCHED: FAILED (including missing cache)
              -> UNKNOWN: fall back to legacy dry deploy
          -> restart app + waitingForDeployable(default 3s)
          -> run dry deploy payload
  -> dry deploy succeeds: no reinstall
  -> dry deploy fails / app updated / clean reinstall: install apks
  -> allowDirectOverlayRecover && direct overlay switch: defer launch after INSTALL, skip waitingForDeployable(5s)
  -> on redeploy / retry, `isSkipExceptOverlayCheck=true`: recovery `checkRecover` and deploy `optimisticSwap` both skip history/cache reconciliation; dry check after reinstall depends on skip and cache/device consistency
  -> otherwise: restart after INSTALL + waitingForDeployable(5s)
  -> DeployFileManager.resetAfterReinstall()
  -> mark follow-up replay isRecoverReplayAfterReinstall=true; restartApp after replay completes
```

Direct Overlay recovery participates in `tryDirectDryDeploy` and deferred launch only when `allowDirectOverlayRecover=true` and `JuggSettings.isEnableDirectOverlayDeploy` is enabled. On a **direct deploy failed** retry, `DeployRetryHandler` passes `allowDirectOverlayRecover=false`: recovery uses the legacy path (start app and perform an Apply Changes dry deploy; wait for the app to come online after reinstall), matching `isAllowDirectOverlayDeploy=false` on redeploy.

Reinstall recovery does not restore a historical resource type. The reinstall has stopped or replaced the old process, so follow-up replay needs only the transient `isRecoverReplayAfterReinstall` flag. In Direct Overlay recovery, `restartApp` is equivalent to the first launch. In ordinary recovery, it removes runtime caches that may remain after replaying historical resources.

Other recovery scenarios, such as overlay mismatch or a not-ready main path, keep `allowDirectOverlayRecover=true` (or inherit `DeployOptions.isAllowDirectOverlayDeploy`).

### 5.2 Retry

| Failure signal | Behavior |
|---|---|
| transient offline | Wait for ADB transport recovery, then redeploy the original deploy data. |
| `REDEPLOY_WITH_COMPAT_MESSAGE` | Run `appendCompatDeployFiles()`, then compat redeploy. |
| `JVMTI_ERROR_UNMODIFIABLE_CLASS` / `app restart` / redefiner/internal error | Fall back to HOT_FIX, then redeploy. |
| `OutOfMemoryError` / `Java heap space` / `GC overhead limit exceeded` | Do not retry in the current IDE process or automatically fall back to Gradle. Clear the compatible resource APK cache and advise restarting Android Studio, increasing IDE heap, or running Gradle install. |
| `INSTRUMENTATION_FAILED` / `IOException occurred` | Retry directly without changing the payload. |
| agent no response | Check JVMTI compatibility first; use compat deploy if needed. If JVMTI is available and the caller permits Direct Overlay, force one Direct Overlay retry so agent responses are unnecessary. |
| deploy timeout | Check JVMTI compatibility first; use compat deploy if needed. Continue with the timeout count policy below. |
| overlay id mismatch / class not found / direct deploy failed | Recover deployment state, then redeploy. For direct deploy failed, recovery disables Direct Overlay (legacy path + `isAllowDirectOverlayDeploy=false`). |
| Default installer's `INSTALL_FAILED_INVALID_APK` | Uninstall the current applicationId set and reinstall. A custom script that previously succeeded for an ordinary app runs again. |
| User restriction, device lost, APK install failure, embedded APK conflict | Stop fallback and surface the failure. |

Timeout policy: when the overlay count exceeds the first-slice threshold, first reduce slice size. Otherwise, retry after waiting for the first two occurrences, attempt reinstall on the third, and stop after the limit.

---
## 6. Direct Overlay Bypass

### 6.0 Direct Transport for Apps Incompatible with run-as

`JuggDeployerHelper` first tests the Android Studio Deployer's `run-as` prerequisite with a rollback-safe write and a unique success marker. Compatibility requires a UID in `10000..19999` and a matching SELinux context between a new `run-as` probe and the existing `code_cache`. If the marker is absent, the UID is out of range, or contexts differ, `JuggDeployer.optimisticSwap()` enters `DirectAppSandboxDeployTransport` before ordinary Direct Overlay or the AS deployer. This path is unconstrained by device readiness or the user's Direct Overlay switch: it substitutes incremental overlay transport when an Apply Changes prerequisite fails.

The same deployment obtains and reuses one resolved `AppSandboxExecutor` from `LaunchContext`. At the actual PackageManager `dataDir`, it tries ordinary shell, at most one adb root followed by reconnection of the same serial, and noninteractive `su 0`/`su -c`/`su sh -c`, in that order. After selection, Direct Overlay, the startup agent, and Hot Reload do not reselect a mode. The transport prepares the Jugg startup agent and commits Direct Overlay first. Following official Apply Changes semantics, new classes become in-memory dex elements appended to the Application ClassLoader; pure method-body changes redefine loaded classes. On Android 11+, ordinary resources/assets and mixed code-resource changes use the same dynamic request to refresh host Resources and recreate Activities according to the upper-layer semantics. A successful request keeps the process alive. Recoverable attach, resource-refresh, or other failures return `Result.needsRestart` so `JuggDeployerHelper` restarts the app. Structural changes, APK-root overlays, compatible deployment, and APK updates retain their existing restart/install paths.

If ordinary shell, root adbd, and noninteractive `su` are all unavailable, a non-compat payload requests one compat redeploy. A compat payload is staged by `RootlessCompatDeployStaging` under `/sdcard/Android/data/<package>/files/jugg/rootless-compat/<requestId>/`. On the next launch, `RootlessCompatDeployImporter` imports it from `Context.getExternalFilesDir(null)` into private `code_cache/.overlay`. The Host commits deployment cache, history, and file state only after receiving success for the matching requestId.

Files created through a Direct privilege mode may have only the static `app_data_file:s0` label. Reusing `restorecon -RF code_cache` directly would lose the app directory's dynamic MCS categories and prevent `platform_app` from executing the JVMTI agent. The executor first corrects ordinary overlay/request files to the existing `code_cache` full context, then labels `.so` files within them `apk_data_file:s0`, which Android appdomain permits executing. An internal boundary marker separates repair-script output from business-command results, so successful `restorecon`/`chcon` diagnostics cannot contaminate the `success` protocol.

Direct transport no longer rejects overlays with a class-only allowlist; it passes `data.isFullRes` unchanged to `DirectOverlayWriteRequestBuilder`. Upstream deployment still handles APK rewriting, resigning, and reinstall for Manifest/native libraries, then can replay overlays. Unavailable Direct privileges or missing deployment cache fail early, rather than falling back to an AS deployer that must fail. ADB transport/offline exceptions keep their existing transient semantics.

An empty payload still performs the complete device overlay-ID check and commits a Direct Overlay checkpoint. If the main process is running, it then sends an empty runtime request and receives an explicit terminal result, without class redefinition or Activity/app restart. If the main process is not running, it succeeds immediately after checkpoint commit.

After preparing the Jugg startup agent, Direct transport writes a `code_cache/.jugg_direct_resource_overlay` marker. On Android 11+, the startup agent uses a `LoadedApk.getResources()` hook and migrated `ResourceOverlays` to load `resources.arsc`, `res/`, and `assets/` beneath `.overlay/*.apk`. The resource loader is attached only to Resources for the real host APK; WebView and other non-host resources are unaffected. After resource commit in a running process, the dynamic agent updates loader providers, attaches them to existing host Resources, and recreates Activities. Consecutive resource updates do not reuse an old provider. When the compat-deploy marker exists, the original resource-APK path remains; Android 8–10 still take effect through process restart.

### 6.1 Trigger Conditions

`LaunchContext.isDirectOverlayEnabled = settingsEnabled && isAllowedByCaller && (!isDeviceReadyDeploy || forceDirectOverlayDeploy)`.

Direct Overlay is an overlay-write bypass for offline or non-ready scenarios, not a replacement for online HOT_RELOAD. The outer layer checks eligibility before slicing. Immediately before swap, it still requires Android O or later, an existing deployment cache, available startup-agent metadata or permission to skip it, and a device overlay ID matching expectations.

Ordinary `DirectOverlaySwapTransport` writes overlay files to the app sandbox only; it does not refresh resources, redefine classes, or recreate an Activity in a running process. Consequently, after an ordinary Direct Overlay write succeeds, `JuggDeployer.Result.needsRestart=true` is a fixed contract and `JuggDeployerHelper` must restart the app, even if `isAppForeground=true`. `DirectAppSandboxDeployTransport` has a different contract: it can apply changes at runtime and independently returns `needsRestart` based on attach, redefine, and resource-refresh results, so a successful request can preserve the process.

`isDeviceReadyDeploy=false` says only that Android Studio currently has no deployable client for Apply Changes. It does not prove that the app is not running or that its APK has `debuggable=false`. For example, an older Android Studio DDMLib may fail to recognize a running process on a newer Android version even though `run-as`, the device process, and sandbox writes work normally. Jugg retains Direct Overlay on a best-effort basis: that channel independently validates deployment cache, `run-as`, and the device overlay checkpoint. Deployment proceeds if those checks pass; failure is surfaced only if the alternate channel also fails.

The former path implicitly assumed that a nonrunning app caused `NO_DEPLOYABLE_APP`, then lifecycle launched the app after a Direct Overlay write. On Android 15 and later, older IDEs such as Android Studio Eel may report `NO_DEPLOYABLE_APP` while the app is already foregrounded. Direct Overlay selection and lifecycle's launch condition then diverge. Without explicitly propagating `needsRestart=true`, the overlay would be written but the current process would keep old code/resources until a manual restart.

In related logs, `ideClientPids` comes solely from Android Studio/DDMLib's client list, not the device's actual process list. When `NO_DEPLOYABLE_APP` occurs with a foreground app and Direct Overlay enabled, print `App is running but not deployable by Android Studio. Direct Deploy will restart the app after deployment.` before selecting the bypass. For a nonforeground app, print `Android Studio deployable client unavailable, try Best-effort Direct Deploy fallback.`. After a successful Direct Overlay commit, also print `IDE deployment unavailable, Direct Overlay fallback succeeded.`; lifecycle should then show `Restarting app...`. Investigate actual process and privilege state with `adb shell pidof <packageName>`, `run-as <packageName>`, and the same time window in IDE `idea.log`.

If the input deploy type is `HOT_RELOAD` but this run actually restarts the app, promote the final result to `HOT_FIX`; the user sees `Jugg HOT_FIX SUCCESSFUL ...` and `App restarted.`. Only a genuine HOT_RELOAD without a process restart displays `App deployed.`. `needsRestartApp` means this run actually requires a restart; it does not identify a Direct Overlay source. Therefore print path-specific messages when selecting Direct Overlay, rather than trying to infer the deployment path from `needsRestartApp` at finish time.

`isAllowedByCaller` comes from outer lifecycle. The default main deploy path allows it; special callers may explicitly disable it. Direct Overlay replaces only the overlay-update transport; `JuggDeployerHelper.runTask()` still owns subsequent start/restart/androidTest actions.

### 6.2 Swap Path

```text
JuggDeployer.optimisticSwap()
  -> load deployment cache
  -> except overlay id check
  -> tryDirectOverlaySwap()
      -> DirectOverlaySwapTransport.canTry()
      -> ensureApplyChangesStartupAgent()
      -> DirectOverlayStateChecker.checkDevice()
      -> DirectOverlayWriteRequestBuilder.build()
          -> OverlayUpdateBuilder deduplicates by qualifiedPath, keeping the first, so the original APK file cannot overwrite incremental resources during full resource push
          -> request builder deduplicates by overlay path, keeping the first, so overlapping new/modified classes cannot make duplicate ZIP entries
      -> DirectOverlayWriter.write()
          -> zip overlay files
          -> push /data/local/tmp/jugg/direct-overlay-*.zip
          -> run apply script through AppSandboxExecutor using a no-fallback shell, so ADB fallback cannot reenter a non-idempotent script
          -> delete old id
          -> start heartbeat to prevent a long silent write from reaching ADB inactivity timeout
          -> delete old files covered by this payload
          -> for full resource push, skip per-file deletion under base.apk and unzip the whole resource batch; retain prior Dex and other untouched overlays
          -> for base install with empty overlay id, skip payload cleanup so clear-data/NO_DIR initial full push does not generate many useless rm commands
          -> unzip when payload files exist; skip extraction for an empty payload but commit the checkpoint, so the device cannot reject an empty ZIP
          -> chmod *.dex 0444
          -> write new id last
      -> JuggDeploymentService.storeEntry()
  -> direct returns null: fall back to legacy Apply Changes
```

When legacy Apply Changes enters `JuggDeployTask.perform(APPLY_CHANGES)`, it creates an Android Studio debugger redefiner only if classes changed and this run does not require app restart. Empty changes or pure overlay/update-APK cases pass no debugger redefiner, preventing AS deployer from mistakenly invoking debugger redefine without a class swap.

For a base install cache, the expected device overlay ID is an empty string. Only non-base installs require the device overlay ID to equal the cached sha.

### 6.3 Dirty Semantics

- If the writer fails before modifying the overlay directory, it returns `SKIPPED` and permits fallback to legacy Apply Changes.
- If it fails after modifying that directory, or a reentered script finds that the overlay ID is already absent, it returns `FAILED_DIRTY` and throws `DirectOverlayDirtyException`. It must not continue into legacy Apply Changes and pretend to roll back a partially committed state.

The Direct Overlay write script emits periodic heartbeats and invokes ADB through `execAdbShellScriptNoFallback()` → `invokeAdbShellCmd()` with a `5 SECONDS` continuous-no-output timeout. Heartbeats extend a long write while connected, but do not mask disconnection or transport errors or impose a separate total duration limit. The heartbeat `sleep` subprocess does not inherit ADB input/output; the heartbeat shell terminates immediately when the script ends, avoiding an occupied ADB output pipe or fixed exit delay. Direct privilege mode recursively repairs the entire `code_cache` when a command exits. That is a performance concern as file volume grows; it does not change whole-batch deployment or failure contracts.

### 6.4 Capability Boundary Between Direct App Sandbox and Official Apply Changes

Direct app sandbox is a minimal substitute when an app fails Android Studio Apply Changes' app-sandbox prerequisite, not a complete reproduction of the official Deployer. Android still provides JVMTI and resource-loading capabilities, but the official channel rejects apps that violate its `run-as`, ordinary-UID, or SELinux-context contract first. Direct therefore implements overlay writes, dynamic-agent requests, resource refresh, and Activity recreation itself. The impact is isolated by the `applyChangesCapability == INCOMPATIBLE` entry gate: compatible apps still use official Apply Changes, and ordinary Direct Overlay remains an independent transport.

Current coverage includes pure method-body changes, ordinary resources/assets, mixed method-body and resource changes, and continued effect after a cold launch. The following are current implementation boundaries; passing common acceptance scenarios does not make the two transports fully equivalent:

| Dimension | Current Direct app sandbox boundary | Result or fallback |
|---|---|---|
| Android version | Live ordinary-resource refresh depends on Android 11+ `ResourcesLoader`. | On Android 8–10, restart the process after overlay commit and load through the startup agent. |
| Process scope | `pidof <package>` selects one main-process PID and cross-checks it with the known PID for this run; one dynamic request attaches only to that process. | Separate processes do not receive online class/resource refresh in the same run and need their respective process restart. |
| Activity scope | Traverse main-process `ActivityThread.mActivities` and recreate all surviving Activities; fall back to window-associated Activities if reflective reading fails. | Covers background Activities, other tasks, and multiwindow instances in the same process; separate processes still need a restart to load the overlay. |
| Class payload | Append `newClasses` as in-memory dex elements under official semantics; redefine `hotReloadModifiedClasses` through JVMTI, requiring each modified Dex to map uniquely to one class descriptor. | New classes can load in the current main process. Structural changes, unmodifiable classes, or a modified Dex without a unique mapping use overlay plus process restart. The official channel still has fuller PID/redefiner and Dex-metadata orchestration. |
| Resource types | Online refresh covers only ordinary host resources represented by `res/**`, `assets/**`, and `resources.arsc`. | APK-root, legacy/modern Compose, and other resources relying on ClassLoader or in-process caches still require process restart. |
| Batch model | Both ordinary Direct and Direct app sandbox deploy whole batches without entering `SliceDeployHelper`. | No official Apply Changes slice progress, per-slice retry, or aggregated slice result; large-payload failure granularity is coarser. |
| State prerequisites | Deployment cache must exist, and expected overlay ID must match device state. | Missing cache, mismatched state, or unavailable Direct privilege fails explicitly and enters existing recovery/reinstall. Direct transport cannot rebuild the baseline itself. |
| Protocol and diagnostics | A compact V4 text request/result protocol distinguishes `NEW` / `MODIFIED` and permits empty requests. Host polls for the result; JNI failures preserve exception type/message, and main-thread resource application has a separate timeout. | Fewer phase diagnostics and debugger-coordination features than official Deployer; heartbeat does not cancel ADB timeout. Timeout, disconnection, and absent results remain failures. |
| Platform adaptation | Resource refresh depends on internal `ResourcesManager` references, host-APK path filtering, and `ResourcesLoader`. | On OEM/framework differences that break refresh, fall back to process restart; coverage of all official Agent version adaptations is not promised. |

`updateApkFiles` such as Manifest and native libraries remain the responsibility of APK rewriting, resigning, and installation; they are not capabilities to add to the Direct dynamic agent. Direct aims to give apps that cannot use official app-sandbox transport common incremental deployment results while preserving existing lifecycle, recovery, and install boundaries.

---

## 7. Hidden Constraints

- `overlay id` is the central deployment-consistency checkpoint. A mismatch among Jugg history, project deployment cache, and device overlay directory can trigger reinstall or recovery.
- Deployment cache is project state; `memoryCache` belongs to one project Runtime only. When switching between IDEA and standalone, invalidate the old Runtime memory cache and read the latest disk snapshot under the project lock. Never reuse cache across projects or Runtimes.
- `exceptOverlayIds` prevents state from leaking for the same package across projects/devices. Recovery or slices within one run skip this check when necessary.
- Sliced deployment must not leave a partially committed overlay. Once an earlier slice succeeds and a later one fails, clear device `code_cache/.overlay` before returning failure.
- `JuggDeployData.filterForApks()` is for deployer transport only. Do not use scoped data after trimming to update global file state or history.
- `DeployItem.targetApkPaths` names the actual deployment targets; `apkPath` remains the old single-APK anchor. Prefer `targetApkPaths` when determining resource/overlay ownership.
- After a self-targeting library Test APK backfill succeeds, immediately merge its new overlay IDs into `deployHistoryManager.lastDeployOverlayIds`; otherwise the first replay mistakenly sees a state mismatch and reinstalls.
- Compat deployment removes original res/asset overlays, adds an enable flag, and generates a resource-APK deploy item from resource overlays.
- APK-root overlays require process restart. Activity restart cannot reliably clear ClassLoader, legacy Compose resource, or `JarURLConnection` caches.
- Modern Compose resources require process restart even when their final paths lie under `assets/**`; Activity restart cannot be relied upon to clear `AssetManager` / Compose runtime caches.
- A Flutter JIT overlay update changes only the overlay directory, so Flutter keeps using the old extracted `kernel_blob.bin` in `app_flutter`. Only removing `app_flutter/res_timestamp-*` triggers extraction again. The invalidation command may delete only ordinary files directly under `app_flutter` whose names start with `res_timestamp-`; it must not touch `flutter_assets`, the kernel, overlays, or other app data. An absent timestamp is an idempotent success.
- Invalidation and overlay commit are not one filesystem transaction. If invalidation fails, this deployment must fail without committing success history. The device overlay may already have changed; the next run reconciles through the existing overlay-ID mismatch/recovery path. Flutter Profile/Release AOT uses the `libapp.so` APK-update path and does not enter this invalidation flow.
- `CompatDeployHelper` returns true for API < 30, device compatibility records, and all HarmonyOS devices. It recognizes HarmonyOS from a nonempty `hw_sc.build.platform.version` property and does not persist it as a manual Force record.
- The Dex-merge threshold is `DeployDataPlanner.MAX_DEPLOYED_DEX_COUNT = 1000`. Above it, merge staging Dex with historical Dex not already staged. If merge fails, retain original data and continue deployment.
- Transient-offline handling aims to recover near the failure point: shell/deployer waits in place and retries once; orchestration handles only offline failures that propagate upward.
- A transient failure on the default installer path may upgrade DELTA to FULL install. A custom script failure itself is not retried automatically; other failures after successful script execution retain their original policies. Not every install failure should enter incremental fallback.

---

## 8. Investigation Entry Points

| Symptom | Start with |
|---|---|
| `Deploy state not match, start reinstalling app...` | `DeployStateRecover.tryDryDeploy()`, `DirectOverlayStateChecker.checkRecover()` |
| `OVERLAY_ID_MISMATCH` or “state unknown to Studio” | `JuggDeployer.optimisticSwap()` |
| Direct Overlay does not trigger | `LaunchContext.logDirectOverlayEnabled()`, `DirectOverlaySwapTransport.canTry()` |
| Cannot fall back after Direct Overlay | `DirectOverlayWriter.write()` |
| App always restarts after deployment | `JuggDeployData.isNeedRestartApp`, `JuggDeployerHelper.runTask()` |
| Flutter Debug Dart change still runs old code | `flutterJitRuntimeFiles` in `DeployDataPlanner.buildDeployData()`, `JuggDeployOrchestrator`, `FlutterJitCacheInvalidator` |
| Rolled-back library Dex remains effective | `JuggDeployerHelper.removeLibraryDexFiles()` |
| androidTest deploys to wrong APK | `JuggDeployData.groupByApplicationId()`, `filterForApks()`, `LibraryTestApkBackfillHelper` |
| Install error is too generic | `AdbLogWrapper.realErrorMessage`, `JuggDeployer.install()` |

---

## 9. Related Documents

- End-to-end deployment: `03_deploy_complete.md`
- Impact analysis and deployment-data generation: `03_deploy_data_generator.md`
- Constant-reference impact analysis: `03_deploy_const_ref.md`
- JVMTI agent coordination: `03_runtime_jvmti.md`
- Deployment test placement: `06_testing.md` §7.1
