# 注解增量支持：接入指引与设计原则

> 最后核对：2026-11-12
> 一致性规则：文档与代码冲突时，以代码为准。

## 1. 文档定位

本页指导为 Jugg 新增注解生成能力：如何判断可支持范围、控制输入和成本、复用执行链，并验证生成物真正被消费。APT、KAPT、KSP 的具体执行方式按项目选择；`IJuggAptProcessor` 是 Jugg 的生成源码入口，不限定为 JSR 269。

本文区分**当前实现**与**新适配的设计要求**。源码阶段顺序见 [02_compile_source.md](02_compile_source.md)，扩展装载和 ARouter 细节见 [02_compile_custom_ui.md](02_compile_custom_ui.md)，验证价值与分层以 [06_testing.md](06_testing.md) 为准。

## 2. 已有模式与复用入口

| 适配 | 当前生成方式 | 输入与能力边界 | 适合参考的需求 |
|---|---|---|---|
| Kuikly Page | 改写已有 `KuiklyCoreEntry.kt`，追加缺失注册；不运行项目 KSP | 当前 Kotlin 候选；已有路由删除、重命名和修改需 Gradle 刷新 | 已有入口结构简单、可局部追加的登记能力 |
| ARouter | 比较当前注解语义与业务 class，变化后运行项目 APT/KAPT 或 KSP | 触发后收集完整模块源码聚合生成；无注解输入不恢复旧注解身份 | 聚合索引、注册表及需要避免无效重生成的场景 |
| ButterKnife | 运行项目官方 APT/KAPT，按类型生成 binding | 当前候选加同模块受影响绑定后代；父类可从 classpath 恢复 | 按类型输出、带继承依赖的生成能力 |

需要查询继承关系不意味着需要全量源码。应分别判断处理器读取哪些信息，以及本轮必须输入哪些源文件。Gradle 的 isolating/aggregating 声明可作为线索，最终以实际生成逻辑、依赖读取和真实产物验证为准。

源码入口均位于 `main/src/main/java/com/sickworm/intellij/jugg/`：

| 入口 | 相对上述目录的路径 | 复用职责 |
|---|---|---|
| `IJuggAptProcessor` / `JuggAptCompiler` / `ProcessorRegistration` | `compiler/source/apt/` | 模块轮次接入、共享分析、生成输出校验及失败归属 |
| `AptSourceAnalysis` | `compiler/source/apt/AptSourceAnalysis.kt` | 共享文本快照、惰性 Java AST/Kotlin PSI、声明事实；改写后失效重建 |
| `AptSemanticResolver` | `compiler/source/apt/AptSemanticResolver.kt` | 按需解析常量、类型及源码关联；当前源码优先于旧 class |
| `AnnotationProcessorClasspath` | `compiler/source/apt/AnnotationProcessorClasspath.kt` | 候选命中后读取处理器 SPI，不承担检测和降级策略 |
| `AnnotationProcessorRunner` / `KspProcessorRunner` | `compiler/source/apt/`、`compiler/source/kotlin/` | 复用项目 APT/KAPT 和 KSP 执行链 |
| `GeneratedSourceCheckpoint` | `compiler/source/apt/GeneratedSourceCheckpoint.kt` | 发布完整聚合源码目录；适用于 ARouter 的完整输出 |
| 三个内置适配器及专项协作者 | `compiler/source/apt/processors/` | 注解语义、影响范围、输出命名及专项兼容 |
| `SourceCompiler` | `compiler/source/SourceCompiler.kt` | generated changed-file 登记、Kotlin/Java 编译、失败重试与 DEX 交接 |

## 3. 接入前先确定支持契约

实现前读项目实际版本的处理器源码、运行时查找逻辑及 Gradle 接线，形成以下结论并保存到 `docs/task/YYYY-MM/` 的任务方案。不能只由注解名字推断成本。

| 必须回答的问题 | 需要形成的结论 |
|---|---|
| 每个输出由哪些声明决定？ | 按类型、按模块聚合，或可基于已有入口追加；明确最小生成输入 |
| 还读取什么？ | 父类、接口、字段类型、常量、资源、其他生成物；区分源码输入和 classpath 查询 |
| 如何确定变化？ | 当前文本/语法是否足够，是否需比较语义，以及可靠基线来自哪里 |
| 新增、修改、删除、重命名各会怎样？ | 需要新增、覆盖还是移除生成类；哪些能局部完成，哪些需用户主动 Gradle |
| 如何运行？ | 项目 processor/compiler、选定引擎、必要参数、JDK/API 访问条件；确认处理器间是否有生成依赖 |
| 生成结果如何被使用？ | 直接引用、聚合入口或反射；类加载、反射缓存、进程重启和部署要求 |

方案必须同时说明正常场景保留的增量范围、失败场景如何局部收口，以及如何复用用户已有的主动 Gradle 入口。不得仅因有潜在风险就让整个模块或整个流程自动回退。

落地时以 `IJuggAptProcessor.process()` 接入模块轮次；内置适配加入 `ProcessorRegistration`，外部适配沿已有 custom compiler 装载路径接入。先确定触发与失败契约，再实现检测、必要输入准备、执行及输出发布，最后补齐真实验证。不要为新能力另建一条源码编译主链。

## 4. 快速跳过与输入扩展

新适配按以下成本顺序组织；某一层没有可靠门禁时跳过该层，不能为了省成本制造漏检：

```text
模块已有依赖/配置或生成入口信息
  -> 本轮共享文本筛选 + 必要的旧生成物关联
  -> AST/PSI 确认真实注解
  -> 按需解析常量、类型和基线；有价值时比较语义
  -> 确认变化后扩展必要输入
  -> 运行处理器或改写已有生成入口
```

- 未关联该能力的模块应尽早返回；不为探测每轮打开 processor JAR、准备 classpath 或遍历源码树。ButterKnife 使用已解析 artifact 文件名门禁，Kuikly 使用既有生成入口；门禁依据由各能力确定。
- 文本负责保守筛选，注释和字符串误命中交由语法确认过滤。支持普通/星号 import、全限定写法、Kotlin alias/use-site 中的哪些形式，必须有明确范围；跨文件 typealias 等间接表达另行评估。
- “当前文本无注解”能否返回取决于删除契约。ButterKnife 还关联旧 binding，包含 `Outer$Inner_ViewBinding`；ARouter 当前不恢复无注解输入的旧身份，删除最后注解需主动 Gradle。新适配不能把这两种策略混用。
- 共享 `AptSourceAnalysis` 的文本和树，语义解析保持惰性。其事实模型和 resolver 只覆盖已实现的语义，不能当作完整编译器符号系统；方法注解等超出事实模型的输入需显式处理。
- 先尝试当前文件 + classpath；确需追加时扩展到受影响源码闭包。只有输出确实依赖模块全集时才收集模块源码，聚合成本也必须在触发门禁之后支付。
- 不要求所有适配器都实现语义去重。ARouter 比较语义以避免聚合开销；ButterKnife 当前对带绑定文件的方法体变化也重生成，后续只有取得成本证据才优化。

## 5. 执行与产物生命周期

### 5.1 复用执行链，兼容放在调用边界

优先运行项目实际 processor，复用项目 compiler、classpath 和已解析参数，显式选择处理器，避免无意启动其他 APT。若处理器依赖其他处理器输出，应先验证最小联合执行范围或既有基线是否足够。

Java、混合 Kotlin、KSP 选择已有 runner；Kotlin stub 语义交给 KAPT。依赖 javac 内部 API 等已确认宿主冲突时，按需使用隔离 JVM，将 exports/opens 限定到该进程，不修改 IDE 或正常源码编译环境。参数按所选处理器契约传递，避免把无关参数整体灌入。

当前 KAPT 已支持显式隔离；ButterKnife 的独立 javac 仍为专项实现，尚未并入共享 runner。后续出现相同需求时可提取进程执行部分，不能把专项资源、继承或版本替换规则一并上提。

### 5.2 生成成功后仍需经过正常编译和部署

```text
适配器：临时生成 -> 校验输出及收缩 -> 发布可编译源码
  -> JuggAptCompiler：校验输出、按路径合并、失效被改写文件的分析快照
  -> SourceCompiler：登记 generated changed files，保留失败后下轮重编入口
  -> Kotlin / Java：同轮编译业务输入和生成源码
  -> D8 / minify：沿既有链路生成部署产物
  -> 部署流程：提交产物和部署状态，运行时消费生成代码
```

生成目录发布不代表语言编译成功或部署成功。语义比较基线不能仅因生成成功就提前推进，否则后续失败会让下一轮误认为无需生成。当前 ARouter 使用业务 class 基线，仍需遵循其检测失败和输入关联边界。

| 输出模式 | 发布原则 | 当前参考 |
|---|---|---|
| 完整聚合输出 | 完整生成、检查旧类集合收缩后替换目录 | ARouter、`GeneratedSourceCheckpoint` |
| 按类型局部输出 | 覆盖本轮输出，保留其他类型，检查本轮对应旧绑定 | ButterKnife |
| 已有入口追加 | 保留已有注册，只追加缺失项；说明删除/修改限制 | Kuikly |

不能对局部输出调用整目录替换；也不能对聚合输出只复制新增文件而遗漏旧类移除问题。当前这些发布实现不构成从生成到部署的统一事务，失败恢复需依靠各阶段现有契约。

适配器返回带正确 module/baseDir 的 Java/Kotlin `CompileFile`，复用现有编译链；不另建 DEX 或部署通道。追加的业务源码也必须参与失败归属，ButterKnife 的后代输入不能被“移除生成物重试”顺带丢弃。

### 5.3 资源符号与运行时发现

R/R2 支持需要同时确认处理器和正常语言编译看到的符号，以及最终绑定引用的运行时资源。ButterKnife 的 R2 为编译占位常量，新增名称需局部补齐并参与同轮编译，官方处理器再保留/转换为 R 符号；不能将占位整数当作真实资源 ID。

资源表和布局更新继续沿既有资源链。遇到找不到 View 时，先核对布局 ID、业务注解、生成绑定和部署产物是否一致；布局重命名不会自动修改业务注解。反射查找型库还需检查新增生成类后的查找缓存，ButterKnife 首次新增 binding 建议重启 App。

## 6. 失败边界与诊断

| 阶段 | 设计要求与当前差异 |
|---|---|
| 无候选 | 原源码编译继续，不打印用户可见的 processor 执行日志 |
| 检测/基线解析失败 | 明确影响输入和是否保留旧生成物；ARouter 当前 debug 后跳过本模块本轮生成，ButterKnife 异常按 failClosed 停止；不能把异常解释为“未变化” |
| 真实执行、必要输出缺失或不支持的生成类收缩 | 按能力契约停止受影响轮次，保留原始诊断；不得静默用旧生成物宣称成功 |
| 生成源码语言编译失败 | `failClosed` 适配器通过 `ownsGeneratedFile` 识别归属，阻止无生成物重试掩盖错误；普通适配器保留既有降级路径 |
| IDE 导航等辅助同步失败 | 仅舍弃辅助能力，保留有效编译输出；ARouter 的 IDE 生成目录镜像与正式编译源分离 |

先评估单输入、同模块闭包和受影响轮次的局部处理。无法满足当前增量契约时明确失败并说明主动 Gradle 的恢复边界，不默认牺牲其他未受影响文件的增量收益；重试必须改变失败条件并有次数上限。

日志使用 `JuggLogger`：无候选和检测统计用 debug/trace；实际处理器执行用 info 记录模块、开始和 finally 耗时；非预期执行错误用 warn 保留可定位诊断。共享 `APT summary` 的解析、查询计数可用于确认无关轮次是否进入昂贵阶段，不通过精确耗时断言代替稳定契约。

## 7. 验证与完成条件

按 [06_testing.md](06_testing.md) 先决定证据和测试价值，再选择 owner；下表是应评估的行为，按支持范围覆盖，不要求为每层新增测试类。

| 要证明的行为 | 推荐证据 |
|---|---|
| 未命中不影响普通编译 | 无依赖、普通注解、注释误命中场景；非法但无关文本可证明未提前解析，结合 classpath 未查询断言和普通源码编译回归 |
| 生成语义符合项目版本 | 真实 processor/compiler，检查生成物外部命名与引用，再用真实编译器验证；Kotlin alias、混合输入等按支持范围选择 |
| 输入范围足够且局部 | classpath 父类、必要后代、聚合输入、常量/资源变化及无关输入保留 |
| 跨轮和失败可恢复 | 连续两轮修改、生成后编译失败再修复、旧输出保留；删除最后注解、嵌套类和重命名按承诺验证 |
| 产物进入真实编译链 | 业务源码 + 生成源码经过 SourceCompiler 到 DEX/staging；变更编排时包含 L3 或已有等价 Flow |
| 运行时实际消费 | Demo 中调用生成入口、绑定或索引；按风险做设备 E2E，覆盖必要重启/缓存行为 |

优先复用 `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/apt/` 下的 `JuggAptCompilerTest`、`ARouterJuggAptProcessorTest`、`ButterKnifeJuggAptProcessorTest`，KSP Flow 参考 `main/src/test/java/com/sickworm/intellij/jugg/compiler/ARouterKspCompileFlowTest.kt`，主机增量链参考 `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggCompilerTest.kt`。主机 DEX/staging 成功与设备运行验证分别记录。

交付时明确：已支持操作与未支持边界、无候选成本、正常输入范围、失败收口、真实引擎/版本、执行过的验证以及未完成项。Demo testcase 变化按测试规范刷新基线。仅运行 assemble 或检查生成文件存在不足以证明旁路增量路径成立。

## 8. 后续扩展的取舍

优先复用入口、共享分析、runner、诊断和正常编译链。注解匹配、变化语义、输入闭包、输出命名/发布及删除策略由适配器负责，只有出现真实重复且契约一致时才提取。不要预建统一注解配置 DSL、全局依赖图、适配器继承层级或持久化签名数据库。

新增能力同步本页中已改变的共性判断，并更新具体机制文档。用户支持范围或操作说明变化时才同步 Wiki；本页是开发指引，不承载用户能力列表。
