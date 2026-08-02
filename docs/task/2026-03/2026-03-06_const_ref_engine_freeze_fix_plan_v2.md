# ConstRefEngine 卡死修复方案（仅方案，不改代码）

- 日期：2026-03-06
- 目标模块：`com.sickworm.intellij.jugg.compiler.constref.ConstRefEngine`
- 现象：Android Studio 在全量扫描期间出现长时间不可交互（UI freeze）
- 证据来源：
  - `/Users/wormchen/Downloads/AS卡死分析报告_2026-03-06_ConstRefEngine.md`
  - `/Users/wormchen/IdeaProjects/joox/JOOX_Android_3/build/jugg/log/compile_latest.log`
  - `/Users/wormchen/Library/Logs/Google/AndroidStudio2025.3.1/idea.log`
  - `/Users/wormchen/Library/Logs/Google/AndroidStudio2025.3.1/threadDumps-freeze-20260306-112023-AI-253.29346.138.2531.14876573/*`

---

## 1. 问题定性

本次问题属于 **高负载导致的 UI 假死**，不是 JVM 级死锁。

### 1.1 已确认事实

1. `diagnosticReport-lockDiagnostics.txt` 显示：
   - `AWT thread deadlocked: false`
   - `JVM did not detect any deadlocks`
2. freeze 期间热点线程为 `DefaultDispatcher-worker-2`，CPU 约 91%。
3. 热点调用链集中在：
   - `ConstRefEngine.parseReferencesByDbSessionMode`
   - `ConstRefCacheDatabase.queryLatestDefinitionsByWhere`
   - `NativeDB.step`
   - `ConstRefSessionCache.cleanupExpiredLocked -> Collection.removeIf`

### 1.2 时间线对齐（关键）

1. `compile_latest.log`：`ConstRefEngine#FULL_SCAN` 在 `2026-03-06 11:20:00` 启动。
2. `idea.log`：`uiFreezeStarted` 在 `2026-03-06 11:20:23` 触发。
3. `compile_latest.log`：`FULL_SCAN` 在 `2026-03-06 11:21:32` 结束，耗时 `91790ms`，累计扫描 `10206` 文件。
4. 结论：freeze 的触发窗口与 constref full scan 高负载窗口重叠。

---

## 2. 根因假设（按置信度）

### A. 高置信：db-session 路径查询过重

在 full scan 中，单文件候选定义查询会频繁进入 SQLite（`queryLatestDefinitionsByWhere`），造成 `NativeDB.step` 长时间占用。

### B. 高置信：会话缓存清理策略过于激进

`ConstRefSessionCache` 在读写热点路径中反复执行 `removeIf` 全量清理，CPU 样本反复命中该路径。

### C. 中置信：任务调度与 UI 线程竞争放大体感

`Dispatchers.Default` 背景高占用期间，AWT Accessibility/menu 更新频繁超时（`InvocationEvent has timed out`），体感为“AS 卡死”。

---

## 3. 修复目标

1. 在 10k+ 源文件工程中，首次 `FULL_SCAN` 不再导致 UI 不可交互。
2. const-ref 结果语义不回退（不能漏算 impacted source）。
3. 增量路径回归通过，错误可降级，不阻断主编译链。

---

## 4. 分阶段修复方案

## 阶段 P0（先止血）

### P0-1：给 FULL_SCAN 增加启动预算与降级开关

- 默认策略：首次启动只扫描“最近改动模块 + 当前编译相关模块”，其余目录延后低优先级补扫。
- 增加系统属性开关：
  - `jugg.constref.fullscan.max.files.per.start`
  - `jugg.constref.fullscan.defer.enabled`
- 目的：避免一次性压满 CPU。

### P0-2：db-session 引用解析增加短路

- 若 `collectReferenceLookupHints()` 为空，直接返回空引用。
- 若候选 definitions（DB + overlay）为空，直接返回空引用。
- 目的：避免“无收益”的 DB 查询与二次解析。

### P0-3：缓存过期清理节流

- `cleanupExpiredLocked()` 增加最小触发间隔（例如 30~60s）。
- 热路径读写只做 key 级检查，不做每次全量 `removeIf`。
- 目的：降低 `Collection.removeIf` 热点开销。

---

## 阶段 P1（性能重构）

### P1-1：减少 queryLatestDefinitionsByWhere 调用次数

- 对单文件 `hints` 先做归一化和去重，统一批量查询。
- 同一批 `changedFiles` 内共享查询结果缓存（batch-level cache），避免 per-file 重复 SQL。

### P1-2：降低 SQLite 单次查询复杂度

- 评估 `OR` 组合查询改为临时表/`VALUES` 联表查询，减少解析与执行成本。
- 对 `scopeRepoKeys` 和定义键集合做分桶，控制单 SQL 参数规模。

### P1-3：背景任务优先级与并发限制

- 限制 constref full scan worker 并发与 CPU 占用比例。
- 避免与 UI 敏感路径抢占同一调度资源。

---

## 阶段 P2（可观测与防回归）

### P2-1：新增指标

- full scan 每阶段耗时（checksum / hint / db_query / parse / upsert）
- `queryLatestDefinitionsByWhere` p50/p95/p99
- `cleanupExpiredLocked` 耗时与触发频率

### P2-2：新增回归测试

- 空 hints 文件不触发 `parseReferences`。
- 空候选 definitions 时直接短路。
- 10k 文件模拟扫描下，单次任务耗时不超过阈值。

### P2-3：灰度与回滚

- 支持快速关闭 constref 任务（功能开关）。
- 发现回归可一键切回“只走 class-node 影响分析”。

---

## 5. 验证标准（验收口径）

1. 大工程首次启动期间，IDE 可持续交互（无 30s+ 冻结）。
2. `idea.log` 中 `InvocationEvent has timed out` 显著下降。
3. freeze 采样中 `DefaultDispatcher-worker-*` 不再长期钉死 `NativeDB.step` 或 `cleanupExpiredLocked`。
4. const-ref 影响集在核心回归用例中与基线一致。

---

## 6. 实施顺序建议

1. 先做 P0-2 + P0-3（改动最小、收益快）。
2. 再做 P0-1（策略层降载）。
3. 最后推进 P1（结构性优化）并补齐 P2 指标与压测。

