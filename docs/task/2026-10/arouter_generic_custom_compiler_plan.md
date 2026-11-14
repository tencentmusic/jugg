# ARouter 内置增量处理器改造计划（已批准实施）

## 已确认目标

Jugg 默认发现 ARouter 增量处理，不要求配置或发布 custom compiler。内置处理器复用 `IJuggAptProcessor` / `JuggAptCompiler` 的源码编译前入口，调用工程自身的 ARouter annotation processor；不把某个版本的 ARouter compiler 打入插件。接受项目原有的 `AROUTER_MODULE_NAME` 或 `moduleName` 选项，不检查处理器版本，不按版本跳过或增加专门提示。低版本若与现有通用路径不兼容，就按正常处理器失败记录 warn；不为低版本单独适配，只在实现处留英文注释说明已知边界。

APK 基线中只出现 AndroidX 或旧 Support Fragment 时选该类型；两者都有、都没有或多 APK 下无法归属时，**默认 AndroidX**，打印包含判断依据的 debug 日志。选择后仍须检查本轮编译类路径；缺少所选类型时明确失败，不能靠只转换 compiler 补出缺失的 API。生成索引继续进入现有 `ArtifactRestartPolicy.kt` 的重启判断，本次不修改部署策略；当前策略对本轮部署中的注册索引保守重启，包括缓存复用后重新编译的相同索引。

## 事实与通用性审查

- 当前适配器 `custom_compilers/src/custom/java/com/sickworm/intellij/jugg/compiler/arouter/ARouterCompiler.kt` 对真实生成的启动/结束、成功文件绝对路径和失败 warn 日志已由适配器自身负责，生成/缓存/KAPT/javac 的基本接线可复用；但它仍是外部 `ICompiler` + `IJuggAptProcessor` 双身份，需要 custom compiler SPI、单独 JAR 和项目配置。
- 官方 ARouter 1.0.1～1.0.4 只有 `RouteProcessor`、`InterceptorProcessor`，使用 `moduleName`、`@Param`，尚不查找 Support Fragment；1.0.5～1.3.1 已有三个处理器和 Support Fragment，仍使用 `moduleName`；1.4.0～1.5.2 改用 `AROUTER_MODULE_NAME`。1.5.2 是目前官方仓库最高的 tag；YB 本地 compiler JAR 无版本元数据，虽具备后期三处理器、`AROUTER_MODULE_NAME` 契约，不能据此认定它与官方 1.5.2 同版。这些结论来自官方仓库各 tag 的 processor、`Consts` 和 annotation 源码，以及 YB JAR 的 SPI/常量池。当前“SPI 必须同时包含固定三个类”和“必须有 `AROUTER_MODULE_NAME`”会错过早期版本，声明签名也未跟踪 `@Param`。
- 现有源码触发只识别官方 `com.alibaba.android.arouter.facade.annotation` 下的 `Route` / `Autowired` / `Interceptor`；它用文本提取注解和类层级，并非完整 Java/Kotlin 语法分析。代表性 Java/Kotlin 样例需检查 alias、常量、继承等当前目标行为；早期 `@Param` 未纳入触发分析，作为已知边界记录，不能仅凭处理器 JAR 检测宣称完全通用。
- 处理器调用目前把模块 `annotationProcessor` 和 `kapt` 路径合并，显式指定三个固定 FQCN，以完整 Java 源码加 Kotlin KAPT stubs 执行 `javac -proc:only`。应从实际识别的 ARouter JAR 的 SPI 取本轮处理器集合，并只传该集合；处理器选项保持 Gradle 项目值，适配官方两种 module name 键。KAPT 与 javac 必须使用同一份选定处理器 JAR。
- 标准 1.5.2 原始 `RouteProcessor` 查找 `android.support.v4.app.Fragment`；在 AndroidX Demo 类路径上直接执行会失败且生成 0 文件。本地 Jetifier `1.0.0-beta10` 转换后查找 AndroidX，相同类路径下生成 3 个 Java 文件。YB 处理器原包已经查找 AndroidX，用户已验证其 Jugg 流程成功。Project Info 可同时出现原始 JAR 和 Jetified AAR，不能以 `android.enableJetifier` 属性推断本轮处理器已转换。
- `ICompileContext.containsApkClass()` 现有 APK DB 查询可提供两种 Fragment 的基线信号，无需增加 DB 列；查询不是按目标 APK 分区，多 APK 时存在歧义。`SourceCompiler.shouldRetryWithoutJuggApt()` 当前只检查外部 custom processor 的 `failClosed` 所有权；内置 ARouter 若不接入此判断，生成源码编译失败可能被不带 ARouter 的重试吞掉。
- YB Demo 仍配置旧 custom compiler JAR。内置路径和旧配置并存会重复执行，需要迁移保护及示例配置清理。

读取实际 SPI 只解决处理器发现与调用入口。首版仍须检查实际触发、Fragment/Jetifier 与有效类路径、生成源码缓存及失败语义。SPI 相同的魔改包仍可能改变上述行为，因此不能仅以 SPI 判定兼容。YB 使用本地 `files(...)` JAR，Manifest 无版本号；项目实际配置 `AROUTER_MODULE_NAME`，处理器也读取此键。本次无需借此推断版本。`moduleName` 只是另一种项目参数，现有 javac 调用已把项目参数原样传入处理器，适配器的前置检查接受它即可。

## 拟定行为

1. 将适配器迁入主仓库，成为 `failClosed` 的内置 `IJuggAptProcessor`，在 `ProcessorRegistration` 注册。无需 `ICompilerCreator`、外部 JAR 或发布名；若项目仍配置旧的 `id = ARouter` custom processor，内置处理器跳过并打 debug，待配置迁移后由内置路径接管。
2. 无 ARouter 声明、无历史检查点且 Gradle 基线无 ARouter 生成文件时安静跳过；其余情况先扫描 `annotationProcessor` / `kapt` 依赖中 JSR 269 SPI 的实际注册项，选出 ARouter processor JAR 和其注册的处理器，再核对环境和检查点签名。缓存命中跳过生成，但仍返回缓存生成源码。不要求三个固定类同时存在，不依赖 JAR 文件名或版本。若同时发现多个候选、相关 SPI 缺损或处理器调用失败，给出模块、候选路径和原因；不能静默跳过。低版本 `@Param` 等未覆盖的变更触发属于已知边界，不增加专门兼容逻辑。
3. 从项目现有 `javaAnnotationProcessorOptions` 与 `kaptArguments` 中读取 module name，优先使用 `AROUTER_MODULE_NAME`，否则使用 `moduleName`；两者都没有时沿用当前的缺少必要参数失败。全部项目选项原样传给 javac，由实际处理器消费，不补写另一种键，也不按键名推断版本。实现处增加英文注释，说明早期 ARouter 的其他行为差异未专门适配。
4. 从实际处理器二进制中的类型引用与字符串判断它需要旧 Support Fragment、AndroidX Fragment，或不依赖两者；不能按包名、文件名或 Gradle 属性推断。不依赖两者时直接走正常处理器调用。其余情况查询 APK DB：单一类型按基线选择；两种均有、均无或多 APK 无法判明归属时用 AndroidX，并打 debug 记录模块、命中情况及多 APK 歧义。再检查 javac 有效类路径是否含目标 Fragment。原处理器要求的 Fragment 与目标一致则直接使用；需要旧 Support → AndroidX 时，只转换当前处理器 JAR，并缓存转换结果，不覆盖项目原文件。已转换包直接运行。若明确为旧 Support APK 且处理器只查找 AndroidX，先验证 Jetifier 反向转换能否形成等价处理器，再决定是否启用；无法安全转换时以 warn 说明，不误报兼容成功。转换后的 JAR 须同时接入 KAPT stubs 和 javac。
5. 检查点签名包含现有源码声明与相关常量/层级、Gradle 生成源码基线、选中处理器及其实际二进制、Fragment 目标类型和转换器版本。签名变动重跑处理器；无变动复用生成源码；生成类删除要求完整 Gradle 构建。真正开始生成前 info、成功文件绝对路径 info、失败原异常 warn、结束秒级耗时 info 均留在 ARouter 处理器内。公共层只保留中性的失败传播与 `failClosed` 停止语义，不输出 ARouter 细节。
6. `SourceCompiler` 对内置 ARouter 生成文件的直接编译错误也禁止去除 JuggApt 后重试，确保失败可见；其他内置处理器原有降级路径保持不变。

## 实施范围

| 文件或模块 | 预期改动 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/processors/ARouterJuggAptProcessor.kt`（新增） | 从现有适配器迁入触发判定、生成、缓存和内部日志；接受两种 module name 参数、按实际 SPI 调用处理器并处理 APK 类型与必要的本地 Jetifier 转换；用英文注释记录旧版未专门适配的已知边界。 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/ProcessorRegistration.kt` | 注册内置处理器。 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/JuggAptCompiler.kt` | 增加 `isFailClosedGeneratedFile(file)` 查询，使用执行生成的同一组内置/外部处理器实例判断归属；保留公共错误输出边界，修正“内置全部 fail-open”的过时注释。 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | 将内置 `failClosed` 处理器纳入“不能去掉生成源码重试”的判断。 |
| `main/build.gradle` | 引入 `com.android.tools.build.jetifier:jetifier-processor:1.0.0-beta10` 运行依赖及标准 ARouter 测试需要的真实依赖，验证插件包中可加载且不与 IDE 运行时冲突。不引入 ARouter 生产依赖。 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/apt/ARouterJuggAptProcessorTest.kt`（新增） | 用真实标准产物验证官方 1.5.2 的生成文件、AndroidX 转换及缓存失效，YB 用本地产物探针和 Flow 补充；不增加版本判定或旧版跳过测试。`moduleName` 的前置接受行为用旧版产物做替代探针，记录其自然结果，不把旧版完整生成列为通过条件。先写有价值的失败断言，不加测试专用生产接口。 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/apt/JuggAptCompilerTest.kt` | 扩充内置 `failClosed` 与旧外部配置并存的回归，断言失败与生成结果，不锁定日志文案。 |
| `docs/ai_knowledge/02_compile_custom_ui.md`、`docs/ai_knowledge/98_code_map.md`、受影响的 `docs/wiki/zh/capabilities/compile/custom-compiler.md` 及英文镜像 `docs/wiki/capabilities/compile/custom-compiler.md` | 将 ARouter 从“外部项目适配器”改为内置能力，登记新增处理器路径，说明识别契约、Jetifier 选择、失败排查与旧配置迁移；中英文 Wiki 同步。 |
| `/Users/wormchen/IdeaProjects/demo/aigc-bot-client/app/build.gradle` | 仅在端到端迁移验证阶段移除旧 `customCompilers` 配置；外部 Demo 的其他配置不改。 |

新增 Jetifier 依赖时同步仓库已有的 `THIRD_PARTY_NOTICES.md`、`third_party/sbom/jugg-third-party.spdx.json` 及实际新增依赖需要的许可证记录；沿用现有插件打包检查，不新增打包框架。

现有独立仓库和被忽略的 `custom_compilers/src/custom` 留作历史实现来源，本次不继续发布新的 ARouter custom compiler，也不修改其远端仓库。若迁移后确定需要归档，再单独处理。`GradleProjectInfoReader`、APK DB schema、`ICompileContext`、公共编译错误打印、ARouter runtime 与路由索引部署策略不在本次范围。

## 具体修改顺序

### 1. 迁入现有处理器，保留源码编译入口

新增 `ARouterJuggAptProcessor`，只实现 `IJuggAptProcessor`，保留 `id = "ARouter"`、`failClosed = true`。删除迁入副本中 `ICompiler` 的空 `compile()`、`dispose()`、`supportedTypes` 和 SPI creator 依赖；外部历史实现不改。在 `ProcessorRegistration.get()` 增加无参实例，缓存路径仍由 `process()` 的 `context.incrementalDataDir` 派生。

`ownsGeneratedFile()` 只认该实例实际使用的 ARouter checkpoint source 目录；目录在 `process()` 中记录，包含缓存复用路径，不能仅凭生成文件名认领其他处理器的文件。`SourceCompiler` 不另建处理器实例，避免生成时和错误判断时的归属状态不同。

内置 `process()` 的第一步检查 `context.customCompilers.filterIsInstance<IJuggAptProcessor>()` 中是否存在 `id == "ARouter"`：存在则只跳过内置实例，保留外部执行及其原有失败语义。不能按整个 custom compiler JAR 名或其他扩展的存在跳过。无 ARouter 项目的正常源码编译与 Kuikly 原有处理不变。

### 2. 先解析实际处理器与环境，再判断缓存

处理顺序固定为：

```text
旧外部配置迁移保护
  -> 扫描模块源码声明、历史检查点与 Gradle 生成源码基线
  -> 三者均无 ARouter 证据：返回空列表
  -> 解析 ARouter SPI、项目参数及处理器二进制
  -> 选择 Fragment 目标，检查有效类路径，必要时转换 JAR
  -> 计算完整签名，检查缓存完整性
  -> 命中：返回已有生成源码
  -> 未命中：KAPT stubs -> javac -proc:only -> 检查删除 -> 保存检查点
```

首版复用当前模块源码扫描和声明/常量/继承事实提取。`allCompileFiles` 只代表当前轮输入，不能仅因其中没有注解就提前跳过：相关常量、父类、处理器依赖或 Gradle 基线仍可能变化。暂不增加依赖图或文本解析框架，不把现有正则分析宣称为完整语言语义。

SPI 按行去掉 `#` 后的注释、去空白、去重；只选 `com.alibaba.android.arouter.compiler.processor.` 下的注册项，检查对应 class entry 存在。无 SPI 的普通依赖不是错误；需要生成但没有可识别 ARouter 候选才失败。文件路径按 canonical path 去重，同一 JAR 同时出现在 kapt 和 annotationProcessor 不算两个候选；两个不同候选 JAR 则明确失败，不通过 classpath 顺序猜测。混合 SPI 中其他框架的处理器不加入 `-processor`。

保留现有 `javaAnnotationProcessorOptions + kaptArguments` 合并顺序，同键仍由 kapt 值覆盖。module name 检查接受 `AROUTER_MODULE_NAME` 或 `moduleName`，两键都存在时日志展示前者，向 javac 传递原始合并后的全部键值，不增删别名。

### 3. 在处理器调用边界完成 Fragment 适配

使用仓库现有 ASM 读取处理器 JAR 中的类型引用和精确类名字符串，区分 Support、AndroidX、无 Fragment 依赖。只匹配目标类型的点分/斜杠形式，不对 JAR 二进制做字符串替换。若同时存在两侧引用、或引用形式无法确定实际要求，不能仅凭出现次数认定转换方向；记录事实并明确失败。首版不分析任意 fork 的动态拼接类名。

对于需要 Fragment 的处理器，通过 `containsApkClass()` 一次查询两个 descriptor；单 APK 且只命中一侧时按该侧选择，双侧/无结果或无法获得 APK 专属证据时选 AndroidX。该接口把数据库不可用和查询失败收口为空列表，日志只能记录“无查询结果”，不能声称 APK 确实没有 Fragment。当前查询未按 APK 分区，首版多 APK 保守按歧义处理，不改 DB 和 `ICompileContext`。

类路径沿用 `context.getModuleDependencies()`、本轮 Kotlin class 输出和处理器运行依赖的既有组合；检查目标 `androidx/fragment/app/Fragment.class` 或 `android/support/v4/app/Fragment.class` 是否在这份路径的目录/JAR 中可见，不查 APK 代替 javac 类路径，不用 IDE 插件类加载器加载 App 类型。检查失败时给出模块、目标类型和路径依据；实际 javac 仍是最终解析与生成结果的验证。

需要转换时使用本地已存在源码可核对的 Jetifier `1.0.0-beta10` API：`ConfigParser.loadDefaultConfig()`、`Processor.createProcessor4()`、`FileMapping` 和 `transform2()`。正向转换须保留字符串重写能力；不启用“包含任意 AndroidX 引用就跳过整个库”的选项，避免混合引用 JAR 被漏转。输出写入 Jugg 自有缓存的临时文件，验证目标引用和 SPI 后再发布，不覆盖项目原 JAR。

缓存 key 至少包含原 JAR 的 SHA-256、转换方向、Jetifier 版本和本次转换参数。使用转换 JAR **替换** 原 JAR 在 KAPT processor path、javac processor path 和辅助 classpath 中的所有实际位置，不能只是追加到列表末尾；其余项目运行依赖保持原顺序。不转换 annotation/runtime JAR，不把 IDE 自带 Jetifier 当运行依赖。

Jetifier API 确实提供 `reversedMode`，但这不是魔改 ARouter 的反向兼容证据。明确的 Support 基线遇到 AndroidX-only compiler 时，先做真实产物反向探针；只有引用、SPI、Java 生成结果均成立才启用该方向，最终通过对应 Flow 验证。探针失败则保留该场景的明确失败边界，不切换成 AndroidX 假装兼容。

### 4. 扩展现有检查点，避免依赖变化被缓存掩盖

继续使用 `arouter/<module.name>/<buildVariant>/sources` 和 `checkpoint.txt`，不新增数据库、公共缓存服务或独立配置。签名覆盖声明/相关常量与继承、module/variant、所有合并选项、Gradle 生成源码基线、选中 processor SPI 集合、处理器实际内容摘要、处理器运行依赖指纹、目标 Fragment 和转换参数/版本；本地改名 JAR 不再靠 library dependency 的名称是否含 `arouter` 判定是否纳入。

保留生成源码内容摘要检查。旧检查点不能满足新签名时只使该模块本次生成缓存失效，不删除 Gradle 产物。失败不发布新检查点、不返回旧源码假装生成成功；下一次正常输入可以重试。完整 Gradle 构建导致基线变化时废弃旧生成清单，按新基线重新建立检查点。

删除保护不能只比较上一次 Jugg checkpoint：首次使用内置能力时，把当前 Gradle 基线中已有 ARouter 生成文件的相对路径也纳入旧清单，避免首次增量删除某个路由组或注入辅助类时漏检。删除最后一个 ARouter 声明但基线仍有生成文件时，也不能走“无声明”的提前返回。保留已有按文件名识别 ARouter 基线的边界，不硬编码生成包；生成文件集合收缩时要求完整 Gradle 构建。仅修改索引内容而文件集合不变时继续生成，并交给现有重启策略。纯文件删除能否进入本轮仍取决于上游变更发现，本次不扩大该入口。

缓存命中仍返回缓存 Java 源码，保持现有编译/部署契约。首版不顺带优化“相同索引是否重新编译/重启”，其性能影响在 Flow 中记录。正文或普通方法体变化而签名不变时不会重跑处理器。

### 5. 补齐内置 failClosed 的错误归属查询

`JuggAptCompiler.isFailClosedGeneratedFile(file)` 查询已有 `processors` 和当前外部 `IJuggAptProcessor` 的 `failClosed && ownsGeneratedFile(file)`；不暴露处理器列表、不扩展 `ICompileContext`。`SourceCompiler.shouldRetryWithoutJuggApt()` 将原有外部列表查询替换为此入口，仍只对 `hasDirectSourceDiagnostic` 的错误文件判断归属。

结果必须满足：ARouter 生成阶段失败继续抛出中性的 `JuggAptFatalException`；ARouter 生成文件的直接语言编译错误保留第一次失败，不去掉 JuggApt 产物重试；仅由 Kuikly 等 fail-open 生成文件直接报错时仍沿用既有单次重试。ARouter 原异常与堆栈只由内置处理器 warn 记录，公共层不扩展业务日志。

### 6. 定向验证后迁移 Demo 与文档

先在不配置 custom compiler 的标准版 Demo 完成内置生成；再验证 YB 保留旧配置时仅外部路径运行，移除旧配置后仅内置路径运行。外部 Demo 修改单独核对工作区状态，仅移除实际承载 ARouter 的配置项，若 JAR 同时承载其他扩展则保留其他能力；不能删除整个 `customCompilers` 列表。

实现完成后再同步 `02_compile_custom_ui.md`、`98_code_map.md` 及 Wiki 中英文能力页，描述最终已验证的内置能力与边界。现有重启页面保持其真实策略；历史文章不改写成首版承诺。方案评审阶段只更新任务方案，实施完成后同步知识库和 Wiki。

## 验证与风险

- **既有失败证据**：标准 1.5.2 原始 compiler + AndroidX Demo 生成失败；本地 Jetifier 处理后的同一 compiler 生成 3 文件。当前适配器的 `AROUTER_MODULE_NAME` 前置检查会拒绝只配置 `moduleName` 的项目，即使后续 javac 已透传该参数。
- **测试价值**：生成文件、缓存失效及 `failClosed` 是稳定、易被真实破坏的可观察行为，归 `main` APT behavior owner（L1/L2）；先补失败断言。`moduleName` 只是小范围前置接受和原样透传，不为此制造测试专用接口或绑定私有判断的自动化测试。不增加版本识别或旧版特殊日志的测试。Jetifier 类加载、反向转换及插件打包用真实产物探针和构建验证。
- **定向验证**：以旧版产物检查仅配置 `moduleName` 时不再被适配器前置检查拒绝，记录处理器的自然结果；以官方 1.5.2、已 Jetified 标准包及无版本 YB 包验证生成结果；另验证 Java/Kotlin 触发、缓存命中/失效、直接生成源码错误不被重试吞掉，以及 APK 单侧/双侧/无结果/多 APK 歧义默认 AndroidX。旧版不设完整兼容性通过门槛。运行 `:main` 定向测试、`:idea:compileKotlin`、`:idea:buildPlugin` 和插件包依赖检查。
- **L3/等价 Flow**：标准 AndroidX Demo 与 YB Demo 都先完整 Gradle 构建建立 APK DB，再修改路由执行 Jugg 增量编译；YB 移除旧 custom compiler 配置后验证生成与日志。若某个环境无法运行，记录已达成的层级，不把处理器探针等同端到端成功。
- **已知边界**：不承诺早期 ARouter 的 `@Param` 等差异都能正确触发增量生成，也不主动让低版本失败；若实际处理器调用失败，按现有 warn / `failClosed` 语义呈现。官方未发布或任意改动注解包/SPI 的 fork 无法仅凭“ARouter”名称保证兼容；选定 AndroidX 但编译类路径没有该类，或反向转换不安全时会明确失败。旧 Support 场景少见，但单侧明确命中时仍按旧 Support 选择，不被模糊场景的 AndroidX 默认覆盖。

### 验收矩阵

| 场景 | 必须观察到的结果 | 验证落点 |
|---|---|---|
| 无 ARouter 声明、无检查点、无 Gradle ARouter 基线 | 无生成、无必要参数错误，原有 Kuikly 与普通编译正常 | 已有 `JuggAptCompilerTest` 回归及 Flow |
| 同一 compiler 同时在 kapt/annotationProcessor；SPI 混有其他处理器 | ARouter 只执行一次，其他处理器不启动 | ARouter L1 生成产物断言 |
| 两个不同 compiler 候选、相关 SPI 缺失、必要参数缺失 | 相关模块失败，有路径和原因，不返回旧生成物 | ARouter L1/L2 失败断言 |
| 官方 1.5.2 + AndroidX、已 Jetified 包、YB 无版本包 | 使用匹配处理器，生成预期路由/注入/拦截器源码 | 标准包自动化；YB 真实产物探针与 Flow |
| 单侧 APK、双侧、无查询结果、多 APK | 单侧选对应类型，其余选 AndroidX；有效类路径缺目标时失败 | ARouter L1/L2 与真实类路径探针 |
| 缓存命中；修改路由、相关常量/父类、选项、processor 内容/依赖或 Gradle 基线 | 前者复用源码；后者重生成，不能仅靠 mtime/length 掩盖相同大小内容变化 | ARouter L1 生成结果/检查点行为断言 |
| 首次内置生成删除基线类/最后一个声明；已有检查点后删除生成类 | 都要求完整 Gradle 构建，不继续部署陈旧类 | ARouter L1 + 等价 Flow |
| Java/Kotlin Route、Autowired、Interceptor、alias 与当前范围内常量/继承 | 生成源码符合输入；KAPT 无 stub 明确失败 | ARouter 真实处理器 + KAPT 探针 |
| 内置/外部 failClosed 与 fail-open 生成失败、生成文件直接编译错误 | 前者失败不被重试吞掉；后者保留原降级 | `JuggAptCompilerTest` L1/L2 + SourceCompiler 等价 Flow |
| 旧配置保留/移除；缓存生成索引部署 | 分别只有外部/内置执行；索引沿用既有重启策略 | 标准 Demo 和 YB Demo 的完整编译→部署→路由验证 |

自动化只通过公开生成/编译结果或归属查询契约验证上述行为，不反射私有函数，不为 `SourceCompiler` 增加可替换 processor factory。无法稳定自动化的真实 IDE 生成错误通过等价 Flow 保存证据，不仅靠 `JuggAptCompilerTest` 宣称已覆盖语言重试链路。

实施时的命令范围：

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.source.apt.ARouterJuggAptProcessorTest' --tests 'com.sickworm.intellij.jugg.compiler.source.apt.JuggAptCompilerTest'
./gradlew :idea:compileKotlin
./gradlew :idea:buildPlugin
```

构建后检查 Jetifier core/processor、默认映射资源及其运行依赖实际入包，并在目标 IDE 验证可加载。不预先引入额外 classloader 或 shading；若发现真实冲突，记录冲突对象并重新评审最小打包修正。探针、构建、Flow 各自报告结果，不能互相替代。

## 本轮方案核对记录（2026-10-10）

- 本轮通过 Android Studio index 定位并读取了 `IJuggAptProcessor`（已有 `failClosed`、`ownsGeneratedFile`）、`ProcessorRegistration`、`JuggAptCompiler`、`SourceCompiler`、外部 `ARouterCompiler`、`ICompiler.kt` 中的 `ICompileContext`、`BaseCompileContext`、`BaseCompiler` 和现有 APT 测试；确认无需扩展项目模型或 APK DB。
- 读取本地 Jetifier `1.0.0-beta10` 源码及 POM，确认正/反向 API 和运行依赖存在；仅确认 API，未新增转换成功或完整兼容性结论。
- 读取 `ArtifactRestartPolicy.kt` 与 Wiki ARouter 相关说明，确认注册索引已有重启策略。
- 原方案中的官方版本历史、标准包失败/转换成功和 YB 成功来自既有调查记录；本轮未重跑这些探针，也未执行生产编译、测试或部署。
- 方案评审阶段只完善此方案；实施前仍需按上表取得与最终代码对应的失败断言及验证证据。

## 评审确认点

本轮默认发现并调用 ARouter，接受 `AROUTER_MODULE_NAME` / `moduleName`，**不做版本判断或低版本特殊处理**。早期版本的其他差异仅作为代码注释中的已知问题；成功与否由实际处理器运行决定。双类型/无类型/多 APK 歧义默认 AndroidX。确认此边界及上表实施范围后再改生产代码。

## 实施记录（2026-10-10）

用户已批准上述范围。已迁入内置处理器、注册默认入口并接入内置 failClosed 所有权查询；外部 `id = ARouter` 配置存在时保持旧路径。Jetifier 只作为运行依赖，标准 ARouter/Kotlin/Fragment 产物仅用于测试。

范围内的补充一致性调整：`02_compile_source.md` 更新内置/外部失败语义；Wiki 注解器能力表及英文镜像补充 ARouter；依赖清单新增 Jetifier Processor、未重定位 ASM 8.0.1 组件组和 JDOM 2.0.6，现有生成器及 `idea/build.gradle` 的固定条目数同步为 108。这些调整沿用原有文档和打包检查，没有引入新框架。

源码签名沿用文本事实提取。新增失败断言发现旧副本会删除注解/常量字符串内的空格，导致不同路由复用缓存；实现改为仅去掉字面量外的格式空白，保留字面量原值。方法体变化仍复用生成源码。独立只读审查另发现合法的 `@Route (path = "...")` 会因短注解名尾部空白漏掉触发；真实 APT 首次生成断言复现后，仅对提取出的注解名调用 `trim()`，并验证首次生成和路径变化后的缓存失效。

### 验证证据

- 实现前新增的默认生成与首次 Gradle 基线删除保护断言失败，记录于 `/tmp/jugg-arouter-red.log`。字面量空白变更的失败断言记录于 `/tmp/jugg-arouter-literal-red.log`，注解格式空白的失败断言记录于 `/tmp/jugg-arouter-spaced-red.log`；修复后纳入定向回归。
- 定向回归包含真实 ARouter 1.5.2 APT、Kotlin KAPT、Jetifier 正反转换、SPI/候选/参数失败、缓存复用/失效、基线删除、旧配置并存和真实 SourceCompiler 生成源码语言错误；同时运行原有 Kuikly fail-open 重试 owner。
- 默认 shell 的 JDK 21 不兼容本仓库 Gradle 7.3.3（class file 65）；验证命令使用本机 Corretto 17 与 Android SDK，不修改仓库 JDK 配置。
- 插件包已核对 Jetifier core/processor、`default.config` / `default.generated.config`、ASM 五个 JAR、JDOM 2.0.6，未打入 ARouter compiler。完整构建及 Flow 结果见下文。
- Wiki 结构校验通过，VitePress build 通过，中英文四个改动页面的结构与内容已同步。

设备 Flow 用现有 `MockJugg` 和 `RequiresDeviceRule` 加载本工作区实现；临时探针与 Gradle init script 保存于 `/tmp/jugg-arouter-flow/`、`/tmp/jugg-arouter-flow.gradle`，不引入生产 seam 或提交本地项目路径。探针在 `finally` 恢复 Demo 源码。验证使用物理设备 `RF8M82H39HD`，运行时仅主动启动验证页面，不新增或猜测 auto-run 入口。外部 YB Demo 既有用户改动已另存 `/tmp/jugg-arouter-yb-user-backups`，迁移只改 ARouter 配置项。

已完成的定向结果：`ARouterJuggAptProcessorTest` 23 项、`JuggAptCompilerTest` 10 项、`SourceCompileTest.juggAptRetry_shouldTrackAndRollbackChangedKuiklyEntry` 1 项，共 34 项通过。`:idea:compileKotlin`、`:idea:buildPlugin` 和现有 `verifyThirdPartyCompliance` 通过，日志 `/tmp/jugg-arouter-final-checks.log`。官方 compiler **artifact 1.0.4**（与仓库 tag 名不是同一版本维度）的 `Consts.KEY_MODULE_NAME` 为 `moduleName`；以该参数、真实 JAR 和内置生成入口运行探针，自然生成 3 个 Java 文件，未加入版本判断或旧版专用处理。

标准 AndroidX Demo Flow 通过：完整 Gradle 构建与安装后，在同一 app 路由组修改 Java/Kotlin 路径；内置生成 3 个索引 Java，语言编译及 D8 成功；部署到物理设备后主动启动验证页，`LogisticsCenter.completion()` 分别解析到 Java/Kotlin 目标 Activity。运行于仓库 IDEA 223 测试 sandbox 的 Jetifier 转换成功，证明该 IDE 测试运行时可加载；没有替换正在使用的 Android Studio 插件，不能将测试 sandbox 结论写成其实际插件重启验证。

上述 XML 与标准 Flow 编译日志保存于 `/tmp/jugg-arouter-verified/`，临时验证代码未提交。生成删除后的“新 Gradle 基线”测试保留真实包路径和新组清单；仅清空目录不是完整构建刷新后的路由生成结果。

YB Flow 通过：旧配置保留时读取实际配置并通过既有 `CustomCompilerManager` 加载真实扩展 JAR，内置只打印跳过日志，外部生成/编译成功。`MockJugg` 默认省略了 IDE 的 server/config 订阅和 custom compiler 初始化，临时探针显式连接已有公开 API；没有修改生产逻辑或测试工具。初次探针还使用了错误的 APK 路径，已按该工程的 `layout.buildDirectory` 修正为 `build/app/outputs/apk/debug/app-debug.apk`。这两类探针配置失败不属于插件实现失败。

随后只移除外部 Demo `app/build.gradle` 的旧 ARouter adapter 配置、MD5 计算和输入依赖，保留生成配置任务及其他字段。完整 Gradle 构建重建基线后，内置使用无版本元数据的原 YB compiler，生成 6 个 Java 文件（路由索引、拦截器索引及 Java 注入辅助类），Java/Kotlin 编译与 D8 成功；设备部署后新 Java/Kotlin 路由均解析到预期 Activity。日志分别为 `/tmp/jugg-arouter-yb-legacy-flow.log` 与 `/tmp/jugg-arouter-yb-builtin-flow.log`，XML/编译日志保存在 `/tmp/jugg-arouter-verified/`。原有用户源码和扩展 JAR 内容保持原值；Gradle 重建的、原先已被用户修改的 `buildSrc/build/libs/buildSrc.jar` 已从预存备份恢复。

补充的相关父类变更（Activity → Service）和 Java Autowired/Interceptor 真实生成断言各 1 项通过。独立审查修复后重跑完整定向集合：ARouter 26 项、JuggAptCompiler 10 项、Kuikly fail-open owner 1 项，共 37 项通过，日志 `/tmp/jugg-arouter-review-checks.log`，最终 XML 已保存至上述证据目录。三个设备/配置 Flow 与旧版 moduleName 探针均通过。Wiki VitePress 产物确认中英文 ARouter 能力段和注解器表格已渲染，结构校验通过。

独立只读复审确认上述格式空白问题已闭合，无未解决阻断项；复审核对了红/绿日志、37 项最终 XML 和方案记录。最终提交后重新执行插件编译与打包，保证分发包的源码版本元数据指向最终提交。
