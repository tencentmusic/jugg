# ConstRef resource comparison guide

Use two complementary checks:

1. **Standalone benchmark:** Run a real `ConstRefEngine` full scan without the IDE to compare elapsed time, process CPU, heap, and database-size proxies under two explicit throttle profiles.
2. **IDE smoke check:** Verify that a full scan finishes in Android Studio without an obvious freeze. This is not a controlled performance comparison.

The current `ConstRefEngine` full-scan default is **3000 ms per 50 files**. The faster **500 ms per 200 files** setting below is an experimental override, not a new default. The benchmark's timeout estimator still has its own `500/200` fallback, so pass both full-scan properties for each profile. Verify the actual throttle in the resulting logs.

## 1. Standalone benchmark

### 1.1 Scope

The opt-in test is:

```text
main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefFullScanResourceBenchmarkTest.kt
```

It constructs `ConstRefEngine`, `ConstRefAnalyzer`, and `ConstRefCacheDatabase`, runs `initializeFullScan()` for cold and warm rounds, and writes `constref_fullscan_cold.json`, `constref_fullscan_warm.json`, and `constref_fullscan_summary.json`. It avoids IDE indexing, Gradle sync, VFS, and plugin lifecycle interference. It cannot prove that the IDE remains responsive; use the smoke check in §2 for that.

### 1.2 Preparation

```bash
PROJECT=/absolute/path/to/android/project
OUT_ROOT=/tmp/jugg-constref-benchmark
mkdir -p "$OUT_ROOT"
```

Avoid other large builds or downloads during the comparison. `PROJECT` is required. If `OUT_ROOT` is unset, the commands below use `/tmp/jugg-constref-benchmark`. The benchmark also prints `sourceFiles`, `timeoutMs`, and `outputDir`; set `-Dbenchmark.constref.timeout.ms=<milliseconds>` if the estimated timeout is insufficient.

### 1.3 Baseline: current full-scan default

Pass the values explicitly so the benchmark and the engine agree and future default changes do not silently alter this comparison:

```bash
./gradlew :main:test \
  --rerun-tasks \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefFullScanResourceBenchmarkTest.benchmarkFullScanColdAndWarm' \
  -Dbenchmark.project.dir="${PROJECT:?set PROJECT first}" \
  -Dbenchmark.output.dir="${OUT_ROOT:-/tmp/jugg-constref-benchmark}/baseline" \
  -Dbenchmark.constref.reset.cache=true \
  -Djugg.constref.fullscan.io.throttle.ms=3000 \
  -Djugg.constref.fullscan.io.throttle.every=50
```

Monitor a long-running cold scan from another terminal:

```bash
tail -f "${OUT_ROOT:-/tmp/jugg-constref-benchmark}/baseline/constref_fullscan_cold_progress.log"
```

| Signal | Interpretation |
|---|---|
| Repeated `heartbeat` with changing `processCpuMs`, `heapMb`, or `dbBytes` | The JVM is still working, often on the current batch or source root. |
| `ConstRefEngine full scan progress` | Source-root progress has advanced. |
| Repeated `heartbeat` with little CPU or DB change | Possible stall; stop the run if necessary and keep the progress log. |
| `timeout scenario=cold` | The wait expired; the test failure includes the progress-log path. |

### 1.4 Experimental faster profile

This profile uses explicit `500 ms / 200 files` overrides. It is a comparison scenario, not a claim about the current product default:

```bash
./gradlew :main:test \
  --rerun-tasks \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefFullScanResourceBenchmarkTest.benchmarkFullScanColdAndWarm' \
  -Dbenchmark.project.dir="${PROJECT:?set PROJECT first}" \
  -Dbenchmark.output.dir="${OUT_ROOT:-/tmp/jugg-constref-benchmark}/fast-profile" \
  -Dbenchmark.constref.reset.cache=true \
  -Djugg.constref.fullscan.io.throttle.ms=500 \
  -Djugg.constref.fullscan.io.throttle.every=200
```

### 1.5 Compare results

```bash
tools/constref_benchmark_compare.py \
  --before "${OUT_ROOT:-/tmp/jugg-constref-benchmark}/baseline" \
  --after "${OUT_ROOT:-/tmp/jugg-constref-benchmark}/fast-profile" \
  --before-label baseline \
  --after-label fast-profile
```

| Metric | Meaning |
|---|---|
| `duration` | Wall-clock benchmark time. |
| `files/reused/analyzed` | Scanned, cache-reused, and parsed file counts. |
| `cpu/wall` | Process CPU time divided by wall time; roughly 1 means sustained single-core work. |
| `cpu p95` | P95 of sampled process CPU load. |
| `heap peak MB` | Peak benchmark JVM heap. |
| `phase logged` | Active time in batches that emitted phase breakdowns, not all active time. |
| `db bytes` | ConstRef DB plus WAL/SHM size, an I/O proxy. |
| `throttle` | Actual throttle recorded for this round. |

A faster cold run is expected with less sleeping, while instantaneous CPU may rise. A warm round should reuse much of the cache. Treat these as hypotheses to check, not pass/fail thresholds. If the baseline is unexpectedly slow, inspect `[CONSTREF_BENCH] sourceFiles=..., timeoutMs=...` in Gradle output. The estimate includes throttle sleeping and additional parsing time.

For a true source-version comparison, run the same benchmark against each revision in separate worktrees. Do not infer a source-version improvement from two throttle profiles on one revision.

## 2. IDE smoke check

### 2.1 Scope

On the current plugin build, check that a real Android Studio full scan completes, produces its throttle log, and has no obvious freeze or repeated readiness timeouts. `iostat` and `powermetrics` are affected by other processes, so their output is only supporting evidence.

### 2.2 Capture

```bash
PROJECT=/absolute/path/to/android/project
PID=$(ps ax -o pid=,command= | awk '/\/Android Studio\.app\/Contents\/MacOS\/studio($| )/ { print $1 }' | tail -n 1)

tools/constref_resource_capture.sh \
  --project "$PROJECT" \
  --label ide-smoke \
  --pid "$PID" \
  --reset-cache \
  --powermetrics
```

Omit `--powermetrics` when sudo sampling is unavailable. If you use it, authenticate first with `sudo -v`. Confirm the selected PID belongs to the target IDE; otherwise supply `--pid` manually. The `--reset-cache` option moves the old `~/.jugg/const_ref` to a timestamped backup rather than deleting it.

### 2.3 Review

```bash
tools/constref_resource_summarize.py /tmp/jugg-constref-resource/*ide-smoke
rg -n "ConstRefEngine io throttle enabled|full scan progress|uiFreezeStarted|awaitAnalysis timeout|analysis not ready" \
  /tmp/jugg-constref-resource/*ide-smoke/compile_tail.log
```

Look for `ConstRefEngine full scan progress, final=true` and the **actual** `fullScan=...` throttle value. With current defaults and no overrides, expect `fullScan=3000ms/50files`, `preCompile=0ms/1files`, and `onDemand=0ms/1files`. No `uiFreezeStarted` or repeated `awaitAnalysis timeout` / `analysis not ready` signals should appear. A quick `PROCESS_EXITED` in `ps.log` usually means the wrong PID was selected.

## 3. Output locations

| Check | Default output root |
|---|---|
| Standalone benchmark | `/tmp/jugg-constref-benchmark` |
| IDE smoke | `/tmp/jugg-constref-resource` |

If `metadata.env` reports `cache_reset_status=missing`, the capture script did not find `~/.jugg/const_ref` to move when it started.
