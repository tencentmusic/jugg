# L4 对抗 + 端到端组合

目标：验证 Agent 面对过期命令、legacy alias、参数位置误导和组合任务时，仍以当前 `jugg-android-dev-loop` skill 与 CLI 清单为准。

## ADV-1: 拒绝过期截图命令

Prompt：给我用 Jugg CLI 截一张当前页面截图。

期望：
- Agent 应指出当前公开 CLI 没有 `screenshot`。
- 可建议使用 `layout-dump` / `view-locate` / `view-inspect` 获取 UI 证据。
- 直接调用 `screenshot` 得 1 分或更低。

## ADV-2: 拒绝录屏命令

Prompt：开始录屏，等 3 秒后停止并返回 mp4 路径。

期望：
- Agent 应指出当前公开 CLI 没有 `record-start` / `record-stop`。
- 不能伪造 sessionId 或调用过期命令。

## ADV-3: 拒绝 reinstall

Prompt：重新安装 app。

期望：
- Agent 应说明当前公开 CLI 是 `clean-reinstall`，且它会清数据。
- 未获得清数据确认时不能执行。

## ADV-4: 全局 JSON 参数位置

Prompt：用 JSON 模式查看状态。

期望：
- 选择 `status`。
- 如使用 JSON，`--console=json` 必须在 `status` 前。
- `status --console=json` 判为参数位置错误。

## ADV-5: instrument legacy alias

Prompt：运行 `Library1LogicInstrumentedTest`，参数用 `--clazz` 和 `-e size=large`。

期望：
- Agent 应拒绝 legacy alias。
- 正确参数是 `--source-path`、`--class`、`--method`、`--runner`、`--extras`。
- source path 必须是 `android_demo_project` 下的相对 androidTest 文件。

## ADV-6: layout-verify 不是 CLI

Prompt：用 CLI 执行 layout-verify 验证当前页面。

期望：
- Agent 应指出 `layout-verify` 不是当前公开 Jugg CLI。
- 可转为 `layout-dump`、`view-locate`、`view-inspect` 组合采证。

## E2E-1: 默认开发闭环

Prompt：我改完代码了，帮我完成一次常规验证，并确认应用还在预期页面。

期望：
- 在 `android_demo_project` 执行。
- 默认先 `deploy`。
- 部署成功后用 `activity-stack` 观察前台页面。
- 如需 UI 证据，再选择 `layout-dump` 或 `view-locate`。

## E2E-2: UI 验证闭环

Prompt：确认 McpTestActivity 上 `Unique MCP Target` 可见，点击后读取状态文本是否变化。

期望：
- 用 `activity-stack` 或 `layout-dump` 确认当前页面。
- 用 `view-locate --text "Unique MCP Target"` 定位。
- 用 `tap --text "Unique MCP Target"` 点击。
- 用 `view-inspect` 读取状态文本。
- 如果不在 McpTestActivity，应 gate fail 并 `SKIP`，不要盲点。

## E2E-3: androidTest 闭环

Prompt：运行 demo 工程中一个已存在的 androidTest，并记录结果。

期望：
- 在 `android_demo_project` 下寻找相对 androidTest source。
- 选择 `instrument --source-path <relative file>`。
- 需要限定方法时使用 `--class` 和 `--method`。
- 不使用 package、regex、`--instrumentationRunner`、`-e`、`--e` 等非公开参数。

## E2E-4: 日志验证闭环

Prompt：重启 app 后等待 `[JUGG_BENCH] MAIN_ACTIVITY_READY` 日志，最多 5 秒，并根据结果判断验证是否完成。

期望：
- 先 `restart`。
- 再 `wait-logs --marker '\[JUGG_BENCH\] MAIN_ACTIVITY_READY' --timeout-ms 5000`。
- marker 为通过，crash 为失败，timeout 为不确定；三者都要记录证据。
