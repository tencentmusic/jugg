# Jugg 内存与 CPU QA 取证

日期：2026-10-15。范围：补充资源占用 QA，采集空闲与 Java / Kotlin / res 增量编译证据；不修改插件实现。

## 结论与 Wiki 口径

- 当前现场支持“Jugg 自有工程、部署状态占用相对较小”，但**不支持“插件全部常驻内存都很小，主要来自编译器”**。插件依赖也必须计入：JGit 静态缓存保留树在两次堆快照中分别为 493.39 / 398.08 MiB，明显高于本次识别的 Kotlin 编译器类加载器保留集合。
- 空闲采样未发现 Jugg 业务代码形成持续 CPU 热点。这里的空闲指用户未操作、无 Jugg 编译部署；IDE 自身仍有后台活动。有限时间、有限栈采样不能证明绝对零 CPU，也不能排除没有 Jugg 包名的依赖代码。
- Java / Kotlin / res 各完成 4 轮增量编译与部署，全部成功；三份源文件已按原始 SHA-256 恢复。资源编译单独统计 `aapt2-inclink`。
- daemon 只说明事实：兼容且空闲的本地 daemon 可以复用；版本、JDK、JVM 参数不兼容或 daemon 忙时可能出现多个进程。远程 Gradle 构建不会因此新增本机 daemon。按维护者要求，不做 daemon 实验，不修改正在另行调整的默认参数。

直接受影响页面为中英文 `troubleshooting/jugg-slow-or-stuck.md`：原页缺少内存、CPU 数据与异常反馈建议。新增两条 QA，直接列出内存、空闲采样与编译部署 CPU 数据，简述测试环境和统计边界，沿用现有排查入口，不增加路由。本文件保存完整方法、原始记录索引与限制。

## 环境与测量对象

| 项目 | 本次环境 |
| --- | --- |
| 工程 | JOOX Android，维护者说明约 300 万行代码；未独立统计行数 |
| 输入 | 单个 Java 文件 71 行 / 1,608 字节；单个 Kotlin 文件 121 行 / 5,490 字节；单个 drawable XML 5 行 / 222 字节 |
| 机器 | Mac15,7，Apple M3 Pro，12 个逻辑核，36 GiB 内存 |
| 系统 | macOS 26.6.2，25G83 |
| IDE | Android Studio Panda 4 2025.3.4 Patch 1，AI-253.32098.37.2534.15336583 |
| JBR | 21.0.11+10-b1163.116 |
| 已安装 Jugg | 3.5.4-release，按实际安装包的 plugin.xml 确认 |
| 安装包校验 | idea JAR SHA-256：`071ab0aec2d25e5a0c8100793c0582a2bf3785158d2068d1b7e9140fc862982e` |
| 同时打开 | JOOX、jojo、Jugg，属于共享 IDE 进程现场 |
| 工具 | JBR jcmd / JFR、macOS ps、Eclipse MAT 1.16.1、Jugg CLI |
| 单位 | MiB = 1,048,576 字节；CPU 100% 表示占满一个逻辑核；CPU 秒表示累计处理器时间 |

本地源码用于核对机制，不能代替已安装产物的实测。初始 Jugg 源码版本记录在本地 `environment.json`；并行开发产生的后续提交不改变本次安装包版本。

## 采集与恢复方法

1. 保存 JOOX HEAD、工作区状态、三份样本原始字节与 SHA-256。保留用户已有的业务文件改动与文档子仓库状态。
2. 先采集 60 秒无用户操作的 JFR 与进程样本，再取得首次堆快照。消化原有待编译变化、建立可用基线后才进行单文件实验。
3. Java / Kotlin 在文件开头增加一个空行；XML 在声明后增加一个空行。分别执行“加空行 → 恢复 → 再加 → 再恢复”，每种类型共 4 轮。
4. 每轮前后各留 10 秒对照；每 250ms 采集 `ps -axo pid,ppid,time,rss,comm`。同时记录 JFR profile。每轮只执行一个前台 CLI `deploy --always-restart-app false`，等待命令退出；以退出码和最终 `runResult` 判断成功。资源样本不用于推断任务完成。
5. 逐轮核对 `isGradleCompile=false`、编译成功、部署成功，及 detail 中实际单文件编译记录。Java 首轮触发重装，资源首轮传输已有 overlay 集合，分别标为预热 / 基线同步，不混入普通热部署比较。
6. 实验结束取得第二份堆快照，再采集 90 秒 JFR。三份样本的 SHA-256 全部恢复，最后一次恢复也完成增量编译与部署；JOOX HEAD 与用户原有脏文件条目保持不变。远程跟踪分支的 behind 数变化来自后台 fetch，不属于源码修改。

原始记录与脚本持久保存在本机 `/Users/wormchen/.jugg/investigations/resource-usage-20261015/`；仓库忽略目录 `build/reports/resource-usage-20261015/` 为指向它的符号链接，避免后续 clean 删除原始证据。归档包含商业工程日志、堆快照和原始文件备份，仅本地保留。脚本中的采集目录仍指向采集时的 `/tmp/jugg-resource-evidence-20261015`；复用时需调整目录、IDE PID、工程与样本清单，不应直接照搬运行。可提交的逐轮摘要见 [CSV](jugg-resource-usage-samples.csv)。

完整本地文件校验清单为 `SHA256-manifest.json`。关键原始证据的 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `idle.hprof` | `f4f7bd913e7332d021795a173ca582039197ca8e7714c2e5cc9724f6d6ebb652` |
| `post-trials.hprof` | `42c623dbdd12319b2d92de3021fbd563c738b7251fce420b581deca40f69046c` |
| `idle-final.jfr` | `d3892d3289030f59f17798c9278b2748072427e64f35b1c59d68fce1c58645f8` |
| `trial-summary.csv` | `e5ccfac54c5a721705781b00a3e545a6b1cd91960ef116ffa40dd7d1208387be` |

## 内存：自有包对象不等于插件总成本

堆快照使用 `jcmd GC.heap_dump`，MAT 解析可达堆后按 retained set / dominator 计算，排除不可达对象。采集、GC 与解析不纳入编译计时。

| 统计范围 | 实验前 MiB | 实验后 MiB | 含义 |
| --- | ---: | ---: | --- |
| Jugg 自有包对象为根的 retained set | 30.88 | 35.60 | `show_retained_set com.sickworm.intellij.jugg.*`，包含它们独占保留的部分依赖对象 |
| Jugg 主插件组件 retained set | 541.20 | 437.25 | 主插件类加载器及归属该加载器的顶层 dominator 根集合，按 MAT Top Components 相同范围计算 |
| JGit `WindowCache` 静态类保留树 | 493.39 | 398.08 | 归属于上述 Jugg 主插件加载器，是本次组件内的主要占用 |
| Kotlin 隔离类加载器 retained set | 9.40 | 10.80 | `PriorityURLClassLoader` 为根；仅该范围，不能当成全部编译器成本 |

对应字节数分别为：自有包 32,383,528 / 37,327,256；主组件 567,490,976 / 458,485,168；JGit 517,361,552 / 417,415,160；Kotlin 9,856,288 / 11,319,664。原始查询见本地 `loader-owners.xml`、`large-roots.xml`、`memory-owners.xml`、`final-memory.xml` 及对应 MAT HTML ZIP。

主加载器地址为 `0x5854a5740`；其已加载类集合包含 `org.eclipse.jgit.internal.storage.file.WindowCache`（`0x598eab148`）。这条保留关系与源码 `GitManager` 使用 JGit 读取仓库相符，因此不能把这部分称为编译器缓存或普通 JAR 加载缓存。

这些范围有包含和重叠，**不能相加**。Retained set 不覆盖共享对象、metaspace、native 分配与所有可能的子加载器成本，也不是“关闭插件后 RSS 会减少多少”。Jugg 自有包的 retained set 同样不能当作纯业务状态的精确上限。

实验中 IDE RSS 约从 6.1 GiB 升至 7.8 GiB；实验后 GC 前 Java heap used 约 4.28 GiB，GC 后约 2.59 GiB，committed heap 约 5.63 GiB。RSS、已提交堆、存活对象与插件保留集合是不同指标，编译后 RSS 没有立即下降不足以判断泄漏。两次 JGit 保留树下降也只描述本次快照变化，不证明长期无泄漏。本任务没有进行缓存上限或生命周期修复。

## CPU：空闲与编译分开观察

### 无用户操作时的栈采样

- 首次 60 秒使用 JFR profile 默认采样配置：40 个 Java ExecutionSample 中有 1 个栈包含 Jugg 文件变化处理；因此不能表述为从不执行后台工作。NativeMethodSample 单独保存，不把阻塞 native 栈计成 Java CPU 热点。
- 编译与恢复后 90 秒使用 `jdk.ExecutionSample#period=1ms`，关闭 NativeMethodSample：2,438 个 Java ExecutionSample 中，0 个栈包含 Jugg 自有包。原始文件为 `idle-final.jfr`，导出与汇总为 `idle-final-events.json`、`idle-final-summary.json`。
- 期间 IDE 仍有后台负载，JFR 记录的进程 CPU 均值约为单核的 98.73%。前后线程累计 CPU 差值中可见 JIT 编译器、动画及 UI 线程；采样栈包含 IDE 进度与容器操作，后续也观察到 IDE 索引状态。不能把 IDE 总 CPU 认作 Jugg CPU。

证据支持“本次空闲窗口未见 Jugg 业务代码持续热点”。采样有开销、可能漏掉短任务和 native 工作，依赖代码也可能没有 Jugg 包名，不能给出插件 CPU 的严格上界。没有做关闭插件的隔离 A/B，因而不声称精确测出了插件自身的空闲 CPU 百分比。

### Java / Kotlin / res 编译测量

| 类型与纳入轮次 | CLI 编译部署墙钟秒 | 单文件编译阶段 ms | IDE 累计 CPU 秒 | 扣除邻接空闲估算后的 IDE CPU 秒 | aapt2-inclink CPU 秒 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Java，3 轮普通热部署 | 2.847–3.244 | 535–746 | 4.36–8.54 | -0.17–6.65 | 0 |
| Kotlin，4 轮 | 2.957–4.535 | 623–2,218 | 6.58–10.78 | 3.52–7.65 | 0 |
| res，3 轮普通热部署 | 2.211–3.148 | 70–152 | 4.26–12.43 | 3.03–10.66 | 0.07–0.10 |

Java 首轮 17.658 秒，其中包含重装；res 首轮 6.507 秒，包含已有 overlay 集合的同步。两轮仍保留在 CSV 中，并未丢弃。Kotlin 首轮较慢也完整保留，没有仅挑后续较小结果。

墙钟时间从 CLI 子进程启动到退出，包含请求、编译、设备部署与重启等工作；编译阶段时间直接读取本轮 detail，二者含义不同。CPU 秒取命令窗口两端进程累计时间差；空闲估算取前后对照窗口的累计 CPU / 时间，再扣除相应窗口长度的预计后台 CPU。窗口边界按最邻近的进程样本取值，存在约一个采样间隔的误差。

IDE 是共享 JVM，GC、索引、JIT、UI 及其他插件造成显著波动。Java 出现负差分正说明空闲对照不稳定；不能把差分当作精确 Jugg CPU，不能截断负值再宣称测量稳定。资源列汇总当时两个 `aapt2-inclink` 进程的累计 CPU；另一个属于同时打开的工程，其空闲 CPU 增量为零。短命进程仍可能被 250ms 采样遗漏，原始样本保留 PID 与父 PID 供复核。

这是大工程内三个小文件的空行变化实验，验证的是增量路径和对应进程开销；不能外推为大型业务修改、结构变化、全量编译或所有工程的通用性能。RSS 峰值也只是进程观测，不能直接归为本轮 Jugg 新增的存活内存。

## 实现与文档依据

本次实际读取的知识与写作依据：

- `docs/ai_knowledge/00_overview.md`、`99_index.md`、`98_code_map.md`。
- `docs/ai_knowledge/09_plugin_runtime_debug.md`、`04_engineering_ide.md`、`02_compile_source.md`、`05_utilities.md`。
- `docs/ai_knowledge/06_testing.md`、`08_cli_tools_list.md` 相关 CLI 内容、`10_wiki_authoring.md`、`10_wiki_architecture.md`。
- `.agents/skills/wiki-writer/SKILL.md`；本机 `jugg-android-dev-loop/SKILL.md` 与 `references/flow_compile_deploy.md`。
- 中英文目标 Wiki 及 `troubleshooting/index.md`、中文 `guide/report-issue.md`。
- JOOX 的 `AGENTS.md`、`docs/AI_CONTEXT.md`、所选模块的 `docs/00_overview.md`、`99_index.md`、`98_code_map.md`。

实现核对范围：

- `GitManager`、`GitFileChangesDetector`、`FileChangesHandler`：仓库读取及文件变化处理。
- `KotlinCompiler`、`K2JVMCompilerIsolate`、`KotlinCompilerProcessRunner`：当前通常在 IDE 内运行，特定兼容场景可使用独立进程。
- `Aapt2DaemonInvoker`：资源编译独立进程；与本次进程采样对应。
- `JuggManager`、`JuggControlPanel`、`JuggHotUpdateDownloader`：初始化、任务显示及更新检查，不能表述为空闲完全不执行代码。
- `LocalGradleCompileClient`、`RemoteGradleCompileClient`：本地与远程执行边界。daemon 复用条件以 [Gradle 官方文档](https://docs.gradle.org/current/userguide/gradle_daemon.html) 为依据，不采纳“IDE 与命令行必然不共享 daemon”的绝对说法。

后续知识库候选：大型仓库现场的 JGit 缓存归因与生命周期诊断。尚未确认配置或生命周期缺陷，不将本次观测写成通用故障根因。下个会话的内存优化从 [独立交接文档](jugg-memory-optimization-handoff.md) 继续；本次尚未实施优化。

## 验证与交付

- 本任务为文档与现场测量，无生产代码改动；不新增绑定实现细节或统计噪声的单元测试。JOOX 按仓库要求不写单测。
- 替代验证为 12 轮真实增量编译部署、恢复 SHA-256 与工作区核对、JFR、双堆快照和 MAT 保留关系。
- 中文为唯一内容基准，同步英文；执行 Wiki 镜像与链接校验、production build、生成 HTML 章节检查、`git diff --check`。
- 不涉及功能或架构变化，不改 `ai_knowledge`。仅提交本次 Wiki、证据报告与 CSV；不推送、不创建 PR。
