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

这些工具走 App 进程内的 ViewHierarchy 通道，不是 uiautomator dump。公开产物为稀疏 JSON，可按用途选择交互摘要或保留已采集的 UI 层级。

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
jugg layout-dump --mode interaction
jugg layout-dump --mode ui
jugg layout-dump --root-layout content
jugg layout-dump --mode ui --include-gone
jugg layout-dump --all-windows
```

常用参数：

| 参数 | 用途 |
|---|---|
| `--mode` | interaction（默认）用于浏览交互候选；ui 用于布局验证、状态排查和辅助还原 |
| `--root-layout` | 只导出指定节点子树 |
| `--include-gone` | ui 模式包含 GONE 节点；interaction 仍删除隐藏子树 |
| `--all-windows` | 导出所有窗口，而不是只看 top window |

输出为 JSON artifact，文件路径见 `data.file`。`interaction` 在未命中窗口外保留规则时，删除无真实 ID、文本、描述、点击行为或诊断的非根节点，并提升其子节点；`ui` 保留已采集的父子关系和装饰节点。两模式使用相同的字段含义，公开虚拟 ID 简写为 `_vid_<hash>`；旧 `_vir_id_` selector 仍可使用。interaction 先删除 invisible/gone 节点及整个子树（不会提升孩子，`--include-gone` 也不例外），省略 `padding`、`textColor`，并保留 className。ui 在请求的采集范围内保留隐藏节点和视觉字段；GONE 节点仍需 `--include-gone`。

interaction 用 `jugg_details` 伪子节点提示窗口外后代已省略；它不是可点击控件。需要展开时使用 `jugg layout-dump --mode ui --root-layout <id>`。子树请求不做窗口外折叠，部分可见或含窗口内后代、未知几何后代、诊断后代的分支也保留展开。规则与边界见[布局 dump 与 UI 证据](../concepts/layout-dump-and-ui-evidence.md#交互摘要与-ui-层级采用不同节点范围)。

模式不会自动改变窗口、子树、GONE 筛选或采集预算。布局验证使用 `ui`；辅助还原还需要截图、图片资源和源码等证据。

小结果会同时返回完整的 `data.layout` 对象，直接使用即可，无需再读同一文件。大结果保留完整文件，节点之间换行，属性和坐标数组保持紧凑。已知目标时优先使用 `view-locate` 或 `--root-layout`；未知目标时先搜索文件中的文本或 ID，再读取相关行。内联大小条件见 [MCP 工具参考](../reference/mcp-tools.md)。

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
jugg view-inspect --resource-id bubble_container layoutParams.leftMargin getLayoutParams().getMarginStart()
```

`view-inspect` 用于读取只读属性，例如：

- `getText()`
- `getVisibility()`
- `isEnabled()`
- `getContentDescription()`
- `getCurrentTextColor()`
- `layoutParams.leftMargin`

不带括号的名字会先读 public 字段，再按 `getXxx()` / `isXxx()` 解析。它适合确认文案、可见性、颜色、边距、选中状态等信息。坐标计算仍应使用 `view-locate`。

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

- 不熟悉页面时先用 `layout-dump --mode interaction`；已有 selector 时直接定位或元素点击。
- 验证布局、隐藏状态或辅助还原时使用 `--mode ui`。interaction 的未出现节点和提升后的层级不能证明不存在或真实父子关系。
- 对文本重复的按钮，优先加 `resource-id` 或 `class-name`。
- 点击后用 `layout-dump`、`activity-stack` 或 `wait-logs` 验证结果。
- 不要把隐藏节点当成点击目标；隐藏节点可用于属性检查，但不适合触控。
- 正常采集的 bounds / padding 以 dp 表示，文件 `units` 标明单位；截图像素以 px 表示，两者需要按 density 换算。

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
- [Agent 或 CLI 执行失败](../troubleshooting/agent-command-failed.md)
