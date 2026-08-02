# JOOX_Android_3 ConstRef 评估文字报告（2026-03-06）

## 1. 背景
- 目标：复用 `docs/task/2026-03/CONST_REF_ENGINE_FREEZE_FIX_PLAN_2026-03-06.md` 中“性能基准测试”思路，对外部工程 `JOOX_Android_3` 进行首次与增量两轮评估。
- 评估维度：耗时、CPU、内存、IO 代理指标（DB 大小）、复用命中相关指标。
- 工程规模（本次扫描集合）：
  - sourceFileCount: 5558
  - sourceTotalBytes: 89399525

## 2. 本次做了什么
- 新增了基准测试类：
  - `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngineBenchmarkTest.kt`
- 执行方式：
  - 通过 `:main:test --tests ConstRefEngineBenchmarkTest.benchmarkFirstAndIncrementalFullScan`
  - 传入系统参数：
    - `benchmark.project.dir=/Users/wormchen/IdeaProjects/joox/JOOX_Android_3`
    - `benchmark.output.dir=/tmp/constref_benchmark/joox_20260306`
- 两轮评估动作：
  - 第一轮（first_scan）：清理 shared DB 后执行扫描。
  - 第二轮（incremental_scan）：保留 DB，直接再次执行扫描。
- 采样方式：
  - CPU：基于 `OperatingSystemMXBean.processCpuLoad` 1s 采样，计算 p50/p95。
  - Heap：基于 `MemoryMXBean.heapMemoryUsage.used` 1s 采样，记录 before/peak/after。
  - IO 代理：使用 const-ref sqlite 文件大小（db/wal/shm 合计）作为持久化开销代理。
  - 复用指标：记录 checksum/analysis reuse 命中统计。
- 结果落盘：
  - `benchmark_first_scan.json`
  - `benchmark_incremental_scan.json`
  - `benchmark_compare.json`

## 3. 结果（原始数值）

### 3.1 首次扫描（first_scan）
- durationMs: 466995
- cpu.p50: 6.5971
- cpu.p95: 13.7967
- cpu.samples: 466
- heap.beforeMb: 48.4531
- heap.peakMb: 3154.1123
- heap.afterMb: 1221.2764
- checksum.mtimeHit: 0
- checksum.fingerprintHit: 5558
- checksum.crcMiss: 0
- checksum.analysisReuseHit: 0
- checksum.analysisReuseRate: 0.0000
- ioProxy.dbSizeBytes: 38256640

### 3.2 增量扫描（incremental_scan）
- durationMs: 9619
- cpu.p50: 6.4109
- cpu.p95: 7.8995
- cpu.samples: 10
- heap.beforeMb: 779.8457
- heap.peakMb: 1199.5119
- heap.afterMb: 1014.6089
- checksum.mtimeHit: 5558
- checksum.fingerprintHit: 0
- checksum.crcMiss: 0
- checksum.analysisReuseHit: 5558
- checksum.analysisReuseRate: 1.0000
- ioProxy.dbSizeBytes: 38256640

### 3.3 两轮对比（incremental - first）
- durationMs: -457376
- cpu.p95: -5.8972
- heap.peakMb: -1954.6004
- dbSizeBytes: 0

## 4. 产物位置
- `docs/task/2026-03/benchmark_first_scan.json`
- `docs/task/2026-03/benchmark_incremental_scan.json`
- `docs/task/2026-03/benchmark_compare.json`
- `docs/task/2026-03/REPORT.md`
- `docs/task/2026-03/ANALYSIS_REPORT.md`
