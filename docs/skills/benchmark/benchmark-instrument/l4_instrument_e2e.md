# L4 instrument 端到端组合

目标：验证 Agent 在端到端流程中能正确组合前置检查、编译部署、instrument 执行和结果判断。

## INST-E2E-1: 完整 instrument 闭环（app 模块）

Prompt：帮我用 jugg instrument 完成一次 app 模块的 androidTest 验证。运行 `AppLogicInstrumentedTest` 全部方法，记录测试结果。

期望：
- 先检查前置：`jugg status` → 确认 `enabledAndroidTest=true`，`jugg devices` → 确认设备在线。
- 前置未满足时记 `SKIP` 并说明原因。
- 执行 `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`。
- 等待终态（自动轮询到 `isFinal=true`）。
- 记录 `isCompileSuccess`、`isDeploySuccess`、每个测试方法的结果（pass/fail/error）。
- 不使用 `adb shell am instrument` 替代。

## INST-E2E-2: 完整 instrument 闭环（library 模块）

Prompt：运行 `library1` 模块的 `Library1LogicInstrumentedTest`，并记录结果。

期望：
- 先检查前置。
- 执行 `jugg instrument --source-path library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`。
- 正确识别这是 library-style self-targeting Test APK。
- 记录编译、部署和测试执行结果。

## INST-E2E-3: 单方法 instrument 闭环

Prompt：只运行 `AppUiInstrumentedTest` 的 `mainActivityShowsTitle` 方法，这个方法会启动 MainActivity 并验证页面标题。

期望：
- 先检查前置。
- 执行 `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt --class com.example.myapplication.AppUiInstrumentedTest --method mainActivityShowsTitle`。
- 记录该方法的 pass/fail 结果。
- 只验证该方法执行，不跑其他 UI test 方法。

## INST-E2E-4: 前置检查失败时的完整拒绝流程

Prompt：帮我运行 instrument 测试。

期望：
- Agent 应先执行 `jugg status` 和 `jugg devices` 检查前置。
- 如果 `enabledAndroidTest=false`：
  - 停止执行 instrument。
  - 清晰提示：需要打开 Jugg App Run Configuration → 开启 Android Test → 执行 `gradle-build` 建立 baseline → 再试。
  - 记 `SKIP: enabledAndroidTest=false` 并描述所需操作。
- 如果无设备：
  - 记 `SKIP: no device`。
- 不得跳过前置检查直接执行 instrument。

## INST-E2E-5: 先通过 instrument 建立基线再 adb 大范围回归

Prompt：用 `jugg instrument` 把 app 源码和 test 源码变更部署到位，然后我想用 adb 跑更大范围的回归测试。

期望：
- 先用一次 `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt` 完成编译部署。
- instrument 成功后，提示可以使用普通 `adb shell am instrument` 做 class/package/suite 级回归。
- 不得一开始就用 adb 跳过 jugg instrument 的编译部署环节。

## INST-E2E-6: 参数全局位置正确

Prompt：用 JSON 模式运行 instrument，执行 `AppLogicInstrumentedTest`。

期望：
- 选择 `jugg --console=json instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`。
- `--console=json` 必须放在 `instrument` 前。
- `jugg instrument --console=json --source-path ...` 判为参数位置错误。

## INST-E2E-7: 执行后定位日志中的测试方法

Prompt：运行 `AppLogicInstrumentedTest`，确认 `targetContextUsesAppPackage` 方法的结果和对应日志。

期望：
- 执行完整的 instrument 闭环。
- 从 instrument 输出中提取以下信息：
  - `targetContextUsesAppPackage` 方法的 pass/fail 状态。
  - 该方法对应的 logcat 日志（如有）。
- 不把其他方法的日志或设备级日志错误归入该方法。

## INST-E2E-8: 两个模块连续 instrument

Prompt：依次运行 app 模块的 `AppLogicInstrumentedTest` 和 library1 模块的 `Library1LogicInstrumentedTest`，记录两次结果。

期望：
- 每次 instrument 前确认前置仍然满足。
- 第一条：`jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`。
- 第二条：`jugg instrument --source-path library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`。
- 两次 instrument 各自记录结果，不混淆。
- 不在第一条 instrument 执行期间并发第二条。
