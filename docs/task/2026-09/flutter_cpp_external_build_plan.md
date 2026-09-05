# Flutter / C++ 源码变更到 native 产物更新方案

创建日期：2026-09-05。状态：调查与待评审方案，尚未实现。

## 1. 结论与推荐范围

两个场景适合共用一条链路：**输入变更识别 → 按构建单元执行外部构建 → 校验并登记产物 → 既有 native 编译产物 → APK 更新、签名、安装恢复**。

公共边界应是“外部构建及产物交接”，不是某种语言编译器。Jugg 不实现 Dart 编译器、CMake 依赖分析或 NDK 工具链选择；项目原有构建系统仍拥有这些责任。首版推荐项目显式配置输入、命令、输出和模块归属，共用一个内置阶段，不分别建立 Flutter / C++ 的任务调度、缓存和部署体系。

“只有命令不同”对 Flutter **AOT 的 native 产物路径**基本成立，但不适用于所有 Flutter 模式：debug 的 Dart 更新会涉及 `flutter_assets/kernel_blob.bin` 等资产，不能假定 `libapp.so` 一定变化。Flutter 的资源、插件依赖和 deferred components 也可能改变 native 以外的内容。

推荐分两步交付：

1. **首版：C/C++ → `.so`，以及 Flutter profile/release AOT → `.so`。** 支持新增、修改源文件及 C/C++ 头文件；真实模块归属、输入补扫、失败保留、取消、APK 更新必须同时闭环。Flutter 配套 assets 与 APK 基线一致时可走 native 增量；发现 assets 变化、输入删除、构建配置变化或不能证明产物完整时，转完整构建或明确失败。
2. **后续：Flutter debug 与 assets 成组更新、自动读取 Gradle task metadata。** 必须先取得 Flutter 真机加载证据，再确定写回 APK 的资产路径与恢复策略。不能把“普通 Asset overlay 成功”当作 Flutter engine 已加载新 Dart 代码的证明。

这是一份首版范围建议，不代表用户已接受排除 debug。用户尚未提供 Flutter 模式、接入方式和 C++ 构建命令；如果实际需求主要是 Flutter debug，应将第 2 项提前纳入首轮交付，并重新评审部署部分的改动清单。

## 2. 当前实现事实与证据

下列路径均相对仓库根目录。

| 观察 | 代码证据 | 对方案的约束 |
|---|---|---|
| 源码只映射 Java/Kotlin，没有 Dart/C/C++ 输入类型 | `main/src/main/java/com/sickworm/intellij/jugg/project/FileChangesHandler.kt`：`checkSource()`；`main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`：`CompileFile.Type` | 只加命令无法让原本被过滤的输入触发构建 |
| 实际及传统模块 `build` 目录在文件分类前被剪枝 | `FileChangesHandler.init()`、`toChangeFile()`、`shouldExpandDirectory()` | 不放开 build 监听；在本轮内部交接生成物 |
| 直接 native 检测要求 `.so` 的父目录为已知 ABI，且在有效工程/模块目录内 | `FileChangesHandler.checkNativeLib()` | 不覆盖全部外部路径、非标准布局或中间产物 |
| 直接 native 检测目前使用 `ModuleInfo.virtualModule` | 同上 | 新生成物必须绑定真实目标模块，不能照搬这个推断，更不能默认广播到所有 APK |
| native 阶段已经可以生成 `lib/<abi>/...`，按模块关联 APK 复制 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AssetOverlayCompiler.kt`；`compiler/BaseCompiler.kt`：`splitApkAndCompile()` | 复用 `NativeLib` 输入与输出、APK 归属，不另造部署数据类型 |
| NativeLib 加入 `updateApkFiles` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt`：`buildDeployData()` | `.so` 更新是 APK 更新，不是普通 assets overlay |
| APK 修改需要签名，之后进入重装恢复 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalDeployHelper.kt`：`updateApk()`；`idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`：`deployIncrementalChanges()` | 保留签名失败、安装失败、恢复与重试契约，不承诺 native 进程内热替换 |
| 编译器已经有“先生成，再交给 Asset 阶段”的模式 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt`：Compose 阶段与 `composeAssets` | 外部生成物在 Asset 阶段前显式加入 `CompileTask`，不用等待第二次 VFS 事件 |
| 自定义 SPI 在类型检查之后执行；before hook 不会自动把返回的 outputs 作为内置后续阶段输入 | `compiler/BaseCompiler.kt`：`compile()`、`executeBeforeCustomCompilers()` | 仅装一个 `atFirst` 自定义 jar 不能完成未识别源码到 native 的闭环 |
| 删除事件仅移除已有待处理项 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`：`processFileChanged()`；`FileChangesHandler.toChangeFile()` | 删除不能只复用现有事件逻辑后声称已支持；首版通过输入清单差异触发完整构建 |
| 编译文件状态和部署提交已有分工 | `compiler/IncrementalCompilerHelper.kt`；`deploy/DeployFileStateTracker.kt`；`deploy/DeployFileManager.kt`：`commit()` | 原始源码成功、staging 可用、部署成功是三个不同事实 |
| 输出进入 staging 时按 APK + 相对路径替换，但不等于按内容自动过滤 native 无变化 | `deploy/DeployFileStateTracker.kt`；`deploy/DeployDataPlanner.kt` | 不假定已有 native 内容去重；需要在交接处比较，或明确接受保守多更新 |
| Git 补检围绕工程和模型中的模块，VFS 事件也不保证覆盖所有外部输入 | `idea/src/main/java/com/sickworm/intellij/jugg/project/GitFileChangesDetector.kt`、`FileChangesDetector.kt` | 单纯拓宽 FileChangesHandler 范围不能保证外部 Flutter module / C++ 仓库可靠触发 |

现场证据边界：已读取本仓库 `build/jugg/log/compile_latest.log`，其中是 Jugg 插件工程自身的 MCP 查询，不能作为用户 Flutter/C++ 工程的复现日志。当前结论由 HEAD 的代码分支和上游 Flutter 源码支持，未在用户工程上执行 Flutter/NDK 构建或真机验证。

反证检查：已检查既有 SPI、NativeLib、Compose 生成物交接、build directory 过滤和 Git/VFS 路径。现有 native 产物更新及 SPI 能力并不能推翻上述输入与交接缺口；同时，不能把“当前无法编译 C++”误写成“当前完全不支持 `.so` 更新”。

## 3. 方案比较

| 方案 | 正确性与用户行为 | 复杂度、兼容与验证成本 | 结论 |
|---|---|---|---|
| 源码变化直接走完整 Gradle 构建 | 原构建图完整包含 Flutter/native 步骤时，最容易保证 APK 完整 | 插件改动少，耗时高；独立源码产物若不在 Gradle 构建图中，仍需显式前置步骤 | 保留为退路；不是目标增量方案 |
| 显式配置外部构建单元，统一执行与产物交接 | 只执行所需构建，复用 Jugg native 更新；项目承担准确命令与产物映射 | 中等改动，局部契约可稳定测试，避免首版适配所有私有 task API | **推荐首版** |
| 自动读取 Flutter/AGP/CMake task 的全部输入输出 | 配置负担低，可更准确绑定 variant 与产物 | Flutter/AGP 结构差异、included build、task 输入不完整、packaging 中间阶段都需适配；模型/序列化/脚本镜像改动更大 | 有真实版本样本后再做，复用同一执行层 |

“前置命令 + 放开整个 build 目录”不作为可交付备选：存在构建事件自激、文件写到一半即被消费、旧 variant 混入、日志/缓存污染输入，以及外部输出仍不能定位 APK 等问题。

## 4. 最小配置契约

复用 `ProjectCustomConfig.moduleCustomConfigs`，为模块增加可选 `externalBuilds` 列表，旧配置缺字段按空列表处理。由现有 `CustomConfigManager` → `JuggManager.loadCustomConfig()` → `CompileContextManager` 装配；首版不增加新的配置文件体系、远程服务或 UI 编辑器。

一个构建单元的必要信息：

| 信息 | 约束 |
|---|---|
| `id` | 同模块内唯一；去重、日志和快照身份使用 |
| 所属模块、`variant` | 模块来自配置外层；显式绑定完整 variant，不使用包含 `release` 的字符串推断 Flutter 模式 |
| `kind` | `native` 或 `flutterAot`；用于产物要求与 Flutter 能力边界，不用于建立不同调度框架 |
| `workingDir`、`command` | 使用项目已有 wrapper / 导出脚本；沿用当前本地编译环境和 Gradle JVM；相对目录基于项目根解析 |
| `inputRoots` 与匹配规则 | 明确源文件范围；C/C++ 包含头文件及项目使用的 `.c/.cc/.cpp/.cxx/.h/.hpp/.inl` 等；Flutter 包含实际本地 Dart package roots，不能只写 Android 子目录 |
| `buildFiles` | pubspec/lock、CMakeLists、cmake include、Android.mk、Application.mk、构建脚本等；这些变化首版走完整构建与必要的模型刷新 |
| `nativeLibDir` | 一个当前 variant 的完整发布目录，规范布局 `<abi>/<name>.so`；可位于 build、中间目录或工程外，不依赖 VFS；脚本负责从实际打包输入导出 |
| `flutterAssetsDir` | Flutter AOT 必填，用于核对配套资产；首版不部署资产差异，发现与有效 APK 资产不同则完整构建 |

建议为配置建一个数据类，放在现有 `Protocols.kt`；核心通过当前配置快照访问。不要创建仅一个方法和一个实现的 builder/adapter 接口，也不引入任务 DAG、持久化任务队列或通用脚本平台。

示意调用，不是已经存在的脚本，也不是可直接照抄的 Flutter/AGP task 名：

```text
Flutter AOT 单元
  module: 实际承载 Flutter 产物的 Android module
  variant: 当前 APK 对应的完整 variant
  inputs: Flutter module/lib、显式本地 package 的源码根
  command: 项目 Flutter/Gradle 导出脚本，携带原有 flavor、target、dart-define、mode、ABI
  nativeLibDir: 脚本导出的当前 variant native 发布目录
  flutterAssetsDir: 同一次构建导出的 flutter_assets 目录

C++ 单元
  module: 实际 native library 所属 Android module
  inputs: 该模块及显式依赖的源码/头文件根
  command: 项目已有 externalNativeBuild 相关任务，或独立 CMake/NDK 脚本
  nativeLibDir: 脚本导出的当前 variant native 发布目录
```

构建命令必须覆盖必要的生成、链接、strip 和库收集依赖。不能仅找到 `.cxx` 下某个 `.so` 就认为等同于 APK 最终打包输入。Flutter 既有 source add-to-app 与预构建 AAR 接入不同：后者若实际消费的是已发布 AAR/Maven 依赖，默认完整依赖构建；只有项目显式导出且能证明宿主的 Java/resources/manifest 依赖未变，才启用 native 旁路。

首版只承诺本地执行。当前仅有远端构建环境时，使用原远端完整构建链；不把本地目录映射成远端目录后盲目执行。远端独立构建尚未接入宿主 Gradle 图时必须明确失败，不能用无关的 assemble 伪造回退成功。

## 5. 输入触发与编译时序

```text
Run / compile
  → 等待初始化、保存文件和待处理文件事件
  → 对已配置输入根进行定向补扫
  → 原有强制全量条件 + 外部构建能力/删除/配置变更预检
  → 外部变更登记为 CompileFile.Type.ExternalBuild
  → JuggCompiler 中 Asset 阶段之前执行 ExternalBuildCompiler
      → 按 module + variant + build id 合并输入，每单元只执行一次
      → 外部构建成功
      → 完整产物清单、路径/ABI/归属/配套资产校验
      → 转成带真实 module/baseDir 的 NativeLib CompileFile
  → AssetOverlayCompiler
  → CompileOutput.Type.NativeLib，保留 apkPath / targetApkPaths
  → staging → DeployDataGenerator.updateApkFiles
  → IncrementalDeployHelper.updateApk → 重签名 → reinstall/recover
  → 整轮部署成功后提交历史及外部输入快照
```

### 5.1 输入补扫与删除

- 文件事件只记录变化，保持原有 compile-on-save 行为；不在 VFS 回调、状态查询或 Git 回调里启动外部命令。
- `FileChangesHandler` 仅新增匹配已配置单元的入口。普通 generated Java/resource、Gradle build 目录继续过滤。CMake 生成文件、`.dart_tool`、日志和产物不能反向匹配源码规则。
- 在“无文件变化”早退之前补扫配置输入；状态刷新可使用相同只读扫描入口，不能执行构建。补扫覆盖 IDE 未打开的外部 package、Git ignored 本地源码和非 Git 工程。
- 为每个单元保存一个小型输入快照，包含配置身份和路径/内容指纹；持久化到 Jugg 增量状态目录。扫描与事件匹配复用同一规则，不全工程递归，也不修改全局 Git ignore 语义。
- 快照缺失时按“未知、需要构建”处理，不能把启动时当前文件直接当作已编译版本。旧配置没有 externalBuilds 时走原路径；无需重建所有项目数据库。
- 删除/重命名的旧路径通过快照清单差异识别，首版请求完整构建，不构造指向不存在文件的普通编译输入。输出中已有库消失也走完整构建，不能只删 staging 而让 APK 保留旧库。
- 有新输入时即使 Git 回滚检查认为内容恢复，也必须相对外部单元自己的有效快照判断；不能让旧文件过滤逻辑吞掉首次启用/新配置的构建请求。

### 5.2 编译阶段与状态

- 新增一个 `ExternalBuildCompiler`，复用已有 `BaseCompiler`、`CompileTask`、`CompileResult`。新增一个输入类型 `ExternalBuild` 足够，输出仍使用 `NativeLib`。
- 共享头文件或 Dart package 可以命中多个构建单元。现有 changed-file 集合按文件路径去重，因此不能只取第一个模块匹配；用该输入的 `extraInfo` 携带当前配置中命中的单元身份，编译阶段据此执行全部受影响单元，生成物分别绑定各自真实模块。补扫同样保留全部命中单元；输入只在所有关联单元成功后标记成功。
- 推荐外部阶段在 Compose/Asset 等后续阶段之前完成，以便缺产物或不支持时尽早失败；其生成物明确交给 Asset 阶段。不得依赖 SPI before hook 隐式传递 outputs。
- 原始 Dart/C++ 输入的 success detail 必须等命令、产物校验及 NativeLib 复制全部成功后才合并。生成的临时 NativeLib 输入不计为用户修改文件，不交回监听入口，也不进入 Java/Kotlin/Dex。
- 同一构建单元本次输入批次只运行一次。后续 Java/Kotlin 影响传播轮没有该类型就不重复执行；真正的新外部修改允许后续 Run 再执行，不能用永久“已执行 id”屏蔽。
- 复用 `CmdExecutor`、当前 `cmdCompileEnv` 和现有命令结果协议；不调用 `LocalGradleCompileClient.compileAndFetchResult()` 假装完整构建。该 API 会查找 APK/收集全量结果，只有 native 产物的任务不满足其契约。
- 只在命令成功且产物校验完成后发布本单元产物；独立单元失败不破坏已有效的其它 staging，但本轮不得继续部署半套 Flutter/native 更新。
- 捕获本次编译输入快照，并在构建结束再次核对。期间又修改源文件时不把新内容标为已处理；首版可明确失败并保留 dirty 输入，下一次重试，不静默提交混合版本。
- 持久化的输入快照只在整轮部署成功后前进；skip-deploy、编译失败、取消、签名/安装失败都不推进。全量构建建立快照也必须使用其已覆盖的输入状态，不能吞掉构建期间的新编辑。

## 6. 产物完整性与 APK 归属

1. 只读取配置的当前构建单元输出根，不扫描所有 `build/**`。发布目录应由项目脚本在构建成功后完整刷新，防止历史 ABI 或已删除库残留；首次配置验证必须检查该约定。
2. 保留 ABI 和 APK entry 身份，目标键为 `(目标 APK, lib/<abi>/<name>.so)`。根据实际模块与 `ModuleApkBelongs` 解析归属；相同文件名但不同 APK/ABI 不合并。
3. 不把 `app.so` 的中间命名原样塞进 APK；Flutter 脚本导出当前工具链真实打包命名。检查预期库存在、可读、非空及 ABI 与当前包相容。多 ABI 应更新该基线包所需的完整集合，不能只更新当前设备 ABI 后污染通用 APK。
4. 两个单元写同一个目标键时不得依赖遍历顺序覆盖。仅在内容一致且归属可证明时合并，否则明确失败；复杂 packaging `pickFirst/excludes` 由原构建图决定，不能在 Jugg 猜测。
5. 不能只比较“命令执行前后 mtime/大小”。脚本可能命中 up-to-date，也可能用户此前已经编好新库。首版最简单且安全的交接是将本单元全部有效 native 产物交给现有阶段，代价是源码变了但二进制没变时可能多一次 APK 更新。
6. 如首轮同时做内容去重，应在外部产物交接处比较目标 APK entry / 有效 deployed 记录，并保留既有 pending staging 与重装要求。磁盘 APK 已重签但上次安装失败不表示设备已更新。不能为省一次 resign 而丢掉待部署更新。**内容去重不是首版闭环的前置要求，不为此新建通用产物数据库。**
7. Flutter AOT 必须核对 `flutter_assets` 的路径集合及内容；任何差异在首版触发完整构建。`libflutter.so` 通常由 engine/依赖决定，不能因某个 Dart 文件变更就硬编码它为必然更新目标。
8. `.so` 的 APK ZIP 对齐、压缩与签名继续由现有 `ApkFileModifier` 路径负责。验证实际目标设备的加载行为，不能仅用 ZIP 内 CRC 变化宣称成功。

## 7. 失败与回退

| 条件 | 推荐行为 |
|---|---|
| 首次无完整 APK 基线、切 variant、原本已强制全量 | 保留原完整构建决策，不先重复执行增量外部步骤 |
| 普通外部命令非零退出 | 返回输入级失败，保留 stdout/stderr/退出码和 dirty 状态；不吞错、不原条件无限重试 |
| 命令成功但缺少预期库、发布目录不完整、重复目标冲突 | 明确失败；旧 `.so` 存在不能作为成功依据 |
| 取消 | 停止所属构建进程，舍弃本次未发布产物，不推进输入快照；验证构建子进程不会继续写入被消费目录 |
| Flutter debug、Flutter assets 变化、配置/依赖变化、输入或产物删除 | 在明确可用的原完整构建路径中处理；不能用 AOT `.so` 路径伪装支持 |
| 签名缺失、安装失败 | 复用 native APK 更新失败和恢复语义，保留已生成的有效产物，不把失败标记成部署完成 |
| 旧配置 / 元数据缺字段 | 未启用单元保持原行为；已启用但缺必填信息则仅拒绝该外部构建能力并给出原因 |
| 无法可靠定位 APK、ABI 或有效的回退命令 | 停止并报告缺失事实，不随意使用 virtualModule、默认 ABI 或无关 assemble |

不改变普通 Kotlin/Java 编译错误“当前失败、按既有下一次 Run 策略处理”的契约。预检已知不支持的条件可以本轮选择完整构建；运行中才发现 Flutter 资产差异时，需要向 `JuggCompilerHelper` 传达明确的可回退原因，不能靠抛未预期异常来碰巧进入 fallback。

用户日志通过 JuggLogger 输出：关键构建步骤和 APK 更新使用 info，非预期失败使用 warn，输入清单、目标路径、校验依据和分阶段耗时使用 debug；不使用 error。修改日志时按仓库 `+` 换行规则检查。

## 8. 预期改动清单

这是推荐首版的 review scope；未修改这些生产文件。

| 文件 | 责任 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/server/protocols/Protocols.kt` | 模块配置增加可选外部构建描述；旧 JSON 缺字段安全兼容 |
| `main/src/main/java/com/sickworm/intellij/jugg/project/CustomConfigManager.kt` | 加载时校验已启用单元；配置指纹变更使相关单元重新检查 |
| `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 配置接线、状态刷新输入补扫、部署/全量成功时外部快照提交；保留当前事件流程 |
| `idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt` | 将当前外部配置接入编译上下文，避免修改 Gradle 原始快照 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt` | 新输入类型；上下文增加有业务含义的只读外部配置；必要的已知回退结果表达，不增加 mock lambda |
| `main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt` | 实现外部配置读取，不将 Dart/C++ 混入 Java/Kotlin sourceDirs |
| `main/src/main/java/com/sickworm/intellij/jugg/project/FileChangesHandler.kt` | 只识别已配置输入；保留现有 build 过滤；真实模块归属 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildCompiler.kt`（新增） | 外部构建及产物交接的独立 behavior owner，含单元分组、输入快照、命令执行、产物校验及原始输入结果映射；先用小型私有方法，不预建 adapter 层 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt` | 外部准备阶段及 NativeLib 到 Asset 阶段的显式交接、失败传播 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt` | 仅为新阶段需要的已知 fallback / 输入未稳定状态做局部传播；保留既有 staging/递归编译契约 |
| `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 无变化早退前补扫、外部能力预检；完整构建与增量步骤避免重复执行 |

`AssetOverlayCompiler`、`DeployDataGenerator`、`IncrementalDeployHelper` 和 `JuggDeployerHelper` 的 native 行为以复用为主，首版不计划重写。`IFileChangesHandler`、`CompileTaskResult.kt`、`CmdExecutor` 及命令实现是否需要局部调整，由实施前现有签名核对及取消实验决定；若需扩展公共结果契约或进程树管理，应补到评审清单，不能混作无关清理。

输入快照仅服务已确认的外部目录补扫、删除检测和部署失败恢复，不修改整个 Jugg 部署历史 schema。新增状态类型放同一文件、维持最小作用域；发现外部编译器承担过多职责时，按已形成的业务边界再拆分，不预先搭框架。

## 9. 测试价值与验证矩阵

当前任务只有方案文档，不新增自动化测试、不触发 demo/设备构建。实施时以下稳定行为有测试价值：输入是否触发、完整有效产物是否进入正确 APK、失败/取消是否保留重试能力、设备是否执行新代码。字段存在、配置透传、类名、私有方法和日志精确字符串没有测试价值。

已定位且读取的 owner 优先复用：

| 层级与路径 | 应增加/复用的场景 | 修改前失败证据与修改后断言 |
|---|---|---|
| L1，`idea/src/test/java/com/sickworm/intellij/jugg/project/FileChangesHandlerTest.kt` | 已配置 Dart/C++/头文件、外部根、未配置文件、build 剪枝 | 当前没有外部类型，filter 不返回目标输入；实现后返回正确类型/模块；原有 build 过滤用例继续通过 |
| L1，拟新增 `main/src/test/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildCompilerTest.kt` | 定向补扫含新增/删除/同 mtime 内容变化；单元构建、up-to-date 但产物未部署、缺库、冲突、期间再编辑 | 使用临时目录和可控真实脚本/既有 CmdExecutor 依赖；断言有效产物、失败结果和快照，不能只验证 mock 调用 |
| L1，`idea/src/test/java/com/sickworm/intellij/jugg/compile/AssetCompileTest.kt` | 多 ABI native 映射，产物来自工程外或 build | NativeLib bytes、相对路径和 APK scope 正确；并保留普通 assets 正常路径 |
| L2，`idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggCompilerTest.kt` | 只改外部源码及与 Kotlin 混合变更 | 当前外部源码不进入主链；实现后产生 NativeLib 且原始输入状态准确，Kotlin 仍正常编译 |
| L2，`main/src/test/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelperTest.kt` | 外部失败/取消/已知 fallback、递归轮不重复执行 | 不部署半成品、不误清待编译输入、不重复运行同批外部单元；扩展现有失败与重试 owner |
| L1/L2，`main/src/test/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTrackerTest.kt` | staging 同目标替换、不同 APK 同名库共存、失败后保留 | 复用已有 deploy-key 与 snapshot 用例；只追加未覆盖的 native 可观察状态 |
| L1，`main/src/test/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGeneratorTest.kt` | native 成为 APK 更新数据 | 从公开输入断言 `updateApkFiles`，不是只断言内部调用 |
| L2，`idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelperRecoverTest.kt` | native 更新后重装恢复 | 复用现有 `recoverDeployState should reinstall without dry deploy when apk update requires install`，补失败后重试仍使用待安装 APK 的证据 |
| L3，`idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt` / `TopLevelFlowWithGitTest.kt` | C++ 函数返回值变化、头文件变化、仅 Dart AOT 文本/返回值变化、外部修改漏 VFS | 保存改前“输入被忽略/设备旧值”；改后经历构建、APK entry 更新、签名安装，设备出现新值；第二次 Run 验证状态收敛 |

现有 Android demo 没有在本次调查中确认可直接复用的 Flutter AOT 构建 fixture。Flutter 的第一份 L3 证据应来自明确版本的实际工程或专用 fixture；未准备好时记录缺口，不能用 Mockito 测试宣称 Flutter 已端到端可用。C++ fixture 如需新增，应先检查 `android_demo_project` 的现有构建约束；不在 JOOX Android 工程添加单元测试。

必需的设备/构建验收矩阵：

- Flutter：记录 SDK 版本、接入方式、mode、flavor、entrypoint、dart-define、ABI；普通 AOT 源码变化、无 binary 差异、资产变化回退、debug 明确回退、签名/安装失败后再 Run。
- C++：修改 `.cpp` 与仅修改头文件；外部源码根；多 ABI；自定义 build directory；NDK/strip/packaging 一致性；输出删除或缺失不得假成功。
- 共用：同轮两个单元各执行一次；与普通 Kotlin/res 混合修改；取消后再 Run；源码在构建期间再次编辑；APK 安装失败后不得漏更；不同 APK 同名 `.so` 不串包。
- 记录源文件差异、执行命令/退出码、导出产物和 APK entry 内容、安装结果及设备新值。单条“编译成功”日志不能代替最后两个环节。

定向命令在实施后按所选 owner 执行，例如：

```bash
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.project.FileChangesHandlerTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.external.ExternalBuildCompilerTest'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.compile.AssetCompileTest'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.JuggCompilerTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.IncrementalCompilerHelperTest'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.TopLevelFlowTest'
./gradlew :idea:compileKotlin
```

以上是计划命令，未执行。新增行为先取得预期失败，确认其保护价值后 TDD；不执行未过滤的全量 `:main:test` / `:idea:test`。

## 10. 实施顺序与待确认项

1. 取得两个实际工程的构建入口、variant/ABI 与输出清单；建立当前版本的失败复现。先验证原完整构建确实覆盖目标产物。
2. 先扩展 FileChangesHandler 与外部构建 owner 的失败测试，再实现配置、识别和定向补扫。覆盖不命中配置时的旧路径。
3. 按外部编译与 JuggCompiler 的失败测试，实现单元执行、完整产物校验、NativeLib 交接和原始输入结果映射。
4. 按已有 helper/state/recover owner 验证取消、失败、构建期间再编辑、skip-deploy、安装失败、重试及快照提交；补齐已知 fallback 传播。
5. 做 C++ 与 Flutter AOT 的 L3/真机验证，检查 `.so` 的实际 APK/设备加载结果；校验 debug/资产变化的完整构建退路。
6. 同步知识库与中英文 Wiki，执行定向验证，按仓库规范提交。若开始实现时应用 feature-development 技能，完成后按该技能要求进行只读实现审查。

需要实际工程确定而不能从插件仓库推断的事实：

- Flutter 是 debug/profile/release，完整 Flutter app、源码 add-to-app，还是预编译 AAR？目标是否必须包含 debug？
- C++ 使用 Gradle externalNativeBuild/CMake/ndk-build 还是独立脚本？头文件和源码根是否跨仓库？
- 原完整构建命令、最终打包库目录、必需 ABI、签名配置是否可用？是否要求远端执行？

上述信息不阻塞公共架构分析，但决定首版范围和项目配置；方案没有猜测用户工程的 task 名或输出路径。

## 11. 文档同步与调查依据

本次仅新增方案，未把尚未实现的能力写入现状文档。实施后预计同步：

- `docs/ai_knowledge/02_compile_core.md`：输入类型、补扫、阶段交接、回退与状态。
- `docs/ai_knowledge/04_engineering_project.md`：模块外部配置、输出目录及旧配置兼容。
- `docs/ai_knowledge/98_code_map.md`：新编译器入口。
- `docs/wiki/zh/capabilities/compile/so-update.md`、`docs/wiki/zh/concepts/incremental-compile/assets-native.md`：源码到产物支持范围及失败边界；对应英文页严格同步。
- 检查 `docs/wiki/zh/concepts/apk-update-and-install.md`、`docs/wiki/zh/guide/compile.md` 及英文镜像是否需要补充入口说明；native 部署机制未变则不改写现有原理。

实际读取的知识库文档：`00_overview.md`、`99_index.md`、`98_code_map.md`、`02_compile_core.md`、`02_compile_custom_ui.md`、`03_deploy_core.md`、`04_engineering_project.md`（相关项目模型与命令章节）、`06_testing.md`、`09_plugin_runtime_debug.md`。实际读取的用户文档：`docs/wiki/zh/capabilities/compile/so-update.md`；其它 Wiki 路径做了相关内容检索。主要代码及日志证据见第 2 节，测试证据见第 9 节。

外部事实核对（2026-09-05 读取上游 stable 源码；不是用户 SDK 的版本保证）：

- [Flutter Android build targets](https://github.com/flutter/flutter/blob/stable/packages/flutter_tools/lib/src/build_system/targets/android.dart)：debug bundle 拷贝 kernel/snapshot 到 flutter_assets；AOT 为不同 ABI 生成 native bundle，并有 deferred component 分支。
- [Flutter Gradle plugin](https://github.com/flutter/flutter/blob/stable/packages/flutter_tools/gradle/src/main/kotlin/FlutterPlugin.kt)：Flutter 编译、JNI 产物注册与资产复制分属相关任务，app 与 add-to-app 接线存在差异。实际命令和目录必须检查项目安装的 Flutter SDK。

调查使用 `discussion-first-development` 技能，已按“事实 → 方案比较 → owner/验证矩阵 → 实施清单”整理；用户本次要求的是分析及落地方案，不包含生产实现授权。
