# ARouter 当前轮检测与处理器能力层重构方案

## 状态与范围

2026-10-11 已按用户明确指令实施本方案。用户随后明确取消本次改动的旧二进制兼容要求；下文相关要求已按澄清调整，首次执行结果保留为历史，收敛结果见文末。评审对象为 `befdd38f`，原方案见 [ARouter 内置增量处理器改造计划](arouter_generic_custom_compiler_plan.md)。原方案保留为该提交的历史记录。

用户已确认的方向：

1. 变更检测只处理当前编译轮次的文件，不每轮扫描整个模块。
2. 常量变化复用工程已有的 ConstRefEngine 跟编机制，参考 Kuikly 的接入方式，不由 ARouter 再维护常量影响分析。
3. 把通用执行、读取和检查点职责收敛到能力层；ARouter processor 保留业务决策，提高内聚性。
4. 只有影响注解生成的语义信息实际改变才执行 APT；相关文件只修改方法体、注释或格式时必须跳过。不能以“本轮包含相关源码”或“进入常量跟编”替代差异判断。
5. 确认需要生成后允许收集整个模块的源码，这是 ARouter 聚合生成的输入要求。
6. 首次没有 Jugg 输入记录时，从与当前基线对应的已有字节码或 generated source 恢复旧信息，不要求先跑一次 APT 建立记录。
7. 变化判断或基线恢复因信息缺失、无法解析等原因不能可靠完成时，按 Best-effort 记录 `warn`，跳过当前注解器在本模块本轮的生成，继续其他注解器和正常源码编译，不因此要求完整 Gradle 构建或中止整轮。

下文细化这些方向，列明行为变化、复用边界和实施门禁。新增名称为建议命名，不要求通过增加类的数量来满足方案。

## 现有事实与问题

### 全模块检测成本

`ARouterJuggAptProcessor.processModule()` 忽略当前输入范围，先遍历 `module.sourceDirs`。无 ARouter 的模块也会读取全部源码；有 ARouter 时，声明、常量、继承分析合计读取每个文件四遍，之后才检查生成缓存。

2026-10-11 在 Apple M3 Pro、Corretto 17.0.17、本地磁盘上，通过公开 `process()` 入口测量；预热 JVM 与文件缓存后，单文件采样 60 次，模块场景采样 25 次。测量包含源码读取和检测，不包含 APT 生成、fixture 创建和 Gradle 启动。

| 输入 | P95 |
|---|---:|
| 单个无 ARouter 引用的 10KB 合成源码 | 1.01ms |
| 单个无 ARouter 引用的 100KB 合成源码 | 3.03ms |
| 单个无 ARouter 引用的 1MB 合成源码 | 16.81ms |
| 单个无 ARouter 引用的真实 5KB Kotlin 源码 | 0.52ms |
| 1000 个约 10KB 文件、无 ARouter、只改变一个文件 | 205.37ms |
| 有 ARouter 且生成缓存命中、另有 1000 个约 10KB 普通文件 | 794.75ms |

同环境下，仅做“读取 + 包名文本筛选”的对照，100KB / 1MB 文件的 P95 分别为 0.18ms / 0.79ms。它说明快速筛选的成本，不代表已实现的优化结果，也不是冷启动或任意机器的耗时保证。

本机会话证据：`/tmp/jugg-arouter-detection-benchmark/results.txt`、`benchmark.xml`、`ARouterDetectionBenchmark.kt`。这些是临时调查产物，不是仓库内的永久测试或运行依赖。

### 声明签名存在正确性缺口

原实现只记录与 ARouter 注解名称相关的 import，常量和父类主要按简单名称关联。真实 ARouter 1.5.2 探针复现：

- 保持 `@Route(path = Paths.PATH)` 不变，只切换 `first.Paths` / `second.Paths` 的 import，错误复用旧路由。
- 保持 `@Autowired public Date value` 不变，只切换 `java.util.Date` / `java.sql.Date` 的 import，错误复用旧类型的注入代码。

清除检查点后，两种情况都生成正确结果。既有 ARouter / JuggApt 36 项测试通过，说明现有断言未覆盖这些缺口。本机会话证据：`/tmp/jugg-arouter-simplification-review/probe.xml`。

### 已有能力可以承接职责

- `JavaCompilerInvoker` 已有 APT-only、processor path、APT options、依赖和诊断处理；DataBinding 正在使用。缺少显式 processor 名单和独立生成源码输出目录，且已有成功后复制产物的行为，复用前必须界定 APT 输出语义。
- `KotlinCompilerInvoker` 已支持 KAPT `stubs` 模式。ARouter 无需独立构建 Kotlin 编译器调用实现。
- `DataBindingClasspathHelper` 已读取 JSR 269 processor service，适合与 ARouter 共享 SPI 读取能力。
- `ConstRefEngine` 经 `DeployFileManager` / `DeployDataGenerator` 将受影响源码纳入跟编，`IncrementalCompilerHelper` 再把它们送入后续编译轮次。
- Kuikly 消费本轮输入；其基类另用 `ConstRefAnalyzer` 和已有 class 查找能力解析注解常量值。影响传播与注解值解析是两个职责。ARouter 最终仍调用真实 APT，但在触发前比较语义信息也需要解析有关常量；应复用已有能力，不能因为最终使用 APT 就省略这一步。

### Kuikly 与旧产物提供的参考证据

- `KuiklyPageJuggAptProcessor.rewriteEntryFile()` 从 generated entry 提取已有 `route + pageFqcn`，只追加缺失项；方法体变化不会改变这个集合，也就不会改写入口。它目前没有删除旧注册的逻辑，不能据此推断 ARouter 的删除和替换场景已经有现成实现。
- 本地 `arouter-annotation:1.0.6` 源码中的 `Route`、`Autowired`、`Interceptor` 均使用 `RetentionPolicy.CLASS`。对现有 Demo 的 `JavaARouterActivity.class` 执行 `javap -v`，可以读取 `RuntimeInvisibleAnnotations` 中的 Route 路径。这证明旧 class 可以提供旧注解事实；不代表任意 fork、DEX 或混淆产物都保留同样信息。
- Demo 的 generated source 可以读取 `RouteMeta.build(...)`、拦截器 `put(priority, class)` 和注入字段赋值。实物 YB 输出比标准模板多了 `RouteMeta.build` 参数，因此不能把某一模板的位置参数正则当作通用解析契约。
- 现有 `ClassNode` 只保留结构、source 和 generic signature 等信息，不保留完整 ARouter 注解值；APK 数据库查询不能直接替代读取原始 class 或生成物。`ClassFileLookupHelper` 可按类名查找 class，但不保证找到的磁盘文件就是已成功部署的版本。

## 当前轮检测与生成触发

### 检测入口

只检查 `allCompileFiles` 中属于当前模块的有效 Java/Kotlin 源码，包括用户直接修改的文件和已有影响分析送来的跟编文件。排除本处理器自己的生成源码，避免生成物再次触发生成。

单文件通过本轮共享源码视图读取一次内容，按各处理器关注的注解包等必要文本信号做廉价候选筛选。还须核对该文件是否有旧注解身份，防止最后一个注解和 import 被删除后漏检；首次身份从既有产物恢复。确认新旧都无相关信息后结束检测，不启动完整语言语义分析、processor JAR 扫描、Fragment 查询或完整生成缓存校验。身份查询与必要的轻量声明定位也计入快速路径耗时，不能把这部分成本排除在验收之外。

文本筛选只判断“是否值得继续检查”，不能直接决定执行 APT。候选中的真实注解、alias、全限定名、同包使用等复用现有语言解析能力。共享层负责读取和语法事实，ARouter 专属比较负责判断这些事实是否影响生成；不再维护全模块的正则常量图和继承图。

已完成上一轮生成产物消费的无变化轮次返回空生成列表，不让后续语言阶段重复编译和部署索引。须区分“是否重新执行 APT”和“是否仍需编译/部署已生成源码”：上次生成成功但后续编译或部署失败时，沿用已有重试与产物跟踪流程补齐，不能因为语义相同就丢弃待完成产物，也不应因此重跑 APT。

### 按当前文件比较影响生成的语义信息

比较单位是当前文件贡献给 ARouter 的语义信息，与对应旧产物或有效生成记录中的信息；不是整份源码文本，也不是所有 import 的摘要。字段列表由真实项目 processor 的读取行为验证，以下是已知需求的最小核查范围，不宣称覆盖任意 processor fork：

| 对象 | 比较信息 | 明确不作为独立触发条件 |
|---|---|---|
| Route | 目标类身份、实际注解参数值与默认值、影响路由分类/Provider 注册的类型关系，以及实际进入路由参数表的字段信息 | 普通方法体、无关成员、注释、格式 |
| Autowired | 所属类和字段身份、解析后的字段类型及有关泛型、注解参数，以及决定注入方式的宿主类型信息 | 相同类型的等价源码写法、无关 import 变化 |
| Interceptor | 目标类身份、优先级和实际影响生成的其他参数/类型约束 | 拦截方法的实现体 |

只提取 processor 实际读取的部分；例如仅修改用于说明的注解参数是否改变生成内容，需要检查真实处理器及其选项，不能把全部注解文本直接拼成签名。比较结果包括新增、修改、删除、无变化，以及无法判断。只有确认影响生成时进入完整聚合；无法判断时按下述 Best-effort 边界记录原因并跳过，不能伪装成“无变化”，也不能默认当作“有变化”运行 APT。

同值写法归一化：字面量与同值常量、隐式与显式默认值、import/alias 与全限定名写法，在语义相同时不触发；import 改绑导致实际值或字段类型改变时必须触发。引用目标改变但最终值/类型及生成行为不变时不触发。类型关系按处理器实际需要投影，例如相关祖先改变后仍归为相同路由类型，不能仅凭祖先名字变化判断必须生成。

**尚需取证的实现点：**当前 AST/PSI 共享只能省去读取和建树，不能自动提供完整常量求值与类型解析。Kuikly 字符串 resolver 也不等于完整 Java/Kotlin 注解求值器。实施前须用真实 Route/Autowired/Interceptor 输入验证最小可复用的语义入口，尤其是跨文件常量表达式、Kotlin 注解落点、泛型与父类型解析；不能通过另写一套正则分析器或候选即 APT 绕过。暂不改变现有预处理位于语言编译前的顺序，不未经评估引入一轮额外编译来取得当前字节码。

### 常量与类型影响传播

删除 ARouter 自有的 `constantFacts()`、`hierarchyFacts()` 及其签名推导。ARouter 不直接创建 ConstRefEngine、不再扫模块建立另一套依赖图，也不增加“源码是否曾经引用路由”的全局数据库。

常量文件进入首轮编译时，不因该文件本身没有 ARouter 引用而启动路由生成；常量影响分析把注解使用方送入跟编后，重新取得使用方的有关语义值并与基线比较，确实改变才触发生成。常量在普通方法中的使用变化不应触发 APT。父类和其他类型的影响继续遵循已有结构影响传播，不能声称 ConstRefEngine 同时负责所有类型变化。

跟编文件的文本可能未变化，跨轮共享旧 AST 或仅比较注解表达式文本不足以判定。保留 ConstRefEngine 的后台预分析和缓存；每轮按有效输入版本重新解析被引用值，不能把旧 class 中尚未更新的常量当作当前值。仅变更普通方法体的路由文件，即使连续修改 100 次，也必须始终跳过 APT。

### 聚合生成输入

检测输入与生成输入分开：检测只看本轮文件；真正触发生成后，ARouter 的聚合处理器仍须获得完整的有效输入，保留未修改的路由、拦截器和注入辅助类。

允许在这一生成阶段按需收集模块 Java 源码和必要 Kotlin stubs。不得把模块收集提前到普通文件的检测路径，也不能只把改变的路由文件交给聚合 APT 后覆盖整张索引。

Kotlin stub 输入须覆盖生成所需的注解源码及本轮必要 Kotlin 输入；其他已编译依赖沿用项目 classpath。验证同模块常量变化、Java/Kotlin 混用和未修改 Kotlin 路由，避免只验证单文件 stub。

### 删除与基线边界

保留 Gradle 生成源码基线、历史生成文件清单和生成类收缩保护。生成完成后与有效旧清单比较，删除生成类仍要求完整 Gradle 构建；失败不得发布新检查点。

检查点只保存参与生成的输入路径、类身份与最小语义摘要，绑定该次有效生成物和 Gradle/variant 基线；不保存完整输入语义副本。它只是已有生成记录的内容，不承担新的依赖图职责。完整 Gradle 基线刷新时废弃不适用的旧记录；旧检查点读取应保留可恢复的生成文件和基线信息。

首次无记录时，按当前文件关联的旧类或 generated source 恢复旧信息，比较新旧集合：旧有新无表示删除，旧无新有表示新增，两边都有则比较语义内容。优先利用原始 class 的注解、字段和类型信息；generated source 可提供注册身份或其他缺失信息，但必须识别实际模板，不能从无法识别的文件得出“该类无旧记录”。不为此扫描模块源码。若需要为共享聚合生成物建立查找表，仅对有效产物版本按需建立并复用，首次读取成本须纳入性能证据。

必须区分生成基线、编译产物和已部署基线。磁盘 class 可能来自未部署或失败任务，不能因为它最近修改就认定是有效旧值；部署查询也不能退回初始 APK 而忽略已成功部署的 overlay。使用同一版本的旧注解值、默认值、类型信息和生成物，不能混拼版本。后续失败的 pending 产物由已有跟踪流程处理，不作为已完成部署的证据。

**实施前仍需验证：**从源码路径确定旧类的现有映射（包括同文件多类、嵌套类、类改名/移动），完整 Gradle 刷新后的对应关系，以及缺 class、缺 generated source、混淆裁剪和 fork 模板时的信息完整性。`CLASS` 保留策略证明原始 class 可读，不证明 APK/Dex 中同样可恢复。确实无法恢复时按已确认的 Best-effort 策略局部跳过，不能执行一次 APT 建基线或扩大到全模块源码扫描。

纯文件删除能否进入本轮仍受现有上游变更发现约束，本次不扩大删除事件入口。

### 变化判断的 Best-effort 边界

旧产物缺失、模板不支持、语义求值或读取失败等导致无法可靠判断时，使用 `JuggLogger.warn` 记录处理器、模块、相关文件、失败原因及“本轮跳过生成”；有异常时保留原异常。由当前注解器边界收口，不将这类检测失败升级为 `JuggAptFatalException`，继续其他处理器和正常 Kotlin/Java 编译。

ARouter 是模块聚合生成：若所需输入中一部分无法判断，应跳过本模块本轮 ARouter 生成，不能只用已解析部分覆盖完整索引。保留旧生成物、旧有效检查点及既有待完成产物跟踪；不发布部分产物、不保存“已确认无变化”状态，不把失败固化到跨轮缓存。后续轮次仍可重新判断。

这个取舍意味着本轮无法保证受影响注解生成物已更新；warn 必须明确这一结果，而正常源码编译是否成功仍由语言编译器决定。无需新增自动重试、自动完整构建或用户确认流程。

本次降级针对触发判断与基线恢复；确认变化后真实 APT 执行失败、生成类收缩和生成源码编译错误仍沿用既有失败边界，不把所有阶段异常统一吞掉。

## 能力层与业务层分工

### ARouter 与 Kuikly 共享本轮源码分析

进一步把“共用解析工具”收敛为“共用解析结果”。如果每个 processor 各自创建 scanner，即使使用同一个工具类，仍可能重复读取文件、创建 PSI/AST 和遍历注解，不能达到本方案的复用目标。

建议由 `JuggAptCompiler.doModuleCompile()` 为当前模块、当前编译轮次创建一个具体的 `AptSourceAnalysis` 对象，交给 ARouter、Kuikly 以及后续使用此能力的内置处理器。不按 processor 分别创建，也不以 `JuggAptCompiler` 实例生命周期长期持有源码结果。

| 共享层次 | 延迟生成的数据 | 规则 |
|---|---|---|
| 文件内容 | 当前输入文件的文本快照 | 按 canonical path 去重，需要时读取一次；不同 processor 使用同一份文本 |
| 候选筛选 | 包名、import 等廉价查询结果 | 无候选不创建语法树；相同查询复用结果，不把预筛当作完整符号解析 |
| 语言语法树 | JavaParser AST 或 Kotlin PSI | 只有业务实际请求时才解析；同一内容版本、同一种前端至多解析一次 |
| 公共语法事实 | import/alias 信息、注解节点及所属声明等实际共享信息 | 需要时从同一语法树提取一次，按注解筛选给不同业务；不建设覆盖全部语言语义的中间表示 |
| 按需常量值解析 | Kuikly 生成及 ARouter 语义比较实际需要的常量定义和解析结果 | 命中相关注解后才请求；沿用现有常量解析职责，优先从已有语法树提取定义，避免再读文件、再建树；现有能力未覆盖的表达式明确识别 |

按需常量值解析与 ConstRefEngine 的影响传播保持分离。ARouter 生成仍由真实 APT 完成，触发比较复用通用求值能力，ARouter 不复制 Kuikly 的字符串解析实现，也不强迫二者使用只适合字符串的业务接口。现有 parser 如果只能接收文件路径，实施时核对能否以小型、明确的入口复用已经解析的语法树；不能只共享一个外层对象，内部又调用 `parseDefinitions(files)` 重复解析。

源码语法树对 processor 为只读。路由声明如何影响生成、Page 如何改写注册、是否失败、如何处理旧生成物，仍由各 processor 决定。共享层不返回 ARouter route 或 Kuikly page 业务实体。

生命周期与范围约束：

- 普通路径只接收本轮 `task.files`，不为填充共享视图预扫模块。ARouter 真正进入聚合生成后，可按需取得额外输入的共享读取/解析结果；新增范围不能倒灌成普通轮次的预加载。
- 跟编、重试和下一轮重新创建视图，避免旧内容和旧失败结果跨轮次残留。共享缓存不增加数据库、mtime 校验服务或全局 AST 缓存。
- 同轮解析失败应保留失败原因，不返回伪造的空注解列表；内置处理器的触发判断按上述 Best-effort 边界记录 warn 并局部跳过，不影响其他有效消费者。公共层不把共享分析失败自动升级为整个编译任务失败，也不改写外部处理器的既有契约。
- 已解析的输入保持只读快照；Kuikly 的 generated entry 改写仍由业务处理，不混入这份输入语法树。若现有扩展会原地改写输入，兼容接线必须显式处理这条边界，不让后续消费者误用旧快照。

按用户澄清，`IJuggAptProcessor.process()` 直接接收第四个 `AptSourceAnalysis` 参数，内置与外部处理器统一通过接口消费同一轮视图；删除基类桥接和三参数入口。外部 custom compiler 接入继续保留，源码适配后重新编译，不支持未重新编译的旧扩展 JAR。不通过全局单例、ThreadLocal 或可变 `ICompileContext` 字段传递本轮数据。`JavaCompilerInvoker.Options` 的显式 processor 名单和生成目录使用主构造器 `val`，不保留旧构造或 copy 的 ABI；DataBinding 正常功能仍须回归。

Kuikly 当前的 `collectPageRegistrations()` 会先调用 `createStringConstReferenceResolver()`，该方法通过 `ConstRefAnalyzer.parseDefinitions()` 解析本轮源码，然后才筛选 Page import。这个顺序必须调整为：既有生成入口存在 → 候选筛选 → 从共享视图获取 Page 注解 → 按需要解析常量值 → 改写生成文件。没有 Page 候选时，不应创建常量解析器；无常量参数时也不应提前进行全批定义分析。

该工厂每次新建 analyzer，同步解析后释放；resolver 内部的求值和 class 查询缓存不覆盖这次预解析。这里的按需调整不取消 ConstRefEngine 独立的异步预分析和缓存。

预期消费关系：

```text
本轮编译文件
  → AptSourceAnalysis：文本一次读取，语法树与公共事实按需一次生成
      → ARouter：提取 Route / Autowired / Interceptor 有关语义，与旧产物比较后决定是否调用 APT
      → Kuikly：筛选 Page，按需要求常量值并改写注册入口
      → 后续 processor：查询已有事实，执行自己的生成策略
```

这消除的是 APT 预处理内部重复的读盘、建树和公共事实提取。不同 processor 的条件判断、业务遍历和生成仍有各自成本，不承诺新增 processor 完全零开销。不同包名的廉价文本筛选也可能增加成本，须在多个 processor 的组合场景测量；没有性能证据时，不引入自动机或通用查询引擎。

JavaParser AST 与 Kotlin PSI 是两种语言的解析结果；共享的是访问与生命周期，不能强行变成同一种树。ConstRefEngine 后台分析和 javac/KAPT 的真实编译有各自上下文，不在本次共享范围内，不能宣称整个编译过程每个文件只会被解析一次。

### 其他职责边界

| 职责 / 建议命名 | 能力层承担 | ARouter 保留 | 复用或变更落点 |
|---|---|---|---|
| 本轮共享源码分析 / `AptSourceAnalysis` | 当前输入的一次读取、廉价筛选、按需共享语言语法树与公共事实；需要时复用常量定义提取 | 注解包、注解集合、何时触发聚合生成 | 由 `JuggAptCompiler` 按模块/轮次创建；ARouter 与 Kuikly 共用，不复制 Kotlin PSI 环境 |
| SPI 读取 / `AnnotationProcessorClasspath` | JSR 269 service 读取、注释和空行处理、去重；必要的 JAR entry 查询 | ARouter 包筛选、候选唯一性和选定处理器要求 | 从 ARouter 与 DataBinding 提取共同读取部分，保留各自失败策略 |
| 混合源码生成 / `AnnotationProcessorRunner` | KAPT stubs → 显式 APT；工作目录、输入拼装、产物收集、执行失败 | 哪些源码、哪些 processor、项目选项和业务日志 | 组合既有 Java/Kotlin invoker；不再直接写第二套 javac 调用 |
| 生成记录 / `GeneratedSourceCheckpoint` | 基线标识、输入路径、生成清单、产物发布；已有格式兼容读取 | 基线生成物识别、类删除是否允许、failClosed 归属 | 提取当前文件操作；不建设通用缓存服务或新增数据库 |
| ARouter 差异判断 | 公共层复用文本/语法树、按需求值、class 查找读取和基线记录 | 选择影响生成的字段、解释 ARouter 生成物、比较新增/变更/删除 | ARouter 专属能力，主流程调用；不建设任意 processor 的语义推断框架 |
| ARouter 兼容 / `ARouterProcessorCompatibility` | 专属辅助实现：processor 发现策略、Fragment 引用、APK 类型选择、Jetifier 及其缓存、路径替换 | processor 主流程只调用已准备好的运行环境 | 保留为 ARouter 业务层内部能力，不宣称是所有 APT 的通用兼容规则 |

`JavaCompilerInvoker` 按实际需要补充显式 processor 名单、生成源码输出目录，并明确 APT-only 的产物返回与复制语义。必须保护 DataBinding 当前行为，不能仅把原调用替换过去就认为完成复用。

KAPT 接线继续复用 `KotlinCompilerInvoker`；混合源码的编排可放在一个小型 runner 中，不为了对称再增加一套 Kotlin 编译器抽象。Kuikly 的源码改写不是 JSR 269 执行，不强迫它经过该 runner。

Jetifier 的转换执行天然可复用，但当前业务策略专属于 ARouter。先通过专属辅助实现隔离；没有第二个调用需求时，不增加通用转换注册表、策略接口或回调框架。

公共能力不得包含 `AROUTER_MODULE_NAME`、Fragment 目标选择、旧 custom compiler 避让、ARouter 生成文件名等业务条件。新增 helper 必须有独立、明确的职责；不为每个 helper 建单实现接口，不增加 processor 基类模板和大量 hook。

重构后的 processor 主流程应直接表达以下决策：

```text
旧外部扩展避让
  → 当前轮文件的有关语义与对应旧产物是否有变化
  → 准备完整聚合输入及有效生成基线
  → 发现并适配项目处理器
  → 调用公共 KAPT / APT 能力
  → 校验生成类删除、发布生成记录、返回源码
```

## 后续实施顺序

1. 先完成当前语义提取、旧 class/generated source 基线和首次删除身份的可行性验证，验证无法判断时 warn 并局部跳过；确认不借助全模块扫描或无差别 APT。核对官方与已有项目 fork 的实际输入契约。
2. 固化行为证据：方法体连续修改不运行 APT、同值写法不运行 APT、首次无记录时可比较、两类 import 变化、常量跟编时源码文本不变。保留真实 APT 生成、Java/Kotlin 聚合、Jetifier 正反转换和 failClosed 回归。
3. 提取 SPI 与生成记录，隔离 ARouter 兼容策略；各步保持现有生成语义，不同时修改无关编译流程。
4. 扩展既有 Java invoker 的最小 APT 能力，建立混合源码 runner，验证 DataBinding 原路径。
5. 建立按轮次共享的源码分析视图，让 ARouter 与 Kuikly 共用读取、语法树和公共事实；将同步常量求值改为按需执行。按当前文件的有关语义差异触发，替换 ARouter 不完整的声明签名及全模块常量/继承分析；验证普通文件快速路径与现有影响传播。
6. 运行定向回归、编译和 Flow，复测性能，再更新知识库与 Wiki 镜像。仅在用户明确要求实施后执行这些步骤。

## 验收与测试价值

验证 owner 以现有 `ARouterJuggAptProcessorTest`、`JuggAptCompilerTest`、ConstRef 和源码编译 Flow 为主。保护生成结果、跟编结果和失败边界，不按新增 helper 数量创建测试，不反射私有函数，不为测试增加生产 seam。

| 场景 | 需要证明的结果 |
|---|---|
| 模块没有 ARouter，仅改变一个普通文件 | 仅检查本轮文件，返回空生成列表；模块文件数增加不会让检测退化为全模块扫描 |
| 模块有路由和历史生成物，仅改变普通文件 | 不启动路由生成，不把历史路由索引作为本轮新产物返回 |
| 路由文件只改普通方法体、注释、格式或无关成员，连续 100 次 | APT 执行次数为 0；不能用最终产物相同代替“未执行”的证据 |
| 注解字面量改为同值常量、显式默认值、等价 import/alias 写法 | 语义不变，不执行 APT；改变实际值、字段类型或生成所需类型关系时才执行 |
| 仅普通方法引用的常量改变，路由文件被送入跟编 | 注解生成语义不变，不执行 APT |
| 同一 Kotlin 文件同时包含 Route 和 Page，两个内置处理器都启用 | 两种生成行为都正确；同一内容版本只读取一次、使用同一份 Kotlin PSI 和公共注解事实 |
| 本轮只有普通文件，存在 Kuikly generated entry 但无 Page 候选 | Kuikly 不请求 PSI 或常量定义解析器，不因启用多个处理器而重复读文件；其他消费者的必要旧身份检查单独计量 |
| Page 使用字面量 / 引用常量 | 前者不提前分析全批常量；后者保持正确求值并复用已有源码解析结果 |
| 下一轮改变内容、跟编、重试或同轮解析失败 | 不复用跨轮次旧快照；内置变化判断失败仅 warn 并局部跳过，不扩散到其他有效消费者 |
| 保留外部 IJuggAptProcessor 接入 | 按统一四参数 SPI 重新编译的扩展仍被发现、执行，内置 ARouter 仍避让同 id 扩展 |
| 常量文件修改，路由使用方未修改 | 经真实 ConstRef 影响查询和跟编到达 ARouter，生成新路由；不被未变源码签名吞掉 |
| import 改变常量绑定或 Autowired 类型绑定 | 生成新路由或新字段类型的注入代码 |
| 改变一个 Java/Kotlin 路由 | 同组未修改路由仍存在；其他聚合索引不丢失 |
| 已知输入删除注解与 import | 检测到生成物变化；收缩时保留完整构建要求 |
| 首次使用、无输入记录，分别只改方法体 / 删除最后一个注解及 import | 从有效旧 class/generated source 恢复信息；前者不运行 APT，后者识别删除，不能只覆盖已有检查点 |
| 同文件多类、嵌套类、类改名/移动 | 旧身份映射完整，不以当前类名查不到旧产物就认定从无注册 |
| 生成成功后编译或部署失败，再次执行 | 不重复 APT；仍补齐有效生成物的待完成编译/部署，不混淆生成完成与部署完成 |
| 原始 class 缺失、产物被裁剪、未知生成模板或语义不完整且无可用恢复信息 | warn 说明原因并跳过该模块本轮注解器生成；其他注解器和正常源码编译继续，不因该检测失败中止或要求完整构建 |
| 聚合输入部分无法判断，下一轮信息恢复 | 本轮不发布部分索引、不推进检查点；下一轮重新判断并正常生成，不受旧失败缓存影响 |
| Gradle 基线刷新、旧格式检查点 | 不误用旧清单，不无必要地删除所有历史状态 |
| 显式 processor 集合 | 不启动同一 classpath 上无关框架的处理器 |
| DataBinding APT/KAPT、Kuikly fail-open、ARouter 实际生成阶段 failClosed | 区分前置检测的 Best-effort 与实际生成失败；原有生成和语言编译错误归属保持正确 |
| Fragment 单侧/歧义、Jetifier 正反转换 | 保留原兼容策略和选定 JAR 的一致替换 |

性能验收沿用相同硬件、JDK、样本和采样方法：无 ARouter 引用且非已知历史输入的单文件快速检测，以 1KB、10KB、100KB、1MB 样本的预热 P95 < 10ms 为目标，分别报告冷启动、最大值和模块规模对照。不能把这个目标扩展成 APT 生成也低于 10ms，不能用总耗时除以文件数量代替单文件测量。

另测“有路由但只改方法体”的完整判断耗时，包括语义提取和旧产物查询，并验证 100 次改动不触发 APT；共享解析性能达标不能替代这一验收。首次从旧产物恢复身份/语义与后续复用分别报告，不把一次性基线恢复成本藏到计时外。

共享能力另测启用 1、2、5、10 个消费者时的文件读取、语言解析次数和总耗时，区分“完全不相关”“仅一个处理器相关”“同文件供多个处理器使用”。读取与同前端建树次数不应随消费者数线性增长；注解筛选与业务执行成本单独报告。通过真实产物断言保护行为，以 profiler 或临时计数探针验证共享收益，不为永久测试在生产代码注入计数回调或工厂。

本次不为时间阈值新增容易受 CI 负载影响的永久单测；后续用可重复性能探针保存前后结果。功能断言覆盖“无关变更不产生索引”，性能证据另行报告。

涉及源码编译编排，实施后必须包含 L3 或等价 Flow：完整基线 → 只修改路由常量 → 实际跟编 → 索引语言编译和 D8 → 部署/路由解析。单独调用 processor 或只验证 ConstRef 返回了路径不能替代完整链路。

## 文档同步与本轮交付

实施完成后同步 `02_compile_custom_ui.md`、`02_compile_source.md`、`98_code_map.md`；必要时在 `03_deploy_const_ref.md` 说明新增的消费方，不改写 ConstRef 的原职责。检查 Wiki 中英文 custom compiler / annotation processor 页面，将旧声明签名和全模块检测说明更新为当前文件的语义比较，保留方法体变化跳过 APT 的承诺，并说明首次基线恢复的实际支持边界及检测失败时 warn、局部跳过的行为。

方案评审阶段只维护本文件；本次用户授权后的实现、验证与文档同步记录如下。原方案及用户交换文件保持原状。

依据：`00_overview.md`、`99_index.md`、`98_code_map.md`、`02_compile_custom_ui.md`、`02_compile_source.md`、`02_compile_core.md`、`03_deploy_const_ref.md`、`03_deploy_data_generator.md` §4.2、`06_testing.md`、原 ARouter 方案，以及上述处理器、编译器、ConstRef/Kuikly、ClassNode、ClassFileLookupHelper 实现。本次补充的产物证据来自本机 `arouter-annotation:1.0.6` sources JAR 和 `/Users/wormchen/IdeaProjects/demo/aigc-bot-client/build/app/` 中现有 class / KAPT generated source；只读核对，未修改 Demo。


## 首次执行结果（2026-10-11，兼容条款已被后续澄清取代）

### 实际改动与边界

- 内置 ARouter 先比较当前文件语义，确认差异后才收集模块源码。普通、方法体和等价值轮次返回空生成列表；去掉原有模块正则常量/继承图。常量使用方通过既有 ConstRef 查询结果进入跟编，再读取当前源码常量值。
- 新增按轮次的共享文本、Java AST/Kotlin PSI、公共事实及按需常量定义提取。ARouter 与 Kuikly 共用同一实例，改写文件后失效对应快照；保留外部三参数 SPI。新增四参数基类方法默认转调旧入口，真实编译的旧扩展二进制加载验证通过。Java invoker 原 Options 构造和 data-class 方法保持兼容。
- 共用 SPI 读取、KAPT stubs/APT runner 和生成记录；Fragment/Jetifier 策略留在 ARouter 专属辅助实现。DataBinding 未使用独立生成目录时保留原返回/复制行为。混合 Java/Kotlin 生成验证包含未修改 Java 路由及同一 Kotlin 文件的 Route/Page 两种生成行为。
- 输入记录绑定 Gradle/variant、生成物内容和处理器环境；源码移动按文件名/声明身份恢复，歧义时 warn。旧格式清单继续参与收缩保护。首次以原始 class 的 SourceFile/JVM 身份和已识别的标准生成模板恢复并核对 Route 路径、group、priority、extras、类型、Provider/Interceptor 注册、参数表及注入模板；支持同文件多类、嵌套类和已识别的 String/引用类型注入。
- 检测、恢复、聚合分析失败 warn 并局部跳过，不写部分索引/新记录，下一轮重新判断。真实生成失败、生成类收缩和生成 Java 直接语言错误仍 failClosed。生成后语言失败留下真实 changed-file 清单，修复字段后重试生成 Java/D8 成功且真实 APT init 计数不变。
- 求值器为本次已有需求提供已验证的标量表达式和类型投影，不是完整 Java/Kotlin 编译器语义分析。复杂 Kotlin 表达式、无法按名定位的跨文件顶层常量、泛型 Provider、未知 fork 模板或未识别的注入形式局部跳过；首次无记录且文档生成输入无法证明，或相关模块/依赖存在 class/DEX overlay（含未知模块身份）时，不能把 Gradle 磁盘 class 当成已部署旧值。纯删除发现入口保持原有约束。这些属于方案要求的明确“无法判断”结果，未用一次 APT 或模块源码扫描建立基线。

### 验证与测试价值

新增断言位于既有 ARouter/JuggApt owner，保护实际生成内容、首次恢复、传播结果、旧扩展加载和失败边界；未按 helper 数量建测试，未新增生产 mock seam。性能探针和计数只在临时文件/临时源码插桩中使用，已经移除生产插桩。

失败证据为原实现两项真实 ARouter 1.5.2 import 改绑回归：常量绑定变化复用了旧路径，Autowired 类型绑定变化复用了旧类型。修复后均通过。100 次真实方法体修改、同值拼接常量、显式默认值和文档选项关闭时说明参数变化，均未新增真实 RouteProcessor init 调用；实际路由值变化增加一次 init。

等价 Flow 使用真实 javac/JSR 269 完整 class/生成源码基线、真实 ConstRefEngine/SQLite 指纹索引、只改常量、由查询结果构造跟编输入、SourceCompiler 的索引 Java 编译和 D8，再通过隔离 ClassLoader 调用真实路由组 loadInto。旧 `/demo/first` 更新为 `/demo/changed`，路由源码文本保持不变。该 Flow 验证路由解析，未替代或声称执行 Android 设备部署。

运行环境为 Apple M3 Pro、Corretto 17.0.17、Android SDK platform 30；默认 JDK 21 的 JVMTI 构建限制与缺少 ANDROID_HOME 属环境前置问题，改用上述环境验证。定向验证命令及最终结果在交付前记录；不执行无过滤全量 main/idea:test。

### 性能复测

同一硬件/JDK，沿用原临时公开 process 入口样本和 60 次单文件、25 次模块采样，15/5 次预热。包含当前文件读取、身份查询和判断，排除 fixture/APT 生成/Gradle 启动。以下首次/新实例数据不是操作系统冷磁盘保证；临时共享计数插桩只增加计数，不改变解析逻辑。

| 样本 | 原预热 P95 | 当前预热 P95 | 当前最大值 | 当前新实例单次 |
|---|---:|---:|---:|---:|
| 无路由 1KB | 0.50ms | 0.52ms | 0.60ms | 1.03ms |
| 无路由 10KB | 1.01ms | 0.39ms | 0.45ms | 2.57ms |
| 无路由 100KB | 3.03ms | 0.87ms | 1.10ms | 1.07ms |
| 无路由 1MB | 16.81ms | 2.73ms | 3.69ms | 1.56ms |
| 真实 Kotlin 5KB | 0.52ms | 0.18ms | 0.19ms | — |
| 无路由 100 文件，只改一个 10KB 文件 | 19.88ms | 0.18ms | 0.19ms | — |
| 无路由 1000 文件，只改一个 10KB 文件 | 205.37ms | 0.18ms | 0.20ms | — |
| 有路由记录，100 普通文件，只改一个 | 86.71ms | 0.49ms | 0.76ms | — |
| 有路由记录，1000 普通文件，只改一个 | 794.75ms | 0.43ms | 0.58ms | — |
| 有路由记录，当前为路由文件的无语义变化判断 | 16.98ms | 12.28ms | 12.99ms | — |

公开入口首次调用 1KB 为 9.47ms。首次真实原始 class/生成索引恢复为 13.51ms；复用该基线后路由无语义变化判断 P95 4.41ms、最大 4.96ms。全部普通单文件样本均达到预热 P95 < 10ms。路由相关判断包含处理器环境指纹和生成内容校验，单独报告，未将其混入普通路径或 APT 耗时目标。

共享探针调用真实 AptSourceAnalysis 公共入口，分别测 Java/Kotlin、完全不相关/仅一消费者相关/所有消费者相关；每轮独立新建视图。消费者数 1/2/5/10 时，三种场景均只读文件一次；不相关场景 parse/facts/definitions 均为零，有需求的场景三者均为一次。实际两内置处理器的行为由混合生成回归保护，额外 5/10 消费者用于共享入口扩展成本测量。

临时证据：`/tmp/jugg-arouter-detection-benchmark/after-results.txt`、`after-benchmark.xml`、`after-run.log`、探针 Kotlin/init script；原对照见本方案前文。性能数字不作为易受负载影响的永久单测阈值。


共享入口查询总耗时（预热 P95，60 次；不包含各业务生成或真实 javac/KAPT 前端耗时）：

| 语言 / 场景 | 1 消费者 | 2 消费者 | 5 消费者 | 10 消费者 |
|---|---:|---:|---:|---:|
| Java / 完全不相关 | 0.033ms | 0.047ms | 0.074ms | 0.135ms |
| Java / 仅一消费者相关 | 0.315ms | 0.328ms | 0.359ms | 0.390ms |
| Java / 全部相关 | 0.269ms | 0.342ms | 0.544ms | 0.485ms |
| Kotlin / 完全不相关 | 0.032ms | 0.044ms | 0.085ms | 0.141ms |
| Kotlin / 仅一消费者相关 | 0.575ms | 0.367ms | 0.494ms | 0.399ms |
| Kotlin / 全部相关 | 0.272ms | 0.272ms | 0.342ms | 0.473ms |

公共缓存查询/候选筛选随消费者数增长仍有成本；语法树和事实提取次数保持一次。上述共享入口数据没有外推为整个编译只解析一次，真实业务判断另见路由无变化行，真实 APT 生成不包含在性能目标内。

补充失败证据：最终审查发现同文件其他类的常量会被按文件名限定的源码查找遗漏，旧编译产物运行新增断言失败（`/tmp/jugg-arouter-same-file-before.log`）。修复为当前共享声明定位，以及按 class 的 SourceFile 精确定位额外文件，不扫描模块源码。丢失 SourceFile 的旧注解身份明确 warn，不能把删除判为“从无旧注册”。


缺 generated source 的现有注解 class 也以真实基线复现了误启动 APT（`/tmp/jugg-arouter-missing-generated-before.log`）；现在明确 warn 并跳过，保留“信息不足”状态，不用 APT 建新记录。

### 最终定向验证命令

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/Users/wormchen/Library/Android/sdk \
./gradlew :main:test \
  --tests '*ARouterJuggAptProcessorTest' \
  --tests '*JuggAptCompilerTest' \
  --tests '*JavaConstParserTest' \
  --tests '*KotlinConstParserTest' \
  --tests '*SourceCompileDataBindingTest' :idea:compileKotlin

JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/Users/wormchen/Library/Android/sdk \
./gradlew :idea:test \
  --tests '*JuggCompileForDataBindingTest.testDataBindingWithMixedJavaKotlinSourceChanges' \
  --tests '*JuggCompileForDataBindingTest.testDataBindingAlwaysUsesApt'

python3 .agents/skills/wiki-writer/scripts/validate_wiki.py --wiki-root docs/wiki
(cd docs/wiki && npm run build)
git diff --check
```

最终 main 定向 75 项（ARouter 41、JuggApt 12、JavaConst 7、KotlinConst 8、DataBinding 7）全部通过；idea 混合 DataBinding 两项通过，`:idea:compileKotlin` 通过。真实常量 Flow、连续 100 次零新增 APT、pending 语言失败重试、二进制旧扩展及缺失/不一致基线均包含在上述 owner 中。日志为 `/tmp/jugg-arouter-final-main-validation.log` 和 `/tmp/jugg-arouter-final-validation.log`。

Wiki 路由、语言镜像、相对链接及新正文的 production HTML 均核对，production build 通过；`git diff --check` 通过。无发布或推送操作。

本次实际读取依据：`docs/ai_knowledge/00_overview.md`、`99_index.md`、`98_code_map.md`、`02_compile_custom_ui.md`、`02_compile_source.md`、`02_compile_core.md`、`03_deploy_const_ref.md`、`06_testing.md`、`10_wiki_authoring.md`、`10_wiki_architecture.md`；本方案；`docs/wiki/zh/capabilities/compile/index.md`、`docs/wiki/zh/concepts/incremental-compile/index.md`、其 `custom-compiler.md` 配对概念页、`docs/wiki/{zh/,}capabilities/compile/{custom-compiler,annotation-processors}.md`；以及相关 ARouter/JuggApt/ConstRef/Java-Kotlin invoker 实现、原始 class/JSR 269 产物和以上失败/性能日志。


## 用户澄清后的收敛（2026-10-11）

### 单一源码契约

- Options 的 `processorNames`、`generatedSourceDir` 改为主构造器 `val`，分别默认空名单和 null；Runner 直接构造完整配置。删除类体可变属性、事后赋值及保留旧构造/copy ABI 的接线。
- `IJuggAptProcessor.process(context, module, files, analysis)` 是唯一入口；删除 Base 桥接、内置三参数入口和编译器按基类/注册来源分派的分支。重新编译的外部 custom compiler 仍通过原发现流程接入，同 id ARouter 的内置避让仍保留。
- 删除仅验证未重新编译的旧扩展 JAR 的测试。原有共享分析、生成、失败边界、常量 Flow、pending 产物跟踪和 DataBinding 回归保留；不为 Kotlin data class 的 copy/equals/toString 再增加字段镜像测试，使用定向编译核对当前契约。
- Kuikly 继续仅追加缺失注册，本次没有增加替换或删除业务。

### 逐项评估 inputs.json

旧 v2 的四个顶层字段是 version、baseline、environment、sources；sources 的值是每个源码的完整语义投影。核对依据为 `ARouterInputSemantics`、`ARouterBaseline` 的真实输出验证，以及真实 ARouter 1.5.2 生成/语言失败回归。

| 原记录信息 | 从已有产物能恢复什么 / 缺少什么 | 本次保留范围与理由 |
|---|---|---|
| version | 生成源码不携带 Jugg 记录格式版本 | 保留 3，明确区分 v2 的完整语义与 v3 的身份/摘要；这是磁盘读取兼容，和旧二进制无关 |
| baseline | 当前 Gradle 产物可以计算当前摘要，但最新 Jugg 生成源码不能说明它基于哪一份 Gradle 产物生成 | 从 inputs.json 删除，仅在 checkpoint 签名保存一次；用于废弃不适用的记录、清单并重新恢复基线 |
| environment：processor JAR 内容、选项、runtime 依赖与 Jetifier 版本 | 生成 Java 无法证明是哪份 JAR/配置生成；不同环境也可能暂时输出相同 Java | 只保留一个 SHA-256，不保存 JAR 或选项副本；相关轮次环境变化仍触发 APT，包括同大小/同时间戳 JAR 替换 |
| sources 的 canonical path | class 的 SourceFile 通常只有文件名；索引是聚合输出，生成 Java 不记录贡献源码绝对路径；APT 后语言失败时 class 还可能属于旧轮次 | 保留 path→最小输入记录，用于归属、最后注解删除以及移动后的关联；模块和 variant 由既有目录区分，不另存副本 |
| 源码中的 JVM 声明身份 | 标准索引/辅助类可以暴露已注册类，但不能完整恢复每个声明来自哪个源码，也不能依赖尚未更新的 class 来补齐多类/嵌套类关系 | 每个源码仅保留去重后的类身份列表；删除字段/注解等具体变化由摘要比较，身份用于跨路径匹配 |
| Route、Provider、Interceptor 投影值 | 标准索引可以读取路由目标、path/group、路由类型、priority/extras、Provider key、Interceptor priority 及参数表 | 不再逐项持久化这些值，不维护第二份注册集合 |
| Autowired 字段、泛型、required、宿主类型及路由参数信息 | 已识别辅助模板可暴露字段/key、getter、宿主分支、部分 cast/TypeWrapper 和引用类型 null check；不能无损重建全部字段 descriptor/signature，例如 primitive/boxed 的同一种 getter，以及路由参数表只保留 TypeKind 和有效 key，不能恢复完整原输入 | 不逐项存完整参数，仅纳入同一个源码语义摘要 |
| 文档开关影响的 name/desc 等输入 | Java 索引不保留完整文档输入；首次恢复原本就拒绝无法证明的文档输入，不能用当前注解补造旧值 | 不逐项存完整参数，仅纳入同一个源码语义摘要 |
| sources 的完整语义 Map | 最新生成物提供输出证据，但不足以无损重建现有语义比较的全部输入；原 class 又不能在 APT 成功、语言失败后补齐新一轮的缺失字段 | **删除完整 Map，每个源码改为一个 SHA-256**。只需判定新旧投影是否相同，无需持久化可逆语义；保持原比较 owner，避免再增加逆向解析器和“可恢复字段 + 残缺字段”双套状态 |

Autowired 行的结论同上：其完整 descriptor/signature、注解 Map、宿主及字段类型投影均不持久化，只参与该源码的归一化语义摘要。摘要覆盖整个比较投影，而不是建立可以读取旧值的副本；这是对完整比较缺口的固定大小补充，不声称所有旧输入都无法从生成物恢复。

`GeneratedSourceCheckpoint` 保留有限的文件职责：暂存发布生成源码、生成文件清单、记录绑定和内容校验；没有新增缓存框架、配置或数据库。保留清单用于生成类收缩保护，保留内容摘要用于判断记录是否确实对应这轮产物。把这些职责直接内联到业务处理器仍需同样的有效性检查，不能减少必要状态。本次删除的是完整输入副本以及 checkpoint 签名中重复的环境摘要，不强制删除已有的小型文件 helper。

### 最新有效生成物的选择与失败边界

1. 首次没有 Jugg 生成记录，沿用原始 Gradle class/生成源码的共同验证，不能用 APT 建空基线。
2. 后续以最新 Jugg 生成源码和绑定的最小记录比较，不再恢复原 Gradle 注解值。有效记录覆盖完整聚合输入；其中没有的新源码视为空旧贡献，不能为它单独退回旧 class，已有 overlay 也不改变这个判断。
3. checkpoint 的 baseline 与当前 Gradle 生成摘要不同，才废弃旧记录/清单并重新恢复；不依赖语言 class 或 overlay 的修改时间来判断是否同轮。
4. 同一基线下记录丢失、未知格式、记录签名不匹配或生成源码内容不匹配，warn 并跳过当前模块本轮 ARouter；不发布记录、不执行 APT、不静默使用更早的 Gradle 输入。恢复信息后下一轮重新判断。
5. v2 输入记录仍先按原签名校验，再确定性转换为身份与摘要供本轮使用，无变化不为迁移重跑 APT；实际生成后写 v3。现代记录的 checkpoint 签名包含输入记录绑定，v3 使用固定 inputs 标记，不重复存环境摘要；记录丢失时必须跳过，即使生成 Java 恰好与 Gradle 相同。更早的三段签名仅清单格式，只在生成源码完整且与原 Gradle 源码逐字节相同时允许沿原 class 恢复，否则局部跳过。旧格式清单继续用于收缩保护。
6. APT 成功后即发布对应记录；语言编译或部署完成由已有 changed-file 跟踪负责。这是生成比较基线，不是已经部署成功的证明；新源码与旧 class 不会混拼为比较输入。

### 定向验证

改动前 ARouter/JuggApt/DataBinding owner 定向通过。新增失败证据：保留 Gradle `/demo/first` 基线，Jugg 生成 `/demo/latest` 后删除最新输入记录，原实现退回旧 Gradle 输入并再次 APT，断言失败；证据 `/tmp/jugg-arouter-latest-baseline-before.log`。现实现保持最新产物并局部跳过。最终审查补充了“生成 Java 恰好回到 Gradle 同值，但现代输入记录丢失”的失败证据（`/tmp/jugg-arouter-missing-modern-before.log`）；签名区分现代记录与旧纯清单，防止用旧 class 补造不可恢复信息。

新增回归仍放在 ARouter owner（L1/L2）：最新产物有效但语言 class 缺失，跨实例无变化不 APT；新增路由且 overlay 模块身份未知时仍按完整最新记录生成；v2 磁盘记录无 class 的无变化/实际变化；损坏记录与生成源码的局部收口及恢复。既有真实 ConstRef→SourceCompiler→Java/D8→loadInto Flow 是本次编排回归，未声称执行设备部署。

本机被 Git 忽略的 `idea/src/test/java/local/idea/LocalTest.kt` 有一处 Kuikly 三参数直接调用，已适配新签名以完成 idea 测试编译；它不纳入提交。被忽略的历史 custom adapter 源码未修改或发布，当前仓库外扩展需适配接口后重新编译。

最终定向覆盖合计 main 79 项（ARouter 46、JuggApt 11、JavaConst 7、KotlinConst 8、DataBinding 7）及 idea DataBinding 两项，均通过。先运行全部五个 main owner 与 `:idea:compileKotlin`；最终签名标记修正后仅重跑受影响的 ARouter 46 项和编译，不重复执行未改变的其他 owner。真实常量 Java/D8/loadInto Flow、100 次零 APT、完整 Java/Kotlin 聚合、failClosed、pending 重试及五项新基线回归均包含在上述验证中。

最终批次命令：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/Users/wormchen/Library/Android/sdk \
./gradlew :main:test --tests '*ARouterJuggAptProcessorTest' :idea:compileKotlin
```

其他 owner 和 idea 定向过滤命令同首次执行记录，不执行无过滤全量测试。日志：`/tmp/jugg-arouter-simplify-final-main.log`、`/tmp/jugg-arouter-simplify-final-baseline.log`、`/tmp/jugg-arouter-simplify-final-idea.log`。当前编译产物通过 javap 核对两个配置字段均为 final，构造/copy 包含完整十项配置，接口只声明四参数 process；这是当前 API 编译核对，没有恢复或新增旧 ABI 测试。

知识库、方案和 Wiki 中英文同步；Wiki production build、链接/语言镜像及新正文 HTML 校验通过，`git diff --check` 通过。Wiki 日志 `/tmp/jugg-arouter-simplify-wiki.log`。首次执行的性能数字属于前文指定版本，本次没有将它们作为收敛后的重新测量结果。

本轮读取依据：`00_overview.md`、`99_index.md`、`98_code_map.md`、`02_compile_custom_ui.md`、`02_compile_source.md`、`06_testing.md`、`10_wiki_authoring.md`、`10_wiki_architecture.md`；本方案、wiki-writer skill；Wiki 的编译能力索引、增量编译概念索引、自定义编译器概念页及 capability 两页中英文；上述接口、Options、处理器、Runner、原始基线恢复、检查点、现有测试与真实生成产物。
