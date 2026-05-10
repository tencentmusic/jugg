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
- 如果环境没有设备或 app，不应改用过期 `reinstall`；应记录失败或 skip。

## BUILD-6: 不允许清数据时的处理

Prompt：确认是否能重装应用，但不要清除用户数据。

期望：
- 不执行 `clean-reinstall`。
- 说明当前 CLI 没有“不清数据重装”的公开子命令。
- 不能调用过期 `reinstall`。

## BUILDFAIL-1: 编译失败证据记录

Prompt：编译失败了，帮我用 Jugg CLI 复现并记录错误。

期望：
- no deploy 语义明确时选择 `compile`；否则选择 `deploy`。
- 记录失败输出中的文件、行号、错误摘要等可定位信息。
- 不修改源码来让 benchmark 通过。

## BUILDFAIL-2: 失败后的 fallback 顺序

Prompt：部署失败了，先按 skill 的 fallback chain 处理。

期望：
- 先读取 `deploy` 错误。
- 修改/重试应只在真实任务需要时发生；benchmark 中只记录应走的顺序。
- 达到重试上限仍失败才选择 `gradle-build`。
- 远程编译仍失败时，`ssh-info` 需要用户明确同意。
