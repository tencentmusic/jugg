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
| 32 位与 64 位 App | 支持 | 自动选择与目标进程架构匹配的 Agent |
| 运行时修正 hook | 支持 | 在 App 启动阶段处理命中的 ClassLoader、资源和系统兼容差异 |
| 不兼容 app/device 记录 | 支持 | 后续部署直接进入兼容路径，避免重复尝试不可用的在线替换 |

> [!NOTE]
> install 本身没有增量部署文件，通常不会触发“部署后补 push agent”。agent 检测依赖 App 重启后 startup agent 被系统加载。

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

## 兼容部署如何触发

部署失败后，Retry 链路会检测失败是否可能来自 JVMTI 兼容问题。若 App 写出了 not-available flag，Jugg 会记录当前 app/device 组合，并在后续部署中直接进入兼容部署，避免重复尝试不可用的 runtime 能力。

## 相关页面

- [Jugg JVMTI Agent](../../concepts/jugg-jvmti-agent.md)
- [App 进程内 Jugg runtime](../../concepts/jugg-runtime.md)
- [Restart](./restart.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [Direct Overlay](./direct-overlay.md)
- [Hot Reload](./hot-reload.md)
