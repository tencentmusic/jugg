---
title: Gradle 回退
description: 说明 Jugg 什么时候会从增量编译回退到 Gradle，以及回退后的用户可见行为。
status: active
tags:
  - capability
  - compile
  - fallback
---

# Gradle 回退

Jugg 会优先尝试增量编译。当当前构建基线不再适用，或失败结果明确允许回退时，本轮 Run 会切换到 Gradle。普通编译错误和用户取消不会自动触发完整构建。

## 触发 Gradle 回退的情况

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 用户强制 Gradle | 支持 | 直接跳过增量检查 |
| 没有文件变化 | 支持提示或自动回退 | 由配置决定是否确认 |
| 文件过多或模块过多 | 支持确认后回退 | 默认 Gradle；倒计时后可本轮继续增量 |
| 设备被判定为无效 | 支持自动回退 | 本轮改走 Gradle；设备恢复后才能完成安装 |
| build target 切换 | 支持自动回退 | App 与 androidTest 切换需要新 APK 基线 |
| 构建文件/依赖变化 | 支持确认后回退或依赖增量 | 用户确认决定是否继续增量 |

## 触发与结果

```text
开始 Run
  -> 前置检查要求完整构建：当前 Run 切换到 Gradle
  -> 增量编译出现普通错误：结束当前 Run，不立即回退
  -> 部署失败：先尝试恢复，满足自动回退条件时整轮切换到 Gradle
  -> Gradle 成功：使用新的完整构建产物继续安装或后续运行
```

Gradle 构建成功后，Jugg 会把新的 APK、classpath、mapping 和资源产物作为后续增量的起点。下一次小范围修改仍会优先尝试增量编译。

## 使用边界

- 增量编译会先对已知且可恢复的输入问题进行有限重试。普通源码或资源错误在重试后仍失败时，本轮直接结束，不会用 Gradle 覆盖原始错误。
- 用户取消是明确的停止信号，不触发自动回退。
- App 未启动或部署状态需要恢复时，Jugg 会优先尝试 Recover、兼容部署或重新安装现有 APK。Gradle 回退不能修复设备离线、ADB 异常等设备问题。
- 部署失败只有在失败结果允许回退，并且开启自动回退配置时，才会让整轮 Run 重新执行 Gradle；多设备运行不会只为单台设备切换构建基线。

需要主动刷新完整构建基线时，按[降级 Gradle 编译](../../guide/downgrade-gradle.md)操作。回退条件和基线更新机制见[Gradle 回退与基线重建](../../concepts/gradle-fallback-baseline.md)。

## 相关页面

- [源码编译](./source-compile.md)
- [依赖库增量编译](./dependency-incremental.md)
- [降级 Gradle 编译](../../guide/downgrade-gradle.md)
- [编译阶段说明](../../guide/compile.md)
- [Gradle 回退与基线重建](../../concepts/gradle-fallback-baseline.md)
- [部署自愈机制](../../concepts/deploy-self-healing.md)
