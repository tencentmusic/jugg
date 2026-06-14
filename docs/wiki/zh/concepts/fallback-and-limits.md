---
title: 回退与限制
description: 说明 Jugg 为什么会回退 Gradle、哪些场景属于能力边界，以及如何理解回退结果。
status: active
tags:
  - concept
  - fallback
---

# 回退与限制

Jugg 的策略是“能增量时增量，不可靠时回退”。回退不是异常，而是 Jugg 发现当前状态不适合继续使用旁路增量后，选择重新建立可信基线。

## 常见回退原因

| 场景 | 为什么回退 |
|---|---|
| 构建脚本或依赖变化 | 需要重新读取 Gradle 任务、classpath、依赖和产物路径。 |
| 运行目标切换 | App 与 Android Test 需要不同 APK 和项目快照。 |
| 文件变化过多 | 增量收益下降，完整构建更可靠。 |
| 设备不可用或状态不匹配 | 无法证明设备上仍是上次部署基线。 |
| 上次完整构建失败 | 缺少可信的 APK、classpath 或部署历史。 |
| 增量编译失败且不能安全修复 | 避免用不完整产物继续部署。 |
| 部署失败但允许 fallback | 整轮 Run 切回 Gradle 构建和安装。 |

如果你看到 fallback 提示，优先理解为“Jugg 正在刷新基线”，而不是“增量功能坏了”。

## Jugg 不替代哪些能力

Jugg 不负责完整替代 Gradle pipeline。以下场景仍以 Gradle 为权威路径：

- 构建脚本、插件、variant、flavor 或依赖图发生复杂变化。
- 需要完整生成 APK/AAB 或发布产物。
- 复杂注解处理、KSP/KAPT、字节码插桩或构建插件副作用。
- 需要重新建立 Android Test full-build baseline。
- 远端构建、include build、AGP 输出目录变化等需要重新拉取项目快照的场景。

Jugg 会尽量识别这些情况；识别不了时，用户也可以手动强制 Gradle 构建。

## “没有文件变化”不总是失败

看到没有文件变化时，Jugg 的行为取决于上下文：

- 首次运行或跨项目切换后，可能直接部署以恢复设备状态。
- Android Test 可以在没有新编译产物时直接重跑 instrumentation。
- Debug 运行可能直接部署并重启以便 attach。
- 普通 App Run 在没有变化且不需要部署时，可能提示回退或停止。

因此不要只根据“no file changes”判断本轮失败，要结合最终 compile/deploy 结果看。

## 兼容与性能边界

Jugg 会在可靠性和速度之间做保守选择：

- 热更新受设备、Android 版本、Android Studio deployer 和 JVMTI 能力影响。
- 首次资源部署可能需要 full resource push，因此比普通源码 hot reload 慢。
- 多 APK 或 Android Test 场景需要额外做 APK 归属裁剪。
- release/minify 场景需要额外补偿内联和移除成员，可能触发更多补编译。
- 大量文件变化时，回退 Gradle 通常比多轮增量更稳定。

## 排查时看什么

如果你想判断为什么回退，优先看：

| 线索 | 含义 |
|---|---|
| `Fallback to gradle compile` | 本轮已进入 Gradle 回退。 |
| `Too many changes` | 文件或模块变化超过增量阈值。 |
| `Build target changed` | App / Android Test 目标切换，需要刷新基线。 |
| `Device not ready` | 设备状态不满足增量编译或部署。 |
| `No file changes` | 当前没有可增量编译的变化，需要结合运行模式判断。 |

详细日志入口见[日志参考](../reference/log-files.md)和[编译问题排查](../troubleshooting/compile.md)。

## 相关页面

- [增量编译](./incremental-compile.md)
- [部署策略](./deploy-strategy.md)
- [项目模型](./project-model.md)
