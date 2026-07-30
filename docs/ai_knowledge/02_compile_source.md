# 编译系统：源码编译链（Java/Kotlin/Dex）

> 最后核对：2026-07-26
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页覆盖源码到 dex 的增量编译主链路：JuggApt/KSP/KAPT 生成源码、DataBinding mapper、Kotlin/Java 编译、dex 生成、minified 变体重映射。它重点记录阶段顺序、失败降级和多 APK target 归属。

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
| `KotlinComplementaryFilesCache` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinComplementaryFilesCache.kt` | 按需定位并读取项目 Kotlin Gradle incremental cache 的 complementary files |
| `ComposeResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceCompiler.kt` | 在常规 source 阶段前，以一次 Kotlin invocation 编译 Compose generated expect/actual sources |
| `K2JVMCompilerIsolate` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/K2JVMCompilerIsolate.kt` | Kotlin 编译器隔离加载、classpath 检查、项目版本 ExpectActualTracker 注入与 incremental cache API 适配 |
| `JavaCompiler` / `JavaCompilerInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompiler.kt`, `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompilerInvoker.kt` | Java 编译与 javac 参数组装 |
| `DexCompiler` / `DexFileMaker` / `DexFileMerger` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/` | class 到 dex、file-per-class 输出与 dex 合并 |
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
| 普通 KMP complementary closure | Kotlin Gradle incremental cache | `KotlinCompiler` | 仅 Android owner 存在 Gradle authoritative `kotlinCommonSourceDirs` 且源码出现 expect/actual token 时查询；requested 与 complementary files 按 canonical path 去重后在 Android owner invocation 中联合编译；成功后用 tracker 原地刷新双向 edge |

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

Compose resource generated source 是这条常规 source 链之前的独立前置步骤：`ComposeResourceCompiler` 将 Res、各 source set accessor、expect collector 和 Android actual collector 放进同一次 `KotlinCompilerInvoker` 调用，并显式传入 common source 文件列表。编译出的 class 随后才进入 `SourceCompiler` 的 class/dex 路径；不会分别编译 expect 与 actual。Gradle project info 仍可把 build directory 下的 generated source 保留在 `sourceDirs` 中，供 Kotlin compilation metadata 使用；`FileChangesHandler` 会在文件变更边界统一排除这些路径，避免它们再作为用户源码进入常规 Kotlin 阶段。JuggApt 等本轮由编译器直接登记的 generated source 不经过该文件事件过滤。

IDE 将 common source set 暴露为同根虚拟 module 时，普通 Kotlin 与 Compose resource 编译都先解析带 Gradle 配置的 Android owner；classpath、output、Compose metadata 和 APK ownership 不取虚拟 module 的扁平快照。

普通 KMP 业务源码走常规 Kotlin 阶段。`KotlinCompiler` 发现 expect/actual token 后切换到同根 Android owner，`KotlinCompilerInvoker` 用项目 Kotlin compiler 打开 Gradle cache，`getComplementaryFilesRecursive()` 返回本轮补充输入。in-process 项目 compiler invocation 会设置 `incrementalCompilation=true` 并注册项目版本 `ExpectActualTrackerImpl`；最终成功后用 requested+complementary closure 调用 `updateComplementaryFiles()`。cache 缺失、损坏、候选不唯一、无 edge 或写回失败时只记 debug；普通 Kotlin 文件不触发查询或 tracker。

---

## 5. 隐形约束 / 设计思路 / 已知边界

- `collectJuggAptGeneratedFiles()` 是 fail-open：处理器异常只 warn，然后继续主编译。不要把 JuggApt warn 直接等同于整轮编译失败。
- JuggApt 生成文件会被登记为 changed file；只有语言编译器把真实源码诊断直接归因到 JuggApt 产物时，重试前才会 `removeChangedFile()`，避免错误 shadow source 持续污染后续轮次。
- Kotlin 批量编译失败时，无直接诊断的同批文件可能被标记为通用失败；Java 同批文件也可能只有空错误列表。这类连带失败不会撤销 JuggApt changed-file tracking，生成文件会保留到后续轮次继续编译。
- JuggApt 降级只重试一次，且只在直接源码诊断指向本轮 JuggApt 产物时触发；普通 Kotlin/Java 编译失败不会进入该分支。
- Kotlin 编译失败时，非 Kotlin 输入会被标记为 skipped，避免 Java 阶段在缺少 Kotlin class 的情况下继续产生误导性错误。
- `ModuleBuildPathInfo.kotlinClassPath` 会在 AGP 9 Built-in Kotlin 的 `intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes` 与 legacy `tmp/kotlin-classes/<variant>` 中选择更新时间最新的现存目录；时间相同时选择 Built-in Kotlin，均不存在时回退 legacy 路径。classpath 同步会同时覆盖两种目录，避免远程全量构建后本地仍缺少 Kotlin class。`android_demo_project` 的 AGP 9 profile 直接使用完整 `src/main` Demo，不再维护隔离 source set；app 保留 KSP，ARouter 统一走 Java `annotationProcessor`，避免 Built-in KAPT 与 KSP/DataBinding 的任务依赖冲突，KMP 则迁移到 `com.android.kotlin.multiplatform.library`。AABResGuard 0.1.10 依赖已移除的 `AppExtension`，因此 AGP 9 profile 不加载该插件，release APK 仍使用标准 R8 构建；其他 profile 继续保留 AABResGuard 集成覆盖。
- `compileDexOutputs()` 会把语言阶段非 class 输出保留下来；这些通常是 generated source 或其他不直接进入 dex 的附属产物。
- minified 场景下 dex 先写到 `context.tempCompileDir/un_minify`，再由 `DexMinifyCompiler` 输出到最终 task outputDir；排查路径时不要只看最终目录。
- `DexCompiler` 输出仍保留旧 `apkPath` 锚点，同时写入 module 的所有 `targetApkPaths`；部署层用 target 集合做多 APK 分流。
- KAPT 场景下 Kotlin 编译器 warning/error 文本会按 debug 记录，避免用户可见输出被 APT/KAPT 噪音淹没；失败判定仍由 parser 处理。
- `commonSourceFiles` 是 Kotlin invoker 的类型化参数，不靠调用方拼自由字符串；为空时不添加 multiplatform 参数，Compose generated expect/actual 场景则同时添加 `-Xmulti-platform` 和 `-Xcommon-sources`。
- `ModuleInfo.sourceDirs` 是模块全部有效源码根的扁平集合；Gradle common roots 会同时加入其中，供文件变更识别、模块归属、源码数据库和影响分析复用。`ModuleInfo.kotlinCommonSourceDirs` 是其中由 Gradle authoritative 数据标记的 common 子集，IDE 扁平 `sourceDirs` 不得覆盖，也不得根据 `commonMain`、`sharedMain` 等目录名反推。普通 KMP 调用只用该子集标记最终输入的 common 文件。
- complementary 查询以非空 `kotlinCommonSourceDirs` 作为 KMP module/source-set 门禁。仅把普通 Android 模块的源码目录配置为 `commonMain`（例如 local-shell 聚合源码）不会启用 KMP complementary 逻辑，即使源码文本出现 expect/actual token。
- 当前普通业务源码闭包已验证 Kotlin 2.1 commonMain/androidMain 和 Kotlin 2.3 expect-only/actual-only。中间 source set 仍缺少 authoritative fragment graph；Kotlin 1.9 仍需隔离 baseline 中 dirty closure 的旧 actual output，详见 `docs/task/2026-07-26-kmp-business-expect-actual-follow-up-todo.md`。
- tracker 只在 in-process 项目 Kotlin compiler 中启用。失败 invocation、retry 的中间 attempt、KSP-only phase 和跨进程 invocation 不写 cache；cache 写回失败不改变已成功的 Kotlin 产物。
- Compose common/platform 分类使用同 owner module root 下的 IDE source-set module 身份；`androidMain` 始终是 platform，其他 `Unknown` source-set module 可表示非 `commonMain` 的 common source set，不从 custom resource root 路径反推。
- generated Kotlin 编译失败时，`KotlinCompilerInvoker` 的原始行号和 diagnostic 文本会聚合回原 Compose resource 输入，不能替换成通用失败文案。
- Compose resource 编译按 generator task/API 结构识别能力，不使用 Kotlin/Compose 精确版本白名单；Kotlin 1.9、2.1、2.3 profile 均有定向回归。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| generated source 落盘但下轮没编译 | `SourceCompiler.prepareSourceCompile()`：确认 `addChangedFile()` 是否登记 JuggApt 输出 |
| JuggApt 生成代码导致编译失败 | `compileLanguageStagesWithRetry()` 和 `shouldRetryWithoutJuggApt()` |
| Kotlin 编译失败后 Java 大量连带报错 | `compileLanguageStages()`：确认 Java 阶段是否被跳过，以及 Kotlin failed details |
| classpath 缺失 / Kotlin metadata 异常 | `K2JVMCompilerIsolate.checkClasspath`、`KotlinCompilerOutputParser`、`KmModuleMergerForCompilation` |
| DataBinding mapper 未生成 | `SourceDataBindingProcessor.processDataBindingMapper()` 与 `DataBindingGenMapperCompiler` |
| dex 合并失败 | `DexCompiler`、`DexFileMerger`、`IncrementalCompilerHelper.mergeDex` |
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
