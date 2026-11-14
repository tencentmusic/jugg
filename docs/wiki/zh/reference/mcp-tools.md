---
title: MCP 工具
description: 汇总 Jugg MCP 服务信息、公开工具、返回结构、异步行为和错误码。
status: active
tags:
  - reference
  - mcp
---

# MCP 工具

本页是 Jugg MCP 工具名、输入输出和公开可用性的参考入口。需要判断工作流时先看能力页；需要确认具体工具契约时看这里。

运行时权威来源：MCP `tools/list`。

## 服务信息

| 项 | 值 |
|---|---|
| 端口范围 | `12320..12329` |
| HTTP 路径 | `/jugg-mcp` |
| 协议 | JSON-RPC `2.0` |
| 支持请求头 | `MCP-Protocol-Version`，支持 `2025-06-18`、`2025-11-25` |

## 返回结构

`tools/call` 的 `structuredContent` 统一包含以下字段：

```json
{
  "status": "OK|ERROR",
  "message": "string",
  "data": {},
  "artifacts": [],
  "errorCode": "string|null"
}
```

编译类工具可能返回 `isFinal=false` 和 `jobId`，此时需要继续调用 `get-compile-status` 轮询终态。

## 公开工具

当前注册的公开 MCP tool 共 20 个。

| Tool | 主要参数 | 用途 |
|---|---|---|
| `settings` | `action`、`key?`、文本 `value?`、布尔 `confirm?` | 全局类型化设置；受保护修改必须显式确认。 |
| `version` | 无 | 返回 Jugg 插件版本。 |
| `list-projects` | 无 | 列出当前 IDE 已初始化项目。 |
| `restart` | `projectDir`、`waitAppReadyAfterSuccess` | 重启目标 App；没有 launch/HOME Activity 时只执行 `am force-stop`。 |
| `compile` | `projectDir` | 仅编译不部署。 |
| `deploy` | `projectDir`、`alwaysRestartApp`、`waitAppReadyAfterSuccess` | 编译并部署。 |
| `clean-reinstall` | `projectDir`、`waitAppReadyAfterSuccess` | 清数据并重装 APK。 |
| `gradle-build` | `projectDir`、`waitAppReadyAfterSuccess` | 强制 Gradle 构建，并继续安装/启动。 |
| `instrument` | `projectDir`、`sourcePath`、`class`、`method`、`runner`、`extras` | 从 androidTest 源文件锚点运行测试。 |
| `get-compile-status` | `projectDir`、`jobId`、`waitTimeoutMs` | 查询异步编译任务状态。 |
| `ssh-info` | `projectDir`、`reason`、`requestedBy` | 申请远端 SSH 排障信息。 |
| `report-prepare` | `projectDir`、兼容参数 `serial` | 生成诊断包；忽略 serial 并收集全部目标设备日志。 |
| `report-upload` | `projectDir`、`reportId`、`sha256` | 上传已确认且校验未变化的诊断包。 |
| `devices` | `projectDir` | 列出设备并标记 selected。 |
| `layout-dump` | `projectDir`、`mode`、`rootLayout`、`includeGone`、`allWindows` | 导出 interaction/ui 稀疏 JSON。 |
| `view-locate` | `projectDir`、`target` | 查找 UI 元素位置。 |
| `view-inspect` | `projectDir`、`target`、`expressions` | 反射读取 View 只读属性，含 getter、Kotlin property 和 public 字段。 |
| `activity-stack` | `projectDir` | 读取 Activity 栈。 |
| `tap` | `projectDir`、坐标/百分比/元素 selector | 执行 tap、long-press 或 swipe。 |
| `status` | `projectDir`、`refreshChanges` | 查询部署状态和未编译文件摘要。 |
| `wait-logs` | `projectDir`、`marker`、`tags`、`timeoutMs` | 等待 App 日志 marker、crash 或 timeout。 |

`version`、`list-projects` 和 `settings` 不需要 `projectDir`。项目工具需要绝对项目路径。`settings` 在显式收到 `confirm=true` 前返回 `CONFIRMATION_REQUIRED` 和 Android Studio 的提示。

## 多设备行为

- `compile`、`status`、`devices` 不要求唯一设备。
- `deploy`、`clean-reinstall`、`instrument` 未传 serial 时处理全部目标设备。
- `restart` 未传 serial 时重启全部目标设备。
- UI、Activity 栈和日志等待等单设备工具发现多个目标设备时返回 `MULTIPLE_DEVICE`，不会转成 HTTP 500。
- `report-prepare` 兼容但忽略 serial，尽力收集全部目标设备的错误日志。

## 编译类异步行为

`deploy`、`gradle-build`、`instrument` 可能先返回运行中状态：

```json
{
  "data": {
    "isFinal": false,
    "jobId": "..."
  }
}
```

客户端应使用：

```json
{
  "projectDir": "/path/to/project",
  "jobId": "...",
  "waitTimeoutMs": 5000
}
```

调用 `get-compile-status`，直到 `data.status` 为 `success`、`failed`、`canceled` 或 `unknown`。终态会返回 `isCompileSuccess` 和 `isDeploySuccess`；失败时可能附带 `detail`、`detailLength`、`detailTruncated`。

`deploy` 成功但本轮没有编译源码时，消息只说明没有源码变化，不表示安装、恢复或其它部署动作未执行。最近一次含文件变化的部署详情只在当前 Runtime 会话内可用。

## UI 工具行为

| 工具 | 关键边界 |
|---|---|
| `layout-dump` | 输出 JSON artifact；mode=interaction（默认）裁剪摘要，mode=ui 保留采集树。非法 mode 返回 INVALID_PARAMS。 |
| `view-locate` | 坐标和尺寸单位为 dp；多匹配时不能把首个结果当作安全点击目标。 |
| `view-inspect` | 只允许只读表达式。显式 `foo()` 走 getter/query 白名单；无括号名字先读 public 字段，再按 `getXxx()` / `isXxx()` 解析。 |
| `tap` | 模式优先级为 coordinate > percent > element；元素多匹配时不执行。 |
| `activity-stack` | 用于确认前台 Activity 和页面稳定性。 |

ViewHierarchy 相关工具执行前会等待 App 在线。设备息屏或未解锁会返回 `DEVICE_NOT_INTERACTIVE`；目标 App 不在前台会返回 `APP_NOT_FOREGROUND`。

文件自包含 mode、units、windows、deviceInfo、truncated 和可选 warnings/rootLayout。bounds/padding 正常为整数 dp；缺少有效 density 时保留 px，由 units 明示。空值和默认 visibility=visible、alpha=1、clickable=false、enabled=true、全零 padding、空 children 省略。textColor 缺失也可能表示未采集或默认值。摘要统计实际输出节点，裁剪不设置预算 truncated。

两模式使用相同的字段含义，公开虚拟 ID 简写为 `_vid_<hash>`；旧 `_vir_id_` selector 仍可使用。interaction 先删除 invisible/gone 节点及整个子树（不会提升孩子，`--include-gone` 也不例外），省略 `padding`、`textColor`，并保留 className。ui 在请求的采集范围内保留隐藏节点和视觉字段；GONE 节点仍需 `--include-gone`。

真实节点另起一行，属性和坐标保持紧凑；`jugg_details` 伪节点不换行。children 至少包含一个真实节点时，开头 `[` 和末尾 `]}` 各另起一行。

interaction 在窗口外分支中保留首个节点，并以仅含 `jugg_details` 的伪子节点代替后代。伪节点不计入节点或 clickable 统计，折叠不设置 `truncated`。用 `--mode ui --root-layout <id>` 展开；规则与保留条件见[布局 dump 与 UI 证据](../concepts/layout-dump-and-ui-evidence.md#交互摘要与-ui-层级采用不同节点范围)。

`data.file` 始终指向完整产物，`contentBytes` 是包含节点换行的 UTF-8 文件字节数。不超过 16 KiB（16,384 字节）时，两种模式都会在 `structuredContent.data.layout` 中附带完整 JSON 对象；超出时省略该字段，不返回截取的部分树。文本 content 只包含摘要，整棵树不重复发送。CLI 的 plain/json 输出保留 Unicode。16 KiB 是文件阈值，不是响应总大小或 Token 上限；客户端需将 structuredContent 提供给 Agent 才能直接使用内联对象。

局部 UI 采集失败时，`layout-dump` 保留有效 JSON，并在摘要标记 `incomplete hierarchy`；结果可选 `data.warnings` 和 JSON 节点诊断给出原因。定位、查询和元素触控命中有效节点时仍返回结果并携带诊断；未命中且当前窗口采集不完整时返回 `UI_HIERARCHY_INCOMPLETE`。预算截断仍单独使用 `truncated`。恢复步骤见 [UI 自动化](../capabilities/tools/ui-automation.md#compose-内容缺失时)。

## 常见错误码

| 错误码 | 含义 |
|---|---|
| `INVALID_JSON_RPC` | JSON-RPC 格式错误。 |
| `METHOD_NOT_SUPPORTED` | 不支持的方法。 |
| `TOOL_NOT_FOUND` | 工具未注册。 |
| `INVALID_PARAMS` | 参数错误。 |
| `INVALID_REGEX` | 日志 marker 正则非法。 |
| `PROJECT_NOT_INITIALIZED` | 项目未完成 Jugg 初始化。错误信息会给出请求路径和当前已初始化项目。 |
| `NO_DEPLOY_BASELINE` | 缺少部署或 full build 基线。 |
| `NO_DEVICE` | 无可用设备。 |
| `MULTIPLE_DEVICE` | 单设备操作发现多个目标设备，需要显式指定 serial。 |
| `DEVICE_NOT_INTERACTIVE` | 设备息屏或非交互态。 |
| `APP_NOT_FOREGROUND` | 目标 App 不在前台。 |
| `UI_HIERARCHY_INCOMPLETE` | 当前窗口的 UI 层级采集不完整，无法断言目标元素不存在。 |
| `INTERNAL_ERROR` | 内部错误。 |

## 未公开 Action

代码中存在但未注册的 action 不能被外部 MCP 客户端调用，包括 screenshot、record、start activity、start app、emulator 和 layout verify 相关 action。判断工具是否公开时，以 `tools/list` 和本页公开工具表为准。

## 相关页面

- [CLI 命令](./cli-commands.md)
- [MCP 使用指南](../guide/mcp.md)
- [UI 检查](../guide/ui-inspection.md)
