# ConstRef Full Scan 内存优化方案（2026-03-07）

## 1. 问题根因

### 1.1 峰值内存来源

benchmark 数据：`heap.peakMb = 3154MB`，`heap.afterMb = 1221MB`，`heap.beforeMb = 48MB`。

当前 `analyzeFiles`（ConstRefEngine.kt:368）实现：

- **阶段一**：遍历所有 changedFiles，解析 definitions，存入 `parsedDefinitionsByPath`（全量驻留内存）
- **阶段二**：逐文件解析 references，每次调用 `parseReferencesByDbSessionMode` 时，内部执行 `parsedDefinitionsByPath.values.flatten()` 重建全量 overlay

结果：5558 个文件的 definitions 全量常驻，且阶段二每个文件循环都触发一次 O(N) flatten，内存和 CPU 双重浪费。

### 1.2 隐藏的 AST 双重解析问题

`collectReferenceLookupHints`（ConstRefAnalyzer.kt:97）内部通过 `TrackingDefinitionLookup` 调用了一次完整的 `parseReferences`，只是用于收集 hints。
随后 `parseReferencesByDbSessionMode` 又用真实的 `ConstDefinitionIndex` 再次调用 `parseReferences`。

即**阶段二每个文件实际做了 2 次 AST 解析**。

---

## 2. 优化方案：小批量两阶段 + 消除 hints 预扫描

### 2.1 核心思路

- **批量大小**：200 文件/批（可配置），兼顾事务开销与内存控制
- **阶段一**（definitions）：按批解析，批内一次事务写 DB，不写 `file_analysis_head`，写完即释放该批内存
- **阶段二**（references）：按批从 DB 查候选 definitions，直接 `parseReferences`，不再需要 hints 预扫描和内存 overlay，批内一次事务写 DB（含 `file_analysis_head`）

### 2.2 为何不需要内存 overlay

阶段一全量 definitions 已写入 DB 后，阶段二通过 `queryCandidateDefinitionsForFile` 查 DB 即可获得其他文件的 definitions。`parseReferencesByDbSessionMode` 中的 overlay 逻辑（ConstRefEngine.kt:608-615）可以直接删除。

### 2.3 为何可以消除 hints 预扫描

当前 `collectReferenceLookupHints` 做一次 dry-run AST 解析只是为了收集 DB 查询的 hints，然后第二次才真正解析 references。

改为：直接用宽泛查询（按 constName 模糊范围）从 DB 获取候选 definitions，或将 `TrackingDefinitionLookup` 和真实解析合并为一次 AST 遍历（parseReferences 一次过，返回 hints + references）。

这样阶段二每文件从 2 次 AST 解析降为 1 次。

### 2.4 中断安全性

阶段一不写 `file_analysis_head`，中断的文件没有"已分析"标记，下次重启时 checksum 不命中，自动重新分析。无脏数据风险。

---

## 3. 改动清单

### 3.1 ConstRefCacheDatabase 新增接口

```
// 仅写 definitions，不写 file_analysis_head（阶段一使用）
fun upsertBatchDefinitions(batch: List<FileDefinitionsEntry>)

// 写完整分析（阶段二使用，含 file_analysis_head）
fun upsertBatchAnalysis(batch: List<FileAnalysisEntry>)
```

两个接口内部各用一个事务处理整批，减少 fsync 次数。

### 3.2 ConstRefEngine.analyzeFiles 重构

当前两轮 forEachIndexed 改为：

```
// Phase 1: definitions in batches
changedFiles.chunked(batchSize).forEach { batch ->
    val definitionsBatch = batch.map { file ->
        file to analyzer.parseDefinitions(listOf(file))[path]
    }
    database.upsertBatchDefinitions(definitionsBatch)  // one transaction
    // batch released after this block
}

// Phase 2: references in batches
changedFiles.chunked(batchSize).forEach { batch ->
    val analysisBatch = batch.map { file ->
        val references = parseReferencesByDbOnly(file)  // no overlay, DB only
        file to (definitions, references)
    }
    database.upsertBatchAnalysis(analysisBatch)  // one transaction
    // update changeTracker, sessionCache, markAnalyzed per file
}
```

### 3.3 parseReferencesByDbSessionMode 简化

删除 overlay 构建逻辑（ConstRefEngine.kt:608-615），函数仅保留：
1. `collectReferenceLookupHints`（或合并为单次 AST）
2. `queryCandidateDefinitionsForFile`（查 DB）
3. `analyzer.parseReferences`

### 3.4 新增配置项

```
jugg.constref.batch.size   默认 200
```

与现有 `jugg.constref.io.throttle.every` 对齐，可独立调整。

---

## 4. 预期收益

| 指标 | 优化前 | 预期优化后 |
|------|--------|-----------|
| heap.peakMb | 3154MB | ~300MB（批内 200 文件 definitions） |
| DB 事务次数 | 5558 次 | ~56 次（28 批 × 2 阶段） |
| 阶段二 AST 解析次数 | 2 次/文件 | 1 次/文件 |
| first_scan durationMs | 466995ms | 预计 280000~360000ms |
| 中断安全性 | 完整 | 完整 |

---

## 5. 不在本次范围内的优化

以下优化有收益但复杂度较高，暂不纳入：

- `sessionCache` 中 references 字段裁剪（当前无读取路径）
- `queryClassesBySimpleNames` SQL 过滤优化
- `analyzeFiles` 协程并行化（受 `analysisMutex` 约束）

---

## 6. 验收目标

- `heap.peakMb <= 500MB`
- `first_scan.durationMs <= 360000ms`
- `incremental_scan.analysisReuseRate = 1.0`（不退化）
- 全量 constref 测试通过：`./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.constref.*"`
