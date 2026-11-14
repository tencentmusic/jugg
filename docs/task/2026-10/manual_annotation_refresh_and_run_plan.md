# 手动刷新注解生成结果并运行：讨论记录与实施方案

创建日期：2026-10-15。

状态：产品方向与主要边界已确认；执行后端、产物接入和性能收益待验证。本轮仅保存讨论与方案，不实现按钮、不修改编译部署行为。

## 1. 最终结论

暂不开发通用的自动注解变化检测或自动注解处理触发。优先验证并设计一个手动入口：**用户主动刷新所有变更模块的注解生成结果，刷新成功后由代码自动编排一次运行，编译并部署刷新后待编译的文件。**

这次操作不再根据源码是否含某种注解、注解参数是否变化来决定要不要执行处理器。目标模块确定后，按照项目实际配置执行其注解处理流程，并提供完整有效的模块输入。

两个前提贯穿整个方案：

1. 普通增量运行的性能几乎不变。新增的处理器运行、模块源码收集和产物比较主要发生在用户主动点击之后。
2. 不引入难以理解的生效差异。执行范围和失败原因必须明确，不能只执行某些处理器或只消费部分产物后仍显示整体刷新成功。

**最终交互不是“刷新完等待用户再点 Run”。** 本会话最后一次调整已将其改为“刷新成功 → 自动编译 → 自动部署”。按钮命名、状态展示和验收均以这个最终行为为准。

## 2. 背景与已核对事实

### 2.1 ARouter 支持提供的基础

近期相关提交：

| 提交 | 与本讨论的关系 |
| --- | --- |
| `68fd3bc53` | 内置 ARouter 生成，不再要求独立 custom compiler 配置 |
| `9f9ae5617` | 比较注解绑定及相关输入，处理索引滞后问题 |
| `35b2eb535` | 比较定制注解输入 |
| `8364043c9` | Kotlin typealias 输入兼容 |
| `7c4216582` | 修复长编译输出正则解析拖慢 KAPT |
| `06c8ec724` | 减少 ARouter 准备阶段重复类型查询 |
| `96969e61a` | 支持项目 ARouter KSP1/KSP2 生成 |

当前 ARouter 在语言编译前比较本轮源码与已有业务 class，比较对象不只是注解文本，还包括声明身份、字段类型、相关父类/接口等输入。确认需要生成后，收集完整模块源码，执行项目配置的处理器，发布生成源码，再进入既有 Kotlin/Java 编译链。

现有基础包括：

- `AptSourceAnalysis`：按模块轮次共享源码文本、Java AST、Kotlin PSI 和局部语义事实。
- `AnnotationProcessorRunner`：纯 Java 使用 javac APT，混合 Java/Kotlin 使用 KAPT。
- `KspProcessorRunner`：调用项目 KSP1 或 KSP2 engine，输出到本轮工作目录。
- `GeneratedSourceCheckpoint`：成功生成后替换生成源码目录。
- `SourceCompiler.prepareSourceCompile()`：将生成源码显式登记为待编译输入，在语言编译之前接入。

这些是可复用实现，不代表任意处理器或任意 Gradle 项目已经被完整支持。

### 2.2 KAPT 耗时认识的修正

早期没有选择“命中后直接运行处理器”，主要顾虑是 KAPT 耗时。后续定位到编译输出解析中的未锚定正则，对超长 classpath 行产生高成本回溯。

已有 [KAPT 长日志解析性能修复记录](./kapt_compiler_output_regex_performance_fix.md) 保存了证据：原现场 ARouter 阶段 151.40 秒；受控重放中旧解析器整体 KAPT invocation 105.646 秒，修复后的三次为 5.676、2.598、2.472 秒，生成的 7 个 Java 文件内容一致。

这些数字是已有任务的重放记录，不是本轮新增测量，也不能直接等同于 IDE 完整编译部署耗时。用户另确认当前几秒耗时总体可接受。因此，“主动执行处理器”值得重新评估；仍需验证完整模块、全部配置处理器以及 Gradle 任务依赖下的总成本。

## 3. 讨论过程与方案取舍

### 3.1 方向一：通用识别注解并精确比较变化

最初目标是推广 ARouter 的两阶段思路：

1. 先从源码判断是否需要进行比较。
2. 再用源码/字节码确认相关注解是否真实变化。
3. 未支持的处理器一旦存在相关变化，直接降级 Gradle。
4. 尽量不增加额外性能损耗；考虑将确认阶段放到 `DeployDataGenerator`。

分析得到的限制：

- APT/KAPT 使用的 JSR 269 提供 `getSupportedAnnotationTypes()`，但允许包通配符和 `*`，也可能依赖处理器初始化环境；声明的匹配范围不等于完整输入依赖。
- KSP/KSP2 的 `SymbolProcessor` 没有统一的受支持注解清单。处理器可以按字符串查询注解，也可以遍历文件、声明和类型。
- 注解未改变不等于生成结果不变。例如带序列化注解的类新增字段，或者路由类的类型关系变化，仍可能需要重新生成。
- SOURCE retention 的注解使用不保留到业务 class；完整比较需要与有效构建基线对应的源码信息。
- 新旧值比较还涉及默认属性、常量、数组、嵌套注解、声明位置、Kotlin use-site target 等，不能用文本比较完整替代。
- 普通语言编译会覆盖部分业务 class，晚比较需要提前保留旧输入，且不能把失败轮中已更新的 class 误认成有效处理器基线。

`DeployDataGenerator` 当前读取 changed DEX 并与部署数据库中的结构比较。`ClassNode` 没有保存通用注解信息，`DexFileNodeCollector` 丢弃普通方法注解和参数注解。它不是可直接复用的通用注解比较器。若以后重新研究自动检测，D8 前的原始 class 分析比最终 DEX 更适合，但仍不能解决上述全部限制。

结论：可以做有明确边界的检测，但无法同时承诺任意处理器覆盖、精确触发和零额外开销。本方向暂缓。

### 3.2 方向二：源码含相关注解就运行处理器

在 KAPT 性能问题修复后，讨论改为不再判断注解值是否变化，只要修改的源码含相关注解就执行真实处理器；同时曾提出增加开关允许用户关闭自动触发。

用户明确收窄范围：

1. 不考虑删除，包括删除注解、删除文件等删除语义。
2. 修改无注解父类、被引用类型或常量所产生的影响，作为已知缺陷，后续再考虑。
3. 接受触发文件和生成输入分开：实际生成通常提供完整模块源码。
4. 继续评估扫描 JAR 中的注解类型是否可替代 KSP 的注解声明接口。

这里已经允许多执行一次处理器，避免 Jugg 自行预测处理器全部输入语义。但仍不能可靠决定哪些注解属于哪些处理器。

### 3.3 扫描 JAR 注解声明的覆盖边界

Java `@interface` 和 Kotlin `annotation class` 编译后可以通过 class header 的 `ACC_ANNOTATION` 标记识别，无须实例化类型。SOURCE retention 不妨碍扫描注解类型自身的定义；它影响的是注解在被标注业务 class 上是否保留。

| 扫描范围 | 结论 |
| --- | --- |
| 仅 processor JAR | 注解经常定义在独立 annotation/API JAR，容易漏掉 |
| processor classpath 及其传递依赖 | 覆盖更多，但处理器可能只使用字符串查询业务 classpath 或项目源码中的注解 |
| 业务有效编译 classpath、目录输出及源码注解定义 | 候选更完整，但包含运行时反射、编译器插件、IDE 检查等与该处理器无关的注解 |

扫描能回答“这是一个注解类型”，不能回答“这个处理器消费它”。`Retention`、`Target`、包名或 JAR 名也不是可靠的处理器归属契约。静态字符串扫描和一次动态查询记录同样不能证明完整覆盖。

因此扫描可以用于候选过滤，但不是精确替代。作为更简单的粗筛，还讨论过“模块配置了处理器，且修改源码中出现任意真实注解使用，就执行处理器”。它避免 JAR 扫描与名称解析，却会因 `@Override`、`@Deprecated` 等普通注解多次触发，不能保证用户原有增量耗时。

当前 `AptSourceAnalysis.Facts` 主要提取类和字段，不能直接当作所有注解位置的检测结果；若未来重启此方向，应覆盖方法、参数、类型、构造参数和 Kotlin use-site target，不能用字符 `@` 判断。

### 3.4 从自动触发转为主动刷新

用户再次强调“性能几乎不变”和“不引入用户困惑”。自动方案处于可能有些情况生效、有些不生效，同时又可能额外增加几秒耗时的位置，未必比维持现状更好。

最终选择手动入口，原因是：

- 用户明确决定何时支付注解处理成本。
- 普通 Run 不新增通用注解扫描或执行。
- 点击后不依赖注解识别准确度，直接处理目标模块的项目配置。
- 可以清楚表达处理范围、执行结果和下一阶段状态。

早期“自动触发开关”随自动方向一起暂缓，手动方案不因此新增一个全局开关。现有 ARouter、DataBinding 和 custom compiler 行为不在本任务中删除或统一重构。

### 3.5 最终调整：刷新后自动运行

最初手动建议是刷新后将产物交给下次 Run。用户最终明确改为：**刷新完成后由代码编排一次运行，编译部署刷新后待编译的文件。**

这个调整覆盖早期“等待用户再运行”的提议。整体动作需要串联刷新、产物登记、编译、部署，而不是模拟点击 Run 按钮或让用户再操作一次。

## 4. 已确认范围与非目标

### 4.1 已确认的产品行为

- 提供一次性的手动刷新入口。
- 处理当前运行目标内所有变更模块的注解流程；无需判断每个变更源码是否带注解。
- 实际处理采用完整有效模块输入，保留聚合处理器语义。
- 全部目标刷新成功并完成产物登记后，自动进入一次既有 Run 编排，编译部署所有待处理文件，包括刷新前已存在的待编译输入。
- 普通增量运行不自动执行新增的通用刷新流程。
- 已确认的删除与无注解依赖影响范围暂不扩展。

### 4.2 不包含的工作

- 通用注解依赖图、JAR 全量注解索引或处理器静态代码分析。
- 实现任意处理器的增量执行协议，或逆向其内部缓存。
- 依据某一源码变化扩散到所有下游模块的全新注解影响分析。
- 删除旧生成 class、设备端旧类清理等通用删除能力。
- 重写部署数据库、建立第二套部署事务或第二套 Run 状态机。
- 默认重跑整个工程所有模块、所有 variant、所有 target 的处理器。
- 本轮实现功能、运行真实项目构建/设备部署、推送或创建 PR。

未主动修改的依赖模块不会因为可能受影响而自动加入刷新目标。Gradle 为目标任务执行必要的前置依赖，是另一回事，应在执行日志中体现，不宣称本方案实现了完整影响传播。

## 5. 建议的用户流程

以下为实施建议；具体按钮位置和最终文案在后续实现前结合现有 UI 确认。

- 优先在 Jugg Running Panel 的 Build 操作区域增加“刷新注解并运行”入口。
- 说明文字建议：“刷新变更模块的注解生成结果，完成后自动编译并部署。”
- 一次点击代表一次用户操作，显示当前处理模块与刷新、编译、部署三个阶段。
- 与现有运行任务共用取消和串行约束，不能允许刷新任务与普通 Run 同时改写同一模块产物。
- 使用发起操作时确定的有效 Jugg Configuration/variant；执行期间切换 UI 配置不应改变正在进行的刷新目标。
- 不静默选择无关配置，不启动新的设备或改变既有部署策略。

```text
点击“刷新注解并运行”
  → 复用既有保存文档/变更收集及运行配置准备流程
  → 确定目标模块与本次输入版本
  → 预检所有目标模块的任务、环境和产物接入能力
  → 执行注解刷新
  → 确认全部目标成功，登记新增/变化及尚未消费的生成物
  → 由代码进入一次既有编译部署流程
      → 编译待编译文件及已有影响传播补充的输入
      → 部署本轮和已有待部署结果
  → 汇总整个操作的最终结果
```

“一次运行”指一次由本动作主动发起的后续 Run；既有 Run 内部已经定义的失败恢复或 Gradle fallback 按原契约执行，不为本动作新增无限重试或刷新递归。

空目标的建议行为：明确提示“没有需要刷新的变更模块”，不因这个刷新入口意外执行完整 Gradle 构建。若存在上次刷新已登记但编译/部署未完成的产物，则仍进入后续 Run 消费这些 pending 输入；它不是空操作。

## 6. 变更模块与状态生命周期

### 6.1 不能等同于当前待编译模块

`DeployFileStateTracker.updateUncompiledFiles()` 会将成功编译的文件从 uncompiled 转到 compiled，部署后状态还会继续推进。一个模块可能已经成功增量运行多次，但注解结果从未刷新。因此不能只用 `getUncompiledFiles()` 或 UI 当前 Changed Files 列表决定目标。

目标应为：**自相关有效完整 Gradle 构建或上次成功注解刷新以来，发生过源码修改、且在当前目标中配置了注解处理流程的模块。**

### 6.2 最小状态要求

优先复用现有文件变化入口和持久化能力；若现有状态无法表达，才增加最小的模块级待刷新记录。不要为一个动作引入通用任务数据库或仅有一个实现的接口。

- 模块身份必须区分实际工程根、模块根和 variant/target，不能只按显示名称。
- 普通业务源码新增/修改将所属模块标为待刷新；生成文件不再次标记为用户源码变化，防止自触发。
- 普通增量编译/部署成功不清除此标记。
- 使用输入版本或现有事件序号标识本次刷新覆盖范围。刷新期间出现的新修改不能被旧任务完成回调清除。
- 标记应能跨 IDE 重启恢复，或从已有可靠历史确定性恢复；具体存储 owner 待实现前核对。
- 标记缺失、旧版本升级、上下文恢复失败时，不能伪装“没有变化”。第一次使用的恢复策略需在技术验证中明确，避免静默扫描全工程或错误扩大目标。
- 完整 Gradle 构建只有确认覆盖相应模块/variant 和输入版本时才能清理对应标记，不能清除构建启动后新发生的修改。

### 6.3 区分生成完成与部署完成

| 阶段结果 | 待刷新状态 | 编译/部署状态 |
| --- | --- | --- |
| 处理器失败或用户取消 | 未成功模块保留；不假定所有输出仍旧或完整 | 不启动后续自动 Run |
| 生成成功但产物登记失败 | 不确认本模块完成刷新 | 保留可恢复结果，明确失败 |
| 生成和登记成功 | 只确认本次覆盖输入版本；新修改继续保留 | 生成结果进入既有待编译状态 |
| 后续编译失败 | 不因编译失败无条件重跑已成功处理器 | pending 生成源码必须保留，下一次 Run 可继续 |
| 后续部署失败或设备不可用 | 已成功刷新事实保留 | 使用已有 staging/部署恢复契约，不显示整体成功 |
| 编译部署全部成功 | 无新增修改时已完成刷新 | 按既有成功边界提交部署状态 |

多模块只完成一部分时，整体动作不得显示刷新成功或进入正常自动 Run。已完整登记的模块结果可保留，未完成模块仍需重试；不新增跨模块全局回滚框架。实际 Gradle 任务可能已经写入部分输出，应按最终失败现场处理，不能假设失败等于文件完全未变。

## 7. 执行后端：优先验证 Gradle 定向任务

### 7.1 选择依据

| 路径 | 优点 | 需要解决的问题 |
| --- | --- | --- |
| 项目 Gradle 注解任务及必要依赖 | 更容易复现项目实际配置、执行顺序和生成物契约 | task 定位、任务依赖放大、产物与基线接入、远程同步及耗时 |
| Jugg 现有原生 runner | 已有 APT/KAPT/KSP1/KSP2 执行基础，可能更轻 | 所有处理器的配置、混合引擎关系、资源/class 输出及额外构建步骤覆盖 |

优先验证 Gradle 路径，但**它尚未被选定为已证实可行的唯一实现**。若速度接近完整 Gradle 构建，或需要大量模拟 Gradle 才能消费结果，应重新评估入口价值，而不是为了按钮继续扩张框架。

### 7.2 Gradle 路径的验证要求

1. 从当前真实 project/variant/task 配置取得处理流程，不靠处理器 artifact 名称或统一拼接 task 名猜测。
2. APT 可能在 JavaCompile 内执行，未必存在可独立调用的注解任务。必须检查实际任务图，不承诺每个模块都有纯 codegen 入口。
3. KAPT、KSP1、KSP2、project processor dependency、混合 Java/Kotlin/KMP 输入必须使用项目当前配置；不能用 Kotlin 版本简单推断 KSP2。
4. 混合引擎按项目实际依赖关系执行，不能自行假设固定的 KSP → KAPT 顺序。
5. 相同可执行环境的目标任务尽量合并为一次 Gradle invocation，避免每模块重复启动；不能破坏 Gradle 已有 UP-TO-DATE 判断，也不默认强制 clean 或 `--rerun-tasks`。
6. Gradle 的 UP-TO-DATE 可以是有效成功，但必须确认输出存在且能接入后续流程；不能用一次旧目录存在代替本次任务结果。
7. 本地、远程、included build 和 variant 归属按现有执行环境契约处理。若首版不能覆盖某环境，操作前明确不可用，不偷偷改为另一执行环境。

“完整模块输入”要求保持聚合处理的完整语义，不要求关闭 Gradle/处理器自身已验证的增量机制。原生 runner 需要显式提供有效源码集合；Gradle 路径则应由真实任务管理输入与失效关系，不能擅自将任务输入缩减为 Jugg 本轮修改文件。

### 7.3 基线污染是必须先验证的风险

Gradle 的必要依赖可能执行 Java/Kotlin 编译，更新业务 class、生成 class 或增量缓存。仅比较生成源码并不保证 Jugg 后续仍能找到所有未部署变化。

技术验证必须明确：

- 本次任务是否只生成源码，还是也编译了业务/生成 class。
- 被 Gradle 改写的产物如何显式进入 Jugg 后续编译/部署与影响分析。
- 如果旧 class 已被覆盖，既有常量、类型和 ARouter 输入比较是否仍有可靠依据。
- 若无法保留并消费必要变化，应通过既有完整构建恢复路径收口，不能报告“已刷新并部署”却漏掉变化。

仅运行注解相关 Gradle 任务，不代表已经生成新的完整 APK。不得因此把普通后续编译结果标为 `CompileTaskResult.isGradleCompile=true`、设置 install，或提交完整 APK 基线。这个字段同时影响安装和上下文重建，必须保持真实含义。

### 7.4 原生 runner 的备用评估范围

若后续选择原生 runner，需要验证同一引擎全部目标处理器在一次 invocation 中执行的多轮语义，并避免独立重复 KAPT 启动。不能因某个无关 processor 失败而静默只运行 ARouter 后声称通用刷新成功。

现有 `KspProcessorRunner` 配置了 Java/Kotlin/class/resource 输出，但返回值只交付源码根；`JuggAptCompiler` 只接收 Java/Kotlin。必须补齐实际需要的产物消费或在预检/执行边界明确失败，不能忽略其他输出。

ARouter Fragment/Jetifier 兼容、DataBinding 与 custom compiler 的现有路径需要分别保留，并避免本次刷新后的同一输入被再次无意义生成。不为本功能重构这些既有能力；具体避免重复执行的方法要根据最终后端和有效输出证明决定。

## 8. 产物登记与自动 Run

### 8.1 文件不能只落盘后等待 IDE 事件

`FileChangesHandler` 会排除 build/generated 等构建输出路径。手动刷新结果必须经过显式收集和登记，不能期待普通源码监听自动发现它们。

复用当前 SourceCompiler 对 generated source 的接入方式，至少保证：

- 路径属于正确模块和 source root，类型为正确的 Java/Kotlin 输入。
- 完整聚合输入排除本处理器的旧产物，避免将旧生成源码作为新一轮原始输入。
- 项目实际依赖的其他生成输入按任务契约保留，不能一刀切排除所有 generated root。
- 新增或内容变化的生成文件加入待编译集合，不只比较 mtime。
- 内容相同但尚未成功编译的生成文件仍需消费；不能因为第二次生成结果相同就丢掉上次失败输入。
- 不覆盖刷新前已有用户待编译文件、staging 或 pending 部署状态。

若生成结果减少或改名，本期不实现通用删除支持，也不能宣称旧 class 已移除。实施前应明确最小失败/完整构建边界，禁止把“本期不支持删除”解释为“静默保留旧类并显示完整刷新成功”。这不扩大为删除能力建设。

### 8.2 复用既有编排

建议把刷新作为同一次用户操作的前置阶段，再进入现有 `JuggRunningTask` 的编译部署主链路；最终接线位置以任务生命周期核对结果为准。

- 由业务代码调用既有运行入口，不使用 UI 点击模拟，不启动第二套部署器。
- 刷新成功且产物登记完成之后才进入 Run，不能在异步发布完成前抢跑。
- 一次用户操作只主动发起一次后续 Run；双击、回调重复或生成文件事件不能重复部署。
- 默认执行用户要求的编译部署，不能沿用早期“只生成、跳过部署”的提议。
- 当前工程有其他运行任务时按既有项目串行规则处理，不让两条路径同时写 classpath 或输出目录。
- 刷新过程中若源码继续变化，不能将不同输入版本错误视为一致结果。应保留新变化并明确本次处理范围；安全交接做不到时停止后续运行并提示重试，不能无限自动刷新。
- 无变化生成物不等于无待编译文件：仍交给既有 Run 处理刷新前待编译和待部署输入。
- 编译、设备、重启、部署策略和 fallback 保持既有契约。没有设备、部署失败或取消时，最终状态必须如实区分。

## 9. UI、日志与失败信息

建议整个操作共享一条用户可追踪的任务记录；不维护重复的成功状态。

| 状态 | 建议对用户表达的事实 |
| --- | --- |
| 准备 | 将刷新哪些模块和 variant；无处理器模块明确跳过 |
| 刷新 | 当前模块/任务与已用时间 |
| 刷新完成 | 自动进入编译部署，不能在这里显示整个操作成功 |
| 编译失败 | 注解已刷新，但代码编译失败，保留诊断和待编译结果 |
| 部署失败/无设备 | 生成、编译结果已保留，但应用未成功更新 |
| 全部完成 | 注解刷新、编译和部署完成，显示总耗时与分阶段耗时 |
| 取消 | 明确已完成到哪一步，不继续自动运行 |

未支持任务或产物形式必须指出模块与原因。不要使用“未识别注解所以跳过”作为按钮路径的常规解释，因为按钮根本不以注解识别为触发条件。

日志继续使用 JuggLogger，关键阶段 info、非预期失败 warn、定位细节 debug、高频事件 trace，不新增 error 等级。现有失败/取消与自动 fallback 偏好应沿用，不引入每次点击的额外确认弹窗。

## 10. 性能验证与是否继续实施的依据

### 10.1 普通运行

在功能存在但用户未点击按钮时，确认没有新增：

- 处理器/引擎启动；
- 全模块源码解析；
- 全 classpath JAR 扫描或内容哈希；
- Gradle 调用；
- 每次 Run 的全量快照序列化。

允许复用已有文件变化回调维护模块标记，但应测量事件量、写盘和普通 Run 的影响，不能把“只是加一个标记”直接视为零开销。

### 10.2 主动操作

同一工程、同一变体、同一变更和设备，比较：

1. 现有普通 Jugg Run；
2. 定向注解刷新 + 自动 Jugg 编译部署；
3. 现有完整 Gradle 构建 + 部署。

分别记录冷/热 Gradle daemon、冷/热引擎、模块数、源码数、处理器集合、配置时间、实际任务图、生成时间、变化产物数量、编译时间、部署时间及总体时间。不得只报告单个 processor 的自报耗时。

如果刷新链路比完整构建没有清楚的收益，或者结果接入需要过大的维护成本，应暂停功能实施并记录证据。没有测量之前，不承诺“所有模块几秒完成”，也不预设未经讨论的固定性能阈值。

## 11. 后续可能涉及的文件与职责

本表用于下一轮定位和范围评审，不表示每个文件都必须修改，也不是本轮已经实现的内容。路径均相对仓库根。

| 领域 | 既有文件 | 可能承担的职责 |
| --- | --- | --- |
| UI | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt` | 手动入口、状态展示 |
| UI 动作 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt` | 选择配置、发起一次用户动作，不承载处理器实现 |
| 运行入口 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt` | 串行、取消与一次后续运行 |
| 总编排 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` | 刷新与编译部署阶段的衔接及最终结果 |
| 编译入口 | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 正确准备后续输入，保持 full build 标记契约 |
| 变化入口 | `main/src/main/java/com/sickworm/intellij/jugg/project/FileChangesHandler.kt` | 复用已有源码变化事件记录目标模块 |
| 文件状态 | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTracker.kt` | 核对既有 pending 状态，避免错误复用为待刷新状态 |
| 文件管理 | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | 显式登记生成产物并保留失败恢复 |
| 项目模型 | `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`、`KspInfo.kt`（同目录） | 复用模块、variant、processor 与 engine metadata；仅在真实缺失时增加任务信息 |
| Gradle 读取 | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt` | 验证并读取真实任务及输出，必要时同步生成脚本与序列化链路 |
| Gradle 执行 | `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/LocalGradleCompileClient.kt`、`RemoteGradleCompileClient.kt`（同目录） | 复用执行环境；先核对能否安全承载非完整构建任务 |
| 源码接入 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | 复用 generated source 编译链与失败 pending |
| 原生后端候选 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/AnnotationProcessorRunner.kt`、`main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KspProcessorRunner.kt` | 仅在选定原生后端时扩展必要能力 |
| 生成发布 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/GeneratedSourceCheckpoint.kt`、`JuggAptCompiler.kt`（同目录） | 核对发布、ownership、产物类别和失败边界 |

当前不预定新增生产类、接口或数据库。后续确需新增时，应说明既有 owner 无法承载的业务边界，再确定最小文件集合。

## 12. 验证策略与验收矩阵

遵循 [06_testing.md](../../ai_knowledge/06_testing.md)。实现前先取得相应行为缺失/失败证据；通过测试价值门禁的稳定行为采用 TDD，不为 mock 增加生产 seam。任务涉及编译部署编排，必须包含 L3 或等价 Flow 验证。

| 场景 | 预期可观察结果 | 建议验证位置 |
| --- | --- | --- |
| 未点击按钮的普通 Run | 原有编译部署行为保留，无通用处理器执行 | 既有 Flow 与性能对照 |
| 模块已经成功增量部署，随后手动刷新 | 仍识别此前尚未刷新模块 | 模块待刷新状态 owner 的行为测试 |
| 新增/修改带注解声明 | 真实生成结果更新，并由自动 Run 编译部署 | 真实处理器集成 + L3 |
| 修改无注解源码所在模块 | 手动动作仍按模块执行，不做注解候选筛选 | 集成验证 |
| 聚合处理器 | 未修改的合法条目保留，不生成只有单文件的索引 | 真实 fixture 输出行为验证 |
| APT、混合 KAPT、KSP1、KSP2 | 使用项目配置，产物与对应 Gradle 基准一致 | 分引擎集成验证 |
| 同模块多个处理器、混合引擎 | 按项目配置处理完整目标，执行顺序不靠猜测 | 真实任务图/多轮生成验证 |
| 多个变更模块 | 所有有效目标处理完成后只主动发起一次 Run | L3 或等价 Flow |
| 无处理器模块或无刷新目标 | 给出正确跳过/空操作状态，无意外完整构建 | 编排行为验证 |
| build 目录中的 generated source | 无须依赖 IDE 文件监听，显式进入本轮编译 | SourceCompiler/文件管理协作验证 |
| 生成相同但前次编译失败 | pending 输入仍被消费 | 既有失败恢复 owner 扩展 |
| 刷新成功且存在其他待编译文件 | 后续 Run 一并处理，不丢用户修改 | L3 |
| 处理器失败、输出不可读或不支持 | 整体不伪成功，不启动正常自动 Run | 集成 + 编排验证 |
| 多模块部分成功 | 已完成事实可恢复，失败模块保留，整体不部署部分刷新结果 | 编排/状态验证 |
| 刷新期间出现新修改 | 不被旧完成回调清除，不错误部署不一致结果 | 状态与并发行为测试 |
| 取消、双击、已有 Run | 不重复运行，不并发写输出，取消后不继续部署 | 编排行为验证 |
| 自动 Run 编译失败、部署失败或无设备 | 区分各阶段结果，保留可恢复输入 | L3/设备验证 |
| Gradle 前置任务编译并覆盖 class | 所有必要变化进入部署，基线语义正确 | 真实工程 Flow，不能只用 mock |
| IDE 重启、variant 切换、完整构建 | 模块刷新记录不串用、不丢失新变化 | 恢复与状态验证 |
| 删除及间接依赖变化 | 不宣称本期已覆盖，必要时按明确完整构建边界收口 | 已知限制记录与必要边界验证 |

可复用的既有 owner 包括 `KspProcessorRunnerTest`、`ARouterJuggAptProcessorTest`、`JuggAptCompilerTest`、`DeployFileStateTrackerTest` 和 `idea/.../manager/TopLevelFlowTest` 相关 Flow。精确方法与新增落点由实际后端决定，不预先新增大量镜像实现的测试。

开发验证只能执行定向 `:main:test --tests ...` / `:idea:test --tests ...`，禁止无过滤全量测试。编译验证可用 `./gradlew :idea:compileKotlin`。若使用 JOOX Android 工程作真实验证，遵守其不新增单元测试的规则；以上可自动化回归放在 Jugg 仓库合适 owner。

## 13. 文档同步与后续实施顺序

### 13.1 本轮交付

仅新增本方案，保存讨论过程、最终产品决定、已知限制和待验证事项。现有知识库与 Wiki 描述的是已实现能力，不能提前改写成该按钮已经存在。

### 13.2 后续顺序

1. 读取本方案及当前仓库规则，核对代码是否已变化。
2. 验证真实 Gradle task 图、输出范围与基线影响；对比定向刷新和完整构建总耗时。
3. 明确后端选择、支持环境、第一次使用/重启的状态恢复，以及失败/删除边界。
4. 按实际选择收敛修改文件和验证 owner，沿后续会话适用的 feature 开发流程确认实现范围。
5. 实施手动入口、最小待刷新记录、产物接入和自动 Run，保留普通运行路径。
6. 完成定向回归、真实处理器产物对照、编译部署 Flow 和性能测量；更新本方案为实际结果。
7. 功能完成后同步知识库和相关中英文 Wiki，提交时只包含当次任务改动。

实施后应检查的文档：

- `docs/ai_knowledge/02_compile_source.md`、`02_compile_custom_ui.md`：手动入口与生成、pending、失败契约。
- `docs/ai_knowledge/04_engineering_project.md`：仅在 task/输出 metadata 改动时更新。
- `docs/ai_knowledge/04_engineering_ide.md`、`03_deploy_complete.md`：按钮到刷新、编译、部署的完整流程。
- `docs/ai_knowledge/98_code_map.md`：实际新增或变更职责时同步。
- `docs/wiki/zh/capabilities/compile/annotation-processors.md` 及英文镜像：明确用户操作、自动运行和限制。
- `docs/wiki/zh/capabilities/compile/custom-compiler.md` 及英文镜像：仅在实际影响已有扩展使用方式时更新。

## 14. 定位依据与证据边界

本会话已读取的知识库文档：

- [00_overview.md](../../ai_knowledge/00_overview.md)
- [99_index.md](../../ai_knowledge/99_index.md)
- [98_code_map.md](../../ai_knowledge/98_code_map.md)
- [02_compile_core.md](../../ai_knowledge/02_compile_core.md) 相关条目
- [02_compile_source.md](../../ai_knowledge/02_compile_source.md)
- [02_compile_custom_ui.md](../../ai_knowledge/02_compile_custom_ui.md) §4.3
- [03_deploy_data_generator.md](../../ai_knowledge/03_deploy_data_generator.md)
- [04_engineering_project.md](../../ai_knowledge/04_engineering_project.md) 相关条目
- [06_testing.md](../../ai_knowledge/06_testing.md) 验证与测试价值条目
- [03_deploy_complete.md](../../ai_knowledge/03_deploy_complete.md)
- [04_engineering_ide.md](../../ai_knowledge/04_engineering_ide.md)

另外核对仓库 `AGENTS.md`、本会话用户提供的规则、[KSP 生成修复记录](./arouter_ksp_generation_fix.md)、[KAPT 性能修复记录](./kapt_compiler_output_regex_performance_fix.md)、ARouter KSP fixture README/源码，以及本方案列出的核心执行和状态代码。曾查阅 feature-development 工作流；本轮是文档整理，不开始功能实施。

外部接口依据：

- [JSR 269 Processor](https://github.com/openjdk/jdk/blob/master/src/java.compiler/share/classes/javax/annotation/processing/Processor.java)：声明注解类型、通配符和初始化契约。
- [KSP SymbolProcessor](https://github.com/google/ksp/blob/main/api/src/main/kotlin/com/google/devtools/ksp/processing/SymbolProcessor.kt)：执行入口没有统一注解清单。
- [KSP Resolver](https://github.com/google/ksp/blob/main/api/src/main/kotlin/com/google/devtools/ksp/processing/Resolver.kt)：字符串注解查询及文件/声明访问能力。
- [RetentionPolicy](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/lang/annotation/RetentionPolicy.java)：SOURCE/CLASS/RUNTIME 的保留语义。

上述外部链接为本次查阅时的上游分支，后续实际适配仍应核对项目使用的具体版本。本会话未执行新的引擎基准、Gradle 定向任务或设备部署，本文不能作为这些验证已经通过的证据。
