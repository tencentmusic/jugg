# 插件运行时问题排查手册

> 最后核对：2026-08-27
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 0. AI 读取本文档时的自动行动清单

收到“排查问题”类请求且含有日志片段时，按顺序执行：

1. 从日志片段定位问题时间窗，优先精确到毫秒。
2. 向上、向下扩展完整上下文，确认前后调用链和任务状态。
3. 使用第 3 节关键词定位线程、状态、回退和耗时信号。
4. 根据 `[ClassName]` 定位症状 owner，再沿调用链确认 behavior owner。
5. 按第 4 节选择专题文档和首查证据，避免在入口手册内猜具体实现。
6. 按第 2.3 节执行反证门禁，再输出根因、调用链和修复方向；缺少直接证据时明确标记推断边界。

---

## 1. 运行时目录入口

由 `JuggPathManager` 定义，优先关注以下入口：

| 路径 | 用途 |
|------|------|
| `build/jugg/log/compile_*.log` | 主运行日志；按时间选择现场文件 |
| `build/jugg/log/compile_latest*.log` | 当前/上一份日志的 best-effort 快捷入口，可能不存在 |
| `build/jugg/build/staging/` | 本轮 dex、资源等增量产物 |
| `build/jugg/database/project_infos.db/` | IDE、Gradle 与 included build 的原始项目快照 |
| `build/jugg/database/compile_context.db/` | 完整标记、module build path、full build 信息 |
| `build/jugg/database/deploy_history.db/` | 增量部署历史与恢复状态 |
| `build/jugg/database/apk/` | APK 解析数据库 |
| `build/jugg/classpath/` | APK、依赖库和 classpath 缓存 |
| `~/.jugg/const_ref/` | 跨项目 ConstRef 缓存；不可写时回退 `${java.io.tmpdir}/jugg-<user>/` |

项目快照文件只代表各自输入源，最终合并结果保存在编译上下文内存中。路径细节以 `JuggPathManager`、`04_engineering_project.md` 为准。

---

## 2. 日志与证据边界

日志格式：

```text
[2026-03-16 16:13:27.109] [FINE   ] [ClassName] message
```

- 时间戳精确到毫秒。
- `FINE`、`INFO`、`WARNING`、`SEVERE` 分别对应 debug、info、warn 和严重异常输出。
- `[ClassName]` 可作为代码定位入口，但打印日志的类不一定决定异常行为。

### 2.1 证据层级与解释边界

| 层级 | 典型内容 | 使用边界 |
|------|----------|----------|
| 原始证据 | 异常栈、协议状态、进程状态、源码分支、命令结果、Git diff | 可直接支持其所在层级的事实，但仍需确认时间、版本和来源 |
| 派生结果 | CLI/UI 汇总文案、wrapper error、任务终态摘要、聚合状态 | 只证明生成方做出了该分类，不能直接证明其假定的底层原因 |
| 调查结论 | 根因、影响范围、版本边界、修复判断 | 必须由原始证据或已核对的生成实现支持 |

解释约束：

- 汇总结果可能合并多个底层失败；向下推断前必须检查生成实现及其输入。
- “没有搜到日志”只有在日志收集范围、时间窗、文件格式和检索方式均已确认后，才能作为缺失证据。
- 当前 HEAD 不能直接代表用户现场版本；涉及行为差异时，应核对报告版本、对应 tag/commit 或实际安装产物。
- 不同层级的信号不得互相替代。例如上层任务仍运行，只能证明该任务链局部存活，不能自动证明其依赖正常。
- 缺少直接异常栈或现场状态时，应将结论表述为“基于现有证据的推断”，并说明尚未被直接观察的环节。

### 2.2 症状 owner 与 behavior owner

打印错误、展示错误或返回汇总状态的组件是症状 owner，不一定是决定异常行为的组件：

1. 定位观察结果由谁生成，以及它消费了哪些下层结果。
2. 沿调用链找到真正决定异常行为、状态迁移或兼容分支的 behavior owner。
3. behavior owner 未确定前，不使用狭窄的 Git path filter 排除其它边界；优先按用户可见现象、关键符号或内容变化搜索历史。
4. behavior owner 确定后，再收窄到对应代码、版本、回归 owner 和修复边界。

### 2.3 结论前反证门禁

在输出确定根因或“现有材料无法确定”前，必须完成以下检查：

1. 写出当前领先结论及其直接支持证据。
2. 明确至少一项能够推翻或显著削弱该结论的可观察证据。
3. 在现有日志、源码、历史、附件和运行状态中主动查找该证据，不以未检索代替不存在。
4. 对已出现的冲突证据逐项解释；无法解释时降低结论强度或继续定位 behavior owner。
5. 核对结论是否超出证据的时间、版本、主机或调用层级边界。

反证门禁不要求穷举所有假设，也不要求固定数量的工具调用。证据缺失时，应明确缺失项和可确认的最小结论，不得伪造确定性。

---

## 3. 常用搜索词速查

| 排查目标 | 搜索关键词 |
|----------|------------|
| 编译开始 | `Jugg compile started` |
| 增量/全量判断 | `preprocessIncrementalCompile` |
| 文件变化与全量回退 | `confirmFallbackWhenNoFileChanges` / `No file changes` / `fallback` |
| EDT 与锁竞争 | `dispatching to background` / `waitCost=` / `TaskRunnerManager lock` |
| 编译后 Git 补检 | `Git check after compile is still running` / `Git recovery CRC summary` |
| APK DB 初始化 | `initAfterInstall parsed apk start` / `database all init finish` |
| 编译或部署失败 | `incremental compile error` / `SEVERE` / `deploy start` |
| UI freeze | `uiFreezeStarted` / `InvocationEvent has timed out` |
| ConstRef 启动与扫描 | `defer initial full scan` / `io throttle enabled` / `full scan progress` |
| ConstRef 降级 | `fallback to no-op const-ref` |
| IDE 启动链 | `InitialVfsRefresh` / `postInit` / `clangd` |
| release 重混淆 | `Obfuscated:` / `mapping.txt` / `NoClassDefFoundError` / `NoSuchMethodError` / `AbstractMethodError` |
| Jugg Debug attach | `Jugg Debug attach:` / `waitForClientReadyForDebug` / `Connected to the target VM` |

---

## 4. 症状路由与首查证据

本章只给排查第一跳。命中症状后读取对应专题，不在入口手册展开历史修复和单个 visitor/API 的实现清单。

| 现象 | 首查证据与解释边界 | behavior owner / 专题 |
|------|--------------------|----------------------|
| IDE 点击或操作短暂冻结 | 对齐 `idea.log` 的 freeze 时间、Jugg 日志停顿和 thread dump；日志间隔本身不能证明 Jugg 持锁 | `FileChangesDetector`、`TaskRunnerManager`；`04_engineering_ide.md` |
| 启动后长时间卡死 | 同时收集 Jugg 日志、`idea.log`、freeze dump、现场 `jcmd`；按 ConstRef、IDE startup、EDT 锁竞争分桶 | `04_engineering_ide.md`、`03_deploy_const_ref.md` |
| ConstRef SQLite corrupt | 检查损坏重建和 `fallback to no-op const-ref`；DB 异常不应扩大为 Run/compile/deploy 失败 | `ConstRefCacheDatabase`、`ConstRefEngine`；`03_deploy_const_ref.md` |
| Jugg Debug 断点不可用 | 同一时间窗确认 WAITING、`Connected to the target VM` 与最终 session 创建；“等待 debugger”不等于 VM 已连接 | `04_engineering_debug_attach.md` |
| 有改动却回退全量 Gradle | 核对 changed files、IDE 文件事件、Git 补检和 deploy history；不要先删除 history 破坏现场 | `JuggCompileHelper`、`DeployFileManager`；`02_compile_core.md` |
| 升级后 `not gradle compile yet` | 查 `complete_flag`、`module_builds.json` 版本及恢复日志；缺失 flag 不应手工伪造 | `CompileContextDb`、`BuildPathInfoSerializer`；`04_engineering_project.md` |
| `Git check after compile is still running` | 该 debug 只表示本轮不等待异步补检，不代表编译失败；持续出现才检查 Git 查询规模与历史 | `GitChangesCompileChecker`；`02_compile_core.md` |
| APK DB 初始化慢 | 对齐 APK 大小、隔离解析信号、数据库体积和实际耗时 | APK parser / database；`05_utilities.md` |
| release 增量后 runtime crash | 先确认 mapping 加载与 `Obfuscated:`，再对比 staging DEX 和 APK DEX；异常名不能单独决定映射缺口 | `DexObfuscator`、`DexMinifyCompiler`；`02_compile_obfuscation.md` |
| Kotlin `INTERNAL_ERROR` 且栈含 shaded `JavaVersion` | recreate compiler 同样失败只能增强“宿主环境”推断；继续核对宿主 JDK、项目 Kotlin 版本和兼容日志 | `KotlinCompilerHostCompat`；`02_compile_source.md` |
| Windows 命令中文乱码 | 保留原始字节链路；出现 `�` 表示可能已发生不可逆解码损失 | `ProcessOutputReader`；`04_engineering_compat.md` |

### 4.1 IDE freeze 的最小证据集

先收集：

1. 当前或最近的 `compile_*.log`。
2. 同一时间窗的 `idea.log`。
3. `threadDumps-freeze-*`。
4. 当场 `jcmd <pid> Thread.print -l`。

以 `uiFreezeStarted` 或用户感知时间为锚点，对齐 Jugg 的 active task 与 worker 栈：

- Jugg 日志存在活跃 ConstRef full scan，且 worker 栈落在 const-ref / SQLite，才支持 ConstRef 高负载判断。
- `ApplicationImpl.postInit`、`InitialVfsRefresh`、`clangd` 更活跃，而 Jugg 缺少对应工作信号时，优先检查 IDE 启动链。
- `waitCost=`、`TaskRunnerManager lock` 与 EDT 栈同时出现时，才继续检查锁竞争 owner。

源码默认值与现场日志不一致时，先核对实际安装插件版本、系统属性和运行时覆盖，不能用当前 HEAD 覆盖现场事实。

### 4.2 release runtime crash 的区分证据

| 异常模式 | 下一项区分证据 |
|----------|----------------|
| 注解/反射查找失败 | 对比 staging/APK DEX 的注解类型描述符 |
| `NoClassDefFoundError` | 检查调用方 DEX 中 `const-class`、数组、异常表等类型引用是否仍为原名 |
| `IllegalAccessError` / `IncompatibleClassChangeError` | 对比成员 access flags、direct/virtual section 和 invoke 形态 |
| 新增类、匿名类、lambda 的 `AbstractMethodError` | 检查类自身 mapping 缺失时是否能从接口/父类推导方法映射 |
| Kotlin facade 或 keep 类 `NoSuchMethodError` | 检查 R8 synthesized 条目的方法名、参数格式及恒等映射覆盖 |

这些模式的当前实现约束统一记录在 `02_compile_obfuscation.md`。仅凭异常类型或“日志中没有目标类名”不能确认具体缺口；必须核对收集范围和 DEX/mapping 证据。

---

## 5. 排查前：保存现场

在任何清理、重试、重装或再次 Run 前先备份：

```bash
BACKUP=~/Desktop/jugg_debug_$(date +%Y%m%d_%H%M%S)
mkdir -p "$BACKUP"
cp -r {projectDir}/build/jugg/log/ "$BACKUP/log/"
cp -r {projectDir}/build/jugg/database/ "$BACKUP/database/"
```

`compile_*.log` 是主日志文件；`compile_latest*.log` 只是快捷入口。

提交问题时按场景附带：

| 文件 | 路径/来源 | 适用场景 |
|------|-----------|----------|
| Jugg 主日志 | `build/jugg/log/compile_*.log` | 所有问题 |
| IDE 主日志 | `idea.log` | freeze、启动、debug attach、IDE 生命周期 |
| freeze dump / 现场线程栈 | `threadDumps-freeze-*`、`jcmd <pid> Thread.print -l` | 卡顿与死锁 |
| 项目信息 | `build/jugg/database/project_infos.db/` | 模块、variant、included build、APK 归属 |
| APK 数据库 | `build/jugg/database/apk/` | APK 解析和数据库状态 |
| 部署历史 | `build/jugg/database/deploy_history.db/` | 增量状态和恢复问题 |
| crash / logcat / 设备 overlay | 设备现场 | runtime crash、资源和部署问题 |

可使用 `tools/collect_jugg_scene.command <projectDir>` 一键保存 APK、R.jar、设备 crash/logcat、实际安装 APK 和 overlay 产物；ADB 定位过程写入 `meta/adb_resolution.txt`。资源运行时问题必须在再次 Run、重装或清数据前采集，避免 staging 和设备 overlay 被覆盖。

included build 资源 ID 与 Application / Dynamic Feature 归属问题分别按 `02_compile_source.md`、`04_engineering_project.md` 的排查入口继续，不在本文重复项目模型和 classpath 规则。

---

## 6. 运行时修复验证流程

测试价值、TDD、L0～L3 和测试落点以 `06_testing.md` 为唯一权威。本手册只补充运行时问题的证据要求：

1. 修改前保存稳定失败证据，记录现场版本、宿主环境、时间窗和可重复操作。
2. 先确定 behavior owner 和失败边界，再选择自动化测试或真机/IDE/外部进程替代验证。
3. 自动化只能绑定私有实现或要求测试专用 seam 时，不新增测试；保留异常日志、复现步骤和判定标准。
4. 修复后回到同一失败边界验证，并补充未命中修复条件的正常路径证据。
5. 输出结论前再次执行第 2.3 节反证门禁，确认修复标志与用户可观察结果一致，不能用单条新增日志代替结果验证。

---

## 7. 关联文档

- 编译主流程与回退：`02_compile_core.md`
- 源码/Kotlin/Dex：`02_compile_source.md`
- release 混淆映射：`02_compile_obfuscation.md`
- ConstRef：`03_deploy_const_ref.md`
- IDE 生命周期：`04_engineering_ide.md`
- Jugg Debug attach：`04_engineering_debug_attach.md`
- 项目快照与 APK 归属：`04_engineering_project.md`
- 兼容层与命令输出：`04_engineering_compat.md`
- 测试与验证：`06_testing.md`
