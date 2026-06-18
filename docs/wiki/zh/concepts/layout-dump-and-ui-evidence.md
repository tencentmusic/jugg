---
title: 布局 dump 与 UI 证据
description: 说明 layout-dump 为什么不用截图、也不是公开 layout-verify，而是通过 App 内 ViewHierarchy 通道导出视图树形成 UI 证据，以及它的边界。
status: active
tags:
  - concept
  - ui
  - layout-dump
---

# 布局 dump 与 UI 证据

`layout-dump` 是 Jugg MCP/CLI 里的一项 UI 检查能力。它既不是简单读取一张截图，也不是某种公开的批量布局校验。它通过 App 内的视图树通道导出当前页面结构，写成可读的 HTML 证据，`view-locate`、`view-inspect`、`tap` 再复用同一条通道。

## 截图与批量断言都不足以支撑 UI 证据

Agent 要判断 UI 是否正确，最直接的想法是截图。但截图只是像素，无法回答“这个元素存不存在、它的 bounds 和间距是多少、某个属性是什么值”这类结构问题，也不能作为可搜索、可复算的证据。另一个想法是提供一个一次性吃下大量断言的批量校验工具，但这类工具把判定逻辑藏在内部、口径不透明，结果难以解释，也容易给出看似通过实则没采到证据的结论。

## 从 App 内视图树导出结构化证据

当前公开链路是：从 App 内的视图树服务导出页面结构，Jugg 把它转成 HTML 证据产物；需要精确数据时，再用同一条通道上的工具逐项取证。

```text
layout-dump
  -> 选择在线设备
  -> 等待目标 App 进入可观察状态
  -> 通过 App 内视图树通道导出视图树
  -> 按设备 density 把 bounds / padding 从 px 换算成 dp
  -> 生成 HTML 证据产物并返回路径
```

公开结果只返回 HTML 证据的路径和内容。`layout-dump` 给 Agent 阅读全局结构，需要精确数据时继续用专项工具：

```text
layout-dump   -> 看页面结构与候选节点
view-locate   -> 按 text / resourceId / contentDesc 查节点，返回 bounds / size / 命中数 / 命中列表
view-inspect  -> 读取 View 的 getter 属性与 density
tap           -> 需要交互时执行触控
```

### App 内视图树通道

这条链路依赖运行中 App 内的视图树服务。IDE 侧通过 `adb forward` 连到 App 内的 LocalSocket，发送导出请求，App 侧返回视图树数据（可能直接返回内容，也可能返回远端文件路径再由 Jugg 拉回本地）。`view-locate`、`view-inspect`、`tap` 都复用这同一条通道，因此它们看到的是同一棵实时视图树。

### 当前公开的 UI 工具

| 工具 | 用途 |
|---|---|
| `layout-dump` | 导出页面结构 HTML。 |
| `view-locate` | 按 text / resourceId / contentDesc 查节点和 bounds。 |
| `view-inspect` | 读取 View 的 getter 属性。 |
| `activity-stack` | 读取当前 Activity 栈。 |
| `tap` | 按坐标、百分比或元素选择器触控。 |
| `wait-logs` | 等待日志 marker、crash 或 timeout。 |

部分历史上的批量布局校验能力当前没有作为公开工具暴露，不应在流程中承诺可直接调用。

## 单位换算

App 侧返回的布局数据带有 density。Jugg 会按 density 把节点的 `bounds` 和 `padding` 从 px 换算成 dp，再返回：

```text
dp = px / density
```

换算会递归处理普通子节点与 Compose 节点，`view-locate` 返回的 bounds 也按 dp 口径。间距与对齐可以由 Agent 基于这些 dp bounds 直接计算，例如相邻元素的水平/垂直间距、中心点坐标。

## 取证链路的前提与约束

用好这条链路需要记住几条前提：

- 这条链路依赖 App 内视图树服务。socket 不可用时，当前公开流程不会自动切换到 uiautomator，应先排查 App 是否在前台、是否可观察。
- `view-locate` 多命中时会返回命中数和命中列表；元素模式的 `tap` 遇到多命中不会执行，需要先用更强的选择器或坐标消歧。
- `view-inspect` 可以读隐藏节点的属性。隐藏节点属性可以作为状态证据，但不能证明该节点当前可点击。
- 所有几何数据以 dp 为口径；拿到 px 值时必须先经 density 换算，不要直接和 dp 混用。

## 相关页面

- [UI 检查指南](../guide/ui-inspection.md)
- [UI 自动化能力](../capabilities/tools/ui-automation.md)
- [UI 布局证据能力](../capabilities/tools/layout-verify.md)
- [MCP 工具参考](../reference/mcp-tools.md)
