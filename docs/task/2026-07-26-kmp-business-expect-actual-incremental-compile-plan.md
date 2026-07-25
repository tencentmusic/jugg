# 普通 KMP 业务源码 expect/actual 增量编译支持方案

> 状态：阶段 2 project-info、阶段 3 complementary cache 查询和阶段 4 tracker/cache 原地写回已完成；下一步进入阶段 5 产物与 E2E。中间 source set fragment graph 与 Kotlin 1.9 baseline 旧 actual 隔离已拆分到 `2026-07-26-kmp-business-expect-actual-follow-up-todo.md`。本方案不以全量编译 `commonMain`/`androidMain` 为兜底，不建立 Jugg 自有 expect/actual 语义索引。

## 1. 最终结论

普通 KMP 业务源码采用以下增量闭包：

```text
changed Kotlin files
  -> 按需读取 Gradle baseline 的 complementary-files cache
  -> 补充 Kotlin compiler 已记录的 complementary files
  -> 仅在本轮最终源码输入中标记 common source files
  -> 同一次 Kotlin/JVM invocation 编译 expect + actual
  -> 编译成功后用 ExpectActualTrackerImpl 原地更新同一份 cache
```

核心原则：

1. 不全量编译 common/platform source set。
2. 不按文件名配对 expect/actual。
3. 不使用 Kotlin PSI、ConstRefEngine 或声明签名索引查找 counterpart。
4. 不解析 `complementary-files.tab/.keystream/.values.at`。
5. 复用 Gradle full build 已生成的 class、Kotlin metadata 和 complementary cache，并由 Jugg 增量维护。
6. `analyzeSource()` 对 `expect`/`actual` 的识别只用于按需触发 cache 查询，不用于推断完整 source-set 图。
7. `kotlinCommonSourceDirs` 只负责标记本轮已有输入中的 common 文件，不扩展为目录全量源码。
8. cache、common source 信息或单侧新增信息缺失时遵循 best effort：记录 debug 日志并继续调用 Kotlin compiler，由编译器决定成功或失败；Jugg 不主动回退 Gradle。
9. 只有 Kotlin compiler 成功后才允许更新 cache、进入 D8、staging 和 deploy 链路。
10. 兼容逻辑基于 Gradle task、source identity 和项目 Kotlin compiler 能力，不增加 Kotlin/Compose 精确版本白名单。

## 2. expect/actual 编译原理

### 2.1 Gradle KMP 模型与 Kotlin CLI 的边界

Gradle/Kotlin Multiplatform plugin 管理 source set、`dependsOn`、target 和 compilation。Kotlin/JVM compiler 最终只接收：

1. 本次参与编译的源码文件。
2. 这些源码中哪些属于 common compilation。

对应 CLI 参数：

```text
-Xmulti-platform
-Xcommon-sources=<本次源码输入中的 common 文件>
```

`-Xmulti-platform` 启用 expect/actual 语义；`-Xcommon-sources` 标记同一次 JVM invocation 中的 common 输入。它不会自动读取 Gradle source-set 图，也不会自动查找或加入 counterpart。

例如只增量编译一对文件：

```text
kotlinc \
  -Xmulti-platform \
  -Xcommon-sources=/project/src/commonMain/PlatformLabel.kt \
  /project/src/commonMain/PlatformLabel.kt \
  /project/src/androidMain/PlatformLabel.android.kt
```

`-Xcommon-sources` 是 Kotlin/JVM 多平台联合编译机制，不是 Compose Resources 专属能力。Compose Resources 只是当前 Jugg 中第一个生产调用方。

### 2.2 已验证的最小条件

本地 Kotlin CLI 实验已确认：

| 输入/参数 | 结果 |
|---|---|
| 只有 expect，无 KMP 参数 | 失败 |
| expect + actual，无 KMP 参数 | 失败 |
| expect + actual，只有 `-Xmulti-platform` | 失败，actual 找不到 expect |
| expect + actual，`-Xmulti-platform` + `-Xcommon-sources=<expect file>` | 成功 |
| 只有 expect，两个 KMP 参数齐全 | 失败，缺少 actual |

`-Xexpect-actual-classes` 只抑制 warning，不解决配对或输入缺失。

### 2.3 baseline classpath 能承担的职责

Gradle full Android Kotlin compilation 已把未变化的普通 common 源码产出为 JVM class 和 Kotlin metadata。现有 Jugg 同时提供：

- Kotlin output 目录进入 classpath。
- `-Xfriend-paths=<kotlinClassPath>`。
- 稳定的 module name。
- `.kotlin_module` 增量合并。

因此未变化的普通 common helper/type 不需要为了 expect/actual 编译再次作为源码加入。inline、top-level、typealias、internal 等能力由 class、metadata、module mapping 和 friend path 提供。

不能由 classpath 替代的是：

- 本轮 changed source 自身。
- expect/actual counterpart source，因为 actualization 是源码联合编译关系。
- baseline 后新增且尚无 class/metadata 的声明。
- 当前源码在本次 invocation 中的 common/platform 身份。

## 3. 当前调用链与准确缺口

### 3.1 普通 Kotlin 源码

```text
VFS / Git changes
  -> FileChangesHandler.filter()
  -> ChangedFile
  -> IncrementalCompilerHelper.compile()
  -> SourceCompiler
  -> KotlinCompiler.doModuleCompile()
  -> KotlinCompiler.analyzeSource()
  -> KotlinCompilerInvoker.compile()
  -> K2JVMCompilerIsolate.exec()
```

当前缺口：

1. `SourceCompiler` 只传递 changed Kotlin/JuggApt Kotlin，没有 complementary closure。
2. `KotlinCompiler.analyzeSource()` 只识别 Android Extensions、Compose、KSP/KAPT，不触发 KMP cache 查询。
3. `KotlinCompilerInvoker.Options.commonSourceFiles` 虽已存在，但普通 Kotlin 调用方从未设置。
4. `K2JVMCompilerIsolate.exec()` 使用 `K2JVMCompiler.exec(PrintStream, args)`，固定传入 `Services.EMPTY`，无法取得 `ExpectActualTracker` 结果。
5. `ModuleInfo.sourceDirs` 是 IDE/Gradle 合并后的扁平集合，已经丢失 common/platform 身份。

### 3.2 为什么现有 `sourceDirs` 不能标记 common 文件

`JuggProjectInfoMerger` 把 IDE 与 Gradle 的 `sourceDirs` 合并到一个列表。`FileChangesHandler` 只选择第一个包含 changed file 的 root，`CompileFile` 没有 source-set role；`BaseCompiler` 又按 `moduleRootDir` 折叠同根 module。

因此以下信息都不能稳定证明 source-set 身份：

```text
ModuleInfo.moduleType
ModuleInfo.sourceDirs
CompileFile.module
CompileFile.baseDir
IDE module name suffix
src/<sourceSetName> path segment
```

普通业务源码不能复制 Compose Resources 中按名称/目录兼容识别的内部逻辑。

### 3.3 Compose generated 路径

```text
ComposeResourceCompiler
  -> generator 产生 allFiles/commonFiles
  -> 同一 Kotlin invocation
  -> Options.commonSourceFiles
  -> -Xmulti-platform
  -> -Xcommon-sources
```

Compose generator 已知 generated file 的 source-set 身份，所以能够工作。普通业务源码应复用 `KotlinCompilerInvoker.Options.commonSourceFiles` 和 compiler invocation 能力，不暴露或依赖 `ComposeResourceCompiler` 的生成细节。

## 4. 备选方案与取舍

### 4.1 source-set 全量编译

每次变化都编译整个 common/platform source set。

优点是正确性直观；缺点是严重违背 Jugg 增量原则，放大 Kotlin、D8、影响分析和 staging 范围。本方案明确排除。

### 4.2 PSI/语义索引精确配对

用 Kotlin PSI、Analysis API、ConstRefEngine 或自有声明索引计算 expect/actual 配对。

它需要处理 overload、嵌套声明、property/accessor、constructor、`actual typealias`、新增声明及多版本 Kotlin frontend，成本高且重复 Kotlin compiler 已有能力。本方案明确排除，只有 complementary cache 被证明无法覆盖真实工程后才重新评估。

### 4.3 Gradle baseline cache + Jugg 原地维护（采用）

Gradle full build 建立初始 complementary cache；Jugg 查询 changed file 的 complementary closure，编译成功后用 Kotlin compiler tracker 更新原 cache。

优点：

- 闭包小，不按 source set 全量编译。
- 不依赖文件名和源码语义解析。
- 直接使用 Kotlin compiler 的真实匹配结果。
- 与 Jugg 当前原地维护 `.class`、`.kotlin_module` 的策略一致。
- 无额外持久化索引文件。

代价：

- Kotlin incremental cache 属于内部 API，需要项目版本 classloader 和结构兼容。
- baseline cache 缺失时只能 best effort。
- 全文件删除仍延续 Jugg 现有“不处理删除”的边界。

## 5. 数据模型

### 5.1 `ModuleInfo.kotlinCommonSourceDirs`

在 Android owner `ModuleInfo` 增加：

```kotlin
/**
 * Kotlin source roots treated as common sources by the selected Android compilation.
 */
val kotlinCommonSourceDirs: List<File> = emptyList()
```

选择 `kotlinCommonSourceDirs` 而不是 `kmpCommonSourceDirs`，因为该字段对应选中 Android `KotlinCompile` task 的 `commonSourceSet` 和 JVM compiler `commonSourceFiles`，不表示项目中所有 KMP target 的完整 common source-set 图。

字段必须保持非空类型，空列表同时覆盖非 KMP module 和 best-effort 收集失败。检测到 expect/actual 但列表为空时打印 debug 日志，继续编译。

### 5.2 收集来源

复用 `GradleProjectInfoReader.findKotlinTask()` 找到：

```text
compile<Variant>Kotlin
compile<Variant>KotlinAndroid
```

从该 task 读取 KGP 的 `commonSourceSet`。本地检查 Kotlin 1.9.22、2.1.0、2.3.20 KGP 均存在结构化 getter：

```text
getCommonSourceSet$kotlin_gradle_plugin_common()
```

读取遵循现有 `Reflector` 能力判断，不按版本号分支。

目录提取优先级：

1. 优先从 `commonSourceSet` 的 source collection/root 对象取得配置 source roots。
2. 若只能取得展开文件，则将 common files 与 task/module 已配置 source roots 做最长父目录匹配，得到 common roots。
3. 不根据 `commonMain`、`sharedMain`、`androidMain` 等名称或 `src/<name>` 路径猜测。
4. 无法取得时保存空列表并输出 debug，不阻断 project-info 或编译流程。

`kotlinCommonSourceDirs` 可以同时包含 `commonMain`、`sharedMain` 等中间 source roots。运行时只将最终输入与 roots 求交，因此不会触发目录全量编译。

### 5.3 序列化与合并链

新增字段需同步：

- `JuggProjectInfoSerialize`
- `ProjectInfoSerializerInGradle`
- `JuggProjectInfoMerger`
- `CmdLineContextManager`
- `LibrariesBackupHelper`
- `main/src/main/resources/gradle/readProjectInfo.gradle.kts`

Gradle 值是 authoritative source。IDE snapshot 不根据 module name/path补值。合并时采用 Gradle 的 `kotlinCommonSourceDirs`；读取失败保留空列表。

## 6. complementary cache 定位和读取

### 6.1 cache 定位

复用 `ModuleBuildPathInfo`：

- `projectRootDir`
- `buildDir`
- `buildVariant`
- `kotlinClassPath`

候选 task 与 `GradleProjectInfoReader.findKotlinTask()` 一致：

```text
compile<Variant>Kotlin
compile<Variant>KotlinAndroid
```

对应 target data root：

```text
<buildDir>/kotlin/<taskName>/cacheable/caches-jvm/jvm
```

`IncrementalJvmCache` 会自行在该目录后追加 `kotlin`，所以不能把 `.../jvm/kotlin` 直接作为构造参数。

仅当候选中存在唯一含 `kotlin/complementary-files.tab` 的目录时使用。零个或多个候选均打印 debug 并返回空 complementary set。

### 6.2 project-root path converter

构造 `IncrementalCompilationContext` 时必须使用：

```text
RelocatableFileToPathConverter(ModuleBuildPathInfo.projectRootDir)
```

真实 Kotlin 1.9 demo cache 已验证：默认 context 能打开 cache 但查询为空；使用 project-root relocatable converter 后，expect 和 actual 两个方向都能查询成功。

### 6.3 按需读取

`KotlinCompiler.analyzeSource()` 对本轮 changed Kotlin 内容做宽松 token 检测：

```text
存在 expect/actual token -> 尝试读取 cache
不存在 -> 保持普通 Kotlin 快路径
```

该检测只控制是否查询 cache。注释或字符串导致的误报最多增加一次查询，不会直接决定 `commonSourceFiles`。不得为了精确触发引入 PSI/语义解析。

读取流程在项目 Kotlin isolated classloader 中执行：

```text
create IncrementalCompilationContext
create IncrementalJvmCache
getComplementaryFilesRecursive(changed files)
close cache in finally
```

返回文件过滤不存在路径并 canonical 去重。读取异常只记 debug，返回空集合并继续原任务。

## 7. 单次增量编译输入

### 7.1 最终源码闭包

```text
requestedFiles = 原本进入 Kotlin SourceCompiler 的 changed/generated inputs
complementaryFiles = cache.getComplementaryFilesRecursive(requested Kotlin files)
sourceFiles = requestedFiles + existing complementaryFiles，按 canonical path 去重
commonSourceFiles = sourceFiles 与 owner.kotlinCommonSourceDirs 的归属交集
```

`commonSourceFiles` 必须是 `sourceFiles` 子集。未变化的普通 common helper 不加入源码输入，由 baseline classpath 提供。

### 7.2 common 文件标记

对每个最终输入文件使用最长 root 匹配：

```text
file 位于 kotlinCommonSourceDirs 任一 root 下 -> commonSourceFiles
否则 -> platform/ordinary source input
```

某个文件无法归类时遵循 best effort：打印 owner、文件和已知 roots 的 debug 日志，不主动失败。若缺少 KMP 参数或 counterpart，最终由 Kotlin compiler 产生诊断。

### 7.3 已有、新增和修改场景

| 场景 | 输入处理 | 预期 |
|---|---|---|
| 修改已有 expect 文件 | cache 补 actual；expect 标记 common | 成功 |
| 修改已有 actual 文件 | cache 补 expect；expect 标记 common | 成功 |
| 同时修改 expect + actual | task 已含两侧；cache 结果去重 | 成功 |
| 同一文件含多个 expect/actual declaration | 按文件 cache closure；声明匹配交给 compiler | 成功 |
| expect/actual 文件名不同 | 不按文件名；使用 cache | 成功 |
| `actual typealias` | counterpart source 联合编译 | 成功 |
| 新增 pair 且两侧都 changed | 两侧来自 requested files；tracker 建立新 cache edge | 成功 |
| 只新增 expect 或 actual | 无历史 edge，按现有输入继续编译 | compiler 给出原始诊断 |
| 修改普通 common 文件 | 保持 requested source；不主动打开 cache（无 token 时） | 沿用现有普通 Kotlin 增量编译 |
| 普通 common 与 expect/actual 同批 changed | requested + complementary；common root 交集标记 | 成功 |
| 中间 `sharedMain` 文件 | 若该 root 在 `kotlinCommonSourceDirs` 中则标记 common | 与 Gradle Android compilation 一致 |
| common roots 缺失 | debug，按可构造输入继续 | 由 compiler 决定 |
| cache 缺失/损坏 | debug，不补 complementary | 由 compiler 决定 |
| complementary 文件不存在 | 过滤缺失路径，继续 | 由 compiler 决定 |
| 文件删除 | 沿用 Jugg 全局忽略删除策略 | 本功能不清理旧 class/cache |

### 7.4 invocation owner

最终 invocation 使用当前 compile group 的 Android owner `ModuleInfo`：

- classpath：`ICompileContext.getModuleDependencies(owner, task)`。
- friend path：`owner.buildPathInfo.kotlinClassPath`。
- module name：`${owner.gradleModuleName ?: owner.name}_${owner.buildVariant}`。
- output：`owner.buildPathInfo.kotlinClassPath`。
- JVM target、free compiler args、plugins：继续读取 owner。

不创建 KMP 专用 classpath、output 或 APK ownership。

## 8. ExpectActualTracker 与 cache 原地更新

### 8.1 tracker 注入

当前 `K2JVMCompilerIsolate.exec(PrintStream, args)` 使用 `Services.EMPTY`。KMP invocation 需要在项目 Kotlin classloader 中执行等价底层流程：

```text
compiler.createArguments()
compiler.parseArguments(args, compilerArguments)
compilerArguments.incrementalCompilation = true

tracker = ExpectActualTrackerImpl()
services = Services.Builder()
    .register(ExpectActualTracker, tracker)
    .build()

compiler.exec(messageCollector, services, compilerArguments)
```

关键事实：只注册 `ExpectActualTracker` 不够。Kotlin compiler 仅在 `incrementalCompilation=true` 时把 tracker 放入 compiler configuration。Kotlin 1.9.22 实验已验证：未开启时编译成功但 map 为空；开启后得到真实 `expect -> actual` 文件映射。

该开关只在需要 tracker 的 KMP invocation 启用，普通 Kotlin 快路径保持现状。

### 8.2 版本结构兼容

Kotlin 1.9/2.1/2.3 均具备：

- `IncrementalJvmCache.getComplementaryFilesRecursive()`
- `IncrementalJvmCache.updateComplementaryFiles()`
- `ExpectActualTrackerImpl.getExpectToActualMap()`
- `Services.Builder.register()`

结构差异：

- 1.9/2.1 `IncrementalJvmCache` 使用三参数构造器。
- 2.3 增加 `SubtypeTracker`，使用项目版本的 `SubtypeTracker.DoNothing`。
- 2.3 tracker 增加 lenient stub 结果，存在能力时一并保留。
- complementary storage 在 1.9 与 2.1+ 的底层 map 类型不同，但 Jugg 不直接访问底层 map。

所有差异通过 isolated classloader 的类/构造器/方法能力判断处理，不按精确版本分支。

### 8.3 写回协议

只在最终一次 Kotlin compile 返回 `ExitCode.OK` 后写回：

```text
dirtyClosure = requested changed files + compile 前查询到的 complementary closure
reopen IncrementalJvmCache
updateComplementaryFiles(dirtyClosure, tracker)
flush
close in finally
```

`updateComplementaryFiles()` 会：

1. 删除 dirty files 的旧记录。
2. 写入 tracker 的 `expect -> actual`。
3. 自动写入 `actual -> expect` 反向记录。

必须传完整 dirty closure，不能只传单侧 changed file，否则另一方向的旧 edge 可能残留。

cache 更新失败只记 debug，不改变已成功的编译、D8 和部署结果。失败 compile、自动 retry 的中间 attempt、KSP-only phase 均不得写 cache；只使用最终成功 invocation 的 tracker。

跨进程 execution mode 暂不传递 tracker。普通 Kotlin 当前使用 in-process；未来确有 KMP 跨进程需求时再增加结构化结果传输。

## 9. best-effort、失败与回退策略

### 9.1 Jugg 不主动失败的场景

以下情况统一 debug 日志并继续：

- `kotlinCommonSourceDirs` 为空或读取失败。
- 某个最终输入无法匹配 common root。
- Kotlin incremental cache 不存在、候选不唯一、打开或读取失败。
- cache 中不存在 changed file 的 complementary edge。
- 单侧新增 declaration 无历史 edge。
- complementary path 已不存在。
- cache 更新、flush 或 close 失败。

Jugg 不为这些情况主动触发 Gradle fallback。Kotlin compiler 的 expect/actual 诊断是最终裁决。

### 9.2 不产生错误后续产物

best effort 不等于忽略 compiler 失败：

- Kotlin compile 失败：保留原始 diagnostic，不进入 D8/staging，不更新 complementary cache。
- Kotlin compile 成功：按现有链路处理所有报告的 class output。
- cache 更新失败：不撤销成功编译产物，也不降级 Gradle。

### 9.3 删除边界

Jugg 现有增量体系普遍忽略不存在文件，本功能保持一致：

- 全文件删除、重命名后的旧 class 清理不在范围内。
- complementary 查询返回的不存在文件直接过滤。
- 文件仍存在但声明关系改变时，成功 compile 的 tracker 可以刷新或清除本轮 dirty closure 的 edge。

## 10. D8、影响分析与 APK ownership

不新增 KMP 专用产物链：

```text
KotlinCompilerInvoker class outputs
  -> SourceCompiler.compileDexOutputs()
  -> DexCompiler file-per-class D8
  -> CompileOutput(Dex, apkPath, targetApkPaths)
  -> IncrementalCompilerHelper.addStagingFiles()
  -> DeployDataGenerator / CompileEffectAnalyzer
  -> Full Swap
```

`CompileEffectAnalyzer#getRecompileFiles` 发生在 Kotlin compile 和 D8 成功之后，负责已有 bytecode 影响传播，不参与 expect/actual counterpart 查找。

所有补充 source 仍在 Android owner invocation 中编译，所以 class output、D8 参数、staging 和 module-to-APK ownership 沿用现有逻辑。测试必须证明 complementary source 产生的 class/dex 进入正确 APK，而不只验证 Kotlin CLI 成功。

## 11. 测试设计与 TDD 清单

下一阶段必须先补失败测试，确认失败原因是当前缺少 complementary lookup、common root 标记或 tracker 写回，再修改生产代码。不得为测试向生产代码注入 provider/supplier/factory/override lambda。

### 11.1 L1：确定性能力与数据链

| 测试路径 | 场景与关键断言 |
|---|---|
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerInvokerArgsTest.kt` | 只有 common subset 非空时添加两个 KMP flags；common source 去重且保持顺序；普通 Kotlin 参数不回归 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinComplementaryFilesCacheTest.kt` | 用真实 Kotlin cache fixture 双向查询；dirty closure 原地更新后双向 edge；读取/写入异常 best effort；1.9/2.1/2.3 capability adapter |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinExpectActualTrackingTest.kt` | 注入项目版本 tracker；开启 incremental tracking 后得到 expect/actual 关系；编译失败不写 tracker 结果 |
| `main/src/test/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerializerAndroidTestTest.kt` | `kotlinCommonSourceDirs` 非空/空列表 round trip，兼容旧数据默认空列表 |
| `main/src/test/java/com/sickworm/intellij/jugg/project/merger/JuggProjectInfoMergerAndroidTestTest.kt` | Gradle common roots authoritative；IDE 扁平 sourceDirs 不覆盖；读取失败保留空列表 |
| `idea/src/test/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradleTest.kt` | Gradle JSON 保留 `kotlinCommonSourceDirs`，自定义 build dir 不影响 roots |

这些测试覆盖复杂 cache 协议、compiler service 注入和序列化，属于允许的 L1 域内测试，不为单一编排 Helper 创建 Mockito-only 测试。cache/tracker 的生产 API 均由对应 L1 红灯驱动建立，不为预设测试结构增加额外生产抽象。

### 11.2 L2：真实 Gradle/Kotlin/D8 协作

扩展：

```text
idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggCompilerTest.kt
  -> KmpComposeFlowReproTest
```

必须加入以下真实 demo 场景：

1. 修改 commonMain expect 文件，只把 changed expect 交给上层；断言 command 自动包含 Android actual、KMP flags，编译成功。
2. 修改 androidMain actual 文件；断言自动包含 common expect 且 expect 位于 `-Xcommon-sources`。
3. 同时修改 expect 和 actual；断言源码去重并成功。
4. 新增 expect/actual declaration，并修改 Android App source 调用；断言 tracker 建立新双向 cache edge，class/D8/staging 成功。
5. 新增场景使用不同文件名，证明配对不依赖文件名。
6. commonMain 普通非 expect 文件单独变更，保持现有普通 Kotlin 增量编译，不强制 KMP flags。
7. expect 文件引用未随本轮编译的普通 common helper/type；断言从 Gradle baseline classpath 解析成功，command 不包含该 helper source。
8. 中间 `sharedMain` root 参与 Android compilation 的验收暂由独立 TODO 跟踪；需要 fragment graph，不能只依赖 `-Xcommon-sources`。
9. `kotlinCommonSourceDirs` 缺失、新增单侧 declaration、complementary cache 缺失合并为 best-effort 失败组：不主动 fallback，由 Kotlin compiler 给出最终结果，不产生错误 D8/staging 产物。
10. generated class 进入 file-per-class D8、staging，并属于正确 app APK/`targetApkPaths`。
11. 现有 Compose generated expect/actual 场景继续作为回归，不另建重复 fixture。
12. Kotlin 2.1 覆盖 commonMain/androidMain 完整场景，2.3 覆盖 expect-only changed 和 actual-only changed；1.9 baseline 旧 actual 隔离由独立 TODO 跟踪。

L2 必须检查实际 compiler command、真实 class、dex、staging 和 cache，而不是只断言 helper 返回文件列表。

### 11.3 L3：Android Studio 真实部署

扩展：

```text
idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt
  -> KmpComposeDeployFlowTest
```

主场景：

1. 使用 `jugg:app` 完成一次真实 Gradle/Jugg baseline install。
2. 修改 common expect 所在文件的普通实现，点击同一个 Run，runtime 读取 common 新结果。
3. 再修改 Android actual 返回值，点击同一个 Run，runtime 同时读取 common 和 actual 新结果。
4. 验证全过程没有 Gradle fallback task。
5. 验证 Full Swap 后 runtime/logcat 读取到每轮新结果。
6. 验证 complementary source 产生的 dex 位于正确 staging 和 APK ownership。

Kotlin 2.1 作为 L3 主 profile。新增、双侧同时修改和跨版本结构兼容由 L2 证明，不在 L3 重复排列。

### 11.4 定向命令

```bash
./gradlew :main:test \
  --tests 'com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvokerArgsTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinComplementaryFilesCacheTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinExpectActualTrackingTest' \
  --tests 'com.sickworm.intellij.jugg.project.data.JuggProjectInfoSerializerAndroidTestTest' \
  --tests 'com.sickworm.intellij.jugg.project.merger.JuggProjectInfoMergerAndroidTestTest'

./gradlew :idea:test \
  --tests 'com.sickworm.intellij.jugg.gradle.script.ProjectInfoSerializerInGradleTest' \
  --tests 'com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest'

./gradlew :idea:test \
  --tests 'com.sickworm.intellij.jugg.manager.KmpComposeDeployFlowTest'

./gradlew :idea:compileKotlin
```

禁止无 `--tests` 的全量 `:main:test` / `:idea:test`。

## 12. Android Studio E2E 手测

### 12.1 前置

1. 选择 Kotlin 2.1 demo profile并 Gradle Sync。
2. 用 `jugg:app` 完成一次完整 baseline install/run，确保 complementary cache 已存在。
3. 记录 `build/jugg/log/compile_latest.log` 起点。
4. runtime probe 输出 common marker、actual result 和新增 API result。

### 12.2 操作

1. 修改 `PlatformLabel.kt`，Run。
2. 修改 `PlatformLabel.android.kt`，Run。
3. 同时修改两者，Run。
4. 新增一对 declaration 并由 App 调用，Run。
5. 修改普通 common helper，Run。
6. 修改 Compose resource，Run。
7. 删除/移走 complementary cache 后修改已有 actual，确认不主动 Gradle fallback，由 Kotlin compiler 给出结果。

### 12.3 验收信号

- command 只包含 requested + complementary files，不包含整个 common/platform source set。
- expect/actual invocation 包含 `-Xmulti-platform` 和正确 common subset。
- 日志不包含 Jugg 主动执行的 Gradle fallback task。
- compiler 失败时无新增 D8/staging/cache edge。
- compiler 成功时 class、dex、staging 和 app APK ownership 正确。
- Full Swap 后 runtime 读取新结果。
- cache 更新失败仅出现 debug，不撤销成功部署。
- Compose generated 和普通 Kotlin 路径不回归。

Kotlin 1.9/2.3 profile 重复 common-only、actual-only 和 Compose 回归，并在切换 profile 后重新 Sync/baseline。

## 13. 分阶段实施

### 阶段 1：失败测试和 fixture

1. 扩展 KMP demo：跨文件普通 common helper、文件名不同、`sharedMain` fixture、runtime probe。
2. 写 `KmpComposeFlowReproTest` L2 和 `KmpComposeDeployFlowTest` L3 失败场景，先固定用户可见行为。
3. 确认代表性用例因当前缺少 complementary lookup、common root 标记和 KMP 参数而失败，不因 fixture 或环境错误失败。
4. 在每个生产实现切片开始前，补对应 cache/tracker/serialization/merge L1 失败测试，再实现该切片。

### 阶段 2：project-info（已完成）

1. 已增加非空 `ModuleInfo.kotlinCommonSourceDirs`，旧 project-info 缺失字段时恢复为空列表。
2. 已从选中 Kotlin task 的 `commonSourceSet` 结构收集 roots，不依据目录名推断。
3. 已同步序列化、Gradle script、merge 和 CLI 链；Gradle common roots 同时加入扁平 `sourceDirs`，merge 保证 `kotlinCommonSourceDirs` 是其 authoritative 子集；backup 通过 `ModuleInfo.copy` 原样保留项目内 roots。
4. Kotlin 1.9/2.1/2.3 profile 的 project-info 定向断言均已通过，均采集到 `commonMain`、`sharedMain` 且未采集 `androidMain`。

### 阶段 3：cache 查询和编译输入（当前边界已完成）

1. 在项目 Kotlin isolated classloader 中增加 cache capability adapter，不增加单实现接口。
2. 根据 `ModuleBuildPathInfo` 定位 cache，并使用 project-root converter。
3. `analyzeSource()` 仅在 expect/actual token 出现时触发查询。
4. 合并 requested + complementary files。
5. 根据 `kotlinCommonSourceDirs` 计算 `commonSourceFiles`。
6. 已验证 Kotlin 2.1 commonMain/androidMain 双向闭包、双侧去重、普通 common 快路径和 best effort；Kotlin 2.3 expect-only/actual-only 通过。
7. `sharedMain` fragment 参数和 Kotlin 1.9 旧 actual 隔离不在本切片继续扩展，见独立 TODO。

### 阶段 4：tracker 和原地写回（已完成）

1. 为 KMP invocation 构造 compiler arguments、Services 和项目版本 tracker。
2. 开启 compiler arguments 的 incremental tracking。
3. 保持现有 output parser 消息格式。
4. 最终成功 compile 后用完整 dirty closure 更新 cache。
5. 所有 cache 异常只记 debug。
6. Kotlin 1.9 L1、Kotlin 2.1 新增 pair 双向 edge L2、Kotlin 2.3 profile 均已验证；失败 compile 保留旧 edge，写回失败不影响成功结果，Compose generated 路径保持回归通过。

### 阶段 5：产物和 E2E（当前边界已完成）

1. Kotlin 2.1 的 common-only、actual-only 和新增 pair L2 已验证 class -> D8 -> staging -> deploy data。
2. 设备 L3 已验证 common-only 与 actual-only 的 app APK ownership、连续 Full Swap 和 runtime 新值；Compose resource runtime 回归同时通过。
3. Kotlin 1.9/2.1/2.3 project-info 均验证 common roots 同时进入 `sourceDirs` 和 `kotlinCommonSourceDirs`；Kotlin 2.3 业务 L2 已通过。Kotlin 1.9 完整业务闭包仍按独立 TODO 跟踪，不在本阶段扩展。
4. `KmpComposeDeployFlowTest` 已在 `emulator-5554` 完成 runtime 验收，增量日志无 Jugg 主动 Gradle fallback。

### 阶段 6：知识库同步和提交

实现通过后更新知识库、方案状态和 changelog（如本次发布要求），按仓库规则提交功能代码。

## 14. 风险与后续核查

1. **KGP common roots 读取**：Kotlin 1.9/2.1/2.3 的真实 Gradle project-info 已验证可从 `commonSourceSet` backing collection 读取 configured roots；结构读取失败时仍保持空列表和 best effort。
2. **cache 内部 API**：构造器和 storage 类型跨版本变化，必须保持结构能力适配和真实 cache fixture 测试。
3. **其他 Gradle IC maps 不由 Jugg 维护**：本功能只维护 complementary relation；`getComplementaryFilesRecursive()` 还可能读取 sealed/type 等其他 cache 信息，它们可能随 Jugg 编译逐步陈旧。expect/actual 直接 edge 仍由本方案维护，其他递归影响不在本功能承诺内。
4. **同根 module ownership**：最终 invocation 必须使用 Android owner；不能依赖 `FileChangesHandler` 首个 root 或 IDE module 顺序。
5. **普通 common classpath**：跨文件 helper/type 必须由真实 L2 证明 baseline classpath 足够；不能仅依赖 CLI toy test。
6. **阶段 3 后续缺口**：中间 source set 需要 Gradle fragment graph；Kotlin 1.9 需要隔离 dirty closure 的 baseline 旧 actual output，统一记录在 `2026-07-26-kmp-business-expect-actual-follow-up-todo.md`。
7. **tracker 输出**：retry、KSP phase 和 compiler recreation 时只能保存最终成功 invocation 的 tracker。
8. **删除**：全文件删除仍会留下旧 class/cache，沿用 Jugg 已知边界。
9. **source threshold**：闭包来自 cache，通常很小；仍受现有 Kotlin source points/compile task限制，但不为本功能新增阈值或设置项。
10. **advanced compiler options**：`-Xcommon-sources` 是高级参数，未来版本如移除，由项目 compiler capability 失败和原始 diagnostic 暴露，不增加版本白名单。

## 15. 文档同步清单

实现完成后同步：

- `docs/ai_knowledge/02_compile_source.md`
  - 增加普通 KMP complementary cache、common roots、tracker 写回和 best-effort 行为。
  - 删除“仅 Kotlin 2.1.x + Compose 1.7.3”的陈旧描述，改为结构识别及 1.9/2.1/2.3 实测事实。
- `docs/ai_knowledge/04_engineering_project.md`
  - 增加 `kotlinCommonSourceDirs` 的 Gradle读取、序列化和 merge 规则。
- `docs/ai_knowledge/06_testing.md`
  - 如需增加 KMP cache/tracker/Flow 路由，只补测试索引，不改变分层原则。
- `docs/ai_knowledge/98_code_map.md`
  - 增加 common root 收集、cache adapter 和 tracker 调用链。
- `docs/task/kmp_compose_incremental_compile_support_plan.md`
  - 保留 Compose Resources 历史方案边界，并链接到普通业务源码方案。

## 16. 验收标准

- existing expect-only changed、actual-only changed、both changed 均自动补齐 counterpart 并成功。
- 新增双侧 declaration + App 调用成功，tracker 原地建立新 cache relation。
- 不按文件名、目录名称或声明签名配对。
- 不全量编译 common/platform source set。
- 未变化普通 common helper 从 baseline classpath解析。
- `kotlinCommonSourceDirs` 只标记本轮输入；direct common roots 已完成，intermediate fragment graph 由独立 TODO 跟踪。
- cache/common roots/单侧新增缺失遵循 best effort，不主动 Gradle fallback。
- compiler 失败不更新 cache、不进入 D8/staging。
- compiler 成功后的 class、D8、staging 和 APK ownership 正确。
- cache 写回失败不影响成功部署。
- Kotlin 2.1/2.3 当前路径不使用精确版本白名单；Kotlin 1.9 baseline 旧 actual 隔离由独立 TODO 跟踪。
- Android Studio 同一个 `jugg:app` Run 完成 Full Swap，runtime 读取新结果。
- Compose generated expect/actual、普通 Kotlin、KSP/KAPT 路径不回归。
