# Flutter/C++ 外部构建兼容性修订方案

创建日期：2026-09-06。状态：原方案已落地；`3dd9ac3e` 后的剩余兼容性方案见第 12 节。

> 2026-09-12 复核说明：第 1～11 节保留 `94ce2ece` 落地时的决策与验证记录。Flutter `copyJniLibsflutterBuild<Variant>` 已由 `3dd9ac3e` 支持；后续范围、优先级和兼容性表述以第 12 节为准。第 12 节同时撤回“Flutter native 更新未保证进程重启”的原判断：变化的 `.so` 会进入 APK 更新和重新安装链路，进程必然重启。

审查对象：`32cb54e5f`；方案依据当前工作树 `b18e47921`，不撤销期间其他任务的改动。

## 1. 修改结论和范围

采用成功 full build 基线提供 Gradle 命令的方案。`BaseCompileContext` 已持有 `IDeployHistoryManager`，因此无需增加命令缓存、构造依赖或 Run session 对象。外部编译器直接从 `ICompileContext` 读取基线命令。

恢复 `CompileTask` 的原有职责、构造签名与任务传播方式。撤回此前讨论的“把外部构建移到 IncrementalCompilerHelper”建议：有了基线上下文，继续保留现有 `JuggCompiler` 阶段顺序即可，避免改动结果合并、custom compiler hook、取消、重试和 staging 更新边界。

本次包含：

1. 复用 full build 命令，移除 `CompileTask` 及编译调用链上的命令透传。
2. 恢复旧自定义编译器调用 `CompileTask` 的二进制兼容性。
3. 根据真实任务属性定位外部构建产物，适配已确认的 Flutter 打包任务名差异。
4. 使用 Flutter 官方 native 打包 JAR，修复 native assets 漏部署。
5. 修复 Gradle 命令派生对 `-x` / `--exclude-task` 等参数值的误删。
6. 去掉 `ModuleInfo.kotlinPluginOptions`、`externalBuildInfos` 的构造默认值，保留 JSON 读取边界的旧字段恢复。

保持当前“已识别的 Dart/C/C++ 源码变化触发外部构建”的行为。旧任务方案中“每次 Run 无条件执行”“新增完整 Flutter bundle 类型”“改为整目录 APK 替换”不在本次范围内。这里也不扩展源码删除、Dart/CMake 完整依赖图、远程外部产物同步或 Flutter VM hot reload。

## 2. 已确认事实

| 事实 | 代码或验证依据 | 对方案的影响 |
|---|---|---|
| 成功全量构建保存命令、build target、时间戳 | `FullBuildInfo.kt`、`JuggManager.reInitAfterFullCompiled`、`CompileContextDb.saveCompileContext` | 复用现有基线，不再持久化另一份命令 |
| IDE 在命令或 build target 改变时先回退全量 | `JuggCompileHelper.preprocessIncrementalCompile` | 允许进入增量时，使用基线命令有明确的一致性前提 |
| `BaseCompileContext` 已持有 history manager；子 context 使用接口委托 | `BaseCompileContext.kt`、`CompilerExt.subContext` | 可以通过只读 getter 取得最新基线，子 context 自动委托，无需逐层传参 |
| 旧基线可以没有 full build info，而已有编译上下文数据仍可恢复 | `CompileContextDbFullBuildInfoTest`、`GradleProjectInfoLocalFetchManager.isIncrementalCompileAvailable` | 不删除可恢复历史；IDE 仍沿用“缺成功 full-build 命令则全局回退”的现有门禁，CLI 普通源码编译不新增命令依赖 |
| 旧 CLI 保存的不是完整命令 | `BuildGradleBaseCommand` 实际执行 `./gradlew ${params.gradleCompileTask}`，保存时只写 `params.gradleCompileTask` | 需要局部兼容旧 CLI 基线，不能无条件把保存值当完整命令 |
| `CompileTask` 的旧三参数构造和旧四参数主构造已消失 | 前轮 diff、`javap` 检查 | 恢复原 descriptor；Kotlin 默认参数不等于保留 JVM 重载 |
| `-x :app:compileDebugKotlin` 被派生为孤立 `-x` | 前轮直接调用编译后的 `deriveCommand` 复现 | 必须区分任务位置和选项参数位置 |
| 两个新增 ModuleInfo 字段已有显式缺字段恢复 | `JuggProjectInfoSerialize.deserialize`；前轮序列化回归通过 | 去默认参数时保留 `?: emptyList()`，不提高快照版本 |
| 现有 ExternalBuildFlowTest 使用模拟 gradlew | `ExternalBuildFlowTest.kt` | 可验证内部数据流，不能代替真实 Flutter/NDK 构建和设备运行证据 |

## 3. 命令归属与生命周期

### 3.1 API 和数据流

在 `ICompileContext` 增加只读的 `fullBuildGradleCommand: String?`，注释明确其含义是“生成当前全量构建基线的命令”，不是当前 UI 中任意选中的命令。接口 getter 提供 `null` 默认实现，使本仓库重新编译的简单 context 无需逐个增加透传代码；`BaseCompileContext` 覆盖该 getter。

`BaseCompileContext` getter 从已有 `deployHistoryManager.getFullBuildInfo()?.compileCommand` 原样取得值，不在通用 context 或 serializer 中改写历史字符串。每次外部构建开始时读一次并保存到方法局部变量；不增加全局缓存或 setter。

```text
成功 full build / 历史恢复
  → FullBuildInfo.compileCommand
  → BaseCompileContext.fullBuildGradleCommand
  → external 子 context 的接口委托
  → ExternalBuildCompiler 开始时读取一次
  → ExternalBuildTaskRunner 派生命令并执行
```

命令派生需要同时被 `main` 中的 runner 和 `idea` 中的增量预检调用。将当前 companion 内的解析逻辑收口为 `ExternalBuildTaskRunner.kt` 同文件中的单个、带介绍性注释的公开顶层函数，作为 `main` → `idea` 的最小可见 API；runner 自身仍可保持内部实现。不要在 `JuggCompileHelper` 复制另一套 tokenizer 或任务过滤规则。

保留 `JuggCompiler` 的现有顺序：Compose → external build → Asset/NativeLib → Resource → Source/Dex。普通编译输入不进入 external 阶段，不读取或要求基线命令。

删除本次功能加入的 `CompileTask.gradleCommand`，恢复它的构造、`equals`、`hashCode`、合并及 parent task 构造逻辑。同步撤回 `JuggCompilerHelper.incrementalCompile`、`IncrementalCompilerHelper.compile` 及递归/重试/Git 补检调用中的命令参数，恢复原有方法签名。

### 3.2 基线一致性及缺失处理

- Run options 仍是全量构建的输入；不把新的 Run command 写入现有基线。
- 命令或 build target 不同：继续由原有预检回退全量；只有成功建立新基线后，context 才读到新命令。
- 新基线保存、旧基线删除与恢复：getter 跟随已有 history manager，不另外管理更新通知。
- 命令缺失：IDE 保持 `GradleProjectInfoLocalFetchManager.isIncrementalCompileAvailable` 的现有全局门禁，不放宽为部分增量；CLI 普通 Java/Kotlin/res 不读取新 getter，有 external 输入但没有命令时明确失败。
- IDE 预检先从本轮 external metadata 取得实际 task paths，再用共享派生函数校验基线命令；解析失败、非构建选项或所选任务被显式排除时直接回退全量，避免进入增量后以不可自动回退的普通编译失败结束。
- 外部入口需要的命令、任务和目录校验必须在启动进程前完成；预检与执行复用同一命令解析规则，runner 只执行已经成功派生的结果。
- 外部编译期间只使用开始时读取的局部命令值，不在每个文件或每个产物上重复读磁盘。

### 3.3 CLI 历史兼容

`BuildGradleBaseCommand` 后续保存其实际执行的 `compileCommand`，不再仅保存任务参数。不要在全局 `FullBuildInfoSerializer` 中猜测或改写字符串。

旧 CLI 基线的兼容 owner 明确放在 `ExternalBuildCompiler` 的私有命令解析入口，仅在 `Scene.INCREMENTAL_APK` 且确有 external 输入时触发。若历史值没有 Gradle 启动器，先按旧 writer 的实际行为形成 `./gradlew <保存值>`，随后仍交给共享派生函数完整校验；包含控制操作、未知语法或不能区分任务/参数时失败。已经是完整命令时原样进入共享解析，IDE 不适用该补齐。普通 Java/Kotlin/res 路径不会读取或迁移这个值。

CLI 搬迁源码工程时，`CmdLineContextManager` 对 `externalBuildInfos.sourceDirs`、`outputDir` 和新增 `nativeLibsArchive` 都使用 `historyProjectDir` → 当前 `projectDir` 的源码工程路径映射；这些路径属于当前工程的 Gradle task 输入/输出，不使用仅面向 `build/jugg` 备份的 `convertBuildBaseDir()`。不能把旧工程绝对路径或历史产物备份误当成本次本地外部构建输出。未能形成有效本地输入时明确失败；不扩展远程执行。

## 4. 版本风险和证据

任务名是插件实现约定，并非对所有版本稳定的 API。目录的两层语义必须区分：输出根从真实 task 属性读取；输出内部的条目结构仍需要明确的产物契约。

| 已核对版本 | 确认的实现 | 适配策略 |
|---|---|---|
| Flutter 2.10.5 | `compileFlutterBuild<Variant>`；`packLibsflutterBuild<Variant>`；Jar 配置使用旧 `destinationDir/archiveName`；native 条目最终为 `lib/<abi>/*.so` | 支持该打包任务别名，读取实际 archive 输出，不拼接 libs.jar 路径 |
| Flutter 3.22.0 | compile 名称仍相同；打包任务变为 `packJniLibsflutterBuild<Variant>`；打包逻辑额外收集独立 native assets 目录 | 使用新版打包任务，消费包含 AOT/native assets 的 JAR |
| Flutter 3.35.0、3.41.0 | 插件实现迁移到 Kotlin；compile/packJniLibs 命名和 archive 打包关系仍存在 | 不绑定 Groovy/Kotlin 实现类包名；通过 task 及输出属性识别 |
| AGP 3.1.4（用于确认历史差异，不作为新增支持承诺） | native 合并依赖旧 transform 管线，TaskManager 使用 `mergeJniLibs` transform | 不假定存在现代 `merge<Variant>NativeLibs`，能力不满足时回退全量 |
| AGP 7.1.3、8.13.1 | `MergeNativeLibsTask` 创建名为 `merge<Variant>NativeLibs` 的任务，`outputDir` 为 DirectoryProperty；artifact 注册内部实现已发生变化 | 保留真实 task.path + outputDir 的读取，不硬编码 intermediates 目录 |
| AGP 9.0.0 | 本地二进制仍有 `MergeNativeLibsTask.getOutputDir(): DirectoryProperty` | 继续验证实际项目任务发现；字节码存在不等于整条构建已验收 |

Flutter 上游来源：

- [2.10.5 flutter.gradle](https://github.com/flutter/flutter/blob/2.10.5/packages/flutter_tools/gradle/flutter.gradle)
- [3.22.0 flutter.groovy](https://github.com/flutter/flutter/blob/3.22.0/packages/flutter_tools/gradle/src/main/groovy/flutter.groovy)
- [3.35.0 FlutterPlugin.kt](https://github.com/flutter/flutter/blob/3.35.0/packages/flutter_tools/gradle/src/main/kotlin/FlutterPlugin.kt)
- [3.41.0 FlutterPlugin.kt](https://github.com/flutter/flutter/blob/3.41.0/packages/flutter_tools/gradle/src/main/kotlin/FlutterPlugin.kt)

AGP 证据来自本地 Gradle 缓存中的对应官方 sources JAR，以及 9.0.0 官方 JAR 的 `javap` 输出。上述列表是已检查事实，不是版本白名单；未知版本按相同能力检查决定是否支持，不遍历或猜测任意相似任务名。

## 5. Flutter 产物方案：使用官方 native 打包 JAR

### 5.1 两种方案比较

| 方案 | 优点 | 代价 | 结论 |
|---|---|---|---|
| compile 后由 Jugg 补扫 native_assets 目录 | 不增加 Jar 打包步骤 | 继续复制 Flutter 的多目录、布局与命名规则，add-to-app 和普通 app 的路径来源不同 | 不推荐 |
| 调用 Flutter 已有 native 打包任务，读取其输出 JAR | Flutter 负责 AOT/native assets 收集及重命名；Jugg 复用最终 APK 相对路径 | 多一个轻量 Jar task，需要适配已确认的 task 名和 archive 属性 | 推荐 |

### 5.2 元数据发现

1. 按当前 module 的 buildVariant 找到 `compileFlutterBuild<Variant>`，读取源码根及 assets 输出根，继续兼容 `outputDirectory` / `intermediateDir`。
2. 查找 `packJniLibsflutterBuild<Variant>`，不可用时尝试已确认的旧名 `packLibsflutterBuild<Variant>`。
3. 校验所选任务是 Gradle Jar 类型，并实际依赖当前 variant 的 Flutter compile task；两者必须属于同一个 Gradle project。两个候选同时存在且无法唯一确认时标记不支持。
4. native archive 优先读取 `archiveFile`（解包 Provider/RegularFile），已知旧 API 回退到 `archivePath`。不能使用仅接受目录的 `readConfiguredSourceRoots` 读取 archive 文件，也不能仅拼 `outputDir/libs.jar`。
5. `ExternalBuildInfo.taskPath` 对 Flutter 保存选中的 pack 任务路径；保留 `outputDir` 表示 Flutter assets 输出根，新增一个可空 `nativeLibsArchive` 文件字段；C++ 对该字段显式提供 null。不新增通用多产物描述框架。
6. 源码根已经可识别、但 compile/pack/输出属性或依赖关系不满足时，保留 sourceDirs 和 unsupportedReason，不能直接丢弃 Flutter metadata。

示例执行命令：

```text
./gradlew :flutter:packJniLibsflutterBuildDebug :native:mergeDebugNativeLibs --offline -Pchannel=demo
```

pack 依赖 compile，因此 Flutter 编译仍会进入任务图；Jugg 不主动追加 `--rerun-tasks`，由 Gradle/Flutter 保持原有增量和缓存判断。此步骤不要求执行宿主 assemble、Java/Kotlin 编译或 APK 打包。

### 5.3 收集和失败边界

- Flutter assets 继续读取 compile task 声明输出根下的 `flutter_assets/**`，沿用现有 Asset 部署行为。
- Flutter native 只从当前 archive 中读取 `lib/<abi>/*.so`。保持库名和 ABI，不再由 Jugg 猜测是否添加 lib 前缀，也不再补扫裸中间目录作为备用成功路径。
- 条目解压前清空本轮 Flutter native 临时目录，只接受严格匹配 `lib/<abi>/<name>.so` 的文件条目；输出路径由已校验的 ABI 和文件名重新构造，不直接拼接原始 Zip entry，避免绝对路径、`..`、反斜杠或重复条目污染 staging。无关 Jar metadata 不作为部署产物。
- 构建成功后 archive 必须存在且可正常读取；损坏或缺失不能因 assets 存在就返回成功。
- Debug 的有效 archive 可以没有 `.so`（仅包含 manifest）；仍需存在有效 assets。Gradle Jar 的默认 manifest 构造已核对，但该场景仍需要真实 Gradle fixture 验证。
- 任务执行失败或取消：不读取 archive 或旧目录；成功且产物内容不变：允许无新增部署输出，不额外执行构建。
- native assets 条目删除和 Flutter bundle 整目录替换沿用当前功能边界，本次不实现删除同步。

### 5.4 C++ 路径

C++ 继续通过 `merge<Variant>NativeLibs` 及其 Gradle 依赖构建源码；从 `outputDir` / 已知兼容属性 `outputDirectory` 获取实际目录。保留 File、Directory、Provider 的边界解包和 `lib/<abi>/*.so` 处理，不根据 AGP 版本拼接目录。

不为旧 transform 管线临时增加另一套 `.cxx` / `.externalNativeBuild` 裸产物扫描。现代 merge 契约不可用时，保留源码识别并回退全量，避免绕过 native merge 的打包处理。

## 6. Gradle 命令派生修复

行为所有者仍为 `ExternalBuildTaskRunner.kt` 中的 external build 命令派生函数。用顺序解析替换对所有 token 的任务名前缀过滤，保持实现局部，不创建通用 shell/Gradle 命令框架；该函数公开仅为 `main` runner 与 `idea` 预检共享，不能演化为全局 shell parser。

必须区分三类 token：Gradle 启动部分、被请求的任务、Gradle 选项及其参数值。

- 将任务位置上的原始请求任务替换为已发现的 task paths；保留选项及其值，不能因为值以 compile/merge 开头就删除。
- 正确处理 `-x task`、`--exclude-task task`、`--exclude-task=task`；同样保护 `-p/-I/-g/-P/-D` 及受支持的长选项参数。
- 保留已支持参数的原始引号/转义语义，确保有空格的路径、属性值不被拆散；生成的 task path 按当前 shell 正确引用。
- 只支持能够确定任务与参数边界的单条 Gradle 命令。未知选项元数、未闭合引用、复合 shell 操作等情况在预检回退，不误删或悄悄执行部分命令。
- `--dry-run` / `-m`、help/version 等不执行构建的选项不能用于此产物更新路径；明确排除本轮所选 task，或排除其必须执行的 Flutter compile / C++ external native build 依赖时也必须拒绝。依赖任务仅按当前 variant 已确认的命名关系判断，不建立通用 Gradle task graph parser。
- 预检与 runner 使用同一个派生函数，避免“预检通过，执行才发现无法派生”。不要修改全局 `CompileProjectCommand.isNormalGradleCommand`，影响面限定为 external build。
- 不扩展 full build 的 init script 注入、环境配置或其它全局命令行为。

验收示例：

```text
输入：./gradlew :app:assembleDebug -x :app:compileDebugKotlin --offline
输出：./gradlew :flutter:packJniLibsflutterBuildDebug -x :app:compileDebugKotlin --offline
```

## 7. API 与 JSON 兼容

### 7.1 旧自定义编译器 JAR

将 `CompileTask` 恢复至被审 feature 之前的接口，包括三参数 statusHolder 构造、三参数 parentTask 构造，以及四参数主构造。恢复被改动方法的旧 JVM descriptor，而不是补一个源码默认值。

ABI 回归用 ASM 在测试运行时生成只引用旧 JVM descriptor 的最小 consumer class，再加载当前 `CompileTask` 并实际调用三参数、四参数构造；必须证明没有 `NoSuchMethodError`，不使用源码 `contains` 或只检查反射方法列表。不要求外部自定义编译器重新编译，也不提交依赖 Git 历史生成的二进制 fixture。

增加 `ICompileContext` getter 也有边界：当前 Kotlin 编译配置没有启用 JVM default，源码默认 getter 不能保证旧二进制实现类拥有新方法。若旧自定义 context 实现被传入新 external 阶段、缺少该 getter，则只在 `ExternalBuildCompiler` 读取能力的位置捕获 `AbstractMethodError`，记录基线命令能力不可用并按 external build 失败收口；普通旧编译流程不读取新 getter。该边界在 `ExternalBuildFlowTest` 中用一个 getter 主动抛出 `AbstractMethodError` 的 context fixture 验证，不为此修改全项目 JVM default 编译模式。

### 7.2 ModuleInfo 和外部元数据

- 去掉 `kotlinPluginOptions`、`externalBuildInfos` 的构造默认值，直接构造 ModuleInfo 时显式传入，virtualModule 明确传空集合。
- `JuggProjectInfoSerialize.deserialize` 继续对旧 JSON 的两个缺字段做空集合恢复；业务侧仍使用非空类型。`copy()` 继承已恢复对象的现有值，不要求调用方重复填写。
- `nativeLibsArchive` 的读取处理缺失和显式 null。旧 C++ metadata 原样可用；旧 Flutter metadata 不能自动认为 archive 已知。
- 旧 Flutter metadata 的判定条件是：Flutter 的旧 contract 在 `taskPath`、`outputDir`、`unsupportedReason` 上表现为可用，但缺少 `nativeLibsArchive`。IDE 预检调用现有 `runUpdateIfNeeded(isForce = true, ...)`，并为 `GradleProjectInfoLocalFetchManager` 增加通用的“等待当前及已排队 refresh 完成”方法；远程初始化等待复用同一内部 await 实现，不复制 latch 循环。
- refresh 完成后，预检通过 `compileContextManager.compileContext.modules[moduleName]` 重新取得 module。`ChangedFile` 可能仍持有旧 `ModuleInfo`，因此 `ExternalBuildCompiler.resolveBuild()` 也必须优先按 module name 从最新 `context.modules` 解析；不能只修改预检而让真正编译继续消费旧 metadata。刷新失败或新 metadata 仍不满足 contract 时回退全量，且不得删除已有 project info、APK 基线或 changed-file 状态。
- 不提高全局 project-info / full-build-info 版本，不删除旧 complete_flag 或 APK 基线。缺失字段的恢复集中在读取边界。
- 同步 Gradle 侧 serializer、IDE serializer/merger、CLI 路径转换，以及生成 init script；校验 Kotlin 1.3/1.5 脚本兼容。

## 8. 验证矩阵和测试落点

测试门禁：保护命令语义、版本契约、历史恢复、失败产物隔离和真实部署结果；不为字段存在、getter 透传或私有函数签名新增普通单测。公共 ABI 通过旧客户端执行验证，不以源码 contains 断言替代。

下表为实施前测试计划，实际执行结果见第 11 节。

| 层级/证据 | owner 与落点 | 修改前证据/预期 | 修改后断言 |
|---|---|---|---|
| ABI 执行 | 拟新增 `main/.../compiler/CompileTaskBinaryCompatibilityTest.kt`，运行时生成旧 descriptor consumer | 旧三/四参数构造在当前实现缺失 | 旧消费者无需重新编译即可构造和组合任务，不发生 `NoSuchMethodError` |
| L1 | `ExternalBuildTaskRunnerTest` | 已复现 -x 参数值丢失 | 分离/等号形式、引号、多个任务、未知语法、dry-run 均得到确定结果；不变更属性值 |
| L1 | `CompileContextDbFullBuildInfoTest`、`DeployHistoryManagerFullBuildInfoTest` | 现有保存/缺历史恢复已有保护 | 复用既有回归，不新增仅验证 getter 透传的用例 |
| L2 | `JuggCompileHelperTest` | 外部命令目前来自 Run 透传；不可派生命令和旧 Flutter metadata 无对应局部门禁 | 基线匹配允许增量，命令/target 切换及缺基线保持原有全量门禁；dry-run、显式排除目标/依赖 task 在启动增量前回退；旧 metadata 只刷新一次并按最新 module 决策 |
| L2 | `GradleProjectInfoLocalFetchManagerTest` | 现有通用 refresh 没有公开等待边界，仅远程初始化可按标记等待 | 强制 refresh 及排队 refresh 均等待到最新 completion；远程等待语义保持不变，失败也能释放 waiter |
| L1 | 拟新增 `main/.../gradle/script/GradleProjectInfoReaderExternalBuildTest.kt`，借鉴现有 reader 公共入口 fixture | 缺少 pack task/Archive 的元数据发现 | 从公开 getProjectInfo 验证新旧 task 名、真实依赖、File/Provider 输出、自定义目录、unsupported 保留源码根 |
| L1 | `JuggProjectInfoSerializerAndroidTestTest`、`ProjectInfoSerializerInGradleAndroidTestTest` | 两个字段缺失恢复前轮已通过；新 archive 尚无保护 | 新旧字段、显式 null、跨 serializer 往返保持语义 |
| 内部 Flow | `ExternalBuildFlowTest` | 模拟的 native assets 放在独立目录时当前收集器遗漏；changed file 可持有旧 module | 只通过官方结构 Jar 的 lib 条目收集；Debug 空 native Jar、AOT/native assets、多模块 APK 归属、路径异常/重复条目、失败/损坏不复用旧输出；实际解析使用 context 中的新 module；getter 抛 `AbstractMethodError` 时仅 external 阶段失败 |
| L2/已有 Flow | `IncrementalCompilerHelperTest`、`JuggCompileHelperTest` | 此前存在命令透传 | 撤回后取消/重试/影响传播/staging 状态保持原行为；无 external 的输入不要求命令 |
| CLI Flow | `cmd_line/.../CmdLineTest`；必要时新增局部历史 fixture | 旧 writer 只保存任务参数；现有 context 未映射 external 路径 | 新完整命令、旧 task-only 基线均可确定恢复；源码目录搬迁后 source/output/archive 都指向当前工程；原 Java/Kotlin 路径不读取该命令且不受影响 |
| Gradle 集成 | `ReadProjectInfoGradle5/6/7/9CompatTest`、`ReadProjectInfoScriptContentTest` | 前轮只覆盖部分脚本/普通项目 | 选定兼容版本的生成脚本可运行；增加 pack/Jar/Provider fixture，AGP fixture 覆盖 native task 和输出路径 |
| L3 真机构建与运行 | `idea/.../manager/TopLevelFlowTest` 既有回归 + 实际 Flutter/native 工程 | 现有模拟 external Flow 不能证明真实工具链 | Flutter Debug 文本更新；AOT/native assets 行为更新；C++ 返回值更新；混合 Kotlin/res 仍生效；失败后不部署旧结果 |

真实版本验收至少区分：旧 Flutter packLibs、现代 packJniLibs、AGP 7/8/9 native；历史版本若与当前 SDK/JDK 不兼容，使用与其匹配的独立工具链。源代码对照与模拟任务不能标记为真实构建通过。无法取得某版本环境时，在交付中明确未验证组合，不扩大支持承诺。

既有 TopLevelFlow 的普通 Android 回归必须保留。不要通过修改生产代码增加 mock provider、lambda 或测试专用 factory。

## 9. 落地方案

### 9.1 阶段门禁

| 阶段 | 生产修改 | 先行失败证据 | 退出条件 |
|---|---|---|---|
| A. 固定兼容失败 | 无 | `CompileTaskBinaryCompatibilityTest` 旧 descriptor 调用失败；`ExternalBuildTaskRunnerTest` 复现 `-x` 参数值被删；用包含独立 native-assets `.so` 的官方结构 JAR 对照当前目录扫描，保存漏收集证据 | 三类失败原因与目标缺口一致，未因 fixture 或环境错误失败；archive 字段接入后再把第三项固化为 `ExternalBuildFlowTest` 失败断言 |
| B. 恢复 API 与命令归属 | `ICompiler.kt`、`BaseCompileContext.kt`、`IncrementalCompilerHelper.kt`、`JuggCompileHelper.kt`，`CompilerExt.kt` 仅在委托无法自动覆盖时修改 | 阶段 A 的 ABI 用例；现有 compile loop 回归 | `CompileTask` 恢复旧构造和合并语义；命令透传从 task/helper 调用链消失；普通输入不读取新 getter |
| C. 统一命令派生与预检 | `ExternalBuildTaskRunner.kt`、`JuggCompileHelper.kt` | `-x/--exclude-task`、带空格参数、未知独立选项、dry-run、复合 shell、排除 external 依赖 task | main runner 与 idea 预检调用同一顶层函数；预检通过的命令可直接执行，拒绝场景均在启动进程前回退 |
| D. 刷新并读取新 metadata | `JuggProjectInfo.kt`、`GradleProjectInfoReader.kt`、`ProjectInfoSerializerInGradle.kt`、`JuggProjectInfoSerialize.kt`、`JuggProjectInfoMerger.kt`、`GradleProjectInfoLocalFetchManager.kt`、`JuggCompileHelper.kt` | pack task/archive 缺失；旧 JSON 缺字段；旧 module 被 changed file 持有；refresh waiter 未等待排队任务 | 新旧 Flutter task 均能形成明确 metadata；旧 metadata 最多刷新一次；refresh 后预检和 compiler 都读取最新 module；unsupported 信息可解释 |
| E. 切换 Flutter archive 收集 | `ExternalBuildCompiler.kt` | archive 中独立 native assets 被旧目录扫描漏掉；损坏 archive 或失败 task 会暴露旧输出风险 | Flutter assets 与 archive 分别验证；只解包合法 `.so`；C++ 目录路径保持原行为；任一 build 失败不产生 staging 输出 |
| F. CLI 与路径搬迁 | `BuildGradleBaseCommand.kt`、`CmdLineContextManager.kt`、`ExternalBuildCompiler.kt` | base writer 保存 task-only；external source/output/archive 保留旧工程绝对路径 | 新 base 保存完整命令；旧 task-only 仅在 CLI external 边界恢复；搬迁后所有 external 路径指向当前源码工程 |
| G. 集成、文档与提交 | 知识库、Wiki 及必要测试 fixture | 现有普通 Android Flow、脚本版本兼容和真实工具链尚未证明 | 定向测试、编译、真实 Flutter/C++ 验收完成；文档与最终行为一致；仅提交本任务相关文件 |

阶段必须按 A → G 推进。某阶段的新证据推翻任务名、产物结构或刷新边界时，停止后续修改并回到方案讨论，不用备用目录扫描伪造成功。

### 9.2 各阶段实施细节

1. **恢复 API 与命令来源**
   - `CompileTask` 按被审 feature 前的四参数主构造、三参数 status-holder 构造和三参数 parent 构造恢复；保留 `ExternalBuildSource` 类型，不回滚本功能本身。
   - `ICompileContext.fullBuildGradleCommand` 默认返回 null；`BaseCompileContext` 覆盖并原样读取 history manager。
   - `ExternalBuildCompiler` 在确认确有 external 文件后只读取一次命令；捕获 `AbstractMethodError` 后返回可解释失败。
   - 删除 `JuggCompilerHelper.incrementalCompile`、`IncrementalCompilerHelper.compile`、Git 二次编译和递归 compile loop 的命令参数，恢复原有调用形态。`JuggCompiler` 阶段顺序不改。

2. **实现共享命令派生**
   - 在 `ExternalBuildTaskRunner.kt` 增加一个公开顶层派生函数；返回派生后的完整字符串或 null。调用方使用统一的“external build command cannot be derived”原因回退，避免为错误分类再增加公共结果类型。
   - tokenizer 只实现本需求需要的单命令、单双引号和反斜杠转义；保留原始 token 文本。已知带值选项消费下一个 token，`--name=value` 边界明确可保留，无法判断是否带值的独立未知选项直接拒绝。
   - 仅删除“任务位置”的原 full-build tasks，再插入去重后的 external task paths。`-x` 的值、`-P/-D` 属性值和路径参数不得参与任务前缀过滤；对 pack/merge task 按当前 variant 推导必须执行的 `compileFlutterBuild` / `externalNativeBuild` 名称，仅用于拒绝危险 exclude。
   - IDE 从最新 module metadata 计算 task paths 后调用该函数；runner 使用同一结果，不修改全局 `CompileProjectCommand`。

3. **发现 metadata 与处理旧快照**
   - `ExternalBuildInfo` 增加无默认值的 `nativeLibsArchive: File?`；`isSupported` 按类型判断 Flutter archive 要求，所有构造点显式传值。
   - Flutter 先定位 compile task 和 assets 输出，再定位 modern/legacy pack Jar task；只接受与 compile task 同 project 且依赖关系成立的唯一候选。archive 使用独立文件读取 helper，不复用目录 helper。
   - `GradleProjectInfoLocalFetchManager` 抽取通用 await-latest-completion 实现，新增供旧 metadata 恢复使用的等待入口；现有 remote wait 只负责决定“是否需要等”，底层等待逻辑共享。
   - `JuggCompileHelper` 仅对“旧 contract 可用但 archive 字段缺失”的 Flutter metadata 在单次 compile 中强制 refresh 一次，等待完成后从 compile context 重取 module。`ExternalBuildCompiler` 同样按 module name 优先取 `context.modules`，避免 changed-file 快照陈旧。
   - 去掉 `ModuleInfo.kotlinPluginOptions`、`externalBuildInfos` 的构造默认值；旧 JSON 的缺字段恢复仍只位于 deserialize 边界。

4. **收集和部署产物**
   - Flutter pack task 成功后先验证 assets 根和 archive，再收集变化内容。archive 即使没有 `.so` 也必须可打开；Debug 空 native archive 只有在 assets 有效时才算成功。
   - archive 输出到 `task.outputDir/external/<module>/flutter-native`，C++ 输出到同级 `cpp-native`，每类产物在收集前只清空自己的目录；同一 ABI/文件名重复条目按错误处理，不使用旧轮次内容。
   - C++ 保持 `merge<Variant>NativeLibs` 目录收集和 CRC 判断，不加入 `.cxx` 裸目录降级。
   - 多个 external build 任一失败时整轮 external 阶段失败，不能部署另一部分的半成品。

5. **CLI 兼容**
   - `BuildGradleBaseCommand` 用同一个局部/只读 `compileCommand` 同时执行构建和写入 `FullBuildInfo`。
   - `ExternalBuildCompiler` 只在 `Scene.INCREMENTAL_APK` 将无 launcher 的旧值补成 `./gradlew ...`，随后仍走共享派生校验。
   - `CmdLineContextManager` 对 external 的 source/output/archive 使用源码工程根映射；普通 classpath/library backup 继续使用原 build-base 映射，两个规则不合并。

### 9.3 定向验证命令

实施时按修改阶段运行，禁止无过滤全量 `:main:test` / `:idea:test`：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.CompileTaskBinaryCompatibilityTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.external.ExternalBuildTaskRunnerTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.external.ExternalBuildFlowTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderExternalBuildTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.ProjectInfoSerializerInGradleAndroidTestTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.project.data.JuggProjectInfoSerializerAndroidTestTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.compiler.JuggCompileHelperTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManagerTest"
./gradlew :cmd_line:test --tests "com.sickworm.intellij.jugg.cmdline.CmdLineTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.ReadProjectInfoGradle7CompatTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.script.ReadProjectInfoGradle9CompatTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"
./gradlew :idea:compileKotlin :cmd_line:compileKotlin
```

Gradle 5/6 兼容测试只在匹配 JDK 可用时运行；若修改的生成脚本语法触及 Kotlin 1.3/1.4 边界，则它们从条件验证提升为交付必需项。命令执行后必须检查本次 diff 中新增或修改的 `JuggLogger` 调用格式。

### 9.4 真实工具链验收

仓库当前没有 Flutter/Android C++ 可运行 fixture，因此模拟 Gradle task 只能作为内部 Flow。合入前至少取得一个 modern Flutter 和一个 modern AGP C++ 工程的真实证据；历史版本作为发布兼容矩阵，环境缺失时明确记录未验证。

| 场景 | 操作 | 必须保存的证据 |
|---|---|---|
| modern Flutter debug | 修改 Dart 文本并 Run | 实际执行 packJniLibs task；assets 更新；应用行为变化；未执行宿主 assemble |
| Flutter native assets/AOT | 修改会改变 native archive 的输入 | archive 中目标 `lib/<abi>/*.so` 更新并部署；进程加载新库；失败后没有旧库 staging |
| legacy Flutter | 使用匹配 JDK/SDK 的 2.10.x 工具链修改 Dart | packLibs task 和 archive 实际路径；成功或明确记录环境不具备，不推断通过 |
| AGP 7/8/9 C++ | 修改 native 返回值 | 执行当前 variant merge/external-native task；APK 归属正确；运行时返回值变化 |
| 混合输入 | 同轮修改 Dart/C++ 与 Kotlin 或 res | external 输出继续进入 Asset/NativeLib 阶段，普通 source/resource 阶段顺序及部署结果不变 |

### 9.5 预计文件与提交

主要生产文件：`ICompiler.kt`、`BaseCompileContext.kt`、`IncrementalCompilerHelper.kt`、`JuggCompileHelper.kt`、`ExternalBuildCompiler.kt`、`ExternalBuildTaskRunner.kt`、`GradleProjectInfoReader.kt`、`JuggProjectInfo.kt`、两个 serializer、必要 merger、`GradleProjectInfoLocalFetchManager.kt`、`BuildGradleBaseCommand.kt`、`CmdLineContextManager.kt`。`CompilerExt.kt` 与 `JuggCompiler.kt` 原则上不改，只有接口委托或接线确实需要时才做最小修改。

实现完成后同步知识库和 Wiki，执行 `git diff --check`、定向测试、编译及真实工具链验收。最终使用一个符合仓库规范的英文提交，仅包含本任务生产代码、测试、fixture 和文档；不得夹带工作树中其他改动。

## 10. 文档同步和剩余风险

实施时更新 `02_compile_core.md`、`04_engineering_project.md`、`98_code_map.md` 中命令来源、任务/产物、旧快照处理说明。Wiki 检查并同步中英文的 `capabilities/compile/so-update.md`、`concepts/incremental-compile/assets-native.md`、能力入口和使用指南；实际编辑 Wiki 时使用 wiki-writer 技能。

本方案已经按第 11 节同步到知识库和 Wiki。原有两份 Flutter/C++ 任务方案保留历史语境，本修订对第 1 节列出的事项给出最终实施决策。

剩余边界：未检查的未来版本可能改任务或产物格式；native archive/modern merge 契约不满足时明确回退。此次不解决所有共享头文件归属、源码删除、APK packaging 定制或 Flutter bundle overlay 的潜在问题；真实 L3 若暴露与本次目标直接相关的部署失败，保存证据并重新讨论必要改动，不把模拟 Flow 通过作为完成。

## 11. 落地结果（2026-09-06）

阶段 A～F 已完成：恢复 `CompileTask` 旧 JVM 构造契约；外部构建命令改从成功 full build 基线读取并由 main/idea 共用同一派生函数；Flutter metadata 改为校验 compile → pack Jar 依赖并记录 archive；旧 Flutter 快照命中 Dart 变化时强制刷新并消费最新 module；Flutter native 仅从合法 archive 条目收集；CLI 新基线保存完整命令，并兼容旧 task-only 基线及工程搬迁路径。

实现中将 Flutter 与 C++ staging 分为 `flutter-native`、`cpp-native` 两个子目录。这样同轮混合输入分别清理自身旧产物，不会因后收集的一类产物清理掉前一类已生成结果；对外的 `lib/<abi>/*.so` 部署契约不变。

已通过的自动化证据：

- ABI、命令派生、外部产物流、Gradle metadata、两个 serializer、生成脚本内容等 main 定向测试。
- `IncrementalCompilerHelperTest`、`JuggCompileHelperTest`、`GradleProjectInfoLocalFetchManagerTest`、`FileChangesHandlerTest`。
- `ReadProjectInfoGradle6CompatTest`、`ReadProjectInfoGradle7CompatTest`、`ReadProjectInfoGradle9CompatTest`。
- `:idea:compileKotlin`、`:cmd_line:compileKotlin`。
- Wiki 中英文镜像校验、静态站点构建和本次提交内容的 whitespace 检查。

仓库没有可直接运行的 Flutter/Android C++ fixture，因此 modern/legacy Flutter 真机构建、AGP 7/8/9 C++ 运行时行为及混合输入 L3 尚未执行。本次只将已通过的模拟 Flow、reader fixture 和 Gradle 生成脚本兼容回归作为自动化证据，不把它们表述为真实工具链验收。

## 12. `3dd9ac3e` 后续兼容性修订方案（2026-09-12）

### 12.1 当前结论

`94ce2ece` 已覆盖标准 Flutter module/add-to-app 和标准 Android CMake/ndk-build 外部构建主路径，`3dd9ac3e` 进一步补齐 Flutter 新版目录型 native 输出。当前能力应表述为：

> 支持能够可靠发现源码根、Gradle task 和最终产物位置的标准 Flutter 与 Android Native 工程；无法识别的版本、输入或构建链安全回退完整 Gradle。

不声明覆盖所有 Flutter 版本、AGP 3.4～9.1 的同等 Native 增量能力或任意自定义外部构建系统。剩余问题集中在输入覆盖和失败原子性，不再把 `copyJniLibsflutterBuild` 或 AGP 3.4 旧 Transform 管线列为待实现能力。

### 12.2 已纠正和已完成事项

| 事项 | 当前事实 | 方案结论 |
|---|---|---|
| Flutter `copyJniLibsflutterBuild<Variant>` | `3dd9ac3e` 已识别 copy task 的 `destinationDir`，并统一使用 archive 或目录形式的 `nativeOutput` 收集 `.so` | 已完成，不进入后续实施范围 |
| AGP 3.4 Native | AGP 3.4 没有现代 `merge<Variant>NativeLibs` task；源码根可识别但 task 不可用时 metadata 保留 `unsupportedReason`，增量预检回退完整 Gradle | 定义为安全降级，不实现旧 Transform 专用增量路径 |
| Flutter native 更新后的重启 | `NativeLib` 输出进入 `changedLibs` 和 `updateApkFiles`，随后更新、重签并重新安装 APK | 产生变化 `.so` 的路径必然重启进程，不存在缓存 FlutterEngine 继续使用旧 native 产物的问题 |
| Flutter Debug 仅 assets 变化 | 允许 native 目录为空，只要 `flutter_assets` 有效；这种情况下可能只走 assets overlay | 不预判为缺陷；在真实 add-to-app 验收中区分“assets-only”和“同时有 native 变化”并记录实际生命周期结果 |

### 12.3 剩余边界分类

#### A. Flutter 输入覆盖

`sourceDirs` 只回答“文件是否属于该 external build”，不能代替构建触发规则。

- Flutter module 外的本地 path dependency 需要把实际 package root 加入 `ExternalBuildInfo.sourceDirs`。
- `pubspec.yaml`、`pubspec.lock`、图片、字体及其他 Flutter assets 即使位于现有 root 下，也需要独立的文件名、文件类型或声明式 asset 规则。
- 非 `.dart` 的生成器输入不能通过扩大 Dart source root 自动覆盖；应监听稳定的生成器输入并执行生成任务，不能监听 `.dart_tool` 或 `build` 中的生成结果。
- 删除文件需要单独确认历史产物移除语义，不能只依赖重新扫描当前输出。

后续调查优先从 Flutter task 的已声明 inputs 和 package metadata 取得真实输入，不递归猜测所有相邻目录，也不把全局 pub cache 纳入监听。

#### B. Native 输入覆盖

- `CMakeLists.txt` 或 `Android.mk` 的父目录只能覆盖主 source tree；`add_subdirectory("../native-common")`、绝对路径或复用仓库中的共享源码需要把实际 source tree 加入 `sourceDirs`。
- `.S/.s`、`.inc/.inl/.ipp/.tpp` 属于文件类型规则缺口，不是 source root 缺口。
- `.proto`、`.idl` 等生成器输入可能同时缺少目录、文件类型和前置任务信息，应按真实生成任务建模。
- `.cxx`、`.externalNativeBuild`、`build/generated` 是构建输出，不直接监听；应监听其稳定输入并让 Gradle 执行生成及 native task。
- Bazel、Cargo、脚本和自定义 Gradle task 缺少任务、命令及产物契约，继续回退完整 Gradle，不纳入标准 CMake/ndk-build 支持声明。

#### C. 混合输入失败原子性

任务覆盖不足与混合输入漏处理是两个独立问题。当前 `ExternalBuildCompiler` 通过 `task.files.mapNotNull(::resolveBuild)` 收集 build；只要至少一个文件解析成功，就可能继续执行，并在最后把原始 `task.files` 全部标记成功。

例如同轮修改两个 Flutter module，其中一个 task 可识别、另一个不可识别时，不能只构建可识别部分后报告全部成功。目标行为是：

1. 预检发现任一 external 输入缺少匹配 metadata、task 或产物契约时，整轮回退完整 Gradle。
2. compiler 再做一次防御校验；解析结果数量与原始输入不一致时整轮失败，不启动部分 external task。
3. 只有全部输入解析成功后才按 build 去重、执行和收集产物；任一 build 或产物收集失败时，不提交其他 build 的半成品和成功状态。

### 12.4 推荐实施顺序

| 优先级 | 工作项 | 最小范围 | 非目标 |
|---|---|---|---|
| P0 | 修复混合输入原子性 | 预检整体回退；compiler 禁止 `mapNotNull` 部分成功；保持现有 staging 和状态提交契约 | 不扩展新的 external build 类型 |
| P1 | 补 Flutter 本地依赖与声明输入 | 识别本地 path package roots；覆盖 pubspec、已声明 assets 和稳定生成器输入 | 不监听全局 pub cache、`.dart_tool` 或任意工程文件 |
| P1 | 补标准 Native 真实 source tree | 从标准 CMake/ndk-build 能力取得共享源码根；补汇编及常见头文件扩展名 | 不解析完整 CMake 语言，不支持 Bazel/Cargo |
| P2 | 删除语义 | 明确已删除 Flutter asset/native library 的 APK 或 overlay 移除契约 | 不进行全量 Flutter bundle 重建框架化改造 |
| 验收 | 更新兼容矩阵 | 区分 legacy pack、modern pack、modern copy、AGP 3.4 fallback、AGP 7/8/9 native | 不用源码结构检查替代真实工程结果 |

阶段顺序为 P0 → Flutter/Native P1 → 删除语义评估。P0 是正确性修复，不能等待输入覆盖一起完成；P1 中 Flutter 与 Native 相互独立，可以分别落地。AGP 3.4 和自定义构建系统保持安全降级，不为扩大版本数字引入平行实现。

### 12.5 后续验证计划

本节只定义后续实施需要取得的证据，本次方案更新不执行测试。

| 层级 | owner/场景 | 修改前应证明 | 修改后结果 |
|---|---|---|---|
| L1 Flow | `ExternalBuildFlowTest`：两个 module 中一个可解析、一个不可解析 | 当前只保留可解析 build，存在全量成功标记风险 | 未启动 runner，全部 external 输入失败并交由上层回退 |
| L2 | `JuggCompileHelperTest`：unsupported metadata 与 supported metadata 混合 | 预检可能未整体阻断 | 在进入 incremental compiler 前选择完整 Gradle |
| Reader/序列化 | Flutter path package、Native 共享 source tree | 当前 metadata 未包含工程外真实 source root | sourceDirs 往返后保持实际绝对路径和工程搬迁语义 |
| 文件变化 | pubspec、Flutter asset、汇编、生成器输入 | 当前只覆盖 `.dart` 和有限 C/C++ 扩展名 | 仅声明支持的输入进入 external build，生成输出目录继续忽略 |
| L3 Flutter | legacy pack、modern pack、modern copy；assets-only 与 native changed 分开 | 模拟 task 不能证明真实产物及生命周期 | Dart/asset 行为变化可见；`.so` 变化走 APK 重装；assets-only 记录实际 engine 生命周期 |
| L3 Native | AGP 3.4、7、8、9 的标准 CMake/ndk-build | 3.4 缺 modern merge task；现代版本仅有结构证据 | 3.4 明确回退；现代版本返回值变化生效且未误跑宿主 assemble |

### 12.6 文档和支持声明

后续实现时同步知识库及 Wiki，并保持以下边界一致：

- Flutter `packLibs`、`packJniLibs`、`copyJniLibs` 是已支持的已知任务结构，不等同于承诺所有未来 Flutter 版本。
- AGP 3.4 支持 Jugg 完整 Gradle 工作流，但不支持本功能的 Native 增量执行；AGP 3.5+ 仍按 task 和输出能力动态判断。
- 无法可靠识别 source root、输入类型、task 或产物位置时回退完整 Gradle，不扫描旧中间目录伪造成功。
- 缓存 FlutterEngine 不是 native 更新路径的剩余风险；仅对没有变化 `.so` 的 assets-only Debug 场景保留真实验收项。
