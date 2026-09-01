# 插件运行时问题排查手册

> 最后核对：2026-08-31
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

```
build/jugg/                            # juggRootDir
├── log/                               # 日志目录
│   ├── compile_latest.log             # 当前主日志的 best-effort 快捷入口
│   ├── compile_latest-1.log           # 上一份主日志的 best-effort 快捷入口
│   ├── compile_YYYY-MM-DD_HH-mm-ss.0.log
│   └── standlone_cli/                  # standalone Runtime 独立日志目录
│       ├── compile_latest.log
│       ├── standalone_startup.log       # CLI 启动 daemon 时捕获的 stdout/stderr
│       └── compile_YYYY-MM-DD_HH-mm-ss.0.log
├── build/staging/                     # 本次增量编译输出（dex/资源）
├── database/
│   ├── apk/                           # APK 解析后的 SQLite DB（*.db）
│   ├── project_infos.db/              # 模块/APK 配置快照
│   │   ├── project_infos.json
│   │   └── gradle_project_infos.json
│   ├── compile_context.db/            # classpath、模块信息
│   │   ├── complete_flag               # compile context 完整写入标记
│   │   ├── module_builds.json          # module build path 快照
│   │   ├── full_build_info.json        # Gradle full build 命令、BuildTarget、写入时间
│   └── deploy_history.db/             # 部署历史（增量恢复）
├── classpath/
│   ├── root/                          # classpath jar
│   ├── apk/                           # APK 文件缓存
│   └── libraries/                     # 依赖库备份
├── config/
│   ├── custom_compilers/
│   ├── agent_setup.md
│   └── jugg-android-dev-loop/
└── tmp/diff/                          # 远程编译 diff 结果

${projectRoot}/.gradle/jugg/
├── readProjectInfo.gradle.kts
└── jugg-runtime.jar

~/.jugg/const_ref/                     # 跨项目常量引用缓存（全局）
~/.jugg/locks/global.lock              # IDEA / standalone 全局写锁
~/.jugg/hot_update/                    # 已校验 update jars、hot_update_data.json、load_manifest.json
```

**代码位置**：`main/src/main/java/.../project/runtime/JuggPathManager.kt`

当前 `reportIssue()` 继续通过 `ProjectInfoReader.printInfo()`、设备 logcat dump 和 `JuggServer.reportAndUploadLogs()` 收集信息，不额外生成 runtime diagnostics JSON。IDEA 与 standalone 两个日志目录各自保留最近 10 份；上报时按修改时间合并，仅选择最新 10 份，并以 `diagnostics/logs/standlone_cli/` 标识 standalone 来源。standalone doctor/report 在真实命令入口落地时再设计共享诊断模型。

standalone 主日志按工程 runtime 生命周期分段：工程初始化时创建，普通 `compile/deploy` 继续追加；成功完成 Gradle 全量构建并开始重建编译上下文时重新分段。`standalone_startup.log` 每次 CLI 发起 daemon 启动时覆盖写入，只服务于启动失败的即时诊断。

---

## 2. 日志与证据边界

日志格式：

```text
[2026-03-16 16:13:27.109] [FINE   ] [ClassName] message
```

- 时间戳精确到**毫秒**
- 级别：`FINE`=debug / `INFO` / `WARNING` / `SEVERE`
- `[ClassName]` 由 `logger.getInstance("ClassName")` 决定，可直接作为代码定位依据
- 日志来源由目录区分：`log/` 是 IDEA，`log/standlone_cli/` 是 standalone；出现 `Runtime lock contention` 与 `Runtime lock acquired after contention` 时，可按 `runtime`、`ownerRuntime`、`ownerPid`、`ownerCommand`、`ownerJobId` 和 `waitMs` 还原交替持锁时序。

### 2.1 证据层级与解释边界

| 层级 | 典型内容 | 使用边界 |
|------|----------|----------|
| 原始证据 | 异常栈、协议状态、进程状态、源码分支、命令结果、Git diff | 可直接支持其所在层级的事实，但仍需确认时间、版本和来源 |
| 派生结果 | CLI/UI 汇总文案、wrapper error、任务终态摘要、聚合状态 | 只证明生成方做出了该分类，不能直接证明其假定的底层原因 |
| 调查结论 | 根因、影响范围、版本边界、修复判断 | 必须由原始证据或已核对的生成实现支持 |

| 排查目标 | 搜索关键词 |
|---------|-----------|
| 编译开始 | `Jugg compile started` |
| 增量/全量判断 | `preprocessIncrementalCompile` |
| 无文件变化弹框 | `confirmFallbackWhenNoFileChanges` |
| EDT 异步派发（文件变化） | `dispatching to background` |
| 编译后 Git 补检未完成 | `Git check after compile is still running` |
| 锁等待耗时 | `waiting for TaskRunnerManager lock` / `waitCost=` |
| APK DB 初始化 | `initAfterInstall parsed apk start` / `database all init finish` |
| SQLite 查询 | `getClassNodes` |
| 部署开始 | `deploy start` |
| 编译耗时 | `cost ${costTime}ms` |
| 回退原因 | `fallback` / `Fallback` |
| 编译失败 | `incremental compile error` / `SEVERE` |
| standalone 远程认证 | `Standalone Runtime is non-interactive` / `remote login` |
| 远程 shell 安全握手 | `failed to disable remote shell echo` / `Remote shell echo could not be disabled safely` |
| 远程同步与产物拉取 | `Sync file` / `Fetch` / `RemoteGradleCompileClient` |
| UI freeze 起点 | `uiFreezeStarted` / `InvocationEvent has timed out` |
| ConstRef 启动延后 | `ConstRefEngine defer initial full scan until startup stabilizes` |
| ConstRef 限速实值 | `ConstRefEngine io throttle enabled` |
| ConstRef 全扫进度 | `ConstRefEngine full scan progress` |
| ConstRef 降级 | `fallback to no-op const-ref` |
| IDE 启动链 | `InitialVfsRefresh` / `postInit` / `clangd` |
| 重混淆结果 | `Obfuscated:` |
| 重混淆注解问题 | `visitAnnotation` / `mapType` |
| 重混淆类型引用遗漏 | `const-class` / `filled-new-array` / `NoClassDefFoundError` |
| 重混淆 access flag 宽化 | `widenAccessFlags` / `invoke-direct` / `IllegalAccessError` / `AbstractMethodError` / `IncompatibleClassChangeError` / `ExternalSyntheticLambda` |
| Jugg Debug attach | `Jugg Debug attach:` / `waitForClientReadyForDebug` / `Debugger is waiting for application to start` / `Connected to the target VM` |

standalone remote compile 失败时，先读取 `{projectDir}/build/jugg/log/standlone_cli/compile_latest.log`，按 `RemoteGradleCompileClient` 的 command id 串联登录、同步、Gradle 与产物拉取阶段。出现 `Standalone Runtime is non-interactive` 表示配置中缺少可直接使用的 SSH 凭据或 iFT 仍需交互认证，应先在 IDEA/profile 或外部 iFT 客户端完成配置。日志不会保留原始远程 command，环境变量也只有白名单路径值可见；排查时不应要求用户上传明文密码或完整环境。

---

### 2.2 症状 owner 与 behavior owner

打印错误、展示错误或返回汇总状态的组件是症状 owner，不一定是决定异常行为的组件：

1. 定位观察结果由谁生成，以及它消费了哪些下层结果。
2. 沿调用链找到真正决定异常行为、状态迁移或兼容分支的 behavior owner。
3. behavior owner 未确定前，不使用狭窄的 Git path filter 排除其它边界；优先按用户可见现象、关键符号或内容变化搜索历史。
4. behavior owner 确定后，再收窄到对应代码、版本、回归 owner 和修复边界。

**排查步骤**：
1. 找停顿区间（两条日志时间戳差值 > 100ms 且无中间日志）
2. 搜 `waitCost=` 确认是否有锁等待
3. 搜 `dispatching to background` 确认 EDT 调用是否正确派发
4. 检查 `@Synchronized` 方法是否可能被 EDT 直接调用

**已知根因**（已修复，供参考）：
- `FileChangesDetector.afterVfsChange()` 在 EDT 调用 `DeployFileManager.addChangedFile()`，与编译线程持有的 `@Synchronized` 锁竞争，导致 EDT 阻塞 ~150ms
- 修复：EDT 调用时通过 `TaskRunnerManager.runBackgroundSafe()` 异步派发
- `processFileChanged()` 与 `tryCreateRunConfigurations()` 曾共同使用 `JuggManager` 实例锁；后台目录扫描长时间持锁时，Gradle Sync 的 EDT 回调会阻塞在 Run Configuration 创建入口
- 修复：文件变化处理与 Run Configuration 创建使用两个独立锁，只保留各自业务域内的串行语义
- VFS 目录事件可能包含工程无关的全局目录，旧实现会先递归 `listFiles()`，再逐文件判断是否属于 Jugg 变更范围；多工程并行时会重复扫描并长时间占用文件变化处理锁
- 修复：`FileChangesHandler` 在展开目录前按 IDE 工程目录与编译模块根目录剪枝，工程外模块仍纳入范围

**关键类**：
```
idea/.../project/change/FileChangesDetector.kt # VFS 事件监听（afterVfsChange 在 EDT）
main/.../deploy/DeployFileManager.kt          # addChangedFile / removeChangedFile
main/.../project/runtime/TaskRunnerManager.kt # 后台派发、isOnEdt、项目/全局锁和 Job 生命周期
idea/.../runtime/HostTaskExecutor.kt          # ApplicationManager.isDispatchThread 与 IDEA Task 执行/进度
```

### 4.1.1 启动后长时间卡死（`postInit / InitialVfsRefresh / clangd / ConstRef` 竞争）

**先收集四份证据**：
1. `build/jugg/log/compile_latest.log` 或最近的 `compile_*.log`
2. IDE `idea.log`
3. `threadDumps-freeze-*`
4. 一份当场 `jcmd <pid> Thread.print -l`

1. 写出当前领先结论及其直接支持证据。
2. 明确至少一项能够推翻或显著削弱该结论的可观察证据。
3. 在现有日志、源码、历史、附件和运行状态中主动查找该证据，不以未检索代替不存在。
4. 对已出现的冲突证据逐项解释；无法解释时降低结论强度或继续定位 behavior owner。
5. 核对结论是否超出证据的时间、版本、主机或调用层级边界。

反证门禁不要求穷举所有假设，也不要求固定数量的工具调用。证据缺失时，应明确缺失项和可确认的最小结论，不得伪造确定性。

---

## 3. 常用搜索词速查

**当前期望行为**：
- `DeployFileManager` 可直接创建 `ConstRefEngine`，但 `ConstRefEngine` 构造不应初始化 SQLite runtime；`JuggManager.<init>` 不应因 ConstRef DB 异常失败。
- `ConstRefCacheDatabase` 初始化或运行期 DB 操作遇到损坏库时会重建 `~/.jugg/const_ref/const_ref_shared.db` 及其 WAL/SHM；运行期只重试触发损坏的原操作一次。
- 若 DB 重建或 `RepoSharedFingerprintStore` 初始化仍失败，日志应出现 `fallback to no-op const-ref`，后续编译/部署按无 ConstRef 继续。

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
| `source_files.db` 每次启动都重建 | 检查 rebuild stamp、删除失败与 `SQLITE_BUSY`；不要使用 DB creation/modified time 判断最近重建 | `SourceFileManager`、`SourceFileDatabaseSqLiteHelper`；本节 4.3 |
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

### 4.3 `source_files.db` 每次启动都重建

**信号**：IDEA 或 standalone 初始化时反复出现 `source file db is too old, recreate database`，源码索引扫描耗时被重复放大，随后可能出现 `SQLITE_BUSY`。

**当前期望行为**：
- Git 补检在增量编译前异步启动，用于发现 IDE 文件事件遗漏的磁盘修改。
- 编译结束后只消费已经完成的补检结果，不等待仍在运行的查询。
- 查询未完成时仅记录 debug，当前编译和部署继续；迟到结果不触发本轮二次编译，也不会被后续 Run 误读。
- 后台查询可以自然完成，其文件刷新结果可进入后续 Run 的待编译状态。

**排查步骤**：
1. 搜 `gitManager.getChangedFiles` 与 `gitManager.getUncommittedFiles`，区分 commit diff 和工作区扫描耗时。
2. 搜 `Git recovery CRC summary`，确认候选文件和历史 CRC 规模。
3. 该日志本身不表示本次 Run 失败；只有持续高频出现时才继续检查仓库规模、未跟踪文件和部署历史。

### 4.3 APK 数据库初始化慢

**信号**：`database all init finish, cost Xms` 中 X > 3000。

**排查步骤**：
1. 确认 APK 大小（`build/jugg/classpath/apk/`）
2. 搜 `APK size exceeds threshold` 确认是否触发了隔离进程解析
3. 检查 `build/jugg/database/apk/` 下 db 文件大小

### 4.3.1 `source_files.db` 每次启动都重建

**信号**：IDEA 或 standalone 初始化时反复出现 `source file db is too old, recreate database`，源码索引扫描耗时被重复放大，随后可能出现 `SQLITE_BUSY`。

**当前期望行为**：
- 最近一次完整重建时间保存在 `build/jugg/database/source_files.rebuild_at`，不使用 DB 的 creation time 或 last modified time。
- stamp 只在数据库创建或重建、schema 初始化、`updateSourceDirs()` 完整提交后更新；普通增量 `updateFiles()` 不刷新。
- 老版本 DB 缺少 stamp、stamp 损坏、超过 14 天或明显位于未来时完整重建一次；重建失败不更新 stamp。
- 删除旧 DB 失败时必须明确失败，不能继续在原文件上伪装重建成功。
- `Clear Jugg Build` 会同时删除 DB 与 stamp，重新打开项目后按新库正常初始化。

**排查步骤**：
1. 检查 `source_files.db` 与 `source_files.rebuild_at` 是否同时存在。
2. 搜 `source file db rebuild stamp` / `source file db daysSinceRebuilt`，确认是缺失、损坏、未来时间还是超过 14 天。
3. 搜 `Failed to delete database` 与 `SQLITE_BUSY`，并对齐 IDEA、`standlone_cli` 日志，确认是否有另一 Runtime 正在写入。
4. 不要用 creation time 或 last modified time 人工修复 stamp；需要恢复时使用 `Clear Jugg Build`，或关闭相关 Runtime 后删除 `source_files.db` 与 `source_files.rebuild_at`。

**关键类**：
```
main/.../deploy/data/SourceFileManager.kt
main/.../deploy/data/SourceFileDatabaseSqLiteHelper.kt
```

### 4.4 release 增量编译后注解类型不匹配 crash

**信号**：runtime crash 报某类 "has no public methods with @Subscribe annotation" 或其他注解查找失败（如 `EventBusException`、Dagger/Hilt 注入失败等注解类型不匹配异常）。

**排查步骤**：
1. 检查 `source_files.db` 与 `source_files.rebuild_at` 是否同时存在。
2. 搜 `source file db rebuild stamp` / `source file db daysSinceRebuilt`，确认是缺失、损坏、未来时间还是超过 14 天。
3. 搜 `Failed to delete database` 与 `SQLITE_BUSY`，并对齐 IDEA、`standlone_cli` 日志，确认是否有另一 Runtime 正在写入。
4. 不要用 creation time 或 last modified time 人工修复 stamp；需要恢复时使用 `Clear Jugg Build`，或关闭相关 Runtime 后删除 `source_files.db` 与 `source_files.rebuild_at`。

**关键类**：
```
main/.../deploy/data/SourceFileManager.kt
main/.../deploy/data/SourceFileDatabaseSqLiteHelper.kt
```

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
