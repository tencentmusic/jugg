---
title: JVMTI Runtime
description: 说明 Jugg 部署链路中的 JVMTI agent 准备、兼容检测和运行时能力边界。
status: active
tags:
  - capability
  - deploy
  - jvmti
---

# JVMTI Runtime

JVMTI Runtime 是 Jugg 部署后的运行时支撑能力。它负责把 Jugg agent 准备到设备和 App sandbox 中，在 App 重启后检测 JVMTI 是否可用，并在不兼容设备上切换到兼容部署策略。

## Agent 准备与可用性检测

| 操作场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 增量部署后准备 Jugg agent | 支持 | 部署完成后为目标 App 补齐 startup agent |
| Apply Changes startup agent 准备 | 支持 | Direct Overlay 路径可以补齐在线替换所需的 Agent |
| JVMTI 可用性检测 | 支持 | App 重启后得到可用或不可用结果 |
| 32 位与 64 位 ARM App | 支持 | 运行中和已停止 App 都按可用证据选择对应 Agent |
| 运行时修正 hook | 支持 | 在 App 启动阶段处理命中的 ClassLoader、资源和系统兼容差异 |
| Direct Activity relaunch | 支持 | 仅在 Restart Activity 模式下于 class 替换后重建 Activity，HOT_RELOAD 不变 |
| 不兼容 app/device 记录 | 支持 | 后续部署直接进入兼容路径，避免重复尝试不可用的在线替换 |

> [!NOTE]
> install 本身没有增量部署文件，通常不会触发“部署后补 push agent”。agent 检测依赖 App 重启后 startup agent 被系统加载。

## 32 位与 64 位 Agent 如何选择

运行中的 App 可以直接使用进程架构。App 已停止时没有进程可供探测，Jugg 会继续按以下顺序寻找证据：

```text
运行中进程架构
  -> Manifest android:use32bitAbi
  -> 全部 base/split APK 中的 ARM native library
  -> 已安装包的 primaryCpuAbi
  -> 设备主 ABI
  -> 仍然未知：使用 64 位兜底
```

APK 只有在全部 split 聚合后能够确定唯一 ARM 位数时才参与选择。同时包含 32 位和 64 位 library，或完全没有 ARM library 时，APK 证据保持未知；不含 native library 的资源 split 不会覆盖其它 APK 的有效结果。只有 Manifest 和 APK 都无法判断时，Jugg 才会通过 ADB 查询已安装包，避免本地证据已经足够时仍增加部署等待时间。

当前只支持 `armeabi`、`armeabi-v7a` 和 `arm64-v8a`，不兼容 x86。App sandbox 中已经存在同版本 Agent 时不会主动替换；App ABI 发生变化后如仍残留旧架构 Agent，重装 App 可以清理该状态。

## 这项能力如何生效

```text
增量部署完成
  -> 必要时准备 Jugg startup agent
  -> 重启或启动 App
  -> 探测 JVMTI 是否可用
  -> 不可用时记录当前 app/device 组合
  -> 触发兼容部署重试或在后续部署中直接使用兼容路径
```

Agent 必须在部署后准备，并在 App 重启后检测。具体时序和 Apply Changes Agent 的分工见 [Jugg JVMTI Agent](../../concepts/jugg-jvmti-agent.md)。

Direct app sandbox 会额外把 instrumentation JAR 复制到 App 的 `code_cache`，避免系统/特权 App 进程映射 `/data/local/tmp` 文件时被 SELinux 拒绝。该兼容逻辑不影响普通 `run-as` 或 Android Studio deploy transport。

## 兼容部署如何触发

部署失败后，Retry 链路会检测失败是否可能来自 JVMTI 兼容问题。若 App 写出了 not-available flag，Jugg 会记录当前 app/device 组合，并在后续部署中直接进入兼容部署，避免重复尝试不可用的 runtime 能力。

## 相关页面

- [Jugg JVMTI Agent](../../concepts/jugg-jvmti-agent.md)
- [App 进程内 Jugg runtime](../../concepts/jugg-runtime.md)
- [Restart](./restart.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [Direct Overlay](./direct-overlay.md)
- [Hot Reload](./hot-reload.md)
