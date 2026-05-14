# androidTest 支持指南

> 最后核对：2026-05-08
> 对应提交：`793d0a0f`、`0bd78f20`、`e36bfdac`、`39b54ba3`、`当前工作区`
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

阶段 2 最终采用 **独立 synthetic ModuleInfo**，不是早期方案里的“合入 app ModuleInfo”：

| 字段 | 当前约定 |
|------|----------|
| `name` | `${ownerModuleName}.androidTest` |
| `moduleType` | `ModuleInfo.Type.Library` |
| `buildVariant` | `debugAndroidTest` |
| `applicationId` | app androidTest 使用 test APK applicationId；self-targeting library androidTest 默认使用 owner module namespace |
| `instrumentationTargetPackage` | app androidTest 使用 app applicationId；self-targeting library androidTest 使用 owner module namespace |
| `sourceDirs` | owner module 的 `src/androidTest` Java/Kotlin 源码目录 |
| `moduleDependencies` | owner module |

判断 androidTest module 使用 `ModuleInfo.isAndroidTestModule`。

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
- Gradle client 先按用户配置命中 app APK，再从实际 app APK 路径派生同 variant 的 `app/build/outputs/apk/androidTest/<variant>/*.apk`。
- `full_build_info.json` 记录 `FullBuildInfo{compileCommand, buildTarget, createdAt}`；target 切换或文件缺失时触发 Gradle full compile，避免 app/test 模式复用错误产物。
- Gradle project info 读取阶段会为存在 `androidTest` source set 的 Application 与 Library 模块生成 synthetic `.androidTest` ModuleInfo；Library 模块用 `namespace` 建立 self-targeting Test APK 归属，保证 `sourcePath` 可命中后续缺失 APK 懒加载流程。

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
| `JuggAndroidTestRunConfiguration` | General 页对齐 Android Instrumented Tests：Module 行、四种 Test scope、动态字段、可编辑 Instrumentation class 与原有 Instrumentation arguments |
| `JuggAndroidTestLineMarkerContributor` | 在 `src/androidTest` 的 JUnit test 上提供 Jugg gutter，并把测试文件路径写入 `sourcePath` |

`JuggAndroidTestRunConfiguration` 当前两种 scope：

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

关键节点约定：

| 节点 | name | locationHint |
|------|------|--------------|
| device suite | 设备展示名 | 空 |
| class suite | FQCN | `java:suite://FQCN` |
| method test | methodName | `java:test://FQCN/methodName` |

设备 suite 展示规则（2026-05-07）：

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
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/LibraryTestApkBackfillHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/ApkInstallOrder.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt`

部署阶段继续按 `applicationId` 分组，install 顺序由 `ApkInstallOrder.sortedForInstall()` 保证 app APK 先于 test APK。2026-05-06 后的关键差异：

- **base APK**：继续走完整部署策略（install / code swap / full swap），参与 JVMTI agent push/attach 与 compat 检测。
- **test APK**：只走 **INSTALL**（完整 APK 安装），不走 code swap / full swap 增量部署。
- **multi APK scoped data**：每个 applicationId 部署前调用 `JuggDeployData.filterForApks(...)`，只保留属于当前 APK 集合的 class / overlay / updateApkFiles，避免 base/test APK 互相错投。

原因：`am instrument` 在主 APK 进程内运行测试代码，test APK 无独立进程，不应参与 JVMTI agent push/attach、compat 检测或 library dex 清理。详见 `docs/task/androidtest_testapk_deploy_optimization.md`。

library-style self-targeting Test APK 是例外：它有自己的 runtime package 和安装目标。`LibraryTestApkBackfillHelper` 只在以下条件同时满足时补齐缺失 APK：

- `sourcePath` 已唯一命中某个 androidTest `ModuleInfo`。
- 当前 APK 列表中无法解析出该 module 对应的 test APK。
- `module.applicationId == module.instrumentationTargetPackage`，即 self-targeting / library-style Test APK。

补齐成功后会先把 Gradle 产出的 Test APK 作为完整 APK 安装一次，再同步更新 deploy target、deploy data database 与 compile context 的 APK 列表。该 APK 已包含本轮最新源码产物，不再消费本轮 Jugg 增量 deploy items。

### 5.2 am instrument

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/TestLauncher.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilder.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParser.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRenderer.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridge.kt`

`InstrumentCommandBuilder` 输出形态：

```text
am instrument -w -r [-e class <testClass>[#<testMethod>][,<testClass>#<testMethod>...]] [-e <key> <value>]* <testPkg>/<runner>
```

`AndroidTestRunSpec.sourcePath` 非空时，MCP 会先用 source file 解析单 class/多 class 与 method 有效性，部署阶段再用 source file 精确解析 androidTest module 与 test APK；无 `sourcePath` 的旧 app androidTest 路径仍回退到首个 test APK。`AndroidTestRunSpec.testFilters` 非空时优先生成逗号分隔的 `-e class` 参数，用于 rerun failed；为空时沿用 `testClass` / `testMethod`。

当需要执行大范围 androidTest 回归时，先用一次 `jugg instrument --source-path ...` 让 Jugg 完成编译、部署和目标 APK 刷新。该命令成功后，app 源码变更与 androidTest 源码变更都已经写入对应 APK；此时可以使用普通 `adb shell am instrument` 执行更大范围的 class/package/suite 回归，不再要求通过 jugg cli 使用 `sourcePath` 做目标锚定。

`TestLauncher` 会为每台设备启动独立 `logcat -v threadtime` 流。logcat 采集与 instrumentation 协议解析分离：`InstrumentationOutputParser` 只负责生成 `TestStarted` / `TestFinished` 等事件，`TestLauncher` 根据当前设备的 active method 把窗口内 logcat 写入 `AndroidTestResultModel.recordTestLog(...)`，method 外日志只保留在设备详情中。多设备各自维护 active method，避免设备间日志串台。

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
3. 当前是否为 app 模块 androidTest；library androidTest 当前不支持。
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
