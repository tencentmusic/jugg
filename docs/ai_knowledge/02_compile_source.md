# 编译系统：源码编译链（Java/Kotlin/Dex）

> 最后核对：2026-05-23
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
| `K2JVMCompilerIsolate` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/K2JVMCompilerIsolate.kt` | Kotlin 编译器隔离加载与 classpath 检查 |
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

---

## 5. 隐形约束 / 设计思路 / 已知边界

- `collectJuggAptGeneratedFiles()` 是 fail-open：处理器异常只 warn，然后继续主编译。不要把 JuggApt warn 直接等同于整轮编译失败。
- JuggApt 生成文件会被登记为 changed file；只有语言编译器把真实源码诊断直接归因到 JuggApt 产物时，重试前才会 `removeChangedFile()`，避免错误 shadow source 持续污染后续轮次。
- Kotlin 批量编译失败时，无直接诊断的同批文件可能被标记为通用失败；Java 同批文件也可能只有空错误列表。这类连带失败不会撤销 JuggApt changed-file tracking，生成文件会保留到后续轮次继续编译。
- JuggApt 降级只重试一次，且只在直接源码诊断指向本轮 JuggApt 产物时触发；普通 Kotlin/Java 编译失败不会进入该分支。
- Kotlin 编译失败时，非 Kotlin 输入会被标记为 skipped，避免 Java 阶段在缺少 Kotlin class 的情况下继续产生误导性错误。
- `compileDexOutputs()` 会把语言阶段非 class 输出保留下来；这些通常是 generated source 或其他不直接进入 dex 的附属产物。
- minified 场景下 dex 先写到 `context.tempCompileDir/un_minify`，再由 `DexMinifyCompiler` 输出到最终 task outputDir；排查路径时不要只看最终目录。
- `DexCompiler` 输出仍保留旧 `apkPath` 锚点，同时写入 module 的所有 `targetApkPaths`；部署层用 target 集合做多 APK 分流。
- KAPT 场景下 Kotlin 编译器 warning/error 文本会按 debug 记录，避免用户可见输出被 APT/KAPT 噪音淹没；失败判定仍由 parser 处理。

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
