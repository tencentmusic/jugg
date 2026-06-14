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

| 操作场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 增量部署后补 push Jugg agent | 支持 | 将 agent bundle 放到设备临时目录，再 setup 到 App sandbox |
| Apply Changes startup agent 准备 | 支持 | Direct Overlay 路径可推送 AS startup agent |
| JVMTI 可用性检测 | 支持 | 读取 app `code_cache` 中的 available / not-available flag |
| 32 位 App agent | 支持 | 使用 `_alt.so` 约定选择 32 位 so |
| HarmonyOS 兼容信号 | 支持 | setup script 写入兼容修复 flag |
| 不兼容设备记录 | 支持 | 命中 not-available 后进入 compat deploy |

> [!NOTE]
> install 本身没有增量部署文件，通常不会触发“部署后补 push agent”。agent 检测依赖 App 重启后 startup agent 被系统加载。

## 这项能力如何生效

```text
增量部署 runTask
  -> 异步检查部署后是否需要 push agent
  -> JuggDeployTask 完成 install / swap
  -> 必要时 push agent bundle 并 setup 到 App sandbox
  -> 根据部署策略重启或启动 App
  -> 读取 .jugg_jvmti_available / .jugg_jvmti_not_available
  -> 不可用时记录 compat device 并触发兼容部署
```

Jugg 把 agent push 放在部署之后，是为了避免 Android Studio Apply Changes 首次写 startup agents 时清理掉 Jugg agent。检测必须等 App 重启，因为 startup agent 只有在进程启动时才会加载。

## 兼容部署如何触发

部署失败后，Retry 链路会检测失败是否可能来自 JVMTI 兼容问题。若 App 写出了 not-available flag，Jugg 会记录当前 app/device 组合，并在后续部署中直接进入兼容部署，避免重复尝试不可用的 runtime 能力。

## 关联能力

- [Restart](./restart.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [Direct Overlay](./direct-overlay.md)
- [Hot Reload](./hot-reload.md)
