# Hilt 入口字节码转换实施方案

> 创建日期：2026-09-06
> 状态：已于 2026-09-06 完成实现、定向回归、真实设备验证和文档同步。
> 本轮交付：Jugg 在 D8 前复用一次 class 分析识别并转换现有 Hilt Android 入口；注入声明或依赖图变化仍由用户主动执行完整 Gradle 构建。

## 1. 目标与已确认决策

让已经通过完整 Gradle 构建的 Hilt 项目，在使用 Jugg 重新编译 Android 入口类后，继续保留 Hilt 注入入口。对外能力描述为：

**支持 Hilt 入口字节码转换；注入声明变化需要用户主动执行 Gradle fallback。**

用户已明确决定以下五点，实施时无需重新讨论或要求确认：

1. **不识别是否只修改了普通逻辑。** 保持现有不支持的注解处理器行为，正常编译；如果修改要求重新生成 Hilt/Dagger 代码，由用户主动执行完整 Gradle 构建。
2. **转换语义由 Jugg 持续维护。** 实现小范围的等价 ASM 转换，不在运行时调用项目 Hilt Gradle 插件的转换器。
3. **不新增 Hilt 专属自动回退。** 转换错误沿用普通编译失败处理；用户自行选择 Gradle fallback。既有通用回退机制保持原样。
4. **转换触发复用现有 D8 前字节码分析。** 不为所有 class 增加一轮独立文件读取；在现有 Desugar 分析读取中同时识别 Hilt 注解，非 Hilt 路径不得增加额外 class I/O 或第二次 ASM 遍历。
5. **`TransformerCompiler` 是通用的 pre-D8 class preparation owner。** 它由 `DexCompiler` 在实际调用 D8 前执行，不注册成独立的 `BaseCompiler` sibling，也不引入 Transformer SPI/registry。第一版只接入具体的 Hilt 转换器，第二个真实转换需求出现后再评估接口抽象。

这些决策覆盖前期讨论中关于自动识别、源码快照、专属回退、复用官方转换器、APK 基线候选索引和独立 Transformer 阶段的建议。旧文档 [Hilt 增量编译适配调研](../2026-08/2026-08-05-hilt-incremental-compile-adaptation-research.md) 仅用于背景和官方资料导航；冲突时以本方案和用户最新指令为准。

## 2. 范围与非目标

### 2.1 本次范围

- 在 Java/Kotlin 编译产物进入 Dex 前，对带 `dagger.hilt.android.AndroidEntryPoint` 或 `dagger.hilt.android.HiltAndroidApp` 的原始 class 执行转换。
- 覆盖用户直接修改及影响传播跟编产生的 class；已转换的 class 再经过此入口时不能重复转换。
- 不依赖 APK 基线是否已有该类；本轮新增或直接输入的 class 也通过自身注解参与识别。
- 复用当前模块及依赖 classpath 中已有的 `Hilt_*` 基类、Factory、MembersInjector 和组件。
- 实现父类、泛型签名、构造器、普通 `super` 调用，以及 BroadcastReceiver 的特殊 `onReceive` 注入语义。
- 未命中 Hilt 注解的 class 保持既有结果和处理路径。
- 转换只处理当前入口 class，不改变既有注解处理器开关、依赖图、部署策略或对象生命周期。

### 2.2 明确不做

- 不运行或扩展 Java APT、KAPT、KSP1、KSP2 的 Hilt/Dagger 处理能力。
- 不生成或修改 `Hilt_*`、Factory、MembersInjector、ComponentTreeDeps 或 Dagger 组件源码。
- 不增加注入声明差异分析、依赖图变化检测、源码内容快照、删除事件跟踪、版本升级监听或生成物失效系统。
- 不通过专属预检、异常或编译结果强制自动执行 Gradle；也不修改现有通用失败后的下一次 Run 行为。
- 不引入 Hilt 专用配置开关、通用插桩框架、只有一个实现的接口或处理器注册体系。
- 不为跨阶段传递分析结果增加全局可变缓存、ThreadLocal、持久化索引或基于文件时间戳的失效系统。
- 不实现通用 Gradle Transform/AOP 兼容，也不扩展现有 jar diff 或整包字节码重写能力。
- 不重建运行中的注入对象，不提供修改 binding 后自动刷新 Singleton 的承诺。
- 不把这项能力描述为完整 Hilt 支持；Hilt 测试组件、混淆、动态特性和多 APK 的完整兼容性不得凭单个 Debug 用例宣称。

## 3. 实施基线与代码落点

以下是方案阶段核对并由本次实现沿用的代码事实。

| 事实 / 责任 | 当前路径或入口 | 对实现的约束 |
|---|---|---|
| 模块内 Kotlin、Java 和直接 class 输入最终由 `SourceCompiler.compileDexOutputs()` 交给 `DexCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | `SourceCompiler` 保持聚合责任，不再增加一轮 class 扫描或 Hilt 专属处理 |
| `DexCompiler.doDex()` 在实际 D8 前调用 `context.getDesugarInfo()` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/DexCompiler.kt` | 通用 Transformer 应位于该调用点前后形成的 pre-D8 preparation 内部，不做顶层 sibling 阶段 |
| jar 输入先在 `DexCompiler.diffJar()` 中通过 CRC 找到变化 entry，并在常见增量路径解压为 class | `compiler/source/DexCompiler.kt` | Transformer 优先处理已经展开的变化 class，禁止让同一个 jar 再完整遍历一次；无旧 jar 的整包路径保持既有语义，不借本任务扩展整包重写 |
| `DexCompiler` 在 D8 前统一读取本轮 program class，`ClassFileParser` 遍历 header、interface、`INVOKESTATIC`、invokedynamic 与 annotation | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/DexCompiler.kt`、`deploy/data/ClassFileParser.kt` | Hilt 转换与 Desugar 必须消费同一份显式 preparation；非 Hilt class 不增加文件读取或第二次分析 visitor |
| 单次 parser 已收集 `externalSuperClasses` | `ClassPreparation.analysis`、`CompileEffectAnalyzer.getDesugarInfo()` | 显式传递分析结果，首批 program class 不再重复父类解析 |
| 递归父类链只消费 `superName` | `CompileEffectAnalyzer.getDesugarSuperclassFiles()` | 依赖父类使用 header-only 分析，设置 `SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES`，不遍历方法指令 |
| `JuggDeployData` 基于 D8 后的 DEX 构建 | `DeployDataGenerator.buildDeployData()`、`ApkParser.parseDex()`、`IncrementalCompilerHelper.compile()` | 该阶段必须保留最终 DEX 解析，不能承担需要在 D8 前完成的 Hilt 转换，也不进入 recompile loop 二次修正本轮产物 |
| class analysis、最终 program files 与 Transformer classpath 必须保持同一状态 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ClassPreparation.kt` | 通过显式 `ClassPreparation` 串行传递，不使用 `CompileFile.extraInfo` side channel，也不保留下游重复解析 fallback |
| classpath 有模块、临时输出、依赖目录和 jar 的既定顺序 | `main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt`：`getModuleDependencies()` | 查找生成基类时保持解析优先级，不硬编码 Gradle 生成目录 |
| 已有目录和 jar 的 class 查找工具不能保持目录/jar 交错顺序 | `main/src/main/java/com/sickworm/intellij/jugg/deploy/ClassFileLookupHelper.kt` | Hilt 生成基类查找不能直接复用该工具；按本轮 class 索引和有效 classpath 顺序查找 |
| 普通失败与可自动回退的异常走不同路径 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt`、`idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 可预期转换失败必须形成普通失败结果，不借用 unexpected exception 通道触发自动 Gradle |
| 存在可复用的内部 ASM | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/BaseJuggAptProcessor.kt` | 优先使用 `com.sickworm.intellij.jugg.org.objectweb.asm`，不额外引入项目 Hilt/AGP runtime |

### 3.1 方案对比

| 方案 | 正确性与性能 | 架构代价 | 结论 |
|---|---|---|---|
| 由 `DexCompiler` 建立显式 preparation，再交给 pre-D8 `TransformerCompiler` 与 Desugar | 不依赖 APK 基线，覆盖新增/直接 class；非 Hilt 无额外 class I/O；还能删除现有重复解析 | 需要让现有 parser 产物可复用，并调整 Desugar 消费方式 | **推荐** |
| 在 `SourceCompiler` 后注册独立 `BaseCompiler`，逐 class 扫注解后再进入 Dex | 触发直观，但所有 class 增加一次文件读取和 ASM 解析；分析结果还需通过公共模型、缓存或 side channel 交给 Dex | 常态损耗和跨阶段状态都不必要 | 排除 |
| 基于 APK 数据库中旧类与 `Hilt_*` 父类关系筛选 | 对已有 Hilt 入口成本低，但无法独立发现新增 class；混淆名称还要额外映射 | 需要新增查询能力，且只能作为历史提示，不能作为正确性 owner | 排除为主方案，不实现 |
| 在 `JuggDeployData` / recompile loop 中检测后重新转换和 D8 | 能看到最终 DEX，但发现时第一份错误 DEX 已进入 staging；需要回滚、替换和再次 D8 | 与影响传播、编译状态和 staging 生命周期冲突 | 排除 |
| 通过 D8 `ProgramResourceProvider` 在 D8 读取时转换 | 理论上可少一次 D8 文件读取，但当前同时兼容内置 R8 和隔离加载的多版本 AGP R8，现有稳定边界只依赖 CLI-style API | 反射兼容与 fallback 一致性风险明显超过收益 | 排除 |

最终结构是：`DexCompiler` 作为通用 class preparation owner，在 `doDex()` 内单次读取和分析 program class；`TransformerCompiler` 作为具体协作者只消费、转换并更新 preparation，不重新解析 program input。第一版不实现 `ITransformer`、SPI、registry 或动态发现；`HiltAndroidEntryPointTransformer` 是唯一具体转换器。

### 3.2 实施变更清单

#### 生产代码

| 路径 | 修改责任 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ClassFileParser.kt` | 将聚合结果整理为可复用的每 class / batch analysis；支持消费已读取字节，并提供只解析 class header 的父类链入口 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/ClassPreparation.kt` | 显式携带 program input、bytes、batch analysis、最终 files、分析性能数据和 Transformer 必要 classpath；不进入 `CompileFile.extraInfo` 或部署历史 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/TransformerCompiler.kt`（新增） | 只消费 `DexCompiler` 建立的 preparation；非命中沿用原 input，命中后复用内存字节输出转换产物，并更新最终 analysis 与必要 classpath |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/HiltAndroidEntryPointTransformer.kt`（新增） | 使用仓库内置 ASM 完成 Hilt 等价转换；只处理已由分析阶段确认命中的字节，读取生成父类及 Receiver marker，并返回最终父类和必要 classpath 信息 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/DexCompiler.kt` | 在 program class 已确定后统一读取和分析输入，再依次调用 `TransformerCompiler`、`getDesugarInfo` 与 D8。jar diff 后的变化 class 复用同一路径，整包 jar 不新增第二次遍历 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | Desugar 查询直接消费显式 batch analysis，不读取 program class，也不维护 metadata fallback |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzer.kt` | 直接使用 preparation 的 external superclasses 和必要 Hilt classpath；递归父类只做 header-only 分析，保持现有有序查找和去重契约 |

实现将 `ICompileContext.getDesugarInfo()` 的首个参数从隐式携带 metadata 的 `List<CompileFile>` 调整为显式 `ClassPreparation`，未修改 `CompileTask`、`CompileOutput`、部署/recompile 编排或 IDE fallback。`SourceCompiler` 仅把 Dex 阶段的首个实际失败原因映射回原 source task，确保 Hilt 生成基类缺失提示不会被通用错误覆盖。

#### 自动化测试与真实 fixture

| 路径 | 修改责任 |
|---|---|
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/data/ClassFileParserTest.kt` | 扩展现有 L1 owner，验证每 class/batch analysis、Hilt 注解 descriptor、external superclass 合并和 header-only 结果；不通过 mock 文件读取次数绑定实现 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzerTest.kt` | 验证预分析结果仍能补齐 default interface 与完整父类链，并覆盖 Transformer 必要 classpath 与原有去重规则 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/HiltAndroidEntryPointTransformerTest.kt`（新增） | L1 行为 owner；用 ASM/编译产物验证父类、signature、调用改写、Receiver marker、幂等和失败边界 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/SourceCompileTest.kt` | 验证 pre-D8 接入、非 Hilt 原路径、直接/新增 class 不依赖 APK 基线、必要生成基类缺失时明确失败，以及 D8 最终产物正确 |
| `android_demo_project/build.gradle`、`android_demo_project/app/build.gradle` | 增加仅由 `-PjuggHiltFixture=true` 启用的 Hilt 插件、依赖和 debug source set；默认 Demo 构建保持不变 |
| `android_demo_project/app/src/hilt/AndroidManifest.xml`（新增） | 仅在 Hilt fixture 中替换测试 Application，并声明 Activity/Receiver |
| `android_demo_project/app/src/hilt/java/com/sickworm/jugg/demo/testcase/hilt/*`（新增） | 提供真实 `@HiltAndroidApp`、Activity、BroadcastReceiver 和 `@Inject` 依赖，生成物全部由完整 Gradle/Hilt 基线产生 |
| `idea/src/test/assets/android/modify_source/hilt/*`（新增） | 提供只修改普通方法逻辑、不改变注入声明的 Activity/Receiver 变更样本 |
| `idea/src/test/java/com/sickworm/intellij/jugg/manager/HiltTopLevelFlowTest.kt`（新增） | 独立管理 Hilt 属性基线，验证安装、增量编译、部署、Activity 注入与 Receiver `onReceive` 注入；结束后强制恢复默认 Demo 基线 |

Hilt fixture 首选版本为 `2.51.1`：其 Kotlin 依赖与 Demo 的 Kotlin `1.9.22` 接近，且官方 visitor 与 `2.60.1` 的核心转换语义一致。该版本是否可在当前 Gradle `7.4` / AGP `7.3.1` / 当前 JDK 组合完成构建，必须作为实施阶段第一项真实基线验证；不可用时只调整 Hilt fixture 版本，不升级 Demo AGP、Gradle 或 Kotlin。

## 4. pre-D8 共享分析与转换流程

```text
既有 Java/Kotlin 编译输出 + 直接 class 输入
  -> DexCompiler 完成当前输入分组 / jar diff
       -> 每个 program class 只读取一次
       -> ClassFileParser 收集 class/interface/static invocation/external superclass
       -> 建立显式 ClassPreparation
  -> TransformerCompiler.transform()
       -> 消费已有 bytes 与 analysis，不重新读取 program class
       -> 非 Hilt：沿用原 input
       -> Hilt：复用内存字节执行转换，只写命中产物，并更新最终 analysis
  -> CompileEffectAnalyzer 消费最终 preparation 解析 Desugar 与必要 classpath
       -> 递归依赖父类只读取 header
  -> D8 读取最终 program files
  -> 沿用现有 minify / DEX 影响传播 / staging / 部署流程
```

### 4.1 单次读取与分析契约

- program class 的主分析保持现有 `ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES`，因为仍需遍历方法指令收集 `INVOKESTATIC` 和 invokedynamic；在同一 visitor 增加 class annotation descriptor 判断。
- 为让命中类复用内存字节，`DexCompiler` 负责打开输入并创建 `ByteArray`；`ClassFileParser` 消费现成 bytes，`TransformerCompiler` 不能再次打开同一 program class。
- 非 Hilt class 不创建副本、不写临时文件、不执行第二次 ASM visitor。新增成本只允许是 annotation callback、descriptor 比较和小型 analysis 对象。
- Hilt class 可以对同一 `ByteArray` 再执行一次转换 visitor；这是命中路径必要成本，不属于所有 class 的常态损耗。
- Hilt 转换会改变直接父类和 `INVOKESPECIAL` owner，但不会新增 `INVOKESTATIC`。最终 analysis 将直接父类替换为 `Hilt_*`；interfaces 与 static invocation 结果可复用初次分析。为保持简单，可保守保留原父类作为额外 classpath 候选，但不得遗漏新的 `Hilt_*` 父类。
- analysis 通过不可变、仅进程内使用的 `ClassPreparation` 显式传递；其中的 `CompileFile.copy` 保留既有 module、相对路径、dependencyPaths 和 jar 元数据。preparation 不进入 `CompileOutput` 或部署历史。
- `DeployDataGenerator` 与 `CompileEffectAnalyzer` 只消费 preparation，不再各自维护 program class fallback parse；解析缺失应在 `DexCompiler` preparation 边界明确失败。
- 初始 program class 的 external superclasses 直接作为父类闭包起点。后续依赖父类仅需 class name、superName 和必要 annotation 时，使用 header-only visitor，禁止遍历 code。
- 增加 trace 性能数据：至少记录 program class 数、字节总量、Hilt 命中数、分析耗时和转换耗时；不为零命中批次打印用户可见日志。

建议保持两个内部不可变模型，字段可按实际实现合并，但不得把分析状态扩散到公共编译模型：

```kotlin
internal data class ClassAnalysis(
    val className: String,
    val superClass: String?,
    val interfaces: Set<String>,
    val staticInvocationRefs: Set<String>,
    val annotationDescriptors: Set<String>,
)

class ClassPreparation internal constructor(
    internal val inputs: List<ClassPreparationInput>,
    internal val requiredClasspathFiles: List<CompileFile>,
)
```

batch 合并后再统一排除同批已声明 class 和 boot class，保持现有 `ClassFileParser`“同批输入不作为外部引用”的语义。若最终实现能用更少字段表达同一契约，应优先删减字段，不为假设中的其他 Transformer 预存信息。

### 4.2 命中、classpath 与重复处理

- 使用 ASM 读取 class 注解的完整 descriptor，不使用 APK 基线、源码 import、短注解名或文件名猜测。
- 按官方命名规则计算生成父类：同 package 下的 `Hilt_` 前缀，嵌套类名中的 `$` 转为 `_`。通过真实嵌套类用例核对规则。
- 当 class 已直接继承其对应 `Hilt_*` 时，保持输入不变。显式继承生成基类的写法也不能被再次插入一层父类。
- 生成基类与其他依赖只读；先查本轮 class 集合，再严格按 `context.getModuleDependencies(module, task)` 的目录/jar 交错顺序查找。
- Hilt 转换已经读取到的生成基类和 Receiver marker 信息应进入 `requiredClasspathFiles`，由 Desugar/classpath preparation 合并复用，不能在转换和 D8 classpath 补齐阶段各查找、读取一次。
- 不直接复用 `ClassFileLookupHelper`：它会先遍历所有模块目录、再遍历 jar，不能保持当前编译 classpath 的目录/jar 交错顺序。

### 4.3 等价转换语义

实现时以第 9 节固定版本的官方 visitor 为基准，逐项验证以下行为：

1. 将入口 class 的直接父类替换为对应 `Hilt_*`，同步 class generic signature。
2. 改写真正的 `super()` 构造调用和普通 `super.method()` 调用，保持其他对象实例化、`this()` 委托和无关 owner 的调用语义。
3. 对具有 `onReceive(Context, Intent)` 的入口，在对应生成基类带有 `dagger.hilt.android.internal.OnReceiveBytecodeInjectionMarker` 时，在方法入口插入生成父类的 `onReceive` 调用。
4. 缺少该 marker 不等于错误，不应无条件为所有 `onReceive` 插入调用；必要基类无法读取时，明确返回转换失败。
5. 保持其他字段、方法、注解、调试信息和方法体行为；初始实现使用 `ClassWriter.COMPUTE_MAXS` 调整 Receiver 新增调用所需栈深，不使用会加载业务类型的默认 `COMPUTE_FRAMES`。最终以 D8 和设备验证为准。

不能为计算 stack frame 直接加载 Android 或业务 class 到 IDE JVM；如确实需要类型层级，使用字节码/classpath 信息，并先复用既有能力。遇到与基准 visitor 不同的真实合法输入，先保留复现证据，再作最小适配，不预建通用字节码框架。

### 4.4 新增 class 与生成物边界

- 注解检测来自本轮 class 自身，因此新增 class、APK 基线不存在的 class 和直接 class 输入都能被识别，不需要部署数据库候选索引。
- “能检测新增 `@AndroidEntryPoint` class”不等于“能生成其 Hilt 依赖”。如果对应 `Hilt_*`、MembersInjector、Factory 或组件绑定不存在，转换器必须明确失败并提示执行完整 Gradle 构建。
- 本次仍不运行 Hilt APT/KAPT/KSP，也不生成上述依赖。只有生成物已由完整 Gradle 基线或其他既有流程提供时，新增 class 才可能完成转换。
- 测试必须分别覆盖“APK 基线无该类但生成基类可解析，仍能触发转换”和“生成基类缺失，明确失败”，避免把检测能力误写成完整新增 Hilt 入口支持。

### 4.5 输出与失败边界

- 转换后的 class 写入 `DexCompiler` 当前受控临时目录，例如 `context.tempCompileDir/transform`；不原地覆盖 Gradle module output、依赖 jar、原始直接 class 输入或其他共享基线。
- 每个命中的输入以转换产物一对一替换，不能把原始与转换后 class 同时交给 D8；未命中 class 保持原路径。
- 某个必须转换的 class 失败时，本轮 Dex 编译正常报错，包含 class、pre-D8 transform 阶段和原始失败原因。不能把对应未转换 class 当成功产物继续 D8。
- 使用现有 `CompileError` / `CompileResult` 形成可见失败，不抛到通用“未预期异常可回退”分支；不沿用 JuggApt 的 warn 后继续策略。
- 不把转换检测放入 `JuggDeployData`，不重新进入 recompile loop 修正同一批 class，不为 Hilt 清理整个 staging、失效项目上下文或刷新 Gradle 基线。
- 日志使用 `JuggLogger`：转换细节和性能数据为 debug/trace，非预期用户可见错误为 warn；禁止 error。检查日志拼接换行符合仓库规范。

## 5. 用户场景与验收口径

| 用户操作 | 本次承诺 |
|---|---|
| 修改已有 Hilt Activity/Fragment 的普通方法 | 注入入口保留，修改后的逻辑按现有部署机制生效 |
| 修改其他已有 Hilt Android 入口 | 相应转换语义正确；按真实验证结果记录入口覆盖，不靠注解名称推断已验收 |
| 修改普通业务类或非 Hilt Activity | 保持原编译和部署行为 |
| APK 基线不存在该 class，但本轮 class 带 Hilt 入口注解且对应生成基类已存在 | 不依赖 APK 基线完成识别和转换；最终是否可部署仍按现有新增 class 与结构变化规则判定 |
| 新增 Hilt 入口且对应 `Hilt_*` / 注入生成物不存在 | 明确编译失败，提示用户完整 Gradle 构建；不伪造生成物或输出未转换 DEX |
| 增删注入字段、修改构造依赖、binding 或注解 | 不识别、不重新生成；用户主动完整 Gradle 构建 |
| 删除、重命名注入相关类型或移除注解 | 仍遵循现有注解处理与删除边界，用户主动刷新 Gradle 基线 |
| 需要的 Hilt 生成基类不存在或转换失败 | 正常编译失败，用户自行 fallback，不承诺自动恢复 |
| 同一已转换 class 再进入编译链 | 不重复改写父类，也不重复插入 Receiver 注入调用 |

不以“图变化必须被拒绝”或“必须自动 fallback”为测试断言；这两种行为均未获授权。编译成功也不意味着依赖图是最新状态。

## 6. 验证先行与测试落点

### 6.1 真实基线与失败证据

1. 先读取仓库必读文档和 `06_testing.md`，核对当前测试 owner、设备环境以及工作树。
2. 复用 `android_demo_project` 和 idea 测试侧已有的 `AssembleAndroidProjectOnce.ensure(compileCommand, forceAssemble)`。通过 `-PjuggHiltFixture=true` 启用 `src/hilt` debug source set，不修改默认 `MyApplication`、主 Manifest 或默认 APK 行为。
3. 当前 Demo 默认 Gradle 为 `7.4`、AGP 为 `7.3.1`、Kotlin 为 `1.9.22`、KSP 为 `1.9.22-1.0.17`。首选 Hilt `2.51.1` 做真实基线；不能为了使用最新 Hilt 全局提升 Demo 工具链。Hilt `2.59+` 的 Gradle 插件要求 AGP 9，不能直接套用到该默认组合。
4. `HiltTopLevelFlowTest` 使用带属性的 compile command 和 baseline command；`@AfterClass` 强制执行默认 `:app:assembleDebug` 恢复 Gradle project info 与 APK 基线，避免后续 Flow 继承 Hilt fixture 状态。
5. 使用真实 Hilt Gradle 插件/处理器完整构建，确认测试 app 注入成功。保存未转换的语言输出和官方转换后 class/DEX 作为对照；路径从实际 variant 产物查明，不把某个 AGP 中间目录写成跨版本契约。
6. 修改 Activity 普通方法，走当前 Jugg 编译。先取得确定失败断言：入口父类仍是 Android 基类或产物缺少必要转换。运行时可能表现为部署结构不兼容、注入丢失或错误，记录实测现象，不预先断言必然以某种异常崩溃。
7. 在改造前记录同一批非 Hilt class 的 `getDesugarInfo`、D8 前 class 数量与总耗时；实现后使用相同 fixture 重复测量。性能验收关注“没有新增完整 class 读取，且 default-interface 场景删除首批重复解析”，不以单次毫秒波动作为失败依据。

上述构建用于测试基线和对照，不是将生产增量路径扩展为调用 Hilt Gradle task。编辑 Android Demo 或部署验证时遵循届时适用的 `jugg-android-dev-loop` skill。

### 6.2 自动化测试价值与矩阵

class 转换正确性、重复处理和端到端注入结果是独立、稳定、可观察的契约，值得自动化。L1 可使用测试侧 ASM fixture 精确构造字节码边界，source 集成使用真实编译/D8 产物，L3 必须使用官方 Hilt 插件生成物；不要通过生产源码字符串、私有反射或 mock 调用次数替代行为断言。

以下文件/方法已按对应 owner 落地；矩阵记录实施后的实际保护范围。

| 层级与 owner | 场景 | 修改前预期 / 修改后验收 |
|---|---|---|
| L1：现有 `main/src/test/java/com/sickworm/intellij/jugg/deploy/data/ClassFileParserTest.kt` | program class 的 annotation、interface、static invocation、external superclass 分析 | 只能消费文件并暴露聚合集合 / 同一次 bytes 分析提供完整、可合并的 per-class/batch 结果 |
| 同一 L1 owner | 依赖父类 header-only 分析 | 当前使用完整 parser / class name 与父类链结果一致，不依赖方法体分析 |
| L1：现有 `main/src/test/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzerTest.kt` | 复用预分析 external superclass，补齐 default interface、Hilt 必要 classpath 与完整父类链 | 首批 class 被再次解析 / 最终 D8 classpath 等价且去重正确 |
| L1：`main/src/test/java/com/sickworm/intellij/jugg/compiler/source/HiltAndroidEntryPointTransformerTest.kt` | 普通入口、泛型 signature 和嵌套类命名 | 无等价转换 / 父类、signature 和调用与官方语义一致 |
| 同一 L1 owner | `super()`、`this()`、普通 super 调用，以及方法内构造原父类对象 | 转换缺失 / 仅改写应改写的调用，其他实例化保留 |
| 同一 L1 owner | Receiver marker 有/无、重复处理、显式继承生成基类 | 必需注入缺失 / 有 marker 时注入一次，无 marker 不新增，二次处理不改变产物 |
| 同一 L1 owner | 非 Hilt class；必要基类缺失或不可读 | 非 Hilt 行为作为原有正常基线 / 非 Hilt 不变，必要转换失败可判定 |
| 编译产物集成：`main/src/test/java/com/sickworm/intellij/jugg/compiler/source/TransformerCompilerTest.kt` 与现有 `main/src/test/java/com/sickworm/intellij/jugg/compiler/SourceCompileTest.kt` | 直接 class 输入、生成基类来自本轮目录或依赖 jar、非 Hilt source 原路径 | 入口 DEX 缺少转换 / 输出 DEX 正确，输入与依赖基线不被覆盖，未额外引入重复类 |
| 同一编译 owner | APK 基线不存在的 Hilt class，生成基类存在 | 基线索引方案无法命中 / 根据本轮 annotation 命中并在 D8 前完成转换 |
| 同一编译 owner | 新增 Hilt class 但生成基类不存在 | 可能进入 D8 后才以间接错误失败 / pre-D8 明确指出缺少必要生成基类，不输出错误 DEX |
| 同一编译 owner | 转换错误 | 无对应错误边界 / 本轮失败且不输出该模块错误 DEX，沿用普通编译失败契约 |
| L3：`idea/src/test/java/com/sickworm/intellij/jugg/manager/HiltTopLevelFlowTest.kt` | Hilt Activity 普通方法修改后编译、部署、运行 | 记录真实失败 / 观察到新逻辑且注入值正确 |
| 同一 L3 owner | Receiver 普通方法修改后收到显式测试广播 | 记录真实表现 / `onReceive` 先完成 Hilt 注入且新逻辑生效 |
| 同一 L3 owner或手工对照矩阵 | 用户主动 Gradle fallback 后再次普通修改；连续两轮修改 | 基线可建立 / 完整构建恢复生成物，后续转换仍有效，无叠加插桩 |

优先复用 `TopLevelFlowTest.testDeploy` / `testDeployKtActivity` 等既有非 Hilt 场景验证未命中路径。性能验证使用真实产物与 trace 计时，不新增用于统计文件打开次数的生产 seam，也不通过 mock 调用次数绑定内部实现。不要为本任务新增变化分类器测试或 `JuggCompileHelperTest` 的 Hilt 自动回退测试，因为没有对应生产行为。

记录至少一个真实可用的 Hilt/Kotlin/KSP或KAPT/AGP/JDK/设备组合；为降低版本维护风险，条件允许时再对比 AGP 9 对应 Hilt 组合。未验证的版本与部署模式明确列为未验证，不增加运行时白名单或自动回退配置来代替验证。

### 6.3 执行命令

按最终方法名使用 `--tests` 定向运行，禁止无过滤的全量 `:main:test` / `:idea:test`。示例：

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.deploy.data.ClassFileParserTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.deploy.CompileEffectAnalyzerTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.source.HiltAndroidEntryPointTransformerTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.SourceCompileTest.hiltEntryPoint_shouldExtendGeneratedBaseBeforeDex'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.SourceCompileTest.hiltEntryPoint_shouldFailClearlyWhenGeneratedBaseIsMissing'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.HiltTopLevelFlowTest'
./gradlew :idea:compileKotlin
```

新增/修改 testcase 后刷新 Demo 完整构建，不能复用旧 skip-assemble 产物。真实设备不可用时保留阻塞事实，不用编译通过替代 L3 验收。

## 7. 实施顺序与完成标准

1. **核对基线。** 读取当前仓库规则和最小文档，检查本方案中的代码入口；保持用户其他改动。
2. **建立失败与性能基线。** 完成第 6.1 节，先增加可判定的入口转换缺失测试并确认按预期失败；同时记录非 Hilt 和 default-interface fixture 的 D8 前分析数据。本阶段允许配置测试用 Hilt，不能先修改生产转换逻辑。
3. **重构可复用 class analysis。** 先扩展 `ClassFileParserTest`，再让 parser 能消费现成 bytes、返回 per-class/batch analysis，并增加 header-only 父类入口。
4. **消除现有重复分析。** 由 `DexCompiler` 建立显式 `ClassPreparation`，让 `TransformerCompiler`、`DeployDataGenerator` / `CompileEffectAnalyzer` 依次消费，首批 program class 不再重读，父类闭包只读 header。先跑 parser 和 desugar 定向回归，确认现有 D8 classpath 行为不变。
5. **实现最小 Transformer。** 新增 `TransformerCompiler` 和 `HiltAndroidEntryPointTransformer`。使用现有 ASM，先满足父类、signature 和调用，再覆盖 Receiver 与重复处理；按“本轮 class → 有序 classpath”读取生成基类，并把已解析的必要 classpath 交给后续复用。
6. **接入 DexCompiler。** 在 `doDex()` 内、`context.getDesugarInfo()` 和 D8 前处理当前 program class；一对一替换命中输入，核对临时输出、错误归因、module/APK 归属、直接 class 和 jar diff 后 class。出现可预期错误时返回普通失败结果；不修改 `SourceCompiler` 编排。
7. **执行定向回归。** 跑 parser、Desugar、转换产物、SourceCompileTest 和真实 L3；验证非 Hilt 路径、APK 基线不存在的 class、生成基类缺失、连续修改以及主动 Gradle 刷新后的恢复。对比性能基线，确认没有新增全量 class I/O，并记录环境和最终支持范围。
8. **同步文档。** 按第 8 节更新能力说明和索引；明确 pre-D8 共享分析、生成物复用和注入声明变化由用户主动完整构建。
9. **提交。** 检查日志格式、`git diff --check` 和任务 diff，只提交本次实现、测试及必要文档。标题按新增用户能力使用 `[feature]`，例如 `[feature] preserve Hilt injection during incremental source updates`；正文交代复用 D8 前分析、继续依赖既有生成物及手动 fallback 边界。

完成必须具备：真实失败证据、现有 Desugar 父类链回归、转换产物回归、Activity 与 Receiver 两条真实注入 L3、APK 基线不存在的 class 识别、生成基类缺失失败、非 Hilt 回归、无新增全量 class I/O 的性能证据、fixture 默认关闭且能恢复默认基线、没有新增自动图识别/回退、文档与实际验证范围一致。未满足时如实列出剩余工作。

本方案已解决产品决策，后续常规实现选择由执行者按最简设计处理；只有新证据要求扩大产品行为或改变用户已确认约束时才返回讨论。

## 8. 实现后文档同步

本轮不提前修改当前能力说明。实施完成后：

- `docs/ai_knowledge/02_compile_source.md`：把 `DexCompiler` 调用链更新为“jar diff / 显式 program class preparation → TransformerCompiler 转换 → Desugar classpath → D8”，记录正常路径不重复读取 program class、父类 header-only 分析和普通失败语义。
- `docs/ai_knowledge/02_compile_core.md`：同步 SourceCompiler/DexCompiler 内部阶段描述，明确 Transformer 不是 custom compiler hook，也没有 Hilt 专属预检或回退。
- `docs/ai_knowledge/03_deploy_data_generator.md`：说明 Desugar 查询消费 pre-D8 class analysis，`JuggDeployData` 仍解析最终 DEX，二者不能合并为同一时点。
- `docs/ai_knowledge/98_code_map.md`：登记 `DexCompiler`、`TransformerCompiler`、`HiltAndroidEntryPointTransformer` 和 `ClassFileParser` 的 preparation 与转换责任；有必要时小范围调整 `00_overview.md` 的插桩能力边界，避免误称支持全部 Gradle instrumentation。
- `docs/wiki/zh/capabilities/compile/annotation-processors.md` 及英文对应页：区分入口 class 转换与 annotation processor 执行，保留 Dagger/Hilt 生成代码变化手动完整构建的说明。
- 检查中英文 `capabilities/compile/source-compile.md` 和源码编译原理页面；仅同步受影响的调用链。编辑 Wiki 前使用 `wiki-writer` skill。
- `docs/skills/jugg-android-dev-loop/references/policy_incremental_compile_limits.md`：保留 unsupported processor 边界，但不能继续把已经覆盖的 Hilt 入口方法修改统一判为 Transform 丢失。同步 `Transform / Instrumentation Behavior` 和 `Decision Rule`，对其他插桩继续保持原限制。
- 若更新 bundled skill/policy，先按 `08_cli_tools_list.md` 和相应资源来源核对版本、打包及镜像同步规则；不要仅改源码 Markdown 却交付旧资源，也不要无依据扩大 CLI 行为。
- 更新本文状态和实测结果；原 2026-08 调研作为历史背景保留，不再照搬其自动回退或完整生成阶段计划。

## 9. 依据与实施检索入口

### 9.1 已读取的仓库文档

- [00_overview.md](../../ai_knowledge/00_overview.md)
- [99_index.md](../../ai_knowledge/99_index.md)
- [98_code_map.md](../../ai_knowledge/98_code_map.md)
- [02_compile_source.md](../../ai_knowledge/02_compile_source.md)
- [02_compile_core.md](../../ai_knowledge/02_compile_core.md)
- [03_deploy_core.md](../../ai_knowledge/03_deploy_core.md)
- [03_deploy_data_generator.md](../../ai_knowledge/03_deploy_data_generator.md)
- [06_testing.md](../../ai_knowledge/06_testing.md)
- [历史 Hilt 调研](../2026-08/2026-08-05-hilt-incremental-compile-adaptation-research.md)
- [Wiki 注解器说明](../../wiki/zh/capabilities/compile/annotation-processors.md)
- [增量限制 policy](../../skills/jugg-android-dev-loop/references/policy_incremental_compile_limits.md)

方案阶段使用 `discussion-first-development` 与 `feature-development` skill 完成调查、方案对比和范围复核；本次已按用户“落地”指令完成生产实现与验证。

### 9.2 已核对的关键代码证据

- `DexCompiler.doDex()` 在 D8 前调用 `context.getDesugarInfo()`，因此这里是共享 analysis 与 transform 的最晚正确时点。
- `DexCompiler.analyzeProgramInputs()` 在 D8 前统一读取 program class，并建立显式 `ClassPreparation`。
- `TransformerCompiler.transform()` 只消费已有 bytes 与 analysis，返回与最终转换文件一致的 preparation。
- `DeployDataGenerator.getDesugarInfo()` 直接消费 preparation 的 batch analysis，不读取 program class 或维护 fallback parse。
- `ClassFileParser` 在 program class analysis 中收集 interfaces、static invocation 和 external superclasses；`CompileEffectAnalyzer` 递归解析依赖父类时改用 header-only parser。
- `ApkParser.parseDex()` 与 `IncrementalCompilerHelper` 的 recompile loop 位于 D8 和 staging 之后，只能消费最终 DEX，不能无状态地修正本轮 JVM class。
- 父类链补齐来自提交 `a6e8d7105`（`[bugfix] preserve inherited overrides during interface desugaring`）；本次优化必须保留该行为和已有测试，不得因减少解析次数回退修复。

### 9.3 官方依据

- [Hilt Gradle setup](https://dagger.dev/hilt/gradle-setup.html)：处理器、插件转换、显式生成基类写法和聚合任务。
- [AndroidEntryPointClassVisitor，dagger-2.60.1](https://github.com/google/dagger/blob/dagger-2.60.1/java/dagger/hilt/android/plugin/main/src/main/kotlin/dagger/hilt/android/plugin/transform/AndroidEntryPointClassVisitor.kt)：本会话实际核对的转换语义基准；作为对照，不作为运行时依赖。
- [AndroidEntryPointClassVisitor，dagger-2.51.1](https://github.com/google/dagger/blob/dagger-2.51.1/java/dagger/hilt/android/plugin/main/src/main/kotlin/dagger/hilt/android/plugin/AndroidEntryPointClassVisitor.kt)：候选 fixture 版本的 visitor；核心转换逻辑与 `2.60.1` 一致。
- [Hilt 2.59 发布说明](https://github.com/google/dagger/releases/tag/dagger-2.59)：该版本的 Hilt Gradle 插件要求 AGP 9，选择测试工具链时必须注意。

引用或改编上游实现时保留必要的原始版权和许可证说明，遵循仓库依赖与许可约定。官方代码和本地实现都需使用英文注释；方案和知识库使用中文。

## 10. 当前交付状态

- 已完成 pre-D8 Hilt 入口转换、class analysis 跨阶段复用、依赖父类 header-only 解析和必要 classpath 传递。普通 program class 由 `DexCompiler` 读取和完整分析一次；`TransformerCompiler`、`DeployDataGenerator` 与 `CompileEffectAnalyzer` 显式消费同一 `ClassPreparation`，不再通过 `CompileFile.extraInfo` 或下游 fallback 隐式传递。
- 转换覆盖 `@AndroidEntryPoint`、`@HiltAndroidApp`、嵌套类生成父类命名、generic signature、构造器与普通 `super` 调用、Receiver marker、幂等和生成基类缺失失败。转换只写受控临时目录，不修改 Gradle 生成物。
- 修改前失败证据：新增 SourceCompile 行为测试中，未经转换的入口 DEX 仍继承原 Android 基类；生成父类缺失场景无法形成有效输出。实现后两条行为分别变为继承 `Hilt_*` 和 pre-D8 明确失败。
- 自动化验证：`HiltAndroidEntryPointTransformerTest`、`TransformerCompilerTest`、`ClassFileParserTest`、`CompileEffectAnalyzerTest`、Hilt 两条 `SourceCompileTest` 及既有非 Hilt `kotlinAndJavaCompile` 共 26 条定向测试通过；`TransformerCompilerTest` 覆盖 JAR/目录交错顺序、不可读 classpath 根因保留和明确 Gradle fallback 错误，`SourceCompileTest` 覆盖依赖 jar 参与 D8；`:idea:compileKotlin` 通过。
- 真实基线验证：Gradle `7.4`、AGP `7.3.1`、Kotlin `1.9.22`、Hilt `2.51.1`、Corretto JDK `17.0.17` 可完成 Hilt fixture 完整构建。
- L3 设备验证：API 35 `emulator-5554` 上，Activity 与 BroadcastReceiver 的普通方法增量修改均成功编译、部署并保留注入；Activity 随后连续执行第二轮修改和部署，仍观察到更新后的 injected marker。最终 `:idea:test --tests HiltTopLevelFlowTest` 无排除项通过，测试结束后恢复默认 Demo 基线。
- 文档已同步 `ai_knowledge`、中英文 Wiki 和 `jugg-android-dev-loop` policy；bundled skill 版本更新为 `1.0.28`，CLI 版本更新为 `1.0.15`，打包资源已核对包含新的 Hilt 边界。
- 未扩展的边界保持不变：Jugg 不重新运行 Hilt/Dagger 处理器，不识别注入图变化，不新增 Hilt 自动 fallback；混淆、动态特性、多 APK 和其他 Hilt/AGP 版本组合本次未验证。
