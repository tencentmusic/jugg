# 系统应用 Rootless 兼容部署方案

## 1. 背景

GitHub Issue #45 报告系统应用 `com.oplus.games` 无法增量部署。现场已经确认：

- 应用是可调试系统应用，但 `run-as` 无法访问 `/data/user/0/com.oplus.games`。
- 设备为量产 `user` ROM，`ro.debuggable=0`。
- `adb root` 返回 `adbd cannot run as root in production builds`。
- `adb shell` 为 `uid=2000(shell)`、SELinux context 为 `u:r:shell:s0`。
- 普通 shell 无法进入应用 dataDir，设备也没有可用的非交互 `su`。

当前 `DirectAppSandboxDeployTransport` 在 Apply Changes 不兼容时依赖 `AppSandboxExecutor` 通过普通 shell、root adbd 或 `su` 写入 app sandbox，并在 Direct 路径准备 startup JVMTI agent。上述权限通道全部不可用时，部署会在写 overlay 前失败。

用户提出的方向是：Host 把增量产物放到 shell 可写的临时目录，重启 App，由 App 在启动阶段把产物复制到自己的 `code_cache` 并加载。经过现有实现核对，本方案应直接复用兼容部署，而不是在启动阶段复制 startup agent 后再次重启。

## 2. 已确认事实

### 2.1 兼容部署已经具备启动期加载能力

- `DeployDataPlanner.appendCompatDeployFiles()` 会设置 `isCompatDeploy=true`、`isPushOverlayOnly=true`，为非空部署追加 compat enable flag，并把普通 resource/asset overlay 转换为 `resource.ap_`。
- `GradleApplicationInjector` 能把 `BootstrapApplication`、`BootstrapAppComponentFactory` 和 Jugg runtime 注入目标 APK。
- `BootstrapApplication.attachBaseContext()` 在原始 Application、Activity、Service 启动前调用 `HotfixLoader`。
- `HotfixLoader` 已经能从 app 私有 `code_cache/.overlay` 加载兼容 Dex 与 `resource.ap_`。
- `JuggDeployData.isCompatDeploy` 的用户可见结果已经是 `COMPAT_HOT_FIX`，并且现有语义本来就要求重启 App。

### 2.2 当前 agent 行为

- `JuggJvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy()` 对 Apply Changes 不兼容应用已经返回 false。
- 当前系统应用 Direct 路径仍会在 `DirectAppSandboxDeployTransport.prepareStartupAgent()` 内主动调用 `pushAgentToApp()`。
- 如果在 Application 启动时才复制 startup agent，系统只能在下一次进程启动时加载该 agent，因此至少需要额外一次重启。
- 兼容部署不需要 dynamic redefine、startup JVMTI agent 或 `am attach-agent`；保留 APK 内的 Jugg Java runtime 即可。

### 2.3 临时目录边界

- Android 设备侧使用 `/data/local/tmp`，不是通用 `/tmp`。
- Jugg 当前已经使用 `/data/local/tmp/jugg` 暂存 agent bundle 和 Direct Overlay ZIP。
- `/data/local/tmp` 文件通常由 shell 创建并归 shell 所有。目标 App 是否可读取仍受厂商 SELinux policy 影响，必须在目标 OPlus user ROM 上取得真实证据。
- 即使 App 可以读取，通常也不应要求 App 删除 shell 所有的临时目录；清理由 Host 在确认成功后完成。

## 3. 目标

在满足以下条件时，为系统应用提供不依赖 root/su 的兼容增量部署：

1. Apply Changes 的 `run-as` 能力不兼容。
2. `AppSandboxExecutor` 无法取得普通 shell、root adbd 或 `su` app sandbox 写权限。
3. 当前 APK 已注入 Jugg compat runtime。
4. 设备 API 和现有 compat deploy 能力满足要求。

用户可观察行为：

- Jugg 自动降级为 `COMPAT_HOT_FIX`。
- Host 暂存兼容部署 payload 后只重启 App 一次。
- App 在启动阶段导入并加载本轮 Dex、resource 和 asset 变化。
- 部署成功后显示兼容热修复成功；失败时返回可定位原因，不能伪造成功或推进部署历史。

## 4. 非目标

- 不在 rootless 路径支持 HOT_RELOAD、运行中 class redefine 或 Activity-only recreate。
- 不在启动阶段复制、安装或 attach JVMTI agent。
- 不修改普通 Apply Changes、已有 root/su Direct transport 或普通 Direct Overlay 的行为。
- 不在本任务中设计通用 ContentProvider、socket 或 Binder 文件传输框架。
- 不为缺少 compat runtime 的存量 APK 自动设计新的完整安装状态机；首版应明确提示先完成一次带 compat runtime 的完整 Gradle build/install。
- Manifest、native library 和其它需要改写 APK 的变化继续沿用完整 APK 更新流程。

## 5. 核心设计决策

### 5.1 固定使用兼容部署

Rootless app-side import 只接受 compat payload。Host 必须先通过现有 `DeployFileManager.appendCompatDeployFiles()` / `DeployDataPlanner.appendCompatDeployFiles()` 把原始数据转换为兼容部署数据。

原因：

- 兼容部署本身就需要重启，不新增用户可见损失。
- `resource.ap_` 虽有生成耗时，但已有稳定的启动期加载实现。
- 不需要解决 startup agent so 的复制、owner、SELinux executable context、版本管理和二次重启。
- 复用现有 `COMPAT_HOT_FIX` 结果语义，不新增部署类型和设置项。

### 5.2 完全不依赖 agent

Rootless compat 路径禁止调用：

- `DirectAppSandboxDeployTransport.prepareStartupAgent()`
- `JuggJvmtiAgentManager.pushAgentToApp()`
- `DirectHotReloadWriter`
- `am attach-agent`
- startup agent 可用性检测与 JVMTI compat flag 轮询

这里的“不 push agent”不等于移除 APK 内 runtime。`BootstrapApplication`、`BootstrapAppComponentFactory`、`HotfixLoader` 和相关 compat runtime 仍由完整 Gradle 构建注入 APK。

### 5.3 一次重启完成导入和加载

导入入口放在 `BootstrapApplication.attachBaseContext()`：

```text
HotfixLoader.init(base)
  -> RootlessCompatDeployImporter.importPending(base)
  -> HotfixLoader.isNeedEnableHotfix()
  -> HotfixLoader.install(base)
  -> super.attachBaseContext(base)
  -> 初始化原始 Application
```

Importer 必须在 `HotfixLoader.isNeedEnableHotfix()` 前完成提交，使本次进程启动直接加载新 overlay。不得放到原始业务 Application 的 `onCreate()`。

## 6. 端到端流程

### 6.1 首次普通部署失败后的兼容重试

```text
原始增量数据
  -> DirectAppSandboxDeployTransport 发现 AppSandboxExecutor.UNAVAILABLE
  -> 返回明确的“需要 rootless compat redeploy”信号
  -> DeployRetryHandler 复用既有 compat retry
  -> deployFileManager.appendCompatDeployFiles(originalData)
  -> 使用 compat data 重新进入 deploy
```

优先复用已有 `REDEPLOY_WITH_COMPAT_MESSAGE` 状态机或建立类型明确、作用域更小的等价信号。不得让该场景继续进入“Direct deploy failed -> recover normal way”，因为 recover 不会改变量产 ROM 的权限条件。

### 6.2 Host staging

compat retry 命中 sandbox unavailable 后，不进入现有 privileged Direct transport，改走 rootless compat staging：

```text
OverlayUpdateBuilder
  -> DirectOverlayWriteRequestBuilder 或契约一致的最小复用
  -> 生成 package-scoped pending archive
  -> adb push /sdcard/Android/data/<package>/files/jugg/rootless-compat/<requestId>/payload.zip
  -> 写 metadata/checksum
  -> 最后写 ready 标记
```

建议 metadata 至少包含：

- protocol version
- requestId
- packageName
- expected overlay ID
- next overlay ID
- payload SHA-256
- full resource push 标记
- 创建时间

目录和文件使用最小可读权限，不使用全局 `chmod 777`。路径必须 package-scoped 和 request-scoped，禁止不同应用或不同请求互相覆盖。

### 6.3 App 启动期导入

新增职责明确的 importer，执行：

1. 只处理当前 package 对应的唯一 ready 请求。
2. 校验 protocol、packageName、requestId、payload digest 和 expected overlay ID。
3. 拒绝绝对路径、`..`、反斜杠逃逸、符号链接和重复危险条目。
4. 解压到 app 私有 `code_cache` 下的 request staging 目录。
5. 复用 Direct Overlay 的 cleanup、full resource push 和 overlay ID 规则；不能直接覆盖正式目录。
6. 全部文件完成后原子提交到 `code_cache/.overlay`，overlay ID 最后写入。
7. 多进程通过 app 私有文件锁串行导入；其它进程只消费已经提交的 overlay。
8. 通过包含 requestId 的稳定日志协议输出成功或失败结果。

导入失败时：

- 保留原有 `.overlay` 和 overlay ID。
- 不留下可被 `HotfixLoader` 识别为成功的新 enable flag。
- 输出具体失败阶段和原因。
- 不删除 Host staging，便于 Host 清理或诊断。

### 6.4 重启、确认和状态提交

当前 `JuggDeployer.optimisticSwap()` 会在 transport 返回后立即更新 deployment cache，而 rootless payload 只有 App 重启并成功导入后才算提交。因此必须调整 rootless 路径的提交时机：

1. staging 成功只返回 pending 状态，不视为最终部署成功。
2. `JuggDeployerHelper` 执行一次正常 App restart。
3. Host 根据 requestId 等待 App 启动期成功/失败日志，设置有界超时。
4. 只有收到成功结果后，才更新 `IJuggDeployerDeploymentService`、deploy history 和 `DeployFileManager.commit()`。
5. 成功后由 Host 删除 `/data/local/tmp` 对应请求。
6. 失败或超时时返回部署失败，不推进任何全局 lifecycle 状态；Host best-effort 清理临时请求。

不得仅以进程启动成功、Activity 出现或 `am start -W` 成功代替 importer 成功结果。

## 7. 模块和文件范围

实现前由执行者再次核对当前代码，保持以下责任边界；如果发现需要超出范围的架构变化，应停止并报告，不得静默扩大。

### 7.1 `main`

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployDataPlanner.kt`
  - 复用现有 compat payload 生成，不重复实现 resource/asset 转换。
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriteRequest.kt` 或当前实际定义文件
  - 仅在 rootless archive 需要共享稳定 payload metadata 时扩展；避免污染普通 Direct 路径。
- 可新增一个最小 Host 侧 rootless pending archive 模型/生成器，放在 `deploy/direct` 或更贴合现有包结构的位置。
  - 负责确定性协议、ZIP 和 metadata 生成，不依赖 IDE API。

### 7.2 `idea`

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/DirectAppSandboxDeployTransport.kt`
  - sandbox unavailable 时区分原始数据与 compat retry；原始数据触发 compat retry，不再直接按普通 Direct failure recover。
  - compat rootless 路径不得调用 `prepareStartupAgent()`。
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt`
  - 接入 rootless staging，并避免在 App 确认前提前更新 deployment cache。
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`
  - 在既有 restart 生命周期中等待 requestId 对应的 importer 结果，再决定 runTask 是否成功。
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt`
  - 复用或收敛 rootless sandbox unavailable -> compat data redeploy 分支，确保不进入无效 recover 循环。
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/LaunchContext.kt`、`LaunchResult` 或更小的现有状态模型
  - 仅在跨 transport/restart 传递 pending requestId 和延迟提交数据确有必要时增加最小状态；禁止引入通用 callback/factory 框架。

如果 rootless staging 与 privileged Direct transport 的职责差异已形成明确边界，允许新增一个小型 `RootlessCompatDeployTransport`；否则优先局部复用，避免额外抽象。

### 7.3 `jvmti_agent` runtime

- `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/BootstrapApplication.java`
  - 在 `HotfixLoader.init()` 后、能力判断前调用 pending importer。
- 新增最小 `RootlessCompatDeployImporter`（最终名称以现有包命名为准）。
  - 负责协议校验、文件锁、安全解压、私有 staging、原子提交和结果日志。
  - 新公共类必须添加英文介绍性注释，代码注释不得使用中文。

### 7.4 文档

功能落地后同步：

- `docs/ai_knowledge/03_deploy_system_app.md`
- `docs/ai_knowledge/03_deploy_core.md`
- `docs/ai_knowledge/03_runtime_jvmti.md`
- `docs/ai_knowledge/98_code_map.md`

检查 `docs/wiki` 的 Direct Overlay、Hot Fix、系统应用或兼容部署页面；存在受影响用户文档时同步中英文镜像。

## 8. 失败和回退策略

| 失败点 | 行为 |
|---|---|
| 当前 APK 未注入 compat runtime | 明确失败并提示先进行一次带兼容 runtime 的完整 Gradle build/install；首版不自动设计新的安装状态机 |
| `/data/local/tmp` 对目标 App 不可读 | 明确失败并保留原因；不回落到必然失败的 Apply Changes/root/su；ContentProvider/socket 不在本任务范围 |
| payload push/校验失败 | 不重启或不提交，清理 Host staging |
| expected overlay ID 不一致 | App 拒绝导入，Host 返回 mismatch，不推进 cache/history |
| 解压、空间、路径或 checksum 失败 | 保留旧 overlay，输出 requestId 和阶段，Host 返回失败 |
| App 启动失败或结果超时 | 不提交 deployment cache/history，best-effort 清理 pending |
| 日志结果出现冲突或重复 | requestId 精确匹配，单个请求只接受一个终态 |

仅对已知、可恢复且改变失败条件的错误重试一次。权限条件不变时禁止循环 root、su、recover 或重复重启。

## 9. 验证与测试计划

本功能保护稳定且用户可见的部署行为，通过测试价值门禁。必须遵循 TDD，先取得当前 sandbox unavailable 最终失败的自动化或稳定 Flow 证据，再实现生产逻辑。

### 9.1 L1：协议和原子导入

优先扩展现有 Direct Overlay writer/builder owner；确有独立协议边界时新增测试。

覆盖：

- pending archive metadata 和 SHA-256 正确。
- ZIP path traversal、绝对路径、重复危险条目被拒绝。
- expected overlay ID mismatch 不修改正式 overlay。
- 解压中断不产生半提交。
- overlay ID 最后提交。
- full resource push 与增量 cleanup 语义与现有 Direct Overlay 一致。
- 多进程/重复调用下单次提交和幂等行为。

### 9.2 L2：部署分支与生命周期

优先扩展：

- `idea/src/test/java/com/sickworm/intellij/jugg/deploy/hotreload/DirectAppSandboxDeployTransportTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/DeployRetryHandlerTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelperDeployFlowTest.kt`

稳定断言：

- sandbox unavailable 的普通数据只触发一次 compat redeploy。
- compat retry 走 rootless staging，不调用 startup agent、dynamic agent 或 AS Apply Changes。
- 一轮只重启一次。
- importer 成功后才更新 deployment cache 和 deploy history。
- importer 失败/超时不提交状态，也不进入 recover 无限循环。
- run-as/root/su 可用时原路径保持不变。

测试应断言用户可见结果、状态提交和关键顺序，不仅断言某个私有方法没有被调用。

### 9.3 L3：主流程回归

部署编排变更必须在现有 `TopLevelFlowTest` 或仓库认可的等价 L3 Flow owner 中增加：

```text
量产 user ROM 能力模型
  + system app / Apply Changes incompatible
  + sandbox unavailable
  + compat runtime available
  -> compile success
  -> rootless compat staging
  -> one restart
  -> importer success
  -> COMPAT_HOT_FIX success
```

同时覆盖 importer failure，确认顶层结果失败且历史不前进。

### 9.4 定向验证

- 执行新增/受影响的 L1、L2、L3 定向测试，禁止无 `--tests` 的全量 `:main:test` / `:idea:test`。
- 执行 `./gradlew :idea:compileKotlin`。
- 若修改 jvmti runtime/bundle，按现有约定递增 `agentVersion` 仅在 bundle 内容契约要求时进行；本方案不 push agent，不得为了版本整齐无条件递增。
- 在真实 OPlus user ROM 上验证：
  - App 可通过 `Context.getExternalFilesDir(null)` 读取 `/sdcard/Android/data/<package>/files/jugg/rootless-compat/...`。
  - 首次带 compat runtime 的完整安装后，源码和资源变化均可通过一次重启生效。
  - 无 `adb root`、无 `su`、无法 `run-as` 的条件保持不变。

如果当前环境无法取得目标真机，必须明确记录自动化证据与尚未验证的 SELinux 风险，不能宣称 Issue 已完全解决。

## 10. 验收标准

1. 在 `user` ROM、`ro.debuggable=0`、adbd 不可 root、无 su、run-as 不可用的系统应用上，Jugg 可以自动进入 rootless compat deploy。
2. 稳态增量部署只重启 App 一次，结果类型为 `COMPAT_HOT_FIX`。
3. Rootless 路径不 push/copy/attach startup 或 dynamic JVMTI agent。
4. Dex、resource、asset 在 App 启动后生效；Manifest/native library 仍走完整 APK 更新。
5. App 确认导入成功前，deployment cache、deploy history 和文件状态不前进。
6. 任意 staging/import 失败都保留旧 overlay，返回清晰错误且不存在无限 recover/retry。
7. 普通 Apply Changes、root/su Direct transport 和已有 compat deploy 行为不回归。
8. 文档、Wiki（如受影响）和代码实现保持一致。

## 11. 提交要求

- 只提交本任务改动，保留工作区已有的无关修改。
- commit message 使用英文，建议：

```text
[feature] support rootless compat deploy for system apps
```

- 只有在自动化验证和真实目标环境证据足以确认完全解决 Issue #45 时，最终提交正文才追加：

```text
Fixes #45
```

- 若缺少真实 OPlus user ROM 验证，禁止添加 `Fixes #45`，并在最终报告中列出残余验证项。
