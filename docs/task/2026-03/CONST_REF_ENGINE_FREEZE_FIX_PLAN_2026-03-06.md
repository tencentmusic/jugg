# ConstRefEngine AS 卡死修复方案

- 日期：2026-03-06
- 触发问题：首次 FULL_SCAN 导致 AS UI freeze（约 90 秒），`DefaultDispatcher-worker-2` CPU 占用 91%
- 关联日志：`JOOX_Android_3/build/jugg/log/compile_latest.log`
- 关联分析报告：`AS卡死分析报告_2026-03-06_ConstRefEngine.md`
- 参考方案：`2026-03-06_const_ref_engine_freeze_fix_plan_v2.md`（Codex）

---

## 一、根因摘要

首次 FULL_SCAN 时，所有文件（本次 10206 个）均无缓存可复用（`totalReused=0`，首次扫描的必然结果），
全部进入 `analyzeFiles`，其中约 393 个文件 `touchFileAnalysis=false`，触发重型 SQLite 查询链。

**FULL_SCAN 的设计定位是低消耗后台扫描，但以下四个实现缺陷导致其实际行为是高消耗独占式阻塞：**

1. **`analyzeFiles` 持有 `analysisMutex` 全程无协程让步，且 `maybeThrottleIo` 用 `Thread.sleep` 实现**
   - 10206 次文件循环 + 393 次 SQLite 查询全部在锁内串行执行，无法被抢占或中断
   - `maybeThrottleIo` 默认关闭（`ioThrottleSleepMs=0`）；即使开启，`Thread.sleep` 持锁期间
     不释放 `analysisMutex`，CPU 实际并未让出
   - `DefaultDispatcher-worker-2` 被独占约 91 秒，无法执行任何其他调度任务

2. **`parseReferencesByDbSessionMode` 缺乏候选定义为空时的短路**
   - 即使候选 definitions（DB + overlay）为空，仍继续执行完整的 `parseReferences` 流程
   - 造成无收益的 DB 查询与二次解析

3. **`ConstRefCacheDatabase.withConnection` 无连接复用**
   - 每次 SQLite 查询都调用 `DriverManager.getConnection(url)` 新建连接
   - 每次新建连接后执行 6 条 PRAGMA（`foreign_keys / journal_mode / synchronous / cache_size /
     temp_store / busy_timeout`）
   - 393 文件 × 多轮查询 × (1 连接 + 6 PRAGMA) = 数千次冗余初始化，在锁内串行累积成 CPU 热点

4. **FULL_SCAN 与 IDE 其他后台任务共用 `Dispatchers.Default`**
   - 独占 Default dispatcher 的 worker 线程，挤压 IDE 本身依赖此 dispatcher 的任务
   - AWT Accessibility、菜单更新等轻量任务得不到调度机会，放大"卡死"体感

**最终表现**：EDT 因 `Dispatchers.Default` 调度队列饱和而产生调度饥饿，`AWT Accessibility /
Menu.menuNeedsUpdate` 等操作反复超时，AS UI 持续不可交互约 10 分钟。

---

## 二、修复方案（共 5 项）

### 实施顺序

```
第一步（先止血，改动小）：Fix-1 + Fix-2
第二步（协程重构，改动大）：Fix-3
第三步（隔离调度）：Fix-4
第四步（清理优化）：Fix-5
```

---

### Fix-1：`parseReferencesByDbSessionMode` 增加空候选短路（P0，最先做）

**目标**：避免无收益的 DB 查询与二次解析，改动最小、收益最直接。

**当前问题**：`hints.isEmpty()` 已有短路，但在构建完 `candidateDefinitions` 后，若结果为空，
仍会继续调用 `analyzer.parseReferences`。

**方案**：在 `queryCandidateDefinitionsForFile` 返回后，若 candidates + overlay 均为空，
直接跳过该文件的 `parseReferences`：

```kotlin
changedFiles.forEach { file ->
    val stdPath = file.toStdPath()
    val hints = hintsByPath[stdPath] ?: ConstReferenceLookupHints.EMPTY
    if (hints.isEmpty()) {
        referencesByPath[stdPath] = emptyList()
        return@forEach  // 已有短路，保留
    }

    val candidateDefinitions = queryCandidateDefinitionsForFile(filePath = stdPath, hints = hints)
    val allDefinitions = buildAllDefinitions(candidateDefinitions, overlayDefinitions, stdPath)

    // 新增：候选定义为空时无需解析引用
    if (allDefinitions.isEmpty()) {
        referencesByPath[stdPath] = emptyList()
        return@forEach
    }

    val definitionIndex = ConstDefinitionIndex(allDefinitions.values)
    referencesByPath[stdPath] = analyzer.parseReferences(listOf(file), definitionIndex)[stdPath].orEmpty()
}
```

---

### Fix-2：`ConstRefSessionCache.cleanupExpiredLocked` 改为惰性触发（P0，最先做）

**目标**：消除 FULL_SCAN 高频调用路径上的 O(n) `removeIf` 热点。

**当前问题**：`cleanupExpiredLocked` 在 `getFileDefinitions`、`putFileAnalysis`、
`getLookupEntry`、`putLookupEntry` 等每次调用中都被触发。

**方案**：加时间间隔节流，默认每 60 秒清理一次：

```kotlin
private var lastCleanupMs = 0L
private val cleanupIntervalMs = 60_000L

private fun cleanupExpiredLocked(nowMs: Long) {
    if (ttlMs <= 0L) return
    if (nowMs - lastCleanupMs < cleanupIntervalMs) return
    lastCleanupMs = nowMs
    fileCache.entries.removeIf { (_, entry) -> isExpired(entry.updatedAt, nowMs) }
    lookupCache.entries.removeIf { (_, entry) -> isExpired(entry.updatedAt, nowMs) }
}
```

**风险**：过期条目在 `cleanupIntervalMs` 内不被清除，但每次读取仍检查单条目 TTL（`isExpired` 保留），
不会返回过期数据，内存略高但可接受。

---

### Fix-3：`analyzeFiles` suspend 化 + `maybeThrottleIo` 改 `delay()` 限速（P0，第二步）

**目标**：让 FULL_SCAN 成为真正的低消耗后台扫描——每批文件处理完后主动挂起，归还 CPU 给其他任务。

**核心原则**：
- `yield()` 只让出调度权，若线程池无其他任务会立即重新执行，**不降低 CPU 消耗**
- `delay(N)` 挂起期间 worker 线程被归还线程池，**CPU 实际闲置**，才是真正限速
- 因此 FULL_SCAN 应使用 `delay()`，而非 `yield()`

**涉及改动**：

```
ConstRefEngine.kt
  analyzeFiles(files: List<File>)        → suspend fun analyzeFiles(...)
  analyzePending()                       → suspend fun analyzePending()
  initializeFullScan(...) 内部 lambda   → lambda 体变为 suspend
  launchSceneTaskLocked(scene, action)  → action: suspend () -> Unit
  maybeThrottleIo(processedCount)       → suspend fun maybeThrottleIo(processedCount)
```

**`maybeThrottleIo` 改造**：

```kotlin
// 原：Thread.sleep（持锁，不释放 CPU）
private fun maybeThrottleIo(processedCount: Int) {
    if (ioThrottleSleepMs <= 0L || processedCount % ioThrottleEveryNFiles != 0) return
    Thread.sleep(ioThrottleSleepMs)
}

// 新：delay（挂起，真正释放 CPU；delay(0) 等价于 yield，仍有让步效果）
private suspend fun maybeThrottleIo(processedCount: Int) {
    if (processedCount % ioThrottleEveryNFiles != 0) return
    delay(ioThrottleSleepMs)
}
```

**FULL_SCAN 默认限速参数**（新增常量，可通过系统属性覆盖）：

```kotlin
companion object {
    private const val DEFAULT_IO_THROTTLE_MS = 10L    // 每批 delay 10ms
    private const val DEFAULT_IO_THROTTLE_EVERY = 50  // 每 50 个文件触发一次
}
```

效果估算：10206 文件 / 50 = ~204 次 delay(10ms) ≈ 额外 2 秒挂起，CPU 负载大幅降低。

**`analysisMutex` 拆锁说明**：

`delay` 期间不可持有 `ReentrantLock`，需将 `analysisMutex` 替换为
`kotlinx.coroutines.sync.Mutex`，拆锁到 per-file 粒度：

```kotlin
// checksum 阶段
existingFiles.forEachIndexed { index, file ->
    mutex.withLock {
        // checksum 逻辑
    }
    maybeThrottleIo(index + 1)  // delay 在锁外
}

// parse + upsert 阶段（每文件一个原子单元）
changedFiles.forEachIndexed { index, file ->
    mutex.withLock {
        // parseDefinitions / parseReferences / upsertFileAnalysis / sessionCache.put
    }
    maybeThrottleIo(index + 1)  // delay 在锁外
}
```

---

### Fix-4：FULL_SCAN 使用专用限并发 Dispatcher（P1）

**目标**：FULL_SCAN 不占用 `Dispatchers.Default`，彻底隔离对 IDE 其他后台任务的影响。

**方案**：为 FULL_SCAN 场景单独创建 `limitedParallelism(1)` dispatcher：

```kotlin
private val fullScanDispatcher = Dispatchers.IO.limitedParallelism(1)

private fun launchSceneTaskLocked(scene: AnalyzeScene, action: suspend () -> Unit) {
    val dispatcher = when (scene) {
        AnalyzeScene.FULL_SCAN -> fullScanDispatcher
        else -> backgroundTaskRunner.dispatcher  // 原有 Default
    }
    // launch(dispatcher) { action() }
}
```

`Dispatchers.IO.limitedParallelism(1)` 使用 IO 线程池，与 `Dispatchers.Default` 物理隔离，
不挤占 IDE 的 DefaultDispatcher worker。

---

### Fix-5：`ConstRefCacheDatabase` 引入长连接（P0）

**目标**：消除每次查询重建连接 + 重复 PRAGMA 的开销，降低 SQLite 操作基础成本。

**方案**：改为单例持久化连接，连接在 `init()` 时创建，`close()` 时关闭：

```kotlin
class ConstRefCacheDatabase(...) {
    private lateinit var sharedConnection: Connection

    @Synchronized
    fun init() {
        SqLiteDriverLoader.load(logger)
        dbFile.parentFile?.mkdirs()
        sharedConnection = DriverManager.getConnection(url)
        applyConnectionPragmas(sharedConnection)
        // ... 现有 schema 检查逻辑 ...
    }

    @Synchronized
    fun close() {
        if (::sharedConnection.isInitialized && !sharedConnection.isClosed) {
            sharedConnection.close()
        }
    }

    private inline fun <T> withConnection(block: (Connection) -> T): T {
        return block(sharedConnection)
    }
}
```

**注意事项**：
- SQLite WAL 模式（已配置）下单连接串行读写安全，`@Synchronized` 保证串行
- `recreateDatabase()` 需先 `sharedConnection.close()`，删库，再重新调用 `init()`
- `ConstRefEngine.dispose()` 需调用 `database.close()`
- 不需要引入第三方连接池库

---

## 三、不采纳项说明

| Codex 方案 | 不采纳原因 |
|-----------|-----------|
| P0-1：首次启动只扫"最近改动模块" | 需要"模块优先级"数据，实现复杂；首次扫不完整存在影响集误判风险；本次 Fix-3 限速已能兜底 |

---

## 四、改动文件清单

| 文件 | 改动内容 | Fix |
|------|---------|-----|
| `ConstRefEngine.kt` | `parseReferencesByDbSessionMode` 增加空候选短路 | Fix-1 |
| `ConstRefSessionCache.kt` | `cleanupExpiredLocked` 加时间间隔节流 | Fix-2 |
| `ConstRefEngine.kt` | `analyzeFiles` / `analyzePending` 改 suspend；`analysisMutex` 改 `kotlinx Mutex`；拆锁至 per-file 粒度；`maybeThrottleIo` 改 suspend + `delay()`；FULL_SCAN 默认限速参数 | Fix-3 |
| `ConstRefEngine.kt` | FULL_SCAN 使用 `Dispatchers.IO.limitedParallelism(1)` | Fix-4 |
| `ConstRefCacheDatabase.kt` | `withConnection` 改持久长连接；新增 `close()`；`recreateDatabase` 补关闭/重建逻辑 | Fix-5 |

---

## 五、验证方案

### 5.1 功能验证（回归）

1. **首次扫描不冻结**：大型项目（10000+ 文件）首次启动时，AS 保持可交互；
   freeze 报告不再出现 `ConstRefEngine` 调用链
2. **增量路径正确**：`ConstRefEngineTest` / `ConstRefCacheDatabaseTest` 全部通过
3. **SQLite 连接复用**：日志中 `Loading SQLite JDBC driver` 仅出现一次（init 时）
4. **const-ref 影响集不回退**：核心回归用例影响集与基线一致

### 5.2 性能基准测试（新增）

**目标**：提供可重复执行的性能基准，用于评估首次扫描与增量扫描是否符合"低消耗后台"预期。

**测试设计**：接受外部工程路径作为输入，分别执行首次扫描（清空 DB）和增量扫描（DB 已热），
采集以下指标：

| 指标 | 采集方式 | 说明 |
|------|---------|------|
| 总耗时（ms） | FULL_SCAN finished 日志 | 区分首次 / 增量 |
| 文件数 / 目录数 | full scan progress 日志 | `totalFiles` / `totalDirs` |
| analysisReuseHit 率 | checksum resolve stats 日志 | `analysisReuseHit / fingerprintHit`，增量应接近 100% |
| CPU 占用（%） | `top -pid` 或 JVM MXBean | 采样间隔 1s，取 p50 / p95 |
| 堆内存使用（MB） | `MemoryMXBean.heapMemoryUsage` | 扫描前 / 扫描中峰值 / 扫描后 |
| SQLite 查询次数 | 在 `queryLatestDefinitionsByWhere` 入口埋点计数 | 区分首次 / 增量 |
| SQLite 单次耗时（ms） | `queryLatestDefinitionsByWhere` 耗时分布 | p50 / p95 / p99 / max |
| `cleanupExpiredLocked` 触发次数 | 埋点计数 | 修复前后对比 |
| IO throttle delay 实际总时长 | `maybeThrottleIo` 累计 delay 时长 | 验证限速是否生效 |

**测试执行流程**：

```
1. 准备阶段
   - 指定外部工程根目录（如 JOOX_Android_3）
   - 删除 const_ref_shared.db（模拟首次启动）

2. 首次扫描
   - 启动 ConstRefEngine，调用 initializeFullScan
   - 等待 FULL_SCAN finished
   - 采集上述全部指标，输出到 benchmark_first_scan.json

3. 增量扫描
   - 不清理 DB，直接重新调用 initializeFullScan
   - 等待 FULL_SCAN finished
   - 采集上述全部指标，输出到 benchmark_incremental_scan.json

4. 对比输出
   - 打印首次 vs 增量各指标对比表
   - 标记不符合预期的指标（见下方验收口径）
```

**验收口径**：

| 指标 | 首次扫描 | 增量扫描 |
|------|---------|---------|
| CPU p95 | < 30%（原 91%） | < 10% |
| 总耗时 | ≤ 原来 1.2 倍（约 ≤ 110s） | < 10s |
| analysisReuseHit 率 | N/A（全未命中） | ≥ 95% |
| SQLite p95 单次耗时 | < 500ms | < 100ms |
| 堆峰值增量 | < 500MB | < 100MB |

**实现位置建议**：
- 新增 `ConstRefEngineBenchmarkTest`，放在 `main/src/test/` 下，通过系统属性传入工程路径
- 默认 skip（`@Ignore`），需显式传入 `-Dbenchmark.project.dir=/path/to/project` 才运行
- 输出 JSON 文件路径可通过 `-Dbenchmark.output.dir` 指定

---

## 六、不在本次修复范围内

- OR 条件 SQL 优化（400 OR 子句改临时表）：推后评估
- N+1 查询 `loadLatestDefinitionsByIdentities`：调用频率低，推后评估
- FULL_SCAN 并发化（多线程扫描不同 sourceDir）：需要更大重构，另起方案
- Codex P0-1 首次启动分模块预算扫描：正确性风险高，另起方案评估
