# Deployment System: Run to Device

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page follows one user-triggered Run from compilation to per-device deployment and final UI state. Install, Apply Changes, Direct transport, retry, and recovery internals are in `03_deploy_core.md`.

## 2. Core Source Index

| Owner | Path | Responsibility |
|---|---|---|
| `JuggRunningTask` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | Holds the project write lock, runs compile then device deployment, aggregates results, and publishes UI/events |
| `JuggCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | Returns the compilation result and incremental/Gradle choice |
| `IDeployTargetManager` / IDEA `DeployTargetManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/IDeployTargetManager.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployTargetManager.kt` | Resolve this request's connected targets without booting an AVD or mutating persisted selection |
| `JuggDeployerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | Turns one device's `DeployOptions` into a `DeployTaskResult`; owns deploy/recovery fallback eligibility |
| `JuggDeployOrchestrator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt` | Runs the shared device lifecycle after the helper chooses a payload |
| `DeployOptions` / `DeployTaskResult` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployHelperBean.kt` | Request/result boundary between Run and the device helper |

## 3. Run State and Handoffs

| State | Producer → consumer | Consequence |
|---|---|---|
| `CompileTaskResult.isSuccess` | `JuggCompilerHelper` → `JuggRunningTask` | Failure ends before any device deployment. |
| `CompileTaskResult.isGradleCompile` | Compile → `DeployOptions.isInstall` | Gradle result installs; incremental result applies changes. A successful Gradle compile may also rebuild incremental context after a skipped or failed deploy. |
| `CompileUiHandler.isSkipDeploy` | UI/MCP → Run | Compile can succeed without deployment. `RunResult.isDeploySuccess=false` and `hasRun` is reset so a later Run will not assume no changes. |
| `DeployTaskResult` | Per-device helper → Run | Carries success, actual deploy type, failure reason, and whether that result permits Run-level Gradle fallback. |
| `RunResult` | Run → UI/dependency-change manager/status | Gradle compile success is enough to stage compile state; an incremental run requires deploy success. Cancellation and `hasRun` reset are resolved at task end. |
| `DeployHistoryManager.isLastFullCompileFailed` | Run → next compile/deploy-state check | After a non-canceled Gradle Run, records compilation success or failure independently of installation. A failed full compile blocks incremental compilation; a successful full compile clears this gate even if deployment later fails. |

```text
JuggRunningTask.run() → runLocked() under project write lock
  → refresh configuration/owner state; start logs, progress, and compile event
  → doRun() → JuggCompilerHelper.compile()
  → compile failure: return without device deployment
  → skip deploy: preserve compile result, reset hasRun, finish
  → IDeployTargetManager.getTargetDevices(request serial) once
  → no targets: report deploy failure; rebuild incremental context after Gradle compile
  → for each target: deployDevice() builds DeployOptions(isInstall = isGradleCompile) → JuggDeployerHelper.deploy()
  → aggregate device results and select final deploy type
  → eligible failure with automatic fallback: force Gradle and rerun the Run flow
  → otherwise finish UI, dependency/build state, and hasRun checkpoint
```

With no explicit serial, the IDEA manager uses the IDE's selected, already-running devices. An explicit serial is matched against online connected devices for this request and does not alter Host selection. Device lookup is side-effect-free and does not start a stopped emulator. The Run flow takes one target list before deploying, so later selection changes do not silently change this run's target set.

For each target, `DeployOptions` carries the current Run Configuration's custom APK install and signing scripts. The install script runs only for ordinary-app install/reinstall on the IDEA Host; androidTest APKs use the default installer. The signing script reaches only the incremental APK rewrite boundary. Remote compilation changes artifact origin, not the local install-script execution host. See `03_deploy_system_app.md` for script behavior.

## 4. Multi-Device Result and Fallback

The Run layer reports the highest-priority device result: `INSTALL > EMBEDDED > COMPAT_HOT_FIX > HOT_FIX > HOT_RELOAD`. Failure reasons from multiple devices are joined for the final result. Core deployment decides whether a device failure can fall back; the Run layer reads the `isCanFallback` flags and the automatic fallback setting.

The current aggregation uses `deployTaskResultList.all { it.isCanFallback }`, including successful results. A successful `DeployTaskResult` normally has `isCanFallback=false`, so a mixed success/failure run does not enter automatic Gradle fallback. If every result permits fallback, Run forces Gradle compilation and reruns the entire target list; it does not retry only failed devices. This distinction matters when investigating a partial success that ended without fallback.

The per-device helper advances shared deployment history and file state when the last target succeeds, before Run aggregates all target results. An earlier target's failure can therefore coexist with an advanced global checkpoint. Inspect per-device cache/overlay IDs and the last-target result when investigating a later mismatch after partial success.

On full Gradle compilation, `initIncrementalCompileTask()` is called after successful deployment and also after several nondeployment exits, including skip-deploy, no target, and a failed deployment with no fallback. This preserves the newly built baseline for the next Run. A failed Gradle install also resets `hasRun`; skip-deploy and cancellation reset it for their own reasons. The final `hasRun` status and target serials are written in `runLocked()`'s `finally` block.

`JuggDeployData.deployType=HOT_RELOAD` is a payload category, not proof that the Activity survived. A nonempty payload without process restart selects APPLY_CHANGES_AND_RESTART_ACTIVITY; official transport runs Full Swap and Direct app sandbox requests Activity recreation. If transport or policy actually restarts the process, the per-device result becomes `HOT_FIX`. A Debug run requests a process restart before debugger attach, including empty-change deployment.

`juggServer.report(action="compile"/"deploy")` and Control Panel events describe the run; successful telemetry submission does not establish compilation or deployment success. The reported deploy type is an aggregate, whereas `notifyLaunched()` uses each device result for its notification.

## 5. Investigation Boundaries

| Observation | What it establishes | Next discriminating evidence |
|---|---|---|
| Compilation succeeded but no device deploy began | Run took skip-deploy or no-target branch; this is not proof of an incremental compiler defect. | Check `CompileUiHandler.isSkipDeploy`, request serial, and `getTargetDevices()` result at the compile/deploy boundary. |
| Some devices succeeded, some failed, with no Gradle fallback | At least one result did not permit fallback under the current `all` aggregation. | Compare each `DeployTaskResult.isCanFallback`, including successful results, and the automatic fallback setting. |
| A deployment failure caused a full Gradle rerun | Every result permitted fallback and auto-fallback was enabled. | Compare per-device failure reasons with `DeployRetryHandler` classification; do not infer all failures had the same cause. |
| `HOT_RELOAD` was followed by Activity `onCreate()` | Process-preserving deployment may still recreate the Activity. | Check `isNeedRestartActivity`, selected transport, and whether `needsRestartApp` promoted the actual result to `HOT_FIX`. |
| Incremental state after a Gradle build looks stale | A rebuild or status reset may be missing at a Run exit, independent of the install outcome. | Inspect the relevant `initIncrementalCompileTask()` branch and the final `runLocked()` status update. |

## 6. Related Documents

- Deployment internals: `03_deploy_core.md`
- Effect analysis: `03_deploy_data_generator.md`
- IDE lifecycle: `04_engineering_ide.md`
- Android test flow: `06_android_test.md`
- Verification strategy: `06_testing.md` §7.1
