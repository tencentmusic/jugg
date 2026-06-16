---
title: 回退与限制
description: 说明 Jugg 哪些场景需要回到 Gradle，以及增量编译方案的边界。
status: active
tags:
  - concept
  - fallback
---

# 回退与限制

Jugg 的增量编译依赖最近一次可信 Gradle 构建产物。回退 Gradle 的目的，是重新生成 APK、class、注解器生成源码和其他基线产物，让下一轮增量可以继续运行。

## 常见回退场景

原文中提到的回退或降级场景包括：

| 场景 | 原因 |
|---|---|
| 首次运行 | 没有可复用的 Gradle 基线产物 |
| `build.gradle` 变化 | 构建配置和依赖可能变化，需要重新读取 |
| 注解处理器或插桩场景 | 可能依赖 Gradle 上下文，增量参数难以确认 |
| 一次性修改文件很多 | 增量收益下降，完整构建更稳妥 |
| Manifest 更新 | Manifest 需要写回 APK 并安装 |
| 设备或部署现场不可信 | 继续部署可能基于错误状态 |
| 用户主动降级 | 用完整构建刷新基线 |

回退不是增量链路失效，而是重新建立可信起点。

## 增量方案的边界

Jugg 不是完整 Gradle pipeline。以下内容仍以 Gradle 构建为准：

- 发布构建和完整 APK / AAB 产物。
- 复杂构建脚本、Gradle plugin 和 variant 逻辑。
- 未明确支持的注解处理器、插桩和生成代码。
- 依赖图复杂变化。
- 需要完整刷新资源表、Manifest、mapping 或 APK 结构的场景。

原文还提到，大工程并不总能一键接入，仍可能需要适配工程里的特殊场景。

## 资源增量的限制

Jugg 定制 aapt2 `inclink` 后，可以基于 APK 中的 `resources.arsc` 和 res 内容做增量 link。限制是删除资源 ID 不会立即从资源表中移除，要等下一次 Gradle 构建刷新基线。

这个限制适用于 debug 开发场景，不能用于生产构建。

## 设备与版本限制

早期原文中提到，Jugg 的热重载依赖 Android 11 以上设备。后续答辩稿提到，Jugg 增加经典热修复后，设备要求从 Android 11 降到 Android 8。

设备厂商对 JVMTI / Apply Changes 的支持也可能有差异。答辩稿中提到过鸿蒙 4.2 和小米澎湃 OS 的兼容问题，Jugg 通过自有 JVMTI Agent 或经典热修复路径做兼容。

## 相关页面

- [增量编译](./incremental-compile/)
- [部署策略](./deploy-strategy.md)
- [项目模型](./project-model.md)
