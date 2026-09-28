# Jugg SO 热更新实现技术方案

> 创建：2026-09-17  
> 最后核对：2026-09-17  
> 状态：已实现（与当前代码对齐）  
> 性质：实现技术方案。产品决策、Freeline/Tinker 对齐口径与互斥/回退规则以调研稿为准；正文记录已落地的类、方法和验证。  
> 调研基线：Agent Store `bc-27622ac3-2dfb-42ad-ada4-96ac66275f8f` 的 SO 热更新调研稿（2026-09-17 修订）

---

## 0. 冻结决策（实现期不再重开）

| 决策 | 口径 |
|------|------|
| 用户可观察结果 | 仅 NativeLib 时砍掉 APK 重签和整包重装，保留进程重启 |
| 传输 | 两步：`adb push` → `/data/local/tmp/jugg/nativeLib/<session>/<abi>/` → `AppSandboxExecutor` 拷入 `code_cache/.jugg_native/<abi>/` |
| 生效 | 冷启动前把 ABI 目录前置进 `DexPathList`；**必须重启**；不是进程内 SO 热替换 |
| 注入宿主 | 默认走现有 Jugg JVMTI startup agent 的 Java hook，无 App SDK、不改应用 `build.gradle` |
| 互斥 | 同一次 NativeLib **只选** push-SO 或 `updateApk`，禁止双写 |
| 失败 | 任一步失败 → 整轮降级原 `updateApk → resign → reinstall`，禁止半成功 |
| ABI | 保留子目录，禁止 Freeline 扁平 basename |
| 删除 `.so` | MVP 不做设备侧删除协议 |
| 用户开关 | Control Panel Settings → Deployment「SO hot update」；持久化 `jugg.isEnableNativeSandboxDeploy`；**默认关闭** |
| 关闭时行为 | NativeLib 走 `updateApk → resign → reinstall`，不探测 sandbox、不写 `.jugg_native`；关闭后保留补丁 `.so`，只撤 `code_cache/.jugg_native/.enabled`，运行时不再注入 |

产品表述：**增量推送 so + ClassLoader native 路径劫持 + 冷启动生效**。不要对外写「SO 热替换 / 不重启」。

---

## 1. 失败证据与落地对照

### 1.1 已有失败证据

真实日志（调研 `so-deploy-latency-freeline-vs-jugg.md`）：

```text
Need resign APK to update files: [NativeLib:lib/arm64-v8a/libdtmp.so].
Resign APK file finished, cost 29171ms
Reinstalling app finished, cost 54186ms
JuggDeployData (HOT_FIX): [nothing to deploy]
Jugg HOT_FIX SUCCESSFUL in 89s.
App restarted
```

根因：`DeployDataGenerator` 把全部 `changedLibs` 写入 `updateApkFiles`，`JuggDeployerHelper` 见 `isNeedUpdateApk` 就改 APK、重签、重装。编译仅 3s。

### 1.2 已落地对照

| 实现前缺口 | 当前代码 |
|------|------|
| 无 push-SO 分流 | `NativeSandboxDeployPlanner` + `JuggDeployerHelper.tryDeliverNativeSandbox()` |
| 无 `.jugg_native` 落盘约定 | `NativeSandboxWriter`：staging `/data/local/tmp/jugg/nativeLib/<session>/<abi>/`，sandbox `code_cache/.jugg_native/<abi>/` |
| 默认 App sandbox 跳过 `repairCodeCache` | **未改** `AppSandboxExecutor` 短路；Writer 仍传 `repairCodeCache = true`，仅 Direct/root 模式会真正 repair |
| native-only 不注入 ClassLoader 路径 | startup hook 在 dex fix 之后调用 `NativeLibraryPathInstaller.install(base)` |
| install 不清理 `.jugg_native` | `JuggDeployer.install()` 同时 `rm -rf code_cache/.overlay code_cache/.jugg_native` |

---

## 2. 目标行为

### 2.1 快路径（本轮只有 NativeLib，且资格满足）

```text
CompileOutput.NativeLib
  -> JuggSettings.isEnableNativeSandboxDeploy（默认 false；Settings → Deployment「SO hot update」）
  -> device.version.apiLevel ≥ 26（Android 8.0 / VERSION_CODES.O）
  -> JuggDeployerHelper 决策门：尝试 push-SO
  -> adb push /data/local/tmp/jugg/nativeLib/<session>/<abi>/libxxx.so
  -> AppSandboxExecutor 拷入 code_cache/.jugg_native/<abi>/
  -> Direct/root sandbox 走现有 repairCodeCache；默认 App sandbox 仍跳过 repair
  -> 不改 APK、不重签、不重装
  -> nativeSandboxFiles 非空 → isNeedRestartApp
  -> 冷启动 hook：NativeLibraryPathInstaller.install(.jugg_native/<abi>)
  -> System.loadLibrary 命中补丁
```

### 2.2 直接走原路径（不尝试 A）

Planner `Skip` 或 Helper 在 Planner 之前直接返回，`updateApkFiles` 不动：

- `JuggSettings.isEnableNativeSandboxDeploy == false`（默认；调用 `tryDeliverNativeSandbox` 前 Skip，debug：`disabled by settings`）。关闭开关会置 `isNeedSyncNativeSandboxRuntime`，立刻或下次部署删除 `.jugg_native/.enabled`，不删补丁 `.so`
- `device.version.apiLevel < 26`（Android 8.0 / `VERSION_CODES.O` 以下；调用前 Skip，debug：`api < 26`；与 `NativeLibraryPathInstaller` 一致）
- 同轮 `updateApkFiles` 里还有 Manifest 等非 NativeLib 必须改包项
- sandbox `UNAVAILABLE`
- 目标 ABI 无法从 `lib/<abi>/` 路径解析，或本轮 NativeLib 没有目标 ABI 文件
- `isRetry == true`（Helper 在 `retryReason != null` 时整段不进 sandbox，沿用原 APK 更新语义）

路径不符合 `lib/<abi>/lib*.so` 的文件不会进入 `abiDirs`；若过滤后为空，Skip `no native libraries for target abi`。

### 2.3 尝试 A 失败后的降级

Writer `finally` 删除本轮 staging。copy / SELinux 失败时再 `rm -f` 本轮写入的 `.jugg_native` 文件（禁止 `rm -rf` 整个 ABI 目录）。push 尚未拷入 sandbox 时不动已有补丁。随后用**原始** `updateApkFiles` 走 `updateApk → resign → reinstall`。用户可见：

```text
SO sandbox deploy failed (<step>), fallback to APK update/resign/reinstall
```

禁止：staging 成功但私有目录失败却 SUCCESS；写入成功但跳过重启却声称已生效；A 部分成功后再改同一批 APK `lib/` 而不清补丁。

---

## 3. 数据与目录契约

### 3.1 `JuggDeployData`

不在 `DeployDataGenerator` 里提前看设备。生成器继续把 NativeLib 放进 `updateApkFiles`。

瞬态字段（不进部署历史、随 `filterForApks()` 裁剪）：

```kotlin
val nativeSandboxFiles: List<DeployItem> = emptyList()
```

快路径成功后：

```kotlin
copy(
    updateApkFiles = attempt.otherApkFiles, // empty
    nativeSandboxFiles = attempt.nativeFiles,
)
```

| 字段 | 变化 |
|------|------|
| `isNeedUpdateApk` | 仍只看 `updateApkFiles`。快路径成功后为 false |
| `isNeedRestartApp` | 增加 `nativeSandboxFiles.isNotEmpty()` |
| `deployType` | native-only 快路径为 `HOT_FIX` |
| `isEmpty` | **不**把 `nativeSandboxFiles` 算进 overlay 空载荷；native-only 可以 overlay 为空，但必须重启 |
| `hasDeployChanges` | Helper 把 `nativeSandboxFiles` 算作有变更 |
| `filterForApks()` | 同步过滤 `nativeSandboxFiles` |
| `toString()` | 打印 `native sandbox: [...]`；native-only 不再附加 `[nothing to deploy]` |

降级时返回原始 `deployData`，`nativeSandboxFiles` 保持空。

### 3.2 设备目录

```text
/data/local/tmp/jugg/nativeLib/<session>/<abi>/libxxx.so   # staging，本轮结束 best-effort 删除
{dataDir}/code_cache/.jugg_native/<abi>/libxxx.so          # 注入读取点，覆盖写
```

常量：`NativeSandboxWriter.STAGING_ROOT = "/data/local/tmp/jugg/nativeLib"`，`SANDBOX_DIR = "code_cache/.jugg_native"`。

- `<session>`：`System.currentTimeMillis().toString()`，只允许 `[A-Za-z0-9_-]+`。
- `<abi>`：从 `DeployItem.name` 解析，合法值 `arm64-v8a` / `armeabi-v7a` / `armeabi` / `x86` / `x86_64`。
- 目标 ABI：与 Apply Changes 共用 `AppAbiResolver` + `JuggDeployerHelper` 持有的 `AppAbiCache`。进程在跑时用进程位数；否则先读缓存，再按 Manifest `use32bitAbi`、APK ARM native、已安装包 `primaryCpuAbi`、设备主 ABI，最后 64 位缺省。
  - `ARCH_32_BIT` → `armeabi-v7a`，其次 `armeabi`，再次 `x86`
  - `ARCH_64_BIT` → `arm64-v8a`，其次 `x86_64`
- 只推目标 Arch 能消费的文件。本轮 NativeLib 全是另一 Arch → Skip，整批走 updateApk。
- 文件名必须 `lib*.so`，路径必须是 `lib/<abi>/<file>`。

### 3.3 注入目录

`findLibrary("foo")` 在目录里找 `libfoo.so`，**不要**把 `.jugg_native` 整棵树当作一个搜索根。注入的是 `.jugg_native/<abi>/`。

---

## 4. Host 实现

### 4.1 类型

| 类型 | 路径 | 职责 |
|------|------|------|
| `NativeSandboxDeployPlanner` | `main/.../deploy/nativesandbox/NativeSandboxDeployPlanner.kt` | 纯函数：从 `updateApkFiles` 拆出可尝试的 NativeLib；资格/ABI 过滤 |
| `NativeSandboxWriteRequest` | `main/.../deploy/nativesandbox/NativeSandboxWriteRequest.kt` | `packageName` / `sessionId` / `abiDirs` |
| `NativeSandboxWriter` | `main/.../deploy/nativesandbox/NativeSandboxWriter.kt` | 两步 push + sandbox 拷贝 + 成功标记；失败抛明确 step |
| `NativeSandboxDeployException` / `NativeSandboxDeployStep` | 同 Writer 文件 | `STAGING` / `PUSH` / `COPY` / `SELINUX` / `CLEANUP_PATCH` |

不新增单方法接口。Planner 无设备依赖，Writer 吃 `IDeviceAdb` + `AppSandboxExecutor`。

### 4.2 Planner 契约

```text
fun plan(updateApkFiles, arch, api, sandboxMode): NativeSandboxPlan
```

`arch` 是 `NativeSandboxDeployPlanner.Arch`（`BIT_32` / `BIT_64`），不是 `Deploy.Arch`。`MIN_API = 26`。

| 结果 | 含义 |
|------|------|
| `Skip(reason)` | 不尝试 A，原 `updateApkFiles` 不动 |
| `Attempt(nativeFiles, otherApkFiles, abiDirs)` | `otherApkFiles` 为空；`abiDirs` 至少一组非空 |

Skip 条件全部在 Planner 内写死。

### 4.3 Writer 序列

对齐 Direct Overlay：先 push 到 `/data/local/tmp/jugg`，再 `sandbox.execNoFallback`。成功协议：

```text
__JUGG_NATIVE_SANDBOX__ OK
```

步骤：

1. `mkdir -p /data/local/tmp/jugg/nativeLib/<session>/<abi>`；失败 → `STAGING`
2. 每个文件写本地 temp，再 `adb.push(local, stagingPath)`；失败 → `PUSH`
3. sandbox 脚本（`repairCodeCache = true`，见 §4.4）：
   - `mkdir -p code_cache/.jugg_native/<abi>`
   - `cp -f <staging> code_cache/.jugg_native/<abi>/<basename>`
   - `wc -c` 核对 size
   - `touch code_cache/.jugg_native/.enabled`
   - 打印 `__JUGG_NATIVE_SANDBOX__ OK`
4. 异常信息含 `repair code_cache` → `SELINUX`，其它 sandbox 失败 → `COPY`
5. finally：`rm -rf /data/local/tmp/jugg/nativeLib/<session>`（失败只 debug）

sandbox 读 `/data/local/tmp` 失败视为 `COPY`，整轮降级。

### 4.4 SELinux / sandbox 模式

| 模式 | 当前行为 |
|------|----------|
| `DIRECT_SHELL` / `ROOT_DIRECT` / `SU_ROOT` | `repairCodeCache=true` 生效，现有逻辑把 `code_cache/*.so` 标成 `u:object_r:apk_data_file:s0` |
| 默认 App sandbox | `AppSandboxExecutor` 仍跳过 repair。Writer 继续传 `repairCodeCache = true`，但不改 executor 短路 |

修复失败 → step `SELINUX` → 降级 B。

### 4.5 `JuggDeployerHelper.deployIncrementalChanges`

在 `isNeedUpdateApk && !isRetry` 里、resign 之前分流。`LaunchContext` 此时尚未创建，因此 **额外** `AppSandboxExecutor(adb, packageName)` 探测一次 mode（注释写明：必须在 resign 前拿到 sandbox）。

```text
if (deployData.isNeedUpdateApk && !isRetry) {
    if (canTryNativeSandbox(device)) {
        tryDeliverNativeSandbox(...)
    }
    if (!skipApkUpdate) {
        IncrementalDeployHelper.updateApk(...)
        isNeedReinstallApk = true
    }
}
```

`canTryNativeSandbox`：开关关闭或 `device.version.apiLevel < 26` 时不进入 `tryDeliverNativeSandbox`。`tryDeliverNativeSandbox`：无 NativeLib 或没有 applicationId → 不尝试。`plan` 为 Skip 时 debug 打 `SO sandbox deploy skipped: <reason>`。Attempt 成功日志：

```text
SO sandbox deploy succeeded, skip APK package/resign/reinstall: <files>
```

快路径成功：`skipApkUpdate = true`，`isNeedReinstallApk` 保持 false。native-only overlay 可为空，`isNeedRestartApp` 仍为 true。`hasDeployChanges` 把 `nativeSandboxFiles` 算作有变更。

agent：沿用现有 `isNeedPushAgentAfterDeploy`（看设备上是否已有 agent，不因 `isEmpty` 跳过）。native-only 且需要 push agent 时，现逻辑仍会因 `isNeedRestartApp` 重启。

### 4.6 重装清理

`JuggDeployer.install()`：

```text
rm -rf code_cache/.overlay code_cache/.jugg_native && echo success
```

覆盖默认 install 与自定义安装脚本成功后的 sandbox 清理。降级 B 在 copy/SELinux 失败时另走 `bestEffortRemovePatchFiles`。

### 4.7 与 overlay 同轮

dex/resource overlay 可与 push-SO 同轮，统一一次进程重启。NativeLib 与 APK 改写仍互斥：同轮有 Manifest 则 NativeLib 并入 APK，不写 `.jugg_native`。

---

## 5. Runtime 实现

### 5.1 为什么不在 `HandleStartupAgent` 里直接反射

`HandleStartupAgent` 发生时 Application 可能尚未 `attachBaseContext`。startup agent 已把 Java hook 打进：

- `Instrumentation.newApplication` → `handleNewApplicationEntry` / `handleNewApplicationEntry2`
- `Application.attachBaseContext` → `handleAttachBaseContextEntry`

无 SDK、冷启动早于业务 `loadLibrary`。不新增 JNI 入口。

### 5.2 `NativeLibraryPathInstaller`

路径：`jvmti_agent/.../hotfix/NativeLibraryPathInstaller.java`

API ≥ 26（低于 26 warn 后返回）。`context == null` warn 后返回。非 `BaseDexClassLoader` 写失败标记后返回。

1. 没有 `.jugg_native/.enabled` → info 后返回，不算失败，不删 `.so`。
2. 目录不存在或没有 `lib*.so` → return，不算失败。
3. 按 `Build.SUPPORTED_ABIS` 依次找第一个含 `lib*.so` 的子目录。
4. `nativeLibraryDirectories`：已在 `[0]` 则幂等跳过；否则去掉相同 canonical path，再 `add(0, folder)`。
5. 合并 `systemNativeLibraryDirectories`，`makePathElements(List)` 写回 `nativeLibraryPathElements`。
6. 成功 info：`native library path installed: <folder>`。
7. 反射失败：warn + 写 `code_cache/.jugg_native_inject_failed`。Host **本轮不读**该标记。

只覆盖 `findLibrary` / `System.loadLibrary`，不覆盖绝对路径 `dlopen`。

### 5.3 调用点与顺序

三个 startup hook：

```text
HotfixLoader.init(base)                    // native-only 也要拿到 codeCacheDir
if (isNeedFix) {
    installDex / install
}
NativeLibraryPathInstaller.install(base)   // 在可能的 ClassLoader 替换之后
```

`jugg.inject.application.enable` 兼容路径：`HotfixLoader.install()` 末尾再调一次，幂等。

`AndroidNClassLoader.recreateDexPathList` **未改**。由 hook 顺序保证 installer 在 ClassLoader 替换之后执行。

### 5.4 Agent 版本

`build.gradle` 的 `agentVersion` 现为 `1.0.77`。instruments JAR / hook 变化必须 bump，否则设备继续用旧 hook。

---

## 6. 文件级改动清单

### 6.1 现有文件

| 文件 | 改动 |
|------|------|
| `main/.../deploy/run/JuggDeployData.kt` | `nativeSandboxFiles`；`isNeedRestartApp` / `filterForApks` / 日志 |
| `idea/.../deploy/run/JuggDeployerHelper.kt` | resign 前 Planner + Writer；ABI 走 `AppAbiResolver.resolveWithCache`；失败整轮降级；`hasDeployChanges` 计入 sandbox 文件 |
| `idea/.../deploy/run/applychanges/JuggDeployer.kt` | install 清理 `.jugg_native`；ABI 解析抽到 `resolveWithCache` |
| `jvmti_agent/.../instrument/InstrumentationHooks.java` | hook 内始终尝试 native 注入 |
| `jvmti_agent/.../hotfix/HotfixLoader.java` | `install()` 末尾幂等注入 |
| `jvmti_agent/.../hotfix/NativeLibraryPathInstaller.java` | 设备侧路径前置；无 `.enabled` 不注入 |
| `build.gradle` | `agentVersion` `1.0.80` |

### 6.2 新文件

| 文件 | 必要性 |
|------|--------|
| `main/.../deploy/nativesandbox/NativeSandboxDeployPlanner.kt` | 资格与互斥，可单测 |
| `main/.../deploy/nativesandbox/NativeSandboxWriteRequest.kt` | Writer 入参 |
| `main/.../deploy/nativesandbox/NativeSandboxWriter.kt` | 两步传输 |
| `jvmti_agent/.../hotfix/NativeLibraryPathInstaller.java` | 设备侧路径前置 |

### 6.3 测试文件

| 文件 | 层级 | 断言什么 |
|------|------|----------|
| `NativeSandboxDeployPlannerTest.kt` | L1 | Manifest+SO → Skip；仅 NativeLib + sandbox 可用/API26/arm64 → Attempt；错 ABI / UNAVAILABLE / API25 → Skip |
| `NativeSandboxWriterTest.kt` | L1 | staging 路径 `nativeLib/`、sandbox `cp`、OK 标记、push 失败不声明成功、unsafe 路径拒绝；写入 `.enabled`；关闭只撤运行时标记 |
| `JuggDeployDataTest.kt` | L1 | `nativeSandboxFiles` 触发 restart；`filterForApks` 裁剪 |
| `JuggDeployerHelperDeployFlowTest.kt` | L2 | native-only 不走 resign/reinstall；Writer 失败后仍走 updateApk；关闭后保留补丁并撤 `.enabled` |
| `JuggRunSettingsComponentTest.kt` | L2 | 打开/关闭 SO hot update 持久化；无设备时置待同步运行时标记 |
| `JuggDeployerInstallTest.kt` | L2 | 清理命令包含 `.jugg_native` |
| `NativeLibraryPathInstallerTest.java` | **不新增** | 无稳定 DexPathList |

### 6.4 文档

| 文件 | 状态 |
|------|------|
| `docs/ai_knowledge/03_deploy_core.md` | 已同步快路径、暂存 `nativeLib/`、互斥回退 |
| `docs/ai_knowledge/03_runtime_jvmti.md` | 已同步 startup hook 注入 |
| `docs/ai_knowledge/98_code_map.md` | 已同步 Planner / Writer / Installer |
| `docs/wiki/zh/capabilities/compile/so-update.md` 及英文镜像 | 已同步用户可见快路径 |
| Wiki 入口页（`capabilities/compile/index`、`guide/compile`、`deploy-strategy`、`apk-update-and-install`） | **仍写旧「一定重签」口径**，未改 |

不改 Freeline 调研归档，不把实现细节写进 `native_library_incremental_deploy_research.md`。

---

## 7. 明确不做

- Freeline Socket Server / HTTP `/pushNative`
- ASM 改启动类、默认 `BootstrapApplication` SDK
- `System.load(绝对路径)` / `dlopen` / `DT_NEEDED` / Flutter AOT `libapp.so` 专用适配
- 进程内替换已映射 SO
- 删除 so 的设备协议
- 同轮 NativeLib 双写
- 为测试增加 `provider` / lambda seam
- 改 `DeployDataGenerator` 按设备分流
- 实现 V14/V23 反射
- 改 `AppSandboxExecutor` 对默认 App sandbox 的 `repairCodeCache` 短路

Flutter AOT `libapp.so` 若出现在 `changedLibs`，Planner 不特殊识别文件名；走同一 `loadLibrary`/findLibrary 快路径。业务用绝对路径加载时快路径会静默旧库——验收只承诺 `System.loadLibrary`。

---

## 8. 落地顺序（已完成）

1. L1 Planner 测试与实现  
2. L1 Writer 测试与实现  
3. L1 `JuggDeployData`  
4. L2 Flow：native-only 不 resign；Writer 失败仍 updateApk  
5. Helper 分流 + install 清理  
6. Runtime installer + hook；`agentVersion` 保持 `1.0.77`  
7. 定向测试 + 能力页 / 知识库同步  

---

## 9. 验证

### 9.1 自动化

| 要证明的行为 | Owner | 层 |
|--------------|-------|----|
| NativeLib 与 Manifest 互斥 | `NativeSandboxDeployPlanner` | L1 |
| 仅 NativeLib 且 sandbox 可用才 Attempt | 同上 | L1 |
| Writer 失败不是 SUCCESS | `NativeSandboxWriter` | L1 |
| native-only 不 resign | `JuggDeployerHelperDeployFlowTest` | L2 |
| 默认关闭走 APK 路径 | 同上 | L2 |
| 关闭开关后保留补丁并撤 `.enabled` | 同上 / `NativeSandboxWriter` | L2 / L1 |
| API < 26 走 APK 路径 | 同上 | L2 |
| Writer 失败整轮 updateApk | 同上 | L2 |
| install 清 `.jugg_native` | `JuggDeployerInstallTest` | L2 |
| 开关默认 false 且面板可改；关闭后标记同步运行时 flag | `JuggSettingsTest` / `JuggRunSettingsComponentTest` | L1 / L2 |

`NativeLibraryPathInstaller` 的反射、真 `dlopen`、SELinux execute：**不自动化**。

### 9.2 设备 PoC（替代 runtime 单测）

目标：debuggable 普通 App，sandbox 可用，API 26+，`System.loadLibrary` 可观察 Build ID / 字符串 marker。

| # | 场景 | 通过标准 |
|---|------|----------|
| 1 | 仅改一个 `.so`，两次连续更新 | 无 Resign/Reinstall 日志；两次 marker 均为新值 |
| 2 | 人为让 `adb push` 或 sandbox `cp` 失败 | 日志含 fallback；最终行为来自新 APK；无半成功 |
| 3 | 同轮 Manifest + SO | 不写 `.jugg_native` 作为唯一真相；走原 APK |
| 4 | 纯 dex HOT_RELOAD / 纯 resource overlay | 行为与改前一致 |
| 5 | `/proc/<pid>/maps` 或 `findLibrary` | 映射来自 `code_cache/.jugg_native/<abi>/` |

不能用「push 成功」代替 Build ID / marker。

### 9.3 定向命令

```text
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.nativesandbox.NativeSandboxDeployPlannerTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.nativesandbox.NativeSandboxWriterTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.run.JuggDeployDataTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelperDeployFlowTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployerInstallTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.bean.JuggSettingsTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggRunSettingsComponentTest"
./gradlew :idea:compileKotlin
```

---

## 10. 风险与残留项

| 项 | 当前处理 |
|----|------|
| sandbox 读 `/data/local/tmp/jugg/nativeLib` | 失败即降级 |
| 默认 App sandbox 下 `.so` 不可执行 | 未改 executor；Direct/root 才 repair |
| 注入晚于首次 `loadLibrary` | hook 放在 `newApplication`/`attachBaseContext` |
| 隐藏 API 反射失败 | warn + `inject_failed` 标记；Host 本轮不读 |
| 大 `.so` 进 `DeployItem.content` | 沿用 `Int.MAX_VALUE` 上限 |
| resign 前探测 sandbox | LaunchContext 尚未创建，Helper 额外构造一次 `AppSandboxExecutor`；ABI 仍复用 Helper 的 `AppAbiCache` |

---

## 11. 分期

**MVP（已落地）**：Planner + Writer + Helper 分流/降级 + installer + agent `1.0.77` + install 清理 + L1/L2 测试；能力页与 `03_deploy_core` / `03_runtime_jvmti` / `98_code_map` 已同步。

**P1**：Host 读 `.jugg_native_inject_failed` 下轮直降 B；用户日志区分 push-SO / APK native；Wiki 入口页仍写旧「一定重签」口径。

**P2**：绝对路径 load、多 ClassLoader、Flutter AOT 专用适配。

---

## 12. 依据

- Store：SO 热更新调研稿、`docs/freeline-so-hotupdate-exploration.md`、`docs/so-deploy-latency-freeline-vs-jugg.md`
- 仓库：`docs/ai_knowledge/00_overview.md`、`99_index.md`、`98_code_map.md`、`03_deploy_core.md`、`03_deploy_data_generator.md`、`03_runtime_jvmti.md`、`06_testing.md`
- `docs/task/2026-09/native_library_incremental_deploy_research.md`
- 代码：`NativeSandboxDeployPlanner.kt`、`NativeSandboxWriter.kt`、`NativeSandboxWriteRequest.kt`、`JuggDeployData.kt`、`JuggDeployerHelper.kt`、`JuggDeployer.kt`、`AppSandboxExecutor.kt`、`NativeLibraryPathInstaller.java`、`InstrumentationHooks.java`、`HotfixLoader.java`、`build.gradle`
