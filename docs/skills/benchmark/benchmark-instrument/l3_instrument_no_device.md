# L3 无设备场景 - instrument

目标：验证 Agent 在无 Android 设备时能正确处理 `instrument` 行为，区分“前置不满足”和“命令本身问题”。

执行条件：MCP 端口可用，但 `jugg devices` 返回空列表。`enabledAndroidTest=true`（已有 baseline）。

如果真实环境存在在线设备，本文件全部 case 记为环境性 `SKIP`，并从有效总分分母中排除。

## INST-NODEV-1: 无设备时尝试 instrument

Prompt：运行 `AppLogicInstrumentedTest`。

期望：
- 先检查 `status` 和 `devices` 前置。
- `enabledAndroidTest=true` 但 `devices` 为空时：
  - 仍可执行 instrument（编译阶段可以成功）。
  - 部署或 instrumentation 运行阶段会失败。
  - Agent 应如实记录失败原因（`no device` 或等价），不把它当成编译失败。
- 若 Agent 直接记 `SKIP: no device` 而不执行 instrument，也可以接受（无设备运行阶段必然失败）。

## INST-NODEV-2: 无设备时 instrument 参数校验仍然有效

Prompt：运行一个不存在的 androidTest 文件 `app/src/androidTest/java/com/example/myapplication/FakeTest.kt`。

期望：
- Agent 应先发现 `source-path` 指向的文件不存在。
- 无论有无设备，参数层面的错误应优先处理。
- 记 `SKIP: source file not found` 或等价描述。

## INST-NODEV-3: 无设备时检查 enabledAndroidTest

Prompt：检查当前是否可以运行 androidTest。

期望：
- 先执行 `jugg status`，读取 `enabledAndroidTest` 字段。
- 再执行 `jugg devices`，读取设备列表。
- 给出结论：`enabledAndroidTest=X`, `hasDevice=false` → 无法成功运行 instrument。
- 结论中区分“baseline 未建立”和“无设备”两个因素。

## INST-NODEV-4: 无设备时用 adb 不可行

Prompt：设备列表为空，请运行 `AppLogicInstrumentedTest`。

期望：
- Agent 不得用 `adb shell am instrument` 替代 `jugg instrument`。
- 不得用 `adb connect` 或模拟器启动等外部动作绕过。
- 必须使用 `jugg instrument` 或判定 `SKIP: no device`。

## INST-NODEV-5: 无设备时 library 模块 instrument

Prompt：无设备环境下运行 `Library1LogicInstrumentedTest`。

期望：
- 与 INST-NODEV-1 一致逻辑：编译阶段可成功，部署/运行阶段因无设备失败。
- Agent 正确识别 library1 模块的 androidTest source。
- 不使用 app 模块的 test APK 替代。
