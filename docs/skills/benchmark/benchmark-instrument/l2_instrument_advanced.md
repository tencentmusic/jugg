# L2 instrument 高级用法

目标：验证 Agent 能否正确处理 `--runner`、`--extras`，以及前置条件不满足时的正确拒绝行为。

## 前置说明

所有 case 在确定前置不满足时必须记 `SKIP` 并注明原因，不得绕过条件强行执行。

## INST-ADV-1: runner override

Prompt：用自定义 runner `com.example.myapplication.CustomTestRunner` 运行 `AppLogicInstrumentedTest`。

期望：
- 先检查前置。
- 选择 `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt --runner com.example.myapplication.CustomTestRunner`。
- `--runner` 完整格式为 `<testPkg>/<runner>`，但 CLI 只需要 runner FQCN。
- 不允许使用过期 `--instrumentation-runner` 参数。

## INST-ADV-2: extras 参数传透

Prompt：运行 `AppLogicInstrumentedTest.extrasReceivesBenchmarkModeAndTimeout` 方法，附带 extra `"benchmark_mode=true"` 和 `"timeout=5000"`。

期望：
- 先检查前置。
- 选择 `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt --class com.example.myapplication.AppLogicInstrumentedTest --method extrasReceivesBenchmarkModeAndTimeout --extras benchmark_mode=true;timeout=5000`。
- `--extras` 使用分号分隔 key=value 对。
- 不允许使用 `-e` raw am instrument 风格传参。
- 测试结果 `extrasReceivesBenchmarkModeAndTimeout` 必须 PASS；该 method 会用 `InstrumentationRegistry.getArguments()` 断言 extra 值正确到达设备端。

## INST-ADV-2b: extras 值含特殊字符（分隔符冲突）

Prompt：运行 `AppLogicInstrumentedTest.extrasHandlesSpecialCharacters` 方法，附带 extra `"filter=name=foo;bar"` 和 `"tags=smoke;regression"`。这些 extra 值中包含 `=` 和 `;`，可能会与 CLI 分隔符冲突。

期望：
- 先检查前置。
- Agent 必须正确组装参数，使 `extrasHandlesSpecialCharacters` 方法 PASS。
- 该方法会用 `InstrumentationRegistry.getArguments()` 断言：
  - `getArguments().getString("filter")` == `"name=foo;bar"`
  - `getArguments().getString("tags")` == `"smoke;regression"`
- 可接受的处理方式：
  - 使用转义（如 `--extras 'filter=name\=foo\;bar;tags=smoke;regression'`），或
  - 使用引号包裹含特殊字符的 value，或
  - 其他合理方式使 extra 值正确到达设备端。
- 直接传 `--extras filter=name=foo;bar;tags=smoke;regression` 不转义的，会导致 extra 解析错误，测试 FAIL → 最高 2 分。
- 测试 FAIL 且未识别出是 extras 特殊字符所致：最高 2 分。

## INST-ADV-3: runner + extras 组合

Prompt：用 runner `com.example.myapplication.CustomTestRunner` 运行 `AppUiInstrumentedTest.mainActivityShowsTitle`，附带 extra `"log_level=debug"`。

期望：
- 先检查前置。
- 选择 `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt --class com.example.myapplication.AppUiInstrumentedTest --method mainActivityShowsTitle --runner com.example.myapplication.CustomTestRunner --extras log_level=debug`。
- 所有参数都在一次 instrument 调用中完成。

## INST-ADV-4: enabledAndroidTest=false 时的前置判断

Prompt：运行 `AppLogicInstrumentedTest` 全部测试方法。

期望：
- 如果 `jugg status` 返回 `enabledAndroidTest=false`：
  - 必须停止执行 `instrument`。
  - 必须给出提示：打开 Jugg App Run Configuration，开启 Android Test / `enableAndroidTest`，执行一次 full build / `gradle-build` 建立 baseline。
  - 记 `SKIP: enabledAndroidTest=false`。
- 如果 `enabledAndroidTest=true`，正常执行 instrument。
- 不得绕过前置直接执行 instrument。

## INST-ADV-5: 缺少 --source-path 的负向验证

Prompt：运行 androidTest 的 `com.example.myapplication.AppLogicInstrumentedTest` 类。

期望：
- Agent 必须补全 `--source-path`（从 class 名反查 androidTest 源文件路径）。
- 若 Agent 直接执行 `jugg instrument --class com.example.myapplication.AppLogicInstrumentedTest`（缺 `--source-path`），应判为参数缺失。
- 不允许用 `adb shell am instrument` 替代。
- 不允许臆造不存在的 source-path。

## INST-ADV-6: source-path 指向非 androidTest 目录的负向验证

Prompt：运行 `app/src/main/java/com/example/myapplication/MainActivity.kt` 的测试。

期望：
- Agent 应识别该路径是 `src/main/java` 而非 `src/androidTest`，不是有效 androidTest source。
- 记 `SKIP: not an androidTest source file`。
- 不得直接把它当 `--source-path` 传给 instrument。

## INST-ADV-7: 多 class 文件且 --method 无法唯一确定 class（主动构造）

Prompt：在 `app/src/androidTest/java/com/example/myapplication/` 下新建一个名为 `MultiClassInstrumentedTest.kt` 的文件，写入两个测试类 `FirstTest` 和 `SecondTest`，两个类都包含一个名为 `testCommonBehavior` 的 @Test 方法。然后用 instrument 运行 `testCommonBehavior` 方法（不要指定 `--class`），观察结果。最后删除这个临时文件并确认恢复。

期望：
- 正确创建包含两个 class、同名方法的合法 androidTest 源文件。
- 执行 `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/MultiClassInstrumentedTest.kt --method testCommonBehavior`（不带 `--class`）。
- Agent 应识别多 class 文件中 `--method` 无法唯一确定 class：
  - 要求用户提供 `--class` 以消除歧义，或
  - 直接记 `SKIP: multiple classes contain method testCommonBehavior`。
- 不得在有歧义的情况下任选一个 class 执行 instrument。
- 完成后删除 `MultiClassInstrumentedTest.kt`，并确认文件已不存在。

## INST-ADV-8: 用 adb 跳过 jugg instrument 的违规行为

Prompt：在已部署 test APK 的设备上，直接运行 `AppLogicInstrumentedTest`。

期望：
- Agent 必须使用 `jugg instrument --source-path ...`，不得直接用 `adb shell am instrument` 替代。
- 只有在 case 明确声明“先通过 jugg instrument 完成首次编译部署，再用 adb 做大范围回归”时才允许使用 adb。
- 直接用 adb 替代 jugg instrument 应判为违规。

## INST-ADV-9: library module 的 runner + extras 组合

Prompt：用 runner `com.example.library1.test.CustomRunner` 运行 `Library1UiInstrumentedTest`，附带 extra `"suite=benchmark"`。

期望：
- 先检查前置。
- 选择 `instrument --source-path library1/src/androidTest/java/com/example/library1/Library1UiInstrumentedTest.kt --runner com.example.library1.test.CustomRunner --extras suite=benchmark`。
- 正确路由到 library1 的 self-targeting Test APK。
