# Deployment System: End-to-End Flow (Run to Device)

> Last checked: 2026-09-23
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Scope

This page answers how compilation results enter deployment after a user clicks Run, and how those results become user-visible feedback.

For transport, Direct Overlay, and impact-analysis details, see `03_deploy_core.md`, `03_deploy_data_generator.md`, and `03_deploy_const_ref.md`, respectively.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `JuggRunningTask` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | Orchestrates Run: prepares UI/logging, invokes compilation, deploys to each device, aggregates results, and decides on Gradle fallback. |
| `JuggCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | Produces `CompileTaskResult` and chooses incremental or Gradle compilation for this run. |
| `JuggDeployerHelper` / `JuggDeployOrchestrator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt` | The helper selects install, embedded, or incremental deployment; the orchestrator runs the shared single-device lifecycle. |
| `DeployOptions` / `DeployTaskResult` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployHelperBean.kt` | Request/result contract between Run orchestration and the deploy helper. |
| `LaunchContext.customApkInstallScript` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/LaunchContext.kt` | Optional install script for the current Run Configuration. It flows through `DeployOptions`, deploy/recovery requests, and `LaunchContextFactory`, then runs on the IDEA Host. |
| `DeployOptions.customApkSignScript` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployHelperBean.kt` | Optional APK-signing script for the current Run Configuration; passed only to the APK-update boundary, not to the installer. |
| `JuggDeployData` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | Source of deployment payload and final deploy type. |
| `DeployStateManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` | Per-device state indicating current incremental-deployment eligibility and whether recovery is needed. |
| `DeployHistoryManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployHistoryManager.kt` | Records the previous deployment checkpoint; advances after successful install/incremental deployment. |
| `JuggServer` | `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt` | Reports compile/deploy telemetry; does not participate in deployment decisions. |

---

## 3. End-to-End State Model

| State/data | Producer | Consumer | Meaning |
|---|---|---|---|
| `CompileTaskResult.isGradleCompile` | `JuggCompilerHelper.compile()` | `JuggRunningTask.deployDevice()` | Successful Gradle compilation leads to install; successful incremental compilation leads to incremental changes. |
| `CompileTaskResult.isSuccess` | `JuggCompilerHelper.compile()` | `JuggRunningTask.doRun()` | On failure, end immediately without deploying to devices. |
| `CompileUiHandler.isSkipDeploy` | UI / MCP caller | `JuggRunningTask.doRun()` | Compilation succeeded but deployment is explicitly skipped. Reset hasRun so the next run does not incorrectly report no file changes. |
| `DeployTaskResult` | `JuggDeployerHelper.deploy()` | `JuggRunningTask.doRun()` | Per-device success, deploy type, fallback eligibility, and failure reason. |
| `RunResult` | `JuggRunningTask.doRun()` | `JuggRunningTask.run()` | Final feedback to UI, dependency-change manager, and hasRun state. |

---

## 4. Core Call Chain

### 4.1 Main Run Path

```text
JuggRunningTask.run()
  -> prepare Run Tool Window / JuggLogger / juggServer.onCompile()
  -> JuggCompilerHelper.compile()
  -> compile failure: show Run window + return failed RunResult
  -> skip deploy: return RunResult with compile success but no deployment
  -> read one snapshot of IDE-selected running devices
  -> no devices: rebuild incremental context after Gradle compilation, return deployment failure
  -> call deployDevice() per device in snapshot order
  -> aggregate DeployTaskResult list
  -> all succeed: print final success log, call initIncrementalCompileTask() after Gradle compilation
  -> partial failure with fallback allowed: force Gradle and recurse into doRun()
  -> partial failure without fallback: return failed RunResult
```

The Run layer decides whether to deploy, whether to fall back for the entire run, and how to aggregate UI results. Install/recovery/retry details live elsewhere. The Debug executor additionally sets `CompileUiHandler.isAlwaysRestartApp` to true, causing both ordinary incremental deployment and empty-change deployment to restart the app after success; the IDE layer then attaches the Java debugger.

### 4.2 Single-Device Deployment Path

```text
deployDevice()
  -> set DeployOptions.isInstall from CompileTaskResult.isGradleCompile
  -> JuggDeployerHelper.deploy()
  -> for install/reinstall, group by applicationId; ordinary apps may run a custom APK install script, while androidTest uses the default installer
  -> report deploy_failed_reason / deploy_type / device in detail
  -> on success, show user-visible notification for the deploy type
```

Gradle compilation maps to `isInstall=true`; incremental compilation maps to `isInstall=false`. This boundary selects `deployInstall()` or `deployIncrementalChanges()` downstream.

Incremental deployment has another transport type: a current non-warm-up, nonempty payload that does not need an app restart sets `isNeedRestartActivity=true`, mapping to `APPLY_CHANGES_AND_RESTART_ACTIVITY`. Android Studio transport performs Full Swap; Direct app sandbox transport performs a separate Activity relaunch after successful class redefinition. Both preserve the process and rerun `onCreate()`. Only `isNeedRestartActivity=false` retains `APPLY_CHANGES` semantics without Activity recreation. Do not infer the Activity lifecycle from the final reported `HOT_RELOAD` name.

### 4.3 Multiple Devices and Fallback

```text
selected and running devices snapshot
  -> any selected device not running: treat whole run as no devices; do not start an AVD or deploy to a subset
  -> deploy per device in selection order
  -> deploy type takes highest priority: INSTALL > EMBEDDED > COMPAT_HOT_FIX > HOT_FIX > HOT_RELOAD
  -> any device fails: check whether every failure has isCanFallback
  -> isCanFallback && automatic fallback enabled: force Gradle compilation and rerun doRun()
  -> otherwise retain failure reasons and end this run
```

For multiple devices, individual failure causes combine into one `failedReason`. Fallback applies to the whole Run, rather than retrying only failed devices.

---

## 5. Hidden Constraints

- `CompileTaskResult.isGradleCompile` affects deployment path, whether to call `initIncrementalCompileTask()` after success, and `isLastFullCompileFailed` state.
- After successful Gradle compilation but failed deployment, incremental context may still need rebuilding; otherwise the next run loses incremental capability.
- `isSkipDeploy` is not deployment success. It sets `isDeploySuccess=false` and requires the next user-triggered run to avoid treating hasRun as proof of no changes.
- Device selection is side-effect-free. On Meerkat–Panda and Quail, Run, status refresh, or MCP queries do not automatically start a stopped AVD.
- The main Run path reads the device snapshot once to avoid selection changing between `hasDevice` and actual deployment.
- For multiple devices, some global state advances only after the last device succeeds; see `03_deploy_core.md` for core deployment behavior.
- The Run layer receives `DeployTaskResult.isCanFallback`; `DeployRetryHandler` / deployment core decide which failures qualify.
- The custom APK install script belongs to Run Configuration. It reaches `LaunchContext` through the deployment request and covers ordinary-app install/reinstall in the current Run. Remote compilation changes only the artifact source; the script still runs on the local IDE host connected to the device.
- The custom APK-signing script replaces only the resigning step for incrementally modified APKs. A full Gradle build, CLI, and manual export do not use this parameter. Script failure does not fall back to local keystore signing.
- `juggServer.report(action="compile"/"deploy")` is telemetry; successful reporting does not establish successful compilation or deployment.

---

## 6. Investigation Entry Points

| Symptom | Start with |
|---|---|
| Run hangs at the compile/deploy boundary | The `isSkipDeploy`, device-snapshot, and `deployDevice()` branches after compile success in `JuggRunningTask.doRun()` |
| Compilation succeeds but nothing deploys | `CompileUiHandler.isSkipDeploy`, `deployTargetManager.getSelectedDevices()`, `CompileTaskResult.isGradleCompile` |
| Only some devices succeed | Fallback aggregation of `deployTaskResultList` in `JuggRunningTask.doRun()` |
| The whole run switches to Gradle compilation after failure | `DeployTaskResult.isCanFallback` and `JuggSettings.isAutoFallbackToGradleWhenDeployError` |
| Incremental state is wrong after Gradle install | `initIncrementalCompileTask()` call site and `deployHistoryManager.isLastFullCompileFailed` |
| UI success notification is unexpected | `notifyLaunched()` and `buildDeploySuccessLogLines()` |

---

## 7. Related Documents

- Deployment core: `03_deploy_core.md`
- Impact analysis: `03_deploy_data_generator.md`
- Constant-reference impact analysis: `03_deploy_const_ref.md`
- IDE orchestration: `04_engineering_ide.md`
- Runtime investigation: `09_plugin_runtime_debug.md`
