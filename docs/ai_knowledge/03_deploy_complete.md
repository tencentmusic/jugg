# 部署系统：端到端流程（Run 到设备）

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页用于回答“用户点击 Run 之后，编译与部署如何串起来”。

---

## 2. 端到端主链路

1. `JuggRunningTask.run` 启动任务并准备 UI/日志。  
2. `JuggCompileHelper.compile` 产出 `CompileTaskResult`。  
3. 若跳过部署或编译失败，流程结束。  
4. `JuggDeployerHelper.deploy` 对每台目标设备执行部署。  
5. 汇总多设备结果，必要时触发 fallback 或重试。

---

## 3. 主链路关键节点

| 节点 | 主要类 | 说明 |
|------|--------|------|
| 任务编排 | `JuggRunningTask` | 统一控制编译/部署顺序与异常处理 |
| 编译决策 | `JuggCompileHelper` | 增量优先，条件不满足时回退 Gradle |
| 部署决策 | `JuggDeployerHelper` | install / swap / embedded / recover |
| 实际下发 | `JuggDeployer`, `JuggDeployTask` | 与设备交互执行部署 |
| 状态同步 | `DeployStateManager`, `DeployHistoryManager` | 状态维护、下次运行参考 |

---

## 4. 多设备与失败处理

- 多设备按选中设备顺序逐台部署。  
- 某设备失败时根据 `isCanFallback` 决定是否整体 fallback。  
- 失败时会带回 `failedReason` 与 deploy type，供 UI/MCP 返回。

---

## 5. 关键恢复机制

- 部署前/中检测状态异常可触发 `recoverDeployState`。  
- Overlay 不一致、agent 兼容问题会触发额外修复流程。  
- Gradle 编译成功后会重建增量上下文，恢复后续增量能力。

---

## 6. 常见问题定位

- “Run 卡在编译/部署边界”：看 `JuggRunningTask.doRun` 分支。  
- “编译成功但未部署”：检查 `isSkipDeploy`、设备列表、`deployTargetManager.hasDevice`。  
- “失败后反复 fallback”：检查 `DeployTaskResult.isCanFallback` 与具体失败原因。

---

## 7. 关联文档

- 部署核心：`03_deploy_core.md`
- 影响分析：`03_deploy_data_generator.md`
- IDE 编排：`04_engineering_ide.md`
