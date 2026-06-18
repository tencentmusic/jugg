---
title: 多设备选择
description: 说明在 Android Studio 中选择多台设备运行 Jugg 时，编译、部署、Debug 和失败结果如何处理。
status: active
tags:
  - guide
  - device
  - multi-device
---

# 多设备选择

普通 Jugg Run 可以选择多台设备。Jugg 会编译一次，然后按 Android Studio 设备选择器里的设备逐台部署。

## 怎么选择

在 Android Studio 顶部设备选择器中选择一台或多台设备，再点击 Jugg Run。

多设备运行适合同时检查不同系统版本、屏幕规格或厂商 ROM。每台设备都有自己的安装状态和部署缓存，所以同一轮里，不同设备会分别进入 Hot Reload、恢复或重装路径。

## 结果怎么算

| 情况 | 本轮结果 |
|---|---|
| 全部设备成功 | 本轮成功 |
| 任一设备失败 | 本轮显示失败 |
| 失败都允许 Gradle 降级 | 整轮降级 Gradle 后重跑 |

多设备降级是整轮级别，不是只重跑失败设备。

## Debug 只选一台

Jugg Debug 当前只支持单设备 attach。要调试断点时，只保留一台目标设备，再点击 Debug。

## 相关页面

- [运行 App](./run.md)
- [Debug](./debug.md)
- [多设备能力](../capabilities/deploy/multi-device.md)
- [部署问题排查](../troubleshooting/deploy.md)
