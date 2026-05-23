# JuggDeployerHelper DeployFlowTest 用例规格

> 状态：Virtual Device 单轨契约已对齐（见 [jugg_deploy_flow_virtual_device.md](jugg_deploy_flow_virtual_device.md)）；DF-L2-001～008 已落地，DF-L2-010～026 待编码
> 日期：2026-05-22  
> 一致性：与 `AGENTS.md`、`docs/ai_knowledge/06_testing.md` 一致；冲突时以代码为准。

---

## 1. 文档目的

在实现代码前，固定 **`JuggDeployerHelper` 部署流程** 的自动化用例清单，使：

- 覆盖 `deploy()` → `deployInstall` / `deployIncrementalChanges` / `embeddedToApk` / `runTask` 的**决策分支**（非仅 happy path）；
- 区分 **L2（mock 设备/协作对象）** 与 **L3（真机 + demo 工程）** 的职责；
- 与现有 `JuggDeployerHelperRecoverTest`、`DeployRetryHandlerTest`、`TopLevelFlowTest` 的关系清晰，避免重复或遗漏。

**目标测试类（拟定）**：`idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelperDeployFlowTest.kt`

---

## 2. 范围

### 2.1 In scope

| 被测入口 | 说明 |
|----------|------|
| `JuggDeployerHelper.deploy` | 顶层分派、catch、重试接线 |
| `deployInstall` | 经 `deploy(isInstall=true)` 触发 |
| `deployIncrementalChanges` | 经 `deploy(isInstall=false)` 触发 |
| `embeddedToApk` | `isEmbeddedToApk` 或 `!isDebuggable` 触发 |
| `runTask` | 经上述路径间接覆盖；L2 需可替换执行器 |
| `tryRetryInstall` | 经 `deploy` catch + `isInstall` 触发 |
| `detectJvmtiCompatIssue` | 经 `deploy` catch → `DeployRetryHandler` 触发 |
| `runRecoverDeployTask` | 经 `DeployStateRecover` → `IJuggDeployHelperRunHost` 回调 |

### 2.2 Out of scope（本计划不新增用例）

| 模块 | 已有测试 | 说明 |
|------|----------|------|
| `DeployStateRecover.tryDryDeploy` / `recoverDeployState` 内部细节 | `JuggDeployerHelperRecoverTest` | DeployFlowTest 只验证 **Helper 是否以正确参数调用** recover |
| `DeployRetryHandler.tryRetry` 分支矩阵 | `DeployRetryHandlerTest` | DeployFlowTest 只验证 **Helper.deploy catch 是否委托** retry |
| `JuggDeployTask` / `JuggDeployer` 内部算法 | L1 transport 测试 | 经 **Virtual Device** 在 DeployFlowTest 覆盖 direct 分支；协议细节不拆第三套用例 |
| `DirectOverlayStateChecker` / `Writer` | `main/.../direct/*Test` | L1 |
| `LibraryTestApkBackfillHelper` 业务细节 | `LibraryTestApkBackfillHelperTest` | DeployFlowTest 只验证 **deployIncremental 是否调用 backfill 及 install 回调结果** |
| `TestLauncher` logcat 算法 | `TestLauncherResultTest` | androidTest 仅验证 Helper 分支选择 |

### 2.3 与现有测试的关系

```
TopLevelFlowTest / AndroidTestTopLevelFlowTest     (L3 真机 happy path)
JuggDeployerHelperDeployFlowTest                   (L2 Virtual Device：001～007 全链，本计划)
DeployRetryHandlerTest                             (L2 补充：retry 单元网)
JuggDeployerHelperRecoverTest                      (L2 补充：dry/MISMATCHED 细节)
DirectOverlaySwapTransportTest                     (L1：transport 窄脚本)
JuggDeployerHelperDeployTest                       (L2 早退；合并后可删，见 §9)
```

---

## 3. L2 设备后端：Virtual Device 单轨

> **实现契约（权威）**：[jugg_deploy_flow_virtual_device.md](jugg_deploy_flow_virtual_device.md)

### 3.1 L2 / L3 分工

- **L2（§5）**：`JuggDeployerHelper.deploy()` 入口 + **真实** `executeDeployRunTask` → `JuggDeployer.optimisticSwap` → direct overlay；设备侧用 **VirtualDeployDevice**（FS + 窄脚本）。
- **L3（§6）**：真机 + demo（`TopLevelFlowTest`）；**不** 在 DeployFlowTest 内维护 `real` 双轨或 `@Ignore` 真机用例。

L2 **禁止**只测 `DeployStateRecover` / `DeployRetryHandler` 而不经过 Helper（存量单测继续承担细节网）。

### 3.2 Virtual Device 概要

| 组件 | 说明 |
|------|------|
| **FS 内核** | tempDir 映射 `data/data/{pkg}/code_cache`、`/data/local/tmp/jugg` |
| **双适配** | `asIDeviceAdb()` + `asDdmlibDevice()` 共用同一 FS |
| **install** | `DeployFlowAsDeployerCompatBoundary.install`；副作用 = 清空 virtual `code_cache` |
| **Apply Changes** | `DeployFlowAsDeployerCompatBoundary.optimisticSwap`；001/002/003/005/008 禁止，004/006/007 记录 fallback |
| **IDE deploy 状态** | 注入 **`IIdeDeployStateHelper`**（物理边界），驱动 `DeployStateManager` |
| **本地 cache** | 真实 **`IJuggDeploymentService`**；与 install **不联动** |
| **overlay 三路** | **前置流程** 写 history + storeEntry + virtual id（禁止仅 mock 对齐） |

已废弃：`RecordingDeployRunTaskExecutor`、`DeployFlowRealDeviceBackend`、`deploy.flow.device=real`。

### 3.3 条件字段词汇表（§5 统一使用）

| 文案 | 含义 |
|------|------|
| **设备在线** | `IDevice.isOnline == true`，adb shell 可用 |
| **设备离线** | adb offline / `IDevice` 不可用（用于 ADB 重试用例，非 Direct overlay 主路径） |
| **app 不可 deploy** | `getDeployState(device).isReadyDeploy == false`；Direct overlay **允许**尝试 |
| **app 可 deploy** | `isReadyDeploy == true`；Direct overlay **不**尝试，走 Apply Changes |
| **overlay 三路一致** | `deployHistory.lastDeployOverlayIds`、deployment cache、`code_cache/.overlay/id` 一致 |
| **overlay 设备不一致** | 上述任一路径与期望 id 不符 |
| **历史产物 overlay id** | 本地 staging/deploy history 记录的 id，用于 recover 判断 |

---

## 4. 实现前置（已对齐）

生产接缝与物理边界详见 [jugg_deploy_flow_virtual_device.md §3–§7](jugg_deploy_flow_virtual_device.md)。

| 类别 | 内容 |
|------|------|
| **生产接缝** | `LaunchContext.deviceAdb`；`executeDeployRunTask` 全量 `deviceAdbFactory`；`JuggDeployer`/`Task` → `IJuggDeploymentService`；`installPathProvider` 注入 |
| **物理边界 mock** | `VirtualDeployDevice`；`DeployFlowAsDeployerCompatBoundary.install` / `optimisticSwap`；`IIdeDeployStateHelper` |
| **真实协作** | `DeployStateRecover`（002）；`JuggDeploymentService` 本地 cache；overlay 三路 **前置 API** |
| **APK** | `com.sickworm.intellij.jugg.mock.context.apkInfos`（`Commons.kt`） |
| **禁止** | `runTaskProvider` lambda；用 `whenever(lastDeployOverlayIds)` 代替三路前置 |

`IJuggDeployRunTaskExecutor` 保留用于其他 L2 早退/故障注入用例时可选；**001～007 不走 recording executor**。

---

## 5. L2 用例清单（`JuggDeployerHelperDeployFlowTest`）

### 5.0 写法约定

每条用例包含：

- **标题**：`# DF-L2-xxx 场景名`
- **设备后端**：**Virtual Device**（单轨，见 §3）
- **条件**：前置状态（使用 §3.3 词汇表）
- **步骤**：从 `JuggDeployerHelper.deploy(DeployOptions)` 起，按时间顺序列出 **观察点**（可混合 `verify` 与 real 文件检查）
- **Kotlin 方法名**（建议）：与标题语义一致，如 `` `df l2 001 direct overlay incremental deploy when app not deployable` ``

**入口**：一律 `deploy(isInstall=false)` 增量场景，除非标题写明 install / embedded。

---

### 5.1 Direct Overlay 增量部署

#### DF-L2-001 direct write 增量部署成功

**设备后端**：Virtual Device（§3、[契约 §5.1](jugg_deploy_flow_virtual_device.md#51-df-l2-001-前置matched)）

**条件**：

- `JuggSettings.isEnableDirectOverlayDeploy = true`
- 设备在线（virtual `isOnline`）
- **app 不可 deploy**（`IIdeDeployStateHelper` → `isReadyDeploy == false`）
- 三路 overlay **前置一致**（history + 本地 `storeEntry` + virtual `code_cache/.overlay/id`）
- `JuggDeployData` 来自 `mock.context.apkInfos`，非空、非 install

**步骤**（同一 `@Test` 内）：

| # | 断言 | 手段 |
|---|------|------|
| 1 | `deploy()` → recover（可选 dry 成功）→ 真实 `JuggDeployTask` | Virtual + 真实 Helper |
| 2 | `canTry` 且 `tryDirectOverlaySwap` 成功 | 生产代码 + virtual 脚本/markers |
| 3 | writer 前 `checkDevice` → MATCHED | virtual FS 已与 cache 对齐 |
| 4 | **未**调用 Apply Changes fallback | `DeployFlowAsDeployerCompatBoundary.optimisticSwapInvokeCount == 0` |
| 5 | `deploy()` 成功；本地 `storeEntry` 新 overlay id | 真实 `IJuggDeploymentService` |
| 6 | virtual 上 overlay 文件与 `DirectOverlayWriteRequest` 一致 | 读 tempDir FS |

---

#### DF-L2-002 recover 后 direct write（overlay 不一致 → 重装）

**设备后端**：Virtual Device（§3、[契约 §5.2](jugg_deploy_flow_virtual_device.md#52-df-l2-002-前置mismatched--install--再-matched)）

**条件**：

- Direct overlay 开启；设备在线；**app 不可 deploy**（incremental 阶段）
- history + 本地 cache 有 **H**；virtual 设备 id 为 **D≠H**（或缺失）→ MISMATCHED
- 真实 `DeployStateRecover`（非 mock recover）

**步骤**（同一 `@Test` 内）：

| # | 断言 | 手段 |
|---|------|------|
| 1 | `recoverDeployState` 因 `!isReadyDeploy` 触发 | 真实 Recover |
| 2 | `checkRecover` → MISMATCHED；不靠 restart 做 Apply Changes 探测 | virtual + 真实 dry 链 |
| 3 | compat boundary `install` 清空 `code_cache`；`runRecoverDeployTask`；`resetAfterReinstall` | `DeployFlowAsDeployerCompatBoundary` + 真实 Helper |
| 4 | 前置再对齐三路 → incremental `isDeviceReadyDeploy=false` → direct 成功 | `IIdeDeployStateHelper` 分阶段 |
| 5 | 未 `optimisticSwap`；virtual overlay 与产物一致 | 同 001 |

---

#### DF-L2-003 recover dry：三路 overlay 一致（跳过重装）

**设备后端**：Virtual Device

**条件**：

- Direct overlay 开关开启
- 设备在线，app 已安装
- **overlay 三路一致**（history、deployment cache、设备 id 相同）

**步骤**：

1. `deploy()` → `recoverDeployState(isNeedTryDeyDeployFirst=true)`。
2. 断言：`tryDryDeploy` → `DirectOverlayStateChecker.checkRecover` **MATCHED**。
3. 断言：**未** `restartApp`；**未** install recover task / reinstall。
4. 断言：后续 incremental 继续走 direct write，且 **未**调用 Apply Changes。

---

#### DF-L2-004 direct write 失败：writer 修改目录前失败（回退 Apply Changes）

**设备后端**：Virtual Device

**条件**：

- 同 DF-L2-001，但 `VirtualDeployDevice.failDirectOverlayPush=true`，模拟 writer 修改目录前失败。

**步骤**：

1. `deploy()` → `tryDirectOverlaySwap` 返回 null。
2. 断言：走进 **Apply Changes** 路径（`DeployFlowAsDeployerCompatBoundary.optimisticSwap` 记录调用）。
3. 断言：整轮 deploy **不因 SKIPPED 直接失败**（除非 Apply Changes 也失败，本用例 mock AC 成功）。
4. 断言：**未**抛 `DirectOverlayDirtyException`。

---

#### DF-L2-005 direct write 脏失败：writer 修改目录后失败

**设备后端**：Virtual Device

**条件**：

- 同 DF-L2-001，但 `VirtualDeployDevice.directOverlayWriteResult=APPLYING`，模拟 writer 已进入不允许 fallback 的脏状态。

**步骤**：

1. `deploy()` → `tryDirectOverlaySwap` 返回 dirty/applying 失败。
2. 断言：**不**回退 Apply Changes（避免半提交状态二次写入）。
3. 断言：`deploy()` 失败，reason 含 `Direct overlay`；本用例设置 `DO_NOT_RETRY`，避免 retry 掩盖 dirty 分支。

---

#### DF-L2-006 app 可 deploy 时跳过 direct write（走 Apply Changes）

**设备后端**：Virtual Device

**条件**：

- Direct overlay 开关开启
- 设备在线，**app 可 deploy**（`isReadyDeploy == true` → `LaunchContext.isDeviceReadyDeploy=true`）

**步骤**：

1. `deploy()` → `runTask` → `optimisticSwap`。
2. 断言：`DirectOverlaySwapTransport.canTry` 为 **false**。
3. 断言：**未**调用 `DirectOverlayWriter.write`；**调用** Apply Changes 通道。
4. 断言：适用于「app 在线热更」场景，与 DF-L2-001 互斥。

---

#### DF-L2-007 direct write 跳过：checkDevice 不匹配（设备 id 与 cache 期望不符）

**设备后端**：Virtual Device

**条件**：

- recover 阶段三路一致并 matched；recover 成功后设备 overlay id 被改成 B，cache 期望 id 仍为 A。

**步骤**：

1. `tryDirectOverlaySwap` 内 `checkDevice` → **MISMATCHED**。
2. 断言：返回 null，**回退** Apply Changes（同 DF-L2-004）。
3. 断言：与 DF-L2-002 区别：本条 **不经过** recover reinstall，仅 swap 阶段跳过 direct。

---

#### DF-L2-008 recover reinstall 后 base install cache → direct write + AS startup agent push

**设备后端**：Virtual Device

**条件**：

- Direct overlay 开启；设备在线；**app 不可 deploy**
- recover 前 overlay 设备不一致 → 触发 reinstall
- reinstall 后 deployment cache / history 为 **base install**（`isBaseInstall == true`），设备无 `.overlay` 目录
- mock `AdbInstaller.version` 与 matryoshka installer fixture 可用；设备 `startup_agents` 初始为空

**步骤**：

1. `recoverDeployState` → reinstall → `restoreBaseInstallCacheAfterMockInstall`。
2. incremental `tryDirectOverlaySwap`：`AsStartupAgentPusher` push → `checkDevice("", NO_DIR)` → MATCHED → writer 成功。
3. 断言：`hasAsStartupAgentPush()`；`listStartupAgents()` 含 `{version}-agent.so`。
4. 断言：**未** `optimisticSwap`；virtual overlay id 已更新为非空。

---

### 5.2 增量部署：前置检查与其它分支

#### DF-L2-010 无连接设备

**设备后端**：`mock`

**条件**：`deployTargetManager.hasDevice == false`

**步骤**：

1. `deploy(isInstall=false)`。
2. 断言：立即失败，`failedReason` 含 `no device connected`；**不**进入 `runTask`。

---

#### DF-L2-011 warmUp 且设备未 ready

**设备后端**：`mock`

**条件**：`isWarmUp=true`，`isReadyDeploy=false`

**步骤**：

1. `deploy()`。
2. 断言：失败，`device not ready to warm up`。

---

#### DF-L2-012 resign APK 失败后中止

**设备后端**：`mock`

**条件**：`deployData.isNeedUpdateApk=true`，`retryReason=null`，`IncrementalDeployHelper.updateApk` 失败

**步骤**：

1. `deploy()`。
2. 断言：`isCanFallback=true`；**不**调用 `recoverDeployState`。

---

#### DF-L2-013 recover 返回失败

**设备后端**：`mock`

**条件**：`!isReadyDeploy`，`isReadyIncCompile`，`recoverDeployState` → `(false, false)`

**步骤**：

1. `deploy()`。
2. 断言：`failedReason` 为 `Try recover deploy state failed.`；**不**进入本轮 `runTask` 成功路径。

---

#### DF-L2-014 编译状态非法

**设备后端**：`mock`

**条件**：`!isReadyDeploy` 且 `!isReadyIncCompile`

**步骤**：

1. `deploy()`。
2. 断言：`Invalid state for deploy.`。

---

#### DF-L2-015 快速降级为 hotfix

**设备后端**：`mock`

**条件**：`JuggSettings.isQuickFallbackToHotFix=true`，deploy data 含 hotFix 或 removed library class

**步骤**：

1. `deploy()` 在 `runTask` 前改写 data 为 hotfix 形态。
2. 断言：传入 task runner 的 data `hotReloadModifiedClasses` 为空或符合 `toFallbackToHotFixData()`。

---

#### DF-L2-016 末台设备提交 history

**设备后端**：`mock`

**条件**：deploy 成功，`isLastDevice=true`

**步骤**：

1. 断言：`deployFileManager.commit`、`updateHistoryOnAfterDeployed`、`lastDeployOverlayIds` 更新。

---

#### DF-L2-017 library test APK backfill 后 merge overlay

**设备后端**：`mock`

**条件**：`androidTestRunSpec` 触发 backfill，install 回调执行

**步骤**：

1. 断言：`mergeOverlayIds` 保留已有 app overlay，合并 library test overlay。
2. （细节仍由 `LibraryTestApkBackfillHelperTest` 覆盖；本条只验证 deploy 接线。）

---

### 5.3 deploy 入口：install / embedded / 取消 / 重试

#### DF-L2-020 用户取消

**设备后端**：`mock`

**条件**：`processHandler.isCanceled=true`

**步骤**：

1. `deploy()`。
2. 断言：`failedReason=deploy canceled`；不进入 `runTask`。

---

#### DF-L2-021 首次 install

**设备后端**：`mock`（real 由 DF-L3-001 覆盖）

**条件**：`deploy(isInstall=true)`，task 成功，`isLastDevice=true`

**步骤**：

1. 断言：`runTask` 使用 INSTALL；`stopApp` 被调用；`lastDeployOverlayIds` 更新。

---

#### DF-L2-022 embedded to apk

**设备后端**：`mock`

**条件**：`isEmbeddedToApk=true` 或 `!isDebuggable`；`updateApk` 成功

**步骤**：

1. 断言：先 `updateApk` 嵌入，再 install `runTask`；**不**走 incremental direct overlay。

---

#### DF-L2-023 增量失败 → DeployRetryHandler

**设备后端**：`mock`

**条件**：`runTask` 抛 `OVERLAY_ID_MISMATCH`；`tryRetry` 返回成功 `DeployTaskResult`

**步骤**：

1. `deploy()` catch。
2. 断言：调用 `DeployRetryHandler.tryRetry`；返回 retry 结果；**同 reason 不二次 retry**。

---

#### DF-L2-024 install 失败 → uninstall 重装

**设备后端**：`mock`

**条件**：`isInstall=true`，异常含 `INSTALL_FAILED_INVALID_APK`

**步骤**：

1. 断言：`AdbClient.uninstall` 各 applicationId；再次 `deploy()`。

---

#### DF-L2-025 JVMTI compat：runTask 末尾抛 compat 消息

**设备后端**：`mock`

**条件**：push agent + restart 后 `isHasJvmtiCompatIssue=true`

**步骤**：

1. `deploy()` catch → `tryRetry` 使用 compat deploy data（与 `DeployRetryHandlerTest` 一致）。

---

#### DF-L2-026 androidTest：instrument 成功/失败

**设备后端**：`mock`

**条件**：`androidTestRunSpec` 非空

**步骤**：

1. 成功子场景：`TestLauncher.run()==true`，**不** `restartApp`（走 instrument 分支）。
2. 失败子场景：`run()==false` → `LaunchResult.success=false` → deploy 失败。

---

**L2 合计：26 条**（001–007 为 Direct overlay 核心；010–026 为编排与其它分支）。旧表格式 45 条已合并删减，避免与 L1 重复。

**P0 实现顺序**：001 → 002 → 004 → 006 → 010 → 013 → 020 → 023。

---


## 6. L3 用例清单（真机，复用/扩展 Flow）

不新建重复场景时，在既有类 **追加** 并在此登记 ID。

| ID | 目标类 | 方法名（建议） | 场景 | 证明的 Helper 分支 |
|----|--------|----------------|------|-------------------|
| DF-L3-001 | `TopLevelFlowTest` | （已有）`testInstallAndLaunch` | 全量编译后首次安装 | `deployInstall` + `runTask` INSTALL |
| DF-L3-002 | `TopLevelFlowTest` | （已有）`testDeploy` / `testDeploy2` / `testDeployKtActivity` | 改 Java/Kotlin 后增量 | `deployIncrementalChanges` 成功 + `updateInfoAfterIncDeploy` |
| DF-L3-003 | `AndroidTestTopLevelFlowTest` | （已有）`androidTestIncrementalDeployRunsUpdatedTestApk` | library test apk backfill + instrument | backfill install 回调 + `runTask` androidTest 分支 |
| DF-L3-004 | `TopLevelFlowWithGitTest` | （已有） | git 变更后 deploy | incremental 与历史状态交互 |
| DF-L3-005 | `TopLevelFlowTest` | **新增** `deployAfterCleanOverlayShouldRecoverOrReinstall` | 手动/脚本使 overlay 与 history 不一致后 deploy | `recoverDeployState` 真实 dry/reinstall（**待手测步骤附录**） |
| DF-L3-006 | `TopLevelFlowTest` | **新增** `deployHotFixChangeShouldRestartApp` | 修改需 hotfix 的类（如 interface default） | `isNeedRestartApp` / hotfix deploy type |

**L3 合计：6 条**（2 条为计划新增，编码前需确认是否真机稳定）。

---

## 7. 覆盖率目标（对齐用）

**「全分支」定义**：§5 列出的 **DF-L2-001～026** 在 **L2 后端** 全部实现且定向跑绿；用户可见主链路由 §6 的 L3 Flow 继续兜底。

| 维度 | 当前状态 / 完成后目标 |
|------|----------------------|
| Direct overlay（001–007） | **已实现**：Virtual Device **100%** |
| recover / incremental 前置（010–017） | 待实现：L2 **≥95%** |
| deploy 入口 / retry（020–026） | 待实现：L2 **100%** |
| `runTask` 切片 / dex 删除 / 多片 | 若未单列用例，可在 001/023 的 mock 中附带 verify，或补 DF-L2-030+ |

**待评审补 ID**（实现前发现再追加一小节即可）：

- `isNeedPushResourceApk == true`
- `removeLibraryDexFiles` 仅 warn
- `retryDeployData` 跳过 `getDeployData`

---

## 8. 实施阶段（编码顺序）

| 阶段 | 内容 | 依赖 |
|------|------|------|
| **A** | 评审 §3.2 设备后端 + §4 接缝 + §5 Direct overlay 七条 | 已完成 |
| **B** | `DeployFlowMockBackend` / Virtual Device + 001～007 | 已完成 |
| **C** | 010–017、020–026 | 阶段 B 绿 |
| **D** | L3 §6；删 `JuggDeployerHelperDeployTest` | 阶段 C |

---

## 9. 存量文件处理

| 文件 | 动作 |
|------|------|
| `JuggDeployerHelperDeployTest.kt` | 用例迁入 `DeployFlowTest` 后 **删除** |
| `JuggDeployerHelperRecoverTest.kt` | **保留** dry/recover 内部用例；Helper 接线改在 DeployFlowTest |
| `DeployRetryHandlerTest.kt` | **保留** retry 决策；`deploy` catch 接线在 DeployFlowTest |
| `TopLevelFlowTest` 等 | **保留** 并登记 §6 |

---

## 10. 运行方式（编码后）

```bash
# L2 全量
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelperDeployFlowTest"

# L2 单条
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelperDeployFlowTest.deploy flow should fail incremental when recover returns false"

# L3
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"
```

禁止无 `--tests` 的全量 `:idea:test`。

---

## 11. 评审检查表

- [x] §5 Direct overlay（001–007）条件/步骤是否与你的场景一致？
- [x] 「app 不在线」= `!isReadyDeploy` 的词汇是否接受（§3.3）？
- [x] Virtual Device 单轨是否接受（§3.2）？
- [x] §4 接缝是否允许阶段 A 小 refactor？
- [ ] 010–026 是否还要拆更多「步骤体」用例（如 resign 成功 → recover）？
- [ ] `DF-L3-005`/`006` 是否纳入首版？

---

## 12. 附录：`JuggDeployerHelper` 分支树（对照用）

```text
deploy
├─ canceled → fail
├─ isInstall → deployInstall → runTask
├─ isEmbeddedToApk → embeddedToApk
├─ !debuggable → embeddedToApk
├─ incremental → deployIncrementalChanges
│   ├─ !hasDevice
│   ├─ warmUp && !readyDeploy
│   ├─ backfill (+ install callback → runTask)
│   ├─ resign apk (fail / success → recover)
│   ├─ !readyDeploy → recover (fail / invalid state / success → refresh data)
│   ├─ quick hotfix fallback
│   └─ runTask → success (update history) / fail
└─ catch → tryRetryInstall | DeployRetryHandler.tryRetry | fail+isCanFallback

runTask (summary)
├─ empty apks → throw
├─ INSTALL → stopApp
├─ removeLibraryDexFiles (conditional)
├─ split slices loop
├─ push agent
├─ restart policy (settings / platform / data)
├─ androidTest branch | restart | startApp | noop
└─ jvmti compat check → throw compat message
```

---

## 13. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-05-22 | 初稿：DeployFlowTest L2/L3 用例规格 |
| 2026-05-22 | §5 改为场景小节 + 分步断言；§3 增加 mock/real 后端评估与词汇表；Direct overlay 001–007 对齐用户示例 |
