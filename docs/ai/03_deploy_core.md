# Deployment System: Core Deployment Mechanisms

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page covers the device deployment lifecycle: payload state, install and incremental paths, recovery, retries, and the boundaries among official Apply Changes, Direct Overlay, and Direct app sandbox. For the user-triggered Run flow see `03_deploy_complete.md`; for effect analysis see `03_deploy_data_generator.md`; for system-app installation and signing see `03_deploy_system_app.md`.

## 2. Core Source Index

| Owner | Path | Responsibility |
|---|---|---|
| `JuggDeployerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | Chooses install, embedded, or incremental deployment, then handles retry and final file/history state |
| `JuggDeployOrchestrator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt` | Runs one device lifecycle: slices, transport, agent, Flutter invalidation, launch, and JVMTI check |
| `LaunchContextFactory` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/LaunchContextFactory.kt` | Binds the executor, ADB adapter, sandbox capability, and Direct eligibility for one device run |
| `JuggDeployData` / `DeployItem` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | Device payload, APK ownership, deploy category, and restart semantics |
| `DeployFileManager` / `DeployDataPlanner` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployDataPlanner.kt` | Stage compiled outputs, build payloads from current and historical data, and commit or reset file state |
| `DeployStateRecover` / `DeployRetryHandler` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployStateRecover.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt` | Restore an uncertain baseline and classify recoverable failures |
| `JuggDeployTask` / `JuggDeployer` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | Scope transport calls by application ID, then install or swap through the selected executor |
| `JuggDeploymentService` / `JuggDeploymentCacheStore` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/cache/JuggDeploymentCacheStore.kt` | Keep the project disk deployment snapshot and Runtime-local cache coherent |
| `DeployHistoryManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployHistoryManager.kt` | Tracks prior deploy state and expected overlay IDs across runs |
| `FlutterJitCacheInvalidator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/flutter/FlutterJitCacheInvalidator.kt` | Clears Flutter's extraction timestamp after a current JIT asset overlay is committed |
| `DirectOverlaySwapTransport` / `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlaySwapTransport.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | Bypass the official overlay write when Direct Overlay is enabled; distinguish clean skip from dirty failure |
| `DirectAppSandboxDeployTransport` / `AppSandboxExecutor` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/DirectAppSandboxDeployTransport.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/AppSandboxExecutor.kt` | Substitute overlay and runtime application when an app violates official app-sandbox prerequisites |
| `RootlessCompatDeployStaging` / `RootlessCompatDeployImporter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/RootlessCompatDeployStaging.kt`, `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/RootlessCompatDeployImporter.java` | Stage and confirm compatible payload import when no app-sandbox privilege is available |
| `DirectOverlayStateChecker` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayStateChecker.kt` | Reconcile history, cache, and device overlay IDs during recovery; check device state before a swap |

The shared deployer uses Jugg-owned types in `deploy_compat/interface`; each IDE or standalone executor adapts real deployer types at its boundary. Compatibility implementation details belong in `04_engineering_compat.md`.

## 3. Payload and Checkpoint State

| Payload condition, in precedence order | `DeployType` | Lifecycle effect |
|---|---|---|
| Embedded setting | `EMBEDDED` | Rewrite incremental files into an APK, then install. |
| Install request | `INSTALL` | Install APKs and establish deployment cache/overlay checkpoint. |
| Warm-up | `WARM_UP` | Dry payload with no business change. |
| Compatible mode | `COMPAT_HOT_FIX` | Package compatible resources and restart as needed. |
| `isNeedRestartApp` | `HOT_FIX` | Apply overlay and restart the process. |
| Otherwise | `HOT_RELOAD` | Apply Changes; a nonempty payload normally recreates the Activity while keeping the process. |

For an incremental request, `JuggDeployerHelper` also switches a non-debuggable APK to embedded-APK installation; this decision precedes the ordinary incremental payload path.

`isNeedRestartApp` is true for hot-fix classes, nonempty overlay-only work, APK-root overlays, current Compose-resource compilation with a nonempty payload, current Flutter JIT runtime assets, or replay after reinstall. APK-root means a final overlay path outside `res/**`, `assets/**`, and `resources.arsc`; ClassLoader and resource caches need a process restart. This path-based rule can conservatively restart a classpath resource using an unfamiliar Android path, or miss one deliberately named like `res/**`. Compose resources also need one even when stored under `assets/**`. `isNeedRestartActivity` applies to non-warm-up, nonempty payloads without a process restart, so `HOT_RELOAD` does not promise an Activity stays intact.

`DeployDataPlanner` recognizes Compose from the current compiled-file type, before that identity is lost in historical overlays. It recognizes Flutter JIT assets only from current Flutter `Asset` staging outputs named `assets/flutter_assets/kernel_blob.bin`, `vm_snapshot_data`, or `isolate_snapshot_data`, before full-resource expansion could add an old baseline file. Both signals are transient and must survive retry; warm-up and install do not carry them. Profile/Release Flutter AOT updates `libapp.so` through APK replacement instead.

Flutter extracts JIT assets into app-private `app_flutter` and uses `res_timestamp-*` files to decide whether to extract them again. The overlay does not change APK `lastUpdateTime`. After every overlay slice succeeds, `JuggDeployOrchestrator` removes only matching ordinary timestamp files directly under `app_flutter`, then restarts the process. A missing timestamp succeeds. If sandbox access or deletion verification fails, deployment fails before success history is committed; the changed device overlay is reconciled on the next run through the overlay-ID recovery path.

```text
changed files → DeployFileStateTracker → successful compile outputs in staging
  → DeployFileManager.getDeployData() → DeployDataPlanner.buildDeployData() combines staging, deployed history, APK ownership, and effect analysis
  → transport may filter full JuggDeployData by applicationId/APK
  → completed deployment → DeployHistoryManager → DeployFileManager.commit(full data) → overlay IDs
reinstall recovery → DeployFileManager.resetAfterReinstall() → replay historical outputs with restart flag
```

`filterForApks()` produces transport-scoped data only. Commit, retry state, and global history must use the full payload. A deploy key includes target APK and relative path: same-name `resources.arsc` files for app and androidTest coexist, while new staging output shadows the old record for the same target. Explicitly scoped staging Dex also replaces an unscoped historical Dex at the same path. `targetApkPaths` is the actual target set; `apkPath` remains a legacy single-APK anchor.

Before an incremental deploy following a dependency change, `JuggDeployOrchestrator` removes device library Dex associated with a rolled-back dependency version. It also removes previously deployed library Dex for a class rollback when that Dex is absent from the current payload. This reconciles dependency-change state with installed overlay state before the next swap; a deletion failure warns and deployment continues, so inspect that warning if reverted library code remains effective.

An androidTest launch can fail after incremental deployment already changed the device. On the last target, `JuggDeployerHelper` still commits that deploy state before returning the test-launch failure, so a later run does not replay already deployed files. Diagnose the test-launch result separately from the deployment checkpoint.

The project deployment snapshot is `<projectDir>/build/jugg/database/deploy_cache.db`. `JuggDeploymentService` caches parsed entries only within one Runtime and invalidates them when the project owner, disk generation, or executor changes; disk writes are serialized under the project lock and replace the snapshot atomically. A missing or incompatible cache is a recovery input, not permission to invent an overlay baseline. History, cache, and device overlay ID must agree. For a base install, the expected device overlay ID is empty even though the cache stores a base-install ID.

## 4. Install and Incremental Paths

```text
JuggDeployerHelper.deploy(isInstall)
  → install: forInstall(apks) → JuggDeployOrchestrator → JuggDeployTask grouped by applicationId
     → JuggDeployer.install → deployment cache → last device publishes overlay IDs
  → incremental: DeployFileManager.getDeployData → optional Test APK backfill
     → updateApkFiles: IncrementalDeployHelper.updateApk() rewrites/signs APK, then recovers installation baseline
     → not-ready or project switch: DeployStateRecover.recoverDeployState() dry-checks or reinstalls
     → optional quick HOT_FIX conversion → JuggDeployOrchestrator → JuggDeployTask
     → last device updates history, commits full data, then publishes overlay IDs
```

The install branch stops the app before installing. The ordinary-app custom APK install script comes from the Run Configuration through `DeployOptions` and `LaunchContext`; androidTest APKs use the default installer. The script runs on the IDEA Host for Gradle install, embedded install, APK-update recovery, and reinstall recovery. Jugg verifies that the package exists and its device APK checksum matches, then clears an old app-sandbox overlay when possible and writes a fresh cache. Script exit/cancellation/verification failure is final for this attempt and does not enter the default installer's retry or Gradle fallback. If the sandbox is unavailable, only the extra overlay cleanup is skipped.

`updateApkFiles` (for example Manifest or native libraries) goes through APK rewriting and installation, not the Direct dynamic agent. A configured signing script replaces only the resign step of an incrementally rewritten APK; Jugg still verifies the signature and replaces the original file only on success. Failure preserves the original APK and does not fall back to local keystore signing. See `03_deploy_system_app.md` for the script contracts.

For androidTest, a self-targeting library Test APK can be backfilled before incremental replay. Its newly installed overlay IDs must be merged into `lastDeployOverlayIds` immediately; otherwise the following swap falsely sees a state mismatch. The main `JuggDeployTask` still scopes each application ID separately.

`JuggDeployOrchestrator` selects INSTALL, APPLY_CHANGES, or APPLY_CHANGES_AND_RESTART_ACTIVITY from the full payload. Official Apply Changes may slice large work; nonfinal slices cannot recreate the Activity, and only the first slice checks the previous overlay ID. If a later slice fails after an earlier one succeeds, it clears each affected app's `code_cache/.overlay` before returning failure. Direct Overlay and Direct app sandbox use one whole batch. The official debugger redefiner is supplied only when classes change without a required process restart; pure overlay, APK update, and empty changes do not need it. After successful slices, the orchestrator handles Flutter invalidation, agent push, process start/restart or androidTest launch, rootless import confirmation, and JVMTI checks. A Debug run also requests a process restart before debugger attach. On a first Compose-resource overlay with a known Android 15/older-IDE Activity-relaunch issue, `ComposeResourceRestartHelper` waits for transform-cache readiness and restarts the process a second time.

Compatible deployment adds its enable flag and a resource APK made from resource overlays instead of sending the original res/asset overlays. `CompatDeployHelper` selects it for API < 30, ASUS devices, recorded device compatibility, or a nonempty HarmonyOS `hw_sc.build.platform.version`; automatic ASUS/HarmonyOS detection is not persisted as a manual Force record. If current staging Dex plus historical Dex exceeds `DeployDataPlanner.MAX_DEPLOYED_DEX_COUNT` (1000), Dex merge is attempted; failure retains the unmerged data and continues.

## 5. Recovery and Retry

```text
uncertain device state → DeployStateRecover.recoverDeployState() → tryDryDeploy() when eligible
  → app absent, history/cache/device mismatch, or failed dry deploy: reinstall APKs
  → successful dry deploy or Direct state check: keep the existing baseline
  → reinstall: reset file state, rebuild payload, mark follow-up replay for process restart
failure in transport/lifecycle → DeployRetryHandler classifies cause
  → one changed recovery action (offline wait, HOT_FIX, compat, Direct bypass, or reinstall)
  → final failure returns fallback eligibility to the Run layer
```

Direct recovery distinguishes `MATCHED` (keep baseline), `MISMATCHED` (reinstall, including missing cache), and `UNKNOWN` (try the legacy dry deploy). The normal except-overlay check compares Jugg history with cache, then cache with device. A same-run retry/replay can skip the history comparison because the device ID has already advanced; it must still check cache/device. A direct-transport failure disables Direct Overlay during its recovery and redeploy, so a failing bypass is not selected again. A reinstall with Direct Overlay recovery may defer app launch until replay; ordinary recovery waits for a deployable app.

The `exceptOverlayIds` history-to-cache check detects when the same package name is used by another project on a device, or one project deploys to different devices. Skipping it is limited to the current run's recovery/replay; it does not make a mismatched device overlay ID valid.

| Failure signal | Recovery decision |
|---|---|
| Transient ADB offline | Recover transport near the failing shell/deployer operation where possible; an upward failure waits and redeploys once. |
| `REDEPLOY_WITH_COMPAT_MESSAGE` or JVMTI compatibility | Build compatible payload and redeploy. |
| Unmodifiable class, app-restart request, redefiner/internal error | Convert classes to HOT_FIX and redeploy. |
| Agent no response or timeout | Check JVMTI compatibility; eligible agent failure may force one Direct Overlay retry. Timeouts first reduce slices for large payloads, then use bounded wait/retry and reinstall. |
| Overlay-ID mismatch, missing class, or Direct deploy failure | Recover baseline, then replay; a Direct failure uses the legacy route for that retry. |
| Default installer's `INSTALL_FAILED_INVALID_APK` | Uninstall the target application IDs and reinstall; a previously successful custom script may run again. |
| Default installer's transient `not found` error during DELTA install | Wait for ADB transport recovery, then retry once in FULL mode; a transient offline error without `not found` keeps its install mode. |
| OOM (`OutOfMemoryError`, heap-space, GC-overhead) | Clear compatible resource-APK cache and stop retry/fallback in the same IDE process; advise IDE restart, more heap, or Gradle install. |
| User restriction, lost device, install failure, embedded-APK conflict | Stop automatic fallback and surface the cause. |

For install and incremental failures, inspect `AdbLogWrapper.realErrorMessage` before treating a generic deployer wrapper as the device cause. A custom install-script failure itself has no automatic retry; a later failure after a successfully verified script follows its own classification.

## 6. Direct Transport Boundaries

| Route | Entry condition | Runtime/lifecycle contract |
|---|---|---|
| Official Apply Changes | App passes the deployer's app-sandbox prerequisites and Direct Overlay is not selected. | Deployer owns swap/redefine; `JuggDeployOrchestrator` still owns slicing, restart/start, recovery, and commit. |
| Ordinary Direct Overlay | Enabled by settings and caller, with device not ready or a forced Direct retry; requires Android 8+, cache, startup-agent eligibility, and matching device overlay ID. | Replaces only the overlay-write action. It always returns `needsRestart=true`, even if the app is foreground; the orchestrator restarts the process. A clean pre-mutation skip may fall through to official Apply Changes. |
| Direct app sandbox | Android 8+ and the official `run-as`/UID/SELinux probe reports `INCOMPATIBLE`; independent of the ordinary Direct switch and IDE readiness. | Commits overlay, then may append new classes, JVMTI-redefine modified classes, refresh ordinary host resources on Android 11+, and recreate Activities. Recoverable runtime failures use the committed overlay with process restart. |
| Rootless compatible import | Direct app sandbox is selected but ordinary shell, root adbd, and noninteractive `su` cannot reach the sandbox. | Non-compat payload requests compat redeploy. The compatible payload is staged under `/sdcard/Android/data/<package>/files/jugg/rootless-compat/<requestId>/`; the startup importer commits it privately, and Host cache/history/file state advance only after a matching requestId success. |

`AppSandboxExecutor` tests official compatibility with a rollback-safe write, UID in `10000..19999`, and a SELinux context matching existing `code_cache`. If incompatible, one resolved sandbox mode is shared by overlay writer, startup agent, and dynamic agent for the run. Direct privilege modes repair the actual app directory's dynamic MCS context and label executable `.so` files appropriately; using a static `app_data_file:s0` label alone can make a `platform_app` unable to execute its JVMTI agent. Repair-script output is separated from the business-command result.

Direct app sandbox writes `code_cache/.jugg_direct_resource_overlay` when it prepares the startup agent. On a cold start, that marker enables host-APK resource overlays through `InstrumentationHooks` and `ResourceOverlays`; without it, a committed overlay alone does not establish the Direct resource-loading path. Compatible resource-APK deployment keeps its separate resource path.

`DirectOverlaySwapTransport` calls `DirectOverlayWriter.write()` to compare the expected device ID before mutation, update files, and write the new ID last. A failed push or pre-mutation script returns `SKIPPED`; a failure after mutation or an already-missing old ID returns `FAILED_DIRTY`. Dirty failure must not fall into another transport or claim rollback. The write uses a no-fallback ADB shell script with a five-second continuous-no-output timeout and heartbeats for long silent operations; heartbeats do not hide disconnects or transport errors or impose a total-duration limit. Full-resource writes replace the resource batch without deleting unrelated historical Dex. Empty payloads can commit an overlay checkpoint without trying to unzip an empty archive; a running Direct app sandbox process then receives an empty runtime request with a terminal result, while a stopped one succeeds after the checkpoint.

`isDeviceReadyDeploy=false` means Android Studio lacks a deployable client, not that the app process is absent. `ideClientPids` in the deploy log comes from IDE/DDMLib, not `pidof`. An older IDE may report `NO_DEPLOYABLE_APP` while the app is running; ordinary Direct Overlay then restarts the process after writing, and a reported `HOT_RELOAD` is promoted to `HOT_FIX` if that restart actually occurred. Distinguish this from Direct app sandbox, whose successful dynamic request can preserve the process.

Direct app sandbox is intentionally narrower than the official Deployer:

| Dimension | Boundary and consequence |
|---|---|
| Android version / resources | Live refresh of ordinary `res/**`, `assets/**`, and `resources.arsc` uses Android 11+ `ResourcesLoader`; Android 8–10 restart and load through the startup agent. APK-root and Compose resources also restart. The loader attaches only to Resources for the host APK, not WebView or other packages. |
| Process / Activity | A dynamic request targets one main-process PID checked against known PIDs. It can recreate surviving Activities in that process, including background instances; other processes need their own restart. |
| Classes | New classes append as in-memory Dex; modified classes require one descriptor per Dex for JVMTI redefine. Structural or unmodifiable changes use overlay plus process restart. |
| Batch and diagnosis | Direct sends one batch, without official per-slice progress/retry. The V4 request/result protocol confirms terminal success; timeout, disconnection, missing result, or JNI/resource error remains a failure or explicit restart decision. |
| Baseline | Missing cache, device-ID mismatch, or unavailable Direct privilege cannot be repaired by the transport itself; recovery/reinstall owns that decision. |

## 7. Investigation Boundaries

| Observation | What it establishes | Next discriminating evidence |
|---|---|---|
| `Deploy state not match, start reinstalling app...` | Recovery decided the current checkpoint could not be reused; it does not identify which of history, cache, or device disagreed. | Compare `DeployStateRecover.tryDryDeploy()`, cache entry, `DirectOverlayStateChecker.checkRecover()`, and device ID. |
| `OVERLAY_ID_MISMATCH` | The optimistic swap rejected its expected checkpoint; it does not prove which project or device last wrote it. | Compare `exceptOverlayIds`, cache SHA/base-install flag, and device overlay ID. |
| `NO_DEPLOYABLE_APP` with foreground app | IDE client discovery failed, not necessarily device process or sandbox access. | Compare IDE `ideClientPids` with device `pidof`, `run-as` probe, and same-window `idea.log`. |
| Direct write failure | `SKIPPED` and `FAILED_DIRTY` have different fallback safety. | Read `DirectOverlayWriter` marker/output and check whether the overlay directory was mutated before attempting another transport. |
| Flutter Debug Dart edit still runs old code | A new overlay may coexist with old extracted `app_flutter` assets. | Check current `flutterJitRuntimeFiles`, timestamp invalidation result, actual restart, and overlay ID before blaming compilation. |
| androidTest or multi-APK resource appears in the wrong APK | The payload may have been scoped with the legacy single-APK anchor. | Inspect `targetApkPaths`, `groupByApplicationId()`, and file-state deploy keys. |

## 8. Related Documents

- End-to-end Run flow: `03_deploy_complete.md`
- Effect analysis and payload generation: `03_deploy_data_generator.md`
- JVMTI coordination: `03_runtime_jvmti.md`
- System app and script boundaries: `03_deploy_system_app.md`
- Verification strategy and test ownership: `06_testing.md` §7.1
