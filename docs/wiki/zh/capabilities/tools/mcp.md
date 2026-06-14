# 面向 Agent 的 MCP

Jugg MCP 是 IDE 插件暴露给 Agent 的本地 JSON-RPC 接口。Agent 可以通过它列出项目、触发编译部署、查询状态、读取 UI 层级、定位 View、执行触控并等待日志结果。

## 输入输出边界

| 用户任务 | 当前支持情况 | 输入输出边界 |
|---|---|---|
| 发现插件与项目 | 支持 | `version`、`list-projects` 不需要 `projectDir` |
| 触发编译 / 部署 / 重装 / 重启 | 支持 | 需要 `projectDir`；长任务可能返回 `jobId`，再用 `get-compile-status` 收口 |
| 运行 androidTest | 支持 | `instrument` 以 `sourcePath` 锚定 androidTest 源文件和目标 test APK |
| 查看设备、Activity、状态、日志 | 支持 | `devices`、`activity-stack`、`status`、`wait-logs` |
| UI 检查和交互 | 支持 | `layout-dump`、`view-locate`、`view-inspect`、`tap` |
| Figma 或批量 layout verify action | 当前不公开 | action 类存在不等于 MCP 工具已注册；未进入 `tools/list` 的工具不能调用 |

## 协议与返回

MCP 服务监听本机端口范围 `12320..12329`，路径为 `/jugg-mcp`，协议为 JSON-RPC `2.0`。业务结果统一放在 `structuredContent`：

```json
{
  "status": "OK|ERROR",
  "message": "string",
  "data": {},
  "artifacts": [],
  "errorCode": "string|null"
}
```

协议错误和业务失败要分开判断。HTTP/JSON-RPC 成功只说明请求被处理；工具是否成功要继续读取 `structuredContent.status` 和业务字段。

## 工具如何执行

```text
MCP request
  -> schema 校验和 projectDir 初始化校验
  -> 查找已注册 tool action
  -> 执行编译、部署、设备、ViewHierarchy 或日志能力
  -> 返回 structuredContent
```

除 `version`、`list-projects` 外，公开工具都需要项目已经由 IDE 里的 Jugg 初始化。运行态工具会在执行前等待 App 在线；UI 工具还会检查设备是否可交互、目标 App 是否在前台。

## 异步编译模型

`deploy`、`gradle-build`、`instrument` 等长任务可能返回 `data.status=running` 和 `jobId`。客户端应调用：

```text
get-compile-status(projectDir, jobId, waitTimeoutMs)
```

终态会返回编译和部署维度的字段，例如 `isCompileSuccess`、`isDeploySuccess`、`detail` 和日志路径。CLI 已封装这一轮询；直接使用 MCP 时需要客户端自己收口。

## 关联能力

- [Jugg CLI](./cli.md)
- [UI 自动化](./ui-automation.md)
- [UI 布局证据](./layout-verify.md)
- [MCP 工具参考](../../reference/mcp-tools.md)
