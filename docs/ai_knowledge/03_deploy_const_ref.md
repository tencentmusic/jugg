# Jugg 常量引用影响分析（ConstRefEngine / ConstRefAnalyzer）

> 文档版本: v1.6
> 创建时间: 2026-02-22  
> 更新时间: 2026-05-19
> 涵盖模块: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/*`、`main/src/main/java/com/sickworm/intellij/jugg/deploy/data/*`  
> 一致性提示: 如文档与代码不一致，以代码为准。

---

## 一、模块定位

`ConstRefEngine` + `ConstRefAnalyzer` 负责解决一个补充场景：

- 传统 `ClassNode` 结构差异分析更偏向"字节码结构变化导致的受影响类"；
- 但 `const` 变更可能在调用侧被编译器内联，结构变化不一定能完整覆盖；
- 因此新增"常量定义/引用索引"链路，用于产出 `const` 变更导致的受影响源码文件列表。

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

- 串行调度分析任务（协程 `Mutex`，按文件粒度加锁；IO 节流在锁外 `delay()`）；
- 以 `ConstRefCacheDatabase` 为主数据源，结合 `ConstRefSessionCache`（LRU+TTL）做会话热点缓存；
- 维护三层 checksum 命中链路：`mtime-map -> RepoSharedFingerprintStore -> CRC32`；
- 异步触发 `ConstRefCacheCleaner` 做 TTL/版本上限清理，不阻塞主流程；
- 通过 `ConstRefChangeTracker` 追踪"真实变更 key + 已删除 key"，避免空白改动触发全量常量引用重编译；
- 通过 `ConstRefImpactResolver` 统一执行受影响文件查询与过滤。

### 3.2 四个分析场景

| 场景 | 触发方式 | 行为 |
|---|---|---|
| `FULL_SCAN` | `initializeFullScan(sourceDirs)` | 首次调用延后 `10_000ms` 启动（启动稳定宽限期）；按 DB 缓存命中跳过已分析文件；使用 `Dispatchers.IO.limitedParallelism(1)` 隔离；后台扫描不阻塞编译就绪，允许轻量节流以降低 IDE 启动期资源竞争。 |
| `FILE_CHANGE` | `onFileSaved(path)` | 日常保存触发的异步增量分析；后台补索引，允许轻量节流。 |
| `PRE_COMPILE` | `awaitAnalysis()` 内触发 | 编译前冲刷待分析队列，调用方会等待目标文件分析完成；不应主动 sleep，否则容易超过 5s readiness 预算并降级到旧缓存。 |
| `ON_DEMAND` | `analyzeOnDemand(paths)` | 同步按需分析目标文件，调用方直接等待结果；不应主动 sleep。 |

### 3.3 核心 API

| API | 行为摘要 |
|---|---|
| `onFileSaved(path)` | 仅处理 `.java/.kt`；将"前一个编辑文件"推入待分析队列，当前文件仅标记为 editing。 |
| `awaitAnalysis(paths, timeout)` | 冲刷 `currentEditingFile`；触发 `PRE_COMPILE`；`shouldSkipFullScanRequirement()` 始终返回 `true`，因此 `FULL_SCAN` 不阻塞编译就绪，仅要求目标文件 `analyzedAt` 达标。 |
| `analyzeOnDemand(paths)` | 同步按需分析：优先复用已有 checksum 结果；缺失或变化时立即分析并返回。 |
| `initializeFullScan(sourceDirs)` | 注册目录后异步全量扫描，构建 definitions/reference candidates 索引。 |
| `onFileDeleted(path)` | 立即清理内存状态与变更跟踪；DB 删除走后台队列，避免 EDT 阻塞。 |
| `getEffectedFiles(changedPaths)` | 基于 `changedDefinitionKeys` + `removedDefinitionKeys` 查询引用文件；仅返回本地存在且不在 `changedPaths` 的文件。 |

### 3.4 路径与注入

- `ConstRefEngine` 不直接拼接路径；
- `DeployFileManager` 从 `JuggPathManager` 注入：
  - `constRefSharedDbFile`：`<PathManager.system>/jugg/const_ref/const_ref_shared.db`
  - `repoFingerprintDbFile`：`<PathManager.system>/jugg/const_ref/repo_fingerprint.db`

---

## 四、ConstRefAnalyzer 能力边界

`ConstRefAnalyzer` 是语言无关封装层：

- Java 走 `JavaConstParser`；Kotlin 走 `KotlinConstParser`；
- 支持输入去重、只处理存在且扩展名合法的源码文件；
- `parseReferenceCandidates()`：不依赖已解析 definitions，仅基于源码语法记录可能引用的常量候选；
- 旧的 `collectHintsAndParseReferences()` / `parseReferences()` 仍保留给兼容用例和旧精确引用模型，但主分析链路写入 candidate 索引；
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

### 4.2 候选引用解析（Reference Candidate）

`ConstReferenceCandidate` 字段：

- `refFilePath`, `packageName`, `constName`, `ownerName`, `ownerKind`, `importPackages`

核心原则：

- 引用扫描不查询 definitions，也不要求目标 const 已经被扫描；
- 候选事实记录语法来源，例如显式 const import、显式 class import、包/类星号导入、owner-qualified 表达式、同包裸引用；
- 影响查询阶段用变更后的 `ConstDefinition` 与 candidate 做保守匹配，允许多编译，不能漏编译；
- companion const 会同时匹配 `Owner.CONST` 与 `Owner.Companion.CONST` 形态；
- const 被降级为普通 `val` 等 removed definition 场景，会用变更 key 构造临时 definition 继续匹配旧候选索引。

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

- 不做全量预加载；每个待解析文件直接写入 syntax-only reference candidates。
- `getEffectedFiles()` 消费 `changedDefinitionKeys` / `removedDefinitionKeys` 后，在 DB 中按 `constName` 找候选引用，再用 owner/package 规则做保守匹配。
- `ConstRefSessionCache`（LRU+TTL，惰性节流清理默认 60s）：
  - `fileCache`：缓存会话内已访问文件的 definitions/legacy references；
  - `lookupCache`：缓存 `constName / class+const / package+const / simpleClassName` 查询结果。

### 5.2 SQLite 缓存：ConstRefCacheDatabase

核心表：

| 表 | 用途 |
|---|---|
| `strings` | 全局字符串字典，存放 repo/worktree/path/package/class/const/type/value/import 等重复字符串 |
| `file_checksum_mtime_map` | `(worktree_id, path_id) -> (last_modified, checksum)`，每 worktree 每文件仅一行 |
| `file_analysis_head` | `(repo_id, path_id, checksum)` 的分析版本头，并提供 `file_id` 给子表引用 |
| `const_definitions` | 按 `file_id` 存定义，package/class/const/type/value 使用 `string_id` |
| `const_references` | 旧精确引用表，保留兼容历史查询与测试 |
| `const_reference_candidates` | 按 `file_id` 存 syntax-only 候选引用，package/const/owner/import 使用 `string_id`，`owner_kind` 使用整数枚举 |
| `maintenance_meta` | 清理节流元数据 |

关键行为：

- `file_analysis_head` / `const_definitions` / `const_reference_candidates` 通过 `file_id` 共享分析结果，避免在高频引用索引里重复保存长路径；
- `file_checksum_mtime_map` 通过 `worktree_id + path_id` 隔离项目本地基线；
- 写入侧先预热当前批次的字符串 ID，减少 full scan/batch analysis 的逐行 `strings` 查询；进程内 `stringIdCache` 仅作为有上限的 LRU 辅助缓存；
- 受影响文件查询先定位定义 key，再匹配 latest candidate rows，最后按当前 worktree 还原绝对路径，仅返回本地存在文件；
- 支持 `queryClassesBySimpleNames` 通过 `simple_class_id + const_name_id` 索引实现点查，避免全表扫描；
- 使用共享 SQLite 长连接，避免高频建连；latest 版本选择追加 `checksum` 作为稳定 tie-breaker；
- `PRAGMA schema_version=6`，不兼容时重建。

### 5.3 Repo 共享指纹：RepoSharedFingerprintStore

- key 由 `repo_key + relative_path + file_size + head/tail(+middle)签名` 组成；
- 支持 Git worktree 共享命中（通过 `commondir` 归一 repo_key）；
- 中段内容变化可避免"同头同尾误命中"。
- 支持独立 cleanup（TTL + 每文件版本上限 + checkpoint/vacuum）。

### 5.4 命中链路（编译前）

对单文件 checksum 解析按顺序执行：

1. `file_checksum_mtime_map` 命中：直接用 checksum；
2. `RepoSharedFingerprintStore` 命中：复用 checksum；
3. 都未命中：计算 CRC32，并回写指纹库；
4. 拿到 checksum 后查 `file_analysis_head`：命中则仅 touch；未命中才执行 parse 并落库。

IO 限频系统属性（默认值）：

| 属性 | 默认值 |
|---|---|
| `jugg.constref.fullscan.io.throttle.ms` | `500` |
| `jugg.constref.fullscan.io.throttle.every` | `200` |
| `jugg.constref.filechange.io.throttle.ms` | `500` |
| `jugg.constref.filechange.io.throttle.every` | `200` |
| `jugg.constref.precompile.io.throttle.ms` | `0` |
| `jugg.constref.precompile.io.throttle.every` | `1` |
| `jugg.constref.ondemand.io.throttle.ms` | `0` |
| `jugg.constref.ondemand.io.throttle.every` | `1` |
| `jugg.constref.session.file.cache.max` | `500` |
| `jugg.constref.session.lookup.cache.max` | `4000` |
| `jugg.constref.session.cache.ttl.ms` | `900_000` |

`jugg.constref.io.throttle.ms` / `jugg.constref.io.throttle.every` 作为兼容兜底仍可使用，但优先级低于各场景专属属性。`FULL_SCAN` 和 `FILE_CHANGE` 是后台任务，默认只做轻量让步；`PRE_COMPILE` 和 `ON_DEMAND` 位于用户等待链路，默认不节流。

---

## 六、就绪性与降级策略

### 6.1 就绪判定

`awaitAnalysis()` 成功条件：目标文件 `analyzedAt >= 等待开始时间`。`FULL_SCAN` 就绪不再作为硬条件（`shouldSkipFullScanRequirement()` 始终返回 `true`）。

超时或中断时返回 `AnalysisReadiness(isReady = false, unreadyPaths, pendingSourceDirs)`。日志 `debug` 打印详细路径，`warn` 仅打印数量。

### 6.2 DeployDataGenerator 降级行为

- `ensureReadyForRecompile()` 异常：warning，按"未就绪"继续；
- 未就绪：warning，仍用当前缓存查询；
- `getEffectedFiles()` 异常：warning 后返回空列表，不阻断部署主流程；
- cleanup 异常：仅 warning，不影响增量编译。

---

## 七、测试覆盖要点

| 测试文件 | 重点验证 |
|---|---|
| `ConstRefEngineTest.kt` | 编辑态延迟分析、await 冲刷、删除清理、`const -> val` removed keys 命中、`db_session` 一致性、首次 `FULL_SCAN` 延后启动、throttle 配置、`FULL_SCAN` 不阻塞就绪。 |
| `ConstRefIntegrationTest.kt` | 冷启动 full scan 命中、companion const 变更命中、无关类不误报。 |
| `JavaConstParserTest.kt` | Java 定义/引用解析、注解常量、忽略注释/字符串。 |
| `KotlinConstParserTest.kt` | Kotlin 定义/引用解析、alias/星号导入、同包解析、忽略注释/字符串。 |
| `ConstRefCacheDatabaseTest.kt` | DB upsert/query、同名常量跨文件共存、mtime 映射复用、cleanup 保留上限、DB-first 批量查询。 |
| `RepoSharedFingerprintStoreTest.kt` | mtime 命中、中段变化 miss、worktree 共享、cleanup 保留上限。 |

---

## 八、常见排查

| # | 症状 | 定位方法 |
|---|---|---|
| 1 | `constRefEffectedSourcePaths` 为空 | 确认 `changedSourcePaths` 是否传入（`DeployFileManager.getRecompileFiles`）。 |
| 2 | 结果疑似滞后 | 确认是否执行过 `awaitAnalysis()`；日志 readiness timeout 时看 `unreadyPathCount`。增量编译首轮可用 `analyzeOnDemand(...)` 同步按需分析。 |
| 3 | 删除 `const` 未触发影响 | `ConstRefEngine` 通过 `removedDefinitionKeys` 回补此场景，确保变更文件已重新分析。 |
| 4 | 大仓库耗时偏高 | 检查 `RepoSharedFingerprintStore` 是否可写、是否位于 Git 工作树内。 |
| 5 | 全局缓存不生效 | 检查 `JuggPathManager.constRefDir` 下两个 db 是否创建。 |
| 6 | 空白改动触发大量重编译 | 确认 `changedDefinitionKeys` 是否为空；空白改动不应产生 changed keys。 |
| 7 | 同一批 effected source 反复出现 | 优先怀疑 stale changed keys 未清理（复用命中或 full scan 复用分支）。 |
| 8 | IDE 卡死疑似 ConstRef | 详见 `09_plugin_runtime_debug.md`；对齐 `compile_*.log`、`idea.log` / freeze dump、源码的时间线。 |

排查关键日志（`build/jugg/log/compile_latest.log`）：

- `ConstRefEngine checksum resolve stats`
- `const ref effected source files`
- `Compile success, but found effected source files, continue compile`
- `analysis not ready` / `awaitAnalysis timeout`

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
