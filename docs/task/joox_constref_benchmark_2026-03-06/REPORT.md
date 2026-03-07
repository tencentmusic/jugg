# JOOX_Android_3 ConstRef Benchmark Report (2026-03-06)

## Artifacts
- benchmark_first_scan.json
- benchmark_incremental_scan.json
- benchmark_compare.json

## Key Metrics
- First scan:
  - durationMs: 466995
  - cpu.p95: 13.7967
  - heap.peakMb: 3154.1123
  - dbSizeBytes: 38256640
- Incremental scan:
  - durationMs: 9619
  - cpu.p95: 7.8995
  - heap.peakMb: 1199.5119
  - analysisReuseRate: 1.0

## Findings
1. First scan latency is high (~7m47s).
2. First scan heap peak is high (~3.15GB).
3. Incremental scan is much better (~9.6s) with full reuse.
