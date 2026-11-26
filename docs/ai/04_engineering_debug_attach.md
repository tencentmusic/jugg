# Engineering: Jugg Debug Attach

> Last checked: 2026-10-07
> If this page conflicts with implementation, follow the code.

## Scope and owners

An ordinary `JuggRunConfiguration` under the Debug executor still uses Jugg's compile/deploy flow. `JuggDebugProgramRunner` (`idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/`) owns IDE preflight and separate Run output; `JuggManager.runTask()` sets debug restart flags and completion callback; `JuggDebugSessionManager` (`idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/`) asks the Android Studio compatibility layer to attach. androidTest Debug does not enter this path. See `04_engineering_ide.md` for Run lifecycle and `03_deploy_complete.md` for deploy results.

## Cross-system lifecycle

```text
Debug executor + JuggRunConfiguration
  → JuggDebugProgramRunner.doExecute(): save documents and refresh open files/VFS
  → Jugg compile/deploy output in Run Content; successful deploy starts app with am start -D -S
  → JuggDebugSessionManager.attachAfterSuccessfulRun(): require one device and resolve package
  → IAsDeployerCompat.attachJavaDebugger(): delegate through the selected Android Studio version adapter
  → AndroidDebugClientReadyWaiter.waitForWaitingDebuggerClient(): observe WAITING
  → AndroidStudioDebuggerAttachStarter.attachExistingProcess(): call AndroidConnectDebugger.closeOldSessionAndRun()
  → Android Studio creates or activates XDebugSession
```

The `JuggDebugProgramRunner` preflight is necessary because its custom runner bypasses ordinary Run file synchronization. Without it, Debug can report `No file changes` for an unsaved edit. Jugg output appears under the Run executor while native Java debugging owns Debug content. `JuggRunningTask` detaches Jugg's Run process when the task ends so successful Debug does not leave two active sessions.

`JuggManager.runTask()` sets both `isAlwaysRestartApp` and `isDebugRun` only for ordinary Jugg Debug. The shared `JuggDeployOrchestrator` chooses `restartAppForDebug()` after successful deployment; IDEA's `DeployTargetManager` reaches `AdbCmdHelper` to emit `am start -D -S`. The forced restart matters even for empty incremental deployment or a foreground app: reusing a running process would never enter debugger `WAITING`. An empty-change Debug run remains incremental and attaches without asking for Gradle fallback.

`JuggDebugSessionManager.attachAfterSuccessfulRun()` returns on compile/deploy failure or cancellation. It reports device selection, package resolution, and attach errors in Run output and a balloon. Exactly one device is required; several devices fail before attach. Only `App process not found` is retried, up to 20 attempts with a 250 ms delay; other errors are final. Current Giraffe/Quail adapters wait for the target client and call the native attach starter. The compatibility interface and older adapters report unsupported where this path is unavailable.

## Android Studio API and success boundary

`AndroidDebugClientReadyWaiter` observes ddmlib `ClientData.DebuggerStatus.WAITING`. This is a precondition, not evidence of a connected VM. `AndroidStudioDebuggerAttachStarter` reflectively calls `AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)` so Android Studio creates or activates its `XDebugSession`, Debug tool window, and breakpoint state. The nullable run-configuration argument keeps internal debugger types out of Jugg's Run Configuration contract. Inspect `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/` and version adapters for API failures.

The historical `JavaDebuggerSessionStarter` uses lower-level `startAndroidJavaDebuggerSession` and yields a `DebuggerSession`. Completion only says the object was created: it neither proves a VM connection nor creates/activates `XDebugSession`. Do not substitute it for native attach or add sleeps around it. Jugg's `Debugger attached` log means the attach request returned, not that breakpoints work. User-visible success requires Android Studio's `Connected to the target VM`, an active Debug tool window, and a breakpoint that suspends execution.

## Diagnosis and verification

| Observation | Check next |
|---|---|
| No `Waiting for <package> to enter debugger WAITING state` | Compile/deploy result, cancellation, Debug executor marker, and `JuggManager` completion callback. |
| Waiting log but no debuggable client | Package/activity, `am start -D -S`, device process, then `AndroidDebugClientReadyWaiter`. |
| Client debuggable but no Debug window | Version adapter and reflective `AndroidStudioDebuggerAttachStarter` call. |
| `Connecting to the target VM` but never `Connected` | Native Android Studio attach result; confirm lower-level starter was not used. |
| Run output exists but breakpoints do not suspend | Active `XDebugSession` and VM connection before investigating Run Content. |
| Immediate failure with multiple devices | `JuggDebugSessionManager` single-device guard. |

Use `06_testing.md` to select verification evidence. Targeted wiring tests exercise runner preflight and attach error propagation; a real `android_demo_project` Debug Flow is required to verify VM connection, Debug window activation, and breakpoint suspension. For log locations, use `09_plugin_runtime_debug.md`.
