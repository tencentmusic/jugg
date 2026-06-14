---
title: 部署策略
description: 解释 Jugg 如何在 install、hot reload、hot fix、兼容部署和重启之间选择。
status: active
tags:
  - concept
  - deploy
---

# 部署策略

Jugg 部署阶段的目标是把编译产物应用到设备，并让 App 进入可继续运行或可测试的状态。部署策略由两类信息共同决定：**本轮产物能否热更新**，以及**设备上的部署状态是否可信**。

## 主要部署类型

| 类型 | 什么时候出现 | 用户感受 |
|---|---|---|
| Install | Gradle 构建后、首次运行、状态不可恢复或需要重装时 | 安装 APK，通常会启动 App。 |
| Hot Reload | 类结构未变，且设备支持在线更新时 | 尽量不重启 App。 |
| Hot Fix | 新增类、结构变化、需要 push overlay 或必须重启时 | 部署后重启 App。 |
| Compat Hot Fix | 设备或运行时不适合标准热更时 | 使用更保守的兼容路径，通常会重启。 |
| Embedded | 用户选择把增量内容写回 APK 时 | 更新 APK 后安装。 |

Jugg 会根据产物自动选择策略。用户通常不需要手动判断某个类修改属于哪一种。

## 部署前先看设备状态

增量部署依赖设备上的历史状态。如果设备状态不可信，Jugg 会先尝试恢复：

- App 是否已经安装。
- 当前 APK 与上次部署基线是否匹配。
- overlay 状态是否和历史记录一致。
- 是否发生了跨项目运行或目标切换。
- 是否需要先写回 APK 并重新安装。

能恢复时继续增量；不能恢复时走重装或回退。

## 编译产物如何变成部署内容

编译阶段只负责产生 staging 产物。部署阶段会把这些产物规划成最终 payload：

- 普通类变更可能进入 hot reload。
- 新增类、结构变化、多 dex 或 library dex 更容易进入 hot fix。
- 资源和 assets 作为 overlay 下发。
- Manifest、`resources.arsc` 和 native lib 可能需要更新 APK。
- 首次资源 overlay 部署会补齐更多资源，避免设备端资源不完整。

部署成功后，Jugg 才会提交 staging 产物并推进历史。

## 多 APK 和 Android Test

在存在 base APK、split APK、test APK 或 library-style Test APK 时，同一份增量产物不能简单投给所有 APK。Jugg 会按 APK 归属裁剪部署内容，确保：

- base APK 和 test APK 不互相写错资源。
- self-targeting library Test APK 有自己的部署目标。
- app-style test APK 不被当成普通独立进程随意热更。

Android Test 会在部署完成后再执行 instrumentation。测试断言失败不等同于部署失败；部署历史仍会按已成功部署的结果推进。

## 什么时候会重启 App

常见重启原因包括：

- 本轮 deploy type 是 hot fix 或 compat hot fix。
- 用户或运行配置要求每次部署后重启。
- Debug 运行需要重启后 attach。
- 首次 push 运行时 agent 或首次 full resource push 需要重启来保证运行时状态正确。
- App 不在前台，Jugg 需要启动它。

如果本轮是 hot reload 且 App 已在前台，Jugg 会尽量不重启。

## 相关页面

- [部署数据与影响分析](./deploy-data-and-impact.md)
- [JVMTI Agent](./jvmti-agent.md)
- [Android Test 流程](./android-test-flow.md)
- [回退与限制](./fallback-and-limits.md)
