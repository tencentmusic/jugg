# androidTest test APK 部署策略优化：取消增量部署与 JVMTI agent

> 创建时间：2026-05-06
> 状态：方案阶段
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 背景与动机

### 1.1 原始设计（阶段 2/3）

在 androidTest 支持的设计中（`docs/task/androidtest_phase3_design.md` §4.1），test APK 被设计为复用 base APK 的部署策略：

> 如果当前 base APK 逻辑选择 code swap，test APK 也 code swap。

这个设计基于一个假设：test APK 和 base APK 一样有独立进程、需要 JVMTI agent 支持增量部署。

### 1.2 根因发现

实际运行时发现这个假设不成立：

1. **`am instrument` 运行在主 APK 进程内**，test APK 不启动独立进程。JVMTI agent 的 `Agent_OnAttach` (`native-lib.cpp:78`) 收到的是主 APK 的 data dir (`/data/data/com.example.myapplication`)，flag file 创建在主 APK 的 `code_cache/` 下。`isJvmtiAvailable` 对 test APK 执行 `run-as <testPkg> ls code_cache`，检查的是 `/data/data/com.example.myapplication.test/code_cache/`，永远找不到 flag file → 始终返回 `null` → JVMTI 检测轮询跑满 3 秒超时。

2. **部署流程会重启 app**（JVMTI compat fix 路径，`JuggDeployerHelper.kt:153`），重启后 codeSwap 的 class 改动全部丢失，只有磁盘上安装的 APK 内容生效。`am instrument` 从磁盘 APK 加载 test runner 和测试类 → test APK 的 codeSwap/fullSwap 对 instrumentation 无效。

3. **startup_agents 对 test APK 无意义**：虽然两个包名都有 startup_agents 部署，但 test APK 进程从未启动（测试跑在主 APK 进程里），test APK 的 `code_cache/startup_agents` 永远不会被 Android 框架读取。

### 1.3 结论

test APK 只需要完整 APK 安装（INSTALL），不需要：
- JVMTI agent push/attach
- codeSwap/fullSwap 增量部署
- JVMTI compat issue 检测

---

## 2. 需要调整的代码

所有位置均为 `jugg_f1` 项目内路径。

### 2.1 JuggJvmtiAgentManagerHelper — 过滤 test APK

**文件**：`main/src/main/java/deploy/JuggJvmtiAgentManagerHelper.kt`

| 方法 | 行号 | 改动 |
|------|------|------|
| `isNeedPushAgentAfterDeploy` | 24 | `data.apks.forEach` → 加 `.filter { !it.isTestApk }` |
| `pushAgentToApps` | 63 | 同上 |
| `attachAgentToApps` | 76 | 同上 |
| `isHasJvmtiCompatIssue` | 106 | `data.apks.map` → `data.apks.filter { !it.isTestApk }.map` |

**影响**：test APK 不再触发 agent push/attach，不再参与 JVMTI 兼容检测。主 APK 的 agent 行为不受影响。

### 2.2 JuggDeployTask — test APK 走 INSTALL

**文件**：`idea/src/main/java/deploy/run/JuggDeployTask.kt`

| 位置 | 行号 | 改动 |
|------|------|------|
| `run()` 循环 | 91-110 | 在 `for ((applicationId, apkInfos) in packages)` 内，当 `apkInfos.any { it.isTestApk }` 且 `type != INSTALL` 时，强制对该 applicationId 走 `performInstall()` 而非 `perform()` |

方案：新增一个私有方法或 inline 判断。推荐在 `perform()` 方法调用前判断：

```kotlin
val effectiveType = if (apkInfos.any { it.isTestApk } && type != AndroidDeployType.INSTALL) {
    AndroidDeployType.INSTALL
} else {
    type
}
val result = perform(device, deployer, applicationId, apkFiles, effectiveType)
```

需要调整 `perform` 的签名接受可选的 type 覆盖，或者在调用处直接分支。

### 2.3 JuggDeployerHelper — removeLibraryDexFiles 跳过 test APK

**文件**：`idea/src/main/java/deploy/run/JuggDeployerHelper.kt`

| 位置 | 行号 | 改动 |
|------|------|------|
| `removeLibraryDexFiles` | 244 | `data.apks.forEach` → 加 `.filter { !it.isTestApk }` |

**原因**：test APK 从不走增量 DEX 部署，不会有待删除的 DEX 文件。

### 2.4 CompatDeployHelper — 过滤 test APK

**文件**：`main/src/main/java/deploy/CompatDeployHelper.kt`

| 位置 | 行号 | 改动 |
|------|------|------|
| `isEnableCompatDeploy` | 65 | `data.apks.any` → 加 filter 跳过 test APK |

**原因**：compat deploy 判断只需看主 APK。test APK 不参与 compat 部署策略。

### 2.5 测试文件

#### 2.5.1 AndroidTestTopLevelFlowTest （可能不需要调整）

**文件**：`idea/src/test/java/.../manager/AndroidTestTopLevelFlowTest.kt`

- Line 49 `apksSize = 2`：仍然 2 个 APK（test APK 仍参与编译输出）
- Line 63-66 的 log 断言：整体 `type` 仍是 `APPLY_CHANGES`，最终 log 消息不变
- **结论**：预期无需调整

#### 2.5.2 新增测试

| 测试文件 | 覆盖点 |
|----------|--------|
| `main/src/test/java/deploy/JuggJvmtiAgentManagerHelperAndroidTestTest.kt` | `isNeedPushAgentAfterDeploy` / `pushAgentToApps` / `attachAgentToApps` / `isHasJvmtiCompatIssue` 对 test APK 的过滤 |
| `main/src/test/java/deploy/run/ApkInstallOrderTest.kt` | 追加 test APK 在非 INSTALL 场景下走 INSTALL 的测试 |

---

## 3. 不需要调整的部分

| 模块/文件 | 原因 |
|-----------|------|
| `ModuleApkBelongsUtils` | 编译时的 androidTest module → test APK 路由不变。test APK 仍需要正确归属以便 INSTALL 定位正确的 APK |
| `ApkInstallOrder.sortedForInstall()` | test APK 仍需要排在 base APK 之后安装 |
| `TestLauncher` / `InstrumentCommandBuilder` | `am instrument` 逻辑不变 |
| `CompileContextManager` | 增量编译仍然纳入 `.androidTest` module |
| `JuggJvmtiAgentManager` (接口和实现) | 只改调用方过滤，`pushAgentToApp(String)` 签名不变——未来若真的需要单独给 test APK push agent 仍可调用 |

---

## 4. 验收标准

1. `isHasJvmtiCompatIssue` 不再对 test APK 执行 `isJvmtiAvailable` 轮询 → 不再有 test APK 的 `isJvmtiAvailable=null` 日志
2. `pushAgentToApps` / `attachAgentToApps` 不操作 test APK
3. 非 INSTALL 部署场景下，test APK 走完整 APK 安装，base APK 走原有增量策略
4. 普通 app Run（无 androidTest）行为不变
5. 现有测试全部通过

---

## 5. 与现有文档的同步

- 更新 `docs/ai_knowledge/06_android_test.md` §5.1：test APK 部署策略改为 INSTALL-only
- 更新本文档状态为"已实现"（实施后）

---

## 6. 变更历史

- 2026-05-06：初版，梳理根因与全部改动点
