# 部署系统：核心部署机制

> 最后核对：2026-05-18  
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
| `DirectOverlaySwapTransport` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlaySwapTransport.kt` | Direct Overlay Writer 的 swap transport；在 `JuggDeployer.optimisticSwap` 内替换 Apply Changes overlay update，不接管 `runTask` 生命周期 |
| `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | 将增量 overlay 文件打包为 ZIP 后 push 到设备，并通过 `run-as` 原子写入 `code_cache/.overlay` |
| `DeployStateManager` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` | 设备部署状态判断 |
| `IJuggDeploymentService` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/IJuggDeploymentService.kt` | main 层 deployment cache 读接口，供 direct recover 校验使用 |
| `JuggDeploymentService` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt` | IDE 侧 deployment cache 读写；实现 `IJuggDeploymentService` 供 direct recover 校验 |
| `DirectOverlayStateChecker` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayStateChecker.kt` | recover 时校验 history/cache/device 三路 overlay 一致性；swap 时用 `checkDevice` 只比对设备 overlay；由 `JuggDeployerHelper` 直接组装调用 |
| `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | 部署文件管理 facade，编排状态跟踪与部署数据生成 |
| `DeployFileStateTracker` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTracker.kt` | 维护 uncompiled/compiled/staging/deployed 等文件状态 |
| `DeployDataPlanner` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployDataPlanner.kt` | 计算 `JuggDeployData`，处理 dex merge 与 compat deploy 组装 |
| `CompileEffectAnalyzer` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzer.kt` | 计算 recompile/desugar/minify 的编译影响分析结果 |

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

开启 `JuggSettings.isEnableDirectOverlayDeploy` 后，`JuggDeployer.optimisticSwap` 会在旧 `AsDeployerCompat.optimisticSwap` 前尝试 Direct Overlay Writer transport。该 transport 只替换 overlay update 动作，不绕过 `JuggDeployerHelper.runTask`，因此分片、按 applicationId 分组、androidTest instrumentation、agent 后处理、重启和 JVMTI 检查仍走原流程。Direct transport 只处理“设备当前不可 deploy、Android O 及以上、非 install、overlay-only、需要重启生效、非空部署数据、deployment cache 已是非 base overlay id”的场景；若 startup agent 尚未准备、cache/local/device overlay id 不匹配，或 writer 在修改 overlay 目录前失败，会返回空结果并继续走旧 Apply Changes 流程。若 writer 已开始修改 overlay 目录后失败，则不再继续旧 Apply Changes，避免在半提交状态上做伪回退。transport 成功时基于 `OverlayUpdateBuilder` 生成 overlay 更新，清理 `/data/local/tmp/jugg/direct-overlay-*.zip` 后打 ZIP push 到 `/data/local/tmp/jugg`，再用 `run-as <package> sh -c` 校验旧 `code_cache/.overlay/id`、解压新文件、最后写入新 id，随后更新 deployment cache 并由 `runTask` 继续完成后续流程。

部署数据支持多 APK 归属：

- `DeployItem.apkPath` 保留旧单 APK 锚点；`DeployItem.targetApkPaths` 表示真实部署目标，并在真实 `apkPath` 存在时自动包含它。
- `JuggDeployTask` 按 `applicationId` 分组部署前，会使用 `JuggDeployData.filterForApks(...)` 得到 scoped data，避免 base/test APK 互相错投。
- `OverlayUpdateBuilder`、`IncrementalDeployHelper`、`ResourceApkGenerator`、`DeployDataDatabase.addFullRes()` 都应优先按 `targetApkPaths` 判断目标 APK。
- library Test APK 懒加载补齐后，只通过 compile context 的 APK 列表更新接口写回 `apks.json`，不改写 `full_build_info.json`。

---

## 5. 关键防护逻辑

- overlay id 校验，避免跨项目/跨设备污染。  
- 切片部署（`SliceDeployHelper`）降低单次推送风险。  
- 部署失败时可回退 Gradle 路径。  
- 兼容模式设备记录与清理（JVMTI 兼容性问题）。
- `DeployFileManager#getDeployData` 在“本轮 dex + 历史 dex（排除已 merge 记录）> 500”时触发 dex merge，减少设备加载 dex 数量，缓解 dex 加载 OOM 风险。
- recover 增量部署状态时，dry deploy 前会通过 `pm path <package>` 快速检测目标 App 是否已安装，并打印检测耗时；未安装时跳过 app 启动与 3s deployable 等待，直接进入 `App not installed, start reinstalling app...` 的重装分支。
- 开启 `JuggSettings.isEnableDirectOverlayDeploy` 后，recover 的 dry deploy 前会通过 `DirectOverlayStateChecker.checkRecover` 校验 deploy history、deployment cache 与设备 `code_cache/.overlay/id` 三路一致：一致则跳过 app 启动与 apply changes 探测，本地 history/cache 不一致或设备 overlay 不一致则进入重装分支，adb/run-as 无法确认时回退旧 dry deploy 流程。
- 同一开关也控制 Direct Overlay Writer swap transport。该 transport 不会替代在线 hot reload：只有 `DeployStateManager` 判定设备未 ready 且本轮本来需要重启生效时才尝试，在线场景仍优先保留 Apply Changes 的 pipe/proto 分发能力。
- 部署过程中遇到模拟器/设备短暂 offline 时，会统一识别 `AdbCommandRejectedException: device offline`、`adb: device offline` 以及 deployer channel 的 `InvalidProtocolBufferException: Protocol message contained an invalid tag (zero)`。全链路共用 `AdbTransientOffline`：**每 500ms 轮询**，最长 **3s** 等待 transport 恢复；就绪判定为 **`adb get-state` + `adb shell true`（CLI）** 或 **ddmlib `IDevice.isOnline` + deployer shell** 任一成功（CLI 优先，避免 IDE 侧 `isOnline` 滞后导致误判仍 offline）。恢复后原地重试当前 ADB/deployer 操作一次，或编排层 `redeploy` 一次。**install 路径**与 shell/swap、`DeployRetryHandler` 同策略；整轮 deploy 因 offline 进入 `tryRetry` 时跳过 `isAppForeground`。
- `IdeaDeviceAdb` 的 ddmlib shell 调用超时后会重启 adb 并重试；若最终回退到 adb CLI，CLI fallback 带硬超时，超时会抛出错误让部署任务进入失败终态，避免 MCP job 一直停留在 `running`。

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
- 部署相关测试落点（L1/L2/L3）：`06_testing.md` §7.1
