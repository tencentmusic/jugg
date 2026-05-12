# L4 参数位置 + 端到端组合

目标：验证 Agent 面对全局参数位置和组合任务时，仍以当前 `jugg-android-dev-loop` skill 与 CLI 清单为准。本文件不覆盖用户错误参数澄清能力。

## PARAM-1: 全局 JSON 参数位置

Prompt：用 JSON 模式查看状态。

期望：
- 选择 `status`。
- 如使用 JSON，`--console=json` 必须在 `status` 前。
- `status --console=json` 判为参数位置错误。

## E2E-1: 默认开发闭环

Prompt：我改完代码了，帮我完成一次常规验证，并确认应用回到默认首页，页面上能看到 `MCP Test Page` 入口。

期望：
- 在 `android_demo_project` 执行。
- 默认先 `deploy`。
- 部署成功后用 `activity-stack` 观察前台 Activity。
- 用 `layout-dump` 或 `view-locate --text "MCP Test Page"` 确认默认首页入口存在。
- 如果 deploy 成功但页面不是默认首页，应记录实际 Activity 和页面证据，不得把模糊的“预期页面”作为判据。

## E2E-2: UI 验证闭环

Prompt：确认 McpTestActivity 上 `Unique MCP Target` 可见，点击后读取状态文本是否变化。状态文本 selector 不直接提供，请先通过布局导出或元素定位找到稳定 selector。

期望：
- 先执行 `jugg restart && sleep 2 && jugg tap --text "MCP Test Page"` 进入 McpTestActivity。
- 用 `activity-stack` 或 `layout-dump` 确认当前页面是 McpTestActivity。
- 用 `view-locate --text "Unique MCP Target"` 定位目标按钮。
- 用 `layout-dump --include-gone`、`view-locate` 或等价 CLI 发现状态文本控件的稳定 selector。
- 不允许臆造 resourceId；使用未验证 selector 读取失败，最高 3 分。
- 点击前读取状态文本。
- 用 `tap --text "Unique MCP Target"` 点击目标按钮。
- 点击后再次读取状态文本。
- 结论必须比较点击前后文本变化。

## E2E-3: androidTest 闭环

Prompt：运行 demo 工程中一个已存在的 androidTest，并记录结果。

期望：
- 在 `android_demo_project` 下寻找相对 androidTest source。
- 选择 `instrument --source-path <relative file>`。
- 需要限定方法时使用 `--class` 和 `--method`。
- 只使用当前公开的 `instrument` 参数。

## E2E-4: 日志验证闭环

Prompt：重启 app 后等待 `[JUGG_BENCH] MAIN_ACTIVITY_READY` 日志，最多 5 秒，并根据结果判断验证是否完成。

期望：
- 先 `restart`。
- 再 `wait-logs --marker '\[JUGG_BENCH\] MAIN_ACTIVITY_READY' --timeout-ms 5000`。
- marker 为通过，crash 为失败，timeout 为不确定；三者都要记录证据。
