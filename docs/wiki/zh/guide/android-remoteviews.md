---
title: Android RemoteViews
description: 说明桌面小组件、通知自定义 View 等 RemoteViews 场景下，如何让增量变化写入 APK 后生效。
status: active
tags:
  - guide
  - remoteviews
  - apk
---

# Android RemoteViews

桌面小组件、通知自定义 View 等 RemoteViews 场景会从 APK 内容读取资源和布局。普通增量部署有时只更新运行时 overlay，系统进程读不到这些变化。

## 什么时候开启

遇到下面情况时，开启 RemoteViews 对应的 APK 嵌入模式：

- 修改 widget layout、通知自定义 layout 后，App 内能看到变化，系统组件看不到。
- RemoteViews 使用的 drawable、values 或 layout 没有按预期更新。
- 你明确知道这次变化需要写入 APK 内容。

入口在 More Options：

```text
Embedded to APK(for Android RemoteViews)
```

第一次开启会有确认框。开启后，Jugg 会把增量变化写入 APK 并重新签名，部署会更慢。

## 用完可以关闭

RemoteViews 场景验证完后，建议关闭这个开关。普通页面调试通常不需要把每轮增量都嵌回 APK。

## 相关页面

- [运行 App](./run.md)
- [导出增量 APK](./export-incremental-apk.md)
- [资源编译](../capabilities/compile/resource-compile.md)
