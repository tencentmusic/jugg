# Install 路径 ADB offline wait + retry 实现方案

> 背景：模拟器在 recover 全量装 APK 时出现亚秒级 `device offline` → `device not found`；`adb install` 手工可成功。  
> shell/swap 路径已有 `AdbTransientOffline.waitForAdbTransport`（5s + 重试 1 次），**install 路径缺失**。  
> 关联日志：`JOOX_Android_2/build/jugg/log/compile_latest.log`（`Installation Failure: device offline`；`dumpsys activity recents` 有 wait 但仍失败）。

---

## 1. 目标与非目标

### 目标

| # | 行为 |
|---|------|
| G1 | `JuggDeployer.install` 在 transient offline / not found 时：**wait transport（≤5s）→ 重试 install 1 次** |
| G2 | 与现有 swap 路径语义一致：只重试**当前 install**，不扩散为整轮 recover/fallback |
| G3 | 识别 deployer 日志里的 `Installation Failure: device offline`（`realErrorMessage` 不为 null） |
| G4 | **保留**现有 `not found` + 非 FULL 时切 `InstallMode.FULL` 的行为，合并进统一 retry 逻辑 |

### 非目标（后续迭代）

- `adb install` CLI fallback（见 §7）
- recover 默认跳过 DELTA（见 §7）
- 模拟器 serial 加长 wait 窗口（见 §7）
- 修改 `IdeaDeviceAdb` shell 侧 wait 逻辑

---

## 2. 根因与缺口（代码定位）

```
JuggDeployTask.perform(INSTALL)
  → JuggDeployer.install()
       → asDeployerCompat.install()   // ApkInstaller + ddmlib，无 offline wait
       catch: 仅 e.message contains "not found" 且非 FULL 时 sleep 2s 重试
       不识别 offline / realErrorMessage
```

对比已有能力：

| 能力 | swap / verifyCache | install |
|------|-------------------|---------|
| `runWithOfflineRetry` | ✅ | ❌ |
| `AdbTransientOffline.isOffline` | ✅ | ❌ |
| `waitForAdbTransport` | ✅ | ❌ |

`AdbLogWrapper.parseInstallFailureReason` 只从 `Caused by: java.io.IOException:` 等行提取原因，**无法**解析单行 `Installation Failure: device offline`，导致 `realErrorMessage == null`，外层 catch 无法做语义化 retry。

---

## 3. 设计

### 3.1 统一 transient 判定

在 `JuggDeployer` companion 新增（或抽到 `AdbTransientOffline` 若需 L1 单测）：

```kotlin
internal fun isTransientInstallFailure(e: Throwable, logger: AdbLogWrapper): Boolean {
    if (AdbTransientOffline.isOffline(e)) return true
    logger.realErrorMessage?.let { if (AdbTransientOffline.isOfflineMessage(it)) return true }
    val msg = e.message ?: return false
    if (msg.contains("not found", ignoreCase = true)) return true
    return AdbTransientOffline.isOfflineMessage(msg)
}
```

### 3.2 Install 专用 retry 包装

复用现有 `waitAdbTransportReady` + `runWithOfflineRetry` 结构，但 install 需额外处理 **installMode 升级**：

```kotlin
private fun invokeInstallWithTransientRetry(
    packageName: String,
    apks: List<String>,
    options: InstallOptions,
    initialMode: InstallMode,
): Boolean {
    var installMode = initialMode
    var attempt = 0
    while (true) {
        try {
            return asDeployerCompat.install(
                adb, service, installer, logger,
                packageName, apks, options, installMode,
            )
        } catch (e: Exception) {
            if (attempt > 0 || !isTransientInstallFailure(e, logger)) {
                throw e
            }
            // 保留旧行为：not found 且非 FULL → 下次用 FULL
            if (shouldEscalateToFullInstall(e, logger, installMode)) {
                installMode = InstallMode.FULL
                logger.warning("Transient install failure, retry with FULL install mode after transport ready.")
            } else {
                logger.warning("Transient install failure during install, wait for ADB transport.")
            }
            if (!waitAdbTransportReady("install", adb, logger)) {
                throw AdbTransientOffline.toException("install", e)
            }
            attempt++
        }
    }
}
```

要点：

- **最多 2 次** `asDeployerCompat.install`（与 shell / swap 一致）
- wait 发生在**第一次失败之后、第二次 install 之前**（替代旧逻辑里固定 `Thread.sleep(2000)`）
- `shouldEscalateToFullInstall`：`installMode != FULL` 且（`not found` in message 或 realErrorMessage）

`install()` 主流程简化为：

```kotlin
result.skippedInstall = !invokeInstallWithTransientRetry(packageName, apks, options, installMode)
// 后续 parseApks / storeEntry 不变
```

### 3.3 AdbLogWrapper 补强

`parseInstallFailureReason` 在现有 IOException 行解析之后，增加对 **Installation Failure 首行** 的提取：

```kotlin
// 例：Installation Failure: device offline
// 例：Installation Failure: 'package install-create ...' returns error '...'
if (message.contains("Installation Failure:")) {
    val tail = message.substringAfter("Installation Failure:").trim()
    if (AdbTransientOffline.isOfflineMessage(tail)) return tail
    // 保留原有 lineSequence + IOException 前缀逻辑
}
```

这样 deployer verbose 打出 offline 时，`isTransientInstallFailure` 在 catch 里能读到 `realErrorMessage`。

### 3.4 日志

与 shell 对齐，wait 时已有：

```
Device {serial} went offline during install, wait up to 5000ms.
```

通过 `waitAdbTransportReady(..., phase = "install", ...)` 产出。

### 3.5 时序（期望）

```
install #1 → offline
  → isTransientInstallFailure = true
  → waitForAdbTransport (poll shell true, ≤5s)
  → install #2 → success
  → storeEntry
```

若 #1 后 5s 内 transport 未恢复 → `AdbTransientOfflineException`，由上层 `JuggDeployerHelper` 现有 retry/recover 处理（不扩大 scope）。

---

## 4. 改动文件

| 文件 | 变更 |
|------|------|
| `idea/.../applychanges/JuggDeployer.kt` | 新增 `invokeInstallWithTransientRetry` / `isTransientInstallFailure`；`install()` 调用之；删除旧 `not found` 专用 catch |
| `idea/.../utils/AdbLogWrapper.kt` | `parseInstallFailureReason` 识别 `device offline` 等 Installation Failure 短消息 |
| `idea/.../test/.../AdbLogWrapperTest.kt` | L1：offline install failure 解析 |
| `idea/.../test/.../JuggDeployerInstallTest.kt` | L2：新建，mock `IAsDeployerCompat` + `AdbClient` |

**不改**：`JuggDeployTask`、`AsDeployerCompat`、deploy_compat 各版本实现。

---

## 5. TDD 与测试落点

按 `06_testing.md`：

| 层级 | 测试类 | 用例 |
|------|--------|------|
| **L1** | `AdbLogWrapperTest` | `Installation Failure: device offline` → `realErrorMessage == "device offline"` |
| **L2** | `JuggDeployerInstallTest`（新建） | 见下表 |
| **L2 回归** | `JuggDeployerHelperDeployFlowTest` | 现有 DF 用例全过即可，不强制新增 DF 场景 |
| **L3** | 不强制 | 本改动为 deployer 单点增强；若后续改 recover 顺序再补 Flow |

### JuggDeployerInstallTest 用例清单

1. **`install retries once after offline exception and succeeds`**  
   - mock `install`：第 1 次抛 `IOException(AdbCommandRejectedException: device offline)`，第 2 次返回 `true`  
   - mock `AdbClient.shell("true")`：第 1 次失败、第 2 次成功（或直接用真实 `waitUntilReady` 短超时 + 可控 probe——**禁止**为测试注入 lambda 到生产代码；通过 mock `AdbClient` 行为驱动 wait）

2. **`install fails when transport never recovers`**  
   - mock `install` 始终 offline；mock shell 始终失败 → 期望 `AdbTransientOfflineException`

3. **`not found on DELTA escalates to FULL then retries`**  
   - 第 1 次：`not found` + `InstallMode.DELTA`；第 2 次：断言 `installMode == FULL` 且成功

4. **`realErrorMessage offline triggers retry`**  
   - 第 1 次 install 内通过 `AdbLogWrapper.verbose("Installation Failure: device offline")` 后抛 generic Exception；第 2 次成功

### 测试构造要点

- `JuggDeployer` 已支持 `asDeployerCompat: IAsDeployerCompat` 构造注入 → 用 Mockito mock 接口  
- `AdbClient`：mock `serial`、`shell`；`parseApks` delegate 到 fake 或 mock 返回最小 `Apk` 列表  
- `IJuggDeployerDeploymentService`：mock `storeEntry`，成功路径断言调用 1 次  
- **禁止**新增 test-only provider/lambda 参数到生产代码

### 运行命令

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.AdbLogWrapperTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployerInstallTest"
./gradlew :idea:compileKotlin
```

---

## 6. 实现步骤（TDD 顺序）

1. **L1** 写 `AdbLogWrapperTest` 失败用例 → 改 `AdbLogWrapper` → 通过  
2. **L2** 写 `JuggDeployerInstallTest` 失败用例（用例 1、3、4）  
3. 实现 `isTransientInstallFailure` + `invokeInstallWithTransientRetry`，重构 `install()`  
4. 补用例 2，跑定向测试全绿  
5. 跑 `JuggDeployerHelperDeployFlowTest` 相关 `--tests` 回归  
6. 更新 `03_deploy_core.md` §5 一句：install 路径与 shell/swap 同样 wait + retry

---

## 7. 后续可选增强（本方案不实现）

| 增强 | 触发条件 | 说明 |
|------|----------|------|
| CLI install fallback | wait+retry 仍失败且为 offline | `adb -s $SERIAL install -r -t $APK`，成功后仍 `storeEntry` |
| recover 跳过 DELTA | `DeployStateRecover` / reinstall 分支 | 减少 installer SocketChannel，降低 offline 概率 |
| emulator 加长 wait | `serial.startsWith("emulator-")` | 10–15s；需单独评估避免拖慢真机 |

---

## 8. 风险与边界

- **误判 retry**：仅 `isTransientInstallFailure` 为 true 时 retry；storage 不足等 IOException **不在** `isOfflineMessage` 内，不会 retry  
- **双次 FULL install**：escalate 后第二次仍失败则直接抛出，不无限循环  
- **与 shell 并发**：install wait 期间其他线程仍可能调 shell；与现网行为一致，不在本方案解决  
- **UndeclaredThrowableException**：依赖 `AdbTransientOffline.isOffline` 遍历 cause chain；若只有 verbose 日志无 exception offline，依赖 `realErrorMessage`（§3.3）

---

## 9. 验收标准

- [ ] 模拟器 recover 全量装时，日志出现 `went offline during install, wait up to 5000ms` 且第二次 install 成功（或明确抛出 transient exception，不再 silent `realErrorMessage=null`）  
- [ ] 定向 L1/L2 测试通过  
- [ ] 真机路径无行为回归（L2 mock 覆盖 + 可选本地 smoke）
