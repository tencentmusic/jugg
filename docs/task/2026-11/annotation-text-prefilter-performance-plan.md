# 注解处理文本预筛选与耗时收敛方案

## 状态与交接目标

- 日期：2026-11-12。
- 已按确认方案实施文本/实际注解门禁、按需 baseline 和单行性能汇总；定向功能回归、IDE 编译与 Wiki 构建通过。MiniBarManager 单次现场复测 APT 总耗时为 5.307ms，达到本次 100ms 目标；尚未采集多轮重复样本。
- 用户明确要求：所有注解处理先通过文本或单 class 信息确认候选，再按需进入语法、语义分析；不为注解移除场景恢复旧身份。
- 本方案选择收缩同步检测范围：普通无注解输入直接退出，保留候选输入的继承语义分析；无注解类型变化对其他注解使用方的间接影响交给完整构建刷新。
- 用户提到的“0.01% / 99.99%”表达成本取舍，尚无统计数据支持该比例。

## 1. 问题与现场证据

现场工程：`/Users/wormchen/IdeaProjects/joox/JOOX_Android_3`。

日志：`/Users/wormchen/.jugg/log/JOOX_Android_3_ed5ed3d9/compile_2026-11-11_23-04-20.0.log:2223`。

本轮仅重新编译 wemusic 的 `MiniBarManager.java`，没有相关注解，声明实现了 `MediaPlayObserverListener`、`BaseLifecycleObserver`。APT 准备仍阻塞后续 Java 编译。

| 指标 | 本次日志值 |
|---|---:|
| APT 总耗时 | 762.999ms |
| ARouter 处理器 | 758.499ms |
| Kuikly 处理器 | 0.447ms |
| ARouter 父类、接口变化检查 | 612.282ms |
| class 查找累计 | 472.914ms |
| Java 解析累计，3 次 | 139.237ms |
| 源码读取累计，3 次 | 0.283ms |
| JAR 打开次数 | 2970 |
| 业务 class 读取 | 45 个，共 299042 字节 |

这些指标存在包含关系，不可相加。本轮没有触发相关类全量扫描，主要成本发生在“确认直接父类、接口是否变化”之前及过程中。

当前 `ARouterJuggAptProcessor.inputsChanged()` 的顺序为：

1. `baseline.classes(file)`：先解析源码声明并关联旧业务 class。
2. `semantics.parentsChanged(file, old)`：解析父类、接口的全限定类型。
3. 关系变化时调用 `baseline.relatedClasses(old)`。
4. 最后才调用 `analysis.mentions(file, PACKAGE)` 做文本筛选。

`AptSemanticResolver.qualified()` 即使命中显式 import，仍调用 `resolvedType()`；后者查源码、查 class，class 查找又逐个打开 classpath JAR。能力上需要识别继承影响，并不要求每个输入都承担这套查找。

## 2. 目标与明确边界

### 2.1 性能目标

- 对 MiniBarManager 这类无相关注解信号的普通源码修改，将本轮 APT 检查总耗时降到 **100ms 以内**。
- 目标覆盖同一轮 ARouter、Kuikly 的检查及编排，不是整个编译部署流程，也不是实际执行聚合注解处理器的耗时。
- 无候选路径不创建完整语法树，不关联旧注解身份，不查询父类、接口、DB 或 classpath JAR，不扫描注解使用方。
- 先以现场复测验证收益，不预先承诺具体优化后数值。

### 2.2 保留的能力

| 场景 | 处理方式 |
|---|---|
| 当前文件命中受支持的注解文本信号 | 进入语法分析，确认实际注解，再进行需要的语义比较 |
| 只有 import、注释或字符串命中文本信号，实际无相关注解 | 允许一次语法确认后退出，不为恢复旧注解身份继续深挖 |
| 当前文件实际有 ARouter 注解 | 保留注解参数、字段类型及相关继承语义比较 |
| 本轮候选 `@Route` 类改变父类、Provider 接口等 | 在该候选的语义比较中判断是否需要重新生成 |
| `@Autowired` 字段或其宿主的语义需要解析继承链 | 按现有语义实现按需解析 |
| Kuikly `@Page` | 保留路由值、页面类名提取与现有注册追加逻辑 |
| 确认 ARouter 输入变化，需要聚合生成 | 保留模块完整源码输入，包括无注解依赖源码 |

Kuikly 当前注册代码只使用路由字符串和页面类名，不需要为页面注册另行检测父类、接口变化。

### 2.3 接受的支持范围收缩

- 不为当前没有相关注解信号的文件恢复旧注解身份，不保证删除最后一个注解后自动刷新生成物。
- 不为无注解父类、接口或字段类型的修改同步追踪其他注解使用方。例如无注解 `Model` 新增 `Serializable`，不会仅因此主动找到其他文件的 `@Autowired Model` 并触发聚合。
- 不再由独立 `parentsChanged()` 检查驱动跨文件继承影响追踪。仍可为本轮实际候选的语义比较读取父类、接口和字段类型定义。
- 当前没有可靠跟编机制，不声称其他链路会自动补齐上述场景；这些变化需要完整 Gradle 构建刷新生成物。
- 某次其他候选变化触发完整聚合时，相关间接变化可能一并刷新，但不将此作为支持保证。

这是明确的性能与支持范围取舍，实施说明中须写明这些边界。

## 3. 最小实现路径

### 3.1 把文本筛选放到所有重查询之前

在 ARouter 对每个本轮输入执行检查的最前面调用现有文本候选判断。未命中立即退出，不能先调用 `baseline.classes()`、`facts()`、`parentsChanged()` 或 resolver。

文本门禁须早于 `ARouterBaseline` 构造：其 `roots` 初始化会遍历 class 输出路径，执行目录检查与 canonical path 去重。先确认当前实际注解候选，再按需创建 baseline、调用 resolver；resolver 本身只创建本轮内存容器，classpath 按需读取，无候选轮次不承担路径准备与查询成本。

优先复用现有 `analysis.mentions(file, ARouterInputSemantics.PACKAGE)` 粗筛；Kuikly 复用现有 `@Page` 文本筛选。保留现有支持写法的命中能力，不为本次优化引入完整词法分析器、跨轮缓存或新的配置。

文本命中可以有假阳性；只有文本信号的输入在语法确认没有实际相关注解后退出。实施时检查普通 import、全限定注解、星号 import 和当前已支持的 Kotlin alias 写法，避免缩小已有的直接注解识别范围。

语法确认复用 `analysis.facts()`，只检查声明与字段的注解名称、import、星号 import 和 alias 归属，不解析注解参数或字段类型。确认实际候选后才调用 `semantics.current()`，不能用该语义入口替代轻量语法确认。

该门禁由内置 ARouter、Kuikly 各自实现；不得在 `JuggAptCompiler` 总入口用这两类注解信号过滤外部 `IJuggAptProcessor` 的输入。

### 3.2 移除同步间接影响入口

- 从本轮输入比较路径移除无条件 `parentsChanged()` 及由其驱动的 `relatedClasses()` 调用。
- 对实际候选继续比较当前注解语义与旧业务 class 语义；不增加另一套独立父类变化比较。
- 检查仅服务上述入口的方法和参数；确认无其他调用者后局部删除，不开展无关重构。
- 生成物收缩时的现有 fail-closed 检查保留：不主动支持移除，不意味着实际触发聚合后可以发布不完整生成物。

### 3.3 保留聚合生成与现有错误边界

仅收缩“什么输入能触发检测与生成”。已触发后的完整聚合、JSR 269/KAPT/KSP 选择、checkpoint 发布、生成源码编译与失败处理继续复用现有流程。

不得把模块聚合输入过滤成只有注解的文件，否则混合 Java/Kotlin、typealias 和未编译依赖可能无法解析。

### 3.4 DB 查询优化不作为本次前置工作

`IDeployDataDatabase.getClassNodes()` 已提供按名称批量查询、增量部署优先、内存缓存和 SQLite 索引，适合后续优化真正命中候选后的类型查询。

本次先让无候选路径完全退出，不增加 DB 检查。候选路径若仍有已测得的明显瓶颈，再单独评估：

- DB 提供已知类型及历史父类、接口；本轮新源码语义仍需优先。
- DB 缺失的 SDK、新增类型等需要既有源码/classpath 查找。
- DB 不保存当前 ARouter 比较所需的注解内容，不能整体替代业务 class 基线。
- 已部署状态与已编译业务 class 状态可能不同，不应顺带改变现有基线生命周期。

### 3.5 单行 debug summary

- 每模块每轮只增加一条 `APT summary:` debug 汇总日志，不逐文件、逐类型或逐 JAR 打印性能明细。
- 同一行保留总耗时、各处理器耗时、嵌套阶段耗时及扫描/查询计数；`inclusiveMs` 明确表示父阶段包含子阶段，不可相加。
- 本要求针对性能诊断日志；保留已有必要的生成结果与异常日志。

## 4. 验证计划

### 4.1 已有回归 owner 与测试价值

- `ARouterJuggAptProcessorTest`：直接注解变化、普通方法体修改、聚合产物与混合源码编译。
- `JuggAptCompilerTest`：Kuikly 注册追加、幂等、无 `@Page` 输入及处理器协作。
- 实施前核对涉及的现有测试；与新支持边界冲突的跨文件继承和最后注解移除测试，应明确调整契约或移除过时断言，不伪装成原行为等价。
- 定位时特别检查 `relatedParentTypeChangesRegenerateTheRouteKind`、`removingLastDeclarationDoesNotSkipGradleBaselineDeletionCheck` 的实际输入和责任边界，不能仅按名称决定去留。
- 不新增读取源码字符串锁定调用顺序的测试，不为 mock 增加生产代码 seam，不用易抖动的单测耗时断言证明性能。
- 生成产物正确性用已有有价值断言保护；快速路径成本通过已有 debug 计时和真实工程复测证明。

### 4.2 功能与流程矩阵

1. 无注解普通 Java/Kotlin 输入：普通编译继续成功，APT 不生成新产物。
2. 注解文本假阳性：语法确认无实际注解后退出，不恢复旧身份。
3. 本轮直接候选修改 `@Route` 参数、Provider 接口、`@Autowired` 字段：生成结果与新语义一致。
4. 保留现有无关方法体修改不运行实际 APT、Kuikly 幂等及路由新增回归。
5. 选择已有混合 Java/Kotlin 编译到 DEX 的等价 Flow 回归；影响编译编排时必须提供 L3 或已有等价 Flow 证据。不能把已忽略的历史 KSP Flow 计为通过。
6. 完整构建验证接受边界的恢复方式：无注解类型间接变化、最后注解移除后，完整构建能够刷新相应生成物。

定向运行选定测试，禁止无 `--tests` 过滤的全量 `:main:test` / `:idea:test`。编译检查按改动范围选择，必要时执行 `./gradlew :idea:compileKotlin`。

### 4.3 性能验收

- 复用 MiniBarManager 的普通方法体修改场景，保留优化前现场日志。
- 记录插件加载后首次触发及后续重复触发，建议各保留一次首次样本及至少 5 次后续样本；列出每次总耗时，不用平均数隐藏超标。
- 验收无候选轮次 APT 总耗时低于 100ms，且不发生 ARouter 的完整语法分析、class 关联、类型查询及 JAR 打开。
- 若首次样本仍超标，区分插件初始化、文件 I/O 和本方案检查成本，如实记录，不能仅凭后续缓存样本宣称全部达标。
- 保留现有 debug 计时；若只能证明没有调用实际 APT，不能据此证明昂贵准备阶段已消除。

## 5. 文档同步与实施约束

- 更新 `02_compile_custom_ui.md` §4.3，移除“所有父类变化均同步追踪已知注解使用方”的现行描述，写明文本门禁与完整构建边界。
- 检查 `02_compile_source.md` 的引用描述，以及 Wiki 中直接承诺 ARouter 支持范围或完整构建恢复条件的页面；只更新确实失准的内容，实际修改 Wiki 时按 `10_wiki_authoring.md` 同步中英文。
- 历史任务方案保留历史语义，不批量改写；本文记录新选择。
- 实施基于交接时已有的 5 个 Kotlin 日志改动，已保留计时与计数并统一成每模块每轮一行 summary；现场验证后用户已授权提交本次实现、测试与相关文档。
- 不新增后台任务、依赖图、身份缓存、DB schema、开关或通用框架。

## 6. 实施与验证记录

- `ARouterInputSemantics.hasSupportedAnnotation()` 先文本筛选，再只确认声明/字段的实际注解；与语义分析共用现有名称归属规则，避免 import、注释等假阳性触发 baseline 查询。
- `prepare()` 延迟构造 `ARouterBaseline`，仅实际候选进入 `inputsChanged()`；已移除独立 `parentsChanged()` 与 `relatedClasses()` 反向影响追踪及其专用辅助方法。聚合仍传入完整模块源码，生成类收缩检查保持原流程。
- `JuggAptCompiler` 的 `APT summary:` 包含模块、输入语言数量、总耗时、处理器耗时、inclusive 阶段计时与查询计数；未新增逐文件/JAR 性能日志。
- 修改前定向红测试已复现：删除最后一个注解会抛 `JuggAptFatalException`，与新契约不符。已有回归 owner 保留，补充 import 假阳性，以及真实 processor 完整重编刷新旧索引的断言；不增加内部调用顺序断言或耗时阈值单测。
- 首轮扩大回归发现 Android demo 夹具在 Java 21 下由旧版 D8 抛 NPE；已取消重复失败并切换本机 Java 17 进行相同定向回归，不改动 Android 工程或测试基础设施。
- 已同步 `02_compile_custom_ui.md` §4.3，以及 Wiki 注解处理器页面的中英文支持边界；`02_compile_source.md` 的引用保持有效，概念页面无需变更。
- Java 17 + Android SDK 30 下，`ARouterJuggAptProcessorTest` 为 75 项通过、2 项因未设置 `JUGG_AROUTER_FIXTURE` 跳过；`JuggAptCompilerTest` 11 项全部通过。包含真实 APT/KAPT/KSP、混合 Java/Kotlin 到 DEX 的流程，以及完整 processor 重编后的注解删除/父类变化恢复断言。跳过的两项定制 processor 用例不计为通过。
- 验证命令：`JAVA_HOME=/Users/wormchen/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home ANDROID_HOME=/Users/wormchen/Library/Android/sdk ./gradlew -Dorg.gradle.java.home=/Users/wormchen/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home :main:test --tests '*ARouterJuggAptProcessorTest' --tests '*JuggAptCompilerTest' :idea:compileKotlin --console=plain`，结果成功，日志 `/tmp/apt-prefilter-check-jdk17.log`。
- `docs/wiki` 下 `npm run build` 与 `git diff --check` 通过；中英文修改段落位置、链接与边界一致。新增/修改日志已检查字符串 `+` 换行与续行缩进。
- 测试夹具的 6 条普通无注解输入 summary 总耗时依次为 0.684、0.190、0.230、0.162、0.522、0.238ms，均仅有 `sourceReadCalls=1`，无语法解析、class 或 JAR 查询。

### 6.1 MiniBarManager 现场复测

日志：`/Users/wormchen/.jugg/log/JOOX_Android_3_ed5ed3d9/compile_2026-11-12_00-52-38.0.log:4016`，任务时间为 2026-11-12 00:57:15，输入仅 `wemusic/MiniBarManager.java`。

| 指标 | 优化前 | 本次复测 |
|---|---:|---:|
| APT 总耗时 | 762.999ms | 5.307ms |
| ARouter 处理器 | 758.499ms | 3.339ms |
| Java 语法解析次数 | 3 | 0 |
| 业务 class 读取数 | 45 | 0 |
| JAR 打开次数 | 2970 | 0 |

本轮 summary 只有 `sourceReadCalls=1`，源码读取 0.124ms，未进入 baseline 或类型查询，APT 输出为空；后续 Java 编译和 DEX 均成功。整体增量编译 2304ms，其中 Java 1234ms、DEX 1056ms；ConstRef 分析 289ms 与 Java 编译重叠。本次没有连接设备，未执行部署。

该现场样本满足快速路径与 100ms 目标。当前日志只有一次对应任务，尚不能提供首次与至少五次重复触发的分布。

依据文档：[项目概览](../../ai_knowledge/00_overview.md)、[检索入口](../../ai_knowledge/99_index.md)、[代码地图](../../ai_knowledge/98_code_map.md)、[源码编译](../../ai_knowledge/02_compile_source.md)、[注解处理接线与边界](../../ai_knowledge/02_compile_custom_ui.md)、[部署数据库与影响分析](../../ai_knowledge/03_deploy_data_generator.md)、[测试与验证规范](../../ai_knowledge/06_testing.md)。
