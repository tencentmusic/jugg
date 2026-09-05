# 编译系统：源码编译链（Java/Kotlin/Dex）

> 最后核对：2026-08-27
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页覆盖源码到 dex 的增量编译主链路：JuggApt/KSP/KAPT 生成源码、DataBinding mapper、Kotlin/Java 编译、dex 生成、minified 变体重映射。它重点记录阶段顺序、Kotlin 模块身份、脱糖上下文、失败降级和多 APK target 归属。

资源与 `R.java` 生成见 `02_compile_resource.md`；DataBinding 细节见 `02_compile_databinding.md`；release 混淆和 `_jugg_fix` 见 `02_compile_obfuscation.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `SourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | 模块内协调 JuggApt、DataBinding mapper、Kotlin、Java、Dex 与 minify |
| `JuggAptCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/JuggAptCompiler.kt` | 执行自定义生成源码处理器，输出 Java/Kotlin shadow sources |
| `IJuggAptProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/IJuggAptProcessor.kt` | JuggApt 处理器接口 |
| `SourceDataBindingProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceDataBindingProcessor.kt` | 在语言编译前生成 DataBinding mapper 所需 Java |
| `DataBindingGenMapperCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenMapperCompiler.kt` | DataBinding mapper 生成实现 |
| `KotlinCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompiler.kt` | Kotlin 源码编译入口 |
| `KotlinCompilerInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerInvoker.kt` | Kotlin CLI 参数、插件参数、错误解析与重试 |
| `IKmModuleMergerForCompilation` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/IKmModuleMergerForCompilation.kt` | 读取并合并模块 classpath 中的 `.kotlin_module`，保留顶层声明与 file facade 元数据 |
| `KotlinComplementaryFilesCache` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinComplementaryFilesCache.kt` | 按需定位并读取项目 Kotlin Gradle incremental cache 的 complementary files |
| `ComposeResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceCompiler.kt` | 在常规 source 阶段前，以一次 Kotlin invocation 编译 Compose generated expect/actual sources |
| `K2JVMCompilerIsolate` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/K2JVMCompilerIsolate.kt` | Kotlin 编译器隔离加载、classpath 检查、项目版本 ExpectActualTracker 注入与 incremental cache API 适配 |
| `JavaCompiler` / `JavaCompilerInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompiler.kt`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompilerInvoker.kt` | Java 编译与 javac 参数组装 |
| `DexCompiler` / `DexFileMaker` / `DexFileMerger` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/` | class 到 dex、file-per-class 输出、D8 脱糖上下文与 dex 合并 |
| `CompileEffectAnalyzer` / `DeployDataGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzer.kt`、`main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | 从 APK/deploy DB 识别 default interface 与 core library rewrite，补齐 D8 所需 classpath 和配置 |
| `DexMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt` | minified 变体的 dex 重映射与 `_jugg_fix` 生成 |

---

## 3. 核心数据流

| 数据 | 生产者 | 消费者 | 关键约束 |
|---|---|---|---|
| 原始 Java/Kotlin/Class 变更 | `JuggCompiler` 上游任务 | `SourceCompiler` | 按 module compile order 分组；androidTest module 使用 name + root 作为分组 key |
| JuggApt generated Java/Kotlin | `JuggAptCompiler` | `KotlinCompiler`, `JavaCompiler` | 会登记到 `ICompileContext.addChangedFile()`，避免落盘后本轮失败导致下轮漏编译 |
| DataBinding mapper Java | `SourceDataBindingProcessor` | `JavaCompiler` | Kotlin 源和 JuggApt Kotlin 会参与 mapper 处理输入 |
| Kotlin 编译输出 Java | `KotlinCompiler` | `JavaCompiler` | Kotlin 先编译，KAPT 等输出的 Java 再进入 Java 阶段 |
| Class 输出 | `KotlinCompiler`, `JavaCompiler`, 原始 Class 输入 | `DexCompiler` | class 编译失败时不会继续 dex；失败结果会 quick fail 其余文件 |
| Dex 输出 | `DexCompiler` | `DexMinifyCompiler` 或部署数据转换 | 非 minified 直接输出；minified 先输出到 `un_minify` 再重映射 |
| `targetApkPaths` | `DexCompiler`, `JavaCompilerInvoker`, `DexFileMerger` | 部署分流 | dex merge 会合并输入 dex 的 targetApkPaths 并保留并集 |
| Compose generated common/platform Kotlin | `ComposeResourceGeneratorBridge` | `KotlinCompilerInvoker` | 所有 generated 文件同批输入；common 文件通过 typed `Options.commonSourceFiles` 显式转为 `-Xmulti-platform -Xcommon-sources=...` |
| `kotlinCommonSourceDirs` | `GradleProjectInfoReader` | 普通 KMP Kotlin 增量编译 | 从选中 Android Kotlin task 的 `commonSourceSet` 结构或 FileTree relative path 读取；同时加入 `sourceDirs` 参与统一源码识别，自身保留 common 身份，不展开为全量编译 |
| `agpR8Classpath` | `GradleProjectInfoReaderManager` | `DexCompiler` / `DexMinifyCompiler` | 引用项目 AGP 的 R8 分发包；Gradle code source 已 instrumentation 时解析原始 buildscript artifact；不复制 jar，不进入 `FullBuildInfo` 或 compile context 磁盘格式 |
| 普通 KMP complementary closure | Kotlin Gradle incremental cache | `KotlinCompiler` | 仅 Android owner 存在 Gradle authoritative `kotlinCommonSourceDirs` 且源码出现 expect/actual token 时查询；requested 与 complementary files 按 canonical path 去重后在 Android owner invocation 中联合编译；成功后用 tracker 原地刷新双向 edge |
| Kotlin module identity | `ModuleInfo` + Kotlin baseline output | `KotlinCompilerInvoker` | `module-name`、friend path、输出目录和 `.kotlin_module` 必须保持同一 Gradle module/variant 语义 |
| Kotlin compiler plugin options | 选中 Kotlin Gradle task 的 `KotlinCompilerPluginData` | `KotlinCompilerInvoker` | 按模块保存 Gradle 已解析的 `plugin:<id>:<key>=<value>`，调用 CLI 时逐项配对 `-P`；当前 compilation 优先，参数为空时才回退最近父模块 |
| `DesugarInfo` | APK/deploy DB + changed class parser | `DexCompiler` / D8 | default interface、`j$.*` rewrite 与 `desugar.json` 都以已安装 APK 的脱糖事实为基线 |
| included build module roots | 主 Gradle project info + `include_build_*` project info | `BaseCompileContext` | 只按快照来源识别；主快照中的同目录模块优先，不根据模块是否位于工程根目录外推断 |

---

## 4. 核心调用链路

```text
SourceCompiler.doModuleCompile()
  -> prepareSourceCompile()
       -> JuggAptCompiler 收集 generated Java/Kotlin，并登记 changed files
       -> SourceDataBindingProcessor 生成 mapper Java
  -> compileLanguageStagesWithRetry()
       -> KotlinCompiler 先编译 Kotlin + JuggApt Kotlin
       -> JavaCompiler 再编译 Java + JuggApt Java + KAPT Java + DataBinding Java
       -> 若真实源码诊断直接指向 JuggApt 产物，移除 changed-file 登记并无 JuggApt 重试一次
  -> compileDexOutputs()
       -> DexCompiler 编译 class / 原始 class 输入
       -> minified 场景交给 DexMinifyCompiler；非 minified 直接返回 dex + 非 class 附属产物
```

这条链路的核心顺序不能随意调整：JuggApt/DataBinding 必须在语言编译前完成，Kotlin 必须早于 Java，minify 必须在 dex 之后执行。

### 4.1 Kotlin 模块身份与元数据

```text
KotlinCompilerInvoker
  -> 优先使用项目 Kotlin compiler；不可用时回退内置 compiler
  -> module-name = Gradle module name + build variant
  -> friend path 指向本模块 baseline Kotlin output
  -> Kotlin + 同模块 Java source roots 联合解析
  -> 非 KAPT 编译直接写入模块 Kotlin classpath
  -> 编译前后合并并保存 `.kotlin_module`
```

这组参数共同维护“本轮单文件仍属于原 Gradle 模块”的语义：

- `-module-name` 必须与 Gradle 基线一致，否则 `internal` 方法/属性的 JVM 名称后缀会变化，调用方可能在运行时出现 `NoSuchMethodError`。
- `-Xfriend-paths` 让本轮源码继续访问同模块 baseline 中的 `internal` 声明；KMP baseline 被隔离时，friend path 必须同步切到隔离视图。
- 非 KAPT 编译把 `-d` 指向模块 Kotlin classpath，使编译器把 baseline class 与本轮源码视为共同编译结果，避免 `public API property declared in different module` 一类误判和 smart cast 失效。
- `-Xjava-source-roots` 让 Kotlin 先读取本轮或同模块 Java 源码，解决 Java/Kotlin 相互引用；因此语言阶段固定 Kotlin 在前、Java 在后。
- `.kotlin_module` 承载 class 文件无法完整表达的顶层函数、扩展函数和 file facade 信息。单文件编译前后都要合并 baseline 与新元数据；失败时仅告警并保留主编译结果，但后续可能出现 extension unresolved reference 或影响传播缺失。

included build 的 Library/JavaLibrary 源码可能同时看到 included build 独立构建的 R 与主 APK 最终 R。IDE 场景会从 Gradle 快照来源保存 included module roots；命中后，`BaseCompileContext.getModuleDependencies()` 先放入推断目标 APK 的 `R.jar`，再补齐主 build 的 Application/Dynamic Feature `R.jar`，最后才放普通 module output。这样即使 base `R.jar` 不包含只存在于 split 的业务 R package，Kotlin/Java 仍会先命中 host feature 的最终资源 ID，而不会退回 included build 的独立 R。本轮 Jugg 生成的 temp classpath 仍保持最高优先级；普通主 build 模块、其他模块类型、host R 全部缺失或快照身份不完整时保持原 classpath 顺序。

### 4.2 D8 脱糖决策

```text
DexCompiler
  -> 解析 changed class 的 interface / static invocation
  -> 选择 D8 minApi：使用当前 module 归属 APK 的 owner variant minSdk（base APK 用 application，split 用 dynamic feature）；minSdk 不可读时回落 21
  -> 从 APK/deploy DB 查找 `$-CC` / `$DefaultImpls` 对应的 default interface
  -> 把这些 baseline class 复制到临时 D8 classpath
  -> APK 中存在 `j$.*` 时查找工程 coreLibraryDesugaring 的 `desugar.json`
  -> 使用项目 AGP D8；API 不兼容或执行失败时回退内置 D8
```

这里不能只按当前模块 `minSdkVersion` 判断是否脱糖。Jugg 的增量 DEX 必须和已安装 APK 保持同一种字节码形态：基线存在 `$-CC` / `$DefaultImpls` 时，D8 需要看到对应接口 classpath，避免 default method 调用形态与 APK 不一致；基线存在 `j$.*` 时，还需要把项目 `coreLibraryDesugaring` 依赖中的 `desugar.json` 传给 D8。找不到配置时会 warn 并继续，最终风险是高版本 Java API 在设备端引用不一致。

Compose resource generated source 是这条常规 source 链之前的独立前置步骤：`ComposeResourceCompiler` 将 Res、各 source set accessor、expect collector 和 Android actual collector 放进同一次 `KotlinCompilerInvoker` 调用，并显式传入 common source 文件列表。编译出的 class 随后才进入 `SourceCompiler` 的 class/dex 路径；不会分别编译 expect 与 actual。Gradle project info 仍可把 build directory 下的 generated source 保留在 `sourceDirs` 中，供 Kotlin compilation metadata 使用；`FileChangesHandler` 会在文件变更边界统一排除这些路径，避免它们再作为用户源码进入常规 Kotlin 阶段。JuggApt 等本轮由编译器直接登记的 generated source 不经过该文件事件过滤。

IDE 将 common source set 暴露为同根虚拟 module 时，普通 Kotlin 与 Compose resource 编译都先解析带 Gradle 配置的 Android owner；classpath、output、Compose metadata 和 APK ownership 不取虚拟 module 的扁平快照。

普通 KMP 业务源码走常规 Kotlin 阶段。`KotlinCompiler` 发现 expect/actual token 后切换到同根 Android owner，`KotlinCompilerInvoker` 用项目 Kotlin compiler 打开 Gradle cache，`getComplementaryFilesRecursive()` 返回本轮补充输入。in-process 项目 compiler invocation 会设置 `incrementalCompilation=true` 并注册项目版本 `ExpectActualTrackerImpl`；最终成功后用 requested+complementary closure 调用 `updateComplementaryFiles()`。cache 缺失、损坏、候选不唯一、无 edge 或写回失败时只记 debug；普通 Kotlin 文件不触发查询或 tracker。

K2 Gradle task 暴露 `multiplatformStructure` 时，project info 会保存 fragment 到 source roots、refines edge 和 default fragment。只有普通 KMP complementary invocation 才根据最终源码闭包附加 `-Xfragments`、`-Xfragment-sources`、`-Xfragment-refines`；Compose resource generated source 继续使用独立的 typed common-source 参数，不复用业务源码 fragment graph。旧 project info 或不支持该结构的 Kotlin task 保持空图并沿原路径编译。

Kotlin 1.9 的 baseline Kotlin output 可能同时包含 dirty expect/actual closure 的旧 JVM class。invoker 通过项目 incremental cache 的 source-to-output 关系定位这些 class，复制其余 baseline 到临时只读视图，并同时替换 classpath 与 friend path；正式 baseline 不被移动或删除。编译成功或失败都会删除临时视图，cache 读取失败则保持原路径交由 Kotlin compiler 判定。

`GradleProjectInfoReaderManager` 先读取 Android plugin 实际加载的 R8 code source；若路径位于 Gradle `jars-*` / `transforms-*` instrumentation cache，则从 Android module 或 root project 的 buildscript classpath 选择同名原始 artifact，找不到时不暴露该外部 runtime。`DexFileMaker` 再用独立 `URLClassLoader` 加载 `agpR8Classpath` 中的 D8，避免项目 AGP R8 与插件内置 R8 在同一 classloader 中发生类冲突；runtime 按 canonical path 缓存。路径缺失、类/方法加载失败、当前 desugared-library API 不受支持，或外部 D8 执行失败时都会回退到内置 R8。外部 D8 执行失败会打印用户可见的 `warn`，包含版本、路径和原始异常；若内置 R8 也失败，则由内置执行继续抛出最终异常。

---

## 5. 隐形约束 / 设计思路 / 已知边界

- `collectJuggAptGeneratedFiles()` 是 fail-open：处理器异常只 warn，然后继续主编译。不要把 JuggApt warn 直接等同于整轮编译失败。
- JuggApt 生成文件会被登记为 changed file；只有语言编译器把真实源码诊断直接归因到 JuggApt 产物时，重试前才会 `removeChangedFile()`，避免错误 shadow source 持续污染后续轮次。
- Kotlin 批量编译失败时，无直接诊断的同批文件可能被标记为通用失败；Java 同批文件也可能只有空错误列表。这类连带失败不会撤销 JuggApt changed-file tracking，生成文件会保留到后续轮次继续编译。
- JuggApt 降级只重试一次，且只在直接源码诊断指向本轮 JuggApt 产物时触发；普通 Kotlin/Java 编译失败不会进入该分支。
- Kotlin 编译失败时，非 Kotlin 输入会被标记为 skipped，避免 Java 阶段在缺少 Kotlin class 的情况下继续产生误导性错误。
- 删除整个 Java/Kotlin 源文件不会形成新的编译输入，也不会生成 class 移除数据。已安装 APK 或既有增量部署中的旧 class 会继续存在，直接引用、反射和类加载仍可能访问它。重命名文件时新路径可以参与编译，但旧路径对应的 class 同样不会因删除事件被移除；只有需要验证旧 class 已不存在时，才通过完整 Gradle build 刷新 APK 基线。
- `ModuleBuildPathInfo.kotlinClassPath` 会在 AGP 9 Built-in Kotlin 的 `intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`、KMP Android target 的 `classes/kotlin/android/main` 与 legacy `tmp/kotlin-classes/<variant>` 中选择更新时间最新的现存目录；时间相同时按 Built-in Kotlin、KMP Android、legacy 顺序选择，均不存在时回退 legacy 路径。classpath 同步会同时覆盖三种目录，避免本地或远程全量构建后仍缺少 Kotlin class。`android_demo_project` 的 AGP 9 profile 直接使用完整 `src/main` Demo，不再维护隔离 source set；app 保留 KSP，ARouter 统一走 Java `annotationProcessor`，避免 Built-in KAPT 与 KSP/DataBinding 的任务依赖冲突，KMP 则迁移到 `com.android.kotlin.multiplatform.library`。AABResGuard 0.1.10 依赖已移除的 `AppExtension`，因此 AGP 9 profile 不加载该插件，release APK 仍使用标准 R8 构建；其他 profile 继续保留 AABResGuard 集成覆盖。
- include build 身份只在 IDE 的多快照 project-info 合并链保存为运行时集合，不进入 `ModuleInfo` 序列化协议。主 Gradle 快照缺失、included 快照不可读或命令行只加载单份 Gradle project info 时集合为空，按既有顺序 best-effort 编译。
- `compileDexOutputs()` 会把语言阶段非 class 输出保留下来；这些通常是 generated source 或其他不直接进入 dex 的附属产物。
- minified 场景下 dex 先写到 `context.tempCompileDir/un_minify`，再由 `DexMinifyCompiler` 输出到最终 task outputDir；排查路径时不要只看最终目录。
- `DexCompiler` 输出仍保留旧 `apkPath` 锚点，同时写入 module 的所有 `targetApkPaths`；部署层用 target 集合做多 APK 分流。
- D8 版本选择以项目 AGP 实际加载的 R8 为准；Gradle instrumentation cache 必须先恢复为原始 buildscript artifact。project info 无安全路径、隔离 runtime 无法建立或外部 D8 执行失败时使用 Jugg 内置 R8。
- D8 `minApi` 使用归属 APK owner variant 的真实 `minSdk`，与 Gradle dex 行为对齐；`minSdk` 不可读时回落 21，`DexMinifyCompiler` 的 `_jugg_fix` dex 走同一解析口径。default interface 兼容通过临时 classpath 补齐，core-library rewrite 由 `desugar.json` 与真实 `minApi` 共同决定。
- 回落值 21 对语言级脱糖是更激进的一侧，不是更保守：基线未脱糖时它会让 D8 生成指向基线不存在的 `$-CC` 的调用。因此只在 `minSdk` 完全读不到时使用，不要用它替代真实 `minSdk`。
- `isEnableDesugared`（基线 APK 是否存在 `$-CC` / `$DefaultImpls`）只是诊断信号，与 minApi 一起打进 debug 日志。它表达不了 variant `minSdk`，一旦参与 minApi 决策就会让增量 DEX 与 Gradle 基线分叉（`java.time` 被改写成 `j$.time`）。
- default interface class 进入临时 classpath 是脱糖上下文，不是普通业务依赖补全；删除这一步可能让改动类生成与基线不同的 default method 调用形态。
- core library rewrite 只在 APK database 已发现 `j$.*` 时查找 `desugar.json`；不能因为工程声明了依赖就无条件为所有模块启用。
- KAPT 场景下 Kotlin 编译器 warning/error 文本会按 debug 记录，避免用户可见输出被 APT/KAPT 噪音淹没；失败判定仍由 parser 处理。
- Kotlin compiler plugin 参数优先复用 Gradle task 已解析的 `KotlinCompilerPluginData.options.arguments`，兼容 Kotlin Gradle Plugin 的 `kotlin_gradle_plugin_common` 与旧 `kotlin_gradle_plugin` getter；读取不到时保持空列表，不伪造插件参数。编译当前 module 时使用第一个非空的 current-to-parent compilation 参数集，不合并多个模块，也不按 option 名去重，保留 `allowMultipleOccurrences` 语义。
- Gradle-resolved plugin 参数只在本轮加载项目 compiler plugin 时转换为 `-P`。已加载插件报 `unsupported plugin option` 时，仅移除该 plugin id 的 Gradle-resolved 参数并共享全局单次重试预算；降级成功后按 compiler toolchain 与原参数集缓存，toolchain 或参数变化后重新尝试。用户显式写入 `kotlinFreeCompilerArgs` 的参数不参与该降级。
- compiler plugin 报 `required plugin option not present` 时只重试一次。Jugg 优先从 JAR 的 `CommandLineProcessor` service 与 class 常量识别 plugin id，再回退旧的文件名匹配；命中的插件仅在本次 invoker 后续编译中禁用，无法识别时保留原始失败，不扩大为禁用全部插件。
- `commonSourceFiles` 是 Kotlin invoker 的类型化参数，不靠调用方拼自由字符串；为空时不添加 multiplatform 参数，Compose generated expect/actual 场景则同时添加 `-Xmulti-platform` 和 `-Xcommon-sources`。
- `ModuleInfo.sourceDirs` 是模块全部有效源码根的扁平集合；Gradle common roots 和 fragment roots 会同时加入其中，供文件变更识别、模块归属、源码数据库和影响分析复用。`ModuleInfo.kotlinCommonSourceDirs` 是其中由 Gradle authoritative 数据标记的 common 子集，IDE 扁平 `sourceDirs` 不得覆盖，也不得根据 `commonMain`、`sharedMain` 等目录名反推。普通 KMP 调用只用该子集标记最终输入的 common 文件。
- complementary 查询以非空 `kotlinCommonSourceDirs` 作为 KMP module/source-set 门禁。仅把普通 Android 模块的源码目录配置为 `commonMain`（例如 local-shell 聚合源码）不会启用 KMP complementary 逻辑，即使源码文本出现 expect/actual token。
- 普通业务源码闭包已验证 Kotlin 1.9 expect-only/actual-only、Kotlin 2.1 commonMain/androidMain 与中间 sharedMain refinement、Kotlin 2.3 expect-only/actual-only。fragment graph 只覆盖选中 Android Kotlin task 暴露的结构，不等同于项目全部 target 的全局 source-set 图。
- Kotlin baseline 隔离对象必须来自 incremental cache 的 source-to-output 关系并限定为本轮 dirty expect/actual closure；不得按文件名、声明名猜测，也不得移动整个正式 output directory。
- tracker 只在 in-process 项目 Kotlin compiler 中启用。失败 invocation、retry 的中间 attempt、KSP-only phase 和跨进程 invocation 不写 cache；cache 写回失败不改变已成功的 Kotlin 产物。
- Compose common/platform 分类使用同 owner module root 下的 IDE source-set module 身份；`androidMain` 始终是 platform，其他 `Unknown` source-set module 可表示非 `commonMain` 的 common source set，不从 custom resource root 路径反推。
- generated Kotlin 编译失败时，`KotlinCompilerInvoker` 的原始行号和 diagnostic 文本会聚合回原 Compose resource 输入，不能替换成通用失败文案。
- Compose resource 编译按 generator task/API 结构识别能力，不使用 Kotlin/Compose 精确版本白名单；Kotlin 1.9、2.1、2.3 profile 均有定向回归。
- IDE JVM 也是进程内 Kotlin compiler 的宿主环境。旧 Kotlin compiler 的 shaded `JavaVersion.current()` 在新宿主 JDK 上可能解析失败；`KotlinCompilerHostCompat` 只在探测失败时预置宿主 feature，宿主 JDK >= 25 且 classpath 含 android.jar 时，`KotlinCompilerInvoker` 同时添加 `-no-jdk`。recreate compiler 不会改变宿主环境，因此相同 `INTERNAL_ERROR` 重试失败时应检查 `preset shaded JavaVersion.current to`、`add -no-jdk` 和实际项目 Kotlin 版本。
- 旧项目 Kotlin compiler 在 IDE 进程内关闭 `DescriptorLoadingContext` 时，可能误关 IDE 的 `DelegatingFileSystem` 并抛出 `UnsupportedOperationException`。只有完整异常块同时命中这三个信号时，invoker 才按规范化 compiler classpath 记录宿主冲突：warm-up 不启动无源码子进程，同一 toolchain 的后续编译改用独立 JVM；真实源码首次命中时立即以独立 JVM 重试一次。不同 compiler classpath、显式隔离模式、内置 compiler 和其他异常保持原路径，跨进程 invocation 不写 expect/actual tracker cache。one-shot 进程将 Kotlin compiler 参数写入 UTF-8 argfile，模块 classpath、插件参数和源码列表不再直接占用系统进程命令行；Java launcher 的 compiler classpath 保持原传参方式。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| generated source 落盘但下轮没编译 | `SourceCompiler.prepareSourceCompile()`：确认 `addChangedFile()` 是否登记 JuggApt 输出 |
| JuggApt 生成代码导致编译失败 | `compileLanguageStagesWithRetry()` 和 `shouldRetryWithoutJuggApt()` |
| 删除或重命名源码文件后旧 class 仍可加载 | 删除路径不会生成 class 移除数据；这是预期的增量结果，只有需要让旧 class 消失时才刷新完整 Gradle APK 基线 |
| Kotlin 编译失败后 Java 大量连带报错 | `compileLanguageStages()`：确认 Java 阶段是否被跳过，以及 Kotlin failed details |
| classpath 缺失 / Kotlin metadata 异常 | `K2JVMCompilerIsolate.checkClasspath`、`KotlinCompilerOutputParser`、`KmModuleMergerForCompilation` |
| included build 源码增量后资源 ID 错误 | `CompileContextManager` 的 included build module roots、`BaseCompileContext.findIncludedBuildTargetRFiles()` 与 Kotlin 实际 `-classpath`；确认推断目标和 host feature R 都位于 included module output 前，并对比新 DEX 内联 ID 与实际 base/split APK 资源表 |
| Kotlin `internal` 运行时找不到方法或 smart cast 被误判跨模块 | `KotlinCompilerInvoker` 的 `module-name`、friend path 与 `-d` 输出目录 |
| DataBinding mapper 未生成 | `SourceDataBindingProcessor.processDataBindingMapper()` 与 `DataBindingGenMapperCompiler` |
| dex 合并失败 | `DexCompiler`、`DexFileMerger`、`IncrementalCompilerHelper.mergeDex` |
| default method / `j$.*` 增量后运行异常 | `DexCompiler` 的 minApi、`CompileEffectAnalyzer.getDesugarInfo()`、`BaseCompileContext.findDesugaredLibraryConfiguration()` |
| AGP/Kotlin 升级后 D8 assertion 或字节码不兼容 | `JuggProjectInfo.agpR8Classpath`、`DexFileMaker` 的隔离加载与版本日志 |
| Kotlin `INTERNAL_ERROR` 栈含 shaded `JavaVersion.parse` | `KotlinCompilerHostCompat`、`K2JVMCompilerIsolate` 与 `KotlinCompilerInvoker` 的宿主 JDK 兼容日志 |
| Kotlin `INTERNAL_ERROR` 栈含 `DelegatingFileSystem.close` 与 `DescriptorLoadingContext.close` | `KotlinCompilerOutputParser` 的宿主冲突标记、`KotlinCompilerInvoker` 的独立 JVM 降级日志；不要通过 recreate compiler 重试同一宿主环境 |
| release dex 路径或类名不对 | `DexMinifyCompiler.preObfuscateForMinifyInfo()`、`obfuscateDexFile()` |
| 多 APK 下 class/dex 部署归属丢失 | `DexCompiler` 输出的 `targetApkPaths` 与 `IncrementalCompilerHelper.mergeDex()` |

---

## 7. 关联文档

- 编译核心调度：`02_compile_core.md`
- 资源编译：`02_compile_resource.md`
- DataBinding：`02_compile_databinding.md`
- Manifest 增量合并：`02_compile_manifest.md`
- 混淆映射：`02_compile_obfuscation.md`
- 测试策略：`06_testing.md`
