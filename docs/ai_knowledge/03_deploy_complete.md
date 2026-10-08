# 部署系统：端到端流程（Run 到设备）

> 最后核对：2026-09-15
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只回答“用户点击 Run 后，编译结果如何进入部署链路，并如何汇总成用户可见结果”。

不展开部署 transport、Direct Overlay、影响分析细节；这些内容分别看 `03_deploy_core.md`、`03_deploy_data_generator.md`、`03_deploy_const_ref.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggRunningTask` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | Run 总编排。准备 UI/日志，调用编译，按设备调用部署，汇总结果并决定是否 Gradle fallback。 |
| `JuggCompileHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 产出 `CompileTaskResult`，决定本轮是增量编译还是 Gradle 编译。 |
| `JuggDeployerHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | 单设备部署入口。根据 `isInstall` 进入 install、embedded 或 incremental deploy。 |
| `LaunchContext.customApkInstallScript` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/LaunchContext.kt` | 当前 Run Configuration 的可选安装脚本；经 `DeployOptions`、deploy/recover 请求与 `LaunchContextFactory` 注入，UI handler 只负责交互。 |
| `DeployOptions.customApkSignScript` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployHelperBean.kt` | 当前 Run Configuration 的可选 APK 签名脚本；只传到 `IncrementalDeployHelper.updateApk()` 的 APK 更新边界，不进入 `LaunchContext`、`DeployStateRecover` 或 installer。 |
| `DeployOptions` / `DeployTaskResult` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployHelperBean.kt` | Run 编排与 deploy helper 之间的请求/结果契约。 |
| `JuggDeployData` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | 部署 payload 与最终 deploy type 来源。 |
| `DeployStateManager` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` | 单设备当前是否可增量部署、是否需要 recover 的状态来源。 |
| `DeployHistoryManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployHistoryManager.kt` | 记录上次部署 checkpoint，install / incremental 成功后推进。 |
| `JuggServer` | `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt` | 上报 compile/deploy 埋点，不参与部署决策。 |

---

## 3. 端到端状态模型

| 状态/数据 | 生产者 | 消费者 | 关键含义 |
|---|---|---|---|
| `CompileTaskResult.isGradleCompile` | `JuggCompileHelper.compile()` | `JuggRunningTask.deployDevice()` | Gradle 编译成功后 deploy 走 install；增量编译成功后 deploy 走 incremental changes。 |
| `CompileTaskResult.isSuccess` | `JuggCompileHelper.compile()` | `JuggRunningTask.doRun()` | 失败时直接结束，不进入设备部署。 |
| `CompileUiHandler.isSkipDeploy` | UI / MCP 调用方 | `JuggRunningTask.doRun()` | 编译成功但显式跳过部署；会 reset hasRun，避免下次误报 no file changes。 |
| `DeployTaskResult` | `JuggDeployerHelper.deploy()` | `JuggRunningTask.doRun()` | 每台设备的成功状态、deploy type、fallback 资格和失败原因。 |
| `RunResult` | `JuggRunningTask.doRun()` | `JuggRunningTask.run()` | 最终反馈给 UI、依赖变更管理器和 hasRun 状态。 |
| 失败日志自动上传 | `ProjectCustomConfig`、`JuggRunningTask.run()` | `JuggServer` | 后台显式开启后，只在整轮最终失败时按排除正则决定是否上传完整诊断信息；日志限制为最近两份，不采集 adb logcat。 |

---

## 4. 核心调用链路

### 4.1 Run 主链路

```text
JuggRunningTask.run()
  -> 准备 Run Tool Window / JuggLogger / juggServer.onCompile()
  -> JuggCompileHelper.compile()
  -> 编译失败: show Run window + 返回失败 RunResult
  -> skip deploy: 返回编译成功但部署未执行的 RunResult
  -> 单次读取 IDE 选中且已运行的设备快照
  -> 无设备: Gradle 编译时重建增量上下文，返回部署失败
  -> 按快照顺序逐台设备 deployDevice()
  -> 汇总 DeployTaskResult 列表
  -> 全部成功: 打印最终成功日志，Gradle 编译后 initIncrementalCompileTask()
  -> 部分失败且允许 fallback: 设置 force Gradle 后递归 doRun()
  -> 部分失败且不可 fallback: 返回失败 RunResult
```

Run 层只决定“是否进入部署、是否整体 fallback、如何汇总 UI 结果”；install / recover / retry 的细节不在这里展开。Debug executor 入口会额外把 `CompileUiHandler.isAlwaysRestartApp` 置为 true，使普通增量部署、空变更部署都在成功后重启 App，再由 IDE 层 attach Java debugger。

### 4.2 单设备部署链路

```text
deployDevice()
  -> 根据 CompileTaskResult.isGradleCompile 设置 DeployOptions.isInstall
  -> JuggDeployerHelper.deploy()
  -> install/reinstall 时按 applicationId 分组；普通 App 可执行自定义 APK 安装脚本，androidTest 使用默认 installer
  -> 写入 deploy_failed_reason / deploy_type / device 信息到上报 detail
  -> 成功时按 deploy type 弹出用户可见提示
```

Gradle 编译对应 `isInstall=true`；增量编译对应 `isInstall=false`。这个分界决定后续进入 `deployInstall()` 还是 `deployIncrementalChanges()`。

增量部署还有一层 transport 类型：当前非 warm-up、非空且不需要重启 App 的 payload 会设置 `isNeedRestartActivity=true`，映射为 `APPLY_CHANGES_AND_RESTART_ACTIVITY`。Android Studio transport 执行 Full Swap；Direct app sandbox transport 在类重定义成功后执行独立 Activity relaunch。两者都会保留进程并让 `onCreate()` 再次执行。只有 `isNeedRestartActivity=false` 时才保持不重建 Activity 的 `APPLY_CHANGES` 语义。这里不要用最终上报的 `HOT_RELOAD` 名称推断 Activity 生命周期。

部署成功后的启动目标按 launch Activity、HOME Activity 的顺序降级；两者都不存在时只执行 `am force-stop <package>` 并打印 warn，不会退化为启动任意普通 Activity，规则细节见 `03_deploy_core.md` §4.4。stop fallback 下 `DeployTaskResult` 仍为成功，但 App 不会进入前台，也不会 ready：显式 `waitAppReadyAfterSuccess`、Recover 的在线检测和 Debug attach 会按各自现有路径失败，用户可见日志中不会出现落地的 App 页面。

### 4.3 多设备汇总与 fallback

```text
selected and running devices snapshot
  -> 任一选中设备未运行: 整轮视为无设备，不启动 AVD，不执行部分设备
  -> 按选择顺序逐台 deploy
  -> deploy type 取最高优先级: INSTALL > EMBEDDED > COMPAT_HOT_FIX > HOT_FIX > HOT_RELOAD
  -> 任一设备失败: 检查所有失败是否 isCanFallback
  -> isCanFallback && 自动 fallback 开启: force Gradle compile 后重跑 doRun()
  -> 否则保留失败原因并结束本轮
```

多设备时某台设备的失败原因会合并成一条 `failedReason`；fallback 是整轮 Run 级别，不是只重跑失败设备。

### 4.4 最终失败日志自动上传

后台配置 `autoUploadFailureLogs=true` 后，`JuggRunningTask` 在整轮最终结果确定时判断是否自动上传失败日志。编译失败使用 `failedReason` 和 Gradle `errorLog`，部署失败使用所有失败设备的最终原因，顶层异常使用异常类型与 message；`autoUploadFailureLogsExcludeRegex` 对该摘要执行包含匹配，命中时不上传。空正则不过滤，非法正则按 fail-closed 跳过本次上传。

上传只发生在最终失败边界：内部 deploy retry、多设备子任务和触发 Gradle fallback 的中间失败不单独上传；fallback 最终成功时不上传。用户取消、显式跳过部署、未找到设备且没有实际进入部署时同样不上传。一次 Run 最多触发一次，上传异步执行且结果不改变原有 `RunResult`。

---

## 5. 隐形约束

- `CompileTaskResult.isGradleCompile` 同时影响 deploy 路径、成功后是否 `initIncrementalCompileTask()`、以及 `isLastFullCompileFailed` 状态。
- Gradle 编译成功但部署失败时，仍可能需要重建增量上下文；否则下一轮增量能力会丢失。
- `isSkipDeploy` 不是部署成功；它会让本轮 `isDeploySuccess=false`，并要求下次用户触发时不要因为 hasRun 状态误判无变更。
- 设备选择是无副作用查询；Meerkat～Panda 与 Quail 不会因 Run、状态刷新或 MCP 查询自动启动未运行的 AVD。
- Run 主链路只读取一次设备快照，避免 `hasDevice` 与实际部署之间选择状态变化。
- 多设备只在最后一台成功部署后推进部分全局状态；部署核心细节见 `03_deploy_core.md`。
- Run 层拿到的是 `DeployTaskResult.isCanFallback`，具体哪些失败可 fallback 由 `DeployRetryHandler` / deploy core 决定。
- 自定义 APK 安装脚本属于 Run Configuration，经部署请求传入 `LaunchContext`，覆盖当前 Run 的普通 App install/reinstall；远程编译只改变产物来源，脚本仍在连接设备的本地 IDE 主机执行。
- 自定义 APK 签名脚本同样属于 Run Configuration，但只替换 Jugg 对增量改写 APK 的重新签名步骤：Gradle 完整构建产物的签名流程不变，CLI `BuildIncrementalApkCommand` 和手工导出增量 APK 也不接受该参数。启用脚本时 `Run Configuration` 校验要求脚本非空，且本地 `SigningConfig` 无效不再是 APK 更新的失败条件；脚本失败后不回退到本地 keystore 签名。
- `juggServer.report(action="compile"/"deploy")` 是观测侧上报；不要把上报成功当作编译或部署成功。
- 自动失败日志上传同样是观测侧 Best-effort 行为；固定上传到问题报告服务，不读取 Custom Server，失败时不重试、不覆盖编译部署结果。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| Run 卡在编译/部署边界 | `JuggRunningTask.doRun()` 中 compile success 后的 `isSkipDeploy`、设备快照、`deployDevice()` 分支 |
| 编译成功但没有部署 | `CompileUiHandler.isSkipDeploy`、`deployTargetManager.getSelectedDevices()`、`CompileTaskResult.isGradleCompile` |
| 多设备只有部分成功 | `JuggRunningTask.doRun()` 汇总 `deployTaskResultList` 的 fallback 分支 |
| 失败后整轮变成 Gradle 编译 | `DeployTaskResult.isCanFallback` 与 `JuggSettings.isAutoFallbackToGradleWhenDeployError` |
| Gradle install 后下一轮增量状态异常 | `initIncrementalCompileTask()` 调用点与 `deployHistoryManager.isLastFullCompileFailed` |
| UI 成功提示不符合预期 | `notifyLaunched()` 和 `buildDeploySuccessLogLines()` |

---

## 7. 关联文档

- 部署核心：`03_deploy_core.md`
- 影响分析：`03_deploy_data_generator.md`
- 常量引用影响分析：`03_deploy_const_ref.md`
- IDE 编排：`04_engineering_ide.md`
- 运行时排查：`09_plugin_runtime_debug.md`
