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
| 查询 View 只读属性 | 支持 | `view-inspect` |
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
jugg layout-dump --mode interaction
jugg layout-dump --mode ui
jugg layout-dump --root-layout content
jugg layout-dump --mode ui --include-gone
jugg layout-dump --all-windows
```

`layout-dump` 输出公开稀疏 JSON artifact。`interaction`（默认）裁剪页面摘要，用于发现交互候选；`ui` 保留已采集的节点和父子关系，用于布局验证、状态排查及辅助还原。两模式使用相同的字段含义，公开虚拟 ID 简写为 `_vid_<hash>`；旧 `_vir_id_` selector 仍可使用。interaction 先删除 invisible/gone 节点及整个子树（不会提升孩子，`--include-gone` 也不例外），省略 `padding`、`textColor`，并保留 className。ui 在请求的采集范围内保留隐藏节点和视觉字段；GONE 节点仍需 `--include-gone`。

交互摘要会删除无语义节点并提升孩子，不能证明元素不存在或原始层级，也不保证每个节点可点击。ui 仍受窗口、子树、GONE 筛选及预算限制；完整视觉还原需要额外图片、字体和绘制信息。

## Compose 内容缺失时

局部内容采集失败时，`layout-dump` 仍保留有效 JSON，摘要会标记 `incomplete hierarchy`，结果中的 `warnings` 和 JSON 节点诊断说明原因。此时没有出现的文本不能直接判定为页面不存在该元素。

`view-locate`、`view-inspect` 和元素模式 `tap` 命中有效节点时仍返回结果并携带采集诊断；未命中且当前窗口采集不完整时返回 `UI_HIERARCHY_INCOMPLETE`。`truncated` 单独表示节点或结果预算截断。

升级插件后，若页面树仍停留在 Compose 宿主而没有内容，先使用 Jugg 的完整 Gradle 构建并安装更新后的 APK，再重新采集。仅重启旧 APK 不能保证更新 App 内运行时。若仍有诊断，保留 JSON 和具体原因，检查报告的 Compose runtime/tooling 兼容问题。

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
jugg view-inspect --resource-id bubble_container "layoutParams.leftMargin" "getLayoutParams().getMarginStart()"
```

`view-inspect` 通过 App 内反射执行只读表达式，并返回原始值与 `density`。表达式可以是 getter、Kotlin property 或 public 字段；不带括号的名字会先读字段，再按 `getXxx()` / `isXxx()` 解析。它可以读取仍在 View 树中的隐藏节点属性；隐藏节点可作为状态证据，但不能证明可点击。

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

## 相关页面

- [UI 检查指南](../../guide/ui-inspection.md)
- [布局 dump 与 UI 证据](../../concepts/layout-dump-and-ui-evidence.md)
- [UI 布局证据](./layout-verify.md)
- [运行时与设备](./cli-runtime-device.md)
- [面向 Agent 的 MCP](./mcp.md)
