# Jugg 常量引用影响分析（ConstRefEngine / ConstRefAnalyzer）

> 文档版本: v1.2  
> 创建时间: 2026-02-22  
> 更新时间: 2026-02-28  
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
- 维护 `ConstDefinitionIndex`（内存索引）与 `ConstRefCacheDatabase`（持久化）；
- 维护三层 checksum 命中链路：`mtime-map -> RepoSharedFingerprintStore -> CRC32`；
- 在预编译点强制冲刷待分析队列，尽量保证结果新鲜；
- 异步触发 `ConstRefCacheCleaner` 做 TTL/版本上限清理，不阻塞主流程；
- 通过 `ConstRefChangeTracker` 追踪“真实变更 key + 已删除 key”，避免空白改动触发全量常量引用重编译；
- 通过 `ConstRefImpactResolver` 统一执行受影响文件查询与过滤。

### 3.2 三个分析场景

- `FULL_SCAN`：模块 sourceDir 初始化后异步全量建索引；
- `FILE_CHANGE`：日常保存触发的异步增量分析；
- `PRE_COMPILE`：`awaitAnalysis()` 内同步抢占执行，确保编译前尽量完成待分析文件。

> `FULL_SCAN` 会先按 `(repo_key, relative_path, last_modified)` 批量查询 DB 缓存命中，命中项仅更新内存就绪状态，未命中项才进入解析流程。

### 3.3 状态模型（核心字段）

- `pendingAnalyzeFiles`：待分析文件集合；
- `currentEditingFile`：当前编辑中的最后一个文件（避免每次按键都解析）；
- `analyzedAt`：文件最近分析完成时间；
- `ConstRefChangeTracker.changedDefinitionKeys`：文件真实变化的 `(fqClassName, constName)` 集；
- `ConstRefChangeTracker.removedDefinitionKeys`：文件删掉的 `(fqClassName, constName)` 集；
- `trackedSourceDirs` + `fullScanReadySourceDirs`：全量扫描目录与就绪标记；
- `definitionIndex` + `cachedDefinitionsByFile`：当前定义索引快照。

### 3.4 核心 API 行为

| API | 行为摘要 |
|---|---|
| `onFileSaved(path)` | 仅处理 `.java/.kt`；将“前一个编辑文件”推入待分析队列，当前文件仅标记为 editing。 |
| `awaitAnalysis(paths, timeout)` | 冲刷 `currentEditingFile` 到待分析；触发 `PRE_COMPILE` 同步分析；等待“目标文件 analyzedAt 达标 + 相关 sourceDir full scan ready”。 |
| `initializeFullScan(sourceDirs)` | 异步扫描目录下所有源码，构建 definitions/references 索引，并设置目录 ready。 |
| `onFileDeleted(path)` | 清理内存状态、索引、数据库文件记录（含前缀删除）。 |
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

### 5.1 内存索引：ConstDefinitionIndex

提供按以下维度查询：

- `class + const`；
- `package + const`；
- `constName`；
- `simpleClassName -> fqClassName`。

支持 `replaceFileDefinitions()` 按文件增量替换，避免全量重建。

### 5.2 SQLite 缓存：ConstRefCacheDatabase

核心表：

- `file_checksum_mtime_map`：`(repo_key, relative_path, last_modified) -> checksum`
- `file_analysis_head`：`(repo_key, relative_path, checksum)` 的分析版本头（`analyzed_at/last_access_at`）
- `const_definitions`：按 `repo_key + relative_path + checksum` 存定义
- `const_references`：按 `repo_key + relative_path + checksum` 存引用
- `maintenance_meta`：清理节流元数据（`last_cleanup_at/last_vacuum_at`）

关键行为：

- 同仓库多 worktree 通过 `repo_key + relative_path` 共享分析结果；
- 受影响文件查询先定位定义 key，再按当前 worktree 还原绝对路径，仅返回本地存在文件；
- db schema 使用 `PRAGMA schema_version=2`，不兼容时重建。

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
- `jugg.constref.io.throttle.ms`：每次节流 sleep 的毫秒数（默认 `0`，即关闭）；
- `jugg.constref.io.throttle.every`：每处理 N 个文件触发一次 sleep（默认 `1`）。

---

## 六、就绪性与降级策略

### 6.1 就绪判定

`awaitAnalysis()` 成功条件：

1. 目标文件存在时，其 `analyzedAt >= 本次等待开始时间`；
2. 目标文件所属 sourceDir 已完成 full scan。

超时或中断时返回：

- `AnalysisReadiness(isReady = false, unreadyPaths, pendingSourceDirs)`

### 6.2 DeployDataGenerator 降级行为

- `ensureReadyForRecompile()` 异常：记录 warning，按“未就绪”处理；
- 未就绪：warning 提示，仍继续用当前已完成缓存查询；
- `getEffectedFiles()` 异常：warning 后返回空列表，避免阻断主部署流程。
- cleanup 异常：仅 warning，不影响增量编译主链路。

---

## 七、测试覆盖要点（可回归入口）

| 测试文件 | 重点验证 |
|---|---|
| `ConstRefEngineTest.kt` | 编辑态延迟分析、await 冲刷、删除清理、`const -> val` 场景下 removed keys 仍能命中引用。 |
| `ConstRefIntegrationTest.kt` | 冷启动 full scan 后的命中、companion const 变更命中、无关类变更不误报。 |
| `JavaConstParserTest.kt` | Java 定义/引用解析、注解常量、忽略注释/字符串。 |
| `KotlinConstParserTest.kt` | Kotlin 定义/引用解析、alias/星号导入、同包解析、忽略注释/字符串。 |
| `ConstRefCacheDatabaseTest.kt` | DB upsert/query、同名常量跨文件共存、mtime 映射复用、cleanup 保留上限。 |
| `RepoSharedFingerprintStoreTest.kt` | mtime 命中、内容中段变化 miss、worktree 共享、cleanup 保留上限。 |

---

## 八、常见排查

1. `constRefEffectedSourcePaths` 为空  
先确认 `changedSourcePaths` 是否传入（`DeployFileManager.getRecompileFiles`）。

2. 结果疑似滞后  
确认是否执行过 `awaitAnalysis()`；若日志出现 readiness timeout，检查 `pendingSourceDirs`。

3. 误以为删除 `const` 不会触发影响  
`ConstRefEngine` 通过 `removedDefinitionKeys` 回补此场景，需确保变更文件已被重新分析。

6. 常量文件只改空行却触发大量重编译  
确认 `changedDefinitionKeys` 是否为空；空白改动不应产生 changed keys，`getEffectedFiles` 应返回空列表。

4. 大仓库耗时偏高  
检查 `RepoSharedFingerprintStore` 是否可写、是否位于 Git 工作树内（否则无法复用共享指纹）。

5. 全局缓存不生效  
检查 `JuggPathManager.constRefDir` 下两个 db 是否创建；若为升级场景，确认迁移日志是否出现。

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
