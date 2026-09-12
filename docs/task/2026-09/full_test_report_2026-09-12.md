# 全量测试执行报告（2026-09-12）

> 任务类型：测试执行与结果记录（不新增自动化测试、不修改生产代码）
> 权威细则：[06_testing.md](../../ai_knowledge/06_testing.md)（测试入口与运行方式以该文档 §10 为准）

---

## 1. 结论速览

| 项 | 值 |
|----|----|
| 执行结论 | 全量测试执行完成，构建失败（`BUILD FAILED`，Gradle 退出码 1） |
| testcase 总数 | **2577**（343 个测试类） |
| 失败 | **44**（错误 0） |
| 跳过 | 6 |
| 耗时 > 60 秒的 testcase | **3**（全部通过） |
| 非 testcase 的基础设施失败 | 有：第一次执行（§7）出现 Gradle 报告写入失败与报告文件被外部进程删除 |

执行方式：不设置 `JUGG_TEST_SKIP_DEVICE`，即**包含设备依赖测试**（本机 `emulator-5554` 在线）。

---

## 2. 执行范围与命令

### 2.1 命令

```bash
# 在仓库根目录执行（工作目录 = 仓库根）
./gradlew test --continue --console=plain
```

- 使用根项目聚合 `test` 任务，一次覆盖所有含测试的模块（等价于文档 §10 的批量入口）。
- `--continue`：单个模块测试失败后继续执行其余模块，用于一次性收集全部失败。
- **未设置** `JUGG_TEST_SKIP_DEVICE`：设备可用，因此 `@RequiresDevice` 测试类真实执行，不进入 skip 分支。
- 遵循 §10 “禁止无 `--tests` 过滤的全量 `:main:test` / `:idea:test`”：本次未单独调用模块级 test 任务，只使用根聚合 `test`。

### 2.2 时间

| 项 | 值 |
|----|----|
| 开始 | 2026-09-12 18:46:18 (CST) |
| 结束 | 2026-09-12 19:30:52 (CST) |
| 总耗时 | **44 分 34 秒**（Gradle 自报 `BUILD FAILED in 44m 32s`） |
| 各 test 任务耗时之和 | 2404.2 秒（≈40 分 4 秒，仅统计 `<testsuite>` 累计） |

### 2.3 环境

| 项 | 值 |
|----|----|
| 系统 | macOS Darwin 25.6.0（Apple Silicon） |
| JDK | 17.0.12（JBR-17.0.12+1-1207.37-nomod），`JAVA_HOME` 指向 jbr-17.0.12 |
| Android SDK | `/Users/wormchen/Library/Android/sdk`（`local.properties` 的 `sdk.dir`） |
| 设备 | `emulator-5554`，`adb devices` 状态 `device`，`sys.boot_completed=1`，API 35 |
| Gradle | 7.3.3（仓库 wrapper），`org.gradle.daemon=false`、`org.gradle.parallel=true`、`org.gradle.caching=true` |

### 2.4 被测代码版本与隔离副本

| 项 | 值 |
|----|----|
| 报告对应版本 | `f1f28e5348562cabf78ed5da01b4594d75893dec`（`[bugfix] fix compile logs written to demo project root when running unit tests`） |
| 执行目录 | `/tmp/jugg-full-test-clone`（仓库的 APFS 克隆副本，含增量构建状态） |
| 工作区差异 | 副本内含 3 个未提交的 `android_demo_project` 夹具文件（`git status` 显示为 `A`，见 §8） |

**为什么在隔离副本中执行**：第一次在同一工作区执行时，报告文件被并发的 Gradle 构建清空/删除，机器可读结果不完整（证据见 §7）。第二次执行改用工作区克隆副本，使本仓库的 `build/test-results` 不再受其他会话的 Gradle 调用影响；源码内容与工作区一致。

---

## 3. 汇总结果

| 模块 : 任务 | 测试类 | testcase | 失败 | 错误 | 跳过 | 任务内耗时 | 任务状态 |
|-------------|-------:|---------:|-----:|-----:|-----:|-----------:|----------|
| `cmd_line:test` | 2 | 6 | 5 | 0 | 0 | 49.0s | FAILED |
| `main:test` | 191 | 1447 | 10 | 0 | 6 | 671.4s | FAILED |
| `idea:test` | 123 | 871 | 29 | 0 | 0 | 1679.9s | FAILED |
| `jvmti_agent:testDebugUnitTest` | 13 | 125 | 0 | 0 | 0 | 1.6s | FROM-CACHE |
| `jvmti_agent:testReleaseUnitTest` | 13 | 125 | 0 | 0 | 0 | 2.3s | FROM-CACHE |
| `deploy_compat:v_narwhal_feature:test` | 1 | 3 | 0 | 0 | 0 | 0.0s | 通过 |
| **合计** | **343** | **2577** | **44** | **0** | **6** | **2404.2s** | 构建失败 |

控制台摘要与 XML 完全一致：`6 tests completed, 5 failed`（cmd_line）、`1447 tests completed, 10 failed, 6 skipped`（main）、`871 tests completed, 29 failed`（idea），控制台 `FAILED` 行数 = 44，与 XML 失败数一致。

### 3.1 设备依赖测试覆盖

`@RequiresDevice` 门禁的测试类在本次运行中**真实执行**（设备在线）：

| 测试类 | testcase | 失败 | 类耗时 |
|--------|---------:|-----:|-------:|
| `com.sickworm.intellij.jugg.manager.TopLevelFlowTest` | 7 | 0 | 151.1s |
| `com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest` | 6 | 1 | 201.7s |
| `com.sickworm.intellij.jugg.manager.SplitClassLoaderFlowTest` | 2 | 0 | 139.4s |
| `com.sickworm.intellij.jugg.manager.HiltTopLevelFlowTest` | 1 | 0 | 20.9s |
| `com.sickworm.intellij.jugg.manager.AndroidTestTopLevelFlowTest` | 2 | 0 | 46.1s |
| `com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest` | 22 | 2 | 219.3s |
| `com.sickworm.intellij.jugg.manager.KmpComposeDeployFlowTest` | 2 | 1 | 73.6s |
| `com.sickworm.intellij.jugg.deploy.DeployTargetManagerTest` | 1 | 0 | 0.9s |
| `com.sickworm.intellij.jugg.deploy.JuggJvmtiAgentManagerTest` | 1 | 0 | 0.2s |
| **合计** | **44** | **4** | — |

---

## 4. 未覆盖 / 跳过范围（不得视为通过）

| 项 | 说明 |
|----|------|
| 无测试源的模块 | `custom_compilers`、`platform_compat:base_api`、`aapt2-inclink`、`tools:stub_api_generator`、`deploy_compat:interface` 及其余 `deploy_compat:v_*`（`test NO-SOURCE`），无 testcase 可执行 |
| 构建缓存复用 | `jvmti_agent` 的 250 个 testcase 由 Gradle 构建缓存还原（`FROM-CACHE`），输入未变、结果有效，但**非本次重新执行** |
| 跳过的 testcase（6） | 5 个基准/内存诊断用例：`com.sickworm.intellij.jugg.compiler.constref.ConstRefEngineBenchmarkTest#benchmarkFirstAndIncrementalFullScan`、`#diagnoseKotlinParserHeapRetention`、`#diagnoseAnalysisResidentMemory`、`com.sickworm.intellij.jugg.compiler.constref.ConstRefFullScanResourceBenchmarkTest#benchmarkFullScanColdAndWarm`、`com.sickworm.intellij.jugg.deploy.DeployHistoryDbCrcCacheTest#compareColdAndWarmCrcScanPerformance`；1 个设备探测用例：`com.sickworm.intellij.jugg.mock.RequiresDeviceRuleTest#missing device should start configured emulator and wait until online`（设备已在线，按设计跳过） |
| 未涉及 | 真机（非模拟器）矩阵、Android Studio 内运行时验证、发布回归矩阵 |

---

## 5. 单个 testcase 耗时 > 60 秒的用例

阈值：严格大于 60 秒（`<testcase time>`，单位秒）。**共 3 个，全部通过。**

| # | 类#方法 | 耗时 | 模块 / 报告来源 | 结果 |
|---|---------|-----:|-----------------|------|
| 1 | `com.sickworm.intellij.jugg.manager.SplitClassLoaderFlowTest#hotFixKeepsInstalledSplitTypesInOneClassLoader` | 70.2s | idea：`idea/build/test-results/test/TEST-com.sickworm.intellij.jugg.manager.SplitClassLoaderFlowTest.xml` | 通过 |
| 2 | `com.sickworm.intellij.jugg.manager.SplitClassLoaderFlowTest#hotFixDoesNotFlattenIsolatedSplits` | 69.3s | idea：同上 | 通过 |
| 3 | `com.sickworm.intellij.jugg.gradle.LocalGradleCompileClientTest#testFetchLibraryChanges` | 63.6s | idea：`idea/build/test-results/test/TEST-com.sickworm.intellij.jugg.gradle.LocalGradleCompileClientTest.xml` | 通过 |

### 5.1 参考：30–60 秒区间（未达阈值，仅供维护者评估耗时分布）

| 类#方法 | 耗时 | 结果 | 模块 |
|---------|-----:|------|------|
| `com.sickworm.intellij.jugg.compile.KotlinCompileTest#testKsp1Compile` | 50.8s | 通过 | idea |
| `com.sickworm.intellij.jugg.manager.KmpComposeDeployFlowTest#deployComposeResourcesAndConsumeAccessorsAtRuntime` | 49.7s | 失败 | idea |
| `com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest#compileBusinessExpectActualWithKotlin19` | 44.8s | 通过 | idea |
| `com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest#recoveryDeployWithGit` | 44.5s | 通过 | idea |
| `com.sickworm.intellij.jugg.manager.CompileConsistencyTest#testConsistency` | 44.3s | 通过 | idea |
| `com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest#compileComposeResourcesWithKotlin23` | 43.7s | 失败 | idea |
| `com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest#compileComposeResourcesWithKotlin19` | 43.6s | 通过 | idea |
| `com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest#compileBusinessExpectActualWithKotlin23` | 43.4s | 失败 | idea |
| `com.sickworm.intellij.jugg.manager.TopLevelFlowTest#testDeployIncrementalDataBindingSetterStore` | 42.5s | 通过 | idea |
| `com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorReleaseTest#testMinifyInlineEffects` | 41.8s | 通过 | main |
| `com.sickworm.intellij.jugg.compile.BuildDemoApkTest#testBuildApkRelease` | 37.4s | 通过 | idea |
| `com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest#initDeployWithGit` | 37.1s | 通过 | idea |
| `com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest#initDeployWithoutGit` | 35.1s | 通过 | idea |
| `com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest#recoveryDeployOnIsReadyIncCompileState` | 34.8s | 通过 | idea |

---

## 6. 失败用例清单（44）

### 6.1 `cmd_line` 模块（5）

报告来源：`cmd_line/build/test-results/test/TEST-com.sickworm.intellij.jugg.cmdline.CmdLineTest.xml`

| 类#方法 | 耗时 | 失败摘要（异常首要原因） |
|---------|-----:|--------------------------|
| `com.sickworm.intellij.jugg.cmdline.CmdLineTest#buildBase` | 22.4s | `java.lang.AssertionError: Expected value to be true` @ `CmdLineTest.kt:30`（`assertTrue(CmdLine().run(args))` 为 false） |
| `com.sickworm.intellij.jugg.cmdline.CmdLineTest#buildIncrementalApk` | 7.5s | 同上 |
| `com.sickworm.intellij.jugg.cmdline.CmdLineTest#buildIncrementalApkEffects` | 6.7s | 同上 |
| `com.sickworm.intellij.jugg.cmdline.CmdLineTest#buildIncrementalApkManifest` | 6.2s | 同上 |
| `com.sickworm.intellij.jugg.cmdline.CmdLineTest#buildIncrementalApkWithCustomCompilers` | 6.2s | 同上 |

补充观察（来自该报告 `system-out`）：内部 `CompileProjectCommand` 实际执行成功（`result: 0`、`BUILD SUCCESSFUL`），随后才出现 `Jugg cmdline exit. result: false`，即失败点在 CLI 的整体返回判定，而非 Gradle 编译本身。

### 6.2 `main` 模块（10）

| 类#方法 | 耗时 | 失败摘要（异常首要原因） | 报告来源 |
|---------|-----:|--------------------------|----------|
| `com.sickworm.intellij.jugg.compiler.overlay.ResourceCompileAabResGuardTest#compileResourceDirOverlay` | 0.73s | `AssertionError: expected:<32> but was:<34>` | `TEST-...ResourceCompileAabResGuardTest.xml` |
| `com.sickworm.intellij.jugg.compiler.overlay.ResourceCompileAabResGuardTest#compileResourceDirOverlayWithOldRes` | 0.62s | 产物清单不匹配：期望含 `ActivityMainBinding.java`/`TestStyleableLayoutBinding.java`，实际为 `DataBindingInfo.java` 等 | 同上 |
| `com.sickworm.intellij.jugg.compiler.overlay.ResourceCompileTest#compileResourceDirOverlay` | 0.73s | `AssertionError: expected:<32> but was:<34>` | `TEST-...ResourceCompileTest.xml` |
| `com.sickworm.intellij.jugg.compiler.overlay.ResourceCompileTest#compileResourceDirOverlayWithOldRes` | 0.62s | 产物清单不匹配（同 AabResGuard 用例） | 同上 |
| `com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest#testGenericConcreteTypeChangeTriggersInvokerRecompile` | 1.58s | `AssertionError: Expected value to be true` | `TEST-...DeployDataGeneratorTest.xml` |
| `com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderAndroidTestTest#project info preserves unsupported Compose detection reason` | 1.88s | `NullPointerException: ... "org.gradle.api.Project.getExtensions()" is null` @ `GradleProjectInfoReader.kt:763` | `TEST-...GradleProjectInfoReaderAndroidTestTest.xml` |
| `com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderAndroidTestTest#project info rejects incomplete Compose resource directory metadata` | 0.01s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderKotlinOptionsTest#built-in Kotlin compiler options populate project info without legacy plugin` | 0.003s | `GradleException: Jugg: runtime classpath is unavailable for app` @ `GradleProjectInfoReader.kt:377` | `TEST-...GradleProjectInfoReaderKotlinOptionsTest.xml` |
| `com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderKotlinOptionsTest#compiler plugin options populate project info` | 0.001s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderKotlinOptionsTest#legacy Kotlin options remain supported` | 0.001s | 同上 | 同上 |

报告来源前缀均为 `main/build/test-results/test/`。

### 6.3 `idea` 模块（29）

| 类#方法 | 耗时 | 失败摘要（异常首要原因） | 报告来源（`idea/build/test-results/test/`） |
|---------|-----:|--------------------------|---------------------------------------------|
| `com.sickworm.intellij.jugg.compile.DexCompileTest#compileClasses` | 0.02s | `AssertionError: Expected value to be true` | `TEST-...DexCompileTest.xml` |
| `com.sickworm.intellij.jugg.compile.DexCompileTest#compileClassesAndJars` | 0.003s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.compile.DexTest#dexMultipleFiles` | 0.17s | `com.android.tools.r8.CompilationFailedException`，origin `<demo>/app/build/intermediates/javac/debug/classes` | `TEST-...DexTest.xml` |
| `com.sickworm.intellij.jugg.compile.JuggCompileForDataBindingTest#testNewBindingAdapterIsReusedByNextIncrementalCompile` | 22.15s | `...ActivityDataBindingIncrementalSetterStoreSecondBinding.dex does not exist in output` | `TEST-...JuggCompileForDataBindingTest.xml` |
| `com.sickworm.intellij.jugg.compile.JuggCompileTest#compileMultiJavaAndAssetAndRes` | 0.95s | `AssertionError: expected:<12> but was:<21>` | `TEST-...JuggCompileTest.xml` |
| `com.sickworm.intellij.jugg.compile.JuggCompileTest#compileResource` | 0.90s | `AssertionError: expected:<4> but was:<13>` | 同上 |
| `com.sickworm.intellij.jugg.compile.JuggCompileTest#compileResourceAddIds` | 0.96s | `AssertionError: expected:<1> but was:<9>` | 同上 |
| `com.sickworm.intellij.jugg.compile.KotlinCompileTest#kotlin23Agp9BuiltInFullDemoCompile` | 26.25s | `IllegalStateException: clean failed, see log for details` @ `GradleBuildHelper.clean(GradleBuildHelper.kt:18)` ← `AssembleAndroidProjectOnce.ensure` | `TEST-...KotlinCompileTest.xml` |
| `com.sickworm.intellij.jugg.compile.StyleableFileGeneratorTest#test2` | 0.001s | `AssertionError: Expected value to be true` | `TEST-...StyleableFileGeneratorTest.xml` |
| `com.sickworm.intellij.jugg.compile.databinding.DataBindingCompileFallbackTest#testMultipleNewXmlDataBinding` | 7.73s | `AssertionError: Expected value to be true` | `TEST-...DataBindingCompileFallbackTest.xml` |
| `com.sickworm.intellij.jugg.compile.databinding.DataBindingCompileFallbackTest#testMultipleNewXmlViewBinding` | 13.12s | `...ActivityViewBindingNewBinding.java does not exist in output` | 同上 |
| `com.sickworm.intellij.jugg.compile.databinding.DataBindingCompileFallbackTest#testNewNodeViewBinding` | 14.51s | 同 上（缺失 `ActivityViewBindingNewBinding.java`） | 同上 |
| `com.sickworm.intellij.jugg.compile.databinding.DataBindingCompileFallbackTest#testXmlIncludeNodeViewBinding` | 6.29s | `...ActivityViewBindingIncludeBinding.java does not exist in output` | 同上 |
| `com.sickworm.intellij.jugg.compile.databinding.DataBindingCompileTest#testMultipleNewXmlDataBinding` | 7.75s | `AssertionError: Expected value to be true` | `TEST-...DataBindingCompileTest.xml` |
| `com.sickworm.intellij.jugg.compile.databinding.DataBindingCompileTest#testMultipleNewXmlViewBinding` | 13.34s | `...ActivityViewBindingNewBinding.java does not exist in output` | 同上 |
| `com.sickworm.intellij.jugg.compile.databinding.DataBindingCompileTest#testNewNodeViewBinding` | 14.70s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.compile.databinding.DataBindingCompileTest#testXmlIncludeNodeViewBinding` | 6.10s | `...ActivityViewBindingIncludeBinding.java does not exist in output` | 同上 |
| `com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest#hasApplyChangesStartupAgent should detect versioned as agent file` | 0.0s | `IllegalStateException: run-as success marker or compatible SELinux context missing; PackageManager dataDir unavailable` @ `AppSandboxExecutor.kt:103` | `TEST-...AsStartupAgentPusherTest.xml` |
| `com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest#hasApplyChangesStartupAgent should ignore jugg agent only` | 0.0s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest#pushApplyChangesStartupAgent should mkdir remote agent dir before adb push` | 0.0s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest#pushApplyChangesStartupAgent should not use shell line continuation backslashes in run-as script` | 0.0s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest#pushApplyChangesStartupAgent should push agent doll for offline app` | 0.0s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest#pushApplyChangesStartupAgent should setup studio dir and remove stale startup agents` | 0.0s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest#pushApplyChangesStartupAgent should throw when run-as cp fails` | 0.0s | `Unexpected exception, expected<DirectOverlayDeployFailedException> but was<IllegalStateException>`（同一 run-as 根因） | 同上 |
| `com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest#pushApplyChangesStartupAgent should use agent-alt on 64 bit device with 32 bit app arch` | 0.0s | run-as 根因同上 | 同上 |
| `com.sickworm.intellij.jugg.manager.KmpComposeDeployFlowTest#deployComposeResourcesAndConsumeAccessorsAtRuntime` | 49.67s | `AssertionError: Updated Compose resources were not consumed after cached baseline`（附带 logcat 日志） | `TEST-...KmpComposeDeployFlowTest.xml` |
| `com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest#compileBusinessExpectActualWithKotlin23` | 43.42s | `IllegalStateException: KMP Compose fixture assemble failed`（内部 Gradle 输出含 `kotlin_version: 2.3.20`） | `TEST-...KmpComposeFlowReproTest.xml` |
| `com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest#compileComposeResourcesWithKotlin23` | 43.75s | 同上 | 同上 |
| `com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest#recoveryDeployFromVersion1CompileContextWithGit` | 29.84s | `AssertionError: expected:<READY_DEPLOY> but was:<READY_FULL_COMPILE>` | `TEST-...TopLevelFlowWithGitTest.xml` |

### 6.4 失败聚类（供定位参考，非结论）

| 聚类 | 用例数 | 共同首要原因 |
|------|-------:|--------------|
| CLI 整体返回 false | 5 | `CmdLine().run()` 返回 false（内部 Gradle 编译已成功） |
| 资源/DataBinding 产物断言 | 12 | 资源 overlay 产物条目数或清单不匹配、生成 Binding 源码缺失 |
| `run-as` 沙箱不可用 | 8 | `AppSandboxExecutor`：`run-as` 成功标记/SELinux context 缺失、`PackageManager dataDir` 不可用（与设备状态相关） |
| Gradle 信息读取（main） | 5 | `Project.getExtensions()` 为 null、`runtime classpath is unavailable` |
| Kotlin 2.3 / AGP 9 夹具 | 3 | `databindingApt/build.gradle` 仍应用 `org.jetbrains.kotlin.android`，AGP 9 拒绝该插件 → `clean` 失败 / 夹具 assemble 失败 |
| 事件绑定与增量编译 | 2 | `com.sickworm.intellij.jugg.compile.JuggCompileForDataBindingTest` 产物缺失、`com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest` 期望 `READY_DEPLOY` 实为 `READY_FULL_COMPILE` |
| 其他单点 | 9 | D8 `CompilationFailedException`、`expected/but was` 断言、`com.sickworm.intellij.jugg.compile.StyleableFileGeneratorTest#test2` 等 |

AGP 9 夹具失败原文（摘自 `com.sickworm.intellij.jugg.compile.KotlinCompileTest` 报告 `system-out`）：

```text
* What went wrong:
A problem occurred evaluating project ':databindingApt'.
> Failed to apply plugin 'kotlin-android'.
  > ⛔ Failed to apply plugin 'org.jetbrains.kotlin.android'
    The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
    Solution: Remove the 'org.jetbrains.kotlin.android' plugin from this project's build file: databindingApt/build.gradle.
```

---

## 7. 基础设施 / 报告生成失败（非 testcase 失败）

### 7.1 第一次执行（同工作区，非隔离）的干扰

第一次执行使用同一命令、在仓库工作区直接运行，时间为 2026-09-12 17:52:28 → 18:43:40（51 分 12 秒，`BUILD FAILED in 51m 10s`），被测版本 `1698ffbf`。其控制台摘要为 2324 个 testcase、49 个失败（cmd_line 5 / main 11 / idea 33），但**机器可读报告不完整**，因此耗时证据以第二次执行（隔离副本）为准：

| 现象 | 证据 |
|------|------|
| `:main:test` 报告写入失败 80 处 | 日志：`Multiple build operations failed. Could not write XML test results for com.sickworm.intellij.jugg.<...> to file .../main/build/test-results/test/TEST-<...>.xml` |
| 69 个 XML 为 0 字节 | `main/build/test-results/test/` 共 191 个 XML，其中 69 个大小为 0 |
| HTML 报告几乎为空 | `main/build/reports/tests/test/classes/` 仅 3 个类页（`idea` 为 123 个） |
| 运行结束后报告被删除 | `idea/build/test-results/test/` 在运行结束时（18:43）有 123 个 XML，随后被删除至只剩 1 个；18:45 重新出现 `TEST-...JuggCompileHelperTest.xml`（mtime 晚于本次运行结束时间 18:43:40） |

**判定**：同一工作区存在**其他会话发起的 Gradle 定向测试**（Gradle `Test` 任务启动时会清空 `build/test-results/test`），导致第一次执行的报告被并发覆盖。这是执行环境竞争，**不是 testcase 失败**，故第二次改为在隔离副本中执行。

### 7.2 两次执行的失败集合差异

第二次执行的 44 个失败是第一次的**子集**；第一次独有 5 个（第二次通过）：

| 第一次独有失败 | 第二次结果 | 备注 |
|----------------|-----------|------|
| `com.sickworm.intellij.jugg.logger.FileLoggerTest#should rotate paged main log files when size limit is exceeded` | 通过 | 已由 `f1f28e5` 修复（同一时间窗内的提交） |
| `com.sickworm.intellij.jugg.manager.AndroidTestTopLevelFlowTest#appRunWithAndroidTestBuildTargetUsesNormalNoChangeFlow` | 通过 | — |
| `com.sickworm.intellij.jugg.gradle.LocalGradleCompileClientTest#testFetchLibraryChanges` | 通过（63.6s） | 本次为最慢用例之一，疑似时间敏感 |
| `com.sickworm.intellij.jugg.manager.SplitClassLoaderFlowTest#hotFixDoesNotFlattenIsolatedSplits` | 通过（69.3s） | 同上 |
| `com.sickworm.intellij.jugg.manager.SplitClassLoaderFlowTest#hotFixKeepsInstalledSplitTypesInOneClassLoader` | 通过（70.2s） | 同上 |

### 7.3 其他环境说明

- 两次执行均存在 `Could not write XML ...` 之外无构建配置级失败：所有模块的 `test` 任务都已实际执行（`NO-SOURCE` 除外），不存在“因构建配置失败而未产生 testcase”的模块。
- 设备相关：`com.sickworm.intellij.jugg.deploy.direct.AsStartupAgentPusherTest` 的 8 个失败两次执行均出现（`run-as` 沙箱不可用），与 `emulator-5554` 的应用安装/沙箱状态相关，属设备状态相关失败而非报告缺失。

---

## 8. 证据与复现

| 项 | 位置 |
|----|------|
| 执行目录（隔离副本） | `/tmp/jugg-full-test-clone` |
| 机器可读报告根 | `<副本>/<module>/build/test-results/test/TEST-*.xml` |
| HTML 报告 | `<副本>/{main,idea,cmd_line}/build/reports/tests/test/index.html` |
| 控制台日志（第二次） | `/tmp/jugg_full_test_20260912/run2_gradle.log` |
| 控制台日志（第一次） | `/tmp/jugg_full_test_20260912/gradle_full_test.log` |
| 起止时间与退出码 | `/tmp/jugg_full_test_20260912/run2_meta.json`（`{"start":"2026-09-12 18:46:18","end":"2026-09-12 19:30:52","exit_code":1}`） |
| 版本与工作区状态 | `/tmp/jugg_full_test_20260912/run2_head.txt`、`run2_status.txt` |
| XML 解析结果（含逐条耗时/失败明细） | `/tmp/jugg_full_test_20260912/parsed2.json` |

复现命令：

```bash
# 建议在独立副本中执行，避免同工作区其他 Gradle 调用清空 build/test-results
./gradlew test --continue --console=plain
# 仅统计 >60s 用例与失败用例：解析各模块 build/test-results/test/TEST-*.xml 的
# <testcase time> / <failure> 节点即可（本次即由此提取，未依赖控制台输出）
```

---

## 9. 局限

1. 本次为**单次执行**结果；`com.sickworm.intellij.jugg.manager.SplitClassLoaderFlowTest`、`com.sickworm.intellij.jugg.gradle.LocalGradleCompileClientTest#testFetchLibraryChanges` 等用例在两次执行间结果翻转且耗时接近 60 秒，不能据单次结果判定稳定性。
2. `jvmti_agent` 的 250 个 testcase 由构建缓存还原（`FROM-CACHE`），本次未重新执行。
3. 设备侧覆盖仅为 `emulator-5554`（API 35）单设备，不含真机与多 Android 版本矩阵。
4. 失败用例仅记录报告中的首要异常与聚类方向，**未做根因修复与逐个复现**（超出本任务范围）。
5. 本报告固定在 `f1f28e5` 版本与上述环境下有效；工作区在该时间窗内持续有他人提交，跨版本对比需重新执行。
