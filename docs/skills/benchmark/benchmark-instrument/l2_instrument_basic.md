# L2 instrument 基础用法

目标：验证 Agent 能正确使用 `instrument` 的 `--source-path`、`--class`、`--method`，在不同 source 文件和 class/method 组合下完成参数组装。

## 前置说明

以下所有 case 都假设 `enabledAndroidTest=true` 且设备已连接。前置检查未通过时，case 应记 `SKIP` 并注明原因。

涉及 `McpTestActivity` 或需要 app 启动状态的 case，先执行：

```bash
jugg restart && sleep 2 && jugg tap --text "MCP Test Page"
```

路由后必须用 `activity-stack` 确认已进入 `McpTestActivity`。路由失败时记 `SKIP: page route failed`。

## INST-BASIC-1: 指定 source-path 运行单 class 全部方法

Prompt：运行 app 模块的 `AppLogicInstrumentedTest` 全部测试方法。

期望：
- 先检查 `enabledAndroidTest` 和 device 前置。
- 选择 `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`。
- 不传 `--class`（单 class 文件可省略）。
- 不传 `--method`。
- 单 class 文件只有一个类时，可以省略 `--class`；传了也不扣分。
- `--source-path` 必须是 `src/androidTest/` 下的真实文件路径。

## INST-BASIC-2: 指定 source-path + class（明确 class）

Prompt：运行 `com.example.myapplication.AppUiInstrumentedTest` 的全部测试方法。

期望：
- 先检查前置。
- 选择 `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt --class com.example.myapplication.AppUiInstrumentedTest`。
- 单 class 文件中显式指定 `--class` 也不扣分。

## INST-BASIC-3: 指定 source-path + class + method

Prompt：只运行 `com.example.myapplication.AppLogicInstrumentedTest` 的 `targetContextUsesAppPackage` 方法。

期望：
- 先检查前置。
- 选择 `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt --class com.example.myapplication.AppLogicInstrumentedTest --method targetContextUsesAppPackage`。
- `--method` 必须在已经唯一确定 class 的前提下使用。

## INST-BASIC-4: 通过 gutter 语义选择方法（source-path + method，缺 class）

Prompt：运行 `app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt` 的 `appNameComesFromTargetResources` 方法。

期望：
- 先检查前置。
- 选择 `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt --method appNameComesFromTargetResources`。
- 单 class 文件时可以省略 `--class`；但必须包含 `--method`。
- 结果只应运行指定的单个方法。

## INST-BASIC-5: library 模块 instrument

Prompt：运行 `Library1LogicInstrumentedTest` 全部测试方法。

期望：
- 先检查前置。
- 选择 `instrument --source-path library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`。
- 正确识别这是 library1 模块的 androidTest，使用 library-style self-targeting Test APK。
- 不混淆 app 模块和 library1 模块的 test APK。

## INST-BASIC-6: UI test 需要 app 处于前台

Prompt：运行 `AppUiInstrumentedTest.mainActivityOpensMcpTestPage` 方法。

期望：
- 先检查前置。
- Agent 应了解 UI test 可能需要 app 处于前台（此方法会启动 `MainActivity`，但 test 进程需要 app 已安装且进程存活）。
- 选择 `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt --class com.example.myapplication.AppUiInstrumentedTest --method mainActivityOpensMcpTestPage`。
- 如果 `instrument` 本身会触发部署和重启，Agent 不需要额外执行 `restart`；如果只是增量部署（不重启），Agent 应在 instrument 前确保 app 可访问。

## INST-BASIC-7: source-path 指向不存在的文件

Prompt：运行 `app/src/androidTest/java/com/example/myapplication/NonExistentTest.kt`。

期望：
- Agent 应发现该文件不存在，不能继续执行 instrument。
- 记 `SKIP: source file not found` 或等价描述。
- 不得为了通过而自造文件或改用其他 source path。
- 不得改用 `adb shell am instrument` 绕过。
