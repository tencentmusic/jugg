# Jugg androidTest 支持——阶段 3 开发前最终方案

> 创建时间：2026-04-29
> 状态：阶段 3 开发中
> 前置：阶段 1/2 已有基础实现，但入口链路仍需在本阶段收口
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 目标与边界

### 1.1 阶段 3 目标

阶段 3 只覆盖 **app 模块的 androidTest**：

1. 补齐阶段 1/2 尚未闭环的 androidTest 运行入口。
2. 让 app androidTest 的 test APK 复用当前 base APK 的部署逻辑。
3. app androidTest 增量编译后，部署阶段按现有 `install / code swap / full swap` 策略处理 test APK。
4. 部署成功后，用当前 `AndroidTestRunSpec` 重新执行 `am instrument`。
5. 补齐基于 `android_demo_project` 的 e2e 单元测试。

### 1.2 非目标

以下内容不进入阶段 3：

- library 模块 androidTest 支持。
- library androidTest 的 test host / target package / test APK 归属规则。
- 常驻 test harness 或保活 test 进程。
- SM Test Runner 面板集成。
- Debug Executor。
- rerun failed tests。
- androidTest resource 增量编译。
- `androidTestAnnotationProcessor` / `androidTestKapt`。

library 模块 androidTest 细节较多，作为阶段 4 单独设计与落地。

---

## 2. 当前现状

### 2.1 已具备能力

阶段 1/2 已经落地了一批阶段 3 可复用的基础能力：

- `BuildTarget.APP / ANDROID_TEST`：`main/src/main/java/com/sickworm/intellij/jugg/compiler/BuildTarget.kt`
- app RunConfig 开关 `enableAndroidTest`：`idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfigurationOptions.kt`
- app androidTest Gradle 命令和 APK glob 派生：`main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriver.kt`
- test APK instrumentation 字段：`main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfo.kt`
- APK manifest 读取 instrumentation：`main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfoReader.kt`
- `AndroidTestRunSpec` / `InstrumentCommandBuilder` / `InstrumentationOutputParser` / `InstrumentationConsoleRenderer`：`main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/`
- test APK install 排序：`main/src/main/java/com/sickworm/intellij/jugg/deploy/ApkInstallOrder.kt`
- app androidTest synthetic `ModuleInfo`：`main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`
- Gradle 侧生成 `app.androidTest` module：`main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`
- androidTest module 到 test APK 的路由：`main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`
- `CompileContextManager` 在 `BuildTarget.ANDROID_TEST` 时不过滤 `.androidTest` module：`idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt`
- 部署层按 `applicationId` 分组：`idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt`

### 2.2 尚未闭环的问题

阶段 3 开始前不能假设 androidTest 入口已经可用。当前仍存在以下缺口：

- `JuggAndroidTestLineMarkerContributor.getInfo()` 仍返回 `null`，gutter icon 不会出现。
- `JuggAndroidTestRunProfileState.execute()` 只返回空的 `DefaultExecutionResult()`，没有真正启动 Jugg 任务。
- `JuggManager` / `JuggConfigurationRunner` / `JuggRunningTask` 没有贯穿 `AndroidTestRunSpec`。
- `DeployOptions` 没有携带 `AndroidTestRunSpec`。
- `JuggDeployerHelper.runTask(..., androidTestRunSpec)` 分支存在，但主链路不可达。
- `TestLauncher.run()` 的失败只记录 warn，尚未稳定反馈到 Run 结果。

因此阶段 3 第一部分必须先收口入口链路。

---

## 3. app androidTest 与 library androidTest 的边界

### 3.1 app 模块 androidTest

app 模块 androidTest 是阶段 3 支持对象，逻辑如下：

1. 用户在 app 的 `src/androidTest` 测试类或测试方法上点击 Jugg gutter。
2. Jugg 创建临时 `JuggAndroidTestRunConfiguration`。
3. RunProfileState 构造 `AndroidTestRunSpec`。
4. `BuildTarget.ANDROID_TEST` 触发 app + app androidTest 编译。
5. Gradle full compile 时产出 app APK + app test APK。
6. 增量编译时 `app.androidTest` module 的 class 输出路由到 test APK。
7. 部署时 app APK 和 test APK 按各自 `applicationId` 进入现有部署逻辑。
8. 部署成功后执行 `am instrument`。

### 3.2 library 模块 androidTest

library 模块 androidTest 不进入阶段 3。

原因：

- library module 没有 app module 的 `applicationId` 语义。
- library androidTest 的 test host / target package 规则需要单独确认。
- 当前 `GradleProjectInfoReader` 只为 `ModuleInfo.Type.Application` 生成 androidTest synthetic module。
- 当前 `AndroidTestCommandDeriver` 基于 app RunConfig 派生 androidTest task，不知道 gutter 所属 library module。
- 当前 `android_demo_project/library1` 有 `androidTestImplementation` 依赖，但没有 `src/androidTest` fixture。

阶段 4 再单独设计：

- library androidTest synthetic `ModuleInfo` 生成规则。
- library test APK 的 `applicationId` 与 `instrumentationTargetPackage` 来源。
- library androidTest Gradle task 与 output APK glob 派生。
- library androidTest 与 app target APK 的安装关系。
- library androidTest 的 e2e fixture 与验收矩阵。

---

## 4. 阶段 3 架构

### 4.1 总体原则

阶段 3 不为 test APK 新建独立部署协议，而是让 test APK 复用当前 base APK 的部署策略。

关键原则：

- 编译阶段根据 `BuildTarget.ANDROID_TEST` 产出 app APK + app test APK。
- 部署阶段继续按 `applicationId` 分组。
- app APK 与 test APK 都走 `JuggDeployTask.perform()`。
- 如果当前 base APK 逻辑选择 install，test APK 也 install。
- 如果当前 base APK 逻辑选择 code swap，test APK 也 code swap。
- 如果当前 base APK 逻辑选择 full swap / hot fix，test APK 也走同类降级策略。
- test 进程不需要在 swap 前存活；部署完成后通过 `am instrument` 重新创建并运行目标测试。

### 4.2 主链路

```text
JuggAndroidTestLineMarkerContributor
  -> JuggAndroidTestRunConfiguration
  -> JuggAndroidTestRunProfileState
  -> AndroidTestRunSpec
  -> JuggManager.runTask(..., androidTestRunSpec)
  -> JuggConfigurationRunner.runTask(..., androidTestRunSpec)
  -> JuggRunningTask
       -> JuggCompileHelper.compile(BuildTarget.ANDROID_TEST)
       -> JuggDeployerHelper.deploy(DeployOptions.androidTestRunSpec)
            -> JuggDeployTask.run()
                 -> app APK existing deploy logic
                 -> test APK existing deploy logic
            -> TestLauncher.run()
                 -> am instrument
```

### 4.3 数据传递

新增或扩展的上下文只用于启动 instrumentation，不参与部署策略判断：

```kotlin
data class DeployOptions(
    // existing fields...
    val androidTestRunSpec: AndroidTestRunSpec? = null,
)
```

普通 app Run 保持 `androidTestRunSpec = null`。

androidTest Run 设置 `androidTestRunSpec != null`，部署成功后由 `JuggDeployerHelper` 触发 `TestLauncher`。

---

## 5. 详细设计

### 5.1 gutter 与 RunConfig

改动点：

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributor.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt`

规则：

- 只在 `**/src/androidTest/**` 下显示 gutter。
- 只识别 `org.junit.Test` / `org.junit.jupiter.api.Test`。
- 只支持 app module androidTest。
- 如果 gutter 所属 module 是 library module，本阶段不显示 Jugg gutter 或显示不可运行提示，避免半支持。
- 用户未启用 app RunConfig 的 `enableAndroidTest` 时，只弹 Notification，不自动修改 RunConfig。
- 点击 test method 时生成 `testClass + testMethod`。
- 点击 test class 时生成 `testClass`，`testMethod = null`。

### 5.2 JuggManager / Runner / RunningTask 贯穿 spec

改动点：

- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/DeployOptions.kt`

设计：

- 保留现有普通 Run API。
- 新增 overload 或可选参数传入 `AndroidTestRunSpec`。
- `JuggRunningTask.deployDevice()` 构造 `DeployOptions` 时带上 spec。
- `compileUiHandler` 与 `processHandler` 继续沿用现有 console 输出。

失败语义：

- 编译失败：与普通 Run 一致。
- 部署失败：与普通 Run 一致。
- instrumentation 失败：Run 结果失败，console 展示测试失败信息。

### 5.3 部署策略

改动点：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/ApkInstallOrder.kt`

设计：

- 不改 `JuggDeployData` 的 deploy type 分类。
- 不改 `JuggDeployTask.perform()` 的核心策略。
- 不新增 test APK 专用 code swap。
- install 顺序继续由 `ApkInstallOrder.sortedForInstall()` 保证 app APK 在 test APK 前。
- 增量部署时 test APK 的 class output 通过 `ModuleApkBelongsUtils` 路由到 test APK。
- `JuggDeployTask` 对 test APK 的包名解析、PID 查找、overlay 校验、code swap/full swap 均沿用当前逻辑。

注意：

- test 进程通常在 `am instrument` 时创建，swap 前可能没有运行中的 test 进程。
- 阶段 3 不要求“在同一个 test 进程内 redefine 后继续跑”。
- 部署后重新 `am instrument` 是阶段 3 的最终生效方式。

### 5.4 TestLauncher 与结果回传

改动点：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/TestLauncher.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParser.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRenderer.kt`

设计：

- `TestLauncher.run()` 返回值必须进入 `DeployTaskResult` 或 `RunResult`。
- `InstrumentationEvent.Aborted` 视为失败。
- `InstrumentationEvent.TestFinished(FAILURE / ERROR / ASSUMPTION_FAILURE)` 视为失败。
- `execAdbShellCmdStreaming()` 返回非 0 或抛异常视为失败。
- 多设备按现有串行部署模型执行，每台设备部署成功后执行 instrumentation。
- 任一设备测试失败，整体 Run 失败。

---

## 6. 基于 android_demo_project 的 e2e 测试策略

### 6.1 fixture 范围

阶段 3 只使用 app module fixture：

- `android_demo_project/app/src/androidTest/java/com/example/myapplication/ExampleInstrumentedTest.kt`

不新增 library androidTest fixture。library androidTest fixture 留到阶段 4。

### 6.2 e2e 覆盖目标

新增 e2e 测试需要覆盖以下链路：

1. app androidTest full compile：
   - `enableAndroidTest = true`
   - `BuildTarget.ANDROID_TEST`
   - 派生 `:app:assembleDebugAndroidTest`
   - 输出 APK glob 包含 `app/build/outputs/apk/androidTest/debug/*.apk`

2. app + test APK install 顺序：
   - app APK 先于 test APK。
   - test APK 通过 `instrumentationTargetPackage` 识别。

3. app androidTest 增量编译：
   - 修改 `android_demo_project/app/src/androidTest/...` 源码。
   - `.androidTest` module 被 `CompileContextManager` 纳入。
   - 编译输出路由到 test APK。

4. test 部署策略：
   - test APK 进入 `JuggDeployTask` 的现有 `perform()`。
   - hot reload / hot fix / install 的判断不走 test 专用分支。

5. instrumentation 启动：
   - `AndroidTestRunSpec(testClass, testMethod)` 正确转成 `am instrument -w -r -e class ...`。
   - `TestLauncher` 被调用。
   - console 输出包含 per-test 和 summary。

6. 失败回传：
   - 模拟 instrumentation failure。
   - Run 结果失败。
   - console 保留失败 stack 或失败摘要。

### 6.3 建议测试文件

main 模块：

- `main/src/test/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriverTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/deploy/run/ApkInstallOrderTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilderTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParserTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRendererTest.kt`

idea 模块：

- `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributorTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfigurationOptionsTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunProfileStateFlowTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTaskAndroidTestSpecTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/project/AndroidTestIncrementalCompileFlowTest.kt`

如已有同被测对象测试文件，应优先追加用例，不新建重复测试类。

### 6.4 测试执行命令

禁止运行完整测试套件。仅运行定向测试：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.AndroidTestCommandDeriverTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.InstrumentationOutputParserTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.run.ApkInstallOrderTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.JuggAndroidTestRunProfileStateFlowTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.project.AndroidTestIncrementalCompileFlowTest"
```

必要时可运行编译验证：

```bash
./gradlew :idea:compileKotlin
```

---

## 7. 验收标准

### 7.1 功能验收

- app `src/androidTest` 下 JUnit test 可看到 Jugg gutter。
- 点击 method gutter 后，运行单个 test method。
- 点击 class gutter 后，运行整个 test class。
- 未启用 `enableAndroidTest` 时只提示，不自动改 RunConfig。
- app androidTest full compile 同时产出 app APK + test APK。
- app androidTest 源码变更后走增量编译。
- test APK 与 app APK 都走当前部署逻辑。
- deploy 成功后自动执行 `am instrument`。
- 测试失败会让 Run 失败。
- 普通 app Run 行为不变。

### 7.2 兼容验收

- `BuildTarget.APP` 下 `.androidTest` module 仍不参与普通编译。
- `BuildTarget.ANDROID_TEST` 下 app `.androidTest` module 被纳入。
- 旧 `base_build_cmd.txt` 仍兼容为 `BuildTarget.APP`。
- 旧 `ApkInfo` JSON 缺少 instrumentation 字段时仍可反序列化。
- library androidTest 不产生半支持行为。

### 7.3 回归验收

- 普通 Run 不显示 androidTest 特有行为。
- 普通 app deploy 不携带 `AndroidTestRunSpec`。
- 多 APK install 原有顺序不被破坏。
- dynamic feature 现有 APK 路由不被 `isTestApk` 逻辑影响。

---

## 8. 开发顺序

1. 补入口测试：RunConfig options、ProfileState、gutter、未启用提示。
2. 实现 `AndroidTestRunSpec` 从 gutter/ProfileState 到 `DeployOptions` 的贯穿。
3. 补部署链路测试：spec 传递、TestLauncher 可达、失败回传。
4. 实现 `JuggDeployerHelper` 的 deploy 后 instrumentation 结果回传。
5. 补 `android_demo_project` app androidTest e2e 测试。
6. 同步文档：
   - 本文档。
   - `docs/task/androidtest_support_design.md` 中阶段 2 旧口径。
   - 如新增关键入口，更新 `docs/ai_knowledge/98_code_map.md`。

---

## 9. 阶段 4 预告：library androidTest

阶段 4 需要重新展开设计，不复用阶段 3 的 app-only 假设。

待确认问题：

- library androidTest 的 test APK manifest 中 `instrumentationTargetPackage` 实际指向谁。
- AGP 7.2 / 8.x 下 library androidTest output APK 路径是否稳定。
- library androidTest 是否需要依赖 app APK 或 generated test host APK。
- gutter 所属 module 到 Gradle task 的映射规则。
- `GradleProjectInfoReader` 是否应为 `ModuleInfo.Type.Library` 生成 synthetic androidTest module。
- library androidTest 与 app androidTest 同时存在时，RunConfig 如何选择目标。

---

## 10. 变更历史

- 2026-04-29：初版，明确阶段 3 只覆盖 app androidTest，library androidTest 延后到阶段 4；补充基于 `android_demo_project` 的 e2e 测试策略。
- 2026-04-29：开始阶段 3 BUILD，已收口 `AndroidTestRunSpec` 入口传递、`DeployOptions` 携带 spec、deploy 后 instrumentation 失败回传、app androidTest gutter 路径边界。
