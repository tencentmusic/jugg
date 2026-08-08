---
title: UI 检查
description: 介绍如何使用 Jugg CLI/MCP 导出 UI 层级、定位元素、读取 View 属性和执行触控。
status: active
tags:
  - guide
  - ui
  - cli
---

# UI 检查

Jugg 提供一组面向 Agent 和脚本的 UI 工具，用于导出当前 App 的 View 层级、定位元素、读取 View 属性，以及执行点击、长按、滑动等操作。

这些工具走 App 进程内的 ViewHierarchy 通道，不是 uiautomator dump。公开产物优先是裁剪后的 HTML，方便 Agent 阅读和引用。

## 前置条件

使用前请确认：

1. Android Studio 已打开目标工程，并且 Jugg 已初始化。
2. 设备已连接，目标 App 已安装。
3. App 当前处于可交互页面。
4. 如果 ViewHierarchy socket 不可用，先尝试 `jugg restart`，必要时执行一次 `jugg deploy` 或 `jugg gradle-build`。

## 推荐流程

```text
layout-dump
  -> view-locate / view-inspect
  -> tap
  -> wait-logs 或再次 layout-dump 验证
```

不要在没有证据的情况下直接猜坐标。优先使用元素选择器，其次才是坐标或百分比。

## 导出布局

```bash
jugg layout-dump
jugg layout-dump --root-layout content
jugg layout-dump --include-gone
jugg layout-dump --all-windows
```

常用参数：

| 参数 | 用途 |
|---|---|
| `--root-layout` | 只导出指定节点子树 |
| `--include-gone` | 包含 GONE 节点 |
| `--all-windows` | 导出所有窗口，而不是只看 top window |

输出是 HTML artifact。内部 JSON 仅供 `view-locate`、`view-inspect` 和其它工具实现使用。

## 定位元素

```bash
jugg view-locate --text 登录
jugg view-locate --resource-id login_button
jugg view-locate --content-desc 返回
```

`view-locate` 会返回元素 bounds、中心点、尺寸、className 和匹配数量。若 `matchCount > 1`，不要直接点击第一个结果，应增加选择条件或先查看布局。

## 读取属性

```bash
jugg view-inspect --resource-id title getText() getVisibility()
jugg view-inspect --text 登录 --class-name TextView getText() isEnabled()
```

`view-inspect` 用于读取 getter / query 方法结果，例如：

- `getText()`
- `getVisibility()`
- `isEnabled()`
- `getContentDescription()`
- `getCurrentTextColor()`

它适合确认文案、可见性、颜色、选中状态等信息。坐标计算仍应使用 `view-locate`。

## 触控

元素模式：

```bash
jugg tap --text 登录
jugg tap --resource-id login_button
jugg tap --content-desc 返回
```

坐标模式：

```bash
jugg tap --x 120 --y 360
jugg tap --action long-press --x 120 --y 360 --duration 800
jugg tap --action swipe --x 500 --y 1200 --end-x 500 --end-y 300
```

百分比模式：

```bash
jugg tap --x-percent 50 --y-percent 90
```

`swipe` 只支持坐标或百分比模式，不支持元素模式。元素模式如果命中多个候选，工具会返回错误并列出摘要，避免误点。

## Agent 使用建议

- 先 `layout-dump`，再定位或点击。
- 对文本重复的按钮，优先加 `resource-id` 或 `class-name`。
- 点击后用 `layout-dump`、`activity-stack` 或 `wait-logs` 验证结果。
- 不要把隐藏节点当成点击目标；隐藏节点可用于属性检查，但不适合触控。
- 所有 bounds / padding 坐标以 dp 表示，截图像素以 px 表示，两者需要按 density 换算。

## 常见问题

| 现象 | 处理方式 |
|---|---|
| socket 不可连接 | 先 `restart`，仍失败则 `gradle-build`、`deploy`、`restart` 后重试 |
| 元素找不到 | 重新 `layout-dump`，确认页面和窗口是否正确 |
| 多个元素命中 | 增加 `resource-id`、`content-desc` 或 `class-name` 过滤 |
| 点击后无反应 | 确认 top Activity 稳定、元素可见且 enabled |
| bounds 和截图对不上 | 注意工具输出单位是 dp，截图通常是 px |

## 相关页面

- [布局 dump 与 UI 证据](../concepts/layout-dump-and-ui-evidence.md)
- [UI 自动化](../capabilities/tools/ui-automation.md)
- [UI 布局证据](../capabilities/tools/layout-verify.md)
- [CLI](./cli.md)
- [UI 工具问题排查](../troubleshooting/ui-tools.md)
