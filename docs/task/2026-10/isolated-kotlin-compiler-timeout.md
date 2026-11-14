# 独立 Kotlin 编译超时与任务收尾方案

日期：2026-10-20。关联现场：Jugg report `8c83e732`。

状态：已按后续请求先实施诊断日志与自动线程取证；下述输出重定向、有界收尾与失败原因改造仍为待实施方案。

## 目标与证据边界

优先解决已观察到的“编译报超时后任务仍不结束、取消也未正常收尾”。目标是让独立 Kotlin 编译在成功、失败、超时和取消时都有明确终态。子进程最初停止推进的原因尚未确定，本方案不以补充用户现场为实施前提，也不承诺消除该原始原因。

现场环境为 Windows 11、Android Studio Meerkat Feature Drop 2024.3.2 Patch 1、IDE JVM 21.0.7、Jugg 3.5.4-release、Kotlin 1.6.21，独立编译使用 `D:\tools\jdk-11\bin\java.exe`。同一报告中较早的 4.0.5 canary 日志不用于代表此次失败版本。

| 时间 | 已确认事实 |
| --- | --- |
| 17:30:26 | 完整 Gradle 构建成功，随后安装成功 |
| 17:31:37 | 进程内 Kotlin 预热出现 `WindowsFileSystem.close` / `DelegatingFileSystem.close` / `DescriptorLoadingContext.close` 异常，并记录后续隔离执行 |
| 17:45:28 | 本轮资源编译完成；进度显示 `11/11`，随后进入 DataBinding |
| 17:45:29 | DataBinding adapter store 生成显式启动独立 KAPT，输入为 `ViewAdapter.kt` |
| 17:50:29 | 独立编译触发五分钟超时 |
| 17:51:43 | 用户取消进度，日志记录 detach |
| 17:53:34 | 用户生成报告；超时后仍未出现 Kotlin 返回码或编译终态 |

注意：DataBinding adapter KAPT 本身显式要求独立进程，不能把此次启动单纯解释为预热冲突的后果。最后一条编译器输出是配置编译环境，但输出解析有缓冲，不能据此锁定编译器内部停在哪个方法。

`KotlinCompilerProcessRunner` 的超时、取消和正常退出分支均调用无期限 `outputThread.join()`；超时日志出现在进程终止与 join 之前。3.5.4 与当前 HEAD 的相关 runner 实现一致。现有证据支持“超时后的清理链路未返回”，不足以区分具体卡在进程终止、输出回收或其下层操作。

## 推荐实施方案

### 1. 让独立编译不依赖管道 EOF 收尾

行为 owner 为 `KotlinCompilerProcessRunner`。将合并后的 stdout/stderr 重定向到本次 invocation 独有的本地临时文件，移除 `jugg-kotlin-compiler-output` 线程和全部 join。

- 在启动进程前创建输出文件；创建或读取失败时明确返回编译失败，不能缺失诊断后继续判成功。
- 保留 `redirectErrorStream(true)`，用文件输出替换 PIPE。启动后关闭未使用的 stdin，使子进程不会等待交互输入。
- 编译线程按文件偏移读取新增字节，继续交给现有 `KotlinCompilerOutputParser`，不另建日志解析器。每轮读取设置字节上限，例如 64 KiB，随后检查取消和截止时间，避免持续输出饿死监控。
- 保留原始字节，不按轮次解码，避免 UTF-8 字符跨批次损坏；解析器只在编译线程使用，返回后不会再被输出线程修改。
- 主进程退出后按已观察到的文件长度读取剩余内容，不等待子孙进程关闭句柄，也不追逐不断增长的 EOF。最终输出读取仍受收尾预算约束；无法完整处理应明确失败，不能返回成功。
- argfile 和输出文件在 finally 中释放。Windows 上因进程仍持有句柄而删除失败时记录原因并复用现有延迟删除方式，不循环重试。

不采用仅把 `join()` 改成 `join(5000)` 的方案：超时返回后，输出线程可能继续持有或修改解析器；在主线程直接关闭仍被读取的管道，也可能重新进入阻塞。文件重定向直接移除这项等待依赖。

### 2. 超时、取消和异常使用同一收尾边界

- 编译上限保持五分钟，轮询保持现有 100 ms；不根据缺少输出提前判定卡死。
- 收尾使用一个基于单调时钟的共同预算，建议五秒，覆盖进程终止后的等待、剩余输出处理和资源释放；不能给每个阶段各自追加五秒。
- 终止前保存当前可见子孙进程句柄快照；终止主进程，并对快照中的存活子孙执行终止。只处理本 invocation 的进程，不按进程名批量结束 Java。
- 所有显式等待使用剩余预算。取消优先于超时判定，已取消任务不再等待原五分钟期限。
- `InterruptedException` 和启动后的其它异常也进入同一清理；恢复线程中断标志，由现有调用链处理取消。
- 截止后仍存活的 PID、终止失败或输出读取失败必须记录。主任务明确失败，不发布部分产物，不启动本轮重试或 Gradle 构建，并提示先处理仍存活的进程，避免并发写入相同目录。

五秒是应用层收尾等待预算，不是对磁盘故障、OS 原生进程 API 或 IDE 日志系统无限阻塞的硬实时保证。若真实进程夹具发现其中的同步调用能稳定越过预算，再针对该调用调整；不预先增加通用进程管理框架或后台清理线程池。

### 3. 向现有流程返回具体失败原因

- 保留 runner 的 `ExitCode` 返回契约。超时仍为失败，通过现有输出通道补入 `error:` 诊断，让 `CompileError` 保存“独立 Kotlin 编译超时”，而非只剩 `compile failed`。
- 进程正常退出但输出回收不完整、输出读取失败或终止未完成时，也必须返回非成功状态，不能仅打印 warning 后返回 `OK`。
- 用户主动取消沿用现有取消标记，不伪装为源码错误或成功。
- `DataBindingGenMapperCompiler` 继续仅在 KAPT 成功时消费 setter store，`SourceDataBindingProcessor` 继续仅在 adapter class 成功后提交 store。超时不能复用本轮半成品，也不能跳过 DataBinding 后继续部署。
- 让既有 `JuggRunningTask` finally/终态流程自然释放运行状态与进度，不额外添加一套“强制完成”状态。验证取消信号确实传到 `CompileTask.isShouldCancel`；只有失败测试证明接线缺失时，才修改 IDE 层。

### 4. 使用现有日志保存下一次的区分证据

在本次调用内记录 PID、实际 Java 路径及版本、输入文件数量、执行耗时和终态。超时额外记录主进程/已知子孙存活情况、终止结果、输出已读取/剩余字节和清理耗时。

- 用户可见 warning 说明“独立 Kotlin 编译超时，正在结束任务”；结束时区分“已停止”和“仍有进程存活”。普通 Gradle 回退仍会复用中间状态，不能承诺直接恢复；用户已反馈必须 clean 后重新编译，见下文补充调查。
- 详细状态用项目 `JuggLogger.debug` 写入现有报告会收集的 compile 日志，不增加上传接口或新报告格式。
- 不记录完整环境变量；复用既有编译参数日志，不重复输出可能含敏感信息的参数。
- 按后续请求，先在独立辅助线程中提前尝试一次有时限的 `jcmd`，不让新增采集成为任务退出的前提。实际落地范围见文末。

## 实施范围与收益

| 落点 | 必要变更 | 预期结果 |
| --- | --- | --- |
| `KotlinCompilerProcessRunner.kt` | 文件输出、单线程读取、有界收尾、具体失败诊断 | 去除无限 join 及解析器并发；超时/取消能进入终态 |
| `KotlinCompilerProcessRunnerTest.kt` | 进程生命周期回归 | 保护返回码、取消、输出完整性与收尾行为 |
| 既有 DataBinding / Flow owner | 定向回归，必要时补失败场景 | 失败不提交 store、不部署；正常 adapter 更新仍有效 |
| `02_compile_source.md` | 实现后同步输出与超时契约 | 知识库与实际行为一致 |

不改变五分钟阈值，不新增用户设置，不升级用户 Kotlin/JDK，不修改 DataBinding 触发条件，不直接改动全局 Gradle `CmdExecutor`。它的输出和等待契约并不能直接替代独立 Kotlin runner，整合会扩大本次补丁。

本次属于既有任务终态异常的 bugfix，实施提交使用 `[bugfix][compile]`。未知超时不满足“改变失败条件后可恢复”的重试条件，因此不自动重跑同一独立编译；本轮仍明确失败，后续 Run/Force Gradle 沿用现有入口。未确认原始原因前，不宣称编译速度改善或卡死根因已修复。

## 验证设计

### 测试价值与 owner

超时/取消可结束、成功输出完整、失败不部署属于独立且稳定的外部行为，值得测试。已有 `KotlinCompilerProcessRunnerTest` 主要验证命令参数，没有保护生命周期；已有 `SourceCompileDataBindingTest` 与 `TopLevelFlowTest.testDeployIncrementalDataBindingSetterStore` 保护 DataBinding 协作和最终部署。

实施前优先通过现有 runner 入口和独立 JVM 夹具取得可重复失败。夹具使用测试 classpath 中的 compiler main，控制退出、持续运行及输出；不向生产代码新增 provider、可变 lambda、测试开关或为了缩短测试而加入可配置超时。取消场景用夹具就绪信号再触发取消，避免靠固定 sleep 猜启动时间。

实际五分钟超时至少做一次独立进程集成验证，验证驱动自身设更大的外部 watchdog 并清理夹具。若 Windows 的原始“终止后未返回”无法稳定构造，保留报告作为失败证据，明确采用 Windows 手工回归；不声称合成夹具重现了原始根因，也不写源码字符串断言代替运行时验证。

### 验收矩阵

| 场景 | 验收结果 |
| --- | --- |
| 子进程正常退出 | 保持现有退出码映射；诊断和产物读取完整；不因文件批次切分丢失中文或末尾诊断 |
| 子进程不退出 | 五分钟触发失败，收尾按五秒共同预算执行，返回后无输出解析线程；主任务释放进度 |
| 用户在编译中取消 | 不等五分钟，按收尾预算进入取消终态；无部署、无成功提示 |
| 子进程退出但子孙持有输出句柄 | 不等待 EOF；读取退出时输出快照后结束，不无限 join |
| 持续大量输出 | 每批输出间能检查取消/超时；预算内处理不完时明确失败，不伪造成功 |
| 输出文件不可读或终止失败 | 保留最终错误、存活 PID；本轮不发布半成品、不启动第二个编译 |
| DataBinding adapter + layout 正常变更 | setter store、adapter class、BindingImpl 与布局均正确生成并部署 |
| KAPT 失败或取消 | merged store 不提交、本轮不部署；确认进程已退出后可重新 Run |
| 非 DataBinding / 未隔离的正常编译 | 行为保持，不受输出重定向影响 |

建议定向命令，均为实施后的待执行项：

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerProcessRunnerTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.SourceCompileDataBindingTest'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.TopLevelFlowTest.testDeployIncrementalDataBindingSetterStore'
./gradlew :idea:compileKotlin
```

Windows JDK 11 为必要的发布回归环境；本机 macOS 验证只能证明通用生命周期和正常链路，不能替代 Windows 进程句柄行为。运行时验收看实际任务终态、进程状态、store 与部署结果，不以出现超时 warning 作为通过标准。

## 补充反馈：回退仍失败，clean 后恢复

用户进一步反馈 DataBinding 编译出问题后，“换降级编译也会失败，提示一些很奇怪的错误，只能 clean，然后重新编译”。这是新增的用户观察，原报告没有包含随后回退失败的首个 Gradle task、异常栈及 clean 后成功日志，不能把它写成已经在报告中验证的调用链。“降级编译”暂按回退 Gradle 理解；进程内编译器的兼容降级是另一条路径，不能混为一谈。

### 已核对的失败边界与反证

- `JuggCompileHelper.gradleCompile()` 将配置中的 command 交给 Gradle 执行，不自动追加 clean。当前 `withGradleCacheRefresh()` 即使追加 `--no-build-cache --rerun-tasks`，也不删除全部已有输出。回退 Gradle 与 clean 重建的前提不同，因此回退仍失败与 clean 后恢复并不矛盾；但不能据此区分具体哪一类状态失效。
- 此次 `runKotlinAdapterKapt()` 的 sources、classes、stubs、incrementalData、`-d` 及 DataBinding processor 输出都指向 Jugg 工作区。`DataBindingGenBaseClassesCompiler.copyToGradleDir()` 的目标实际为 `BaseCompileContext.backupGradleDir()` 返回的 Jugg incremental 目录，不是原 Gradle layout info 目录。上述两条是对“这次 KAPT 直接覆盖了 Gradle DataBinding 目录”假设的反证，不能仅凭方法名判定污染。
- 普通 Kotlin 编译会写入模块 Gradle Kotlin classpath，编译前后还会合并 `.kotlin_module`；DataBinding 后续 `mergeLibraryBr()` / `mergeAppBr()` 也会写入选中的 Gradle BR 源文件。这说明 Jugg 与 Gradle 并非所有产物都隔离，但不能证明本次卡住时执行过这些写入。
- `SourceDataBindingProcessor.prepareKotlinAdapter()` 先等待 KAPT，再编译 adapter class，最后发布 store，随后才进入 mapper APT 与 BR 合并。原现场没有 KAPT 返回码或后续阶段记录，缺少本次进入这些后续步骤的证据，不能将其写入直接认定为原因。该顺序、KAPT 路径和 layout info 备份实现已与 3.5.4 tag 对照；当前 Mapper 文件相对该 tag 的差异仅为另一处诊断日志。
- 原现场缺少进程终态，仍需排除旧任务或子进程未结束与后续构建重叠。clean 成功本身不证明子进程已退出，也不证明存在 Windows 文件句柄占用；句柄问题通常还需要删除失败或 owner 证据。

### 下一次取证与优化顺序

1. 优先使用已实施的 PID、phase、宿主栈与子 JVM 栈，区分编译器内部停顿、宿主输出阻塞和收尾未完成。有存活旧进程时，不能直接把新构建错误归为缓存损坏。
2. 关联同一次失败之后的 Gradle command、首个失败 task、第一处有效异常及退出码，区分生成源/符号不一致、处理器异常和文件删除/占用失败。只用最后的 `BUILD FAILED` 或汇总文案不能定位原因。
3. 已实施的线程诊断并未新增 DataBinding 产物快照。如果下一轮仍指向失败状态残留，再围绕实际失败 task 保存 clean 前的直接相关文件清单、大小、修改时间和必要的内容 hash，并与本轮 KAPT 前后、回退前后对照。优先检查 setter store、BR/Mapper、layout info、`.kotlin_module` 及相关 Kotlin class，不能从缺少快照推断它们已变化；也不默认扫描或复制整个 build 目录。
4. 实施有界收尾后，先验证原任务已结束，再验证同一改动在下一次 Gradle 构建中的结果。若定位到某个共享产物写入失败后破坏基线，修复该写入 owner 的最小提交/恢复边界；若定位到旧进程存活，先修复进程终止与任务互斥，不扩大为全量缓存治理。

新增验收场景为“DataBinding 失败/超时 → 原任务与进程结束 → 同一改动执行 Gradle”，记录未 clean、强制重跑 task、clean 重建各自的结果。现有生产逻辑和新增诊断均不自动执行 clean、不自动删除用户 Gradle 缓存；clean 仅作为用户已经验证的临时恢复手段，不能作为修复根因的依据。

本轮补充为文档调查，不引入新的可执行行为或实现细节测试。替代验证为原日志时间窗复核、3.5.4/HEAD 调用链和路径对照，以及文档 diff 检查；未执行用户 Windows 项目的 clean 或 Gradle 构建，也未取得其回退失败的直接证据。

## 文档与本次交付

首轮仅交付方案。后续用户要求通过日志捕捉下一次原因，本次先落地下面的诊断增强，未实施上文的退出优化。已同步 `02_compile_source.md`、`09_plugin_runtime_debug.md` 与 `98_code_map.md`。现有操作入口、支持范围和恢复方式未变，Wiki 无失准页面，不修改 Wiki。

### 已实施：诊断增强

- Runner 增加 PID、输入数量与 argfile 路径，按 PID 记录实际退出码、终止子孙、终止主进程、等待输出和 flush 等阶段的开始与完成位置。按补充请求，终止子孙后记录 `destroyForcibly` 返回值和检查时的存活状态；主进程记录终止调用已返回及存活状态，不把请求返回记成已经退出。
- `KotlinCompilerProcessDiagnostics` 负责本次进程的独立取证生命周期。输出读取仍沿用原管道与 PrintStream，只增加读取/转交字节和最后输出时间统计，不修改 Kotlin 编译参数、超时、回退、退出码和 DataBinding 逻辑。
- 进程仍未开始收尾时，第二、第四分钟各采样一次累计 CPU 毫秒数、实际采样间隔、输出读取/转交字节和最近输出距今时间；CPU 不可用时明确记录 unavailable，读取异常仅舍弃 CPU 信息。比较两次进展用于辅助区分持续计算与等待，不据此认定正常或死锁。第四分钟由同一独立 daemon 线程采集宿主线程栈，再尝试同一 Java home 的 `jcmd <pid> Thread.print -l`。工具最多显式等待五秒、最多读取 256 KiB，不在主编译线程等待采集。临时输出文件避免诊断自身依赖管道 EOF；工具缺失、失败、超时或文件异常均有 debug 原因，不伪造成功。
- 正常退出、取消或超时后，收尾十秒仍不返回时，辅助线程记录编译线程和输出线程的真实栈、当前 phase、主进程与已发现子孙的存活状态及进展计数。子孙句柄沿原终止遍历登记，不再次枚举进程树，避免在取证线程重复进入待排查的枚举边界；单个进程状态不可读时记录原因和 unavailable。若主编译监控本身未按期进入收尾，也记录其栈。每次 invocation 最多两次慢编译进展快照、一次子 JVM dump 和一次迟滞快照，避免无限周期采集。
- 全部证据写入既有 compile 日志，沿用现有报告脱敏和收集。没有新增报告接口、用户开关或阻塞主流程的 dump 操作。原有无期限 join 仍存在，本次不宣称修复卡住。

### 本次验证

原始失败证据为报告缺少子进程栈与超时后的状态。新增真实外部进程验证归入已有 `KotlinCompilerProcessRunnerTest`：实际 JVM 的 CountDownLatch 等待能被线程栈识别；工具缺失不终止编译；模拟 attach 工具不退出时五秒内结束显式等待并保留部分输出；正常编译退出码和中文输出保持；输出消费者阻塞时，收尾监控能同时记录编译线程的 join 栈和输出线程的等待栈。测试不引入生产代码测试参数或 provider。

添加测试后先运行定向检查，因诊断组件尚未实现而在测试编译阶段失败；原始现场缺少诊断的事实仍以报告为准，不把该编译失败描述为已复现用户卡死。

使用本机 Corretto JDK 17 执行以下命令成功，runner 测试共 11 项，0 失败、0 跳过。其中真实 JVM dump 约 0.43 秒，模拟 attach 不退出的检查约 5.30 秒，收尾阻塞取证约 10.23 秒；正常 fixture 编译约 0.68 秒，没有等待诊断阈值。诊断组件的真实 attach、超时降级和十秒收尾监控均已覆盖；未执行完整五分钟超时和四分钟慢编译触发的长时等待，也未复现 Windows 原现场。

```bash
JAVA_HOME=/Users/wormchen/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
./gradlew :main:test \
  --tests 'com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerProcessRunnerTest' \
  :idea:compileKotlin
```

`git diff --check` 通过，新增日志均按字符串 `+` 位置换行、续行增加八空格，不新增 error 等级日志。

本次仅增加诊断及保持语义的等待逻辑拆分，不修改顶层 compile/deploy 编排或 DataBinding 产物策略；采用外部 JVM / 输出阻塞验证覆盖实际边界，不据此宣称通过 Windows 11 + JDK 11 的原始现场回归。

补充终止结果和 CPU/输出进展日志时，缺口证据来自现有源码：终止阶段未保存子孙终止返回值，慢编译仅有第四分钟的一次快照。普通日志文案和采样字段不满足新增自动化测试的价值门禁，本轮不新增实现细节测试；复用上述 11 项外部进程回归和 `:idea:compileKotlin` 验证正常退出、输出、辅助工具失败和收尾迟滞路径，最终检查全部通过（11 项，0 失败、0 跳过）。diff 检查和新增日志字符串续行格式检查通过。第二、第四分钟实际定时采样及 Windows 存活状态仍需长时现场验证，不把编译通过等同于已覆盖这些运行时分支。

依据：`00_overview.md`、`99_index.md`、`98_code_map.md`、`09_plugin_runtime_debug.md`、`02_compile_core.md`、`02_compile_source.md`、`02_compile_databinding.md`、`06_testing.md`；报告的 manifest、environment、17:05 / 17:31 日志；3.5.4 与 HEAD runner 对比；`KotlinCompilerInvoker`、`KotlinCompilerOutputParser`、`DataBindingGenMapperCompiler`、`SourceDataBindingProcessor`、`CompileTask` 和上述回归 owner。
