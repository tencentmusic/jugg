# androidTest 支持指南

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 能力范围

Jugg 目前支持 **app 模块的 androidTest**，并已接入 **library-style self-targeting Test APK** 的 sourcePath 精确选择、缺失 APK 懒加载补齐与多 APK 归属部署：

- app RunConfig 开启 `enableAndroidTest` 后，编译目标切到 `BuildTarget.ANDROID_TEST`。
- Gradle full compile 会同时产出 app APK 与 app test APK。
- 后续 app 源码与 `app/src/androidTest` 源码变更都可以进入 Jugg 增量编译。
- 部署阶段不引入 test APK 专用协议，继续复用当前 `install / code swap / full swap` 策略，并按 applicationId 拆分 scoped deploy data。
- 部署成功后执行 `am instrument`，并把 instrumentation 输出渲染到 Jugg console。
- androidTest Run 面板接入 SM Test Runner，显示 `Test Results` 树，支持测试节点源码跳转与 rerun failed tests。
- library-style Test APK 缺失时，只对当前 `sourcePath` 命中的 androidTest module 派生并执行 `:<module>:assemble<Variant>AndroidTest`，再把新增 Test APK 合入本轮 APK 列表。

当前不覆盖：

- androidTest resource 增量编译。
- `androidTestAnnotationProcessor` / `androidTestKapt`。
- app-style other-targeting test APK 的懒加载补齐。
- Debug Executor。
- 常驻 test harness 或保活 test 进程内 redefine。

---

## 2. 核心模型

### 2.1 BuildTarget

入口：`main/src/main/java/com/sickworm/intellij/jugg/compiler/BuildTarget.kt`

| Target | 编译范围 | 启动策略 |
|--------|----------|----------|
| `APP` | app variant | `am start` |
| `ANDROID_TEST` | app variant + androidTest variant | `am instrument` |

`BuildTarget.ANDROID_TEST` 只是一层运行会话 tag，不把 androidTest 当成独立 app 运行模式。普通 app run 仍默认走 `APP`，避免影响既有行为。

### 2.2 test APK 识别

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfoReader.kt`

`ApkInfo` 通过 APK manifest 的 `<instrumentation>` 元素识别 test APK：

| 字段 | 含义 |
|------|------|
| `instrumentationTargetPackage` | 被测 app package；非空表示这是 test APK |
| `instrumentationRunner` | test APK manifest 中声明的 runner |
| `isTestApk` | `instrumentationTargetPackage != null` |

下游不要靠路径或文件名猜测 test APK，应优先使用 `ApkInfo.isTestApk`。

### 2.3 androidTest ModuleInfo

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt`

androidTest 使用 **独立 synthetic ModuleInfo**，不合入 owner module：

| 字段 | 当前约定 |
|------|----------|
| `name` | `${ownerModuleName}.androidTest` |
| `moduleType` | `ModuleInfo.Type.Library` |
| `buildVariant` | `debugAndroidTest` |
| `applicationId` | app androidTest 使用 test APK applicationId；self-targeting library androidTest 默认使用 `${owner namespace}.test` |
| `instrumentationTargetPackage` | app androidTest 使用 app applicationId；self-targeting library androidTest 使用 `${owner namespace}.test`，与 Gradle 产出的 self-targeting Test APK manifest 对齐 |
| `sourceDirs` | owner module 的 `src/androidTest` Java/Kotlin 源码目录 |
| `moduleDependencies` | owner module |

判断 androidTest module 使用 `ModuleInfo.isAndroidTestModule`，即 `instrumentationTargetPackage != null`；`.androidTest` 后缀只作为 IDE module 补齐候选，不作为最终身份判断。

当 Gradle project info 不可用或缺少 androidTest synthetic module 时，`CompileContextManager#doGetAllModulesByModuleManager()` 会在创建 IDE project info 阶段逐个处理 IDE 侧 `.androidTest` module：

- `.androidTest` 后缀只用于 `ModulePathMergePolicy` 判定 IDE module 创建候选；最终身份仍由补齐后的 `instrumentationTargetPackage != null` 表示。
- `sourceDirs` 来自 IDE module source roots，并额外纳入 androidTest IDE module 的 test source root 类型，因此支持自定义 androidTest source root，不再硬编码标准目录。
- test package / target package 来自 `AsDeployerCompat#getIdeModuleInfo` 暴露的 IDE Android 模型 androidTest artifact 信息；Chipmunk 继承链读取 `artifactForAndroidTest.applicationId` 与 module `applicationId`，Narwhal feature / Otter / Panda 继承链读取 AndroidTest artifact core 与 main artifact 的 applicationId。
- IDE project info 不再用已保存 Test APK manifest 信息反推缺失字段；IDE module info 只有 test package 与 target package 都可用时才标记为 androidTest module。
- Gradle merge 时 test 相关字段仍以 Gradle 非空值优先。

---

## 3. 编译链路

### 3.1 Gradle full compile

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriver.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/FullBuildInfo.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt`

关键不变式：

- 不改写用户 RunConfig 中的 compile command 或 output APK 配置。
- `BuildTarget.ANDROID_TEST` 通过 Gradle init script 注入 `-Pjugg.buildTarget=ANDROID_TEST`，并把同 variant 的 `assemble<Variant>AndroidTest` 挂到用户请求的 Gradle task 前执行。
- 若 `LibraryTestApkBuildHistory` 命中近期 self-targeting library Test APK 记录，Gradle compile 会通过 `-Pjugg.libraryTestTasks=...` 传递历史 task 列表，init script 在同一 `projectsEvaluated` 阶段把这些 library androidTest task 也挂到用户请求的 Gradle task 前执行；`BuildTarget.APP` 不参与该逻辑。
- Gradle client 先按用户配置命中 app APK，再从实际 app APK 路径派生同 variant 的 `app/build/outputs/apk/androidTest/<variant>/*.apk`；history library Test APK output 作为 optional APK 收集，命中则追加到本轮 APK 结果，缺失只记录日志，不进入 `failedApkPaths`。
- `full_build_info.json` 记录 `FullBuildInfo{compileCommand, buildTarget, createdAt}`；target 切换或文件缺失时触发 Gradle full compile，避免 app/test 模式复用错误产物。
- Gradle project info 读取阶段仅在 `-Pjugg.buildTarget=ANDROID_TEST` 时为存在 `androidTest` source set 的 Application 与 Library 模块生成 synthetic `.androidTest` ModuleInfo；`APP`/未传时不写入快照。localFetch 的 buildTarget 来自 `IDeployHistoryManager.getFullBuildInfo()`。Library 模块用 `${namespace}.test` 建立 self-targeting Test APK 归属，保证 `sourcePath` 可命中后续缺失 APK 懒加载流程。

### 3.2 增量编译

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongs.kt`

`CompileContextManager` 的过滤规则：

- `BuildTarget.APP`：继续过滤 `.androidTest` module。
- `BuildTarget.ANDROID_TEST`：纳入 `.androidTest` module。
- `.test` / `.unitTest` 在两种 target 下都继续过滤。

`ModuleApkBelongsUtils` 返回 `ModuleApkBelongs` 封装类，默认通过 `getBelongsApk()` 保留现有单 APK 语义，同时用 `getAllBelongsApk()` 暴露多 APK 归属视图。当前 androidTest module 优先路由到匹配 `instrumentationTargetPackage` 的 test APK；普通 library module 在存在 self-targeting library Test APK 时，`getAllBelongsApk()` 会同时包含 base APK 与 library Test APK。

`CompileOutput.targetApkPaths` 与 `DeployItem.targetApkPaths` 会把多 APK 归属传到部署层，并保证在有真实 `apkPath` 时至少包含它；Dex merge、resource APK、APK 内嵌更新和 overlay update 都必须优先读取 target paths，旧的 `allTargetApkPaths` 视图已经删除。

---

## 4. 运行入口

### 4.1 IDE RunConfig 与 gutter

入口：

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfigurationOptions.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributor.kt`

入口分层：

| 配置 | 职责 |
|------|------|
| `JuggRunConfiguration.enableAndroidTest` | 控制 app RunConfig 是否允许 androidTest 编译与运行 |
| `JuggAndroidTestRunConfiguration` | General 页对齐 Android Instrumented Tests：Module 行、两种 Test scope、动态字段、可编辑 Instrumentation class 与原有 Instrumentation arguments |
| `JuggAndroidTestLineMarkerContributor` | 在 `src/androidTest` 的 JUnit test 上提供 Jugg gutter，并把测试文件路径写入 `sourcePath` |

`JuggAndroidTestRunConfiguration` 支持两种执行 scope：

| Scope | 配置字段 | 运行映射 | 校验 |
|-------|----------|----------|------|
| `CLASS` | `testClass` | 追加 `-e class <testClass>` | testClass 必填 |
| `METHOD` | `testClass` + `testMethod` | 追加 `-e class <testClass>#<testMethod>` | testClass/testMethod 必填 |

`sourcePath` 是目标锚点，用于解析测试 class/method、androidTest module 与 test APK；package / regex 不再作为 target 入口。`instrumentationRunner` 为空时使用 test APK manifest runner/default runner；非空时覆盖为 `<testPkg>/<instrumentationRunner>`。

gutter 默认值：class gutter 生成 `sourcePath + CLASS + testClass`，method gutter 生成 `sourcePath + METHOD + testClass/testMethod`。rerun failed 仍使用 `AndroidTestRunSpec.testFilters`，不会反写 General 页 scope。

gutter 约束：

- 支持路径包含 `/src/androidTest/` 的测试；library test APK 通过 `sourcePath` 进入后续 target resolver。
- 识别 `org.junit.Test` 与 `org.junit.jupiter.api.Test`。
- Java / Kotlin PSI 都支持，gutter 通过测试注解 owner 判定，不依赖单一 PSI 类型。
- 未开启 `enableAndroidTest` 时只弹 Notification，引导用户打开 App RunConfig，不自动修改配置。

Agent / CLI 场景中，如果用户要求执行 androidTest 或 instrumented unit tests，但 `jugg status` 返回 `enabledAndroidTest=false`，应停止执行 `instrument` 并提示用户：打开 Jugg App Run Configuration，开启 Android Test / `enableAndroidTest`，对该配置执行一次 full build / `gradle-build` 建立 AndroidTest full-build baseline，然后重新检查 `status.data.enabledAndroidTest=true` 后再继续。若仍直接调用 `instrument`，MCP 层返回 `INVALID_PARAMS`，并在错误信息中携带同一组开启方式。

### 4.2 AndroidTestRunSpec 传递

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestRunSpec.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/DeployOptions.kt`

gutter 触发后由 `JuggAndroidTestRunSpecFactory` 生成 `AndroidTestRunSpec`，再经 `JuggManager.runTask(...)`、`JuggConfigurationRunner`、`JuggRunningTask` 写入 `DeployOptions.androidTestRunSpec`。普通 app run 的 `androidTestRunSpec = null`，行为不变。

### 4.3 Test Results UI

入口：

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestConsoleProperties.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRerunFailedTestsAction.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridge.kt`

androidTest run 会在 `JuggConfigurationRunner` 中创建 SM Test Runner console；普通 app run 仍使用普通 text console。SM Runner 只负责 UI，不接管 Jugg 的编译、部署和 instrumentation 执行。

UI 事件链路压缩为：`InstrumentationOutputParser` 生成 `InstrumentationEvent`，`InstrumentationSmRunnerBridge` 转成 TeamCity service message，最终由 `SMTestRunnerConnectionUtil` 驱动 Test Results tree。

一次 androidTest run 只创建一个 `InstrumentationSmRunnerBridge`；多设备按设备顺序创建 sink，但共享同一个 SM runner session，避免每台设备各自输出一段独立 `enteredTheMatrix`。

androidTest 的 SM Runner process output 不接收 Jugg 项目级 `info/warn` 日志；插件运行日志保留在 `compile_latest.log`，Test Results 节点只通过 instrumentation service message 和 method 级 logcat 输出展示测试相关内容。

关键节点约定：

| 节点 | name | locationHint |
|------|------|--------------|
| device suite | 设备展示名 | 空 |
| class suite | FQCN | `java:suite://FQCN` |
| method test | methodName | `java:test://FQCN/methodName` |

设备 suite 展示规则：

- **单设备运行**：隐藏 device suite，仅展示 class/method 节点，减少一层无效树层级。
- **多设备运行**：展示 device suite，按设备分组 class/method 节点，避免不同设备结果混在同一层。
- **设备展示名**：由 `TestLauncher` 统一生成，优先使用设备品牌/型号，并追加 `API xx`，用于对齐 Android Test 的设备维度可读信息。
- **设备详情**：右侧详情面板展示设备 Serial、Name、API 和该设备的 instrumentation 原始日志。
- **结果矩阵**：多设备运行时会补一段矩阵文本，按 `Test | device1 | device2 ...` 展示每个测试在各设备上的 `Pass / Fail / Ignored / Running / -` 状态。

`JuggAndroidTestConsoleProperties` 使用 IntelliJ `JavaTestLocator` 处理 source navigation，并通过 `JuggAndroidTestRerunFailedTestsAction` 把 failed leaf tests 转回 `AndroidTestRunSpec.testFilters` 后重跑。rerun failed 会保留原 `runnerOverride` 与 `extraArgs`。

---

## 5. 部署与 instrumentation

### 5.1 部署策略

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/LibraryTestApkBackfillHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/ApkInstallOrder.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt`

部署阶段继续按 `applicationId` 分组，install 顺序由 `ApkInstallOrder.sortedForInstall()` 保证 app APK 先于 test APK。关键差异：

- **base APK**：继续走完整部署策略（install / code swap / full swap），参与 JVMTI agent push/attach 与 compat 检测。
- **test APK**：只走 **INSTALL**（完整 APK 安装），不走 code swap / full swap 增量部署。
- **multi APK scoped data**：每个 applicationId 部署前调用 `JuggDeployData.filterForApks(...)`，只保留属于当前 APK 集合的 class / overlay / updateApkFiles，避免 base/test APK 互相错投。

原因：`am instrument` 在主 APK 进程内运行测试代码，test APK 无独立进程，不应参与 JVMTI agent push/attach、compat 检测或 library dex 清理。详见 `docs/task/androidtest_testapk_deploy_optimization.md`。

library-style self-targeting Test APK 是例外：它有自己的 runtime package 和安装目标。`LibraryTestApkBackfillHelper` 只在以下条件同时满足时补齐缺失 APK：

- `sourcePath` 已唯一命中某个 androidTest `ModuleInfo`。
- 当前 APK 列表中无法解析出该 module 对应的 test APK。
- `module.applicationId == module.instrumentationTargetPackage`，即 self-targeting / library-style Test APK。

补齐成功后会先把 Gradle 产出的 Test APK 作为完整 APK 安装一次，并立即把新 package 的 overlay id 合并到 deploy history，避免后续 dry deploy 把新安装的 library Test APK 误判为跨项目状态；随后同步更新 deploy target、deploy data database 与 compile context 的 APK 列表。该 APK 已包含本轮最新源码产物，不再消费本轮 Jugg 增量 deploy items。

当 Gradle compile 成功、Test APK 路径解析成功、APK 安装成功且 compile context 已同步后，`LibraryTestApkBuildHistory` 会记录该 library androidTest module 的 compile command、compile time、APK output pattern 与实际 APK path。记录写入 `~/.jugg/library_test_build_records/{projectName}_hash{0:8}.json`，有 git 仓库时 hash 使用仓库 URL，否则使用工程绝对路径；每次读取普通 `BuildTarget.ANDROID_TEST` Gradle build 历史时，只选择最近 30 天、同 variant 的最近 3 条记录用于回放。

命中缺失分支时，Jugg 会通过 Run tool window balloon 提示 `Library Test APK missing. Run Gradle compile once to build the test APK.`，让用户知道需要一次 Gradle 编译来生成 Test APK baseline。

### 5.2 am instrument

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/TestLauncher.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilder.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParser.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRenderer.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridge.kt`

`InstrumentCommandBuilder` 输出形态：

```text
am instrument -w -r [-e class <testClass>[#<testMethod>][,<testClass>#<testMethod>...]] [-e <key> <value>]* <testPkg>/<runner>
```

`AndroidTestRunSpec.sourcePath` 非空时，运行入口会先用 source file 解析单 class/多 class 与 method 有效性，部署阶段再用 source file 精确解析 androidTest module 与 test APK；无 `sourcePath` 的 app androidTest 路径仍回退到首个 test APK。`AndroidTestRunSpec.testFilters` 非空时优先生成逗号分隔的 `-e class` 参数，用于 rerun failed；为空时沿用 `testClass` / `testMethod`。

当需要执行大范围 androidTest 回归时，先用一次 `jugg instrument --source-path ...` 让 Jugg 完成编译、部署和目标 APK 刷新。该命令成功后，app 源码变更与 androidTest 源码变更都已经写入对应 APK；此时可以使用普通 `adb shell am instrument` 执行更大范围的 class/package/suite 回归，不再要求通过 jugg cli 使用 `sourcePath` 做目标锚定。

### 5.3 日志捕获与归类

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/TestLauncher.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/instrument/AndroidTestLogAttributor.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestResultModel.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridge.kt`

AndroidTest 日志捕获方案：

1. `TestLauncher` 在每台设备执行 instrumentation 前读取设备侧 `date '+%m-%d %H:%M:%S.000'` 作为 logcat 起点，格式为 `MM-dd HH:mm:ss.SSS`；设备时间读取失败或格式异常时才回退本机时间。
2. 每台设备启动独立 `logcat -T <deviceRunStartTime> -v threadtime` 流；Run 窗口与 debug 日志都会输出 `Capturing logcat since <deviceRunStartTime>`，用于验证本轮采集起点。
3. `InstrumentationOutputParser` 只解析 `am instrument` 协议并生成 `TestStarted` / `TestFinished` / `Aborted` 等事件，不负责 logcat 采样；当 `class` 与 `test` status 都已到达时即打开 `TestStarted` 窗口，后续 `STATUS_CODE: 1` 只作为兼容确认，避免 method 开头的 logcat 早于 code=1 到达时丢失。
4. `TestLauncher` 把所有 logcat 行先写入 `AndroidTestResultModel.recordLog(...)` 作为设备级日志，同时交给 `AndroidTestLogAttributor` 放入 run 级有界缓存。缓存默认上限为 100000 行；采集结束后会在 debug 日志打印保留行数、字节数、总采集行数、截断行数和上限，并立即释放缓存引用。归类器在 method 窗口 finalize 时再按 test process PID 筛选并输出 `recordTestLog(...)`，避免 PID 查询或 logcat 流稍晚导致 method 日志丢失。PID 来自 `pidof <targetPackage>` / `pidof <testPackage>`，失败时回退到 `ps -A` 精确匹配 package name；若 PID 获取失败则降级为原时间窗口归类，避免 method 日志全丢。
5. `InstrumentationSmRunnerBridge` 把 method 级日志输出为 SM Runner `testStdOut`；输出前补齐末尾换行，保证导出的 method 日志按行展示。失败事件输出 `testFailed` 时，`message` 使用异常首行，`details` 只保留后续 stack trace，避免 IntelliJ 详情面板重复展示同一条失败摘要。

归类边界：

- method 日志优先使用 AndroidX TestRunner 的 `TestRunner: started/finished: method(class)` logcat marker 做边界，并限定为 marker 所在 PID 的日志。该路径用于覆盖 logcat 早于 `InstrumentationEvent.TestStarted` 到达的场景，避免 instrumentation 协议回调滞后导致 method 日志漏归类。
- 没有完整 TestRunner marker 时，method 日志窗口回退到 `InstrumentationEvent.TestStarted(className, testName)` 生命周期边界。PID 过滤可用时，窗口保留到下一个 `TestStarted` 或本轮 logcat 关闭前的短暂 drain，然后统一筛选输出；PID 过滤不可用时仍在同一 method 的 `TestFinished` finalize。`TestStarted` 可早于 `INSTRUMENTATION_STATUS_CODE: 1` 发出，但必须等 `class` 与 `test` status 都齐备。
- method 外 logcat 只进入设备详情，不进入任一 method；active method 窗口内 PID 不属于当前 test process 的全局设备噪声也只进入设备详情。
- 多设备各自维护 active method，不共享归属状态。
- `Aborted`、instrumentation 非 0 退出或设备异常时，已收到且处于 active method 窗口内的日志保留在 result model；后续日志不再猜测补归属。
- 禁止从业务 logcat tag、message 或时间戳反推 method；method 归属只以 instrumentation lifecycle 或 AndroidX TestRunner marker 为准。

使用 `logcat -T` 的原因：设备 logcat buffer 会残留旧运行日志。若直接使用 `logcat -v threadtime`，旧日志可能在本轮启动后立即吐出，并被误归入第一个 active method。`-T <deviceRunStartTime>` 让采集只关注本轮启动后的日志，避免历史 buffer 污染测试方法详情。`-T` 必须使用设备侧时间；若使用主机时间，主机与设备时钟偏移会导致本轮测试 logcat 被整体过滤。

`TestLauncher` 对每台设备串行执行 instrumentation。任一设备出现以下情况，整体 Run 失败：

- instrumentation command 非 0 退出。
- `INSTRUMENTATION_ABORTED`。
- test result 为 `FAILURE` / `ERROR` / `ASSUMPTION_FAILURE`。
- 设备执行过程中抛异常。

---

## 6. 测试入口

禁止运行完整测试套件。androidTest 支持相关回归优先跑定向测试。

按能力域搜索测试，避免维护易漂移的静态文件清单：

```bash
rg --files main/src/test idea/src/test | rg 'AndroidTest|Instrumentation|ApkInstallOrder|TestLauncher|RunSpec'
```

常用跑法：先用上面的 `rg` 定位目标测试类，再替换 `--tests` 参数。

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.<MainModuleTestClass>"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.<IdeaModuleTestClass>"
```

必要时可做编译验证：

```bash
./gradlew :idea:compileKotlin
```

---

## 7. 排查口径

### 7.1 gutter 不出现

优先确认：

1. 文件路径是否在 `/app/src/androidTest/` 下。
2. test 方法或类是否有 `org.junit.Test` / `org.junit.jupiter.api.Test`。
3. 文件是否位于 app 或 library 模块的 `src/androidTest` source root 下。
4. 如果是 Kotlin 文件，确认 PSI 能正确识别注解 owner；当前实现同时兼容 `getAnnotations()` 与 `getAnnotationEntries()`。

### 7.2 点击 gutter 后没有真正跑 test

优先确认：

1. App RunConfig 是否开启 `enableAndroidTest`。
2. 是否已经用 `BuildTarget.ANDROID_TEST` 做过一次 Gradle full compile。
3. `DeployOptions.androidTestRunSpec` 是否非空。
4. `deployData.apks` 中是否存在 `ApkInfo.isTestApk == true` 的 test APK。

### 7.3 增量变更没有进入 test APK

优先确认：

1. 当前 `FullBuildInfo.buildTarget` 是否为 `ANDROID_TEST`。
2. `CompileContextManager` 是否纳入 `.androidTest` module。
3. `ModuleInfo.instrumentationTargetPackage` 是否非空。
4. `ModuleApkBelongsUtils` 是否把 androidTest module 路由到 test APK。

### 7.4 instrumentation 失败

优先确认：

1. test APK manifest 中是否有正确的 `instrumentationRunner`。
2. `InstrumentCommandBuilder` 生成的 `<testPkg>/<runner>` 是否正确。
3. `InstrumentationOutputParser` 是否解析到了 `ABORTED`、`FAILURE`、`ERROR` 或 `ASSUMPTION_FAILURE`。

### 7.5 Test Results 树或 rerun failed 异常

优先确认：

1. `JuggConfigurationRunner` 是否收到了非空 `androidTestRunSpec`、`executor` 与 `runProfile`。
2. `JuggAndroidTestConsoleProperties.TEST_FRAMEWORK_NAME` 是否与 `SMTestRunnerConnectionUtil.createAndAttachConsole()` 的 framework name 一致。
3. `InstrumentationSmRunnerBridge` 是否输出了 `java:suite://FQCN` 与 `java:test://FQCN/method` locationHint。
4. rerun failed 生成的 `AndroidTestRunSpec.testFilters` 是否非空，且 `InstrumentCommandBuilder` 是否优先使用 `testFilters`。
