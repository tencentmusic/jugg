# Jugg 内存优化交接：JGit 缓存归因与验证方法

> 后续进展：Repository 关闭修复与 JOOX 大仓库耗时验证已完成，见 [生命周期修复与验证结果](jgit-repository-lifecycle.md)。本页保留优化前的现场和原始交接边界。

日期：2026-10-15。状态：已完成现场取证，尚未实施内存优化。下个会话从 JGit 缓存的实际组成、配置与生命周期继续调查，不必重做 CPU QA 采样。

## 已确认的结果

| 统计对象 | 首次快照 | 编译实验后快照 |
| --- | ---: | ---: |
| Jugg 主插件组件保留集合 | 541.20 MiB | 437.25 MiB |
| 其中 JGit `WindowCache` 静态类保留树 | **493.39 MiB** | **398.08 MiB** |
| Jugg 自有包对象的保留集合 | 30.88 MiB | 35.60 MiB |
| Kotlin 隔离类加载器的保留集合 | 9.40 MiB | 10.80 MiB |

这些范围有包含和重叠，不能相加。保留集合是这些根独占保留的可达堆对象，包含部分关联依赖；不等于浅堆大小、进程 RSS，也不包含全部共享对象、metaspace 与 native 内存。自有包集合不能当成纯业务状态的精确大小，Kotlin 一行也不能当成所有编译器的总成本。

当前主要线索是 **JGit 缓存，而非编译器缓存**。已确认的保留关系为：

```text
Jugg 主 PluginClassLoader：0x5854a5740
  -> 已加载类列表：0x5854a58e8
  -> org.eclipse.jgit.internal.storage.file.WindowCache：0x598eab148
  -> 保留树：517,361,552 / 417,415,160 字节
```

地址只用于本次两份旧快照；新快照必须重新定位。源码 [GitManager.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt) 使用 JGit 读取仓库，与该加载器归属相符。但尚未沿静态字段继续拆解，不能把整棵树都称为 Git pack 窗口数据，也未确认触发其规模的具体调用。

实验后，IDE Java 堆 used 从 GC 前约 4.28 GiB 降至 GC 后约 2.59 GiB，committed heap 约 5.63 GiB；实验期间 RSS 约 6.1–7.8 GiB。RSS 增长不能直接归因于 Jugg。两次 JGit 保留树下降也不能证明长期没有泄漏。

## 环境与现场状态

- JOOX Android，维护者说明约 300 万行代码；同时打开 JOOX、jojo、Jugg，共享一个 IDE 进程。
- Apple M3 Pro，12 个逻辑核，36 GiB；macOS 26.6.2（25G83）。
- Android Studio Panda 4 2025.3.4 Patch 1，build `AI-253.32098.37.2534.15336583`；JBR `21.0.11+10-b1163.116`。
- 实际安装 Jugg `3.5.4-release`。idea JAR SHA-256：`071ab0aec2d25e5a0c8100793c0582a2bf3785158d2068d1b7e9140fc862982e`。
- 初始源码 HEAD 为 `e274ad1ca841a11ed5d7d16b22a7669629d8e0a4`；之后存在并行开发及本次文档提交。源码 HEAD、安装包、热更新版本必须分别核对，不能混用。
- 已完成 Java / Kotlin / res 各 4 轮空行修改与恢复，12 轮增量编译部署全部成功。三份实验源文件的 SHA-256 已恢复，用户原有改动保留。完整实验记录见 [资源取证报告](jugg-resource-usage-evidence.md)。
- 原 IDE PID 为 `65842`，下个会话重新确认，不能直接复用。

## 原始证据的位置

持久归档目录：`/Users/wormchen/.jugg/investigations/resource-usage-20261015/`。

原仓库 `build/reports/resource-usage-20261015` 已改为指向归档的符号链接。即使后续 clean 删除这个链接，归档仍在用户目录中。目录只在本机存在，包含商业工程数据，不随 Git 提交。

| 文件 | 用途 |
| --- | --- |
| `idle.hprof`、`post-trials.hprof` | 两份原始堆快照，约 3.73 / 3.63 GB；同目录已有 MAT 索引 |
| `environment.json` | 安装版本、机器、初始源码与 JOOX HEAD |
| `memory-summary.json` | 两次快照的精确字节数 |
| `SHA256-manifest.json` | 原始归档文件的大小及 SHA-256 |
| `idle_Top_Components.zip` | 首次快照的 MAT Top Components 报告 |
| `idle_jugg_owners.zip`、`post-trials_jugg_owners.zip` | 自有对象、管理器与编译器加载器归属 |
| `idle_jugg_loader.zip` | 主插件加载器出向引用、编译器根集合 |
| `idle_jugg_large_roots.zip` | 首次快照按保留大小排序的已加载类 |
| `post-trials_final_memory.zip` | 实验后组件、JGit、自有包及 Kotlin 汇总 |
| `memory-owners.xml`、`loader-owners.xml`、`large-roots.xml`、`final-memory.xml` | 上述定向 MAT 查询的输入 |
| `mat/MemoryAnalyzer.app/Contents/Eclipse/` | 已下载的 Eclipse MAT 1.16.1，可复用 |

两份堆快照 SHA-256：

```text
idle.hprof
f4f7bd913e7332d021795a173ca582039197ca8e7714c2e5cc9724f6d6ebb652
post-trials.hprof
42c623dbdd12319b2d92de3021fbd563c738b7251fce420b581deca40f69046c
```

原始采集、汇总脚本仍写着旧 `/tmp/jugg-resource-evidence-20261015` 路径及旧 PID，复用前必须调整。旧目录已迁走，不应重新在那里开始采集。

## 本次采用的分析方法

1. **先区分统计对象。** 用进程 RSS 看现场总压力，用 `GC.heap_info` 看 Java 堆 used / committed，再用堆快照定位存活对象的归属；不从 RSS 差值直接推导插件成本。
2. **从插件组件找大头。** 通过插件标识、管理器所属类加载器及 MAT Top Components 定位 Jugg。只筛选 `com.sickworm.intellij.jugg.*` 会漏掉 JGit 等依赖。
3. **沿 dominator / 出向引用追到静态根。** 从主加载器的已加载类列表按 retained heap 排序，定位到 `WindowCache`；不是看到类加载器大就判断为 JAR 加载缓存。
4. **用多种范围交叉核对。** 自有包、主组件、Kotlin 隔离加载器和 JGit 静态根分别计算保留集合，保留重叠关系，不将它们累加。
5. **比较前后快照。** 在明确的工作负载前后，以相同采集方式比较；GC、heap dump 和 MAT 解析均不放进编译计时窗口。现有两张快照适合找线索，尚不足以证明长期增长或优化收益。

旧快照中已验证的 MAT 查询：

```text
# 自有包对象及独占保留数据
show_retained_set com.sickworm.intellij.jugg.*

# 当前 Kotlin 隔离加载器范围
show_retained_set com.sickworm.intellij.jugg.compiler.source.kotlin.PriorityURLClassLoader

# JGit 静态类保留量
oql "SELECT c, c.@retainedHeapSize FROM OBJECTS org.eclipse.jgit.internal.storage.file.WindowCache c"

# 本次主加载器的直接出向引用
oql "SELECT x, x.@retainedHeapSize FROM OBJECTS (SELECT OBJECTS outbounds(l) FROM OBJECTS 0x5854a5740 l) x"
```

组件集合的完整查询在 `final-memory.xml`，它还纳入归属该加载器的顶层 dominator；仅看加载器单个对象的 retained heap 与此范围不同。表格总数应读取 MAT 的 Total 行，不能只加显示出来的前 30 行。

复用现有索引和查询的命令示例：

```bash
MEM_EVIDENCE="$HOME/.jugg/investigations/resource-usage-20261015"
MAT_ECLIPSE="$MEM_EVIDENCE/mat/MemoryAnalyzer.app/Contents/Eclipse"
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" -Xmx3g \
  -jar "$MAT_ECLIPSE/plugins/org.eclipse.equinox.launcher_1.6.900.v20240613-2009.jar" \
  -data "$MEM_EVIDENCE/mat-workspace-next" \
  -consolelog -nosplash -application org.eclipse.mat.api.parse \
  "$MEM_EVIDENCE/post-trials.hprof" "$MEM_EVIDENCE/final-memory.xml"
```

本次首次解析使用过 `-Xmx6g`，已有索引上的定向报告用 `-Xmx3g` 完成。再次生成报告会使用同名输出，新增调查建议先复制 XML 并更换 `filename_suffix`。指定独立 `-data`，避免在源码工作区生成 Eclipse 的 `workspace/`。优先定向查询，完整 Top Components 会做大量额外分析，耗时与输出明显更多。

## 尚未确认的问题

- 实际安装包使用的 JGit 版本、运行时缓存配置及缓存实例数量。
- `WindowCache` 保留树内部，pack 窗口、索引、引用队列、仓库对象等各占多少；哪些是软引用相关保留，哪些存在其他强引用。
- 缓存是否符合配置上限、是否在多次读取后达到稳定平台、关闭工程后是否按预期释放。
- `GitManager` 及其调用方的 Repository / Reader 生命周期是否完整，是否存在未关闭实例；尚未据此确认资源泄漏。
- 单开 JOOX 与同时打开三个工程的差异，以及不同仓库规模的影响。

此前在 `main/src/main`、`idea/src/main` 搜索 `WindowCache|WindowCacheConfig|DfsBlockCache|setPackedGit` 未命中。它只说明当时这些源码目录中未找到显式配置，不能推出依赖库的默认值或实际运行时上限。

## 下个会话的推进顺序

1. 读取本文件、仓库必读知识与 [原报告的依据清单](jugg-resource-usage-evidence.md#实现与文档依据)，检查当前工作区与实际插件版本。保留原始快照，新的测量用独立文件名。
2. 先在旧快照中拆开 `WindowCache` 的静态字段和最大子树，记录类型、对象数、字节数、引用强弱与归属仓库；再核对实际 JGit 版本及配置来源。优先完成这一步，不急于重新做完整编译实验。
3. 在 Android Studio 已打开的工程中，使用 `android-studio-index` 导航 `GitManager` 及其调用方，每次传入 Jugg 的绝对 `project_path`。检查创建、复用、读取和关闭的实际边界，再判断配置还是生命周期值得改动。
4. 按发现选择最小措施：配置超出需求才评估缓存限额；已证实资源未关闭才修复关闭边界；调用导致不必要大范围读取才缩小读取范围。先明确配置作用域及跨工程影响，不预设清空缓存或强制 GC 是最终方案。
5. 实施前记录同一工程、同一插件环境下可重复的基线：初始化完成后的内存、固定次数 Git 读取后的内存、空闲及关闭工程后的内存，同时记录读取和 Jugg 初始化耗时。新快照可用 JBR `jcmd <当前PID> GC.heap_info` / `GC.heap_dump <新文件绝对路径>` 采集，统一是否触发 GC 的条件，并记录快照停顿。
6. 用相同工作负载验证优化，报告 JGit 和插件组件保留量减少多少、是否达到稳定平台、Git 操作是否变慢、变化检测和增量编译是否仍正确。不要只展示一次较小的 RSS。

完成标准：根因或合理缓存成本有明确证据，补丁与该边界匹配，内存及耗时可对照，正常变化检测与增量编译无回归。测试价值与 owner 遵循 `06_testing.md`；JOOX 不新增单元测试。当前不预设收益目标或具体修复方案。

## 本次交接的边界

只新增交接文档、补充原报告入口并持久保存证据；未修改 JGit 配置、Repository 生命周期或任何插件实现，未重启 IDE、未执行新编译。daemon 与默认 `--no-daemon` 的独立改动不在本次内存优化范围。
