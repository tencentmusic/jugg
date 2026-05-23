# 部署系统：核心部署机制

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只回答三个问题：

- **核心类在哪里**：AI 先知道读哪个类，不在仓库里盲跳。
- **主链路怎么走**：把跨类调用链和状态机写清楚，减少逐层 Go to Definition。
- **代码不显眼的约束是什么**：overlay id、Direct Overlay、multi APK、retry/recover 的设计边界。

不展开编译产物如何生成；影响分析看 `03_deploy_data_generator.md`，端到端 Run 链路看 `03_deploy_complete.md`，JVMTI 细节看 `03_runtime_jvmti.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggDeployerHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | 部署总协调器。决定 install / embedded / incremental，串联 recover、retry、runTask、agent、androidTest、历史提交。 |
| `DeployStateRecover` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployStateRecover.kt` | 设备状态未知或不匹配时恢复基线：direct check、dry deploy、reinstall。 |
| `DeployRetryHandler` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt` | 根据失败原因选择 retry、fallback HOT_FIX、compat deploy、recover 后 redeploy 或停止。 |
| `JuggDeployTask` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt` | 单设备单轮 deploy task。按 `applicationId` 分组，把全量 `JuggDeployData` 裁成 APK-scoped data 后调用 `JuggDeployer`。 |
| `JuggDeployer` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | 封装 Android Studio deployer：install、code swap、full swap、deployment cache、overlay id、Direct Overlay transport。 |
| `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | 部署文件 facade。维护 changed/compiled/staging/deployed 状态，生成 `JuggDeployData`，reinstall 后 reset。 |
| `DeployDataPlanner` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployDataPlanner.kt` | 从 staging + history 规划部署数据，处理 dex merge 与 compat deploy 组装。 |
| `JuggDeployData` / `DeployItem` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | 最终下发设备的部署数据模型，包含 deploy type、APK 归属、restart 判断、split/filter。 |
| `DirectOverlaySwapTransport` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlaySwapTransport.kt` | Direct Overlay swap transport。只替换 Apply Changes 的 overlay update 动作，不接管部署生命周期。 |
| `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | 通过 `run-as` 原子写入设备 `code_cache/.overlay`，新 overlay id 最后提交。 |
| `DirectOverlayStateChecker` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayStateChecker.kt` | recover 校验 history/cache/device 三路一致；swap 前只校验 device overlay。 |
| `DeployHistoryManager` / `JuggDeploymentService` | `main/.../DeployHistoryManager.kt`, `idea/.../JuggDeploymentService.kt` | 两套 checkpoint 来源：Jugg 自有部署历史与 Android Studio deployment cache。Direct Overlay recover 同时依赖二者。 |

---

## 3. 部署状态模型

### 3.1 `JuggDeployData` 到部署类型

| 条件 | `DeployType` | 含义 |
|---|---|---|
| `JuggSettings.isEmbeddedToApk` | `EMBEDDED` | 将增量文件写回 APK 后安装。 |
| `isInstall` | `INSTALL` | 安装 APK，并写 deployment cache / overlay id。 |
| `isWarmUp` | `WARM_UP` | dry / warm-up payload，不应产生真实业务变更。 |
| `isCompatDeploy` | `COMPAT_HOT_FIX` | 兼容热修路径，通常 `isPushOverlayOnly=true`。 |
| `isNeedRestartApp` | `HOT_FIX` | 需要重启 App 生效。 |
| 其他 | `HOT_RELOAD` | 在线 Apply Changes，尽量不重启 App。 |

`isNeedRestartApp` 由 hot-fix classes 或非空 `isPushOverlayOnly` 决定；`isNeedRestartActivity` 只在非 warm-up、非空、且不需要重启 App 时成立。

### 3.2 文件状态流转

```text
changed source
  -> DeployFileStateTracker.addChangedFiles()
  -> compile success: updateUncompiledFiles() + addStagingFiles()
  -> DeployDataPlanner.buildDeployData()
  -> deploy success: DeployFileManager.commit()
  -> deployed history

recover with reinstall
  -> DeployFileManager.resetAfterReinstall()
  -> 清空 deployed data / resource APK / staging 状态
```

关键约束：`DeployFileManager.commit(deployData)` 只能在整轮 deploy 成功后执行；`JuggDeployTask` 内部按 APK 裁剪出来的 scoped data 不能用于全局 lifecycle commit。

---

## 4. 核心调用链路

### 4.1 install 链路

```text
JuggDeployerHelper.deploy(isInstall=true)
  -> deployInstall()
  -> JuggDeployData.forInstall(apks)
  -> runTask()
  -> JuggDeployTask.run()
  -> groupByApplicationId()
  -> JuggDeployer.install()
  -> AsDeployerCompat.install()
  -> JuggDeploymentService.storeEntry()
  -> deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
```

install 前会先 stop app，避免用户看到“安装后又被停止”的错觉。安装失败时优先透出 `AdbLogWrapper.realErrorMessage`，不要先改高层错误文案。

### 4.2 incremental deploy 链路

```text
JuggDeployerHelper.deploy(isInstall=false)
  -> deployIncrementalChanges()
  -> DeployFileManager.getDeployData(isWarmUp, isNeedPushResourceApk)
  -> LibraryTestApkBackfillHelper.backfillIfNeeded()
  -> 需要更新 APK: IncrementalDeployHelper.updateApk() + recoverDeployState()
  -> 设备 not ready: DeployStateRecover.recoverDeployState()
  -> 可选 quick fallback: JuggDeployData.toFallbackToHotFixData()
  -> runTask()
  -> JuggDeployTask.run()
  -> JuggDeployer.codeSwap() / fullSwap()
  -> updateInfoAfterIncDeploy()
```

`updateInfoAfterIncDeploy()` 顺序不能乱：先更新 deploy history，再 `DeployFileManager.commit(deployData)`，最后写 `lastDeployOverlayIds`。这个顺序保证文件历史和 overlay checkpoint 一起前进。

### 4.3 runTask 内部决策点

```text
runTask()
  -> data.isInstall ? INSTALL
     : data.isNeedRestartActivity ? APPLY_CHANGES_AND_RESTART_ACTIVITY
     : APPLY_CHANGES
  -> INSTALL 时先 stop app
  -> 异步判断是否需要 push JVMTI agent
  -> 删除回滚后的 library dex
  -> SliceDeployHelper 切片
  -> 每个 slice 创建 LaunchContext + JuggDeployTask
  -> 必要时 push agent / restart app / start app / run androidTest
  -> 必要时检查 JVMTI compat issue
```

切片后只有第一个 slice 保留 except overlay check；后续 slice 会跳过，否则同一轮部署中 overlay id 已变化会导致自我冲突。

---

## 5. recover / retry 状态机

### 5.1 recover

```text
recoverDeployState()
  -> clean reinstall? 先 pm clear
  -> isNeedDryDeployFirst?
      -> tryDryDeploy()
          -> pm path 不存在: APP_NOT_INSTALLED
          -> DirectOverlayStateChecker.checkRecover()
              -> MATCHED: SUCCESS
              -> MISMATCHED: FAILED
              -> UNKNOWN: fallback legacy dry deploy
          -> restart app + waitingForDeployable(默认 3s)
          -> run dry deploy payload
  -> dry deploy 成功: 不重装
  -> dry deploy 失败 / app updated / clean reinstall: install apks
  -> allowDirectOverlayRecover && direct overlay 开关: defer INSTALL 后 launch，跳过 waitingForDeployable(5s)
  -> redeploy 时 `isSkipExceptOverlayCheck=true`（retry 或 `isRecoverWithReinstall`）会在 recover 的 `checkRecover` 中跳过 cache/history 本地不一致，改以 deployment cache 校验设备 overlay，避免重装后二次 recover 再 install
  -> 否则: INSTALL 后 restart + waitingForDeployable(5s)
  -> DeployFileManager.resetAfterReinstall()
```

Direct Overlay recover 只在 `allowDirectOverlayRecover=true` 且 `JuggSettings.isEnableDirectOverlayDeploy` 开启时参与 `tryDirectDryDeploy` / defer launch。`DeployRetryHandler` 在 **direct deploy failed** retry 时传 `allowDirectOverlayRecover=false`：recover 走 legacy（启动 App + Apply Changes dry deploy；reinstall 后 wait online），与 redeploy 的 `isAllowDirectOverlayDeploy=false` 一致。

其它 recover 场景（overlay mismatch、主链路 not ready）保持 `allowDirectOverlayRecover=true`（或来自 `DeployOptions.isAllowDirectOverlayDeploy`）。

### 5.2 retry

| 失败信号 | 行为 |
|---|---|
| transient offline | 等待 ADB transport 恢复，成功后用原 deploy data redeploy。 |
| `REDEPLOY_WITH_COMPAT_MESSAGE` | `appendCompatDeployFiles()` 后 compat redeploy。 |
| `JVMTI_ERROR_UNMODIFIABLE_CLASS` / `app restart` / redefiner/internal error | fallback 到 HOT_FIX 后 redeploy。 |
| `INSTRUMENTATION_FAILED` / `IOException occurred` | 不改 payload，直接重试。 |
| agent no response / deploy timeout | 先检测 JVMTI compat；必要时 compat deploy。 |
| overlay id mismatch / class not found / direct deploy failed | recover deploy state 后 redeploy。direct deploy failed 时 recover 禁用 direct overlay（legacy + `isAllowDirectOverlayDeploy=false`）。 |
| install `INSTALL_FAILED_INVALID_APK` | uninstall 当前 applicationId 集合后重新 install。 |
| 用户限制、设备丢失、APK install 失败、embedded APK 冲突 | 停止 fallback，向上暴露失败。 |

timeout 规则：overlay 数超过首片阈值时先降低 slice size；否则前两次等待后重试，第三次尝试 reinstall，超过次数停止。

---

## 6. Direct Overlay 旁路

### 6.1 触发条件

`DirectOverlaySwapOptions.enabled = settingsEnabled && !isDeviceReadyDeploy && isAllowedByCaller`。

Direct Overlay 是离线/非 ready 场景下的 overlay 写入旁路，不替代在线 HOT_RELOAD。还要求非 install、deploy data 非空、deployment cache 存在、设备当前 overlay id 与预期一致。

### 6.2 swap 链路

```text
JuggDeployer.optimisticSwap()
  -> load deployment cache
  -> except overlay id check
  -> tryDirectOverlaySwap()
      -> DirectOverlaySwapTransport.canTry()
      -> ensureApplyChangesStartupAgent()
      -> DirectOverlayStateChecker.checkDevice()
      -> DirectOverlayWriteRequestBuilder.build()
      -> DirectOverlayWriter.write()
          -> zip overlay files
          -> push /data/local/tmp/jugg/direct-overlay-*.zip
          -> run-as package sh -c apply script
          -> 删除旧 id
          -> unzip files
          -> chmod *.dex 0444
          -> 最后写新 id
      -> JuggDeploymentService.storeEntry()
  -> direct 返回 null: fallback 旧 Apply Changes
```

### 6.3 dirty 语义

- writer 在修改 overlay 目录前失败：返回 `SKIPPED`，允许 fallback 旧 Apply Changes。
- writer 已开始修改 overlay 目录后失败：返回 `FAILED_DIRTY` 并抛 `DirectOverlayDirtyException`，不再继续旧 Apply Changes，避免半提交状态上做伪回退。

---

## 7. 隐形约束

- `overlay id` 是部署一致性的核心 checkpoint：Jugg history、Studio deployment cache、设备 overlay 目录任一不一致，都可能导致重装或 recover。
- `exceptOverlayIds` 防止同 package 在不同项目/不同设备间串状态；recover 或同轮切片会按需跳过检查。
- `JuggDeployData.filterForApks()` 只给 deployer transport 用；不要用裁剪后的 scoped data 更新全局文件状态或历史。
- `DeployItem.targetApkPaths` 表示真实部署目标；`apkPath` 仍保留旧单 APK 锚点。判断资源/overlay 归属时优先看 `targetApkPaths`。
- self-targeting library Test APK backfill 成功安装后，必须立即把新 overlay ids merge 到 `deployHistoryManager.lastDeployOverlayIds`，否则第一轮 replay 会误判状态不匹配并重装。
- compat deploy 会去掉原 res/asset overlays，追加 enable flag，并按资源 overlay 生成 resource APK deploy item。
- dex merge 阈值是 `DeployDataPlanner.MAX_DEPLOYED_DEX_COUNT = 1000`；超过阈值时把 staging dex + 未 staging 的历史 dex merge，失败则保留原数据继续部署。
- transient offline 的设计目标是在失败点附近恢复：shell/deployer 层原地等待并重试一次，编排层只处理已经冒泡的 offline 失败。
- install 路径遇到 transient failure 可能从 DELTA 升级为 FULL install；不是所有 install 失败都应该进入 incremental fallback。

---

## 8. 排查入口

| 现象 | 优先入口 |
|---|---|
| `Deploy state not match, start reinstalling app...` | `DeployStateRecover.tryDryDeploy()`、`DirectOverlayStateChecker.checkRecover()` |
| `OVERLAY_ID_MISMATCH` 或 “state unknown to Studio” | `JuggDeployer.optimisticSwap()` |
| Direct Overlay 未触发 | `DirectOverlaySwapOptions.logEnabled()`、`DirectOverlaySwapTransport.canTry()` |
| Direct Overlay 后不能 fallback | `DirectOverlayWriter.write()` |
| 部署后总是重启 App | `JuggDeployData.isNeedRestartApp`、`JuggDeployerHelper.runTask()` |
| library dex 回滚后仍生效 | `JuggDeployerHelper.removeLibraryDexFiles()` |
| androidTest 部署到错误 APK | `JuggDeployData.groupByApplicationId()`、`filterForApks()`、`LibraryTestApkBackfillHelper` |
| install 错误信息太泛 | `AdbLogWrapper.realErrorMessage`、`JuggDeployer.install()` |

---

## 9. 关联文档

- 端到端部署全流程：`03_deploy_complete.md`
- 影响分析与部署数据生成：`03_deploy_data_generator.md`
- 常量引用影响分析：`03_deploy_const_ref.md`
- JVMTI agent 协同：`03_runtime_jvmti.md`
- 部署相关测试落点：`06_testing.md` §7.1
