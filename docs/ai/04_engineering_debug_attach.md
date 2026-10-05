# Engineering: Jugg Debug Attach

> Last checked: 2026-06-11
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Scope

This page explains how Jugg's Debug executor reuses the main compile/deploy path and attaches Android Studio's Java debugger after successful deployment.

It covers only Debug attach lifecycle, the Android Studio debugger-API boundary, and investigation of unusable breakpoints. For ordinary Run, deployment state machine, compatibility deployment, and test layers, see `04_engineering_ide.md`, `03_deploy_complete.md`, `04_engineering_compat.md`, and `06_testing.md`, respectively.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `JuggDebugProgramRunner` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggDebugProgramRunner.kt` | Takes over Debug executor for Jugg RunConfiguration. Jugg compile/deploy output stays in the Run tool window; the later AS attach flow takes over Java debugging. |
| `shouldForceRestartAppForDebugExecutor` | `JuggDebugProgramRunner.kt` | Decides whether ordinary Jugg Debug forces an `am start -D -S` app restart; androidTest does not use this Debug executor path. |
| `JuggManager.runTask` | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | Creates `JuggDebugSessionManager` under Debug executor and writes `isAlwaysRestartApp` / `isDebugRun` into `JuggCompileUiHandler`. |
| `JuggCompileUiHandler` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt` | Carries the Debug-run marker and triggers an onEnd attach callback after deployment. |
| `JuggRunningTask` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | Reuses normal compilation/deployment. When the task concludes, detaches Jugg Run content so the Java debugger session owns subsequent debugging. |
| `JuggDeployerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | Calls `deployTargetManager.restartAppForDebug(device)` after successful Debug-run deployment. |
| `AdbCmdHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt` | Builds `am start -D -S -n <package>/<activity>` so the app waits for debugger during startup. |
| `JuggDebugSessionManager` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` | Requires one device, resolves package, and invokes compatibility-layer attach; failures return to Run output and notifications. |
| `IAsDeployerCompat.attachJavaDebugger` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt` | Version-compatibility boundary for Android Studio debugger APIs; older versions report unsupported by default. |
| `AndroidDebugClientReadyWaiter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/AndroidDebugClientReadyWaiter.kt` | Reflectively invokes AS `waitForClientReadyForDebug` and waits for target client to enter `ClientData.DebuggerStatus.WAITING`. |
| `AndroidStudioDebuggerAttachStarter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/AndroidStudioDebuggerAttachStarter.kt` | Reflectively invokes native AS `AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)`. |
| `JavaDebuggerSessionStarter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JavaDebuggerSessionStarter.kt` | Historical lower-level entry point creating only `DebuggerSession`; current Debug attach no longer uses it. |

---

## 3. State Model

| State | Owner | Meaning |
|---|---|---|
| `isAlwaysRestartApp` | `CompileUiHandler` | Debug run forces start/restart after deployment so the target process waits for debugger. |
| `isDebugRun` | `CompileUiHandler` / `JuggRunningTask` | Selects debug restart after deployment; no longer prevents Jugg Run content from detaching, which would leave two active sessions after Debug success. |
| `DebuggerStatus.WAITING` | AS ddmlib `ClientData` | App started with `am start -D` and waits for debugger. Client attach is possible; IDE VM connection is not yet established. |
| `DebuggerSession` | IntelliJ Java debugger core | Low-level Java debugger session. Creation alone proves neither VM connection nor working breakpoints. |
| `XDebugSession` | IntelliJ XDebugger | IDE owner of Debug tool window, breakpoint state, and Debug content lifecycle. Jugg must have AS native attach flow create/activate it. |

---

## 4. Core Call Chain

```text
Debug executor + JuggRunConfiguration
  -> JuggDebugProgramRunner.doExecute()
     save all documents and refresh open files/VFS for the same IDE file-state synchronization as ordinary Run
     create Jugg compile/deploy Run content, but do not pass this descriptor to Debug executor
  -> JuggManager.runTask()
     set isAlwaysRestartApp=true and isDebugRun=true; register debug attach callback after deployment
  -> JuggRunningTask.run()
     reuse ordinary compile/deploy path; detach Jugg Run content when task concludes
  -> JuggDeployerHelper.deploy()
     after successful deployment, trigger debug launch via restartAppForDebug
  -> AdbCmdHelper.startDefaultApp(..., isDebug=true)
     run am start -D -S so app waits for debugger after startup
  -> JuggDebugSessionManager.attachAfterSuccessfulRun()
     require one device, resolve packageName, call AsDeployerCompat.attachJavaDebugger
  -> AndroidDebugClientReadyWaiter.waitForWaitingDebuggerClient()
     wait for AS to discover target client in WAITING state
  -> AndroidStudioDebuggerAttachStarter.attachExistingProcess()
     request native AS attach flow to create/activate XDebugSession
```

Jugg owns only compilation, deployment, and startup to an attachable state. Android Studio's native attach flow must own Debug tool-window lifecycle, breakpoint state, and activation of an existing session.

---

## 5. Android Studio Debugger API Boundary

Jugg currently uses the high-level attach entry point:

```text
AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)
  -> terminateRunSessions(project, client)
  -> AndroidJavaDebugger.getExistingDebugSession(project, client)
     existing XDebugSession: activateDebugSessionWindow(project, session)
     no XDebugSession: DebugSessionStarter.attachDebuggerToClientAndShowTab(...)
  -> XDebuggerManager.startSessionAndShowTab(...)
  -> AndroidSessionInfo.create(...)
```

The fourth `runConfiguration` argument may be `null`; AS falls back to `AndroidJavaDebugger.createState()` for default Java-debugger state. Jugg does not make `JuggRunConfiguration` implement AS `RunConfigurationWithDebugger` directly, avoiding propagation of internal debugger APIs into the IDE main path.

The historical lower-level entry is useful only to explain the old failure and must not serve as a new Debug attach path:

```text
StartJavaDebuggerSessionKt.startAndroidJavaDebuggerSession(project, client, console, detachIsDefault)
  -> DebuggerManagerEx.attachVirtualMachine(...)
  -> AsyncPromise.setResult(DebuggerSession)
```

It returns `DebuggerSession`; its promise completes when the session object is created, not when VM connects. It neither creates `XDebugSession` nor activates the Debug tool window.

---

## 6. Hidden Constraints

- `WAITING` is a precondition for attach, not proof of success. A `waitForClientReadyForDebug ... is now debuggable` message alone does not establish usable breakpoints.
- Do not use “DebuggerSession created” as the final success criterion. Jugg currently records only that it requested AS debug attach flow. Actual usability requires `Connected to the target VM`, a Debug tool window, and breakpoint suspension.
- Do not add sleep/retry around lower-level `JavaDebuggerSessionStarter`. The missing piece is XDebugger lifecycle ownership, not client-readiness waiting.
- Placing Jugg compile/deploy output in Run tool window is intentional and does not itself cause unusable breakpoints.
- A Debug run with no file changes should perform an empty incremental deployment without showing `Confirm Fallback to Gradle`: its objective is restart and attach, not a Gradle-fallback prompt.
- Because the custom Debug runner takes over Debug executor, it must save documents and refresh open files/VFS before Jugg's main path. Otherwise ordinary Run may detect an edit while Debug incorrectly reports `No file changes`.
- Java debugger attach flow creates the real Debug session. Detach Jugg compile/deploy Run content when the task concludes so a successful Debug does not leave two active sessions.
- Debug executor covers ordinary Jugg RunConfiguration only; androidTest Debug executor does not follow this attach path.
- Multiple-device Debug attach is currently unsupported. `JuggDebugSessionManager` must fail first and report in Run output rather than attach to the wrong device.
- A Debug run must force `am start -D -S`. Even with empty deploy data and a foreground app, ordinary foreground checks must not skip restart or the app will not wait for debugger before attach.

---

## 7. Investigation Entry Points

| Symptom | Start with |
|---|---|
| Jugg log lacks `waiting for <package> to enter debugger WAITING state` | Check whether `JuggManager.runTask()` created `JuggDebugSessionManager` and whether `RunResult` succeeded in compile/deploy. |
| Jugg log waits for WAITING but AS log lacks `is now debuggable` | `AndroidDebugClientReadyWaiter.waitForWaitingDebuggerClient()`, device app process, and packageName. |
| AS logs `is now debuggable` but no Debug tool window | Did `AndroidStudioDebuggerAttachStarter` reach `AndroidConnectDebugger.closeOldSessionAndRun`? |
| `Connecting to the target VM` / `Debugger is waiting for application to start` without `Connected to the target VM` | Compare native AS Attach and confirm the old lower-level `JavaDebuggerSessionStarter` was not used. |
| Run window has output but breakpoints do not work | Check for `XDebugSession` and `Connected to the target VM` before suspecting Run window. |
| Debug fails immediately with multiple devices | Single-device validation in `JuggDebugSessionManager.attachAfterSuccessfulRun()`. |
| Older AS reports Debug attach unsupported | Default `IAsDeployerCompat.attachJavaDebugger()` and the current AS-version adapter. |

---

## 8. Verification Entry Points

Automation should first cover wiring and error propagation:

```text
./gradlew :idea:test --tests '*Debugger*Test' --tests 'com.sickworm.intellij.jugg.ide.logic.JuggDebugSessionManagerTest'
./gradlew :idea:compileKotlin
```

User-visible Debug attach still requires manual Flow verification with `android_demo_project`: `idea.log` shows `Connected to the target VM`, the Debug tool window appears, and a breakpoint can suspend execution.

---

## 9. Related Documents

- IDE lifecycle: `04_engineering_ide.md`
- Compatibility-layer boundary: `04_engineering_compat.md`
- Deployment flow: `03_deploy_complete.md`
- Test strategy: `06_testing.md`
- Runtime investigation: `09_plugin_runtime_debug.md`
