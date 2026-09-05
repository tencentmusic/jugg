# 部署系统：核心部署机制

> 最后核对：2026-09-05
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只回答三个问题：

- **核心类在哪里**：AI 先知道读哪个类，不在仓库里盲跳。
- **主链路怎么走**：把跨类调用链和状态机写清楚，减少逐层 Go to Definition。
- **代码不显眼的约束是什么**：overlay id、Direct Overlay、multi APK、retry/recover 的设计边界。

不展开编译产物如何生成；影响分析看 `03_deploy_data_generator.md`，端到端 Run 链路看 `03_deploy_complete.md`，JVMTI 细节看 `03_runtime_jvmti.md`，系统应用首次落入 `/system` 的约束看 `03_deploy_system_app.md`。

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
| `AppSandboxExecutor` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AppSandboxExecutor.kt` | 统一 app 私有目录命令；严格探测 Apply Changes 的 `run-as`、UID 与 SELinux label 前提，并在不兼容时固定普通 shell、root adbd 或非交互 `su` 模式与真实 `dataDir`。 |
| `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | 通过 app sandbox 原子写入设备 `code_cache/.overlay`，新 overlay id 最后提交。 |
| `DirectAppSandboxDeployTransport` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/DirectAppSandboxDeployTransport.kt` | 在 AS deployer 前接管 `run-as` 不兼容应用的增量 overlay payload，组合 Direct Overlay、Jugg JVMTI redefine 与重启降级。 |
| `DirectOverlayStateChecker` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayStateChecker.kt` | recover 校验 history/cache/device 三路一致；swap 前只校验 device overlay。 |
| `DeployHistoryManager` / `JuggDeploymentService` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployHistoryManager.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt` | 两套 checkpoint 来源：Jugg 自有部署历史与 Android Studio deployment cache。Direct Overlay recover 同时依赖二者。 |

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

当前实现对所有满足 `isNeedRestartActivity` 的非空增量部署使用 `APPLY_CHANGES_AND_RESTART_ACTIVITY` 语义。Android Studio transport 调用 `JuggDeployer.fullSwap()`；`run-as` 不兼容的 Direct app sandbox transport 在 class redefine 成功后使用请求级 relaunch 标志重建 Activity，不重新进入 `fullSwap/overlaySwap`。两条路径都保留 App 进程并让 `onCreate()` 再次执行；这与 `Always restart app after deployment` 不同，后者用于额外重启整个 App 进程。`JuggDeployData.deployType=HOT_RELOAD` 是 Jugg 的结果分类，不表示 transport 一定使用不重建 Activity 的 `APPLY_CHANGES`。

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
  -> runTask()
  -> JuggDeployTask.run()
  -> groupByApplicationId()
  -> JuggDeployer.install()
  -> AsDeployerCompat.install()
  -> JuggDeploymentService.storeEntry()
  -> deployHistoryManager.lastDeployOverlayIds = launchResult.overlayIds
```

install 前会先 stop app，避免用户看到“安装后又被停止”的错觉。安装与增量部署失败时优先透出 `AdbLogWrapper.realErrorMessage`，不要先改高层错误文案；`run-as: package not debuggable` 等设备侧明确原因必须覆盖 deployer 的通用失败信息。

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

兼容部署的 `resource.ap_` 保留 JVM 14+ ZipFS 快速更新，但每次生成或增量修改都在同目录唯一临时文件上完成，ZipFS 关闭成功后才替换正式文件。这样异常后不会再次打开同一个残留 ZipFS URI，也不会把半生成文件发布为缓存。首次生成只在最终返回部署数据时读取一次完整 APK 字节。

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
  -> 复用本轮 sandbox 能力，前置判断普通 Direct Overlay 或 Direct app sandbox 是否可尝试
  -> 任一 Direct 通道可尝试时整批部署；否则按原阈值切片
  -> 每个 deploy data 派生 slice LaunchContext + JuggDeployTask
  -> 必要时 push agent / restart app / start app / run androidTest
  -> 必要时检查 JVMTI compat issue
```

`LaunchContextFactory` 统一创建 deviceAdb、install session、installer metadata、Direct Overlay lifecycle facts，以及 deploy prompt/message 回调。切片前复用两种 transport 的 `canTry()`：普通 Direct Overlay 沿用开关、调用方许可和 ready/force 条件；Direct app sandbox 在 Android 8+ 复用本轮 `LaunchContext` 缓存的 sandbox 能力判断，目标 APK 中任一应用与 Apply Changes 不兼容时也整批部署，不受普通 Direct 开关限制。非 install、非空 payload 命中任一 Direct 通道后不再进入 `SliceDeployHelper`；官方 Apply Changes 保留现有切片。Direct 不新增分片或分片结果汇总。`JuggDeployTask` 仍按 applicationId 分组处理整批数据。

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
| `OutOfMemoryError` / `Java heap space` / `GC overhead limit exceeded` | 不在当前 IDE 进程重试或自动 Gradle fallback；清理兼容资源 APK 缓存，并提示重启 Android Studio、增大 IDE heap 或执行 Gradle install。 |
| `INSTRUMENTATION_FAILED` / `IOException occurred` | 不改 payload，直接重试。 |
| agent no response | 先检测 JVMTI compat；必要时 compat deploy；JVMTI 可用且调用方允许 direct overlay 时，强制重试一次 direct overlay，避免依赖 agent responses。 |
| deploy timeout | 先检测 JVMTI compat；必要时 compat deploy；timeout 规则继续按下方计数策略处理。 |
| overlay id mismatch / class not found / direct deploy failed | recover deploy state 后 redeploy。direct deploy failed 时 recover 禁用 direct overlay（legacy + `isAllowDirectOverlayDeploy=false`）。 |
| install `INSTALL_FAILED_INVALID_APK` | uninstall 当前 applicationId 集合后重新 install。 |
| 用户限制、设备丢失、APK install 失败、embedded APK 冲突 | 停止 fallback，向上暴露失败。 |

timeout 规则：overlay 数超过首片阈值时先降低 slice size；否则前两次等待后重试，第三次尝试 reinstall，超过次数停止。

---

## 6. Direct Overlay 旁路

### 6.0 run-as 不兼容应用的 Direct transport

`JuggDeployerHelper` 先用可回滚写入和唯一成功标记判断 Android Studio Deployer 的 `run-as` 前提；只有 UID 位于 `10000..19999`，且 `run-as` 新建探针的 SELinux context 与既有 `code_cache` context 一致，才视为兼容。无成功标记、UID 越界或 context 不一致时，`JuggDeployer.optimisticSwap()` 在普通 Direct Overlay 和 AS deployer 之前进入 `DirectAppSandboxDeployTransport`。该路径不受“设备是否 ready”或 Direct Overlay 用户开关限制，因为它是 Apply Changes 前提不成立时的 增量 overlay 替代通道。

同一轮部署从 `LaunchContext` 取得并复用一个已解析的 `AppSandboxExecutor`。它在 PackageManager 的真实 `dataDir` 依次探测普通 shell、最多一次 adb root 并等待同一 serial 重连、非交互 `su 0`/`su -c`；选定后 Direct Overlay、startup agent 与 Hot Reload 不再重新判断模式。transport 先准备 Jugg startup agent 并提交 Direct Overlay。纯方法体变化对主进程执行 class redefine；Android 11+ 的普通资源/asset 或方法体与资源混合变化使用同一 dynamic 请求刷新宿主 Resources，并按上层语义重建 Activity。请求成功时保留进程，attach、资源刷新或其他可恢复失败通过 `Result.needsRestart` 让 `JuggDeployerHelper` 重启应用。新类、结构变化、APK 根目录 overlay、兼容部署和 APK 更新保持原有重启或安装路径。

Direct 权限模式创建的文件可能只有静态 `app_data_file:s0`，不能直接复用 `restorecon -RF code_cache`：它会丢失应用目录的动态 MCS categories，并使 `platform_app` 无法执行 JVMTI agent。executor 先把普通 overlay/request 文件修正为既有 `code_cache` 的完整 context，再把其中的 `.so` 标记为 Android appdomain 允许执行的 `apk_data_file:s0`；修复脚本输出通过内部边界标记与业务命令结果隔离，避免 `restorecon`/`chcon` 的成功诊断污染 `success` 协议。

Direct transport 不再以 class-only 白名单拒绝 overlay，`data.isFullRes` 原样传递到 `DirectOverlayWriteRequestBuilder`。Manifest/native library 的 APK 改写、重签和 reinstall 仍由上游部署流程负责，随后可重放 overlay。Direct 权限不可用或 deployment cache 缺失时提前失败，不回落到必然失败的 AS deployer；ADB transport/offline 异常继续按原有 transient 语义传播。

准备 Jugg startup agent 后，Direct transport 写入 `code_cache/.jugg_direct_resource_overlay` 标记。Android 11+ 的 startup agent 通过 `LoadedApk.getResources()` hook 和迁移的 `ResourceOverlays` 加载 `.overlay/*.apk` 下的 `resources.arsc`、`res/`、`assets/`；资源 loader 只加入真实宿主 APK 对应的 Resources，不污染 WebView 等非宿主资源。运行中提交资源后，dynamic agent 更新 loader providers、补挂现存宿主 Resources，再重建 Activity；连续资源更新不会复用旧 provider。兼容部署标记存在时保持原资源 APK 路径，Android 8～10 继续通过进程重启生效。

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
          -> 以 no-fallback shell 经 AppSandboxExecutor 执行 apply script，避免非幂等脚本被 ADB fallback 重入
          -> 删除旧 id
          -> 启动 heartbeat，避免 full push 长时间无输出触发 ADB inactive timeout
          -> 删除本次 payload 覆盖的旧文件
          -> full resource push 跳过 base.apk 下逐文件删除，直接 unzip 整批资源；保留先前 Dex 与其他未更新 overlay
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

Direct 写入脚本虽然定期输出 heartbeat，仍通过 `execAdbShellScriptNoFallback()` → `invokeAdbShellCmd()` 使用带 `5 SECONDS` 超时参数的 ADB 调用。Heartbeat 不等于无限等待；超时或断连仍需按写入状态处理失败。Direct 权限模式在命令退出时递归修复整个 `code_cache`，这是随文件量增长的性能检查项，不改变本轮整批部署或失败契约。

### 6.4 Direct app sandbox 与官方 Apply Changes 的能力边界

Direct app sandbox 是 Android Studio Apply Changes 的 app sandbox 前提不成立时的最小替代通道，不是官方 Deployer 的完整复刻。Android 平台仍具备 JVMTI 和资源加载能力，但官方通道会先拒绝不满足 `run-as`、普通 UID 或 SELinux context 契约的应用；Direct 因此自行完成 overlay 写入、dynamic agent 请求、资源刷新和 Activity 重建。影响通过 `applyChangesCapability == INCOMPATIBLE` 的入口门禁隔离：兼容应用继续进入官方 Apply Changes，普通 Direct Overlay 也保持独立 transport。

当前已覆盖纯方法体、普通资源/asset、方法体与资源混合变化，以及提交后冷启动继续生效。以下差异是当前实现边界，不能仅因常用验收场景通过就认为两条通道完全等价：

| 维度 | Direct app sandbox 当前边界 | 结果或回退 |
|---|---|---|
| Android 版本 | 运行中普通资源刷新依赖 Android 11+ `ResourcesLoader`。 | Android 8～10 提交 overlay 后重启进程，由 startup agent 加载。 |
| 进程范围 | `pidof <package>` 选择主进程的一个 PID，并与本轮已知 PID 交叉确认；单次 dynamic 请求只附加该进程。 | 独立进程不会在同一轮获得在线 class/resource 刷新，需要相应进程重新启动。 |
| Activity 范围 | 遍历主进程 `ActivityThread.mActivities`，重建全部存活 Activity；反射读取失败时从窗口关联 Activity 回退。 | 覆盖同进程的后台 Activity、其他 task 和多窗口实例；独立进程仍需在对应进程重启后加载 overlay。 |
| class payload | 只在线处理 `hotReloadModifiedClasses`，且每份 Dex 必须能唯一映射到一个 class descriptor。 | 新类、结构变化、不可修改类或多 class Dex 进入 overlay + 进程重启。官方通道拥有更完整的 PID/redefiner 与 Dex 元数据编排，但同样受 JVMTI 结构重定义限制。 |
| 资源类型 | 在线刷新只覆盖 `res/**`、`assets/**` 和 `resources.arsc` 表示的普通宿主资源。 | APK 根目录、legacy/现代 Compose 等依赖 ClassLoader 或进程内缓存的资源仍要求重启进程。 |
| 批次模型 | 普通 Direct 与 Direct app sandbox 都整批部署，不进入 `SliceDeployHelper`。 | 没有官方 Apply Changes 的切片进度、分片重试和分片结果汇总；大 payload 的失败粒度更粗。 |
| 状态前提 | 必须已有 deployment cache，且预期 overlay id 与设备状态匹配。 | cache 缺失、状态不匹配或 Direct 权限不可用时明确失败并进入既有 recover/reinstall，不能在 Direct transport 内重建基线。 |
| 协议与诊断 | 使用精简的 V3 文本请求/结果协议，Host 轮询结果，主线程资源应用也有独立超时。 | phase 诊断和调试器协同少于官方 Deployer；heartbeat 不取消 ADB 超时，超时、断连和无结果仍按失败处理。 |
| 平台适配 | 资源刷新依赖 `ResourcesManager` 内部引用、宿主 APK 路径过滤和 `ResourcesLoader`。 | OEM 或 framework 差异导致刷新失败时降级为进程重启，不承诺覆盖官方 Agent 的全部版本适配。 |

Manifest、native library 等 `updateApkFiles` 继续由 APK 改写、重签和安装链路处理，不属于 Direct dynamic agent 需要补齐的能力。Direct 当前的设计目标是让官方 app sandbox 通道不可用的应用获得常用增量部署结果，同时保留原有 lifecycle、recover 和安装边界。

---

## 7. 隐形约束

- `overlay id` 是部署一致性的核心 checkpoint：Jugg history、Studio deployment cache、设备 overlay 目录任一不一致，都可能导致重装或 recover。
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
