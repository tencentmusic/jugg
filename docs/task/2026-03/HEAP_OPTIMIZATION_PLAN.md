# ConstRef First-Scan Heap 增量拆解与优化方案（2026-03-06）

## 1. 目标与口径
- 目标：解释 `first_scan` 的 `heap.peakMb` 为什么偏高，并给出可落地优化方案。
- 数据口径：来自 `benchmark_first_scan.json` / `benchmark_incremental_scan.json`。

## 2. 现状数据拆解

### 2.1 first_scan（重点）
- heap.beforeMb = 48.4531
- heap.peakMb = 3154.1123
- heap.afterMb = 1221.2764

拆解：
- 峰值总增量 = `peak - before` = **3105.6592 MB**
- 峰值后可回收增量（瞬时对象） = `peak - after` = **1932.8359 MB（62.2%）**
- 峰值后仍驻留增量（长生命周期对象） = `after - before` = **1172.8233 MB（37.8%）**

结论：主要问题是**瞬时对象暴涨**，但也存在较大的**驻留增长**。

### 2.2 incremental_scan（对照）
- heap.beforeMb = 779.8457
- heap.peakMb = 1199.5119
- heap.afterMb = 1014.6089
- 增量峰值 = 419.6662 MB

说明：增量扫描复用率 1.0，说明主问题集中在 full scan 的“全量解析路径”，不是增量复用路径。

## 3. 增量组成（代码级归因）

### A. 高优先级热点：重复构建 overlay definitions（瞬时内存主因）
- `analyzeFiles()` 先把所有 changed file 的 definitions 放入 `parsedDefinitionsByPath`。
- 之后逐文件调用 `parseReferencesByDbSessionMode(changedFiles = listOf(file), parsedDefinitionsByPath = parsedDefinitionsByPath)`。
- 在该函数内部，每次都会执行 `parsedDefinitionsByPath.values.flatten()` 再构建 3 个 overlay map。

影响：
- full scan 时 `changedFiles ~= 5558`，等于把“全量 definitions 展平 + 建索引”重复做了 5558 次。
- 直接导致大量中间集合与对象分配，推高 `heap.peakMb` 和 GC 压力。

### B. 中优先级热点：lookup cache 在逐文件循环前后反复清空
- 每个文件处理前后都 `sessionCache.clearLookupCache()`。
- 这会抑制同一批次文件间的查找复用，导致 DB 查询与对象构建重复发生。

### C. 中优先级热点：simple class 查询缺少 SQL 过滤
- `queryClassesBySimpleNames()` 当前先取 `buildLatestDefinitionsSql(scopeRepoKeys)` 的全集，再在 JVM 侧筛选 simpleName。
- 在 full scan 中若频繁 miss，会重复扫描较大结果集，增加 CPU 和临时对象。

### D. 低优先级（基线驻留）：解析器运行时与会话缓存
- `KotlinConstParser` 持有 `KotlinCoreEnvironment` 生命周期跟随 `ConstRefAnalyzer`。
- `ConstRefSessionCache` 默认容量：file=500, lookup=4000，TTL 15 分钟。
- 这些属于必要驻留，但在 full scan 后会抬高 `heap.afterMb` 基线。

## 4. 优化方案（按优先级）

## P0（必须先做）
1. 将 overlay definitions 的构建从“逐文件”改为“单次构建+复用”。
- 在 `analyzeFiles()` 中：
  - 先完成 `parsedDefinitionsByPath`。
  - 单次构建 `overlayDefinitionsByConstName/classConst/packageConst`。
  - 逐文件解析 references 时复用该 overlay。
- 避免在循环内 `values.flatten()`。

2. 将 references 解析改为批处理接口（可选同一步完成）。
- 新增内部函数：接收 `changedFiles` 和预构建 overlay，一次性返回 `referencesByPath`。
- 保证语义不变：仍支持 changed 文件之间的交叉引用解析。

预期收益：
- 显著降低瞬时对象分配，目标 first_scan `heap.peakMb` 降低 **25%~40%**。

## P1（建议紧随其后）
1. 缩小 lookup cache 清理范围。
- 由“每文件前后都清理”改为“每批次结束后清理”或“仅在 definitions 更新后按 key 定向失效”。

2. 给 `queryClassesBySimpleNames()` 增加 DB 侧过滤。
- SQL 层按 simpleName 或末段 class 名过滤，避免每次扫描 latest definitions 全量结果。

预期收益：
- 降低 full scan CPU 与次级内存峰值，目标 first_scan duration 再降 **10%~20%**。

## P2（守底线）
1. 为 full scan 增加分段处理与阶段性释放。
- 例如按 sourceDir 或 chunk（200~500 文件）处理，chunk 后主动清理临时容器。

2. 调整默认会话缓存上限（仅在验证后）。
- 例如 `session.file.cache.max` 从 500 降到 200~300，平衡增量命中与驻留内存。

## 5. 验证与验收标准
- 验证方式：复用当前 benchmark 用例，同项目同参数执行 first + incremental。
- 验收阈值（第一阶段）：
  - first_scan.heap.peakMb <= **2300 MB**（约降低 27%）
  - first_scan.durationMs <= **360000 ms**（6 分钟）
  - incremental 分析复用率保持 >= **0.99**
  - const ref 结果一致性：关键回归用例全通过（Java/Kotlin alias、星号导入、同包 top-level 等）

## 6. 实施顺序建议
1. 先做 P0（overlay 单次构建）并补单测。
2. 跑 benchmark 对比峰值与耗时。
3. 再做 P1（cache 失效策略 + simpleName SQL 过滤）。
4. 如仍超阈值，再做 P2（chunk 化与缓存参数调优）。
