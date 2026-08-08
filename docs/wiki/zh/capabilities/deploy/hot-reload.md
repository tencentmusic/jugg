---
title: Hot Reload
description: 说明 Jugg 在线增量部署能力，以及它如何在不重装 App 的情况下应用变化。
status: active
tags:
  - capability
  - deploy
  - hot-reload
---

# Hot Reload

Hot Reload 是 Jugg 默认优先尝试的在线增量部署能力。它把本轮可增量处理的代码、资源 overlay 或其它部署项下发到设备，尽量避免完整 Gradle 构建和重新安装。

## 热更如何处理不同变化

| 操作场景 | 当前支持情况 | 部署策略 |
|---|---|---|
| 方法体级代码修改 | 支持 | Code Swap，尽量不重启 App |
| 可 overlay 的资源或 asset 修改 | 支持 | 推送 overlay，必要时重启 Activity |
| 首次资源 overlay | 支持 | 补齐全量资源，避免设备端缺资源 |
| 新增或结构变化 class | 支持增量下发，但可能需要重启 | 进入 hot fix 或 restart 路径 |
| Manifest、`resources.arsc`、`.so` 更新 | 支持作为 APK 更新 | 修改 APK 并重签名后安装或恢复状态 |
| 设备状态不匹配 | 支持自动恢复 | 先 recover/retry，再决定是否继续热更 |

> [!NOTE]
> Hot Reload 不承诺所有修改都“不重启”。Jugg 会优先保留运行态，但当 payload 需要进程或 Activity 重启时，会切到对应策略。

## 这项能力如何生效

```text
增量编译成功
  -> DeployFileManager 汇总 staging 文件
  -> DeployDataGenerator 生成 JuggDeployData
  -> 判断 HOT_RELOAD / HOT_FIX / update APK
  -> 设备 ready 时走 Apply Changes
  -> 设备未 ready 且满足条件时尝试 Direct Overlay
  -> 成功后 commit 部署历史
```

Hot Reload 的核心是部署数据分类。Jugg 会把可在线更新的 class 放入 hot reload，把结构变化或需要进程重启的内容放入 hot fix，把 Manifest、`resources.arsc`、native lib 等放入 APK 更新路径。

## 使用边界

Jugg 会在以下情况离开普通 Hot Reload 路径：

- 本轮编译已经回退 Gradle，部署会进入 install。
- 设备 overlay id、deployment cache 或历史状态不匹配，需要 recover。
- payload 需要 App 重启或 Activity 重启。
- JVMTI 不可用或部署失败信号要求兼容部署。

## 相关页面

- [Code Swap](./code-swap.md)
- [Full Swap](./full-swap.md)
- [Direct Overlay](./direct-overlay.md)
- [Recover 与 Retry](./recover-and-retry.md)
