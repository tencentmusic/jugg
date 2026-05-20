# ConstRef 资源占用评估步骤

本文档分两部分：

1. **独立 benchmark**：直接运行 `ConstRefEngine` full scan，不启动 IDE，用于对比 old-profile / new-profile 的耗时、CPU、DB/IO proxy。
2. **新版 IDE smoke**：只验证新版在真实 IDE 内不会明显 freeze，不作为精确性能归因。

## 1. 独立 Benchmark

### 1.1 适用口径

主对比数据使用：

```text
main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefFullScanResourceBenchmarkTest.kt
```

这个测试会：

- 直接构造 `ConstRefEngine + ConstRefAnalyzer + ConstRefCacheDatabase`；
- 通过 `initializeFullScan()` 跑真实 `FULL_SCAN` 场景；
- 同一轮内跑 `cold` 和 `warm` 两次；
- 输出 `constref_fullscan_cold.json`、`constref_fullscan_warm.json`、`constref_fullscan_summary.json`。

它不会启动 Android Studio，因此能规避 IDE indexing、Gradle sync、VFS、其他插件生命周期等干扰。它不能证明 IDE 体感不卡顿，所以还需要第 2 节 smoke。

### 1.2 准备

```bash
PROJECT=/Users/wormchen/IdeaProjects/joox/JOOX_Android
OUT_ROOT=/tmp/jugg-constref-benchmark
mkdir -p "$OUT_ROOT"
```

建议先确认没有其他大编译/下载任务在跑。下面命令里的 `PROJECT` 是必填项；`OUT_ROOT` 如果未设置，会自动使用 `/tmp/jugg-constref-benchmark`，避免展开成 `/old-profile` 这类根目录路径。

### 1.3 old-profile

old-profile 用新 benchmark 类模拟旧 full scan 的保守参数：`3000ms / 50 files`。这适合专门评估本次 throttle 策略变化，不需要 checkout 旧 commit；相比真实旧值 `10000ms / 50 files`，耗时约缩短到 30%，更适合 smoke benchmark。

JOOX 这类大仓库在 old-profile 下仍会比较慢。benchmark 会按源码文件数和 throttle 参数自动放大等待超时，并在启动时打印 `sourceFiles`、`timeoutMs`、`outputDir`。如果你希望显式控制超时，可追加 `-Dbenchmark.constref.timeout.ms=<毫秒>`。

```bash
./gradlew :main:test \
  --rerun-tasks \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefFullScanResourceBenchmarkTest.benchmarkFullScanColdAndWarm' \
  -Dbenchmark.project.dir="${PROJECT:?set PROJECT first}" \
  -Dbenchmark.output.dir="${OUT_ROOT:-/tmp/jugg-constref-benchmark}/old-profile" \
  -Dbenchmark.constref.reset.cache=true \
  -Djugg.constref.fullscan.io.throttle.ms=3000 \
  -Djugg.constref.fullscan.io.throttle.every=50
```

运行过程中可以另开终端观察进度：

```bash
tail -f "${OUT_ROOT:-/tmp/jugg-constref-benchmark}/old-profile/constref_fullscan_cold_progress.log"
```

判断口径：

| 现象 | 含义 |
|---|---|
| 持续出现 `heartbeat`，且 `processCpuMs` / `heapMb` / `dbBytes` 有变化 | JVM 仍在跑，通常是在分析当前 batch 或当前 source root。 |
| 出现 `ConstRefEngine full scan progress` | source root 级别进度已经前进。 |
| 长时间只有 `heartbeat`，且 `processCpuMs`、`dbBytes` 基本不变 | 可能卡住，建议中断并保留 progress log。 |
| 出现 `timeout scenario=cold` | benchmark 等待超时，失败信息会带 progress log 路径。 |

### 1.4 new-profile

new-profile 使用当前默认参数：`500ms / 200 files`。

```bash
./gradlew :main:test \
  --rerun-tasks \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefFullScanResourceBenchmarkTest.benchmarkFullScanColdAndWarm' \
  -Dbenchmark.project.dir="${PROJECT:?set PROJECT first}" \
  -Dbenchmark.output.dir="${OUT_ROOT:-/tmp/jugg-constref-benchmark}/new-profile" \
  -Dbenchmark.constref.reset.cache=true
```

### 1.5 汇总对比

```bash
tools/constref_benchmark_compare.py \
  --before "${OUT_ROOT:-/tmp/jugg-constref-benchmark}/old-profile" \
  --after "${OUT_ROOT:-/tmp/jugg-constref-benchmark}/new-profile" \
  --before-label old-profile \
  --after-label new-profile
```

重点看：

| 指标 | 含义 |
|---|---|
| `duration` | benchmark wall time |
| `files/reused/analyzed` | full scan 文件数、复用数、实际分析数 |
| `cpu/wall` | 进程 CPU time / wall time，越接近 1 越接近单核持续工作 |
| `cpu p95` | JVM 进程 CPU load 采样 P95 |
| `heap peak MB` | benchmark JVM heap 峰值 |
| `phase logged` | 已打印 phase breakdown 的累计 active time，只代表日志覆盖到的 batch |
| `db bytes` | const-ref DB + WAL + SHM 大小，作为 IO proxy |
| `throttle` | 本轮实际 throttle 日志 |

预期：

- old-profile cold 总耗时会很长，因为主动 sleep 多；
- new-profile cold 总耗时应明显下降；
- new-profile 的瞬时 CPU 可能更高，但 `cpu/wall` 不应长期明显超过单核级别；
- warm 应大量复用缓存，耗时明显低于 cold。

如果 old-profile 运行仍然偏久，先看 Gradle 输出里的 `[CONSTREF_BENCH] sourceFiles=..., timeoutMs=...`，这个 timeout 已包含 throttle sleep 估算和额外解析预算。

### 1.6 源码级 before/after 可选口径

如果要严格用旧 commit 源码运行 benchmark，可以：

1. 切到 `8a46f2415`；
2. 只把 benchmark 测试和 compare 脚本 cherry-pick / apply 到旧工作区；
3. 运行第 1.3 节命令。

但本次 full scan 差异主要是 throttle 参数变化，推荐优先使用 old-profile/new-profile；数据更干净，执行更简单。

## 2. 新版 IDE Smoke

### 2.1 适用口径

IDE smoke 只做新版，目标是确认真实 Android Studio 内：

- full scan 能正常完成；
- 没有明显 freeze；
- 没有大量 readiness timeout；
- 新版 throttle 日志正确。

它不是精确能耗对比；`iostat` 和 `powermetrics` 仍会受其他软件影响。

### 2.2 运行

```bash
PROJECT=/Users/wormchen/IdeaProjects/joox/JOOX_Android
PID=$(ps ax -o pid=,command= | awk '/\/Android Studio\.app\/Contents\/MacOS\/studio($| )/ { print $1 }' | tail -n 1)

tools/constref_resource_capture.sh \
  --project "$PROJECT" \
  --label after-ide-smoke \
  --pid "$PID" \
  --reset-cache \
  --powermetrics
```

如果 `powermetrics` 需要 sudo 密码，建议先执行：

```bash
sudo -v
```

### 2.3 Smoke 汇总

```bash
tools/constref_resource_summarize.py /tmp/jugg-constref-resource/*after-ide-smoke
```

再检查关键日志：

```bash
rg -n "ConstRefEngine io throttle enabled|full scan progress|uiFreezeStarted|awaitAnalysis timeout|analysis not ready" \
  /tmp/jugg-constref-resource/*after-ide-smoke/compile_tail.log
```

### 2.4 Smoke 判断口径

通过条件：

- `ConstRefEngine full scan progress, final=true` 正常出现；
- throttle 日志应包含 `fullScan=500ms/200files`、`preCompile=0ms/1files`、`onDemand=0ms/1files`；
- 没有 `uiFreezeStarted` 或 freeze dump 相关信号；
- 没有大量 `awaitAnalysis timeout` / `analysis not ready`；
- `ps.log` 不应很快出现 `PROCESS_EXITED`，否则说明 PID 选错。

## 3. 输出目录

| 场景 | 默认输出 |
|---|---|
| 独立 benchmark | `/tmp/jugg-constref-benchmark` |
| IDE smoke | `/tmp/jugg-constref-resource` |

IDE smoke 的 `--reset-cache` 不会直接删除旧缓存，而是把 `~/.jugg/const_ref` 移到 `~/.jugg/const_ref.backup.<run_id>`。如果 `metadata.env` 里 `cache_reset_status=missing`，表示脚本启动时没有找到可移动的 `~/.jugg/const_ref`。
