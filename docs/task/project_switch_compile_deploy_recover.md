# 跨工程切换：编译/部署行为修正

> 背景：同 IDE 会话内在工程 A 与工程 B 间切换后 Run，设备上常仍运行 A 的 APK，IDE 侧 `isReadyDeploy=true`，553 行 recover 被跳过，`JuggDeployer.optimisticSwap` 发现 cache/history 与设备不一致后走 `tryRetry`。

## 目标

1. **`JuggCompilerHelper#incrementalCompile`**：切换工程后的首次 Run，即使 `isNoFileChanges()`，也不弹「No file changes, fallback to gradle?」；行为对齐 `isFirstTimeRun`（直接 incremental success → 进入 deploy）。
2. **`JuggDeployerHelper#deployIncrementalChanges`（553 附近）**：切换工程后强制进入 `recoverDeployState`，即使 `isReadyDeploy == true`，用 direct overlay dry check / 重装把设备状态与新工程对齐。

## 设计要点

### 全局标记：最后一次 Run 的工程根路径

| 项 | 说明 |
|----|------|
| 粒度 | **IDE 进程级**（非 per-Project），与 `LastCompileTimestampRegistry` 同类 |
| 存储值 | 工程根目录 canonical path（`JuggGradleCompileOptions.projectRootPath` / `JuggPathManager.projectDir`） |
| 更新时机 | **单次 Run 结束时**（`JuggRunningTask.run` finally），无论 compile/deploy 成败；表示「用户最后一次对哪个工程点了 Run」 |
| 切换判定 | Run **开始时**：`lastPath != null && normalize(lastPath) != normalize(currentPath)` → `isProjectSwitched = true` |
| 首次 Run | `lastPath == null` → **不算**切换（避免 IDE 冷启动误触 recover） |

路径规范化建议：

```kotlin
fun normalizeProjectRoot(path: String): String =
    File(path).canonicalFile.absolutePath
```

### 单次 Run 内传递 `isProjectSwitched`

`detectSwitch` 必须在 **commit 之前** 完成；deploy 与 compile 共用同一 flag。

推荐：扩展 `IJuggRunningTaskStatusManager`（已有 first-run / device 维度）：

```kotlin
interface IJuggRunningTaskStatusManager {
    // existing ...

    /** True when this run detected a project-root switch since the previous Run. */
    var isProjectSwitchedThisRun: Boolean
}
```

生命周期（`JuggRunningTask`）：

```
run() try:
  registry.detectSwitch(options.projectRootPath)
    → statusManager.isProjectSwitchedThisRun = result
  doRun(options)  // compile → deploy
run() finally:
  registry.record(options.projectRootPath)   // 更新 lastPath
  statusManager.isProjectSwitchedThisRun = false  // 可选，下次 run 会重设
  statusManager.setHasRun / resetHasRun ...
```

> 若 `isProjectSwitchedThisRun == true`，**不要**在 compile 成功时提前 `record`，否则 deploy 侧会误判为未切换。

### 1. incrementalCompile：跳过 no-file-changes 弹窗

现有逻辑（521–556 行）：

```
isNoFileChanges && !isNeedCompilation:
  if isFirstTimeRun → deploy directly
  else → confirmFallbackWhenNoFileChanges 弹窗
```

改为：

```
if isFirstTimeRun || isProjectSwitchedThisRun:
  // 与 first time 相同：空变更也 incrementalSuccess，交给 deploy
else:
  // 原弹窗 / fallback 逻辑
```

日志建议：`No file changes, but project switched since last run, deploy directly.`

**不改动** `JuggSettings.isConfirmFallbackWhenNoFileChanges` 语义；切换工程视为与 first-run 等价的例外。

### 2. deployIncrementalChanges：强制 recover

现有条件（551 行）：

```kotlin
if (isNeedReinstallApk || !deployStateManager.getDeployState(device).isReadyDeploy)
```

改为：

```kotlin
val isProjectSwitched = statusManager.isProjectSwitchedThisRun
if (isNeedReinstallApk || !deployStateManager.getDeployState(device).isReadyDeploy || isProjectSwitched) {
    recoverDeployState(
        ...
        isNeedDryDeployFirst = !isNeedReinstallApk,  // 切换且 isReadyDeploy 时仍为 true → 走 direct dry check
        isSkipExceptOverlayCheck = deployOptions.isSkipExceptOverlayCheck,
        ...
    )
}
```

切换 + `isReadyDeploy` 时预期：

- `tryDirectDryDeploy` → `checkRecover` 发现 cache/history/设备不一致 → `FAILED` → reinstall（或 dry 成功则跳过）
- **不再**依赖 `JuggDeployer.optimisticSwap` 抛异常 → `tryRetry` 的绕路

可选增强（非必须首版）：

- recover 成功后设 `isRecoverWithReinstall` 或 `isSkipExceptOverlayCheck`，与现有 retry/reinstall 路径一致。

### 3. 新增类（main 模块，可测）

```kotlin
/** Tracks the last Jugg Run project root across IDE projects in one JVM. */
interface ILastCompileProjectRegistry {
    fun detectSwitch(currentProjectRoot: String): Boolean
    fun record(currentProjectRoot: String)
}

class LastCompileProjectRegistry : ILastCompileProjectRegistry {
    @Volatile private var lastProjectRoot: String? = null
    // detectSwitch / record with normalizeProjectRoot
    companion object { val INSTANCE = LastCompileProjectRegistry() }
}
```

注入：

- `JuggRunningTask`：构造注入 `ILastCompileProjectRegistry`（默认 `INSTANCE`）
- `JuggCompilerHelper` / `JuggDeployerHelper`：读 `IJuggRunningTaskStatusManager.isProjectSwitchedThisRun`（不直接依赖 registry，保持 run 内单一数据源）

`JuggManager` 组装处传入同一 `INSTANCE`（与 `LastCompileTimestampRegistry` 并列）。

## 测试计划（TDD）

| 层级 | 文件 | 用例 |
|------|------|------|
| L1 | `main/.../LastCompileProjectRegistryTest.kt` | null→A 非切换；A→A 非切换；A→B 切换；路径别名（`./` vs canonical）仍判同工程 |
| L2 | `idea/.../JuggDeployerHelperRecoverTest.kt` | `isProjectSwitchedThisRun=true` + mock `READY_DEPLOY` → 仍调用 `recoverDeployState` |
| L2 | `idea/.../JuggRunningTaskStatusManagerTest.kt`（或扩展现有） | switch flag 读写 |
| L2 | compile 无变更 + switched：mock `JuggCompilerHelper.incrementalCompile` 或抽 `NoFileChangesPolicy` 小函数单测，断言不调用 `confirmFallbackWhenNoFileChanges` |

**不要求** L3，除非改动 `deploy()` 分派顺序；本方案仅扩 recover 条件与 compile 早退。

定向运行：

```bash
./gradlew :main:test --tests "*.LastCompileProjectRegistryTest"
./gradlew :idea:test --tests "*.JuggDeployerHelperRecoverTest"
```

## 文档

- 更新 `docs/ai_knowledge/03_deploy_core.md` §5.1：`isReadyDeploy` recover 条件补充「跨工程切换强制 recover」
- 可选：`03_deploy_complete.md` CompileUiHandler / no file changes 表增加切换例外

## 实现顺序

1. L1 `LastCompileProjectRegistry` + 失败测试
2. 扩展 `IJuggRunningTaskStatusManager` + `JuggRunningTask` begin/commit
3. `JuggCompilerHelper.incrementalCompile` 分支
4. `JuggDeployerHelper.deployIncrementalChanges` 条件
5. L2 测试绿 → 更新 ai_knowledge

## 边界

| 场景 | 行为 |
|------|------|
| 同工程连续 Run、无文件变更 | 仍弹窗（或 settings 跳过），**不** force recover |
| 切换工程、有文件变更 | 正常编译；deploy 仍 force recover |
| 切换工程、Gradle 全量编译 | `record` 在 run finally；下次 incremental 才体现切换检测（Gradle run 本身会 reinstall，影响较小） |
| MCP / 多入口 Run | 凡走 `JuggRunningTask` 均覆盖；其它入口若 bypass RunningTask 需单独评估 |
| 两工程同路径（不可能） | canonical path 相同则非切换 |

## 与既有 fix 的关系

- `isSkipExceptOverlayCheck`（retry recover）：仍用于 **retry redeploy** 路径
- 本方案在 **首次 deploy 前** 即 force recover，减少「Apply Changes 失败 → tryRetry」主路径
- `deferPostDeployLaunch`：切换后 recover reinstall 行为不变，redeploy 内第二次 recover 仍可能触发；后续可再优化「retry recover 已成功则跳过 553」（独立 task）
