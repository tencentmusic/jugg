# 编译系统：自定义编译器与编译交互

> 最后核对：2026-10-15
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述两个扩展面：

- 自定义编译器如何从配置 jar 变成 `ICompiler`，以及如何插入增量编译阶段。
- 编译流程如何通过 `CompileUiHandler` 与 IDE/CLI 交互。

不展开内置编译主链，主流程见 `02_compile_core.md`；IDE 运行配置和任务调度见 `04_engineering_ide.md`。

---

## 2. 核心源码索引

| 类 | 文件 | 作用 |
|----|------|------|
| `CustomCompilerManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/CustomCompilerManager.kt` | 接收 server 配置，解析本地/远端 jar，校验 md5，懒加载 SPI 编译器 |
| `ICompilerCreator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/ICompilerCreator.kt` | SPI 入口，为当前 `ICompileContext` 和 `Disposable` 创建一个 `ICompiler` |
| `BaseCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | 根据 `CompileOrder` 在内置阶段前后执行自定义编译器 |
| `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileOrder.kt` | 定义 `before/after asset/res/source/minify/dex` 与 `atFirst/atLast` 插入区间 |
| `CompileUiHandler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileUiHandler.kt` | 编译侧交互抽象，屏蔽 IDE UI、CLI 默认实现和 androidTest 事件 sink |
| `CustomConfigManager.readConfig()` | `main/src/main/java/com/sickworm/intellij/jugg/project/CustomConfigManager.kt` | 本地与默认项目配置的读取边界 |
| `CustomCompilerInfo` | `main/src/main/java/com/sickworm/intellij/jugg/server/protocols/Protocols.kt` | server 下发的 jar 名、路径和 md5 配置模型 |
| `ARouterJuggAptProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/processors/ARouterJuggAptProcessor.kt` | 内置发现项目 ARouter 处理器、Fragment 适配、业务 class 比较和 failClosed 生成 |
| `AptSourceAnalysis`, `AptSemanticResolver` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/` | 本轮共享只读源码、语法事实及按需常量/类型解析 |
| `AnnotationProcessorRunner`, `AnnotationProcessorClasspath`, `GeneratedSourceCheckpoint` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/` | 项目处理器执行、SPI 发现与完整生成目录发布 |
| `ARouterInputSemantics`, `ARouterBaseline`, `ARouterProcessorCompatibility` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/processors/` | ARouter 相关输入、业务 class 关联及 Fragment/Jetifier 策略 |
| `Example*CustomCompiler` | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | 示例 SPI 实现，覆盖 assemble、delay、hook init 等常见插入形态 |

---

## 3. 核心数据模型

| 对象 | 来源 | 关键语义 |
|------|------|----------|
| `CustomCompilerInfo.jarFileName` | server config | 远端 jar 下载到 `customCompilerDir` 时使用的文件名 |
| `CustomCompilerInfo.path` | server config | 可以是绝对路径、相对 `projectDir` 路径、或 `http(s)` URL |
| `CustomCompilerInfo.md5` | server config | 本地已有 jar 必须匹配；远端下载后也必须匹配，不匹配会删除 |
| `customCompilerJars` | `CustomCompilerManager` 内存状态 | 当前有效 jar 列表；废弃 jar 会从 `customCompilerDir` 清理 |
| `customCompilers` | `CustomCompilerManager` 懒加载缓存 | 首次 `getCustomCompilers()` 时通过 `ServiceLoader` 创建，`resetCompilerJars()` 后清空并下次重建 |
| `ICompiler.order` | 自定义编译器实现 | 决定被哪个 `BaseCompiler` 的 before/after hook 执行 |

---

## 4. 装载与执行链路

### 4.1 配置到 jar 状态

`JuggManager` 收到 server config 后交给 `CustomCompilerManager` 维护 jar 状态。这里不需要记住单文件内的方法顺序，只需要关注以下规则：

- `null` config 不清空旧状态；非 null 列表才会重算有效 jar。
- 本地 jar 必须存在且 md5 匹配才进入 `customCompilerJars`。
- 远端 jar 先复用缓存；缓存不存在时后台下载，下载后校验 md5。
- 下载成功或缓存变化后会清空已创建的 `customCompilers`，下次 `getCustomCompilers()` 再懒加载 SPI。

### 4.2 SPI 实例到编译阶段

```text
BaseCompileContext.customCompilers
  -> CustomCompilerManager.getCustomCompilers()
     -> URLClassLoader(customCompilerJars, current classloader)
     -> ServiceLoader.load(ICompilerCreator)
     -> creator.create(context, parent)
  -> BaseCompiler.compile(task)
     -> executeBeforeCustomCompilers(beforeCompileOrderRange, task)
        -> consumeFiles() 先过滤后续输入
        -> compile(filteredTask)
     -> 内置 doCompile(filteredTask)
     -> executeAfterCustomCompilers(afterCompileOrderRange, filteredTask, result)
        -> 把内置 outputs 转回 CompileFile 后交给自定义编译器
```

实现了 `IJuggAptProcessor` 的 custom compiler 还有源码预处理入口：`SourceCompiler.prepareSourceCompile()` 在 Kotlin/Java 编译前调用一次 `JuggAptCompiler`，它从 `context.customCompilers` 取得这类扩展。此入口不依赖 `ICompiler.order`，可对混合 Java/Kotlin 模块生成 Java 后交给既有语言编译阶段。适配器可设置 `failClosed`，使生成异常和生成源码编译错误停止本轮增量任务；普通处理器保持原降级行为；内置 ARouter 使用 `failClosed`。

### 4.3 内置 ARouter 处理

`ProcessorRegistration` 默认注册 `ARouterJuggAptProcessor`，不要求 custom compiler 配置或单独 JAR。它复用项目 `annotationProcessor` / `kapt` / `ksp` 依赖，不把 ARouter compiler 打入插件。若 `context.customCompilers` 中存在已加载的外部 `IJuggAptProcessor.id == "ARouter"` 实例，内置实例跳过，由外部扩展处理；扩展需按当前接口重新编译，迁移时只移除该扩展配置，保留其他 custom compiler。

`ARouterJuggAptProcessor.prepare()` 先用标准注解包名做文本预筛，再通过共享 `AptSourceAnalysis.facts()` 确认声明或字段上实际使用 `Route`、`Autowired`、`Interceptor`，兼容普通/星号 import、全限定写法和 Kotlin alias。未命中时不创建 baseline、不解析类型、不读业务 class；外部 `IJuggAptProcessor` 输入保持原样。语法确认不解析注解参数和字段类型，实际候选才进入语义比较。

实际候选比较当前注解输入、声明身份及必要继承语义与现有业务 class，确认差异后才收集完整模块源码聚合生成；普通方法体、格式与无关成员变化不触发生成。不恢复当前无注解输入的旧注解身份，不从无注解父类/接口/字段类型修改主动追踪其他注解使用方；删除最后注解或这类间接变化后，本轮保留旧生成物，需完整 Gradle 构建刷新。实际触发生成后的类集合收缩仍按 fail-closed 处理。

`JuggAptCompiler` 每模块每轮创建一个 `AptSourceAnalysis`，供 ARouter、Kuikly 和外部处理器共享源码快照；改写文件后，后续消费者必须读到失效重建的快照。只读 Kotlin 分析在创建 PSI 前移除文件头 BOM，并将 CRLF/CR 换行归一化为 LF，避免合法 Windows 源码被误判为语法错误；原始文本和用于源码改写的解析入口保持原样，避免位置偏移失配。真实语法错误保留具体解析原因和归一化文本中的错误偏移。`IJuggAptProcessor.process()` 使用统一四参数入口，外部扩展需同步适配并重新编译。

性能诊断每模块每轮汇总为一行 debug `APT summary:`，含输入语言数量、总耗时、各处理器耗时、`inclusiveMs` 嵌套阶段计时和查询计数；父阶段包含子阶段，不可相加。未执行的解析/查询不产生计数项，普通无候选输入应只有源码读取计数。

ARouter 的 `AptSemanticResolver` 只在本轮准备期间复用类型和源码查询结果，包括未找到的空结果，避免重复遍历依赖源码路径和打开 classpath JAR。下一轮重新查询，不缓存跨轮缺失状态。

常量影响传播复用既有 ConstRef 链路，使用方跟编时按新常量值重新比较，即使其源码文本未变化。调用链和阶段职责见 `03_deploy_const_ref.md` §4。

`ARouterProcessorCompatibility.projectProcessor()` 从项目依赖的实际 JSR 269 SPI 发现处理器，生成时仅执行选中的 ARouter 集合；缺失、歧义或不完整的 SPI 会使生成失败。项目参数接受 `AROUTER_MODULE_NAME` 或 `moduleName`，其余选项原样透传，kapt 同键覆盖 Java 配置；未单独适配早期 `@Param` 等规则差异。

Project Info 的 AAR 类路径和 processor JAR 可能分别处于 Jetifier 之后、之前，不能以 `android.enableJetifier` 判断处理器已转换。`ARouterProcessorCompatibility.selectProcessor()` 对照处理器实际 Fragment 要求、APK 基线信号和本轮有效编译类路径：仅单 APK 明确只含 Support 时选择 Support，其余情况默认 AndroidX。`containsApkClass()` 的空结果也可能表示数据库不可用，不能断言 APK 没有该类；选择后仍须验证类路径含目标 Fragment。

Fragment 要求与所选目标不同才转换 processor 的独立副本，并同时替换 KAPT、javac 的 processor path 和辅助 classpath；不覆盖项目文件，不转换其他 runtime/annotation 依赖。转换后校验 Fragment 引用及 SPI，无法可靠识别或转换时生成失败。

`ARouterProcessorCompatibility.projectKspProcessor()` 从 `SymbolProcessorProvider` SPI 发现项目 ARouter KSP 实现，按当前模块有效 KSP 参数调用；不按 artifact 名称判定 processor。同一模块同时声明 ARouter APT/KAPT 与 KSP，或发现多个 KSP processor JAR 时明确失败，避免执行顺序决定索引。KSP 参数接受 `AROUTER_MODULE_NAME` 或 `MODULE_NAME`，不从 APT/KAPT 参数补造配置。

`KspProcessorRunner.generate()` 在独立临时目录执行完整模块聚合：KSP1 复用 `K2JVMCompilerIsolate` 加载项目 compiler/plugin，Kotlin 2.x 的 KSP1 仍使用 classic frontend（language/API 上限 1.9）；KSP2 以独立 classloader 加载当前 task 的 Analysis API runtime 与项目 provider，通过 `KSPJvmConfig` / `KotlinSymbolProcessing.execute()` 执行，不要求新增 JVM 进程。两者透传处理器参数和 common sources，只读取本轮生成的 Java/Kotlin 源码，拒绝将旧 Gradle KSP 输出视为成功结果。KSP2 API/engine 缺失或不兼容时明确失败，不改用插件内嵌版本。

KSP 产物复用同一 checkpoint 和 failClosed 边界；生成类身份按 package 与文件类名比较，忽略 Gradle 与本轮输出根的 `java/`、`kotlin/` 布局差异。生成 Kotlin 返回 `CompileFile.Type.Kotlin`，沿用现有后续编译处理。该入口只扩展 ARouter；Moshi 的既有 KSP 范围不随本次修复扩大。

`AnnotationProcessorRunner.generate()` 对混合 Java/Kotlin 模块使用原生 KAPT，对纯 Java 模块使用 javac，均只执行选定的项目处理器。完整聚合输入进入同一次生成，产物先留在临时目录，成功后由 `GeneratedSourceCheckpoint.publish()` 发布，再交既有 Kotlin/Java 编译链；普通语言编译保持 APT/KAPT 关闭，DataBinding 的既有输出语义不变。

生成源码目录只在完整生成成功后发布；生成类收缩时保留旧目录并要求完整 Gradle 构建，不能将部分索引交给后续源码编译。完整构建清空增量目录后，下一轮重新使用 Gradle 生成物基线。

发布成功后，ARouter 将本轮 Java/Kotlin 源码尽力同步到模块的 Gradle generated source 目录，供 IDE 查看与跳转。APT/KAPT 优先沿用当前生成文件所在的模块生成目录，否则按项目依赖选择 `ap_generated_sources/<variant>/out` 或 `source/kapt/<variant>`；KSP 优先沿用模块源码根中的实际 KSP 输出位置，保留 `java/`、`kotlin/` 布局，否则使用 `ksp/<variant>`。同步只覆盖本轮文件，不清空共享目录；路径解析或复制失败时记录包含模块、目录及异常的 warn，继续使用 Jugg 发布目录参与编译。生成失败或类集合收缩时不执行同步；同步发生在语言编译之前，不代表编译或部署成功。

相关检测或完整聚合输入准备失败时，仅在 debug 文件日志中记录受影响文件、原因及异常栈，说明“本模块本轮跳过生成，索引可能滞后”，不向正常编译输出 warn；其他处理器和普通编译继续。真实 APT 执行失败、生成类收缩和生成文件直接语言诊断保留 failClosed。正常 Java 编译更新 javaClassPath，Kotlin 更新 kotlinClassPath，这些业务 class 是下一轮比较依据；生成目录、业务 class 和设备部署不是同时提交的事务。检测跳过而普通编译成功时，业务 class 可已新、旧索引仍滞后，不保证再次提交相同输入能够补生成；完整 Gradle 构建可恢复一致状态。

APT 成功但语言编译失败时保留已有 changed/compiled/staging/deployed 跟踪，业务 class 仍有差异则下一次请求允许再次聚合 APT。pending 不替代新输入比较，重新生成后消费最新产物；已消费的索引不会反复加入普通轮次。D8 或部署失败后若业务 class 输入已相同，跳过 APT 并继续既有下游重试。processor JAR、依赖和选项变化交给工程模型与完整 Gradle 构建，不维护环境摘要或独立原地 JAR 替换检测。

语义求值覆盖经过真实官方 processor 和定制注解 fixture 验证的范围，未提供完整 Java/Kotlin 编译器符号分析；无法可靠判定时按检测失败边界局部跳过。实现取舍与验证记录见 `docs/task/2026-10/arouter_class_input_comparison_plan.md`。

---

## 5. 编译交互协议

`CompileUiHandler` 是编译流程唯一应该依赖的交互面。IDE、CLI、测试默认实现都通过它提供行为，编译核心不直接操作具体 UI。

| 对象 | 文件 | 用途 |
|------|------|------|
| `isForceGradleCompile` | `CompileUiHandler` | 用户强制 Gradle 编译开关 |
| `isSkipDeploy` / `isAlwaysRestartApp` | `CompileUiHandler` | 编译后的部署策略输入 |
| `createCompileStatusHolder()` | `CompileUiHandler` | 创建取消与当前文件状态对象 |
| `createOutputParser()` | `CompileUiHandler` | Gradle 编译输出解析入口 |
| `confirmBuildChanges()` / `confirmDependencyChanges()` / `confirmTooManyChanges()` | `CompileUiHandler` | 构建文件/依赖变化/源码过多时的用户确认 |
| `notifyByBalloon()` / `updateIndicatorText()` | `CompileUiHandler` | 用户可见进度提示 |
| `testEventSinkFactory` | `CompileUiHandler` | androidTest 运行时把 instrumentation 事件接到 Test Results |
| `RunResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/RunResult.kt` | 编译/部署终态描述 |
| `BuildChangesConfirmResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/BuildChangesConfirmResult.kt` | 构建变更确认结果 |
| `TooManyChangesConfirmResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/TooManyChangesConfirmResult.kt` | 源码过多确认结果 |

---

## 6. 隐形约束 / 设计思路

- `updateCustomCompilers(null)` 不会清空旧配置；只有收到非 null 列表才会重算 jar 并清理废弃缓存。
- 远端 jar 下载是异步的。首次更新配置后，如果 jar 尚未存在，本轮 `getCustomCompilers()` 可能仍为空；下载成功后 `resetCompilerJars()` 会让下一轮重新加载。
- 自定义编译器异常会被 `BaseCompiler` 捕获，打印用户可见 warn，并把当前 task 收口为失败；异常不会穿透到 IDE 进程。
- before hook 可以通过 `consumeFiles()` 改写后续内置编译输入；after hook 只能看到内置产物转成的 `CompileFile`。
- `order` 必须落在具体编译器暴露的区间内才会执行。例如想处理 Java/Kotlin 编译后的产物，应使用 `CompileOrder.afterSource`，并确认目标阶段由 `JavaCompiler` / `KotlinCompiler` / `SourceCompiler` 暴露。
- `URLClassLoader` 的 parent 是 Jugg 当前 classloader；自定义 jar 可以复用 Jugg API，但要避免打包冲突版本导致类加载行为不可预期。
- `CompileUiHandler.DEFAULT` 是无 UI 的安全默认值，适合 CLI/测试，但不会弹确认或展示 Run 窗口。

---

## 7. 新增自定义编译器

源码目录、SPI 注册、打包、JAR 校验与项目配置步骤见 [custom_compilers/README.md](../../custom_compilers/README.md)；普通 hook 与源码预处理的执行契约见本页 §4。

---

## 8. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 配置了 jar 但没生效 | `CustomCompilerManager.updateCustomCompiler()`，检查路径类型与 md5 |
| 远端 jar 下载成功但本轮没执行 | `downloadCompilers()` / `resetCompilerJars()`，确认是否需要下一轮 compile 重新加载 |
| `ServiceLoader` 没找到实现 | jar 内 `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator` |
| 编译器执行阶段不对 | `ICompiler.order` 与 `CompileOrder` 区间，目标内置编译器的 `beforeCompileOrderRange` / `afterCompileOrderRange` |
| 自定义编译器失败导致整轮失败 | `BaseCompiler.executeBeforeCustomCompilers()` / `executeAfterCustomCompilers()` warn 日志 |
| UI 确认或取消行为与预期不一致 | 当前 `CompileUiHandler` 实现，而不是 `CompileUiHandler.DEFAULT` |

---

## 9. 关联文档

- 编译核心：`02_compile_core.md`
- IDE 执行链：`04_engineering_ide.md`
- 工程/配置：`04_engineering_project.md`
- 历史适配器、迁移及验证：`docs/task/2026-10/arouter_generic_custom_compiler_plan.md`
