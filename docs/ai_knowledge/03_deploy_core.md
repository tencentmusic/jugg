# 部署系统：核心部署机制

> 最后核对：2026-08-06
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

共享部署编排使用 `deploy_compat/interface` 中 `com.sickworm.intellij.jugg.deploy.api` 的 `IDevice`、`Apk`、`ApkEntry`、`DexClass`、`ByteString`、`Deploy.Arch` 与 `ILogger`。这些类型保留原调用面以降低迁移 diff，但不链接 ddmlib、Android Studio deployer model 或 shaded protobuf；真实类型仅由 legacy/Quail 版本 compat 实例和 standalone executor 各自转换，interface JAR 不保存 raw converter 或全局 adapter 状态。

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggDeployerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | IDEA 与 standalone 共享入口。决定 install / embedded / incremental，并把单轮设备 lifecycle 委托给共享 orchestrator。 |
| `JuggDeployOrchestrator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt` | 共享设备部署 lifecycle：分片、Apply Changes、agent、restart/start、JVMTI 检查；Host 差异由 `IDeployHost` 注入。 |
| `DeployStateRecover` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployStateRecover.kt` | 设备状态未知或不匹配时恢复基线：direct check、dry deploy、reinstall。 |
| `DeployRetryHandler` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt` | 根据失败原因选择 retry、fallback HOT_FIX、compat deploy、recover 后 redeploy 或停止。 |
| `JuggDeployTask` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt` | 单设备单轮 deploy task。按 `applicationId` 分组，把全量 `JuggDeployData` 裁成 APK-scoped data 后调用 `JuggDeployer`。 |
| `JuggDeployer` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | 通过 `IApplyChangesExecutor` 封装 install、code swap、full swap、deployment cache、overlay id 和 Direct Overlay transport。 |
| `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | 部署文件 facade。维护 changed/compiled/staging/deployed 状态，生成 `JuggDeployData`，reinstall 后 reset。 |
| `DeployDataPlanner` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployDataPlanner.kt` | 从 staging + history 规划部署数据，处理 dex merge 与 compat deploy 组装。 |
| `JuggDeployData` / `DeployItem` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | 最终下发设备的部署数据模型，包含 deploy type、APK 归属、restart 判断、split/filter。 |
| `DirectOverlaySwapTransport` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlaySwapTransport.kt` | Direct Overlay swap transport。只替换 Apply Changes 的 overlay update 动作，不接管部署生命周期。 |
| `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | 通过 `run-as` 原子写入设备 `code_cache/.overlay`，新 overlay id 最后提交。 |
| `DirectOverlayStateChecker` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayStateChecker.kt` | recover 校验 history/cache/device 三路一致；swap 前只校验 device overlay。 |
| `DeployHistoryManager` / `JuggDeploymentService` / `JuggDeploymentCacheStore` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployHistoryManager.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt`, `main/src/main/java/com/sickworm/intellij/jugg/deploy/cache/JuggDeploymentCacheStore.kt` | 两套 checkpoint 来源：Jugg 自有部署历史与项目级 deployment cache。Service 根据 runtime owner、磁盘 generation 或 bound executor 变化失效 Runtime 内存对象，并用当前 Apply Changes executor 从 snapshot 恢复。 |

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

`isNeedRestartApp` 由 hot-fix classes、非空 `isPushOverlayOnly`、APK 根目录 overlay、非空的本轮 Compose resource compile，或 reinstall recover 后的 follow-up replay 决定；`isNeedRestartActivity` 只在非 warm-up、非空、且不需要重启 App 时成立。

正常部署由 `DeployDataPlanner` 从 `DeployFileStateTracker.getCompiledFiles()` 识别 `CompileFile.Type.ComposeResource`，写入瞬态 `isComposeResourceCompiled`。该状态在 commit 前保留，能覆盖正常部署与 retry；不需要从已经丢失来源信息的 `CompileOutput.Type.Asset` 或历史 staging 路径恢复 Compose 身份。Compose 标记只对非空 payload 生效，避免编译成功但最终无产物时空重启。

APK 根目录 overlay 使用最终部署路径判断：`res/**`、`assets/**`、`resources.arsc` 之外的 overlay 都要求重启进程，例如 legacy Compose resource 的 `values/strings.xml` 和 Java SPI 的 `META-INF/services/**`。这个规则不依赖编译阶段类型，因此历史恢复后的部署数据也能得到相同行为。它允许少量无害 false positive；如果 Classpath resource 刻意使用 Android 专属路径名，则存在 false negative。

当前实现对所有满足 `isNeedRestartActivity` 的非空增量部署使用 Android Studio 的 `APPLY_CHANGES_AND_RESTART_ACTIVITY`，即 Full Swap / Apply Changes and Restart Activity，并调用 `JuggDeployer.fullSwap()`。Activity 会重建，`onCreate()` 会再次执行；这与 `Always restart app after deployment` 不同，后者用于额外重启整个 App 进程。`JuggDeployData.deployType=HOT_RELOAD` 是 Jugg 的结果分类，不表示 transport 一定使用不重启 Activity 的 `APPLY_CHANGES`。

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

multi APK 场景下，staging/deployed 的同名资源必须按“目标 APK + relative path”判定是否覆盖；不能只用 `relativeFile.path`，否则主包与 androidTest 都存在 `resources.arsc` 时会互相过滤，导致 full resource push 回读原 APK 资源。

编译产物和 reinstall recover 历史进入 staging 时使用同一逻辑身份规则：相同“目标 APK + relative path”的产物后写覆盖，不同目标 APK 的同路径产物继续共存。恢复历史中缺失 APK scope 的 Dex 优先级低于有明确 scope 的 staging Dex，避免历史目录与新编译目录同时保留同一类定义。

部署成功提交时沿用相同 shadow 规则：staging 产物进入 deployed 前，先移除相同 deploy key 的旧记录；有明确 APK scope 的 staging Dex 同时覆盖相同 relative path 的无 scope 历史 Dex。deployed 不再因物理目录不同保留同一逻辑类的多份记录，避免后续自动 Dex merge 收到重复类型。发生清理时，`DeployFileStateTracker` 会用一条 debug 日志记录清理目的、数量、原因分类及前 20 个旧文件；无 shadow 冲突时不输出该日志。

---

## 4. 核心调用链路

### 4.1 install 链路

```text
JuggDeployerHelper.deploy(isInstall=true)
  -> deployInstall()
  -> JuggDeployData.forInstall(apks)
  -> JuggDeployOrchestrator.execute()
  -> JuggDeployTask.run()
  -> groupByApplicationId()
  -> JuggDeployer.install()
  -> AsDeployerCompat.install()
  -> JuggDeploymentService.storeEntry()
  -> deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
```

deployment cache 固定保存到 `<projectDir>/build/jugg/deploy_cache/.deploy_cache.db`。`JuggDeploymentService` 是项目 Runtime 实例，不再使用 `~/.jugg` 全局 singleton；同一 Runtime 优先读取 `memoryCache`，写入同步更新内存和磁盘 checkpoint。磁盘读写由项目锁串行，写入先落临时文件并 flush，再原子替换目标文件。

install 前会先 stop app，避免用户看到“安装后又被停止”的错觉。安装失败时优先透出 `AdbLogWrapper.realErrorMessage`，不要先改高层错误文案。

### 4.2 incremental deploy 链路

```text
JuggDeployerHelper.deploy(isInstall=false)
  -> deployIncrementalChanges()
  -> DeployFileManager.getDeployData(isWarmUp, isNeedPushResourceApk)
  -> LibraryTestApkBackfillHelper.backfillIfNeeded()
  -> 需要更新 APK: IncrementalDeployHelper.updateApk() + recoverDeployState()
  -> 设备 not ready 或 **跨工程切换**（`LastCompileProjectRegistry` + `isProjectSwitchedThisRun`）: DeployStateRecover.recoverDeployState()
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
  -> LaunchContextFactory 创建本轮基础 LaunchContext
  -> 前置判断 Direct Overlay 是否可尝试
  -> Direct Overlay 可尝试时跳过 SliceDeployHelper；否则按阈值切片
  -> 每个 deploy data 派生 slice LaunchContext + JuggDeployTask
  -> 必要时 push agent / restart app / start app / run androidTest
  -> 必要时检查 JVMTI compat issue
```

`LaunchContextFactory` 统一创建 deviceAdb、install session、installer metadata、Direct Overlay lifecycle facts，以及 deploy prompt/message 回调。IDE compat 门面的所有能力都保留已知 API 链接错误 fallback；session 记录实际成功的 executor，`LaunchContext` 后续直接使用同一 executor 和由它创建的 debugger，不再通过门面分发有状态调用，避免 installer、overlay、cache 与 redefiner 跨 deployer ABI。Direct Overlay 的可尝试判断在切片前完成，判断条件与 transport `canTry()` 保持一致：开关开启、调用方允许、设备当前不是 ready deploy、非 install、deploy data 非空。命中后本轮不再进入 `SliceDeployHelper`；`JuggDeployTask` 只消费完整 `LaunchContext`，不再二次拼装 Direct Overlay 参数。

切片后只有第一个 slice 保留 except overlay check；后续 slice 会跳过，否则同一轮部署中 overlay id 已变化会导致自我冲突。

当原始部署类型是 `APPLY_CHANGES_AND_RESTART_ACTIVITY` 时，非最后一个 slice 会降级为 `APPLY_CHANGES`，只允许最后一个 slice 触发 restart activity，避免中间态 overlay 被进程启动/重载使用。若切片部署已有成功 slice，后续 slice 失败时，返回失败前必须对本轮涉及的 applicationId 执行 `run-as <applicationId> rm -rf code_cache/.overlay`，清理设备端半提交 overlay。

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
              -> except-overlay 规则与 `JuggDeployer.optimisticSwap` 一致：`exceptOverlayId != cache.sha` 则 MISMATCHED（含 history 为空且 cache 有值）
              -> `isSkipExceptOverlayCheck=true`：不比 history 与 cache，仅 cache + 设备校验
              -> MATCHED: SUCCESS
              -> MISMATCHED: FAILED（含 cache 缺失）
              -> UNKNOWN: fallback legacy dry deploy
          -> restart app + waitingForDeployable(默认 3s)
          -> run dry deploy payload
  -> dry deploy 成功: 不重装
  -> dry deploy 失败 / app updated / clean reinstall: install apks
  -> allowDirectOverlayRecover && direct overlay 开关: defer INSTALL 后 launch，跳过 waitingForDeployable(5s)
  -> redeploy / retry 时 `isSkipExceptOverlayCheck=true`，recover 的 `checkRecover` 与 deploy 的 `optimisticSwap` 同样跳过 history 与 cache 对账；reinstall 后 dry check 依赖 skip 与 cache+设备一致
  -> 否则: INSTALL 后 restart + waitingForDeployable(5s)
  -> DeployFileManager.resetAfterReinstall()
  -> follow-up replay 标记 isRecoverReplayAfterReinstall=true，replay 完成后统一 restartApp
```

Direct Overlay recover 只在 `allowDirectOverlayRecover=true` 且 `JuggSettings.isEnableDirectOverlayDeploy` 开启时参与 `tryDirectDryDeploy` / defer launch。`DeployRetryHandler` 在 **direct deploy failed** retry 时传 `allowDirectOverlayRecover=false`：recover 走 legacy（启动 App + Apply Changes dry deploy；reinstall 后 wait online），与 redeploy 的 `isAllowDirectOverlayDeploy=false` 一致。

reinstall recover 不恢复历史资源类型：重装已经停止或替换了旧进程，follow-up replay 只需携带瞬态 `isRecoverReplayAfterReinstall`。Direct Overlay recover 中 `restartApp` 等价于首次启动；普通 recover 中它负责清理重放历史资源后可能残留的运行时缓存。

其它 recover 场景（overlay mismatch、主链路 not ready）保持 `allowDirectOverlayRecover=true`（或来自 `DeployOptions.isAllowDirectOverlayDeploy`）。

### 5.2 retry

| 失败信号 | 行为 |
|---|---|
| transient offline | 等待 ADB transport 恢复，成功后用原 deploy data redeploy。 |
| `REDEPLOY_WITH_COMPAT_MESSAGE` | `appendCompatDeployFiles()` 后 compat redeploy。 |
| `JVMTI_ERROR_UNMODIFIABLE_CLASS` / `app restart` / redefiner/internal error | fallback 到 HOT_FIX 后 redeploy。 |
| `INSTRUMENTATION_FAILED` / `IOException occurred` | 不改 payload，直接重试。 |
| agent no response | 先检测 JVMTI compat；必要时 compat deploy；JVMTI 可用且调用方允许 direct overlay 时，强制重试一次 direct overlay，避免依赖 agent responses。 |
| deploy timeout | 先检测 JVMTI compat；必要时 compat deploy；timeout 规则继续按下方计数策略处理。 |
| overlay id mismatch / class not found / direct deploy failed | recover deploy state 后 redeploy。direct deploy failed 时 recover 禁用 direct overlay（legacy + `isAllowDirectOverlayDeploy=false`）。 |
| install `INSTALL_FAILED_INVALID_APK` | uninstall 当前 applicationId 集合后重新 install。 |
| 用户限制、设备丢失、APK install 失败、embedded APK 冲突 | 停止 fallback，向上暴露失败。 |

timeout 规则：overlay 数超过首片阈值时先降低 slice size；否则前两次等待后重试，第三次尝试 reinstall，超过次数停止。

---

## 6. Direct Overlay 旁路

### 6.1 触发条件

`LaunchContext.isDirectOverlayEnabled = settingsEnabled && isAllowedByCaller && (!isDeviceReadyDeploy || forceDirectOverlayDeploy)`。

Direct Overlay 是离线/非 ready 场景下的 overlay 写入旁路，不替代在线 HOT_RELOAD。外层在切片前判断是否可尝试 Direct Overlay；真正进入 swap 前仍要求 Android O 及以上、deployment cache 存在、startup agent 元数据可用或允许跳过、设备当前 overlay id 与预期一致。

`isAllowedByCaller` 来自外层 lifecycle；默认主部署链路允许，特殊调用方可显式关闭。Direct Overlay 只替换 overlay update transport，后续 start/restart/androidTest 仍由 `JuggDeployerHelper.runTask()` 收口。

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
          -> OverlayUpdateBuilder 按 qualifiedPath 去重并保留第一份，避免 full resource push 中原 APK 文件覆盖增量资源
          -> request builder 按 overlay path 去重并保留第一份，避免 new/modified class 重叠导致 ZIP duplicate entry
      -> DirectOverlayWriter.write()
          -> zip overlay files
          -> push /data/local/tmp/jugg/direct-overlay-*.zip
          -> 以 no-fallback shell 执行 run-as package sh -c apply script，避免非幂等脚本被 ADB fallback 重入
          -> 删除旧 id
          -> 启动 heartbeat，避免 full push 长时间无输出触发 ADB inactive timeout
          -> 删除本次 payload 覆盖的旧文件
          -> full resource push 不清理 base.apk 目录，避免切片部署删除前序 slice；直接 unzip 当前 slice 内容
          -> base install 空 overlay id 场景跳过 payload cleanup，避免清数据/NO_DIR 首次 full push 生成大量无效 rm 命令
          -> unzip files
          -> chmod *.dex 0444
          -> 最后写新 id
      -> JuggDeploymentService.storeEntry()
  -> direct 返回 null: fallback 旧 Apply Changes
```

旧 Apply Changes 进入 `JuggDeployTask.perform(APPLY_CHANGES)` 时，只有存在 class 变更且本轮不需要重启 App，才创建 Android Studio debugger redefiner；空变更或纯 overlay/update-apk 场景不传 debugger redefiner，避免 AS deployer 在无 class swap 时误走 debugger redefine 能力。

base install cache 对应的 expected device overlay id 为空字符串；非 base install 才要求设备 overlay id 等于 cache 中的 sha。

### 6.3 dirty 语义

- writer 在修改 overlay 目录前失败：返回 `SKIPPED`，允许 fallback 旧 Apply Changes。
- writer 已开始修改 overlay 目录后失败，或脚本重入时发现 overlay id 已缺失：返回 `FAILED_DIRTY` 并抛 `DirectOverlayDirtyException`，不再继续旧 Apply Changes，避免半提交状态上做伪回退。

---

## 7. 隐形约束

- `overlay id` 是部署一致性的核心 checkpoint：Jugg history、项目级 deployment cache、设备 overlay 目录任一不一致，都可能导致重装或 recover。
- deployment cache 属于项目状态；`memoryCache` 只能属于单个项目 Runtime。IDEA/standalone 切换时必须失效旧 Runtime 内存缓存，并在项目锁内读取磁盘最新快照，不能复用跨项目或跨 Runtime 缓存。
- `exceptOverlayIds` 防止同 package 在不同项目/不同设备间串状态；recover 或同轮切片会按需跳过检查。
- 切片部署不能留下半提交 overlay：一旦前序 slice 已成功而后续 slice 失败，必须先清理设备端 `code_cache/.overlay` 再返回失败。
- `JuggDeployData.filterForApks()` 只给 deployer transport 用；不要用裁剪后的 scoped data 更新全局文件状态或历史。
- `DeployItem.targetApkPaths` 表示真实部署目标；`apkPath` 仍保留旧单 APK 锚点。判断资源/overlay 归属时优先看 `targetApkPaths`。
- self-targeting library Test APK backfill 成功安装后，必须立即把新 overlay ids merge 到 `deployHistoryManager.lastDeployOverlayIds`，否则第一轮 replay 会误判状态不匹配并重装。
- compat deploy 会去掉原 res/asset overlays，追加 enable flag，并按资源 overlay 生成 resource APK deploy item。
- APK 根目录 overlay 必须重启进程；Activity restart 无法可靠清除 ClassLoader、legacy Compose resource 或 `JarURLConnection` 缓存。
- 现代 Compose resource 即使最终路径位于 `assets/**` 也必须重启进程；`AssetManager` / Compose runtime 缓存不能依赖 Activity restart 清理。
- `CompatDeployHelper` 对 API < 30、设备兼容记录以及所有 HarmonyOS 设备返回 true；HarmonyOS 通过非空的 `hw_sc.build.platform.version` 属性识别，不持久化为手动 Force 记录。
- dex merge 阈值是 `DeployDataPlanner.MAX_DEPLOYED_DEX_COUNT = 1000`；超过阈值时把 staging dex + 未 staging 的历史 dex merge，失败则保留原数据继续部署。
- transient offline 的设计目标是在失败点附近恢复：shell/deployer 层原地等待并重试一次，编排层只处理已经冒泡的 offline 失败。
- install 路径遇到 transient failure 可能从 DELTA 升级为 FULL install；不是所有 install 失败都应该进入 incremental fallback。

---

## 8. 排查入口

| 现象 | 优先入口 |
|---|---|
| `Deploy state not match, start reinstalling app...` | `DeployStateRecover.tryDryDeploy()`、`DirectOverlayStateChecker.checkRecover()` |
| `OVERLAY_ID_MISMATCH` 或 “state unknown to Studio” | `JuggDeployer.optimisticSwap()` |
| Direct Overlay 未触发 | `LaunchContext.logDirectOverlayEnabled()`、`DirectOverlaySwapTransport.canTry()` |
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
