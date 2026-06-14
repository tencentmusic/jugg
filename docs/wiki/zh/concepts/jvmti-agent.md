---
title: JVMTI Agent
description: 解释 Jugg 运行时 agent 的职责、推送时机和兼容部署关系。
status: active
tags:
  - concept
  - deploy
  - runtime
---

# JVMTI Agent

JVMTI Agent 是 Jugg 运行时能力的一部分。它帮助 Jugg 在某些设备和系统版本上完成更稳定的增量部署、兼容检测和运行时修复。

用户通常不需要直接操作 agent。它会随部署流程自动检查、推送和验证。

## Agent 负责什么

Jugg 的 agent 主要承担三类职责：

| 职责 | 说明 |
|---|---|
| 运行时能力准备 | 把 Jugg 需要的 startup agent 放到 App 沙箱中。 |
| 兼容性检测 | App 重启后判断 JVMTI 能力是否可用。 |
| 兼容部署辅助 | 标记某些设备或 App 需要进入更保守的兼容部署路径。 |

它不是普通业务代码，也不是每次 install 都必须推送。Jugg 会根据部署数据和设备状态判断是否需要处理。

## 为什么部署后才推送

Jugg 会在增量部署完成后再检查是否需要补推 agent。原因是 Android Studio 的 Apply Changes 机制可能在首次部署时清理 startup agent 目录。如果 Jugg 提前推送，可能会被后续部署动作删除。

典型顺序是：

1. 完成 install、hot reload 或 hot fix。
2. 检查 App 沙箱中是否同时具备所需 agent。
3. 缺失时推送 agent bundle 并写入 App 沙箱。
4. 如果本轮会重启 App，再等待 App 启动后检查 JVMTI 状态。

## 如何判断设备是否兼容

Agent 启动后会在 App 的缓存目录写入状态标记。Jugg 读取这些标记来判断：

- JVMTI 可用：继续使用标准增量能力。
- JVMTI 不可用：记录兼容设备状态，后续进入兼容部署。
- 暂无结果：可能是 App 尚未启动完成，Jugg 会短时间等待。

如果已经进入兼容部署模式，Jugg 不会重复做同一轮检测，避免循环重试。

## 用户能感知到什么

你可能会看到这些现象：

- 首次增量部署后 App 被重启，用于让 startup agent 生效。
- 某些设备提示进入 compat mode。
- 部署日志出现 JVMTI status 检测。
- 同一设备后续更倾向使用兼容部署路径。

这些通常表示 Jugg 在适配设备运行时能力，而不是业务代码错误。

## 与部署策略的关系

JVMTI Agent 会影响部署策略，但不单独决定部署策略。最终仍由部署数据、设备状态、用户设置和兼容记录共同决定。

当标准热更能力不可用时，Jugg 会尝试切换到更保守的路径，优先保证本轮改动可靠生效。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [回退与限制](./fallback-and-limits.md)
- [兼容层](./compatibility-layer.md)
