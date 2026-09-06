# Flutter 混合工程与 C++ 外部构建接入方案

创建日期：2026-09-05。状态：依据用户 Flutter debug 反馈修订，待评审，未实现。

## 1. 推荐结论

**首版目标是让 Flutter 混合工程在 Jugg 模式下跑通：每次编译请求执行项目原有 Flutter 构建任务，Java/Kotlin/res 继续使用 Jugg 增量，构建产物接入 APK 更新、签名、安装与恢复。**

用户接受 Flutter 部分每次花几秒构建，主要收益是省掉宿主 Android 的完整构建。Jugg 不为 Flutter 实现编译缓存、输入内容快照、Dart 依赖图、按文件跳过构建或独立热重载协议；Flutter/Gradle 自己仍可使用其原有 up-to-date 和缓存机制。“每次调用任务”不代表额外加 `--rerun-tasks` 或关闭构建工具自身的缓存。

这次修订取消原方案“AOT 优先、debug 延后”以及外部输入持久化快照的建议。**Flutter debug 是首版核心验收场景**，不是需要默认回退宿主完整 Gradle 构建的异常模式。

C++ 保留在共同方案内：同样通过外部命令生成产物，再走 Jugg 的 APK 更新。首版也可每次调用对应的 CMake/NDK/Gradle 任务，把源码与头文件依赖判断交给原构建系统。Flutter 与 C++ 共用命令执行、产物校验、staging 和部署编排，产物处理有必要差异：Flutter debug 更新完整资产 bundle，C++ 更新 `.so`。

## 2. 新反馈能确认什么

用户截图显示：

- Build Analyzer 的 `FlutterPlugin` 下实际出现 `:flutter:compileFlutterBuildDebug`，耗时约 6.2 秒。
- 用户修改的是 Flutter Text 显示内容，关注的是这个修改能在 Jugg 模式下生效。
- 截图中的任务耗时合计为 95.2 秒，Kotlin/Android 插件任务占主要部分。这支持“值得拆出 Flutter 构建”的方向，不能直接作为拆分后的耗时预测。

截图不能单独证明：该任务独立执行时仍只需 6.2 秒；其所有输出目录；是否还需要资产复制/native hook 等任务；SDK/AGP 版本；直接调用时所需的 project properties；debug 产物已经生成新 `.so`。任务耗时合计也不能直接当作完整构建墙钟耗时。

`:flutter:compileFlutterBuildDebug` 是实际工程的有效调查入口，支持工程存在名为 `:flutter` 的 Gradle module。它不足以确定全部 source add-to-app/依赖接入细节。应在该工程中核对任务图和产物，而不是照搬上游其它版本的任务名。

候选命令是 `./gradlew :flutter:compileFlutterBuildDebug`，**尚未在用户工程验证**。执行前需保留原 Run/Gradle 命令里的 target、flavor、dart-define、ABI、SDK/JVM 等必要参数；不能因为 task 名含 Debug 就删除这些输入。若任务本身不产出完整可打包 bundle，补上所需产物准备任务或一个薄导出脚本，不调用宿主 assemble 来假装完成增量拆分。

## 3. 已确认的实现边界

| 事实与代码证据 | 含义 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/project/FileChangesHandler.kt` 的源码检查只识别 Java/Kotlin，且先过滤模块 build 目录 | Dart/C++ 及中间产物不能靠现有监听自动进入流程；首版每次运行外部步骤，因此不需要先扩展源码类型与输入扫描 |
| `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` 的 `incrementalCompile()` 在无原生变化时会直接部署或询问 fallback | 必须调整此边界；只改 Dart 时也要执行 Flutter 步骤，不能先被“无文件变化”分支挡住 |
| 同一 helper 在 Git 补检后可能再调用一次 incrementalCompile；核心 helper 还有影响传播递归轮 | 外部步骤应该属于一次顶层编译请求，在原生补检/递归轮和部署重试时不重复运行 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt` 已有 Compose 生成物到 Asset 阶段的显式交接 | 外部生成物也应在本轮直接登记，不等待第二次 VFS 事件，不放开 build 目录监听 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AssetOverlayCompiler.kt` 支持 NativeLib，按 ABI 路径和模块 APK 归属生成输出 | C++ 和 Flutter AOT 的 native 产物可以继续复用 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` 将 NativeLib 加入 updateApkFiles，普通 Asset 进入 overlay | Flutter debug 不能仅复制成普通 Asset 后便宣称已支持；需要明确的 APK 内 bundle 更新语义 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalDeployHelper.kt` 和 `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` 已串起 APK 修改、签名、重装恢复 | 首版推荐复用该路线，更新后重启进程加载 Flutter bundle，不承诺 Flutter hot reload |
| `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt` 当前只增加/覆盖 entry，已有临时 APK → align → sign → verify → replace 流程 | 完整替换 Flutter bundle 还需局部目录替换能力，以移除新 bundle 中已不存在的旧文件；继续保留签名失败时原 APK 不变的契约 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` 的 isEmpty 当前未计算 updateApkFiles | 只改 Flutter bundle 时不能被当成空结果；新增行为要覆盖 APK-only 更新的状态、返回值及部署早退判断 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFilePathExt.kt` 显式枚举可带 APK scope 的输出类型 | 新 bundle 类型需要正确转换并保留目标 APK，不能落到默认 base APK 分支 |

Flutter 上游 Android 构建源码显示：debug 使用 `flutter_assets/kernel_blob.bin` 以及相应 snapshot/资产；AOT 会生成 ABI 对应 native bundle。Dart Text 变化不意味着 `libapp.so` 或 `libflutter.so` 必然变化。真实产物以用户安装 SDK、实际任务和基线 APK 为准。

证据边界：此前读取的本仓库 `build/jugg/log/compile_latest.log` 是插件工程自身的 MCP 查询，不能代表用户 Flutter 现场。本次新增现场证据为用户截图与文字反馈，未执行用户工程构建或真机验证。已检查 NativeLib/SPI/Compose/VFS/Git 和 APK 更新边界，现有能力不消除上述空输入与 bundle 部署缺口。

## 4. 方案选择

| 方案 | 收益与成本 | 建议 |
|---|---|---|
| 每次宿主完整 Gradle 构建 | 最容易维持原任务图，但保留用户当前主要等待 | 仅作为基线建立、构建配置变化或不支持条件的退路 |
| 每次独立 Flutter/C++ 构建，原生 Jugg 增量，产物写回 APK | 无需 Flutter 缓存/源码扫描；额外付出产物收集、APK 重签名与安装 | **推荐首版** |
| Dart 输入差异分析、Jugg 自有编译缓存、Flutter VM 热重载 | 可以进一步提速，但扩大工程与 runtime 接入范围 | 本需求不做 |

首版顺序执行外部步骤与原生增量，先保证产物一致、失败可恢复；不引入并行构建与资源竞争。后续是否并行只看真实耗时证据，不预建调度框架。

## 5. 推荐流程与执行时机

```text
一次 Jugg Run / compile 请求
  → 等待初始化、保存文件和 pending file processing
  → 原有完整构建预检（无基线、切 variant、构建配置/依赖变化等）
      → 已决定完整构建：走完整路径，不重复执行外部增量步骤
      → 允许增量：
          → 执行已配置 Flutter / C++ 构建单元，各一次
          → 校验并冻结本轮完整产物
          → 原生 Java/Kotlin/res 增量（允许 0 个原生输入）
          → 合并外部产物与原生 CompileResult，登记 staging
  → Flutter bundle / native libs 写回对应 APK
  → align + sign + verify
  → 安装恢复，重启进程，回放仍需保留的原生增量结果
  → 全部成功后提交现有 Jugg 部署历史
```

具体约束：

- 外部步骤由顶层编译请求编排，先于“无文件变化”的增量早退。状态查询、Git/VFS 回调、warm-up/dry deploy 和部署重试不执行构建命令。
- 已配置外部步骤时，原生零输入也算有效编译请求；不伪造一个不存在的 Dart/BuildFile 去绕过判断，也不要求新增 `CompileFile.Type.ExternalBuild`。
- 复用 CompileResult.outputs/staging。必要时给外部阶段一个空源码输入的 CompileTask；成功与否由明确任务结果判断，不因空 details 的 all-success 判定而吞掉外部失败。
- 本轮是否实际有可部署产物由合并后的结果决定，不能只用 `CompileTask.isNeedCompile` / Java 文件数计算 `hasFileChanges`。这包括 MCP/CLI 通过同一编译入口获得的结果。
- Git 二次检查、Java/Kotlin 影响传播轮及同次安装重试复用本次结果，不重新执行 Flutter。去重状态只活在本次 Run，不持久化为缓存。
- 复用 CmdExecutor、已有命令完成协议和当前本地 `cmdCompileEnv`。不要用 `LocalGradleCompileClient.compileAndFetchResult()` 执行仅生成 Flutter bundle 的命令：其返回契约包含查找 APK 等完整构建结果。
- Gradle 报 UP-TO-DATE 仍校验并收集当前有效产物，不要求 mtime 必须变化，不加 `--rerun-tasks`。用户已经手动编好的产物也不能因此被漏掉。
- 每次外部构建成功后重新交接完整产物，不新增输入快照、内容去重数据库或 Jugg 侧任务缓存。staging 是本次部署所需的产物快照，继续保留；它与跳过 Flutter 编译的缓存不是同一职责。
- 构建失败/取消不继续部署；产物全部就绪后才能发布，禁止一边运行命令一边从正在写的目录装包。用户在构建期间继续编辑，沿用既有“保存后再 Run”的构建语义，不承诺在任意持续编辑下自动部署最后一个字节。

## 6. 最小配置

复用 `ProjectCustomConfig.moduleCustomConfigs`，增加可选 `externalBuilds`。只声明真实执行所需的信息，旧配置缺字段视为空列表。

| 配置 | 职责 |
|---|---|
| 所属模块、单元 id、完整 variant | 选择当前构建步骤，绑定真实 APK；禁止默认 virtualModule 或广播所有 APK |
| workingDir、command | 项目原 wrapper/任务/脚本及必要参数；沿用本地 Gradle JVM/SDK 环境 |
| flutterAssetsDir（Flutter） | 本次完整、可打包的 flutter_assets 目录；目标 APK 资产前缀依据实际 loader/manifest 配置，标准值为 assets/flutter_assets/ |
| nativeLibDir（native/AOT，可选） | 当前 variant 的发布目录，规范布局 `<abi>/<name>.so`；来自真实打包输入，保留所需 ABI |

不配置 Dart inputRoots、文件匹配规则、输入 hash 或缓存策略。C++ 首版也每次调用原构建任务，其头文件依赖由 CMake/NDK 自己判断。项目配置本身、pubspec/plugin/native 工具链等构建图变化仍需识别为构建配置变化，走必要的 Sync/完整构建；这不等于为普通 Dart 源码扫描和建缓存。

允许输出在 build、中间目录或工程外，只通过显式配置读取。原有 FileChangesHandler 的 build 过滤保持不变。独立任务需覆盖实际需要的资产生成、native hooks、链接/strip/收集步骤；上游内部 task 名和目录不硬编码成跨版本保证。

首版先验证用户这个 `:flutter:compileFlutterBuildDebug` 工程的本地路径。预构建 AAR、Flutter plugin 依赖升级、deferred components、远端独立任务、androidTest 等未验证组合不默认宣称支持；需要完整构建时，必须确认原构建图真的包含该外部模块，不能使用无关 assemble 伪造成功。

## 7. Flutter debug 产物接入

**推荐首版把完整 Flutter bundle 写回 APK，重签名、安装并重启进程。** 这样不用先验证 Flutter engine 是否会遵循 Jugg 的 Asset overlay 加载路径。用户要的是更短的构建等待，不是保留 Flutter 页面运行状态。

为了保持最小而完整的状态契约，推荐新增一个专用 `CompileOutput.Type.FlutterAssets`：

1. 表示一个当前目标 APK 的完整 Flutter assets bundle，用本轮临时归档承载完整目录，归档路径稳定且内容来自此次构建；不是把该归档直接放进 APK。
2. 归档包含全部 bundle 条目，整体进入既有 staging 与本轮重试数据；同一 APK + bundle 身份后写替换。由一个整体产物保留完整目录身份，不新增一套逐资产缓存或删除日志。部署提交后以已更新 APK 为持久基线，不额外持久化一份 Flutter bundle。
3. 转成 DeployItem 时保留 `apkPath/targetApkPaths`，在 DeployDataGenerator 中加入 `updateApkFiles`，不放入普通 overlays，不冒充 NativeLib。
4. IncrementalDeployHelper 消费时，在临时 APK 内完整替换该 bundle 前缀，移除旧目录中未出现在新 bundle 的条目，再写入全部新条目。限制操作在已确定的目标前缀内，拒绝归档越界路径和同目标冲突。
5. 保留 ApkFileModifier 的临时文件、签名校验和最终替换契约，两个 ZIP 更新实现均需正确处理目录替换。签名失败不得破坏原 APK，错误不能变成成功。
6. 安装恢复之后必须重启进程加载新 bundle；与本轮以及此前原生 DEX/res overlay 的恢复、回放顺序保持一致。不能仅断言 APK entry 已更新而忽略设备还在运行旧代码。
7. 本轮重试和 reinstall replay 保留 bundle 身份，不能将归档降级成普通 Asset。已提交后的恢复使用更新后的 APK：当前 CompileContextDb 对 NativeLib 就是 no-op，因为内容已经写入 APK；bundle 同样沿用该语义。下次新 Run 会重新构建/交接，无需跨会话保存 Flutter 编译结果。沿既有 staging 搬运与 APK 历史恢复路径检查，不升级整个数据库或丢弃旧历史。

这个专用输出类型是为 debug bundle 的成组替换与本轮重试建立明确边界，不能省成一个瞬态布尔值后让 staging 丢失身份。实现时不增加单实现 adapter 接口或通用资源框架。

C++ `.so` 和 Flutter AOT native 产物继续用现有 NativeLib，保证 ABI 与模块归属。首版可交接全部有效库，接受二进制未变时多一次 APK 更新；不增加内容去重缓存。库集合删除、ABI/packaging 变化需完整构建刷新基线，不能让旧 `.so` 静默残留。普通 Dart 源码删除由原 Flutter 构建系统处理，完整 bundle 替换保证旧资产不会仅因 APK 只追加而继续残留。

## 8. 失败、兼容与性能验收

- 首次无基线、variant 切换或原生构建配置变化：保留现有完整构建决策。已走完整构建时不再提前执行一次 Flutter。
- Flutter/C++ 命令失败：保留命令输出/退出码，停止当前部署；下一次 Run 重新调用命令，不增加原条件自动重试。
- 命令成功但目录缺失、必需 kernel/native 产物缺失、任务选择与模式不匹配：明确失败，不拿其它 variant 的旧产物替代。由真实任务/模式确定必需文件，不把某个 SDK 的 snapshot 文件列表作为永久硬编码。
- 取消：终止所属命令并舍弃未发布产物；验证 Gradle/外部子进程不会继续写到被部署的目录。必要的进程取消修正应单独列入范围，不预先重构 CmdExecutor。
- 原生增量失败：即使 Flutter 成功也不安装这轮半套结果；下一次 Run 可重新执行 Flutter，首版接受这点成本。
- 签名/安装失败：复用现有失败与恢复路径，不提交成功历史。安装重试使用本轮冻结的 bundle/库；后续新 Run 可以重建。
- 没有 Flutter/C++ 配置的工程：保持原有无变化、增量和回退行为。
- 日志使用 JuggLogger：info 显示外部构建、原生增量、APK 更新与安装耗时；warn 显示非预期失败；debug 保存产物范围与归属。遵循仓库日志换行规则，不用 error。

性能应分别测量 Gradle 启动/配置、Flutter 任务、产物冻结、原生增量、APK 修改/签名、安装启动，以及完整 Run 的墙钟时间。6.2 秒只能作为当前截图的任务数据，不能承诺总耗时只有 6 秒。验收标准是**设备显示新 Flutter 文本，且宿主未重新跑完整 Java/Kotlin/res 构建链，总等待明显低于原完整构建**。

## 9. 预期改动清单

下列是修订后的 review scope，尚未实现。

| 已有/拟新增文件 | 责任 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/server/protocols/Protocols.kt` | 简单 externalBuilds 配置数据，旧字段缺失按未启用处理 |
| `main/src/main/java/com/sickworm/intellij/jugg/project/CustomConfigManager.kt`、`idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 配置校验/接线；不再新增源码补扫和输入快照提交 |
| `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 顶层请求执行外部步骤，绕过不适用的无原生变化分支，合并结果；递归/Git 补检不重复执行 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildRunner.kt`（新增） | 复用命令执行；按单元一次运行、产物冻结/校验、返回 native/bundle 结果。独立外部构建 owner，不做 Flutter cache |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`、`main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileTaskResult.kt` | bundle 输出身份；零原生输入且外部有输出的结果/失败语义。原输入 enum 无须扩展 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFilePathExt.kt` | bundle 转 DeployItem 时保留正确 scope |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | bundle 明确路由 APK 更新，普通 Asset 保持 overlay |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | 只含 APK 更新时也不是空部署；检查结果、分流与重启契约 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalDeployHelper.kt` | 展开完整 bundle 并替换目标 APK 目录 |
| `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt` | 最小的目录替换能力，保留临时 APK 签名/校验成功后发布 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTracker.kt`、`main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileContextDb.kt` | 沿现有产物类型处理补齐 bundle staging 分支；已写回 APK 的 bundle 在 CompileContextDb 沿用 NativeLib 的 no-op，不新增缓存存储 |
| `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | 核对 APK-only 状态和 reinstall 后原生回放；有明确失败证据才局部修改 |

JuggCompiler、AssetOverlayCompiler 与 IncrementalCompilerHelper 的原生编译核心以复用为主；如接线必须新增产物参数，使用明确业务结果，不加测试专用 lambda。FileChangesHandler、GitFileChangesDetector、Gradle 项目 reader、project info 脚本镜像无需为此建立 Dart/C++ 源码模型。后续需要自动生成配置，再依据真实工程另行评审。

## 10. 测试与实施顺序

当前仅修改方案，执行文档验证，不运行工程编译。实施时按 06_testing 的价值门禁与 TDD：先保存真实缺失行为，再增加有稳定结果的断言；不测试字段存在、私有方法或仅 mock 调用次数。

| 层级/owner | 关键场景与修改前后断言 |
|---|---|
| L2，`idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelperTest.kt` | 只改 Dart/零原生输入：当前不执行 Flutter；改后外部步骤执行且结果可部署。一次 Run 的 Git 补检/递归不重复，原无配置工程保持原行为 |
| L1/L2，拟新增 `main/src/test/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildRunnerTest.kt` | 可控真实脚本输出、非零退出、取消、缺产物、UP-TO-DATE 仍交接；断言冻结后的 bundle/库与失败终态，不只验证命令被调用 |
| L1，`main/src/test/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGeneratorTest.kt` | bundle 进入 updateApkFiles，普通 Asset 保留 overlay；模块/多 APK 归属正确 |
| L1，`main/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployDataTest.kt` | 只有 bundle/native 更新时不是空部署；filterForApks 不串包；记录当前 isEmpty 的缺失行为 |
| L1，`idea/src/test/java/com/sickworm/intellij/jugg/compiler/manifest/ApkFileModifierTest.kt` | 完整 bundle 替换、旧条目删除、APK 无关文件不变；复用签名失败原 APK 字节不变用例；覆盖两条 ZIP 更新路径 |
| L1/L2，`main/src/test/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTrackerTest.kt` 及实施前定位的现有历史恢复 owner | bundle 新批次覆盖、本轮安装失败不丢失、已提交后从更新 APK 恢复；不引入独立 Flutter 缓存测试 |
| L2，`idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelperRecoverTest.kt` | 复用 APK update 要求 reinstall 的现有用例，补只改 bundle 和混合原生 staging 的恢复路径 |
| L3，`idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt` 与实际 Flutter debug 工程 | 修改 Text：改前 Jugg 仍显示旧值，改后设备新值；混合改 Kotlin/res、无变化第二次 Run、签名/安装失败后重试，完整链均可判定 |

必需实施顺序：

1. 在用户实际 SDK/AGP 下独立执行截图 task，保存完整命令、任务图、bundle/APK entry 对照与失败复现。确认不连带执行宿主完整源码/资源构建；识别需要的资产/native 准备任务。
2. 先做 zero-native-input 编排和外部结果失败测试，再实现顶层接线与 runner。
3. 先做 APK-only 结果、bundle 类型路由与完整目录替换失败测试，再实现产物/部署边界。确认既有 APK 持久化与恢复 owner，bundle 已写回 APK 后不额外保存编译缓存。
4. 定向运行 helper、runner、data、APK modifier 和 recover 测试，执行 `./gradlew :idea:compileKotlin`。禁止无过滤的 `:main:test` / `:idea:test`。
5. Flutter debug 真机验收：Text 新值、Dart 源码删除后的重新构建、Flutter asset 添加/删除、混合 Kotlin/res 修改、安装失败后恢复、连续两轮运行；保留完整墙钟耗时。没有 Flutter fixture 时以实际工程 L3/等价发布回归为证据，不能由 Mockito 替代。
6. C++ 验收：源码及仅头文件变化、输出位于中间/外部目录、多 ABI/目标 APK、设备 JNI 新返回值；构建系统自己处理依赖。库删除/ABI 变化确认完整构建退路。
7. 同步知识库和中英文 Wiki，再按仓库规范提交。仍属于方案讨论，未授权生产实现；如实现阶段应用 feature-development 技能，按其要求完成只读实施审查。

尚需用户工程提供的事实缩减为：实际命令及必要参数、Flutter SDK/AGP 版本、完整 bundle/库发布目录、基线 APK/模块归属与签名环境。已不再询问“首版是否需要 Flutter debug”，该点由新反馈明确。

## 11. 文档与调查依据

本次只修订方案，不把未实现行为写成已支持。落地后同步 `docs/ai_knowledge/02_compile_core.md`、`03_deploy_core.md`、`04_engineering_project.md`、`98_code_map.md`；检查 `docs/wiki/zh/capabilities/compile/so-update.md`、`docs/wiki/zh/concepts/incremental-compile/assets-native.md`、`docs/wiki/zh/concepts/apk-update-and-install.md`、`docs/wiki/zh/guide/compile.md` 及其英文镜像，补充 Flutter debug 的边界。

本任务累计实际读取的知识库：`00_overview.md`、`99_index.md`、`98_code_map.md`、`02_compile_core.md`、`02_compile_custom_ui.md`、`03_deploy_core.md`、`04_engineering_project.md`（相关章节）、`06_testing.md`、`09_plugin_runtime_debug.md`。已读取用户文档 `docs/wiki/zh/capabilities/compile/so-update.md`，其它 Wiki 路径进行了检索。主要代码与测试 owner 见第 3、9、10 节。当前新增证据是用户提供的 Build Analyzer 截图及“Flutter 每次构建、原生增量”的文字约束。

此前核对的上游来源（读取时为 2026-09-05；用户安装版本仍需核对）：

- [Flutter Android build targets](https://github.com/flutter/flutter/blob/stable/packages/flutter_tools/lib/src/build_system/targets/android.dart)：debug bundle、AOT native 产物与 deferred component 分支。
- [Flutter Gradle plugin](https://github.com/flutter/flutter/blob/stable/packages/flutter_tools/gradle/src/main/kotlin/FlutterPlugin.kt)：Flutter 编译、JNI 注册和资产复制任务及 app/add-to-app 接线。这里的 task 命名不能覆盖用户截图中的真实旧版本 task。

使用 discussion-first-development 工作流：依据新反馈调整事实、推荐范围和改动清单，不开始生产实现。
