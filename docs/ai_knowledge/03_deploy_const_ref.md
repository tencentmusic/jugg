# Jugg 常量引用影响分析（ConstRefEngine / ConstRefAnalyzer）

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只回答常量引用影响分析如何接入增量重编译：

- 哪些类负责扫描、缓存和影响查询。
- 保存/删除/编译前各阶段如何推进索引。
- SQLite / fingerprint 缓存为什么能跨 worktree 复用。
- 出现漏重编或耗时时应从哪里第一跳排查。

不展开普通类结构影响分析；那部分看 `03_deploy_data_generator.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `DeployFileManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt` | ConstRef 接入 deploy 的 facade：保存/删除事件、full scan 初始化、编译前 readiness、effected files 查询。 |
| `DeployDataGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | 构建 `JuggDeployData` 时等待 ConstRef 分析，并把结果写入 `constRefEffectedSourcePaths`。 |
| `ConstRefEffectProvider` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ConstRefEffectProvider.kt` | `DeployDataGenerator` 与 `ConstRefEngine` 之间的窄接口，便于禁用 ConstRef 或测试替换。 |
| `ConstRefEngine` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngine.kt` | 生命周期协调器：编辑态延迟、full scan、pre-compile flush、on-demand 分析、readiness 和影响查询。 |
| `ConstRefAnalyzer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefAnalyzer.kt` | 语言无关解析封装，分发 Java/Kotlin parser 并串行化 Kotlin PSI 访问。 |
| `JavaConstParser` / `KotlinConstParser` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/*ConstParser.kt` | 解析常量定义与 syntax-only 引用候选。 |
| `ConstRefChangeTracker` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefChangeTracker.kt` | 记录真实变更的 definition key 和被删除 key，避免空白改动触发误重编。 |
| `ConstRefImpactResolver` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefImpactResolver.kt` | 消费 changed/removed definition keys，从 DB 还原受影响源码文件。 |
| `ConstRefCacheDatabase` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabase.kt` | 共享 SQLite 索引：strings 字典、mtime checksum、analysis head、definitions、reference candidates。 |
| `RepoSharedFingerprintStore` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/RepoSharedFingerprintStore.kt` | Git repo/worktree 间共享 checksum 指纹，减少冷启动重复解析。 |
| `ConstRefSessionCache` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefSessionCache.kt` | 会话级 LRU/TTL 热点缓存。 |
| `ConstRefCacheCleaner` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheCleaner.kt` | 后台 TTL、版本上限、checkpoint/vacuum 清理。 |

---

## 3. 核心数据模型

| 数据 | 来源 | 消费者 | 关键含义 |
|---|---|---|---|
| `ConstDefinition` | Java/Kotlin parser | `ConstRefChangeTracker`, DB | 一个可内联常量定义：文件、包、类、常量名、类型、值。 |
| `ConstReferenceCandidate` | Java/Kotlin parser | `ConstRefImpactResolver`, DB | syntax-only 引用事实；不要求目标 const 已被扫描。 |
| `changedDefinitionKeys` | `ConstRefChangeTracker` | `ConstRefImpactResolver` | 本轮真实变化的 `(fqClassName, constName)`。 |
| `removedDefinitionKeys` | `ConstRefChangeTracker` | `ConstRefImpactResolver` | `const -> val` 或删除常量时的旧 key，用旧候选索引继续找引用方。 |
| `AnalysisReadiness` | `ConstRefEngine.awaitAnalysis()` | `DeployDataGenerator` | 编译前目标文件是否已完成本轮分析；未 ready 时允许降级。 |
| `JuggDeployData.constRefEffectedSourcePaths` | `DeployDataGenerator` | 编译循环 / 日志 | ConstRef 额外要求重编译的源码路径。 |

`ConstReferenceCandidate.ownerKind` 使用整数枚举写入 DB：显式 const import、显式 class import、包星号导入、类星号导入、owner-qualified 表达式、同包裸引用。

---

## 4. 核心调用链路

### 4.1 保存 / 删除 / full scan 接入

```text
DeployFileManager.addChangedFile()
  -> Java/Kotlin 文件: ConstRefEngine.onFileSaved()
  -> 只把前一个 editing 文件推入 pending，当前文件先标记 editing

DeployFileManager.removeChangedFile()
  -> ConstRefEngine.onFileDeleted()
  -> 清内存状态 + change tracker + session cache
  -> DB 删除清理进入后台队列

DeployFileManager.updateModuleInfos()
  -> sourceFileManager.init(sourceDirs)
  -> ConstRefEngine.initializeFullScan(sourceDirs)
  -> 首次 full scan 延迟 10s，避免 IDE 启动期资源竞争
```

`onFileSaved()` 的“当前文件延迟、前一个文件入队”是为了降低高频保存时的重复分析；编译前 `awaitAnalysis()` 会 flush 当前 editing 文件。

### 4.2 编译前影响查询

```text
DeployFileManager.getRecompileFiles()
  -> DeployDataGenerator.buildDeployData(..., constRefChangedSourcePaths)
  -> ConstRefEffectProvider.ensureReadyForRecompile()
      -> ConstRefEngine.awaitAnalysis(timeout=5s)
      -> flush editing file + PRE_COMPILE 分析目标文件
  -> readiness 未 ready: warn 后继续用已完成缓存
  -> ConstRefEffectProvider.getEffectedFiles()
      -> ConstRefChangeTracker.peekDefinitionDiff()
      -> ConstRefImpactResolver.getEffectedFiles()
      -> DB 按 constName 找 candidates，再按 owner/package 规则保守匹配
  -> 写入 JuggDeployData.constRefEffectedSourcePaths
  -> 部署成功后 DeployFileManager.commit()
      -> ConstRefEngine.acknowledgeEffectedFilesAfterDeployCommit()
      -> ConstRefChangeTracker.consumeDefinitionDiff()
```

`FULL_SCAN` 不再作为编译前硬门槛；`awaitAnalysis()` 只要求目标变更文件达到本轮分析时间线。查询异常返回空列表，不阻断部署主流程。
`getEffectedFiles()` 只查询并登记待确认的 definition diff，不在查询阶段清理；只有部署成功 commit 后才 ack 清理，避免“跟编失败后下一次编译漏掉同一批 const-ref 影响”。

### 4.3 缓存命中链路

```text
analyzeFiles()
  -> file_checksum_mtime_map 命中: 直接取得 checksum
  -> RepoSharedFingerprintStore 命中: 跨 worktree 复用 checksum
  -> 都 miss: 计算 CRC32 并回写 fingerprint
  -> file_analysis_head 命中: touch 现有分析结果
  -> analysis miss: parser 解析 definitions + reference candidates 后落库
```

`ConstRefEngine` 不直接拼 DB 路径；`DeployFileManager` 从 `JuggPathManager.constRefSharedDbFile` 和 `repoFingerprintDbFile` 注入。

---

## 5. SQLite 与缓存设计

### 5.1 `ConstRefCacheDatabase`

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

- `file_analysis_head` / `const_definitions` / `const_reference_candidates` 通过 `file_id` 共享分析结果，避免在高频引用索引里重复保存长路径。
- `file_checksum_mtime_map` 通过 `worktree_id + path_id` 隔离项目本地基线。
- 写入侧先预热当前批次的字符串 ID，减少 full scan / batch analysis 的逐行 `strings` 查询；进程内 `stringIdCache` 是有上限的 LRU 辅助缓存。
- 受影响文件查询先定位 definition key，再匹配 latest candidate rows，最后按当前 worktree 还原绝对路径，仅返回本地存在文件。
- 支持 `queryClassesBySimpleNames` 通过 `simple_class_id + const_name_id` 索引实现点查，避免全表扫描。
- 使用共享 SQLite 长连接，避免高频建连；latest 版本选择追加 `checksum` 作为稳定 tie-breaker。
- `PRAGMA schema_version=6`，不兼容时重建。

### 5.2 `RepoSharedFingerprintStore`

- key 由 `repo_key + relative_path + file_size + head/tail(+middle)签名` 组成。
- 支持 Git worktree 共享命中（通过 `commondir` 归一 repo_key）。
- 中段内容变化可避免“同头同尾误命中”。
- 支持独立 cleanup（TTL + 每文件版本上限 + checkpoint/vacuum）。

### 5.3 会话缓存与 IO 限频

`ConstRefSessionCache` 使用 LRU + TTL：

- `fileCache` 缓存会话内已访问文件的 definitions / legacy references。
- `lookupCache` 缓存 constName、class+const、package+const、simpleClassName 点查结果。

IO 限频默认只影响后台任务；用户等待链路默认不 sleep：

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

`jugg.constref.io.throttle.ms` / `jugg.constref.io.throttle.every` 作为兼容兜底仍可使用，但优先级低于各场景专属属性。

---

## 6. 隐形约束

- 引用扫描不查询 definitions，也不要求目标 const 已经被扫描；影响查询阶段用变更后的 definition 与 syntax candidate 保守匹配，原则是允许多编译，不能漏编译。
- companion const 会同时匹配 `Owner.CONST` 与 `Owner.Companion.CONST` 形态。
- `const` 被降级为普通 `val` 或删除时，`removedDefinitionKeys` 会继续命中旧候选索引。
- `awaitAnalysis()` 成功条件是目标文件 `analyzedAt >= 等待开始时间`；full scan ready 不再阻塞编译。
- `ensureReadyForRecompile()` 异常时 warning，按“未就绪”继续。
- 未就绪时 warning，仍用当前缓存查询。
- `getEffectedFiles()` 异常时 warning 后返回空列表，不阻断部署主流程。
- const-ref definition diff 的清理时机是成功部署后的 commit ack，而不是影响查询本身；编译失败、跟编失败或部署失败时，同一批 const diff 应在下一次编译继续可查。
- cleanup / vacuum 异常仅 warning，不影响增量编译。
- Java 只记录可内联类型的 `static final` 字段；Kotlin 支持 top-level、object、companion、嵌套 class/object 的 `const val`。
- Java/Kotlin parser 都忽略注释和字符串文本中的伪引用。

---

## 7. 排查入口

| 现象 | 优先入口 |
|---|---|
| `constRefEffectedSourcePaths` 为空 | `DeployFileManager.getRecompileFiles()` 是否传入 changed source；`DeployDataGenerator` 的 `constRefEffectProvider.getEffectedFiles()` |
| 结果疑似滞后 | `ConstRefEngine.awaitAnalysis()`，日志 `analysis not ready` / `awaitAnalysis timeout` 的 `unreadyPathCount` |
| 删除或 `const -> val` 未触发重编 | `ConstRefChangeTracker` 的 removed keys，`ConstRefImpactResolver.getEffectedFiles()` |
| 空白改动触发大量重编译 | `ConstRefChangeTracker.consumeDefinitionDiff()` 是否产生 changed keys |
| 同一批 effected source 反复出现 | `ConstRefChangeTracker` 清理时机，DB 复用命中分支是否 stale |
| 大仓库 cold scan 耗时高 | `RepoSharedFingerprintStore` 命中与写入、`ConstRefCacheDatabase.findReusablePathsByLastModified()` |
| 全局缓存不生效 | `JuggPathManager.constRefSharedDbFile`、`repoFingerprintDbFile` 是否创建 |
| IDE 卡死疑似 ConstRef | `09_plugin_runtime_debug.md`，对齐 `compile_latest.log`、`idea.log` / freeze dump、`ConstRefEngine` 时间线 |

排查关键日志（`build/jugg/log/compile_latest.log`）：

- `ConstRefEngine checksum resolve stats`
- `const ref effected source files`
- `Compile success, but found effected source files, continue compile`
- `analysis not ready` / `awaitAnalysis timeout`

---

## 8. 测试落点

| 测试文件 | 重点验证 |
|---|---|
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngineTest.kt` | 编辑态延迟、await 冲刷、删除清理、removed keys、full scan 不阻塞就绪、on-demand 降级。 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefIntegrationTest.kt` | 冷启动 full scan、companion const、无关类不误报。 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefAnalyzerTest.kt` | Java/Kotlin parser 并发访问串行化。 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/JavaConstParserTest.kt` | Java 定义/引用解析、注解常量、忽略注释/字符串。 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/KotlinConstParserTest.kt` | Kotlin alias/星号导入、同包解析、忽略注释/字符串。 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabaseTest.kt` | DB upsert/query、mtime 映射、cleanup、DB-first 批量查询。 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/RepoSharedFingerprintStoreTest.kt` | mtime 命中、中段变化 miss、worktree 共享、cleanup。 |

---

## 9. 关联文档

- 部署核心：`03_deploy_core.md`
- 影响分析与部署数据生成：`03_deploy_data_generator.md`
- 编译主流程：`02_compile_core.md`
- 运行时排查：`09_plugin_runtime_debug.md`
- 测试策略：`06_testing.md`
