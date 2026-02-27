# 部署系统：核心部署机制

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页聚焦设备部署执行本身（install / code swap / full swap），不展开编译侧细节。

---

## 2. 关键入口

| 类 | 文件 | 作用 |
|----|------|------|
| `JuggDeployerHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | 部署主协调器，负责 recover、agent、重试、状态修复 |
| `JuggDeployer` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployer.kt` | 具体 install / optimistic swap 执行 |
| `JuggDeployTask` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt` | 单次部署任务封装 |
| `AsDeployerCompat` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | 对接 deploy_compat 版本适配入口 |
| `DeployStateManager` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` | 设备部署状态判断 |
| `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | 管理待部署文件与部署数据 |

---

## 3. 部署类型（概念层）

- `INSTALL`：安装 APK。  
- `HOT_RELOAD`：类/资源热更新（无需重启为主）。  
- `HOT_FIX` / `COMPAT_HOT_FIX`：结构变化或兼容路径。  
- `EMBEDDED`：将变更嵌入 APK 后重装。

> 具体策略组合以 `JuggDeployData` + `JuggDeployerHelper.deploy` 逻辑为准。

---

## 4. 核心执行流程

1. 读取 `DeployFileManager.getDeployData(...)`。  
2. 根据状态与策略决定是否 `recoverDeployState` / 是否重签 APK。  
3. 调用 `runTask`，内部执行 `JuggDeployTask` + `JuggDeployer`。  
4. 必要时处理 JVMTI agent push/attach 与 app 重启。  
5. 回写 overlayId、部署历史与状态。

---

## 5. 关键防护逻辑

- overlay id 校验，避免跨项目/跨设备污染。  
- 切片部署（`SliceDeployHelper`）降低单次推送风险。  
- 部署失败时可回退 Gradle 路径。  
- 兼容模式设备记录与清理（JVMTI 兼容性问题）。
- `DeployFileManager#getDeployData` 在“本轮 dex + 历史 dex（排除已 merge 记录）> 500”时触发 dex merge，减少设备加载 dex 数量，缓解 dex 加载 OOM 风险。

---

## 6. 常见问题定位

- “部署立即失败”：先看 `DeployStateManager.updateDeployState()`。  
- “overlay id mismatch”：看 `JuggDeployer.optimisticSwap`。  
- “部署后需频繁重启”：看 `JuggDeployerHelper` 中 `isNeedRestartApp` 决策。  
- “兼容模式反复触发”：看 `JuggJvmtiAgentManagerHelper.isHasJvmtiCompatIssue`。

---

## 7. 关联文档

- 部署全流程：`03_deploy_complete.md`
- 影响分析：`03_deploy_data_generator.md`
- JVMTI：`03_runtime_jvmti.md`
