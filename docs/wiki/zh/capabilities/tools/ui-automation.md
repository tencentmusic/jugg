---
title: UI 自动化
description: 说明 Agent 如何读取页面结构、定位元素、查询 View 属性并执行基础触控。
status: active
tags:
  - capability
  - tools
  - ui
---

# UI 自动化

UI 自动化命令用于让 Agent 在设备上读取页面结构、定位元素、查询 View 属性并执行基础触控。当前公开能力围绕 App 内 ViewHierarchy 通道构建，不依赖 uiautomator 回退。

## 可完成的任务

| 用户任务 | 当前支持情况 | 命令 |
|---|---|---|
| 导出当前 UI 层级 | 支持 | `layout-dump` |
| 按 text / resource id / content-desc 定位元素 | 支持 | `view-locate` |
| 查询 View getter 属性 | 支持 | `view-inspect` |
| tap / long-press / swipe | 支持 | `tap` |
| 截图 | 当前 MCP/CLI 不公开 | `screenshot` action 未注册 |

## 推荐使用顺序

```text
activity-stack
  -> layout-dump
  -> view-locate / view-inspect
  -> tap（需要交互时）
  -> activity-stack / layout-dump / wait-logs 验证结果
```

先确认页面，再采集结构和属性；需要交互时优先使用元素选择器，重复命中时先消歧，再考虑坐标或百分比触控。

## UI 层级

```text
jugg layout-dump
jugg layout-dump --root-layout content
jugg layout-dump --include-gone
jugg layout-dump --all-windows
```

`layout-dump` 输出公开 HTML artifact，适合 Agent 和用户阅读。内部 JSON 只供工具实现复用，不作为稳定公开接口。

## 元素定位

```text
jugg view-locate --text "Submit"
jugg view-locate --resource-id btn_confirm
jugg view-locate --content-desc "Back"
```

返回 `bounds`、`position`、`size`、`matchCount` 和候选摘要；坐标单位为 dp。`matchCount > 1` 时，首个结果不能直接作为稳定断言或安全点击目标，需要补充更强 selector。

## 属性读取

```text
jugg view-inspect --text "Submit" "getText()" "isEnabled()"
jugg view-inspect --resource-id btn_confirm "getCurrentTextColor()" "getTextSize()"
```

`view-inspect` 通过 App 内反射执行只读 getter / query 表达式，并返回原始值与 `density`。它可以读取仍在 View 树中的隐藏节点属性；隐藏节点可作为状态证据，但不能证明可点击。

## 触控

```text
jugg tap --text "Login"
jugg tap --resource-id btn_submit
jugg tap --content-desc "Close"
jugg tap --x 540 --y 960
jugg tap --x-percent 50 --y-percent 80
jugg tap --action swipe --x-percent 50 --y-percent 80 --end-x-percent 50 --end-y-percent 20
```

触控前会检查 App 在线、设备可交互、目标 App 前台和 Activity 稳定性。元素模式多匹配时不会执行触控，而是返回候选摘要供调用方消歧。

## 关联能力

- [UI 布局证据](./layout-verify.md)
- [运行时与设备](./cli-runtime-device.md)
- [面向 Agent 的 MCP](./mcp.md)
