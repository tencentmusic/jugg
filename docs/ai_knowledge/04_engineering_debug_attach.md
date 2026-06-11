# 工程化：Jugg Debug Attach

> 最后核对：2026-06-11
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页说明 Jugg 在 Debug executor 下如何复用编译/部署主链路，并在部署成功后接入 Android Studio Java debugger。

本页只展开 Debug attach 生命周期、Android Studio 内部 debugger API 边界、断点不可用排查入口；不展开普通 Run、部署状态机、兼容部署细节和测试分层，分别见 `04_engineering_ide.md`、`03_deploy_complete.md`、`04_engineering_compat.md`、`06_testing.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggDebugProgramRunner` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggDebugProgramRunner.kt` | 接管 Jugg RunConfiguration 的 Debug executor；Jugg 编译/部署输出仍挂 Run tool window，Java debugger 由后续 AS attach flow 接管 |
| `shouldForceRestartAppForDebugExecutor` | `JuggDebugProgramRunner.kt` | 判断普通 Jugg Debug 是否需要强制 `am start -D -S` 重启 App；androidTest 不接管 Debug executor |
| `JuggManager.runTask` | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | Debug executor 下创建 `JuggDebugSessionManager`，并把 `isAlwaysRestartApp` / `isDebugRun` 写入 `JuggCompileUiHandler` |
| `JuggCompileUiHandler` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt` | 承载 Debug run 标记；部署完成后触发 onEnd attach 回调 |
| `JuggRunningTask` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | 复用普通编译/部署主链路；任务收口时 detach Jugg Run content，由 Java debugger session 承载后续调试 |
| `JuggDeployerHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | Debug run 部署成功后调用 `deployTargetManager.restartAppForDebug(device)` |
| `AdbCmdHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt` | 构造 `am start -D -S -n <package>/<activity>`，让 App 在启动阶段等待 debugger |
| `JuggDebugSessionManager` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` | 校验单设备、解析 package、调用兼容层 attach；失败时回 Run 输出和通知 |
| `IAsDeployerCompat.attachJavaDebugger` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt` | Android Studio debugger API 的版本兼容边界；旧版本默认声明不支持 |
| `AndroidDebugClientReadyWaiter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/AndroidDebugClientReadyWaiter.kt` | 反射调用 AS `waitForClientReadyForDebug`，等待目标 client 进入 `ClientData.DebuggerStatus.WAITING` |
| `AndroidStudioDebuggerAttachStarter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/AndroidStudioDebuggerAttachStarter.kt` | 反射调用 AS 原生 `AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)` |
| `JavaDebuggerSessionStarter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JavaDebuggerSessionStarter.kt` | 历史低层入口；只创建低层 `DebuggerSession`，当前 Debug attach 不再使用 |

---

## 3. 状态模型

| 状态 | 所属对象 | 含义 |
|---|---|---|
| `isAlwaysRestartApp` | `CompileUiHandler` | Debug run 强制部署后启动/重启 App，确保目标进程进入等待 debugger 状态 |
| `isDebugRun` | `CompileUiHandler` / `JuggRunningTask` | 控制部署后走 debug restart；不再阻止 Jugg Run content detach，避免 Debug 成功后保留两个活跃 session |
| `DebuggerStatus.WAITING` | AS ddmlib `ClientData` | App 已由 `am start -D` 启动并等待 debugger；只说明 client 可 attach，不说明 IDE VM 已连接 |
| `DebuggerSession` | IntelliJ Java debugger core | 低层 Java debugger session；创建成功不等于 VM connected，也不等于断点可用 |
| `XDebugSession` | IntelliJ XDebugger | Debug tool window、断点状态、Debug content 生命周期的 IDE 侧接管对象；Jugg 必须让 AS 原生 attach flow 创建/激活它 |

---

## 4. 核心调用链路

```text
Debug executor + JuggRunConfiguration
  -> JuggDebugProgramRunner.doExecute()
     先保存所有文档并刷新打开文件/VFS，补齐普通 Run 前的 IDE 文件状态同步
     创建 Jugg 编译/部署 Run content，但不把该 descriptor 交给 Debug executor
  -> JuggManager.runTask()
     标记 isAlwaysRestartApp=true、isDebugRun=true，注册部署完成后的 debug attach 回调
  -> JuggRunningTask.run()
     复用普通编译和部署主链路；任务收口时 detach Jugg Run content
  -> JuggDeployerHelper.deploy()
     部署成功后通过 restartAppForDebug 触发 debug 启动
  -> AdbCmdHelper.startDefaultApp(..., isDebug=true)
     执行 am start -D -S，让 App 启动后等待 debugger
  -> JuggDebugSessionManager.attachAfterSuccessfulRun()
     只允许单设备，解析 packageName，调用 AsDeployerCompat.attachJavaDebugger
  -> AndroidDebugClientReadyWaiter.waitForWaitingDebuggerClient()
     等待 AS 发现目标 client 且状态为 WAITING
  -> AndroidStudioDebuggerAttachStarter.attachExistingProcess()
     请求 AS 原生 attach flow 创建/激活 XDebugSession
```

关键点是 Jugg 只负责“编译/部署/启动到可 attach 状态”，不直接接管 Debug tool window 生命周期。Debug content、断点状态和已有 session 激活必须交给 Android Studio 原生 attach flow。

---

## 5. Android Studio Debugger API 边界

Jugg 当前采用高层 attach 入口：

```text
AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)
  -> terminateRunSessions(project, client)
  -> AndroidJavaDebugger.getExistingDebugSession(project, client)
     已有 XDebugSession：activateDebugSessionWindow(project, session)
     无 XDebugSession：DebugSessionStarter.attachDebuggerToClientAndShowTab(...)
  -> XDebuggerManager.startSessionAndShowTab(...)
  -> AndroidSessionInfo.create(...)
```

第四个 `runConfiguration` 允许传 `null`；AS 会回退到 `AndroidJavaDebugger.createState()` 创建默认 Java debugger state。Jugg 不让 `JuggRunConfiguration` 直接实现 AS `RunConfigurationWithDebugger`，避免把 AS debugger 内部 API 扩散到 IDE 主路径。

历史低层入口只用于解释旧问题，不应作为新 Debug attach 路径：

```text
StartJavaDebuggerSessionKt.startAndroidJavaDebuggerSession(project, client, console, detachIsDefault)
  -> DebuggerManagerEx.attachVirtualMachine(...)
  -> AsyncPromise.setResult(DebuggerSession)
```

该入口返回的是 `DebuggerSession`，promise 完成点是 session object created，不是 VM connected；它不创建 `XDebugSession`，也不负责激活 Debug tool window。

---

## 6. 隐形约束

- `WAITING` 只是 attach 前置条件，不是 attach 成功条件；不要因为 `waitForClientReadyForDebug ... is now debuggable` 就认定断点可用。
- 成功日志不能使用 “DebuggerSession created” 作为最终判据；当前 Jugg 只记录已请求 AS debug attach flow，真正可用性要看 `Connected to the target VM`、Debug tool window 和断点 suspend。
- 不要在低层 `JavaDebuggerSessionStarter` 外围继续加 sleep / retry；缺失的是 XDebugger 生命周期接管，不是 client ready 等待。
- Jugg 编译/部署输出放在 Run tool window 是设计选择；Run window 本身不是断点不可用的根因。
- Debug run 遇到无文件变化时应直接走空增量部署，不弹 `Confirm Fallback to Gradle`；Debug 的目标是重启并 attach，而不是提示用户回退 Gradle。
- Debug runner 自定义接管 Debug executor，执行 Jugg 主链路前必须显式保存文档并刷新打开文件/VFS，避免普通 Run 可检测到变更而 Debug 误判 `No file changes`。
- Java debugger attach flow 会创建真正的 Debug session；Jugg 编译/部署 Run content 在任务收口时应 detach，避免 Debug 成功后留下两个活跃 session。
- Debug executor 只接管普通 Jugg RunConfiguration；androidTest 的 Debug executor 不走这条 attach 链。
- 多设备 Debug attach 当前不支持；`JuggDebugSessionManager` 必须先失败并回 Run 输出，避免误 attach 到错误设备。
- Debug run 必须强制 `am start -D -S`；即使本次 deploy data 为空、App 已在前台，也不能复用普通前台判断跳过重启，否则 attach 前目标 App 不会进入等待 debugger 状态。

---

## 7. 排查入口

| 现象 | 优先入口 |
|---|---|
| Jugg 日志没有 `waiting for <package> to enter debugger WAITING state` | `JuggManager.runTask()` 是否创建 `JuggDebugSessionManager`，以及 `RunResult` 是否 compile/deploy 成功 |
| Jugg 日志有 WAITING 等待，但 AS 日志没有 `is now debuggable` | `AndroidDebugClientReadyWaiter.waitForWaitingDebuggerClient()`、设备上的 app process 与 packageName |
| AS 日志有 `is now debuggable`，但没有 Debug tool window | `AndroidStudioDebuggerAttachStarter` 是否走到 `AndroidConnectDebugger.closeOldSessionAndRun` |
| 只有 `Connecting to the target VM` / `Debugger is waiting for application to start`，没有 `Connected to the target VM` | 对比 AS 原生 Attach 行为；确认没有走历史低层 `JavaDebuggerSessionStarter` |
| Run window 有输出但断点不可用 | 不要先怀疑 Run window；优先检查是否存在 `XDebugSession` 和 `Connected to the target VM` |
| 多设备 Debug 直接失败 | `JuggDebugSessionManager.attachAfterSuccessfulRun()` 的单设备校验 |
| 旧 AS 版本提示不支持 Debug attach | `IAsDeployerCompat.attachJavaDebugger()` 默认实现与当前 AS 版本 compat 实现 |

---

## 8. 验证入口

自动化优先覆盖接线与错误传播：

```text
./gradlew :idea:test --tests '*Debugger*Test' --tests 'com.sickworm.intellij.jugg.ide.logic.JuggDebugSessionManagerTest'
./gradlew :idea:compileKotlin
```

用户可见 Debug attach 是否真正可用，仍需要用 `android_demo_project` 做手动 Flow 验证：`idea.log` 出现 `Connected to the target VM`，Debug tool window 出现，断点可 suspend。

---

## 9. 关联文档

- IDE 生命周期：`04_engineering_ide.md`
- 兼容层边界：`04_engineering_compat.md`
- 部署流程：`03_deploy_complete.md`
- 测试策略：`06_testing.md`
- 运行时排查：`09_plugin_runtime_debug.md`
