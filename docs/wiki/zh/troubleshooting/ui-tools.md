---
title: UI 工具问题排查
description: 排查 Jugg MCP UI 自动化、截图、录屏、view-inspect、layout-dump 和 layout-verify 工具的明确错误。
status: active
tags:
  - troubleshooting
  - ui-tools
---

# UI 工具问题排查

UI 工具依赖在线设备、前台 App、ViewHierarchy server 和正确的 selector。先按错误文案判断是设备、App 状态、参数，还是元素匹配问题。

## 设备和 App 状态

| 报错 | 第一判断 | 建议处理 |
|---|---|---|
| `No connected device is available.` | 没有可用在线设备 | 连接设备或启动模拟器 |
| `app is not ready` | 工具执行前 App 没准备好 | 先启动或重启 App |
| `app is not ready before action after ... checks` | 动作前等待 App 超时 | 确认 App 在前台且没有卡死 |
| `finished but app is still not ready after ...ms` | 动作后 App 未恢复可用状态 | 查看 logcat 是否 crash 或 ANR |
| `device screen is off or not interactive` | 屏幕关闭或不可交互 | 点亮并解锁设备 |
| `target app is not in foreground` | 目标 App 不在前台 | 启动目标 App 后重试 |

## tap / long-press / swipe

常见错误：

```text
tap failed. Reason: Unsupported action: ...
tap failed. Reason: swipe action does not support element mode.
tap failed. Reason: No matching UI element found.
tap failed. Reason: <n> elements matched.
tap failed. Reason: ViewHierarchy server error: ...
tap failed. Reason: No valid tap mode detected.
tap failed. Reason: swipe requires both start and end coordinates in the same mode.
tap failed. Reason: Unable to determine screen size from device.
tap failed. Reason: Unable to resolve package name for ViewHierarchy server.
```

处理方式：

- `Unsupported action`：只使用 `tap`、`long-press` 或 `swipe`。
- `swipe action does not support element mode`：滑动使用坐标或百分比，不使用元素 selector。
- `No matching UI element found`：放宽或修正 `text`、`resourceId`、`contentDesc`。
- `<n> elements matched`：增加 selector 条件，让目标唯一。
- 屏幕尺寸或 packageName 解析失败时，先确认设备在线和运行配置可解析包名。

## view-inspect / ui-find

常见错误：

```text
view-inspect failed. Reason: 'target' is required and must be an object.
view-inspect failed. Reason: target must have at least one selector ...
view-inspect failed. Reason: 'expressions' is required and must be a non-empty array.
view-inspect failed. Reason: 'expressions' must contain string values.
view_inspect failed. Reason: expressions count ... exceeds max ...
Element not found
```

处理方式：

1. `target` 至少提供一个 selector。
2. `expressions` 必须是非空字符串数组。
3. `Element not found` 时先用更宽的 selector 定位元素，再收窄。

## layout-dump / layout-verify

常见错误：

```text
layout-verify failed: checks is required and must be non-empty
layout-verify failed: every check requires type
layout-verify failed: type=property check requires property
layout-verify failed: relation check requires valid target2 selector
layout-verify failed: dumpFile not found: ...
layout-verify failed: No connected device is available.
failed to fetch layout dump from ViewHierarchy server
failed to resolve package name for ViewHierarchy server
```

处理方式：

- 没有 `checks` 或 `type` 时，按工具 schema 补齐。
- property 检查必须提供 `property`。
- relation 检查必须提供可解析的 `target2`。
- `dumpFile not found` 时确认 dump 文件路径存在。
- ViewHierarchy server 失败时，先确认 App 在前台且设备可交互。

## 截图和录屏

常见错误：

```text
screenshot failed. Reason: No connected device is available.
failed to prepare artifact directory
failed to pull screenshot file
active session already exists on serial=...
sessionId not found: ...
session serial mismatch. expected=..., actual=...
failed to pull record file after retries
```

处理方式：

1. 先确认在线设备。
2. 录屏开始前如果已有 active session，先 stop 当前 session。
3. 停止录屏时使用同一设备和正确的 `sessionId`。

## 相关页面

- [UI 检查指南](../guide/ui-inspection.md)
- [UI 自动化](../capabilities/tools/ui-automation.md)
- [UI 布局证据](../capabilities/tools/layout-verify.md)
