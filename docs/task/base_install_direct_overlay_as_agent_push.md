# Base Install Direct Overlay + AS Startup Agent 推送方案

> 状态：调研 / 待实现  
> 关联：`DirectOverlaySwapTransport`、`JuggDeployer.tryDirectOverlaySwap`、`03_deploy_core.md` §4

---

## 1. 背景与目标

### 1.1 现状

Direct Overlay Writer 在 `JuggDeployer.tryDirectOverlaySwap` 与 `DirectOverlaySwapTransport.trySwapInternal` 两处对 **base install** 硬跳过：

- deployment cache 中 `overlayId.isBaseInstall == true` → 直接 `return null`
- 注释意图：base install 必须先走 legacy `verifyCache()`（APK dump 校验）+ AS `optimisticSwap`

典型场景（JOOX recover）：

1. overlay 三路不一致 → reinstall → cache 写入 base install overlay id
2. recover 后立即 HOT_FIX deploy
3. direct overlay 被 base install 门禁挡住 → 走 AS `overlayinstall`（含 `verifyCache` dump + install server）

### 1.2 目标

**支持 base install 场景走 Direct Overlay Writer**，前提：

- 设备上 **尚未存在 Android Studio Apply Changes 的 startup JVMTI agent**（`startup_agents/` 下非 Jugg 的 `.so`）
- 在 direct write **之前**，主动完成 AS agent 推送（与 Apply Changes `SetUpAgent` 等价）
- 不依赖 app 进程在线（与 direct overlay 的 `isDeviceReadyDeploy=false` 场景一致）

### 1.3 非目标

- 不替代 Jugg 自有 `jugg_jvmti_agent`（compat 部署仍走现有 `JuggJvmtiAgentManagerHelper` 后置 push）
- 不在此方案中实现 hotswap / `attach-agent`（仅 startup agent 文件落盘）
- 不修改 AOSP `deploy` 仓库新增 installer 命令（可作为后续优化）

---

## 2. AS Apply Changes Agent 机制（`deploy`）

### 2.1 Agent 是什么

仓库内 **没有** `applychanges_jvmti_agent` 符号；实际二进制为 installer matryoshka 内嵌的：

| 文件 | 用途 |
|------|------|
| `agent.so` | 64-bit app / 64-bit host |
| `agent-alt.so` | 32-bit app on 64-bit device |

设备路径（R+ optimistic）：

```
/data/data/<pkg>/code_cache/startup_agents/{installerVersion}-agent.so
/data/data/<pkg>/code_cache/startup_agents/{installerVersion}-agent-alt.so
```

Jugg 侧检测逻辑（已存在）：

```kotlin
// JuggJvmtiAgentManagerHelper.isNeedPushAfterDeploy
agents.any { !it.contains("jugg_jvmti_agent") && it.endsWith(".so") }
```

列表中典型 AS agent 文件名形如 `{hash}-agent.so`（非 Jugg 前缀）。

### 2.2 推送调用链（R+ overlayinstall）

```
Deployer.install() / OptimisticApkInstaller
  └─ AdbInstaller.overlayInstall(OverlayInstallRequest)
       └─ adb shell → /data/local/tmp/.studio/bin/installer
            └─ OverlayInstallCommand::Run()
                 ├─ ExtractBinaries(tmp, {agent.so, install_server})
                 ├─ SetUpAgent()                    ← ★ agent push
                 ├─ UpdateOverlay()                 ← install server 写 overlay
                 └─ GetAgentLogs()
```

`SetUpAgent` 核心（`installer/overlay_install.cc`）：

1. `InstallClient::CheckSetup` 检查 `startup_agents/`、`.studio/`、versioned agent 是否存在
2. `run-as mkdir startup_agents/`（必要时清理旧 agent）
3. `run-as cp -F /data/local/tmp/.studio/tmp/{ver}/agent.so → startup_agents/{ver}-agent.so`

**不调用 `cmd activity attach-agent`**，不要求 app PID。

### 2.3 Install Server 角色

`CheckSetup` / `UpdateOverlay` 经 `InstallClient::Send()` 与 app 内 `install_server-{version}` 通信。

`InstallClient::Send` 失败时会自动：`StartServer()` → `CopyServer()` → 再 `StartServer()`。

Server 通过 `run-as` `ForkAndExec` 启动，**不要求 debuggable app 进程已 attach**（与 hotswap 的 `overlaySwap` + PID 不同）。

### 2.4 是否存在 “仅 push agent” 公开 API？

**没有**独立命令。最接近的路径：

| 路径 | Agent | Overlay | App 在线 |
|------|-------|---------|----------|
| `overlayinstall` 的 `SetUpAgent` | ✅ | 随后 `UpdateOverlay` 必跑 | ❌ |
| `installCoroutineAgent` | ✅（另一 agent） | ❌ | ❌ |
| `overlayswap` 的 `PrepareAndBuildRequest` | ✅ | ✅ + attach | PID 为空时可只 push |

Jugg 需要的是 **`SetUpAgent` 等价能力**，且最好 **不与 install server 写 overlay 冲突**（因 overlay 由 DirectOverlayWriter 负责）。

---

## 3. Jugg 现状与缺口

### 3.1 Direct Overlay 门禁

| 层级 | base install 行为 |
|------|-------------------|
| `JuggDeployer.tryDirectOverlaySwap` | `isBaseInstall` → return null（transport 未调用） |
| `DirectOverlaySwapTransport.trySwapInternal` | 再次检查 `isBaseInstall` → skip |
| `DirectOverlayStateChecker.checkDevice` | base install 期望 device overlay id 为 `""`；`NO_DIR` → **MATCHED**（已支持） |

文档 `03_deploy_core.md` 写 “startup agent 尚未准备 → fallback Apply Changes”，但 **生产代码未实现** startup agent 检查（仅有 unused import + L1 测试）。

### 3.2 Agent 推送时序

`JuggDeployerHelper.runTask`：

```
[async] isNeedPushAgentAfterDeploy
deploy_to_device (direct 或 AS optimisticSwap)
await → pushAgentToApps()   // 仅 Jugg agent，且在 overlay 写入之后
restart / JVMTI 检测
```

Direct overlay 成功 → **跳过 AS `optimisticSwap`** → **AS agent 不会被 push**。

`JuggJvmtiAgentManagerHelper` 能检测 “缺少 AS agent”，但 push 的是 **Jugg agent**，不能替代 AS startup agent。

### 3.3 已有可复用组件

| 组件 | 可复用点 |
|------|----------|
| `OptimisticApkUpdater.pushOverlays` | 已封装 `installer.overlayInstall` + `OverlayInstallRequest` 构建 |
| `JuggDeployTask` | 已创建 `AdbInstaller` / `JuggDeployer` |
| `JuggJvmtiAgentManager.getCurrentAgentsInApp` | 列举 `startup_agents/` |
| `DirectOverlayWriteRequestBuilder` | 计算目标 `overlayId` 与文件列表 |

---

## 4. AS Agent 从哪里来（获取路径调研）

### 4.1 结论：Jugg **已经间接拥有** agent，但没有单独的文件

Apply Changes JVMTI agent **不是**独立下载物，也 **不是** `applychanges_jvmti_agent.so` 这种独立命名（测试 mock 里的名字是约定俗成，非 AOSP 符号）。

真实链路：

```
android-libswap.so（AOSP agent/native）
  → BUILD 打包进 matryoshka， doll 名 agent.so / agent-alt.so
  → 嵌在 AS 自带的 deploy installer 可执行文件里
  → SetUpAgent / install_coroutine_agent 在设备上 ExtractBinaries 解出
  → run-as cp 到 code_cache/startup_agents/{Version.hash()}-agent.so
```

当前 AS Panda（本机验证）：

| 项 | 值 |
|----|-----|
| installer 路径 | `{AS}/plugins/android/resources/installer/{abi}/installer` |
| Jugg 解析入口 | `CopyEmbeddedDistributionPaths.get()`（与 `EmbeddedDistributionPaths.findEmbeddedInstaller` 等价） |
| `Version.hash()` | `dced2491`（`libjava_version.jar` 内 `com.android.tools.deployer.Version`） |
| 设备 agent 文件名 | `dced2491-agent.so` 或 `dced2491-agent-alt.so` |
| matryoshka doll 名 | `agent.so` / `agent-alt.so`（`strings installer` 可见） |

Jugg **不需要**向 AS 再要一份新 binary；需要的是 **从已有 installer 里把 doll 解出来**，再 `run-as cp` 到 `startup_agents/`。

### 4.2 为何 `overlayInstall` / `pushOverlays` 不能当「获取 + 推送」手段

与 §3 实践一致：`OptimisticApkUpdater.pushOverlays` → 完整 `overlayinstall`，离线易失败。

native 命令表（`installer/command.cc`）里 **没有** “只 push Apply Changes startup agent” 的命令；最接近的离线范例是 `installcoroutineagent`，但解出的是 **协程调试 agent**，不是 `agent.so`。

| 命令 | 解出 agent.so | 写 startup_agents | 依赖 install server |
|------|---------------|-------------------|---------------------|
| `overlayinstall` | ✅ | ✅（SetUpAgent） | ✅（CheckSetup + UpdateOverlay） |
| `installcoroutineagent` | ❌（coroutine agent） | ❌（写 code_cache 根） | ❌ |
| （不存在）`installstartupagent` | — | — | — |

因此 **获取 agent 与推送 agent 应拆开**：获取走 matryoshka 解压；推送走纯 `run-as`（与 `DirectOverlayWriter` / `JuggJvmtiAgentManager` 同层）。

### 4.3 可行获取方案（按推荐顺序）

#### 方案 1（推荐）：Host 从 installer 解 matryoshka → adb push → run-as cp

**输入**：`CopyEmbeddedDistributionPaths.get() + "/{deviceAbi}/installer"`  
**输出**：`agent.so` / `agent-alt.so` 字节（按 `Deploy.Arch` 选择）

步骤：

1. 按设备 ABI 选 installer（与 `AdbInstaller.prepare()` 相同规则：`arm64-v8a` / `armeabi-v7a` / …）
2. Host 侧解析 matryoshka，按 doll 名提取 `agent.so` 或 `agent-alt.so`
3. `adb push` 到 `/data/local/tmp/jugg/as-agent/{Version.hash()}/agent.so`（路径可自定）
4. `run-as <pkg> mkdir -p code_cache/startup_agents`
5. `run-as cp -F …/agent.so code_cache/startup_agents/{Version.hash()}-agent.so`

**优点**：完全离线；不启 install server；与 direct overlay 一致。  
**实现点**：新增 L1 `InstallerMatryoshkaReader`（Kotlin 解析 doll，或 dev 环境 shell 调 AOSP `matryoshka` CLI）。

版本号必须与 `AdbInstaller.getVersion()` / `Version.hash()` 一致，否则 AS 后续 overlay 链路会 `ERROR_WRONG_VERSION`。

#### 方案 2：Device 上让 installer 解 doll（不跑 overlayinstall）

installer 已在 `AdbInstaller.prepare()` 推到 `/data/local/tmp/.studio/bin/installer`。  
native `ExtractBinaries` 从 **正在执行的 installer 自身** matryoshka 解文件到 `/data/local/tmp/.studio/tmp/{version}/`。

但 shipped installer **仅**在已有命令里调用 ExtractBinaries；没有「只解 agent.so」的公开 Java API（`installCoroutineAgent` 是另一 doll）。

可选子路径：

- **2a** Fork AOSP 增加 `installstartupagent` 命令（长期，需随 AS 升级维护）  
- **2b** 通过 `AdbInstaller` 发 protobuf 调 `installcoroutineagent` — **不可用**（错误 doll）

**结论**：除非改 native installer，device-only 解 `agent.so` 仍要自己在 host 解 matryoshka，或接受 overlayinstall 的 install server 依赖（已否决）。

#### 方案 3（不推荐）：继续用 `overlayInstall` 顺带 SetUpAgent

见 §4.2；仅作 app 在线时的 fallback，不作为 base install offline 主路径。

### 4.4 与 `JuggJvmtiAgentManager` 的对比

| | AS Apply Changes agent | Jugg compat agent |
|--|------------------------|-------------------|
| 来源 | AS installer matryoshka / `agent.so` | Jugg 插件 bundle `jvmti_agent` |
| Host 是否已有文件 | ✅（installer 目录） | ✅（resources） |
| 推送方式 | 待实现：host 解压 + run-as | 已实现：push bundle + setup script + run-as |
| 落盘 | `startup_agents/{hash}-agent.so` | `startup_agents/{ver}-jugg_jvmti_agent.so` |

**可复用模式**：仿 `JuggJvmtiAgentManager.pushAgentToApp`，把 bundle 换成 host 解出的 `agent.so` bytes。

---

## 5. 方案对比（推送层，不含获取）

### 方案 A：`overlayInstall` — **已否决**（离线不可靠）

见 §4.2 与历史 `pushOverlays` 实践。

### 方案 B：Host matryoshka 解压 + run-as cp — **推荐**

见 §4.3 方案 1。获取与推送均在 Jugg 控制下，不依赖 install server。

### 方案 C：首次 base 仍走 AS `optimisticSwap` — fallback only

app 在线或 B 失败时回退。

---

## 6. 推荐实现设计（方案 B）

### 6.1 新组件

```
idea/.../deploy/direct/InstallerMatryoshkaReader.kt   // L1：从 installer 解 agent.so
idea/.../deploy/direct/AsStartupAgentPusher.kt      // run-as 推送到 startup_agents/
```

```kotlin
/** Reads embedded dolls from AS deploy installer on host. */
class InstallerMatryoshkaReader(
    private val installersRoot: String, // CopyEmbeddedDistributionPaths.get()
) {
    fun extractAgentSo(deviceAbi: String, arch: Deploy.Arch): ByteArray
}

/** Pushes AS Apply Changes startup agent before direct overlay (offline-safe). */
class AsStartupAgentPusher(
    private val adb: IDeviceAdb,
    private val matryoshkaReader: InstallerMatryoshkaReader,
    private val versionHash: String, // AdbInstaller.getVersion() / Version.hash()
    private val logger: Logger,
) {
    fun hasApplyChangesStartupAgent(packageName: String): Boolean
    fun pushApplyChangesStartupAgent(packageName: String, arch: Deploy.Arch, deviceAbi: String): Boolean
}
```

`hasApplyChangesStartupAgent`：复用 `JuggJvmtiAgentManager.getCurrentAgentsInApp` + 与 `JuggJvmtiAgentManagerHelper` 相同判定（非 jugg 前缀的 `.so`）。

`pushApplyChangesStartupAgent`：

1. `matryoshkaReader.extractAgentSo(deviceAbi, arch)`
2. push 到 device tmp
3. `run-as cp` → `code_cache/startup_agents/{versionHash}-agent.so`（32-bit-on-64 用 `-agent-alt.so`）

### 6.2 `DirectOverlaySwapOptions` 扩展

```kotlin
data class DirectOverlaySwapOptions(
    val enabled: Boolean,
    val isDeviceReadyDeploy: Boolean,
    val adb: IDeviceAdb?,
    val installersRoot: String? = null,   // CopyEmbeddedDistributionPaths.get()
    val installerVersion: String? = null, // AdbInstaller.getVersion()
    val deviceAbi: String? = null,
    val arch: Deploy.Arch? = null,
)
```

由 `JuggDeployTask` 注入（installer 创建处已有 `installPathProvider` / `adb.getAbis()` / `arch`）。

### 6.3 `DirectOverlaySwapTransport.trySwapInternal` 新流程

```
1. canTry (unchanged)
2. adb null → skip
3. [NEW] 若缺少 AS startup agent:
       AsStartupAgentPusher.push(...)
       失败 → log + return null (fallback AS)
4. DirectOverlayStateChecker.checkDevice (base: expected "")
5. DirectOverlayWriter.write
6. return overlay id
```

**移除** `JuggDeployer.tryDirectOverlaySwap` 中对 `isBaseInstall` 的 early return。

### 6.4 与 `verifyCache` / APK dump 的关系

Base install 走 AS fallback 时会 `ApplicationDumper.dump` 校验 APK checksum。

Direct overlay **不写 APK overlay**，只写 `code_cache/.overlay`；首次 base direct 成功后会更新 deployment cache 为非 base id。

**风险**：跳过 APK dump 可能在 “设备 APK 与 cache 不一致但 overlay 目录为空” 时漏检。  
**缓解**：

- recover/reinstall 路径刚完成 install，`storeEntry` 已同步
- 可选：base install direct 前增加轻量 `pm path` + base.apk checksum 比对（不启动 install server dump）

建议在实现阶段加 **L2 deploy flow case**：reinstall → base cache → direct overlay 成功且 `startup_agents` 含 AS agent。

### 6.5 App 是否必须在线？

| 步骤 | 需要 app 进程？ |
|------|----------------|
| Host matryoshka 解压 | **否** |
| `adb push` + `run-as cp` | **否**（需 APK debuggable，不需进程） |
| `overlayInstall` | 不可靠离线（已否决） |
| `attach-agent` / hotswap | **是**（本方案不涉及） |

结论：**direct overlay + 前置 agent push 与 `isDeviceReadyDeploy=false` / `pids=[]` 兼容**。

### 6.6 与 Jugg JVMTI agent 的协作

| Agent | 时机 | 条件 |
|-------|------|------|
| AS `{ver}-agent.so` | direct write **前** | base install 或缺少 AS agent |
| Jugg `{ver}-jugg_jvmti_agent.so` | deploy **后** | `finalIsEnableCompatibleDeploymentMode` + `isNeedPushAgentAfterDeploy` |

两者共存于 `startup_agents/`。AS agent 为 Apply Changes / install server 链路所需；Jugg agent 为 compat 探测所需。

---

## 7. 测试计划（TDD）

| 层级 | 文件 | 场景 |
|------|------|------|
| L1 | `InstallerMatryoshkaReaderTest` | 对 AS 自带 `arm64-v8a/installer` 解出 `agent.so`；doll 大小 > 0 |
| L1 | `AsStartupAgentPusherTest` | mock adb；push 后 `startup_agents` 含 `{hash}-agent.so` |
| L1 | `DirectOverlaySwapTransportTest` | base install + mock pusher 成功 → direct write |
| L2 | `JuggDeployerHelperDeployFlowTest` | reinstall recover → base cache → direct 成功，无 `optimisticSwap` |

---

## 8. 实现步骤（建议顺序）

1. **Spike**：`InstallerMatryoshkaReader` 对 `CopyEmbeddedDistributionPaths` 路径解 doll，与设备上 `overlayinstall` 解出的 tmp agent 字节比对（可选）
2. L1 测试 + `InstallerMatryoshkaReader` 实现
3. `AsStartupAgentPusher` + L1 测试
4. 接入 `DirectOverlaySwapTransport` + 去掉 base install hard skip
5. L2 deploy flow + 更新 `03_deploy_core.md`

---

## 9. 待验证问题（Spike 清单）

- [ ] matryoshka doll 格式是否稳定跨 AS 小版本（需用 `Version.hash()` 对齐，不假设 doll layout 永不变）
- [ ] 32-bit app on 64-bit device 必须推 `agent-alt.so` 并重命名为 `{hash}-agent-alt.so`
- [ ] `run-as` 在 app 从未启动时是否可用（通常仅要求 debuggable APK + 已 install）

---

## 10. 参考代码路径

| 说明 | 路径 |
|------|------|
| matryoshka 打包 | `deploy/installer/BUILD` |
| ExtractBinaries | `deploy/installer/binary_extract.cc` |
| installCoroutineAgent（离线 push 范例） | `deploy/installer/install_coroutine_agent.cc` |
| Jugg installer 路径 | `idea/.../CopyEmbeddedDistributionPaths.kt` |
| Version.hash | `plugins/android/lib/libjava_version.jar` → `com.android.tools.deployer.Version` |
| AS installer 资源 | `{AS}/plugins/android/resources/installer/{abi}/installer` |
| Jugg compat agent 推送参考 | `main/.../JuggJvmtiAgentManager.kt` |
