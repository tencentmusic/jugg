# KMP Resource Compile 重启策略方案

## 1. 背景

KMP Compose resource 当前分为两条运行时读取路径：

- legacy resource 写入 APK 根目录，通过 `ClassLoader` 读取，现有 APK 根目录 overlay 规则会重启 App。
- non-legacy resource 写入 `assets/`，通过 `AssetManager` 读取。虽然不属于 APK 根目录 overlay，但 Compose runtime 仍存在进程级资源缓存，因此只重建 Activity 不足以保证读取新内容。

目标是让本轮成功执行过 Compose resource compile 的正常部署重启 App，同时让 reinstall recover 在历史 overlay replay 完成后统一重启或启动 App。方案不恢复历史 `ComposeResource` 来源，也不依赖 Asset 路径、generated class 名称或 Compose runtime 反射。

## 2. 已确认事实

### 2.1 正常编译状态

`IncrementalCompilerHelper.compile()` 在第一轮编译完成后调用：

```text
DeployFileManager.updateUncompiledFiles(successFiles, failedFiles)
DeployFileManager.addStagingFiles(compileResult.outputs)
```

成功的原始 `ChangedFile` 会从 `uncompiledFiles` 移入 `compiledFiles`。Compose resource 输入仍保留 `CompileFile.Type.ComposeResource`，直到整轮部署成功后 `DeployFileManager.commit()` 清理。

因此，正常部署是否发生过 Compose resource compile 可以直接由以下状态判断：

```kotlin
stateTracker.getCompiledFiles().any {
    it.type == CompileFile.Type.ComposeResource
}
```

该状态具有以下生命周期：

- 同一轮多次 continue compile 时保留。
- deploy retry 前保留。
- 多设备部署时保留到最后一个设备 commit。
- commit 后清理，不会污染下一轮部署。
- 不包含历史 deployed 文件，不会把历史恢复误判成本轮 resource compile。

### 2.2 Reinstall recover

`DeployStateRecover.recoverDeployState()` 在确认需要 reinstall 后：

1. 安装 APK。
2. 调用 `DeployFileManager.resetAfterReinstall()`，把需要 replay 的历史 deployed 文件加入逻辑 staging。
3. `JuggDeployerHelper.deployIncrementalChanges()` 重新调用 `getDeployData()`。
4. follow-up deploy replay 当前 staging 与历史 staging。

Direct Overlay recover 会通过 `deferPostDeployLaunch=true` 让 App 在 reinstall 后保持停止；普通 recover 为了使用在线 Apply Changes，会先启动 App 并等待进程可部署。因此不能统一假设 replay 时 App 一定停止。

但两条路径都可以在 replay 完成后统一调用 `restartApp()`：

- Direct Overlay：App 原本停止，`restartApp()` 等价于首次启动并读取完整 overlay。
- 普通 recover：App 已运行，`restartApp()` 清理启动及 replay 期间产生的进程缓存。

因此 recover 只需要传递“本轮在 replay 前发生过 reinstall”这一瞬时事实，不需要识别历史 Compose resource。

## 3. 目标与非目标

### 3.1 目标

1. 本轮成功 Compose resource compile 后，增量部署完成时重启 App。
2. reinstall recover 的 follow-up replay 完成后统一重启或启动 App。
3. 正常 retry、compat retry、Direct Overlay、普通 Apply Changes、split deploy 和多设备部署保持一致行为。
4. 不修改历史数据库格式，不恢复历史 Compose 来源。
5. 保留现有 APK 根目录 overlay 重启规则，继续覆盖 Java classpath resource 等非 Compose 场景。

### 3.2 非目标

- 不通过 `assets/composeResources/**` 路径猜测 Compose 来源。
- 不通过 generated class / Dex 名称识别 Compose resource compile。
- 不新增 `CompileOutput.Type.ComposeResourceAsset`。
- 不反射清理 Compose runtime 内部缓存。
- 不改变 Compose resource 删除仍需完整 Gradle build 的现有边界。

## 4. 数据模型

在 `JuggDeployData` 增加两个默认值为 `false` 的瞬时字段：

```kotlin
val isComposeResourceCompiled: Boolean = false,
val isRecoverReplayAfterReinstall: Boolean = false,
```

职责分别为：

| 字段 | 生产者 | 生命周期 | 含义 |
|---|---|---|---|
| `isComposeResourceCompiled` | `DeployDataPlanner` | 当前未 commit 编译批次 | 本轮成功编译过 Compose resource |
| `isRecoverReplayAfterReinstall` | `JuggDeployerHelper` | 当前设备 recover/retry 链路 | 当前 deploy 是 reinstall 后的 follow-up replay |

这两个字段只影响当前部署策略，不进入 `CompileContextDb`、`DeployHistoryDb` 或设备端 payload。

## 5. 正常部署规则

### 5.1 检测位置

在 `DeployDataPlanner.buildDeployData()` 中读取 `stateTracker.getCompiledFiles()`：

```kotlin
val isComposeResourceCompiled = !isWarmUp && stateTracker.getCompiledFiles().any {
    it.type == CompileFile.Type.ComposeResource
}
```

调用 `deployDataGenerator.buildDeployData()` 后，将结果写入最终 `JuggDeployData`：

```kotlin
deployData = deployData.copy(
    isComposeResourceCompiled = isComposeResourceCompiled,
)
```

选择 `DeployDataPlanner` 的原因：

- 它是 `DeployFileManager state -> JuggDeployData` 的现有转换 owner。
- retry 重新构建 deploy data 时仍读取未 commit 的 `compiledFiles`。
- 不需要给 `CompileResult`、`CompileOutput` 或 `DeployItem` 增加来源字段。

### 5.2 重启判断

`JuggDeployData.isNeedRestartApp` 增加 Compose resource 条件：

```text
hot-fix modified classes 非空
或 push-overlay-only 且 payload 非空
或存在 APK 根目录 overlay
或本轮 Compose resource compile 且 payload 非空
或当前是 reinstall 后的 recover replay
```

Compose 条件增加 `!isEmpty`，避免异常状态下仅残留编译标记却产生空部署重启。warm-up 在 planner 中不设置该字段。

Recover replay 条件不增加 `!isEmpty`：Direct Overlay recover 可能在 reinstall 后保持 App 停止，即使没有历史文件需要 replay，也必须最终启动 App。

## 6. Recover 规则

`JuggDeployerHelper.deployIncrementalChanges()` 已通过 `isRecoverWithReinstall` 记录本轮是否真的发生 reinstall。

在 reinstall 后重新构建 deploy data 时设置：

```kotlin
deployData = deployFileManager
    .getDeployData(deployOptions.isWarmUp, isNeedPushResourceApk(device, deployData))
    .copy(isRecoverReplayAfterReinstall = true)
```

这会让 follow-up deploy 使用 `APPLY_CHANGES` transport，完成 overlay/class 写入后由 `executeDeployRunTask()` 的现有 post-deploy 分支调用 `restartApp()`。

### 6.1 Direct Overlay recover

```text
stop App
-> reinstall APK
-> defer launch
-> resetAfterReinstall
-> rebuild deploy data + isRecoverReplayAfterReinstall
-> Direct Overlay replay
-> restartApp（等价于首次启动）
```

### 6.2 普通 recover

```text
stop App
-> reinstall APK
-> restart App 并等待 deployable
-> resetAfterReinstall
-> rebuild deploy data + isRecoverReplayAfterReinstall
-> online Apply Changes replay
-> restartApp（清理缓存）
```

普通 recover 会比当前多一次进程重启，但 recover 属于低频异常/恢复路径，正确性优先。

## 7. Retry 与其他部署分支

### 7.1 普通 retry

`retryDeployData` 直接复用 `JuggDeployData`，两个字段会通过 data class `copy()` 保留。

未显式传递 `retryDeployData`、而是重新调用 `getDeployData()` 的 retry：

- `isComposeResourceCompiled` 从仍未 commit 的 `compiledFiles` 重建。
- 若 retry 中再次发生 reinstall recover，重新设置 `isRecoverReplayAfterReinstall=true`。

### 7.2 Compat retry

`appendCompatDeployFiles()` 使用 `copy()` 构建 compat data，会保留两个字段。compat 本身还有 `isPushOverlayOnly=true`，因此即使字段丢失也会重启，但仍应保持数据语义一致。

### 7.3 Split deploy

`executeDeployRunTask()` 在切片前使用完整 `JuggDeployData.isNeedRestartApp` 选择 `AndroidDeployType`。命中任一新条件后：

- 所有 slice 使用不重建 Activity 的 `APPLY_CHANGES`。
- 所有 slice 成功后只在外层执行一次 `restartApp()`。

中间 dry slice 不需要单独携带重启原因；最后 slice 通过原始 data copy 保留完整字段。

### 7.4 多设备部署

只有最后一个设备成功后才 commit。前序设备部署期间 `compiledFiles` 不会清理，因此每台设备构建的 deploy data 都能获得相同的 Compose resource compile 标记。

### 7.5 Dry deploy

仅 dry deploy 成功、未 reinstall 时，不设置 `isRecoverReplayAfterReinstall`。本轮当前 Compose resource compile 标记仍保留，真实变更部署完成后正常重启。

### 7.6 Install 与 embedded deploy

- 普通 install 的 `DeployType.INSTALL` 优先级高于重启判断，现有安装启动行为不变。
- embedded deploy 最终重新安装 APK，不依赖新增 Compose 标记。

### 7.7 Debug 与 Always Restart

Debug、`Always restart app after deployment` 等现有强制重启策略继续在 post-deploy 阶段覆盖新增规则，不需要调整优先级。

## 8. 失败与回退

- Compose resource compile 失败时不会进入 `compiledFiles`，不产生重启标记。
- deploy 失败时不 commit，标记保留供 retry。
- reinstall recover 失败、未进入 follow-up replay 时，不设置 recover replay 标记。
- follow-up replay 失败时不执行成功后的 commit；retry 继续保留当前编译状态，若再次 reinstall 则重新设置 recover 标记。
- `restartApp()` 失败沿用当前行为和日志，不新增安装或 Gradle fallback。

## 9. 测试价值与测试矩阵

这次行为保护的是用户可见的进程重启策略、recover 编排和 KMP resource 运行时可见性，均通过测试价值门禁。

| 层级 | Owner | 场景 | 修改前失败 | 修改后结果 |
|---|---|---|---|---|
| L1 | `JuggDeployDataTest` | `isComposeResourceCompiled=true` 且 payload 非空 | non-legacy Asset 不触发 App restart | `isNeedRestartApp=true`、`deployType=HOT_FIX` |
| L1 | `JuggDeployDataTest` | Compose 标记但 payload 为空 | 可能误重启 | 不因 Compose 标记重启 |
| L1 | `JuggDeployDataTest` | `isRecoverReplayAfterReinstall=true` 且 payload 为空 | Direct Overlay recover 后 App 可能保持停止 | `isNeedRestartApp=true` |
| L2 | `JuggDeployerHelperDeployFlowTest` | non-legacy Compose compile deploy | 仅 Activity restart | Apply Changes 后调用一次 `restartApp()` |
| L2 | `JuggDeployerHelperDeployFlowTest` | Direct Overlay reinstall recover + replay | 历史来源不可识别 | replay 后调用一次 `restartApp()`，不依赖 overlay 类型 |
| L2 | `JuggDeployerHelperRecoverTest` | 普通 reinstall recover | replay 后只重建 Activity | follow-up data 带 recover 标记并最终重启进程 |
| L3 | `KmpComposeDeployFlowTest` | 先读取 baseline 以预热缓存，仅修改 resource value 后部署 | 读取旧值 | 进程重启后读取新值 |

L3 失败证据必须避免当前测试的冷缓存缺口：baseline APK 启动后先执行 `runtimeSnapshot()`，确认旧值已进入进程缓存；随后只修改 Compose resource，不同时修改 Activity 探针源码。优先使用长度相同的新旧 value，避免 Compose cache key 因 size 变化自然失效而掩盖问题。

## 10. 实施步骤

1. 在 `JuggDeployDataTest` 增加 Compose compile、空 payload、recover replay 三个失败用例并确认失败原因。
2. 在 `JuggDeployData` 增加两个默认字段并扩展 `isNeedRestartApp`。
3. 在 `DeployDataPlanner.buildDeployData()` 从 `compiledFiles` 写入 `isComposeResourceCompiled`。
4. 在 `JuggDeployerHelper.deployIncrementalChanges()` 的 reinstall rebuild 分支写入 `isRecoverReplayAfterReinstall`。
5. 扩展 L2 deploy/recover flow，确认 transport 使用 `APPLY_CHANGES`，并且整轮只调用一次 `restartApp()`。
6. 修正 `KmpComposeDeployFlowTest` 的缓存预热方式，取得修改前失败证据并验证修改后运行时新值。
7. 执行定向测试和 `./gradlew :idea:compileKotlin`。
8. 同步 `02_compile_resource.md` 与 `03_deploy_core.md`，记录正常 Compose compile 和 reinstall recover 的新重启规则。

## 11. 验证命令候选

实施时按实际测试类确认 Gradle task，禁止无 `--tests` 的全量测试。候选范围：

```text
./gradlew :main:test --tests '*JuggDeployDataTest'
./gradlew :idea:test --tests '*JuggDeployerHelperDeployFlowTest'
./gradlew :idea:test --tests '*JuggDeployerHelperRecoverTest'
./gradlew :idea:test --tests '*KmpComposeDeployFlowTest'
./gradlew :idea:compileKotlin
```

## 12. 文档同步

- `docs/ai_knowledge/02_compile_resource.md`
  - non-legacy Compose resource compile 完成后需要进程重启。
  - L3 必须先预热资源缓存再验证 value 更新。
- `docs/ai_knowledge/03_deploy_core.md`
  - `isNeedRestartApp` 增加当前 Compose resource compile 与 reinstall recover replay 两类条件。
  - recover replay 不依赖历史资源类型判断。

## 13. 已排除方案

### 13.1 Asset 路径识别

`CompileOutput.Type.Asset` 已丢失 Compose 来源；通过 `assets/composeResources/**` 或 Gradle metadata 反推可以工作，但会把部署策略绑定到产物路径约定，且没有必要。

### 13.2 Generated class / Dex 识别

legacy/modern 类名不同，release/minify 会改变路径，历史 Dex replay 还会产生误判。编译输入状态已经提供更直接、稳定的事实。

### 13.3 历史来源持久化

给 `CompileContextDb` 增加 sidecar 或新输出类型可以保留 Compose 来源，但 recover 只需要“reinstall 后统一 restart”这一编排事实，持久化属于过度设计。

### 13.4 反射清理 Compose 缓存

Compose 1.6～1.10 的缓存与 `AssetManager` 行为不同，内部 API 不稳定。进程重启实现更直接，并覆盖未来版本和其他进程级缓存。

## 14. 残余风险

- 普通 reinstall recover 会比当前多一次进程重启，属于可接受的低频恢复成本。
- `isRecoverReplayAfterReinstall` 必须在 rebuild 后立即写入，后续所有 `copy()`、fallback 和 retry 不得主动清除。
- L3 若未先预热 baseline 缓存，会产生无效通过，不能证明本方案解决缓存问题。
- 新字段属于瞬时部署事实，禁止写入历史 DB 或从历史 staging 反推。
