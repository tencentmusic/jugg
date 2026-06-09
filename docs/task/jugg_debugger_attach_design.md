# Jugg Debugger Attach Design

> 最后核对：2026-06-11
> 一致性规则：文档与代码冲突时，以代码为准。

## 1. 文档定位

本文记录 Jugg Debug attach 的现有设计、两次提交的行为边界、当前会话的排查结论，以及下一步修复方向。

本文不展开 Jugg 编译、部署、兼容部署、androidTest 运行链路；这些内容分别以 `04_engineering_ide.md`、`03_deploy_complete.md`、`09_plugin_runtime_debug.md` 为准。

## 2. 变更来源

| 来源 | 主题 | 关键内容 |
|---|---|---|
| `4ca9993d` | `[feature] [WIP] enable jugg debug runner` | 引入 Jugg Debug runner、`am start -D -S` 启动、部署成功后 attach Java debugger、低层 `JavaDebuggerSessionStarter` |
| `290b8fda` | `[bugfix] wait for android debug client ready` | attach 前复用 Android Studio `waitForClientReadyForDebug`，等待目标 client 进入 `ClientData.DebuggerStatus.WAITING` |
| 当前会话 | 断点不可用排查与修复 | 现场日志显示 session object created，但缺失 `Connected to the target VM`；修复前 Jugg 只走低层 `DebuggerSession`，当前已提升到 AS 高层 attach/XDebugger 生命周期入口 |

## 3. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggDebugProgramRunner` | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggDebugProgramRunner.kt` | 接管 Debug executor 下的 Jugg RunConfiguration；把 Jugg 编译/部署 console 显示到 Run tool window，并让 Java debugger session 独立接管 Debug 生命周期 |
| `shouldForceRestartAppForDebugExecutor` | `JuggDebugProgramRunner.kt` | 判断普通 Jugg Debug 是否需要强制 `am start -D -S` 重启 App；androidTest 不接管 Debug executor |
| `JuggManager.runTask` | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 根据 executor 创建 `JuggDebugSessionManager`，并把 `isAlwaysRestartApp` / `isDebugRun` 传入 `JuggCompileUiHandler` |
| `JuggCompileUiHandler` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt` | 承载 Debug run 标记，任务结束时回调 debugger attach |
| `JuggDeployerHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | Debug run 成功部署后调用 `deployTargetManager.restartAppForDebug(device)` |
| `AdbCmdHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt` | 构造 `am start -D -S -n <package>/<activity>`，让 App 启动阶段等待 debugger |
| `JuggDebugSessionManager` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` | 部署成功后校验单设备、解析 package、通过兼容层 attach Java debugger，并上报失败 |
| `IAsDeployerCompat.attachJavaDebugger` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt` | AS 版本兼容边界；旧版本可声明不支持，Giraffe/Quail 走内部 debugger API |
| `AndroidDebugClientReadyWaiter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/AndroidDebugClientReadyWaiter.kt` | 反射调用 AS `UtilsKt.waitForClientReadyForDebug`，等待目标 client 进入 `WAITING` |
| `AndroidStudioDebuggerAttachStarter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/AndroidStudioDebuggerAttachStarter.kt` | 反射调用 AS 原生 `AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)`，进入 XDebugger 生命周期 |
| `JavaDebuggerSessionStarter` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JavaDebuggerSessionStarter.kt` | 历史低层入口；仅反射调用 AS `StartJavaDebuggerSessionKt.startAndroidJavaDebuggerSession`，返回低层 `DebuggerSession`，当前兼容层 attach 不再使用 |

## 4. 当前调用链

```text
Debug executor + JuggRunConfiguration
  -> JuggDebugProgramRunner.doExecute()
     Jugg 编译/部署 console 显示到 Run tool window，且不返回 descriptor 给 Debug executor
  -> JuggManager.runTask()
     标记 isAlwaysRestartApp=true、isDebugRun=true，并注册 onEnd attach 回调
  -> JuggRunningTask.run()
     复用普通 Jugg 编译和部署主链路；Debug run 收口时不主动 detach Jugg process handler
  -> JuggDeployerHelper.deploy()
     成功部署后调用 restartAppForDebug
  -> AdbCmdHelper.startDefaultApp(..., isDebug=true)
     执行 am start -D -S，让 App 等待 debugger
  -> JuggDebugSessionManager.attachAfterSuccessfulRun()
     只允许单设备，解析 packageName，调用 AsDeployerCompat.attachJavaDebugger
  -> AndroidDebugClientReadyWaiter.waitForWaitingDebuggerClient()
     等待 AS 发现目标 client 且状态为 WAITING
  -> AndroidStudioDebuggerAttachStarter.attachExistingProcess()
     反射请求 Android Studio 原生 attach flow 创建/激活 XDebugSession
```

## 5. 当前状态模型

| 状态 | 所属对象 | 当前含义 |
|---|---|---|
| `isAlwaysRestartApp` | `CompileUiHandler` | Debug run 强制部署后启动或重启 App，避免热更新后 App 不进入等待 debugger 状态 |
| `isDebugRun` | `CompileUiHandler` | 控制部署后走 `restartAppForDebug`，并让 `JuggRunningTask.stop()` 不主动 detach Jugg Run content |
| `DebuggerStatus.WAITING` | AS ddmlib `ClientData` | App 已由 `am start -D` 启动并等待 debugger；只说明 client 可 attach，不说明 IDE VM 已连接 |
| `DebuggerSession` | IntelliJ Java debugger core | `StartJavaDebuggerSessionKt` 返回的低层 session；创建成功不等价于 `Connected to the target VM`；当前不再作为 Jugg attach 成功判据 |
| `XDebugSession` | IntelliJ XDebugger | Debug tool window、断点状态、Debug content 生命周期的 IDE 侧接管对象；当前通过 AS 原生 `AndroidConnectDebugger.closeOldSessionAndRun` 创建/激活 |

## 6. 已知日志证据

当前复现项目：`/Users/wormchen/IdeaProjects/jugg/jugg_f1/android_demo_project`

Jugg 主日志 `build/jugg/log/compile_latest.log`：

```text
[2026-06-11 00:28:21.441] [INFO] [JuggDebugSessionManager] Jugg Debug attach: waiting for com.example.myapplication to enter debugger WAITING state.
[2026-06-11 00:28:22.082] [INFO] [JuggDebugSessionManager] Jugg Debug attach: Android Studio Java debugger session created for com.example.myapplication.
```

Android Studio `idea.log` 同时间窗：

```text
2026-06-11 00:28:21,443 waitForClientReadyForDebug - Waiting for clients [com.example.myapplication] for 15 seconds
2026-06-11 00:28:22,039 waitForClientReadyForDebug - Found process com.example.myapplication. Waiting for it to be debuggable.
2026-06-11 00:28:22,039 waitForClientReadyForDebug - com.example.myapplication is now debuggable.
2026-06-11 00:28:22,084 DebuggerSession - Connecting to the target VM, address: 'localhost:59840', transport: 'socket'
2026-06-11 00:28:22,084 DebuggerSession - Debugger is waiting for application to start; debug address: 'localhost:59840', transport: 'socket'
```

缺失日志：

```text
Connected to the target VM
```

设备侧只读检查显示 `com.example.myapplication` 进程仍存在，pid 为 `1149`，与 `idea.log` 中 ddmlib 记录一致。因此当前优先排除“App 立即退出”这一类根因。

## 7. AS 内部 API 差异

修复前 Jugg 调用的低层入口：

```text
StartJavaDebuggerSessionKt.startAndroidJavaDebuggerSession(project, client, console, detachIsDefault)
  -> DebuggerManagerEx.attachVirtualMachine(...)
  -> AsyncPromise.setResult(DebuggerSession)
```

字节码核查结论：

- 该入口返回的是 `com.intellij.debugger.impl.DebuggerSession`。
- promise 完成点是 `DebuggerSession` 对象创建，不是 VM 连接完成。
- 该入口不创建 `XDebugSession`，不调用 `XDebuggerManager.startSession(...)` / `startSessionAndShowTab(...)`。
- 该入口不负责激活 Debug tool window。

Android Studio 原生 “Attach debugger to Android process” 入口：

```text
AndroidConnectDebugger.closeOldSessionAndRun(project, androidDebugger, client, runConfiguration)
  -> terminateRunSessions(project, client)
  -> AndroidJavaDebugger.getExistingDebugSession(project, client)
     已有 XDebugSession：activateDebugSessionWindow(project, session)
     无 XDebugSession：DebugSessionStarter.attachDebuggerToClientAndShowTab(...)
  -> XDebuggerManager.startSessionAndShowTab(...)
  -> AndroidSessionInfo.create(...)
```

Android Studio Debug run 新进程入口：

```text
DebugSessionStarter.attachDebuggerToStartedProcess(...)
  -> waitForClientReadyForDebug(...)
  -> AndroidDebugger.getDebugProcessStarterForNewProcess(...)
  -> XDebuggerManager.startSession(environment, debugProcessStarter)
  -> processHandler.startNotify()
  -> AndroidSessionInfo.create(...)
```

关键差异不是 “是否调用了 `startAndroidJavaDebuggerSession`”，而是调用后是否把 `DebuggerSession` 包装成 `JavaDebugProcess` 并交给 `XDebuggerManager` 管理。

## 8. 当前判断

本次问题不在 “找不到进程” 或 “没等到 debuggable” 阶段。`290b8fda` 已补上 `WAITING` 等待，现场日志也证明目标 client 已进入可调试状态。

修复前更可能的根因是：

1. `JuggDebugSessionManager` 的成功日志使用了过早判据：`DebuggerSession` created 不等于 VM connected。
2. attach 只创建低层 Java debugger core session，没有进入 AS 原生 `XDebugSession` / Debug tool window 生命周期。
3. Raw `DebuggerSession` 可能留下未由 XDebugger 管理的连接尝试；当前修复避免先创建 raw session 再补高层 session。

`Run window` 本身不是断点失效的直接原因。Jugg 编译/部署输出放在 Run window 是当前设计的一部分；异常点是部署完成后没有出现可用的 Debug session。

## 9. 当前修复方案

### 9.1 已采用：复用 AS 原生 Attach 高层入口

新增兼容层 starter，走：

```text
AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)
```

设计目标：

- 复用 AS 原生 attach action 的 session 关闭、已有 session 激活、新 session 创建逻辑。
- 让 `XDebuggerManager.startSessionAndShowTab(...)` 创建 Debug tool window content。
- 继续保留 `AndroidDebugClientReadyWaiter`，保证进入 `WAITING` 后再 attach。
- 成功日志不能只写 `session created`，改为记录 “requested Android Studio debug attach flow”。

约束：

- `JuggRunConfiguration` 当前未实现 AS `RunConfigurationWithDebugger`。`closeOldSessionAndRun` 第四个参数可先传 `null`，由 `AndroidJavaDebugger.createState()` 创建默认 Java debugger state。
- 该方法返回 `Unit`，内部通过 background task + coroutine 执行；如需同步判定成功，仍需要额外观察 `XDebuggerManager` 或日志，不应再用 raw `DebuggerSession` created 作为成功。

### 9.2 备选方案：直接调用 `DebugSessionStarter.attachDebuggerToClientAndShowTab`

反射调用：

```text
DebugSessionStarter.INSTANCE.attachDebuggerToClientAndShowTab(
  project,
  client,
  AndroidJavaDebugger(),
  AndroidJavaDebugger().createState(),
  continuation
)
```

设计目标：

- 跳过 action 层，但仍创建 `XDebugSession` 和 Debug tab。
- 更容易拿到返回的 `XDebugSession` 作为成功判据。

约束：

- 这是 Kotlin suspend 方法，反射接入复杂度高于 `AndroidConnectDebugger.closeOldSessionAndRun`。
- 需要处理 coroutine continuation / `runBlockingCancellable` 之类的 AS runtime API。

### 9.3 不建议继续加等待

不建议继续在低层 `JavaDebuggerSessionStarter` 外围增加 sleep / retry 来等待 `Connected to the target VM`，原因：

- 当前已等到 `DebuggerStatus.WAITING`。
- 缺失的是 Debug 生命周期接管，不是 client ready。
- 继续基于 raw `DebuggerSession` 补等待，仍可能无法更新断点状态和 Debug tool window。

## 10. TDD 与验证建议

按 `06_testing.md` 分层要求，本次实现覆盖：

| 层级 | 测试入口 | 覆盖点 |
|---|---|---|
| L2 | `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/AndroidStudioDebuggerAttachStarterTest.kt` | 高层 starter 反射目标、默认 `AndroidJavaDebugger` 构造、错误 unwrap |
| L2 | `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManagerTest.kt` | 成功日志从 raw session created 调整为请求 AS Debug attach flow；失败仍回 Run 输出 |
| L3 或手动 Flow | `android_demo_project` Debug run | `idea.log` 出现 `Connected to the target VM`，Debug tool window 出现，断点可用并可 suspend |

定向验证建议：

```text
./gradlew :idea:test --tests '*JuggDebugSessionManagerTest' --tests '*Debugger*Test'
./gradlew :idea:compileKotlin
```

禁止为了验证该问题运行无过滤的 `:idea:test` 全量测试。

## 11. 排查入口

| 现象 | 优先入口 |
|---|---|
| Jugg 日志有历史 `session created`，断点仍不可用 | 对齐 `idea.log` 是否有 `Connected to the target VM`，并确认代码是否仍停留在修复前版本 |
| 有 `waitForClientReadyForDebug ... is now debuggable`，但无 Debug window | 检查是否走到 `AndroidStudioDebuggerAttachStarter` / `AndroidConnectDebugger.closeOldSessionAndRun` |
| Debug window 未出现，只有 Run window | 检查是否创建了 `XDebugSession`；不要只看 Run content |
| 原生 Attach 可以命中，Jugg Debug 不行 | 对比 `AndroidConnectDebugger.closeOldSessionAndRun` 与 Jugg compat attach |
| 多次尝试后 attach 更异常 | 检查是否存在 raw `DebuggerSession` 残留或旧连接未释放 |

## 12. 下一会话起点

下一会话应从本文开始，不要重新从“是否等待 WAITING”排查。当前已知结论是：client ready 阶段已补齐，自动化已覆盖高层 attach flow 接线；剩余验证集中在 `android_demo_project` 手动 Debug run 是否出现 `Connected to the target VM`、Debug tool window 和可用断点。
