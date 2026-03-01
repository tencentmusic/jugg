# Jugg MCP 测试用例

> 说明：本文档覆盖 Jugg MCP 全部 17 个工具的功能验证，用自然语言描述，可直接输入给 AI coding agent 执行。
> 工具清单来源：`McpToolActionRegistry.kt` + `08_mcp_usage.md`

---

## 执行须知

### 执行顺序约束

本文档的章节顺序即为推荐执行顺序。**第一章（远程 SSH）必须最先执行**，因为涉及 IDE 弹窗交互，需要用户在电脑前操作。交互完成后用户即可离开，剩余用例由 agent 自动完成。

### 分组执行策略（防止 context 溢出）

> 本文档有 64 个用例，工具调用密集，单次会话 context 容易溢出。**必须按分组执行**，每组隔离 context。

**分组表：**

| 组 | 用例范围 | 章节 | 备注 |
|----|---------|------|------|
| 1 | TC-01~04 | 一、远程 SSH | 需用户交互，直接执行 |
| 2 | TC-05~08 | 二、三 基础连通+设备 | |
| 3 | TC-09~15 | 四、截图/录屏/布局 | |
| 4 | TC-16~24 | 五、应用启动与交互 | |
| 5 | TC-25~30 | 六、编译与部署（正常） | |
| 6 | TC-31~34 | 七、编译失败 | |
| 7 | TC-35 | 八、build.gradle 降级 | |
| 8 | TC-36~39 | 九、长耗时编译 | |
| 9 | TC-40~54 | 十、无设备场景 | 执行前关闭 AVD，执行后恢复 |
| 10 | TC-55~60 | 十一、十二 设备选择+错误处理 | |
| 11 | TC-61~64 | 十三、组合场景 | |

**通用规则（适用于所有 AI coding agent）：**

1. 组1（TC-01~04）直接执行（需要用户交互）
2. 其余每组独立执行，执行完一组再执行下一组
3. **严格串行**：MCP 同时只支持一个客户端调用，不可并行
4. 每组执行前，先读取本文件中对应章节的用例内容
5. 每组只返回/记录精简的测试结果表格（用例编号、PASS/FAIL、关键备注），不记录工具调用的原始 JSON
6. 涉及环境变更（开关 AVD）在组与组之间处理
7. 每组开始前，告知用户该组负责哪些用例
8. 每组完成后，展示结果摘要，并追加写入 `jugg-mcp-test-result.md`
9. 全部完成后，汇总整体通过率

**Sub-agent 优化（支持 Sub-agent 的工具适用）：**

> 如果你的 agent 框架支持子任务/Sub-agent 机制（如 Claude Code 的 Task 工具），请使用以下优化策略。
> 不支持的工具（如 Codex、Continue）按上方通用规则分组执行即可，每组作为一次独立会话/对话轮次。

- 组2~11 每组启动一个 Sub-agent 独立执行，主 agent 仅做调度和汇总
- 每个 Sub-agent 自行读取本文件对应章节，逐条执行工具调用
- Sub-agent 完成后仅返回精简结果表格给主 agent，避免传递大量原始输出
- 主 agent 负责：启动/等待 Sub-agent、环境变更操作、结果汇总写入

### 环境操作约定

- **确认初始状态**：用户提供了测试工程，且 jugg 可用；通过 `adb` 和 `emulator` 确认当前无连接的物理设备，且有虚拟机设备可以启动/关闭
- **启动 AVD**：需要保证有设备时，自行通过 cli 完成 AVD 启动。e.g. `emulator -avd <avd_name> &`（或通过 Android Studio AVD Manager 启动）
- **关闭 AVD**：需要保证无设备时，自行通过 cli 完成 AVD 启动。e.g. `adb emu kill` 或 `adb -s <serial> emu kill`
- 涉及"无设备"的用例，执行前必须先通过上述 CLI 关闭所有 AVD/拔掉真机，执行后再恢复


## 交付约定

- 按次序执行测试用例，每完成一项都实时记录测试结果到工程根目录的 jugg-mcp-test-result.md
- 测试过程不中断，测试完成后交付整体结果

---

## 一、远程 SSH 信息（最先执行，需要用户交互）

> 本章必须在用户在场时执行。`request_remote_ssh_info` 会触发 IDE 弹窗二次确认，需要用户点击。
> 两条路径（同意 / 不同意）都要测试。建议先测"用户同意"，再测"用户不同意"。

**TC-01: 请求 SSH 信息 - 用户同意**
调用 `request_remote_ssh_info`，传入 `projectDir`、`reason="testing mcp ssh tool"`、`userConsent=true`。IDE 侧会弹出二次确认弹窗，**用户点击"同意/确认"**。验证返回 `status` 为 `OK`，`data` 中包含 SSH 连接信息（如 host、port、username 等字段）。

**TC-02: 请求 SSH 信息 - 用户拒绝**
再次调用 `request_remote_ssh_info`，传入 `projectDir`、`reason="testing mcp ssh tool rejection"`、`userConsent=true`。IDE 侧弹出二次确认弹窗，**用户点击"拒绝/取消"**。验证返回 `status` 为 `ERROR`，不返回任何 SSH 连接信息。

**TC-03: 请求 SSH 信息 - userConsent=false**
调用 `request_remote_ssh_info`，传入 `projectDir`、`reason="test"`、`userConsent=false`。验证不弹出 IDE 弹窗，直接返回 `status` 为 `ERROR`，不返回 SSH 信息（agent 未获得用户授权即直接拒绝）。

**TC-04: 请求 SSH 信息 - 缺少 reason**
调用 `request_remote_ssh_info`，不传 `reason` 参数，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`。

> 以上交互完成后，用户可以离开电脑，后续用例全部由 agent 自动执行。

---

## 二、基础连通性

**TC-05: 获取项目列表**
调用 `list_projects`，验证返回值中 `status` 为 `OK`，`data.projects` 是一个数组，数组中每个元素包含 `projectDir`（字符串）和 `initialized`（布尔值）字段。

**TC-06: list_projects 无参数**
`list_projects` 不需要任何参数（无 `projectDir` 要求），直接调用应当成功返回，不应报 `MCP_INVALID_PARAMS`。

---

## 三、设备相关工具

**TC-07: 获取设备列表**
调用 `device_list`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 中包含设备信息列表，且有 `selected` 标记标识当前选中的设备。

**TC-08: device_list - 项目未初始化**
调用 `device_list`，传入一个不存在/未初始化的 `projectDir`（如 `/tmp/not_a_project`），验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_PROJECT_NOT_INITIALIZED`。

---

## 四、截图 / 录屏 / 布局导出

**TC-09: 截图 - 有设备连接**
在有设备连接的情况下，调用 `screenshot`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`artifacts` 数组非空，包含一个类型为 `screenshot` 的产物，`path` 字段指向一个实际存在的图片文件。

**TC-10: 开始录屏 - 立即返回 session**
在有设备连接的情况下，调用 `start_record`，仅传入 `projectDir`，验证返回 `status` 为 `OK`，`data` 中包含 `sessionId`，且调用应快速返回（不阻塞至录屏结束）。

**TC-11: 停止录屏 - 拉取产物**
先调用 `start_record` 获取 `sessionId`，等待 2~3 秒后调用 `stop_record`（传入 `projectDir`、`sessionId`），验证返回 `status` 为 `OK`，`artifacts` 中包含可读取的 mp4 文件。

**TC-12: start_record - 不接受旧参数**
调用 `start_record`，传入 `projectDir` 和旧参数 `durationSec=10`（或 `tapX`/`tapY`），验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`。

**TC-13: stop_record - 缺少 sessionId**
调用 `stop_record`，仅传入 `projectDir`，不传 `sessionId`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`。

**TC-14: 布局导出 - 有设备**
在有设备连接的情况下，调用 `layout_dump`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`artifacts` 数组中包含一个 `layout_dump` 类型的产物，`path` 指向一个 XML 文件。读取该文件，确认内容是有效的 UI 层级 XML。

**TC-15: Activity 栈查询**
在有设备连接的情况下，调用 `activity_stack`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 或 `artifacts` 中包含当前 Activity 栈信息，能看到前台 Activity 名称。

---

## 五、应用启动与交互

**TC-16: 启动应用 - 默认入口**
调用 `start_app`，仅传入 `projectDir`，验证返回 `status` 为 `OK`，应用被成功启动（可通过后续 `activity_stack` 或 `screenshot` 确认）。

**TC-17: 启动应用 - 指定包名**
调用 `start_app`，传入 `projectDir` 和一个有效的 `packageName`，验证返回 `status` 为 `OK`。

**TC-18: 启动特定 Activity - 基本调用**
调用 `start_activity`，传入 `projectDir` 和一个有效的 `activity`（如 `.MainActivity`），验证返回 `status` 为 `OK`，目标 Activity 被启动。

**TC-19: 启动特定 Activity - 完整 intent 参数**
调用 `start_activity`，传入 `projectDir`、`action`（如 `android.intent.action.VIEW`）、`data`（如 `https://example.com`）、`categories`（如 `["android.intent.category.BROWSABLE"]`）、`mimeType`、`extras`（如 `{"key1": "value1", "key2": 42, "key3": true}`），验证返回 `status` 为 `OK`。

**TC-20: 坐标点击**
调用 `tap`，传入 `projectDir`、`x=540`、`y=960`，验证返回 `status` 为 `OK`。可通过前后截图对比确认点击生效。

**TC-20a: 百分比点击**
调用 `tap`，传入 `projectDir`、`xPercent=50`、`yPercent=50`，验证返回 `status` 为 `OK`，`data` 中 `mode` 为 `percent`，`screenWidth` 和 `screenHeight` 有值，`x` 和 `y` 为换算后的像素坐标。

**TC-20b: 元素模式点击 - 按 text 精确匹配**
通过 `layout_dump` 获取当前 UI 层级 XML，找到一个有**唯一** `text` 属性的可见元素（确认该 text 在当前界面只出现一次），然后调用 `tap`，传入 `projectDir` 和 `text=<该元素的完整 text>`，验证返回 `status` 为 `OK`，`data.mode` 为 `element`，`data.matchedElement` 包含对应元素信息。注意：text 为精确匹配，子串不会命中。

**TC-20c: 元素模式点击 - 按 resourceId 匹配**
通过 `layout_dump` 获取当前 UI 层级 XML，找到一个有 `resource-id` 属性的元素，然后调用 `tap`，传入 `projectDir` 和 `resourceId=<该元素的 resource-id>`，验证返回 `status` 为 `OK`，`data.mode` 为 `element`。

**TC-20d: 元素模式点击 - 无匹配返回候选**
调用 `tap`，传入 `projectDir` 和 `text="ThisElementDoesNotExist_12345"`，验证返回 `status` 为 `ERROR`，`message` 中包含"No matching UI element found"以及可点击的候选元素列表。

**TC-20e: 元素模式点击 - 多匹配返回候选列表**
通过 `layout_dump` 找到一个在当前界面出现多次的 `text`（如列表项的重复文字），调用 `tap`，传入 `projectDir` 和 `text=<该重复 text>`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`，`data.matchCount` > 1，`data.matches` 为数组且每个元素包含 `bounds`、`centerX`、`centerY`，`message` 中包含引导使用坐标或百分比模式的提示。Agent 应根据返回的坐标信息用 `tap(x, y)` 进行二次精确点击。

**TC-21: tap - 缺少必填参数**
调用 `tap`，仅传入 `projectDir`，不传 `x`、`y`、`xPercent`、`yPercent`、`text`、`resourceId`、`contentDesc`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`。

**TC-21a: tap - 坐标模式优先于百分比模式**
调用 `tap`，同时传入 `projectDir`、`x=100`、`y=200`、`xPercent=50`、`yPercent=50`，验证返回 `status` 为 `OK`，`data.mode` 为 `coordinate`，`data.x` 为 100，`data.y` 为 200（优先使用坐标模式）。

**TC-22: 重启应用**
调用 `restart_app`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`message` 包含 "restart_app executed successfully"。`restart_app` 不返回额外 data 字段（`data` 为空对象）。

**TC-23: 重启应用 - serial 参数不支持**
`restart_app` 不接受 `serial` 参数（`additionalProperties=false`），因此传入 `serial` 会被 MCP 框架拦截返回 `MCP_INVALID_PARAMS`。本条用例由单元测试 `testRestartAppRejectSerialArgument` 覆盖。

**TC-24: 重启应用 - serial 回落（不适用）**
`restart_app` 不支持 `serial` 参数，本条用例不适用。设备选择始终使用 IDE 当前选中的设备。

---

## 六、编译与部署（正常场景）

**TC-25: 仅编译（不部署）**
修改项目中一个 Kotlin/Java 文件（如加一行注释），然后调用 `compile_only`，传入 `projectDir`，验证返回 `status` 为 `OK`，确认编译成功但不会部署到设备。

**TC-26: 编译并部署 - 同步完成**
修改项目中一个文件，调用 `compile_and_deploy`，传入 `projectDir`。如果返回 `isFinal=true`，验证 `status` 为 `OK`；如果返回 `isFinal=false`，验证 `data` 中包含 `jobId`。

**TC-27: 编译并部署 - 异步轮询**
调用 `compile_and_deploy` 后，如果返回 `isFinal=false`，取出 `jobId`，反复调用 `get_compile_status`（传入 `projectDir` 和 `jobId`），直到返回 `isFinal=true`，验证最终 `status` 为 `OK` 或包含编译错误信息。

**TC-28: 查询编译状态 - 无效 jobId**
调用 `get_compile_status`，传入有效 `projectDir` 和一个不存在的 `jobId`（如 `"fake-job-999"`），验证返回 `status` 为 `ERROR`，有合理的错误信息。

**TC-29: Gradle 回退编译**
调用 `force_gradle_compile`，传入 `projectDir`。由于 Gradle 编译较慢，验证可能返回 `isFinal=false` 和 `jobId`，使用 `get_compile_status` 轮询直到完成。

**TC-30: 卸载重装 APK**
调用 `clean_reinstall_apk`，传入 `projectDir`，验证返回 `status` 为 `OK`（或异步完成后为 `OK`），应用数据被清空，APK 被重新安装。注意：此操作会清空应用数据，测试时需预期。

---

## 七、编译失败 - 错误信息验证

> 本章验证编译失败时，各编译类工具能否正确返回可读的错误信息（文件名、行号、错误描述）。

**TC-31: compile_only - 语法错误**
在项目中故意引入一个 Kotlin 语法错误（如在某个 `.kt` 文件中写入 `val x: String = 123`），调用 `compile_only`。验证返回 `status` 为 `ERROR`，`message` 或 `data` 中包含：出错文件名、行号、具体错误描述（如类型不匹配）。错误信息应当足够让 agent 定位并修复问题。

**TC-32: compile_and_deploy - 语法错误**
同 TC-31 的错误代码不还原，调用 `compile_and_deploy`。如果是异步返回，使用 `get_compile_status` 轮询至终态。验证最终 `status` 为 `ERROR`，错误信息中包含出错文件名、行号和错误描述。

**TC-33: compile_and_deploy - 符号未解析**
在项目中调用一个不存在的方法（如 `nonExistentMethod()`），调用 `compile_and_deploy`。等待终态，验证返回 `status` 为 `ERROR`，错误信息中包含 "unresolved reference" 或类似未解析符号的描述。验证完毕后还原代码。

**TC-34: force_gradle_compile - 编译失败**
在项目中引入一个编译错误，调用 `force_gradle_compile`。使用 `get_compile_status` 轮询至终态。验证最终 `status` 为 `ERROR`，错误信息中包含可定位的文件和错误描述。验证完毕后还原代码。

---

## 八、编译降级 - build.gradle 修改触发 Gradle 编译

> 验证 `compile_and_deploy` 在检测到 `build.gradle` 文件变更时，自动降级到 Gradle 编译路径。

**TC-35: 修改 build.gradle 后 compile_and_deploy 降级**
1. 在项目的 `build.gradle`（或 `build.gradle.kts`）中做一个无害修改（如在文件末尾加一行注释 `// mcp test`）
2. 调用 `compile_and_deploy`，传入 `projectDir`
3. 验证行为：工具应自动降级走 Gradle 编译路径（而非 Jugg 增量编译），可通过返回的 `message` 或 `data` 中是否包含 Gradle 相关的描述来判断
4. 使用 `get_compile_status` 轮询至终态，验证最终编译成功
5. 还原 `build.gradle` 的修改

---

## 九、长耗时编译场景（>25s）

> 本章验证编译耗时超过 25 秒的情况下，异步机制是否正常工作，编译成功和失败的结果都能正常获取。
> 制造长耗时方式：在根目录 build.gradle 增加 sleep 25s，且在 build.gradle 末尾增加空行，保证触发 build.gradle 变更识别
> 测试完成后，需回退改动

**TC-36: 长耗时编译 - 成功**
1. 通过 CLI 修改一个会触发大范围重编译的文件（如在 `build.gradle` 中添加一行无害注释，或修改 `buildSrc` 中的版本号常量，使整个工程需要重新编译）
2. 调用 `force_gradle_compile`，传入 `projectDir`
3. 验证立即返回 `isFinal=false` 和 `jobId`（因为耗时超过同步阈值）
4. 使用 `get_compile_status` 持续轮询（按返回的 `pollIntervalSuggestedMs` 字段执行），验证中间状态返回合理（如 `isFinal=false`，可能包含进度信息）
5. 等待编译完成（预期超过 25 秒），验证最终返回 `isFinal=true`、`status` 为 `OK`
6. 还原修改

**TC-37: 长耗时编译 - 失败**
1. 通过 CLI 在一个公共基础模块的核心文件中引入编译错误（如在频繁被依赖的工具类中写入非法语法），同时修改 `build.gradle` 确保触发完整 Gradle 编译
2. 调用 `force_gradle_compile`，传入 `projectDir`
3. 验证立即返回 `isFinal=false` 和 `jobId`
4. 使用 `get_compile_status` 持续轮询，等待终态
5. 验证最终返回 `isFinal=true`、`status` 为 `ERROR`，且错误信息中包含出错文件名和错误描述
6. 还原修改

**TC-38: 长耗时 compile_and_deploy - 成功**
1. 通过 CLI 修改 `build.gradle` 触发降级 + 长耗时
2. 调用 `compile_and_deploy`，传入 `projectDir`
3. 验证异步返回 `isFinal=false` 和 `jobId`
4. 使用 `get_compile_status` 轮询至终态，验证最终 `status` 为 `OK`，应用被成功部署
5. 还原修改

**TC-39: 长耗时 compile_and_deploy - 失败**
1. 通过 CLI 修改 `build.gradle`（触发降级）+ 引入编译错误
2. 调用 `compile_and_deploy`，传入 `projectDir`
3. 验证异步返回 `isFinal=false` 和 `jobId`
4. 使用 `get_compile_status` 轮询至终态，验证最终 `status` 为 `ERROR`，包含可定位的错误信息
5. 还原所有修改

---

## 十、无设备场景 - 全量工具验证

> 本章在执行前，必须先通过 CLI 关闭所有 AVD 并拔掉真机，确保 `adb devices` 返回空列表。
> 执行完毕后再重新启动 AVD 恢复环境。

### 前置操作

执行 `adb emu kill`（关闭所有模拟器），确认 `adb devices` 输出为空。

### 不需要设备的工具（应正常返回）

**TC-40: 无设备 - list_projects**
调用 `list_projects`，验证返回 `status` 为 `OK`，正常返回项目列表。无设备不影响此工具。

**TC-41: 无设备 - device_list**
调用 `device_list`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 中设备列表为空数组。

**TC-42: 无设备 - compile_only**
修改一个源码文件，调用 `compile_only`，传入 `projectDir`，验证返回 `status` 为 `OK`。仅编译不需要设备。

**TC-43: 无设备 - get_compile_status**
使用 TC-42 中如果有返回的 `jobId` 调用 `get_compile_status`，验证正常返回编译状态。如果 TC-42 同步完成无 `jobId`，则传入一个假 `jobId`，验证返回合理的错误。

### 需要设备的工具（应返回 MCP_NO_DEVICE）

**TC-44: 无设备 - compile_and_deploy**
调用 `compile_and_deploy`，传入有效 `projectDir`，验证工具能正常执行编译阶段。由于无设备，部署阶段会失败，最终返回 `status` 为 `ERROR`，但 `errorCode` 不一定是 `MCP_NO_DEVICE`（可能是 `MCP_INTERNAL_ERROR`，因为编译后部署失败）。关键验证点：工具不应在编译前就因无设备而拒绝执行。

**TC-45: 无设备 - force_gradle_compile**
调用 `force_gradle_compile`，传入有效 `projectDir`，验证工具能正常执行 Gradle 编译。`force_gradle_compile` 是纯编译操作，不依赖设备，应正常返回编译结果（`status` 为 `OK` 或编译失败的 `ERROR`），不应返回 `MCP_NO_DEVICE`。

**TC-46: 无设备 - clean_reinstall_apk**
调用 `clean_reinstall_apk`，传入有效 `projectDir`，验证工具能正常执行编译阶段。由于无设备，重装阶段会失败，最终返回 `status` 为 `ERROR`，但 `errorCode` 不一定是 `MCP_NO_DEVICE`（可能是 `MCP_INTERNAL_ERROR`）。关键验证点：工具不应在编译前就因无设备而拒绝执行。

**TC-47: 无设备 - restart_app**
调用 `restart_app`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-48: 无设备 - screenshot**
调用 `screenshot`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-49: 无设备 - start_record**
调用 `start_record`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-50: 无设备 - layout_dump**
调用 `layout_dump`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-51: 无设备 - activity_stack**
调用 `activity_stack`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-52: 无设备 - start_app**
调用 `start_app`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-53: 无设备 - start_activity**
调用 `start_activity`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-54: 无设备 - tap**
调用 `tap`，传入有效 `projectDir`、`x=100`、`y=100`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-54a: 无设备 - tap 百分比模式**
调用 `tap`，传入有效 `projectDir`、`xPercent=50`、`yPercent=50`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**TC-54b: 无设备 - tap 元素模式**
调用 `tap`，传入有效 `projectDir`、`text="Login"`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

### 后置操作

重新启动 AVD（`emulator -avd <avd_name> &`），等待 `adb devices` 显示设备 online 后，继续后续用例。

---

## 十一、设备选择策略

**TC-55: 不传 serial - 自动使用 selected device**
调用任何需要设备的工具（如 `screenshot`），不传 `serial`，验证返回 `status` 为 `OK`，工具正常执行。注意：`message` 中不包含设备选择说明文案，设备选择细节不暴露在 MCP 响应中。

**TC-56: 多设备环境 - 指定 serial**
在连接了多台设备的环境下，通过 `device_list` 获取非 selected 的设备 serial，调用 `screenshot` 并指定该 serial，验证截图来自指定设备（可通过截图内容区分）。

---

## 十二、错误处理与边界

**TC-57: projectDir 缺失**
对任何需要 `projectDir` 的工具（如 `compile_and_deploy`），不传 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`，`message` 中包含 "projectDir is required" 或类似提示。

**TC-58: projectDir 非绝对路径**
调用 `compile_and_deploy`，传入 `projectDir="relative/path"`（不以 `/` 开头），验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`（inputSchema 要求 `pattern: "^/.+"`）。

**TC-59: 调用不存在的工具**
通过 JSON-RPC 发送 `tools/call`，`name` 设为 `"nonexistent_tool"`，验证返回 `errorCode` 为 `MCP_TOOL_NOT_FOUND`。

**TC-60: 返回结构一致性验证**
对所有 17 个工具分别调用一次（包括正常和异常场景），验证每次返回值都严格包含 `status`、`message`、`data`（对象）、`artifacts`（数组）、四个字段；失败时额外多一个 `errorCode` 字段。

---

## 十三、组合场景（端到端工作流）

**TC-61: 完整开发迭代流程**
1. 调用 `list_projects` 获取有效 `projectDir`
2. 调用 `device_list` 确认有设备连接
3. 调用 `start_app` 启动应用
4. 调用 `screenshot` 截取应用初始状态
5. 修改一个源码文件
6. 调用 `compile_and_deploy` 编译部署
7. 如果是异步，用 `get_compile_status` 轮询直到完成
8. 调用 `screenshot` 截取部署后状态
9. 对比前后两次截图，确认修改已生效

**TC-62: 编译失败后 Gradle 回退流程**
1. 故意在代码中引入一个编译错误
2. 调用 `compile_and_deploy`，预期编译失败
3. 验证返回 `status` 为 `ERROR`，包含编译错误信息
4. 修复代码错误
5. 调用 `force_gradle_compile` 走 Gradle 回退
6. 验证最终编译成功

**TC-63: UI 自动化操作流程**
1. 调用 `start_app` 启动应用
2. 调用 `screenshot` 获取当前界面
3. 调用 `layout_dump` 获取 UI 层级
4. 根据 layout_dump 结果找到目标按钮坐标
5. 调用 `tap` 点击该坐标
6. 调用 `screenshot` 验证点击后的界面变化
7. 调用 `activity_stack` 验证当前页面是否跳转

**TC-64: 两段式录屏验证完整流程**
1. 调用 `start_record`（仅传 `projectDir`）并获取 `sessionId`
2. 调用 `start_app` 启动应用
3. 调用 `tap` 点击目标坐标
4. 等待 2~3 秒后调用 `stop_record`（传 `projectDir`、`sessionId`）
5. 验证返回成功且 mp4 产物存在，播放确认包含启动与点击过程

---

## 附录：工具清单速查

| # | 工具名称 | 需要设备 | 主要用途 |
|---|---------|---------|---------|
| 1 | `list_projects` | 否 | 列出 IDE 已初始化项目 |
| 2 | `compile_and_deploy` | 否（编译不需要，部署需要） | Jugg 增量编译 + 部署 |
| 3 | `compile_only` | 否 | 仅 Jugg 增量编译，不部署 |
| 4 | `clean_reinstall_apk` | 否（编译不需要，重装需要） | 卸载重装 APK（清数据） |
| 5 | `force_gradle_compile` | 否 | Gradle 回退编译 |
| 6 | `get_compile_status` | 否 | 查询异步编译任务状态 |
| 7 | `restart_app` | 是 | 重启应用 |
| 8 | `device_list` | 否 | 列出已连接设备 |
| 9 | `screenshot` | 是 | 设备截图 |
| 10 | `start_record` | 是 | 开始设备录屏并返回 `sessionId` |
| 11 | `stop_record` | 是 | 停止录屏并拉取 mp4 |
| 12 | `layout_dump` | 是 | 导出 UI 层级 XML |
| 13 | `activity_stack` | 是 | 获取当前 Activity 栈 |
| 14 | `start_app` | 是 | 启动应用默认入口 |
| 15 | `start_activity` | 是 | 按 intent 参数启动 Activity |
| 16 | `tap` | 是 | 屏幕坐标点击 |
| 17 | `request_remote_ssh_info` | 否 | 获取远端 SSH 信息 |
