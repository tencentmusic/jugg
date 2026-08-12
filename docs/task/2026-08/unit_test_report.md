# 单元测试执行与优化报告

## 执行结论

- 根工程 JVM 单元测试执行成功，耗时 26 分 20 秒，低于 30 分钟目标。
- `android_demo_project` JVM 单元测试执行成功，耗时 14 秒。
- 共发现 2007 个测试，执行 2002 个，测试自身跳过 5 个，失败 0 个。
- 完整类级耗时明细见 [unit_test_class_durations.csv](unit_test_class_durations.csv)，共 278 个测试类。
- 本次未发现需要仅记录而不修改的生产代码实现错误。

## 执行范围

根工程执行命令：

```text
./gradlew test -I /tmp/jugg-skip-device-tests.gradle --continue --rerun-tasks --console=plain --stacktrace
```

示例工程执行命令：

```text
cd android_demo_project
./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain --continue
```

明确排除以下需要真实设备或模拟器的测试类：

- `com.sickworm.intellij.jugg.deploy.DeployTargetManagerTest`
- `com.sickworm.intellij.jugg.manager.TopLevelFlowTest`
- `com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest`
- `com.sickworm.intellij.jugg.manager.AndroidTestTopLevelFlowTest`
- `com.sickworm.intellij.jugg.manager.KmpComposeDeployFlowTest`
- `com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManagerTest`

同时排除 `local.*` 开发者本机测试。这些测试包含个人绝对路径，不属于可复现的工程单元测试。

## 结果汇总

| 范围 | 测试类 | 测试数 | 跳过 | 失败 | 类耗时合计 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `cmd_line` | 2 | 6 | 0 | 0 | 43.536 秒 |
| `main` | 164 | 1275 | 5 | 0 | 463.879 秒 |
| `idea` | 108 | 719 | 0 | 0 | 922.300 秒 |
| `android_demo_project` | 4 | 7 | 0 | 0 | 0.014 秒 |
| 合计 | 278 | 2007 | 5 | 0 | 1429.729 秒 |

类耗时合计是 JUnit XML 中各测试类的运行时间之和，不包含 Gradle 配置、编译、instrumentation 和报告生成时间，因此不等于命令墙钟时间。

测试自身跳过的 5 个用例为：

- `ConstRefEngineBenchmarkTest.diagnoseKotlinParserHeapRetention`
- `ConstRefEngineBenchmarkTest.diagnoseAnalysisResidentMemory`
- `ConstRefEngineBenchmarkTest.benchmarkFirstAndIncrementalFullScan`
- `ConstRefFullScanResourceBenchmarkTest.benchmarkFullScanColdAndWarm`
- `RequiresDeviceRuleTest.missing device should start configured emulator and wait until online`

## 最慢测试类

| 排名 | 测试类 | 测试数 | 耗时 |
| ---: | --- | ---: | ---: |
| 1 | `KmpComposeFlowReproTest` | 20 | 236.274 秒 |
| 2 | `KotlinCompileTest` | 13 | 147.910 秒 |
| 3 | `DataBindingCompileTest` | 14 | 134.454 秒 |
| 4 | `DataBindingCompileFallbackTest` | 14 | 127.193 秒 |
| 5 | `BuildDemoApkTest` | 7 | 74.969 秒 |
| 6 | `DeployDataGeneratorTest` | 25 | 62.729 秒 |
| 7 | `JuggCompileForDataBindingTest` | 14 | 60.916 秒 |
| 8 | `TapMcpToolActionTest` | 31 | 53.370 秒 |
| 9 | `ReadProjectInfoGradle9CompatTest` | 10 | 51.626 秒 |
| 10 | `DeployDataGeneratorReleaseTest` | 5 | 47.901 秒 |

## 基线与优化结果

首次根工程全量执行耗时 41 分 53 秒。失败包括测试模块并行修改共享 `android_demo_project`、测试夹具未恢复真实文件、内存缓存未跟随工程信息文件刷新，以及测试断言绑定 Gradle 内部缓存状态等问题。

最终根工程全量执行耗时 26 分 20 秒，缩短约 15 分 33 秒，降幅约 37%。主要优化如下：

- `GitManagerTest` 和 `GitManagerWorktreeTest` 改用最小临时 Git 仓库，不再复制完整 Android 示例工程。两类合计耗时从约 875 秒降至 5.562 秒，原有 18 个行为断言全部保留。
- `cmd_line`、`main`、`idea` 测试任务按顺序执行，避免三个模块同时删除、编译或修改同一示例工程造成竞争条件。
- 命令行测试的 Jugg 构建备份移至系统临时目录，避免备份目录递归进入自身 classpath，并使用 `finally` 保证恢复。
- DataBinding 测试类启动时重建干净基线，保留每个用例原有的 assemble 语义，避免继承 Kotlin 版本切换或前一模块留下的增量产物。
- 工程信息序列化测试改为复制只读夹具到临时构建目录，避免直接改写仓库夹具。
- 文件变更测试恢复目标文件的真实原内容，而不是假设修改源目录中存在同名基线文件。
- `AssembleAndroidProjectOnce` 在工程信息文件缺失或更新时重新 assemble 并刷新序列化缓存，避免读取过期模块路径。

## 测试错误修复与价值判断

- 修正 `DeployFileManagerDexMergeTest` 对已变更构造参数的调用，恢复测试源码编译。
- `ApkParserProcessLauncherTest` 在用例前恢复 `TestPlatformApi`，隔离其他测试修改的全局平台实现。
- 删除 `ReadProjectInfoGradle9CompatTest` 对 `mergeExtDexDebug FROM-CACHE` 的内部任务状态断言。Gradle 可在运行时 classpath 指纹变化后合法命中构建缓存；测试仍保留 APK 中 bootstrap application 与 app component factory 的可观察结果断言，因此未损失用户行为保护。
- 排除 `local.*` 本机专用测试，避免个人绝对路径在通用环境中造成无价值失败。

## 剩余失败

无。最终根工程与示例工程 JUnit XML 中失败和错误均为 0。

## 生产代码问题评估

本轮失败均定位为测试源码兼容、测试隔离、共享夹具竞争或过度绑定实现细节，未发现可确认的生产代码实现错误，因此没有新增生产错误建议文档，也未修改生产代码。
