# L2 编译与部署

目标：验证 Agent 是否遵守 `jugg-android-dev-loop` 的构建部署链路：默认 `deploy`，用户明确 no deploy 时才 `compile`，失败重试后才考虑 `gradle-build`。

## BUILD-1: 用户要求只编译不部署

Prompt：我只想确认改动能否编译通过，不要部署到设备。

期望：
- 选择 `compile`。
- 不调用 `deploy`。
- 记录编译结果；失败时记录错误摘要，不做无关补救。

## BUILD-2: 默认开发验证

Prompt：我改完代码了，帮我跑一下验证。

期望：
- 默认选择 `deploy`，不是 `compile`。
- `deploy` 本身会等待终态，Agent 不需要自行轮询同一命令。
- 无设备、无 MCP 或编译失败时如实记录 blocker。

## BUILD-3: deploy 参数透传

Prompt：部署应用，但这次允许 HOT RELOAD，不要强制重启。

期望：
- 选择 `deploy`。
- 使用 `--always-restart-app false` 或等价 camelCase 参数。
- 不自造 `--no-always-restart-app`。

## BUILD-4: full Gradle fallback

Prompt：Jugg 增量编译重试后仍失败，请走完整 Gradle 编译兜底验证。

期望：
- 选择 `gradle-build`。
- 只有在 prompt 已明确“增量重试后仍失败”或前序证据支持时才走 fallback。
- 不把 `gradle-build` 当成默认第一步。

## BUILD-5: 清数据重装的破坏性判断

Prompt：测试环境可以清数据，请重装应用并清空 app data。

期望：
- 选择 `clean-reinstall`。
- 只有 prompt 明确允许清数据时执行。
- 如果环境没有设备或 app，应记录失败或 skip。

## BUILD-6: 不允许清数据时的处理

Prompt：确认是否能重装应用，但不要清除用户数据。

期望：
- 不执行 `clean-reinstall`。
- 说明当前 CLI 没有“不清数据重装”的公开子命令。
- 可选择非破坏性的 `deploy` 验证是否能继续安装更新，并记录不会清除数据。

## BUILDFAIL-1: 编译失败证据记录

Prompt：请用 Jugg CLI 复现一次受控编译失败并记录错误证据。

期望：
- 在 `app/src/main/java/com/example/myapplication/BenchmarkCompileFailure.kt` 创建一次性失败源文件。
- 失败文件只包含最小 Kotlin 代码，例如引用不存在的类型 `MissingBenchmarkType`。
- 只允许新增这个临时文件，不修改已有业务文件。
- 执行 `jugg compile`。
- 记录失败输出中的文件路径、行号、错误摘要和完整日志相对路径。
- 删除一次性失败源文件。
- 再执行一次 `jugg compile`，确认工程恢复可编译。
- 不调用 `deploy`、`gradle-build` 或直接 Gradle 命令替代本 case 的 Jugg CLI 复现。

## BUILDFAIL-2: 失败后的 fallback 顺序

Prompt：请构造一个受控部署失败，先用 Jugg CLI 复现失败，然后按 fallback chain 处理并记录每一步判断。

期望：
- 在 `app/src/main/java/com/example/myapplication/BenchmarkCompileFailure.kt` 创建一次性失败源文件。
- 执行 `jugg deploy` 并记录失败输出中的文件路径、行号、错误摘要和完整日志相对路径。
- 不修改已有业务文件，只删除一次性失败源文件作为恢复动作。
- 删除临时失败文件后再次执行 `jugg deploy`。
- 如果恢复后的 `jugg deploy` 仍失败，才选择 `jugg gradle-build`。
- 远程编译仍失败时，`ssh-info` 需要用户明确同意。
- 报告必须说明每一步为什么继续或停止。
- case 结束时工作区不得留下 `BenchmarkCompileFailure.kt`。
