# JuggDeployerHelper DeployFlowTest 用例规格

> 状态：编码中（001/002 **mock 编排切片**已落地；Direct overlay 设备写盘与 `JuggDeployer` 分支待 L1/L2-integration/real）  
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
| `JuggDeployTask` / `JuggDeployer` / `deploy_compat` | 无单测 / 真机间接 | 不在此文件展开 device 协议细节 |
| `DirectOverlayStateChecker` / `Writer` | `main/.../direct/*Test` | L1 |
| `LibraryTestApkBackfillHelper` 业务细节 | `LibraryTestApkBackfillHelperTest` | DeployFlowTest 只验证 **deployIncremental 是否调用 backfill 及 install 回调结果** |
| `TestLauncher` logcat 算法 | `TestLauncherResultTest` | androidTest 仅验证 Helper 分支选择 |

### 2.3 与现有测试的关系

```
TopLevelFlowTest / AndroidTestTopLevelFlowTest     (L3 真机 happy path)
JuggDeployerHelperDeployFlowTest                   (L2 分支矩阵，本计划)
DeployRetryHandlerTest / JuggDeployerHelperRecoverTest   (保留，不重复测 retry/recover 内部)
JuggDeployerHelperDeployTest                       (合并入 DeployFlowTest 后删除，见 §9)
```

---

## 3. 测试分层与设备后端

### 3.1 L2 / L3 分工

- **L2（§5）**：场景化步骤 + 断言；入口必须是 `JuggDeployerHelper.deploy()`（或文档允许的等价入口）。
- **L3（§6）**：真机 + demo 工程，证明主链路在 refactor 后仍可跑通；**不替代** L2 的分支矩阵。

L2 **禁止**只测 `DeployStateRecover` / `DeployRetryHandler` 内部逻辑而不经过 Helper（存量单测文件继续承担那部分）。

### 3.2 设备后端：`mock` / `real` 可切换（推荐）

每条 L2 用例标注 **`设备后端`**：

| 后端 | 适用 | 实现要点 |
|------|------|----------|
| **`mock`（默认）** | CI、日常 PR、分支接线 | `IDevice` + `IDeviceAdb` fake；`DeployStateManager` / `DeployStateRecover` 可注入返回值；overlay 文件一致性用 **内存目录或 stub 脚本输出** 断言 |
| **`real`（可选）** | Direct overlay 写盘、run-as、ZIP push | `@RequiresDeviceRule` + 与 `TopLevelFlow` 相同 demo；同一用例 ID 下增加 `@DeployFlowRealDevice` 或 JUnit category，本地/夜间 job 跑 |

**对你写法的评估（结论）**：

| 维度 | 评价 |
|------|------|
| **场景 + 条件 + 分步断言** | ✅ 比表格一句话更适合 DeployFlowTest；与「验证设备目录与推送产物一致」这类 **端到端子目标** 对齐 |
| **术语「app 不在线」** | ⚠️ 需在条件里写清映射：在 Direct overlay 语境下 = **`DeployStateManager` 判定 `isReadyDeploy == false`**（不可 Apply Changes），**不是** adb offline。adb 仍须 **设备在线** |
| **术语「app 在线」** | = `isReadyDeploy == true` → `DirectOverlaySwapTransport.canTry` 为 false → 走 **Apply Changes**，不应出现 direct write |
| **mock / real 切换** | ✅ 推荐：**同一用例 ID、两套后端**。mock 保证全分支可每日跑；real 只覆盖 §5.2 Direct overlay 与少量 reinstall（避免 45 条全跑真机） |
| **与 L1 边界** | `DirectOverlayStateChecker` / `Writer` 单元细节仍在 `main/.../direct/*Test`；DeployFlowTest 验证 **Helper 编排是否走到** 这些能力，real 后端才断言设备目录 |

**实现约定（编码时）**：

```text
interface DeployFlowDeviceBackend {
  fun prepare(condition: DeployFlowCondition): DeployFlowFixture
}
// DeployFlowMockBackend / DeployFlowRealDeviceBackend
// 测试方法: @Test fun df_l2_001(...) = runFlow(DF_L2_001, backendFromEnv())
```

环境变量或 Gradle property，例如 `-Ddeploy.flow.device=real`，未设置则 `mock`。

### 3.4 Direct Overlay 覆盖矩阵（review 对齐，避免 mock L2 过度承诺）

`RecordingDeployRunTaskExecutor` **绕过** `JuggDeployerHelper.executeDeployRunTask` → `JuggDeployTask` → `JuggDeployer.optimisticSwap`，因此 **mock 后端不能证明** `DirectOverlaySwapTransport.trySwap` / writer / `AsDeployerCompat.optimisticSwap` 分支。

| 规格步骤（§5.1） | 证明层级 | 落点 |
|------------------|----------|------|
| Helper：`deploy()` → recover → `runTask` 且 `isDeviceReadyDeploy=false` | **L2-mock**（编排） | `JuggDeployerHelperDeployFlowTest` + `RecordingDeployRunTaskExecutor` |
| `DirectOverlaySwapTransport.canTry` / `trySwap`、writer、未走 Apply Changes | **L1** | `idea/.../direct/DirectOverlaySwapTransportTest`；`main/.../direct/DirectOverlayWriterTest` |
| `JuggDeployer` 在 `optimisticSwap` 内优先 `tryDirectOverlaySwap`（`:168`），失败再 `AsDeployerCompat` | **L2-integration**（待补） | 建议 `JuggDeployerDirectOverlayTest`：stub `deploymentService` + `RecordingAdb`，**不** stub 整个 `runTask` |
| 设备 `code_cache/.overlay`、push zip 清单与产物一致 | **L2-real / L3** | `DeployFlowRealDeviceBackend` 或 `TopLevelFlowTest` 夜间 job |

**命名约定**：同一 `DF-L2-00x` ID 下拆三条实现轨，避免 CI 绿但语义不完整：

- `DF-L2-00x-orchestration`（mock，默认跑）
- `DF-L2-00x-deployer`（L2-integration，待补）
- `DF-L2-00x-real`（`@Ignore` 或 `-Ddeploy.flow.device=real`）

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

## 4. 实现前置（编码前需对齐）

为满足 AGENTS「禁止为测试新增 test-only lambda」，L2 需下列**生产语义**接缝（实现阶段再定具体类名）：

| 接缝 | 职责 | 用途 |
|------|------|------|
| `IJuggDeployTaskRunner`（拟定） | 封装原 `runTask`：入参 `JuggDeployRunTaskRequest`，出参 `LaunchResult` | L2 注入成功/失败/抛错，避免真 `JuggDeployTask` |
| 既有 `IJuggDeployHelperRunHost` | Recover/Retry 回调 | 已由 `DeployStateRecover` / `DeployRetryHandler` 使用；DeployFlowTest 用 **真实 Helper** + mock 协作对象 |
| `DeployStateRecover` | 可 mock 实例注入 Helper（若当前写死 `private val` 需改为构造注入） | 控制 recover 返回值 |
| `DeployRetryHandler` | 同上 | 控制 retry 返回值（可选；也可只 mock `runTask` 抛错走真实 Retry） |

**不在接缝清单内、实现时禁止**：

- 仅为测试增加 `runTaskProvider: () -> LaunchResult` 等无业务语义的 lambda 参数。

编码前评审：接缝是否足够覆盖 §5 全部用例；Direct overlay **real** 后端需能读取 `code_cache/.overlay` 与 push 的 zip 清单。

---

## 5. L2 用例清单（`JuggDeployerHelperDeployFlowTest`）

### 5.0 写法约定

每条用例包含：

- **标题**：`# DF-L2-xxx 场景名`
- **设备后端**：`mock` | `real` | `mock+real`（后者表示两种后端都要实现，ID 相同）
- **条件**：前置状态（使用 §3.3 词汇表）
- **步骤**：从 `JuggDeployerHelper.deploy(DeployOptions)` 起，按时间顺序列出 **观察点**（可混合 `verify` 与 real 文件检查）
- **Kotlin 方法名**（建议）：与标题语义一致，如 `` `df l2 001 direct overlay incremental deploy when app not deployable` ``

**入口**：一律 `deploy(isInstall=false)` 增量场景，除非标题写明 install / embedded。

---

### 5.1 Direct Overlay 增量部署

#### DF-L2-001 direct write 增量部署成功

**设备后端**：`mock+real`（mock 仅覆盖 **编排轨** `DF-L2-001-orchestration`，见 §3.4）

**条件**：

- `JuggSettings.isEnableDirectOverlayDeploy = true`
- 设备在线
- **app 不可 deploy**（`isReadyDeploy == false`）
- deployment cache 中 overlay id **非 base**；**设备** `code_cache/.overlay/id` 与 cache / history **一致**（`DirectOverlayStateChecker.checkDevice` → MATCHED）
- 本轮 `JuggDeployData` 非空、非 install

**步骤**：

| # | 断言 | 层级 |
|---|------|------|
| 1 | `deploy()` → `deployIncrementalChanges` →（recover）→ `runTask` 请求里 `isDeviceReadyDeploy=false` | **L2-mock** ✅ 已实现 |
| 2 | `DirectOverlaySwapTransport.canTry == true` | **L1**（`DirectOverlaySwapTransportTest`） |
| 3 | writer 前 `checkDevice` → MATCHED | **L1** + recover 用例 |
| 4 | 走 direct write；**未**调用 `AsDeployerCompat.optimisticSwap` | **L2-integration**（`JuggDeployer.kt:168`） |
| 5 | `deploy()` 成功；`storeEntry` 写入新 overlay id | **L2-integration** / **L2-real** |
| 6 | 设备 overlay 目录与 `DirectOverlayWriteRequest` 一致 | **L2-real** / **L3** |

---

#### DF-L2-002 recover 后 direct write（overlay 不一致 → 重装）

**设备后端**：`mock+real`（mock 仅 **编排轨** `DF-L2-002-orchestration`；MISMATCHED dry 由 `JuggDeployerHelperRecoverTest` 承担）

**条件**：

- Direct overlay 开关开启
- 设备在线，**app 不可 deploy**
- **overlay 设备不一致**（history/cache 与设备 `code_cache/.overlay/id` 不匹配，或 cache 缺失）
- 已编译产物存在（history 中有 **历史产物 overlay id**）

**步骤**：

| # | 断言 | 层级 |
|---|------|------|
| 1 | `!isReadyDeploy` → `recoverDeployState` | **L2-mock** ✅ |
| 2 | `checkRecover` → MISMATCHED，不 restart 做 Apply Changes 探测 | **L2** `JuggDeployerHelperRecoverTest` |
| 3 | reinstall（`runRecoverDeployTask` + `forInstall`）+ `resetAfterReinstall` | **L2-mock** ✅（mock recover 模拟决策；非真实 MISMATCHED 链） |
| 4 | 再次 `runTask` 且 `isDeviceReadyDeploy=false` | **L2-mock** ✅；direct write 本体见 001 的 L2-integration / real |
| 5 | 重装后设备 overlay 与 history/cache 对齐 | **L2-real** / **L3** |

---

#### DF-L2-003 recover dry：三路 overlay 一致（跳过重装）

**设备后端**：`mock`（real 可选，难构造稳定三路一致）

**条件**：

- Direct overlay 开关开启
- 设备在线，app 已安装
- **overlay 三路一致**（history、deployment cache、设备 id 相同）

**步骤**：

1. `deploy()` → `recoverDeployState(isNeedTryDeyDeployFirst=true)`。
2. 断言：`tryDryDeploy` → `DirectOverlayStateChecker.checkRecover` **MATCHED**。
3. 断言：**未** `restartApp`；**未** `runRecoverDeployTask`（无 reinstall）。
4. 断言：recover 返回 `(true, false)`；后续 incremental 可继续（若 `isReadyDeploy` 仍为 false 且满足 canTry，仍可在 runTask 走 direct write）。

---

#### DF-L2-004 direct write 失败：writer 修改目录前失败（回退 Apply Changes）

**设备后端**：`mock`

**条件**：

- 同 DF-L2-001，但 stub `DirectOverlayWriter.write` → **SKIPPED**（或 push 失败模拟修改前失败）

**步骤**：

1. `deploy()` → `tryDirectOverlaySwap` 返回 null。
2. 断言：走进 **Apply Changes** 路径（`AsDeployerCompat.optimisticSwap` 被调用）。
3. 断言：整轮 deploy **不因 SKIPPED 直接失败**（除非 Apply Changes 也失败，本用例 mock AC 成功）。
4. 断言：**未**抛 `DirectOverlayDirtyException`。

---

#### DF-L2-005 direct write 脏失败：writer 修改目录后失败

**设备后端**：`mock`

**条件**：

- 同 DF-L2-001，但 `DirectOverlayWriter.write` → **FAILED_DIRTY**

**步骤**：

1. `deploy()` → `tryDirectOverlaySwap` 抛 **`DirectOverlayDirtyException`**。
2. 断言：**不**回退 Apply Changes（避免半提交状态二次写入）。
3. 断言：`deploy()` 失败或进入 catch → `DeployRetryHandler`（reason 含 `Direct overlay`）；`recoverDeployState` 可在 retry 中被调用（与 RetryHandlerTest 对齐）。

---

#### DF-L2-006 app 可 deploy 时跳过 direct write（走 Apply Changes）

**设备后端**：`mock`

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

**设备后端**：`mock+real`

**条件**：

- app 不可 deploy，cache 期望 id = A，设备实际 id = B

**步骤**：

1. `tryDirectOverlaySwap` 内 `checkDevice` → **MISMATCHED**。
2. 断言：返回 null，**回退** Apply Changes（同 DF-L2-004）。
3. 断言：与 DF-L2-002 区别：本条 **不经过** recover reinstall，仅 swap 阶段跳过 direct。

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

**「全分支」定义**：§5 列出的 **DF-L2-001～026** 在 **mock 后端** 全部实现且定向跑绿；标注 `mock+real` 的用例在夜间或本地 `-Ddeploy.flow.device=real` 再绿一次。

| 维度 | 完成后目标 |
|------|------------|
| Direct overlay（001–007） | mock **100%**；001/002/007 real **必做** |
| recover / incremental 前置（010–017） | mock **≥95%** |
| deploy 入口 / retry（020–026） | mock **100%** |
| `runTask` 切片 / dex 删除 / 多片 | 若未单列用例，可在 001/023 的 mock 中附带 verify，或补 DF-L2-030+ |

**待评审补 ID**（实现前发现再追加一小节即可）：

- `isNeedPushResourceApk == true`
- `removeLibraryDexFiles` 仅 warn
- `retryDeployData` 跳过 `getDeployData`

---

## 8. 实施阶段（编码顺序）

| 阶段 | 内容 | 依赖 |
|------|------|------|
| **A** | 评审 §3.2 设备后端 + §4 接缝 + §5 Direct overlay 七条 | 本文件评审通过 |
| **B** | `DeployFlowMockBackend` + P0：001、004、006、010、020、023 | 阶段 A |
| **C** | 002、005、007、011–017、021–026 | 阶段 B 绿 |
| **D** | `DeployFlowRealDeviceBackend`：001、002、007 真机子集 | 设备可用 |
| **E** | L3 §6；删 `JuggDeployerHelperDeployTest` | 阶段 C |

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

## 11. 评审检查表（编码前勾选）

- [ ] §5 Direct overlay（001–007）条件/步骤是否与你的场景一致？
- [ ] 「app 不在线」= `!isReadyDeploy` 的词汇是否接受（§3.3）？
- [ ] `mock+real` 双后端策略是否接受（§3.2）？
- [ ] §4 接缝（含 real 读 overlay 目录）是否允许阶段 A 小 refactor？
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
