---
title: UI 布局证据
description: 说明 Agent 如何使用公开 UI 工具形成可复核的布局判断。
status: active
tags:
  - capability
  - tools
  - ui
  - layout
---

# UI 布局证据

UI 布局证据页说明 Agent 如何用当前公开的 UI 工具形成可复核的布局判断。当前公开流程是“采集实际 View 信息，再由 Agent 计算 expected / actual / diff / verdict”，不是调用未注册的批量 layout-verify 工具。

## 公开证据链

| 证据类型 | 当前支持情况 | 工具 |
|---|---|---|
| 当前页面和窗口上下文 | 支持 | `activity-stack`、`layout-dump --all-windows` |
| 元素 bounds、position、size | 支持 | `view-locate` |
| View getter 属性 | 支持 | `view-inspect` |
| 交互后页面变化 | 支持 | `tap` 后再次 `activity-stack` / `layout-dump` |
| 日志闭环 | 支持 | `wait-logs` |
| `layout-verify` / `figma-layout-verify` 直接调用 | 当前不公开 | action 类存在但未注册 |

## 如何形成布局判断

```text
设计稿 / 需求 / 预期值
  -> Agent 明确列出 expected value 来源
view-locate
  -> 获取 Android actual bounds / size / position
view-inspect
  -> 获取颜色、文字、可见性、enabled 等 getter 属性
Agent 计算
  -> expected / actual / diff / verdict
```

间距和对齐由 Agent 基于 dp bounds 计算，例如：

```text
horizontalSpacing = rightElement.left - leftElement.right
verticalSpacing   = bottomElement.top - topElement.bottom
centerX           = (left + right) / 2
centerY           = (top + bottom) / 2
```

推荐报告同时写出预期值来源、实际值来源、差值和判定，不只写“通过/失败”。

## Figma 场景

有 Figma 设计稿时，Agent 应从设计稿结构化数据中提取 expected value，再用 `view-locate` / `view-inspect` 获取 Android actual。当前不要把 `figma-layout-verify` 当成公开工具调用。

> [!NOTE]
> `figmaNode` 字段存在于部分 schema，但当前公开定位仍以 text、resourceId、contentDesc 等 selector 为主，不提供自动 IoU 匹配承诺。

## 边界

- `layout-dump` 公开 HTML，不公开内部 JSON。
- `view-locate` 的 bounds 单位已经是 dp。
- `view-inspect` 返回 getter 原始值；px 值需要结合 `density` 换算。
- `matchCount > 1` 时必须消歧，不能把第一个候选当作稳定证据。
- 截图 action 当前未注册，不能作为默认 MCP 证据来源。

## 关联能力

- [UI 自动化](./ui-automation.md)
- [面向 Agent 的 MCP](./mcp.md)
