# L2 SSH / 连通性 / 设备

目标：验证 Agent 能在正确 Android projectDir 下完成基础 CLI 探测，并区分 no MCP、no device 与 SSH 授权类结果。

## CONNECT-1: 确认 CLI 和插件版本

Prompt：确认当前 Jugg CLI 与 IDE 插件版本，并记录结构化证据。

期望：
- 在 `android_demo_project` 或其子目录执行。
- 选择 `version`。
- 如需 JSON 证据，使用全局 `--console=json` 且放在 `version` 前。
- 报告 CLI version 与 plugin version；MCP 端口不可用时记 `SKIP: no MCP port`。

## CONNECT-2: 查看当前状态

Prompt：查看当前 Jugg 状态，判断是否有设备、是否需要 fallback、最近 full build 基线是否启用 androidTest。

期望：
- 选择 `status`。
- 记录 `hasDevice`、`needFallback`、`enabledAndroidTest`、`stateMessage` 等可见字段。
- 不把 status 失败误判为设备失败；先区分 MCP 端口是否可用。

## CONNECT-3: 列出设备

Prompt：列出当前可用设备，并说明是否存在已选设备。

期望：
- 选择 `devices`。
- 有设备时记录设备列表与 selected 标记。
- 无设备时仍应接受 `devices` 正常返回空列表，不应直接失败。

## CONNECT-4: 在错误目录下执行

Prompt：从仓库根目录执行一次需要 projectDir 的命令，验证 Agent 是否能发现应该切到 Android 工程。

期望：
- 初始目录是仓库根目录时，不把仓库根目录当 Android projectDir。
- Agent 应切换到 `android_demo_project` 后再执行 `status` 或 `devices`。
- 如果坚持在仓库根目录执行并把失败当成 CLI bug，得分不超过 2。

## SSH-1: 请求 SSH 信息

Prompt：申请远程排障 SSH 信息，理由是 benchmark connectivity check。

期望：
- 选择 `ssh-info`。
- 传入 `--reason`，不要省略理由。
- 用户同意、拒绝、远程编译未启用都可以成为有效结果；必须记录实际输出和授权结果。

## SSH-2: SSH 缺少 reason 的负向验证

Prompt：验证 `ssh-info` 缺少理由时不会被 Agent 当成正常请求。

期望：
- Agent 应指出 `ssh-info` 需要 `--reason`。
- 若实际执行缺参命令，应把非 0 退出或错误输出判为预期失败。
