# 部署系统：端到端流程（Run 到设备）

> 最后核对：2026-05-23  
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

---

## 4. 核心调用链路

### 4.1 Run 主链路

```text
JuggRunningTask.run()
  -> 准备 Run Tool Window / JuggLogger / juggServer.onCompile()
  -> JuggCompileHelper.compile()
  -> 编译失败: show Run window + 返回失败 RunResult
  -> skip deploy: 返回编译成功但部署未执行的 RunResult
  -> 无设备: Gradle 编译时重建增量上下文，返回部署失败
  -> 逐台设备 deployDevice()
  -> 汇总 DeployTaskResult 列表
  -> 全部成功: 打印最终成功日志，Gradle 编译后 initIncrementalCompileTask()
  -> 部分失败且允许 fallback: 设置 force Gradle 后递归 doRun()
  -> 部分失败且不可 fallback: 返回失败 RunResult
```

Run 层只决定“是否进入部署、是否整体 fallback、如何汇总 UI 结果”；install / recover / retry 的细节不在这里展开。

### 4.2 单设备部署链路

```text
deployDevice()
  -> 根据 CompileTaskResult.isGradleCompile 设置 DeployOptions.isInstall
  -> JuggDeployerHelper.deploy()
  -> 写入 deploy_failed_reason / deploy_type / device 信息到上报 detail
  -> 成功时按 deploy type 弹出用户可见提示
```

Gradle 编译对应 `isInstall=true`；增量编译对应 `isInstall=false`。这个分界决定后续进入 `deployInstall()` 还是 `deployIncrementalChanges()`。

### 4.3 多设备汇总与 fallback

```text
selected devices
  -> 按选择顺序逐台 deploy
  -> deploy type 取最高优先级: INSTALL > EMBEDDED > COMPAT_HOT_FIX > HOT_FIX > HOT_RELOAD
  -> 任一设备失败: 检查所有失败是否 isCanFallback
  -> isCanFallback && 自动 fallback 开启: force Gradle compile 后重跑 doRun()
  -> 否则保留失败原因并结束本轮
```

多设备时某台设备的失败原因会合并成一条 `failedReason`；fallback 是整轮 Run 级别，不是只重跑失败设备。

---

## 5. 隐形约束

- `CompileTaskResult.isGradleCompile` 同时影响 deploy 路径、成功后是否 `initIncrementalCompileTask()`、以及 `isLastFullCompileFailed` 状态。
- Gradle 编译成功但部署失败时，仍可能需要重建增量上下文；否则下一轮增量能力会丢失。
- `isSkipDeploy` 不是部署成功；它会让本轮 `isDeploySuccess=false`，并要求下次用户触发时不要因为 hasRun 状态误判无变更。
- 多设备只在最后一台成功部署后推进部分全局状态；部署核心细节见 `03_deploy_core.md`。
- Run 层拿到的是 `DeployTaskResult.isCanFallback`，具体哪些失败可 fallback 由 `DeployRetryHandler` / deploy core 决定。
- `juggServer.report(action="compile"/"deploy")` 是观测侧上报；不要把上报成功当作编译或部署成功。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| Run 卡在编译/部署边界 | `JuggRunningTask.doRun()` 中 compile success 后的 `isSkipDeploy`、`hasDevice`、`deployDevice()` 分支 |
| 编译成功但没有部署 | `CompileUiHandler.isSkipDeploy`、`deployTargetManager.hasDevice`、`CompileTaskResult.isGradleCompile` |
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
