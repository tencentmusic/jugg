# DeployFlow Virtual Device 实现契约

> 状态：**已实现（DF-L2-001/002）**  
> 日期：2026-05-22  
> 关联：`jugg_deployer_helper_deploy_flow_test_plan.md` §5.1（DF-L2-001/002）、`JuggDeployerHelperDeployFlowTest`

---

## 1. 目标

在 **不新增 `@Test`** 的前提下，让 `DF-L2-001` / `DF-L2-002` 在同一用例内跑通：

```text
JuggDeployerHelper.deploy()
  → DeployStateRecover（002）
  → executeDeployRunTask（真实，无 RecordingDeployRunTaskExecutor）
  → JuggDeployTask → JuggDeployer.optimisticSwap
  → tryDirectOverlaySwap → DirectOverlaySwapTransport / Writer
```

仅在 **物理边界** mock；业务编排与 deployer 分支走生产代码。

真机端到端仍由 `TopLevelFlowTest` 等 **L3** 承担；本契约 **不** 维护 `deploy.flow.device=real` 双轨。

---

## 2. Virtual Device（选项 A）

### 2.1 结构

```text
VirtualDeployDevice
  ├── root: java.nio tempDir（设备文件系统根）
  ├── packageName: String
  └── narrowScriptInterpreter: __JUGG_* + run-as 写 overlay + push 映射

  fun asIDeviceAdb(): IDeviceAdb          // DirectOverlay / Recover / Helper 侧
  fun asDdmlibDevice(): IDevice         // AdbClient（parseApks / getPids / getArch）
```

**单一 FS 内核**：`IDeviceAdb.execAdbShellScript` 与 ddmlib 所需 shell 行为读写同一棵树，避免双轨漂移。

### 2.2 虚拟目录布局（v1）

以 `run-as` 语义映射到 tempDir（不执行真 shell）：

```text
{tempDir}/data/data/{packageName}/code_cache/.overlay/id     # 设备 overlay id
{tempDir}/data/data/{packageName}/code_cache/.overlay/...  # writer 解压产物
{tempDir}/data/local/tmp/jugg/                             # push 的 zip
```

### 2.3 窄脚本解释器（v1）

| 标记 / 模式 | 行为 |
|-------------|------|
| `__JUGG_OVERLAY_STATE__` | 读/写 `code_cache/.overlay/id`；返回 `__JUGG_OVERLAY_STATE__ ID {id}` |
| `__JUGG_DIRECT_OVERLAY__` | 按 `DirectOverlayWriter` 脚本语义更新 overlay 目录；返回 `__JUGG_DIRECT_OVERLAY__ OK` |
| `push` / `pull` | 本地文件 ↔ `{tempDir}/data/local/tmp/...` |
| `startup_agents`（若 JVMTI 检测走到） | 可返回固定 agent 列表或空（与 `DirectOverlaySwapTransportTest.RecordingAdb` 对齐） |

**不** 实现通用 shell；新脚本需求先扩表，禁止宽解释器。

### 2.4 install 语义（物理边界）

**不** 调用真 `pm install` / native Installer。

| 操作 | 行为 |
|------|------|
| `AsDeployerCompat.install`（测试 static mock） | 成功；副作用 = **清空** `{package}/code_cache/**` |
| `deployTargetManager.stopApp` / `restartApp` | no-op 或记录调用（非协议验证重点） |

**`JuggDeploymentService`**：纯开发机本地 deployment cache（memory + db 文件），**不** 随 install 清空或伪造；与设备 FS **解耦**。install 后若需增量 deploy，靠 **前置/后置流程** 单独 seed 本地 cache（见 §4.2）。

### 2.5 ddmlib `IDevice` 最小能力

供 `AdbClient` 完成 `optimisticSwap` 前置：

- `serialNumber` / `name` 稳定
- `version` API ≥ 26
- `isOnline == true`
- `getPids` → 非空或空列表均可（与现有测试一致即可）
- `getArch` / installer 相关：满足 `parseApks` 后不抛即可

---

## 3. 生产侧接缝（已同意）

| 接缝 | 改动要点 |
|------|----------|
| **`LaunchContext.deviceAdb`** | `JuggDeployTask` / `DirectOverlaySwapOptions` 使用 context 注入的 `IDeviceAdb`，禁止写死 `IdeaDeviceAdb(device)` |
| **`JuggDeployerHelper.deviceAdbFactory`** | `executeDeployRunTask` 内所有 `IdeaDeviceAdb(device)` 改为 factory（含 `SliceDeployHelper`、JVMTI 检测等） |
| **`JuggDeployer` + `JuggDeployTask`** | `deploymentService` 类型改为 **`IJuggDeploymentService`**（可注入）；Task 构造传入与 Helper 相同实例 |
| **`JuggDeployerHelper.installPathProvider`** | 测试注入 `Computable`（见 §4.3） |
| **`DeployStateManager(ideDeployStateHelper = …)`** | 已支持构造注入；测试注入 **`IIdeDeployStateHelper`** 控制 IDE 侧 deploy 探测 |

**禁止**：`runTaskProvider` 等无业务语义的 test-only lambda。

**移除**：`RecordingDeployRunTaskExecutor` 作为默认路径（可删或仅保留调试注释）。

---

## 4. 物理边界 vs 真实协作

| 边界 | 实现 | 说明 |
|------|------|------|
| 设备 FS + adb 脚本 | `VirtualDeployDevice` | 001/002 主路径 |
| `AdbClient` / ddmlib | `VirtualDeployDevice.asDdmlibDevice()` | 与 `IDeviceAdb` 同 FS |
| **install** | static mock `AsDeployerCompat.install` | 清空 virtual `code_cache` |
| **Apply Changes** | static mock `AsDeployerCompat.optimisticSwap` **never**（001/002） | 断言未走 fallback |
| **IDE deploy 探测** | mock **`IIdeDeployStateHelper.getIdeDeployState`** | 默认不可 deploy；002 mock install 后 **一次性** 返回 OK 供 `waitingForDeployable`，随后恢复不可 deploy 以走 direct overlay |
| `taskRunnerManager` JVMTI 异步 | mock 返回 `false`（存量 fixture） | 非 deploy 协议本体 |
| `Project` / `Logger` | Mockito（存量） | |

**走真实代码**：`JuggDeployerHelper`、`DeployStateRecover`（001 dry recover / 002 reinstall，无子类 override）、`JuggDeployTask`、`JuggDeployer.tryDirectOverlaySwap`、`DirectOverlaySwapTransport`、`DeployFileManager.resetAfterReinstall`（002）。

**测试 fixture（非流程分支）**：`DeployFlowRecoverFixtureHooks` 仅在 `recoverDeployState` 成功后刷新 IDE mock 状态；装包后 cache 对齐在 `DeployFlowRecoverRunHost`；`VirtualDeployDevice.asDdmlibDevice()` 为单例。

---

## 5. 前置流程（overlay 三路一致 — 禁止「只对齐 mock」）

「三路一致」指：

1. `deployHistoryManager.lastDeployOverlayIds[pkg]`
2. `IJuggDeploymentService.loadCachedOverlayId` / `loadEntry` 中 overlay sha
3. virtual 设备 `code_cache/.overlay/id`

### 5.1 DF-L2-001 前置（MATCHED）

通过 **fixture API** 顺序写入（真实 manager + virtual device，**不用** `whenever(lastDeployOverlayIds)` 偷懒）：

1. 用 `CommonsKt.context` 的 `apkInfos` 得到 apk 路径（§6）。
2. 调用 **`JuggDeploymentService.storeEntry`**（或注入的 `IJuggDeploymentService`）写入非 base overlay entry（与 demo 产物一致）。
3. 调用 **`deployHistoryManager.updateHistoryOnAfterDeployed`** 或项目内等价 API，使 history overlay id 与 cache 相同。
4. `virtualDevice.writeOverlayId(expectedId)` 写入 FS。
5. `IIdeDeployStateHelper` 返回使 `JuggDeployState.isReadyDeploy == false` 且 `isReadyIncCompile == true`（app 不可 deploy 但可 recover）。

### 5.2 DF-L2-002 前置（MISMATCHED → install → 再 MATCHED）

1. 先完成与 001 类似的 **history + 本地 cache** seed（历史产物 overlay id = `H`）。
2. **故意** 只让设备 FS 为 `D`（`D != H`）或缺失 id → `checkRecover` → MISMATCHED。
3. install（mock）清空 `code_cache` 后，**前置流程** 再写入与 `H` 一致的 id + 必要时重新 `storeEntry`（本地 cache 不因 install 自动改设备）。
4. recover 轮询 `waitingForDeployable`：由 **`IIdeDeployStateHelper`** 在 install 完成后短暂返回 OK，使 `isReadyDeploy` 为 true **仅用于通过等待**；**incremental 前** 再切回 `isReadyDeploy == false`（否则 `canTry` 为 false，走 Apply Changes）。

> 注：第 4 步由 `DeployFlowIdeDeployStateHelper` 在 mock install 后 **一次性** 返回 `IdeDeployState.ok`，满足 `waitingForDeployable` 后自动回到不可 deploy。

---

## 6. APK 与 parseApks

- 来源：`com.sickworm.intellij.jugg.mock.context`（`idea/src/test/.../Commons.kt`，`getContext` 即 `context` 属性）。
- 使用 `context.apkInfos` 中 **已 assemble 的 debug apk 路径** 填入 `JuggDeployData.apks`。
- 依赖：`TestGlobal.init()` + `AssembleAndroidProjectOnce`（与存量 idea 测试一致）。

---

## 7. installPathProvider

- `JuggDeployerHelper` 构造注入 **`installPathProvider: Computable<String>`**（已存在）。
- 测试注入指向 **含 deploy installer 的目录** 或 **minimal stub**；若 `getInstaller` 仍触达 native，可 **测试侧 static mock `AsDeployerCompat.getInstaller`** 返回 no-op（与 install mock 配套）。
- **优先** 与 `MockJugg` 一致：`AndroidProfilerDownloader.makeSureComponentIsInPlace()` 在 `@BeforeClass`（重但稳）；若 CI 过慢再改为 stub + static mock。

---

## 8. 断言清单（001 / 002 同用例内）

| 断言 | 手段 |
|------|------|
| `deploy().isSuccess` | JUnit |
| 未调用 `AsDeployerCompat.optimisticSwap` | **Mockito static mock** |
| direct write 发生 | virtual 存在 `__JUGG_DIRECT_OVERLAY__` / push 记录；overlay id 文件更新 |
| `isDeviceReadyDeploy == false`（incremental runTask） | 解析 `LaunchContext` 或集成日志；或 IIdeDeployState 最终阶段 |
| 002：recover + install + 第二次 incremental | `resetAfterReinstall()` verify；install/direct write **之前** `overlayStateProbes` 含 `mismatched-device-overlay`（卡住 `checkRecover`→MISMATCHED 路径） |
| `storeEntry` 新 overlay id | 真实 `IJuggDeploymentService` 读 memory/db（本地） |

---

## 9. 测试代码落点

```text
idea/src/test/.../deploy/run/deployflow/
  VirtualDeployDevice.kt
  DeployFlowMockBackend.kt          # 改为组装 Virtual + 前置 API
  DeployFlowTestSupport.kt
JuggDeployerHelperDeployFlowTest.kt # 仍 2 个 @Test，删除 @Ignore real
```

删除或废弃：`RecordingDeployRunTaskExecutor`、`DeployFlowRealDeviceBackend`、`resolveDeployFlowDeviceBackend()`。

---

## 10. 实现顺序

1. 生产接缝：`LaunchContext.deviceAdb`、`JuggDeployer`/`Task` `IJuggDeploymentService`、`executeDeployRunTask` 全面 `deviceAdbFactory`。
2. `VirtualDeployDevice` + 窄解释器 + `asIDeviceAdb` / `asDdmlibDevice`。
3. Fixture 前置 API + static mock（install / optimisticSwap）+ `IIdeDeployStateHelper` 分阶段。
4. **DF-L2-001** 跑绿后 **DF-L2-002**。
5. 更新 `06_testing.md` §7.1；本契约与 `jugg_deployer_helper_deploy_flow_test_plan.md` §3/§5.1 保持一致。

---

## 11. 与 L1 / L3 关系

| 层级 | 职责 |
|------|------|
| **L1** `DirectOverlaySwapTransportTest` | 算法/脚本细节回归；不重复 |
| **L2 Virtual Device**（本契约） | Helper → JuggDeployer direct 分支 |
| **L3** `TopLevelFlowTest` | 真机 + demo 全链路 |
