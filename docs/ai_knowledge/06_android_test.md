# androidTest 支持指南

> 最后核对：2026-05-06
> 对应提交：`793d0a0f`、`0bd78f20`、`e36bfdac`、`39b54ba3`
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 能力范围

Jugg 目前支持 **app 模块的 androidTest**：

- app RunConfig 开启 `enableAndroidTest` 后，编译目标切到 `BuildTarget.ANDROID_TEST`。
- Gradle full compile 会同时产出 app APK 与 app test APK。
- 后续 app 源码与 `app/src/androidTest` 源码变更都可以进入 Jugg 增量编译。
- 部署阶段不引入 test APK 专用协议，继续复用当前 `install / code swap / full swap` 策略。
- 部署成功后执行 `am instrument`，并把 instrumentation 输出渲染到 Jugg console。
- androidTest Run 面板接入 SM Test Runner，显示 `Test Results` 树，支持测试节点源码跳转与 rerun failed tests。

当前不覆盖：

- library 模块 androidTest。
- androidTest resource 增量编译。
- `androidTestAnnotationProcessor` / `androidTestKapt`。
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
| `name` | `${appModuleName}.androidTest` |
| `moduleType` | `ModuleInfo.Type.Library` |
| `buildVariant` | `debugAndroidTest` |
| `applicationId` | test APK applicationId |
| `instrumentationTargetPackage` | app applicationId |
| `sourceDirs` | app module 的 `src/androidTest` Java/Kotlin 源码目录 |
| `moduleDependencies` | app module |

判断 androidTest module 使用 `ModuleInfo.isAndroidTestModule`。

---

## 3. 编译链路

### 3.1 Gradle full compile

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriver.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/FullBuildInfo.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt`

`CompileProjectCommand` 在 `BuildTarget.ANDROID_TEST` 时向 Gradle init script 注入 `-Pjugg.buildTarget=ANDROID_TEST`。`readProjectInfo.gradle.kts` 在 `projectsEvaluated` 阶段复用 `GradleProjectInfoReader` 的 variant guess 规则，按 app variant 查找 `assemble<Variant>AndroidTest`，并通过 `dependsOn` 挂到用户请求的 Gradle task 前执行；因此不再要求用户命令必须是 `assemble`。

`JuggCompileHelper` 不再改写 RunConfig 中的 compile command 或 output APK 配置。`LocalGradleCompileClient` / `RemoteGradleCompileClient` 先按用户配置找到 app APK，再基于实际命中的 app APK 路径派生同 variant 的 `androidTest` APK glob 并追加到结果列表。

典型规则：

| app 配置 | androidTest 运行时派生 |
|----------|------------------------|
| `:app:assembleDebug` / 自定义 app task | 原命令保持不变，init script 注入 `:app:assembleDebugAndroidTest` 作为依赖 |
| `app/build/outputs/apk/debug/app-debug.apk` | client 命中 app APK 后追加查找 `app/build/outputs/apk/androidTest/debug/*.apk` |

`full_build_info.json` 记录 `FullBuildInfo{compileCommand, buildTarget, createdAt}`。当当前 target 与记录 target 不一致时，必须触发 Gradle full compile，避免 app/test 模式复用错误产物。缺失该文件时按首次运行处理。

### 3.2 增量编译

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongs.kt`

`CompileContextManager` 的过滤规则：

- `BuildTarget.APP`：继续过滤 `.androidTest` module。
- `BuildTarget.ANDROID_TEST`：纳入 `.androidTest` module。
- `.test` / `.unitTest` 在两种 target 下都继续过滤。

`ModuleApkBelongsUtils` 现在返回 `ModuleApkBelongs` 封装类，默认通过 `getBelongsApk()` 保留现有单 APK 语义，同时用 `getAllBelongsApk()` 预留多 APK 归属视图。当前 Step 0 仍是 `isAndroidTestModule` 优先路由到匹配 `instrumentationTargetPackage` 的 test APK；找不到 test APK 时才落回普通 base APK 兜底。

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
| `JuggAndroidTestRunConfiguration` | 承载 `testClass` / `testMethod` / `testFilters` / `instrumentationRunner` / `extraArgs` |
| `JuggAndroidTestLineMarkerContributor` | 在 app `src/androidTest` 的 JUnit test 上提供 Jugg gutter |

gutter 约束：

- 只支持路径包含 `/app/src/androidTest/` 的测试。
- 识别 `org.junit.Test` 与 `org.junit.jupiter.api.Test`。
- Java / Kotlin PSI 都支持，gutter 通过测试注解 owner 判定，不依赖单一 PSI 类型。
- 未开启 `enableAndroidTest` 时只弹 Notification，引导用户打开 App RunConfig，不自动修改配置。

### 4.2 AndroidTestRunSpec 传递

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestRunSpec.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/DeployOptions.kt`

主链路：

```text
JuggAndroidTestLineMarkerContributor
  -> JuggAndroidTestRunConfiguration
  -> JuggAndroidTestRunSpecFactory
  -> JuggManager.runTask(appOptions, androidTestRunSpec, executor, runProfile)
  -> JuggConfigurationRunner
  -> JuggRunningTask
  -> DeployOptions(androidTestRunSpec)
  -> JuggDeployerHelper
```

普通 app run 的 `androidTestRunSpec = null`，行为不变。

### 4.3 Test Results UI

入口：

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestConsoleProperties.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRerunFailedTestsAction.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridge.kt`

androidTest run 会在 `JuggConfigurationRunner` 中创建 SM Test Runner console；普通 app run 仍使用普通 text console。SM Runner 只负责 UI，不接管 Jugg 的编译、部署和 instrumentation 执行。

UI 事件链路：

```text
InstrumentationOutputParser
  -> InstrumentationEvent
  -> InstrumentationSmRunnerBridge
  -> TeamCity service messages
  -> SMTestRunnerConnectionUtil
  -> Test Results tree
```

节点约定：

| 节点 | name | locationHint |
|------|------|--------------|
| device suite | 设备展示名 | 空 |
| class suite | FQCN | `java:suite://FQCN` |
| method test | methodName | `java:test://FQCN/methodName` |

`JuggAndroidTestConsoleProperties` 使用 IntelliJ `JavaTestLocator` 处理 source navigation，并通过 `JuggAndroidTestRerunFailedTestsAction` 把 failed leaf tests 转回 `AndroidTestRunSpec.testFilters` 后重跑。rerun failed 会保留原 `runnerOverride` 与 `extraArgs`。

---

## 5. 部署与 instrumentation

### 5.1 部署策略

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/ApkInstallOrder.kt`

部署阶段继续按 `applicationId` 分组，install 顺序由 `ApkInstallOrder.sortedForInstall()` 保证 app APK 先于 test APK。

**关键差异**（2026-05-06 优化）：

- **base APK**：继续走完整部署策略（install / code swap / full swap），参与 JVMTI agent push/attach 与 compat 检测。
- **test APK**：只走 **INSTALL**（完整 APK 安装），不走 code swap / full swap 增量部署。

原因：`am instrument` 在主 APK 进程内运行测试代码（test APK 无独立进程），主 apk 和 test APK 共享 JVMTI Agent。`am instrument` 只启动主 APK 的 application。因此：

- test APK 不参与 `JuggJvmtiAgentManagerHelper` 的 agent push/attach/compat 检测。
- test APK 在非 INSTALL 场景下仍强制走 `AndroidDeployType.INSTALL`。
- `JuggDeployerHelper.removeLibraryDexFiles` 跳过 test APK。
- `CompatDeployHelper.isEnableCompatDeploy` 跳过 test APK 的 applicationId。

详见 `docs/task/androidtest_testapk_deploy_optimization.md`。

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

`AndroidTestRunSpec.testFilters` 非空时优先生成逗号分隔的 `-e class` 参数，用于 rerun failed；为空时沿用 `testClass` / `testMethod`。

`TestLauncher` 对每台设备串行执行 instrumentation。任一设备出现以下情况，整体 Run 失败：

- instrumentation command 非 0 退出。
- `INSTRUMENTATION_ABORTED`。
- test result 为 `FAILURE` / `ERROR` / `ASSUMPTION_FAILURE`。
- 设备执行过程中抛异常。

---

## 6. 测试入口

禁止运行完整测试套件。androidTest 支持相关回归优先跑定向测试。

### 6.1 main 模块

| 测试文件 | 覆盖点 |
|----------|--------|
| `main/src/test/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriverTest.kt` | Gradle 命令保持不变与 client 侧 test APK 查找路径派生 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/FullBuildInfoSerializerTest.kt` | target 记录序列化与容错 |
| `main/src/test/java/com/sickworm/intellij/jugg/apk/ApkInfoInstrumentationTest.kt` | test APK instrumentation manifest 读取 |
| `main/src/test/java/com/sickworm/intellij/jugg/project/data/ModuleInfoAndroidTestTest.kt` | `isAndroidTestModule` |
| `main/src/test/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerializerAndroidTestTest.kt` | project info 新字段序列化兼容 |
| `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderAndroidTestTest.kt` | Gradle 侧 synthetic module 生成 |
| `main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt` | androidTest module 到 test APK 路由 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilderTest.kt` | `am instrument` 命令构造 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParserTest.kt` | instrumentation 输出解析 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRendererTest.kt` | console 渲染 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationSmRunnerBridgeTest.kt` | SM Test Runner service message 映射 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/run/ApkInstallOrderTest.kt` | app/test APK install 顺序 |
| `main/src/test/java/com/sickworm/intellij/jugg/ide/logic/AndroidTestRunSpecPropagationTest.kt` | spec 传递链路 |

### 6.2 idea 模块

| 测试文件 | 覆盖点 |
|----------|--------|
| `idea/src/test/java/com/sickworm/intellij/jugg/project/CompileContextManagerAndroidTestFilterTest.kt` | `.androidTest` module 过滤规则 |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributorTest.kt` | gutter 路径与入口边界 |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunSpecFactoryTest.kt` | RunConfig options 到 spec |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestConsolePropertiesTest.kt` | SM Test Runner locator |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRerunFailedTestsActionTest.kt` | failed leaf tests 到 `testFilters` |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/DeployOptionsAndroidTestSpecTest.kt` | DeployOptions 携带 spec |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/TestLauncherResultTest.kt` | parser event sink 与结果语义 |

### 6.3 常用定向命令

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.AndroidTestCommandDeriverTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.InstrumentationOutputParserTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.InstrumentationSmRunnerBridgeTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.run.ApkInstallOrderTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.project.CompileContextManagerAndroidTestFilterTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.JuggAndroidTestRunSpecFactoryTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.JuggAndroidTestConsolePropertiesTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.JuggAndroidTestRerunFailedTestsActionTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.TestLauncherSmEventSinkTest"
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

---

## 8. 变更历史

- 2026-05-06：接入 SM Test Runner Test Results UI，补充 `JavaTestLocator` source navigation 与 rerun failed tests。
- 2026-04-29：新增正式知识库文档，沉淀 androidTest 支持的当前实现、边界、链路和测试入口。
