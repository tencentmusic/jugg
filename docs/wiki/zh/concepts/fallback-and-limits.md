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

回退通常来自三类信号：没有可信基线、当前修改超出旁路增量能力、设备或部署现场不可信。

| 场景 | 原因 |
|---|---|
| 首次运行 | 没有可复用的 Gradle 基线产物 |
| `build.gradle` 变化 | 构建配置和依赖可能变化，需要重新读取 |
| 注解处理器或插桩场景 | 可能依赖 Gradle 上下文，增量参数难以确认 |
| 一次性修改文件很多 | 增量收益下降，完整构建更稳妥 |
| Manifest 更新 | Manifest 需要写回 APK 并安装 |
| 设备或部署现场不可信 | 继续部署可能基于错误状态 |
| 用户主动降级 | 用完整构建刷新基线 |

这些场景的共同点是：继续走增量可能基于错误上下文运行。回退不是增量链路失效，而是重新建立可信起点。具体触发条件和用户操作建议见 [Gradle 回退](../capabilities/compile/gradle-fallback.md)。

## 增量方案的边界

Jugg 不是完整 Gradle pipeline。以下内容仍以 Gradle 构建为准：

- 完整发布构建和正式 APK / AAB 产物。
- 复杂构建脚本、Gradle plugin 和 variant 逻辑。
- 未明确支持的注解处理器、插桩和生成代码。
- 依赖图复杂变化。
- 需要完整刷新资源表、Manifest、mapping 或 APK 结构的场景。

大工程不一定能一键覆盖所有特殊构建逻辑。遇到工程自定义插件、非标准生成代码或复杂 variant 逻辑时，应先用 Gradle 刷新基线，再判断能否纳入 Jugg 增量路径。

## 资源增量的限制

Jugg 定制 aapt2 `inclink` 后，可以基于 APK 中的 `resources.arsc` 和 res 内容做增量 link。限制是删除资源 ID 不会立即从资源表中移除，要等下一次 Gradle 构建刷新基线。

这个限制适用于 debug 开发场景，不能用于生产构建。

## 设备与版本限制

设备厂商对 JVMTI / Apply Changes 的支持可能有差异。Jugg 会通过自有 JVMTI Agent、兼容部署或经典热修复路径降低设备差异影响，但设备能力仍会影响本轮能否在线替换、是否需要重启，或是否需要重新安装。

## 相关页面

- [增量编译](./incremental-compile/)
- [部署策略](./deploy-strategy.md)
- [工程上下文获取](./project-model.md)
