---
title: 布局 dump 与 UI 证据
description: 说明 Jugg 的 layout-dump 如何通过 App 内 ViewHierarchy 服务导出页面结构，并形成 UI 检查证据。
status: active
tags:
  - concept
  - ui
  - layout-dump
---

# 布局 dump 与 UI 证据

`layout-dump` 是 Jugg MCP/CLI 里的特殊能力。它不是简单读取一张截图，也不是调用公开的 `layout-verify`。当前公开链路是：App 内 ViewHierarchy 服务导出视图树，Jugg 写出 HTML artifact，后续 `view-locate`、`view-inspect`、`tap` 复用同一套 App 内通道。

## 公开工具边界

`McpToolActionRegistry.defaultActions()` 注册了这些 UI 工具：

| 工具 | 状态 | 用途 |
|---|---|---|
| `layout-dump` | 已注册 | 导出页面结构 HTML。 |
| `view-locate` | 已注册 | 按 text/resourceId/contentDesc 查节点和 bounds。 |
| `view-inspect` | 已注册 | 在 App 侧执行 getter 链，读取 View 属性。 |
| `activity-stack` | 已注册 | 读取当前 Activity 栈。 |
| `tap` | 已注册 | 坐标、百分比或元素选择器触控。 |
| `wait-logs` | 已注册 | 等待日志 marker、crash 或 timeout。 |

`layout-verify` 和 `figma-layout-verify` 有 action 类，但没有注册到 `defaultActions()`。它们不是当前公开 MCP 工具。

## dump 链路

`LayoutDumpHelper.dump()` 是公开 `layout-dump` 的核心入口：

```text
layout-dump
  -> DeviceSelectionResolver 选择在线设备
  -> McpAppReadyGuard 等待目标 App 可观察
  -> ViewHierarchyClient.dumpLayout()
  -> App 内 ViewHierarchy server 返回 JSON 或远端文件路径
  -> LayoutDumpHelper 拉取/写入本地 JSON
  -> 按 density 把 bounds / padding 从 px 转 dp
  -> LayoutHtmlConverter 生成 HTML
  -> MCP result 返回 HTML artifact 路径
```

公开结果只返回 HTML 文件路径和 `contentBytes`。中间 JSON 会保存在本地，但代码注释写明它给内部消费者使用，不是公开 API。

## App 内 ViewHierarchy 通道

`ViewHierarchyClient` 通过 `adb forward` 连接 App 内 LocalSocket server。`dumpLayout()` 发送 action：

```text
action = "layout_dump"
params:
  rootLayout
  excludeGone
  topWindowOnly
```

App 侧可能直接返回 payload JSON，也可能返回远端文件路径。`LayoutDumpHelper` 会把远端文件 pull 到本地，再统一转 HTML。

这条链路依赖 App 内 ViewHierarchy server。socket 不可用时，当前公开流程不会自动换成 uiautomator。

## 单位

App 侧返回的布局数据包含 density。`LayoutDumpHelper` 会读取 `deviceInfo.density`，再把节点里的 `bounds` 和 `padding` 从 px 转成 dp。

```text
dp = (px / density).toInt()
```

转换会递归处理普通子节点和 `composeNodes`。`view-locate` 返回的 bounds 也按 dp 口径使用。

## 与 locate / inspect / tap 的关系

`layout-dump` 给 Agent 阅读全局结构。需要精确定位时，继续用 `view-locate`：

```text
layout-dump
  -> 看页面结构和候选节点
view-locate
  -> 返回 bounds / size / matchCount / matches
view-inspect
  -> 读取 getter 值和 density
tap
  -> 需要交互时执行触控
```

`view-locate` 多命中时会返回 `matchCount` 和 `matches`。元素模式的 `tap` 遇到多命中不会执行，需要先消歧。

`view-inspect` 可以读隐藏节点属性。隐藏节点属性可以作为状态证据，不能证明节点可点击。

## 相关页面

- [UI 检查指南](../guide/ui-inspection.md)
- [UI 自动化能力](../capabilities/tools/ui-automation.md)
- [UI 布局证据能力](../capabilities/tools/layout-verify.md)
- [MCP 工具参考](../reference/mcp-tools.md)
