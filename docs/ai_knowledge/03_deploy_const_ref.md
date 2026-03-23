# Jugg 常量引用影响分析（ConstRefEngine / ConstRefAnalyzer）

> 文档版本: v1.4  
> 创建时间: 2026-02-22  
> 更新时间: 2026-03-23  
> 涵盖模块: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/*`、`main/src/main/java/com/sickworm/intellij/jugg/deploy/data/*`  
> 一致性提示: 如文档与代码不一致，以代码为准。

---

## 一、模块定位

`ConstRefEngine` + `ConstRefAnalyzer` 负责解决一个补充场景：

- 传统 `ClassNode` 结构差异分析更偏向“字节码结构变化导致的受影响类”；
- 但 `const` 变更可能在调用侧被编译器内联，结构变化不一定能完整覆盖；
- 因此新增“常量定义/引用索引”链路，用于产出 `const` 变更导致的受影响源码文件列表。

最终，这些源码路径会进入 `JuggDeployData.constRefEffectedSourcePaths`，并与原有受影响源码集合合并参与重编译。

---

## 二、主流程（接入点）

### 2.1 写入与删除事件

入口在 `DeployFileManager`：

- `addChangedFile()` 对 Java/Kotlin 调 `constRefEngine.onFileSaved(path)`；
- `removeChangedFile()` 调 `constRefEngine.onFileDeleted(path)`；
- `updateModuleInfos()` 调 `constRefEngine.initializeFullScan(sourceDirs)` 触发冷启动全量扫描。

### 2.2 重编译决策阶段

`DeployFileManager.getRecompileFiles()` 将 `changedSourcePaths` 透传给：

- `DeployDataGenerator.buildDeployData(..., constRefChangedSourcePaths = changedSourcePaths)`

在 `DeployDataGenerator` 内：

1. 先 `ensureReadyForRecompile()` 等待分析就绪（超时可降级）；
2. 再 `getEffectedFiles()` 查询受影响引用文件；
3. 结果写入 `JuggDeployData.constRefEffectedSourcePaths`；
4. 回到 `DeployFileManager` 与 `effectedClassNodes` 解析出的源码集合合并去重。

---

## 三、ConstRefEngine 关键设计

### 3.1 责任

- 串行调度分析任务（避免并发解析冲突）；
- 管理“当前编辑文件/待分析队列/分析完成时间戳”；
- 以 `ConstRefCacheDatabase` 为主数据源，结合 `ConstRefSessionCache`（LRU+TTL）做会话热点缓存；
- 维护三层 checksum 命中链路：`mtime-map -> RepoSharedFingerprintStore -> CRC32`；
- 分析锁使用协程 `Mutex`，按文件粒度加锁；IO 节流在锁外 `delay()`，避免长时间持锁阻塞；
- 在预编译点触发 `PRE_COMPILE` 分析任务冲刷待分析队列；`awaitAnalysis` 只在超时窗口内等待状态，不会因场景锁竞争无限阻塞；
- 异步触发 `ConstRefCacheCleaner` 做 TTL/版本上限清理，不阻塞主流程；
- 通过 `ConstRefChangeTracker` 追踪“真实变更 key + 已删除 key”，避免空白改动触发全量常量引用重编译；
- 通过 `ConstRefImpactResolver` 统一执行受影响文件查询与过滤。

### 3.2 三个分析场景

- `FULL_SCAN`：模块 `sourceDir` 初始化后异步全量扫描与落库（命中可复用缓存则跳过解析）；当前实现里**首次** `initializeFullScan()` 会先经过启动稳定宽限期，再真正启动扫描。
- `FILE_CHANGE`：日常保存触发的异步增量分析；
- `PRE_COMPILE`：`awaitAnalysis()` 内异步触发执行，用于尽量冲刷待分析队列且不阻塞超时预算。

> `FULL_SCAN` 会先按 `(worktree_key, relative_path, last_modified)` 批量查询 DB 缓存命中，命中项仅更新就绪状态，未命中项才进入解析流程。
> `FULL_SCAN` 使用 `Dispatchers.IO.limitedParallelism(1)` 与默认 dispatcher 隔离，避免挤占 IDE 常规后台任务。
> 当前主干代码中，启动稳定宽限期由 `ConstRefEngine.startupStabilizationDelayMs` 内部字段提供，默认值 `10000ms`；不是 `TaskRunnerManager` 注入。若历史方案文档与此冲突，以代码为准。

### 3.3 状态模型（核心字段）

- `pendingAnalyzeFiles`：待分析文件集合；
- `currentEditingFile`：当前编辑中的最后一个文件（避免每次按键都解析）；
- `analyzedAt`：文件最近分析完成时间；
- `ConstRefChangeTracker.changedDefinitionKeys`：文件真实变化的 `(fqClassName, constName)` 集；
- `ConstRefChangeTracker.removedDefinitionKeys`：文件删掉的 `(fqClassName, constName)` 集；
- `trackedSourceDirs` + `fullScanReadySourceDirs`：全量扫描目录与就绪标记；
- `sessionCache(fileCache+lookupCache)`：会话热点缓存。

### 3.4 核心 API 行为

| API | 行为摘要 |
|---|---|
| `onFileSaved(path)` | 仅处理 `.java/.kt`；将“前一个编辑文件”推入待分析队列，当前文件仅标记为 editing。 |
| `awaitAnalysis(paths, timeout)` | 冲刷 `currentEditingFile` 到待分析；触发 `PRE_COMPILE` 异步分析；当前代码中 `shouldSkipFullScanRequirement()` 始终返回 `true`，因此等待条件实际只要求目标文件 `analyzedAt` 达标，`FULL_SCAN` 不再阻塞编译就绪。 |
| `analyzeOnDemand(paths)` | 同步按需分析入口：优先复用已有 checksum 分析结果；缺失或内容变化时立即同步分析并返回，不依赖等待超时窗口。 |
| `initializeFullScan(sourceDirs)` | 注册目录后异步扫描源码，构建 definitions/references 索引；首次调用会先记录 `defer initial full scan` 日志并延后 `10000ms` 再启动。 |
| `onFileDeleted(path)` | 立即清理内存状态与变更跟踪；数据库删除改为后台队列异步执行（含前缀删除），避免在调用线程（如 EDT）上等待 DB 锁。 |
| `getEffectedFiles(changedPaths)` | 基于 `changedDefinitionKeys` + `removedDefinitionKeys` 查询引用文件；仅返回本地存在且不在 `changedPaths` 的文件。 |

### 3.5 路径与注入

- `ConstRefEngine` 不直接拼接路径；
- `DeployFileManager` 从 `JuggPathManager` 注入：
  - `constRefSharedDbFile`：`<PathManager.system>/jugg/const_ref/const_ref_shared.db`
  - `repoFingerprintDbFile`：`<PathManager.system>/jugg/const_ref/repo_fingerprint.db`

---

## 四、ConstRefAnalyzer 能力边界

`ConstRefAnalyzer` 是语言无关封装层：

- Java 走 `JavaConstParser`；
- Kotlin 走 `KotlinConstParser`；
- 支持输入去重、只处理存在且扩展名合法的源码文件；
- 提供 `collectReferenceLookupHints()`，用于收集 constName/class/package/simpleName 线索，支撑 DB 候选查询；
- `dispose()` 负责释放 Kotlin PSI 环境资源。

### 4.1 定义解析（Definition）

`ConstDefinition` 字段：

- `filePath`, `packageName`, `fqClassName`, `constName`, `constType`, `constValue`

Java 侧（`JavaConstParser`）：

- 仅记录可内联类型的 `static final` 字段（含 `String`）；
- 支持嵌套类、接口字段、注解字段。

Kotlin 侧（`KotlinConstParser`）：

- 支持 top-level `const val`（归属 `FileKt`）；
- 支持 `object`、类内 `companion object`、嵌套 `class/object` 的 `const val`。

### 4.2 引用解析（Reference）

`ConstReference` 字段：

- `refFilePath`, `defFqClassName`, `constName`

Java 引用覆盖：

- `Owner.CONST` 字段访问；
- `import static A.B` / `import static A.*`；
- 忽略注释和字符串文本。

Kotlin 引用覆盖：

- `Owner.CONST`；
- 显式常量导入、类导入、包/类星号导入；
- `as` 别名导入；
- 同包 top-level 常量无导入引用；
- 忽略注释和字符串文本。

---

## 五、索引与持久化

### 5.1 查找模式与内存缓存

- 不做全量预加载；每个待解析文件先用线索回源 DB 查询候选 definitions，再构建临时 `ConstDefinitionIndex` 仅用于当前文件引用解析。
- `db_session` 模式下新增短路：当单文件 `collectReferenceLookupHints()` 为空，或候选+overlay definitions 为空时，直接返回空 references，不再执行二次 `parseReferences`。
- `ConstRefSessionCache`：
- `fileCache`：缓存会话内已访问文件的 definitions/references（用于增量 diff 快速命中）；
- `lookupCache`：缓存 `constName / class+const / package+const / simpleClassName` 查询结果；
- 采用 LRU+TTL，缓存失效后统一回源 DB，语义不变；
- 过期清理采用惰性节流（默认 60s 一次），读取时仍逐条校验 TTL，不返回过期数据。

### 5.2 SQLite 缓存：ConstRefCacheDatabase

核心表：

- `file_checksum_mtime_map`：`(worktree_key, relative_path) -> (last_modified, checksum)`（每 worktree 每文件仅保留一行“最近一次看到的 checksum”）
- `file_analysis_head`：`(repo_key, relative_path, checksum)` 的分析版本头（`analyzed_at/last_access_at`）
- `const_definitions`：按 `repo_key + relative_path + checksum` 存定义
- `const_references`：按 `repo_key + relative_path + checksum` 存引用
- `maintenance_meta`：清理节流元数据（`last_cleanup_at/last_vacuum_at`）

关键行为：

- `file_analysis_head` / `const_definitions` / `const_references` 仍通过 `repo_key + relative_path` 共享分析结果；
- `file_checksum_mtime_map` 通过 `worktree_key + relative_path` 隔离“项目本地基线”；
- 受影响文件查询先定位定义 key，再按当前 worktree 还原绝对路径，仅返回本地存在文件；
- 新增 DB-first 查询 API（latest 口径）：
- `getLatestDefinitionsByFile(filePath)`
- `queryDefinitionsByConstNames(Set<String>)`
- `queryDefinitionsByClassConstKeys(Set<Pair<class,const>>)`
- `queryDefinitionsByPackageConstKeys(Set<Pair<pkg,const>>)`
- `queryClassesBySimpleNames(Set<String>)`：通过 `simple_class_name` 列 + 索引实现点查，避免全表扫描
- 使用共享 SQLite 长连接（`init()` 创建、`close()` 关闭），避免高频建连与重复 PRAGMA；
- latest 版本选择在 `analyzed_at/last_access_at` 相同场景下追加 `checksum` 作为稳定 tie-breaker；
- db schema 使用 `PRAGMA schema_version=4`，不兼容时重建。

### 5.3 Repo 共享指纹：RepoSharedFingerprintStore

用途：当仅 `mtime` 变化时，尽量复用历史 checksum，减少整文件 CRC 成本。

特性：

- key 由 `repo_key + relative_path + file_size + head/tail(+middle)签名` 组成；
- 支持 Git worktree 共享命中（通过 `commondir` 归一 repo_key）；
- 中段内容变化可避免“同头同尾误命中”。
- 支持独立 cleanup（TTL + 每文件版本上限 + checkpoint/vacuum）。

### 5.4 命中链路（编译前）

对单文件 checksum 解析按顺序执行：

1. `file_checksum_mtime_map` 命中：直接用 checksum；
2. `RepoSharedFingerprintStore` 命中：复用 checksum；
3. 都未命中：计算 CRC32，并回写指纹库；
4. 拿到 checksum 后查 `file_analysis_head`：
   - 命中版本则仅 touch（不重复 AST 解析）；
   - 未命中才执行 parse definitions/references 并落库。

另外支持 IO 限频（系统属性）：
- `jugg.constref.io.throttle.ms`：每次节流 `delay` 的毫秒数（当前代码默认 `10000`）；
- `jugg.constref.io.throttle.every`：每处理 N 个文件触发一次节流（默认 `50`）；
- `jugg.constref.session.file.cache.max`：会话文件缓存上限（默认 `500`）；
- `jugg.constref.session.lookup.cache.max`：会话查询缓存 key 上限（默认 `4000`）；
- `jugg.constref.session.cache.ttl.ms`：会话缓存 TTL（默认 `900000`ms）。

> 排查时必须同时核对**当前代码常量**与**实际运行日志**。当前仓库保存的 `2026-03-20 ~ 2026-03-23` 历史 `compile_*.log` 仍打印 `sleepMs=10, everyNFiles=50`；若源码默认值与运行日志不一致，优先怀疑 IDE 中加载的插件产物未更新，或被系统属性覆盖。

---

## 六、就绪性与降级策略

### 6.1 就绪判定

`awaitAnalysis()` 当前成功条件：

1. 目标文件存在时，其 `analyzedAt >= 本次等待开始时间`；
2. `FULL_SCAN` 就绪不再作为硬条件；`shouldSkipFullScanRequirement()` 当前对所有 `sourceDir` 都返回 `true`，因此 `pendingSourceDirs` 通常为空。

**历史口径提醒**（2026-03-23 校准）：
- 旧文档/历史方案里提到的“仅 `build/generated` 跳过 full scan 要求”已经不是当前实现；
- 当前代码把 `FULL_SCAN` 定位为后台补索引任务，`awaitAnalysis()` 不再等待任何目录 full scan 完成；
- 如后续再次恢复目录级等待，请先同步本节与 `ConstRefEngineTest.kt`。

超时或中断时返回：

- `AnalysisReadiness(isReady = false, unreadyPaths, pendingSourceDirs)`
- 日志分级：`debug` 打印详细路径；`warn` 仅打印数量（如 `targetPathCount/unreadyPathCount`）。

### 6.2 DeployDataGenerator 降级行为

- `ensureReadyForRecompile()` 异常：记录 warning，按“未就绪”处理；
- 未就绪：warning 提示，仍继续用当前已完成缓存查询；
- `getEffectedFiles()` 异常：warning 后返回空列表，避免阻断主部署流程。
- cleanup 异常：仅 warning，不影响增量编译主链路。

---

## 七、测试覆盖要点（可回归入口）

| 测试文件 | 重点验证 |
|---|---|
| `ConstRefEngineTest.kt` | 编辑态延迟分析、await 冲刷、删除清理、`const -> val` 场景下 removed keys 仍能命中引用、`db_session` 模式一致性与缓存淘汰一致性、首次 `FULL_SCAN` 延后启动、默认 throttle 配置、`FULL_SCAN` 不阻塞编译就绪。 |
| `ConstRefIntegrationTest.kt` | 冷启动 full scan 后的命中、companion const 变更命中、无关类变更不误报。 |
| `JavaConstParserTest.kt` | Java 定义/引用解析、注解常量、忽略注释/字符串。 |
| `KotlinConstParserTest.kt` | Kotlin 定义/引用解析、alias/星号导入、同包解析、忽略注释/字符串。 |
| `ConstRefCacheDatabaseTest.kt` | DB upsert/query、同名常量跨文件共存、mtime 映射复用、cleanup 保留上限、DB-first 批量查询 API。 |
| `RepoSharedFingerprintStoreTest.kt` | mtime 命中、内容中段变化 miss、worktree 共享、cleanup 保留上限。 |

---

## 八、常见排查

1. `constRefEffectedSourcePaths` 为空  
先确认 `changedSourcePaths` 是否传入（`DeployFileManager.getRecompileFiles`）。

2. 结果疑似滞后  
确认是否执行过 `awaitAnalysis()`；若日志出现 readiness timeout，先看 `unreadyPathCount`，不要再默认把问题归因到 `pendingSourceDirs`。

2.1 增量编译首轮 const-ref 预处理路径
`DeployFileManager.awaitConstRefAnalysis(...)` 可使用 `analyzeOnDemand(...)` 同步按需分析，不依赖固定 timeout 等待窗口。

3. 误以为删除 `const` 不会触发影响  
`ConstRefEngine` 通过 `removedDefinitionKeys` 回补此场景，需确保变更文件已被重新分析。

4. 大仓库耗时偏高  
检查 `RepoSharedFingerprintStore` 是否可写、是否位于 Git 工作树内（否则无法复用共享指纹）。

5. 全局缓存不生效  
检查 `JuggPathManager.constRefDir` 下两个 db 是否创建；若为升级场景，确认迁移日志是否出现。

6. 常量文件只改空行却触发大量重编译  
确认 `changedDefinitionKeys` 是否为空；空白改动不应产生 changed keys，`getEffectedFiles` 应返回空列表。

### 8.1 IDE 卡死排查补充（2026-03-23）

先对齐三类证据的时间线：`build/jugg/log/compile_*.log`、`idea.log` / freeze thread dump、当前源码实现。

优先搜索这些日志：
- `ConstRefEngine defer initial full scan until startup stabilizes`
- `ConstRefEngine io throttle enabled`
- `ConstRefEngine full scan progress`
- `ConstRefEngine.awaitAnalysis timeout`
- `uiFreezeStarted`
- `InvocationEvent has timed out`

快速判断：
- **更像 ConstRef 引起**：freeze 时间窗与 `FULL_SCAN` 重叠，且 worker 栈热点落在 `parseReferencesByDbSessionMode` / `ConstRefCacheDatabase.queryLatestDefinitionsByWhere` / `NativeDB.step`。
- **更像 IDE 启动链引起**：thread dump / `idea.log` 主要卡在 `ApplicationImpl.postInit`、`InitialVfsRefresh`、`clangd`，同时 `jugg` 日志没有活跃 `FULL_SCAN` 进度。
- **口径冲突时**：以**当前代码 + 当前运行日志**为准。比如源码默认 throttle 可能是 `10000ms`，但旧日志仍显示 `sleepMs=10`；这通常意味着 IDE 里跑的不是当前源码产物，或有系统属性覆盖。

---

## 九、常量变化重编译排查手册（复用版）

> 目标：快速判断“常量文件改动后触发了哪些重编译，是否符合预期”，并稳定定位到根因。  
> 适用症状：降级编译后持续触发同一批 effected source；仅空白改动仍重复触发常量重编译。

### 9.1 先看日志（最小定位法）

在 `build/jugg/log/compile_latest.log` 搜这些关键字：

- `ConstRefEngine checksum resolve stats`
- `const ref effected source files`
- `Compile success, but found effected source files, continue compile`
- `analysis not ready` / `awaitAnalysis timeout`

快速判定规则：

- 同一批 `const ref effected source files` 在多轮连续重复：优先怀疑 stale 变更状态未清理。
- 出现 `analysisReuseHit>0` 且仍重复触发旧 effected 列表：优先检查复用路径是否正确清理旧 changed keys。
- 空白改动后 `const ref effected source files` 非空：优先检查 changed keys 是否真的为空。

### 9.2 代码定位顺序（固定入口）

1. `IncrementalCompilerHelper.compile`  
确认是否在“首轮编译成功后、调用 `getRecompileFiles` 前”执行 `deployFileManager.awaitConstRefAnalysis(changedSourcePaths)`，以及重编译轮次的 `changedSourcePaths` 来源。

2. `DeployFileManager.getRecompileFiles`  
确认 `changedSourcePaths` 是否透传到 `DeployDataGenerator.buildDeployData(... constRefChangedSourcePaths=...)`。

3. `DeployDataGenerator.buildDeployData`  
确认 `ensureReadyForRecompile()`、`getEffectedFiles()` 返回是否符合日志。

4. `ConstRefEngine.analyzeFiles / resolveChecksum`  
重点看“复用命中路径”是否会：
- 触发 `changeTracker` 清理；
- 正确判断 mtime 命中是否可安全复用 checksum。

### 9.3 两类高频根因与处理

根因 A：复用命中后 stale changed keys 未清理。  
表现：同一批 effected source 反复出现，即使后续无真实常量变化。  
处理：在 `analysisReuseHit` 或 full scan 复用分支清理对应文件的 changeTracker 状态。

根因 B：mtime 命中误判（时间戳精度/同步链路导致内容变更未被识别）。  
表现：文件内容变了，但被当成未变，后续行为异常（含误复用或错过更新）。  
处理：mtime 命中时与指纹校验交叉验证；不一致时退回指纹/CRC 路径。

### 9.4 验证清单（修复后必跑）

1. 空白改动 const 文件：`const ref effected source files` 应为空。  
2. 实际改动某个 const 值：只触发引用该 const 的文件。  
3. 复用路径命中（`analysisReuseHit>0`）：不应重复触发上一轮旧 effected 集。  
4. full scan 后再次小改：不应携带 full scan 前的 stale 影响集。  
5. 全量 `constref` 测试通过：`./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.constref.*"`。

### 9.5 回归用例建议

建议至少覆盖以下场景：

- `analysis reuse` 后 stale 清理；
- `full scan reuse` 后 stale 清理；
- 冷启动 full scan 后真实改动仍能命中引用；
- 删除引用文件后，effected 列表可被正确清理。

---

## 附录：关键文件清单

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngine.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefChangeTracker.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefImpactResolver.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefAnalyzer.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/JavaConstParser.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/KotlinConstParser.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabase.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/RepoSharedFingerprintStore.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheCleaner.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefRepoPathResolver.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ConstRefEffectProvider.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt`
