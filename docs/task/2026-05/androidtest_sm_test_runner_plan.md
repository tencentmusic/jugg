# Jugg androidTest Test Results UI 升级方案

> 创建时间：2026-05-06
> 状态：规划阶段
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 背景

当前 Jugg androidTest 已具备 app 模块 `androidTest` 的编译、部署和 `am instrument` 运行链路，但结果展示仍复用普通 Run console：

- `JuggConfigurationRunner.runTask()` 通过 `TextConsoleBuilderFactory` 创建普通文本 console。
- `TestLauncher` 使用 `InstrumentationOutputParser` 解析 `am instrument -r` 输出后，只交给 `InstrumentationConsoleRenderer` 渲染 ANSI 文本。
- `06_android_test.md` 当前明确将 SM Test Runner 面板、Debug Executor、rerun failed tests 标为未覆盖。

因此 Jugg 能跑出测试结果，但 Run 面板左侧不会出现 Android Studio 原生 androidTest 那种 `Test Results` 树。

---

## 2. 目标与边界

### 2.1 总目标

让 Jugg androidTest 在保留现有增量编译、部署、`am instrument` 链路的前提下，接入 IntelliJ SM Test Runner，获得 Android Studio 同款 Test Results 体验。

### 2.2 两阶段目标

| 阶段 | 目标 | 用户可见效果 |
|---|---|---|
| 阶段 A：Test Results 基础树 | 接入 SM Test Runner console，把 instrumentation 事件转换为测试树事件 | Run 面板显示 `Test Results` 树、通过/失败/忽略状态、summary 与失败 stack |
| 阶段 B：完整交互 | 补 source navigation 与 rerun failed tests | 点击测试节点跳转源码；失败用例可一键重跑 |

### 2.3 非目标

- 不替换 Jugg 当前编译、部署、test APK 安装策略。
- 不引入 Android Studio 原生 AndroidTest runner 的执行链路，避免绕开 Jugg 增量能力。
- 不引入 ddmlib `RemoteAndroidTestRunner`，当前 `InstrumentationOutputParser` 已能提供足够事件粒度。
- 不支持 Debug Executor。
- 不扩展 library 模块 androidTest 支持。
- 不实现常驻 test harness 或同一 instrumentation 进程内 redefine 后继续跑。

---

## 3. 总体设计

### 3.1 核心思路

保留现有链路：

```text
JuggAndroidTestRunConfiguration
  -> JuggManager.runTask(..., androidTestRunSpec)
  -> JuggConfigurationRunner
  -> JuggRunningTask
  -> JuggDeployerHelper
  -> TestLauncher
  -> am instrument -w -r
```

新增 UI 事件桥：

```text
InstrumentationOutputParser
  -> InstrumentationEvent
  -> InstrumentationSmRunnerBridge
  -> TeamCity service messages
  -> SMTestRunnerConnectionUtil
  -> Test Results tree
```

### 3.2 为什么不用 Android Studio 原生 runner

Android Studio 原生 AndroidTest runner 会绑定其自身 RunConfiguration、Gradle/UTP/测试执行流程。Jugg 的核心价值是复用自己的增量编译与部署链路，因此本方案只复用 IntelliJ Platform 的 Test Results UI，不复用 Android Studio 的 test execution。

### 3.3 为什么用 service message

`SMTestRunnerConnectionUtil.createAndAttachConsole()` 默认从 `ProcessHandler` 输出里读取 TeamCity service messages。Jugg 现有 `SimpleProcessHandler` 已经是 Run console 输出中心，因此最小改动是把 `InstrumentationEvent` 转换成 service message，再写回同一个 process handler。

---

## 4. 阶段 A：Test Results 基础树

### 4.1 目标

阶段 A 只解决“看起来像 Android Studio androidTest”的结果展示：

- Run tool window 使用 SM Test Runner console。
- 左侧显示 `Test Results` 树。
- 支持 class/method 层级。
- 支持 passed / failed / error / ignored / assumption failure。
- failure stack 显示在测试节点下。
- 多设备时按设备分组。
- 普通 Jugg app run 仍保持普通 console。

### 4.2 改动点

| 文件 | 改动 |
|---|---|
| `idea/build.gradle` | 如编译期缺少 test runner classes，`intellij.plugins` 追加 `testFramework` |
| `idea/src/ide_entry/.../JuggAndroidTestRunConfiguration.kt` | `JuggAndroidTestRunProfileState.execute()` 把 `executor` / `environment.runProfile` 传给 Jugg runner |
| `idea/src/main/.../ide/logic/JuggConfigurationRunner.kt` | 当 `androidTestRunSpec != null` 时创建 SM console；普通 run 维持 `TextConsoleBuilderFactory` |
| `idea/src/main/.../deploy/run/TestLauncher.kt` | 增加 test event sink，parser event 同时发给文本 renderer 与 SM bridge |
| `idea/src/main/.../deploy/run/InstrumentationSmRunnerBridge.kt` | 新增，负责把 `InstrumentationEvent` 转为 TeamCity service messages |
| `idea/src/test/...` | 覆盖 console 类型选择与 TestLauncher event sink |
| `main/src/test/.../deploy/instrument` | 覆盖 service message 生成的纯逻辑 |

### 4.3 SM console 创建

新增一个 `JuggAndroidTestConsoleProperties`：

```kotlin
class JuggAndroidTestConsoleProperties(
    project: Project,
    runProfile: RunProfile,
    executor: Executor,
) : SMTRunnerConsoleProperties(project, runProfile, "JuggAndroidTest", executor)
```

阶段 A 不做自定义 navigation 与 rerun，先只提供基础 properties。

`JuggConfigurationRunner` 增加 console factory 分支：

```text
androidTestRunSpec == null
  -> TextConsoleBuilderFactory.getInstance().createBuilder(project).console

androidTestRunSpec != null
  -> SMTestRunnerConnectionUtil.createAndAttachConsole(
       "JuggAndroidTest",
       processHandler,
       JuggAndroidTestConsoleProperties(...)
     )
```

注意：`processHandler.startNotify()` 必须在 console attach 之后执行，否则 SM runner 收不到 testing started lifecycle。

### 4.4 service message 映射

| InstrumentationEvent | service message |
|---|---|
| run start | `enteredTheMatrix` |
| device start | `testSuiteStarted(name=<device>)` |
| class first seen | `testSuiteStarted(name=<class>, locationHint=java:suite://<class>)` |
| `TestStarted` | `testStarted(name=<method>, locationHint=java:test://<class>/<method>)` |
| `TestFinished(OK)` | `testFinished(name=<method>)` |
| `TestFinished(FAILURE/ERROR)` | `testFailed(name=<method>, message=<first stack line>, details=<stack>)` + `testFinished` |
| `TestFinished(IGNORED/ASSUMPTION_FAILURE)` | `testIgnored(name=<method>, message=<reason>)` + `testFinished` |
| class end | `testSuiteFinished(name=<class>)` |
| device end | `testSuiteFinished(name=<device>)` |
| aborted | root-level failed synthetic test 或 failed suite marker |

### 4.5 class suite 生命周期

`am instrument -r` 输出是流式的，不会显式告诉 class suite 何时结束。阶段 A 采用简单规则：

1. 每台设备开始时打开 device suite。
2. 第一次看到某个 class 时打开 class suite。
3. 设备结束时按打开顺序反向关闭所有 class suite。
4. 多设备互不共享 class suite 状态。

这个规则能保证树结构稳定，且不会要求预扫描全部 test list。

### 4.6 文本 console 兼容

阶段 A 保留当前 `InstrumentationConsoleRenderer`，但 service messages 不应该污染用户可见 console 文本。

实现建议：

- SM bridge 写 service messages 到 `ProcessOutputType.SYSTEM` 或普通 stdout，但由 SM runner 过滤展示。
- 人类可读文本继续由 `InstrumentationConsoleRenderer` 输出。
- 如果发现 service message 在 console 标签可见，再把 bridge 的输出和 human-readable 输出分到不同 content type，并以实际 IDE 行为为准调整。

### 4.7 阶段 A 测试

TDD 前置测试：

| 测试 | 断言 |
|---|---|
| `InstrumentationSmRunnerBridgeTest` | OK 测试生成 suite/testStarted/testFinished |
| 同上 | failure 生成 testFailed，stack 写入 details |
| 同上 | ignored / assumption failure 生成 testIgnored |
| 同上 | 多 class 在设备结束时全部关闭 |
| 同上 | service message 内容正确 escape |
| `JuggConfigurationRunnerAndroidTestConsoleTest` | androidTest run 使用 SM console |
| 同上 | 普通 app run 仍使用 text console |
| `TestLauncherSmEventSinkTest` | parser event 同步发送给 SM event sink，且不影响返回失败语义 |

定向验证命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.InstrumentationSmRunnerBridgeTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggConfigurationRunnerAndroidTestConsoleTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.TestLauncherSmEventSinkTest"
./gradlew :idea:compileKotlin
```

---

## 5. 阶段 B：navigation 与 rerun failed

### 5.1 目标

阶段 B 在阶段 A 的测试树基础上补齐交互：

- 点击 class suite 可跳转测试类。
- 点击 method test 可跳转测试方法。
- failure stack trace 可以跳到源码。
- Run 面板提供 rerun failed tests。
- rerun failed 只重跑失败/错误/未通过的 leaf tests，不重跑整套。

### 5.2 source navigation

优先使用 IntelliJ 现有 `JavaTestLocator`：

- class location：`java:suite://com.example.FooTest`
- method location：`java:test://com.example.FooTest/testBar`

新增 `JuggAndroidTestConsoleProperties.getTestLocator()`：

```kotlin
override fun getTestLocator(): SMTestLocator = JavaTestLocator.INSTANCE
```

如果 Kotlin androidTest 方法跳转不稳定，再新增 `JuggAndroidTestLocator`：

1. 先委托 `JavaTestLocator.INSTANCE`。
2. 找不到时用 `JavaPsiFacade.findClass()` 定位类。
3. 在类 PSI 内按方法名查找 Java/Kotlin method。

阶段 B 的默认实现先走 `JavaTestLocator`，避免过早复制 IDE 定位逻辑。

### 5.3 stack trace navigation

`SMTRunnerConsoleProperties` 已支持 stack trace filter 扩展。阶段 B 给 `JuggAndroidTestConsoleProperties` 增加 Java/Kotlin stack trace filter，目标是让 failure details 中的 `FooTest.kt:42` 可点击。

优先复用 IDE 现有 filter；若当前依赖不可见，再只保留 test node navigation，不强行自研 stack parser。

### 5.4 rerun failed 设计

当前 `AndroidTestRunSpec` 只支持单个 `testClass + testMethod`。rerun failed 需要表达多个 leaf test。

新增字段，保持向后兼容：

```kotlin
data class AndroidTestRunSpec(
    val testClass: String? = null,
    val testMethod: String? = null,
    val testFilters: List<TestFilter> = emptyList(),
    val extraArgs: List<Pair<String, String>> = emptyList(),
    val runnerOverride: String? = null,
)

data class TestFilter(
    val className: String,
    val methodName: String? = null,
)
```

兼容规则：

- `testFilters.isEmpty()`：沿用 `testClass/testMethod`。
- `testFilters.size == 1`：生成单个 `-e class class[#method]`。
- `testFilters.size > 1`：生成 `-e class class1#method1,class2#method2`。

AndroidJUnitRunner 支持逗号分隔的 `class` 参数；若后续遇到 runner 不兼容，再降级为多次 `am instrument` 串行执行。

### 5.5 rerun action

新增 `JuggAndroidTestRerunFailedTestsAction`，继承 `AbstractRerunFailedTestsAction`。

职责：

1. 从 SM runner model 读取 failed leaf tests。
2. 从每个 failed test 的 location/name 还原 `TestFilter(className, methodName)`。
3. 构造包装 RunProfile，复用原 `JuggAndroidTestRunConfiguration` 的 app 配置查找逻辑。
4. 新的 RunProfileState 调用 `JuggManager.runTask(appOptions, rerunSpec)`。

`JuggAndroidTestConsoleProperties.createRerunFailedTestsAction(consoleView)` 返回该 action。

### 5.6 rerun name 与 location 约定

为了 rerun failed 可稳定还原目标测试，阶段 A/B 必须统一节点命名：

| 节点 | name | locationHint |
|---|---|---|
| device suite | 设备展示名 | 空 |
| class suite | FQCN | `java:suite://FQCN` |
| method test | methodName | `java:test://FQCN/methodName` |

不要把 method test name 写成 `FQCN.methodName`，否则树上可读性较差，rerun 也要二次拆分。

### 5.7 阶段 B 测试

TDD 前置测试：

| 测试 | 断言 |
|---|---|
| `InstrumentCommandBuilderTest` | 单个 `TestFilter` 生成 `-e class Foo#bar` |
| 同上 | 多个 `TestFilter` 生成逗号分隔 class 参数 |
| 同上 | `testFilters` 优先于旧 `testClass/testMethod` |
| `JuggAndroidTestConsolePropertiesTest` | `getTestLocator()` 返回可处理 `java:suite` / `java:test` 的 locator |
| `JuggAndroidTestRerunFailedTestsActionTest` | failed leaf tests 转成 `AndroidTestRunSpec.testFilters` |
| 同上 | ignored tests 不进入 rerun failed |
| 同上 | rerun failed 保留 runnerOverride / extraArgs |

定向验证命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.InstrumentCommandBuilderTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.JuggAndroidTestConsolePropertiesTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.JuggAndroidTestRerunFailedTestsActionTest"
./gradlew :idea:compileKotlin
```

---

## 6. 分阶段落地顺序

### 6.1 阶段 A 执行顺序

1. 在 `main/src/test` 写 `InstrumentationSmRunnerBridgeTest`，先固定 service message 协议。
2. 在 `main/src/main` 新增纯逻辑 bridge，或放在 `idea/src/main` 并用 idea test 覆盖；优先选择不依赖 IDE API 的纯逻辑层。
3. 在 `idea/src/test` 写 console 分支测试。
4. 改 `JuggConfigurationRunner`，androidTest run 切到 SM console。
5. 改 `TestLauncher`，新增 event sink。
6. 跑阶段 A 定向测试与 `:idea:compileKotlin`。
7. 手动验证 `android_demo_project` 的 app androidTest，确认 Run 面板出现 Test Results 树。

### 6.2 阶段 B 执行顺序

1. 扩展 `AndroidTestRunSpec` 和 `InstrumentCommandBuilder` 支持 `testFilters`。
2. 给阶段 A 的 service message 补 `locationHint`。
3. 新增 `JuggAndroidTestConsoleProperties.getTestLocator()`。
4. 新增 rerun failed action。
5. 跑阶段 B 定向测试与 `:idea:compileKotlin`。
6. 手动验证失败用例 rerun 只执行失败 leaf tests。

---

## 7. 风险与回退

| 风险 | 影响 | 处理 |
|---|---|---|
| `testFramework` 依赖在不同 AS baseline 下不可见 | 编译失败或运行时 ClassNotFound | 先用当前 `ideaIC-223.7571.182` 编译验证；必要时通过 compat 层或反射隔离 |
| service message 泄漏到 console 文本 | 用户看到 `##teamcity[...]` | 调整输出 content type 或 custom converter |
| `JavaTestLocator` 对 Kotlin method 跳转不稳定 | 点击节点不能跳源码 | 阶段 B 增加 `JuggAndroidTestLocator` fallback |
| 多 failed tests 的 `-e class` 逗号语法被自定义 runner 拒绝 | rerun failed 失败 | 降级为逐个 `am instrument` 串行执行 |
| rerun failed action 与 RunProfile/ExecutionEnvironment 绑定复杂 | rerun 按钮不可用 | 阶段 B 可先提供 context action 或临时 RunConfig，后续再接 toolbar action |

---

## 8. 验收标准

### 8.1 阶段 A

- 普通 Jugg app run 的 Run 面板不变。
- Jugg androidTest run 的 Run 面板出现 `Test Results` 树。
- 成功测试显示 passed。
- 失败测试显示 failed，并能看到 stack。
- ignored / assumption failure 不计为 passed。
- `TestLauncher.run()` 的成功/失败语义不变。

### 8.2 阶段 B

- 点击 class suite 跳转测试类。
- 点击 method test 跳转测试方法。
- failure stack 至少能通过测试节点跳回对应测试方法。
- rerun failed 只重跑失败 leaf tests。
- rerun failed 保留原 runnerOverride 与 extraArgs。
- 多设备结果不互相覆盖。

---

## 9. 文档同步点

实现完成后需要同步：

- `docs/ai_knowledge/06_android_test.md`
  - 删除或更新“SM Test Runner 面板、rerun failed tests 未覆盖”的边界描述。
  - 在运行入口章节补充 SM Test Runner UI 链路。
  - 在测试入口章节补充新增定向测试命令。
- `docs/ai_knowledge/98_code_map.md`
  - AndroidTest 运行模型追加 `InstrumentationSmRunnerBridge` / `JuggAndroidTestConsoleProperties` / `JuggAndroidTestRerunFailedTestsAction`。
- `docs/ai_knowledge/99_index.md`
  - 如新增专题文档或关键入口变更，补任务路由。

---

## 10. 结论

两个阶段可以一起设计，但实现应分开落地：

1. 先用 SM Test Runner + service message 拿到稳定 Test Results 树。
2. 再基于稳定的 test node name/location 约定补 navigation 与 rerun failed。

关键约束是：SM Runner 只负责 UI，不接管 Jugg 的编译、部署与 instrumentation 执行。
