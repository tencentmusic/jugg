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
| 方法体级代码修改 | 支持 | 满足 `run-as`、UID 和 SELinux label 前提的 App 使用 Apply Changes；不兼容 App 在 sandbox 权限可用时先写 overlay，再由 Jugg Agent 在线替换，不重建 Activity |
| 可 overlay 的资源或 asset 修改 | 支持 | 推送 overlay；普通 Apply Changes 按需重启 Activity，Direct sandbox 在 Android 11+ 刷新资源并重建当前 Activity，Android 8～10 重启 App |
| 首次资源 overlay | 支持 | 补齐全量资源，避免设备端缺资源 |
| 新增 class | 支持增量下发 | 作为 new class 进入 Apply Changes |
| 结构变化 class | 支持增量下发，但需要重启 | 进入 Hot Fix 路径 |
| Manifest、`resources.arsc`、`.so` 更新 | 支持作为 APK 更新 | 修改 APK 并重签名后安装或恢复状态 |
| 设备状态不匹配 | 支持自动恢复 | 先 recover/retry，再决定是否继续热更 |

> [!NOTE]
> Hot Reload 不承诺所有修改都“不重启”。Jugg 会优先保留运行态，但当 payload 需要进程或 Activity 重启时，会切到对应策略。

## 这项能力如何生效

```text
增量编译成功
  -> 汇总本轮 class、资源和 APK 文件
  -> 判断 Hot Reload / Hot Fix / APK 更新
  -> run-as、UID 或 SELinux label 不兼容 App 的 class、资源和 assets 变更走 Direct Overlay + Jugg JVMTI Agent
  -> 设备 ready 时走 Apply Changes and Restart Activity
  -> 设备未 ready 且满足条件时尝试 Direct Overlay
  -> 成功后提交部署历史
```

Hot Reload 的核心是部署数据分类。Jugg 会把可在线更新的 class 放入 hot reload，把结构变化或需要进程重启的内容放入 hot fix，把 Manifest、`resources.arsc`、native lib 等放入 APK 更新路径。

Jugg 用一次可回滚写入探测判断 Android Studio Apply Changes 的前提：只有 `run-as` 返回唯一成功标记、原始 UID 位于 `10000..19999`，且新建文件与 App 既有缓存目录使用相同 SELinux label，才继续进入该通道。其它结果不依赖应用 flags 或具体错误文本；Jugg 会在真实 data 目录依次验证普通 shell、一次 root adbd 和非交互 `su`。权限可用时，它先把 Dex 写入持久化 overlay，再尝试在线替换；在线失败时重启 App，由 Jugg startup agent 加载同一份 overlay。

Direct 路径会让 Dex 和请求文件继承 App 缓存目录的动态 SELinux label，并把 JVMTI Agent `.so` 标为 App 进程可执行的类型。这样 root 写入不会因为 owner、MCS categories 或文件执行类型不同而在重启或 dynamic attach 时失效。

`run-as`、UID 或 SELinux label 不兼容时，Jugg 可以直接下发 class、资源和 assets。纯方法体变化可在线替换；Android 11+ 的普通资源、assets 或与代码混合的变化会刷新主进程资源并重建当前 Activity，刷新失败时再重启 App。Android 8～10 需要重启进程，兼容部署沿用资源 APK。Manifest 和 native library 仍走 APK 更新与安装流程。权限探测失败或缺少 deployment cache 时会直接报告失败。

## 使用边界

Jugg 会在以下情况离开普通 Hot Reload 路径：

- 本轮编译已经回退 Gradle，部署会进入 install。
- 设备 overlay id、deployment cache 或历史状态不匹配，需要 recover。
- payload 需要 App 重启或 Activity 重启。
- JVMTI 不可用或部署失败信号要求兼容部署。

Direct sandbox 是 Apply Changes 前提不成立时的替代通道，当前仍有这些范围限制：

- 单次在线请求只处理一个主进程；独立进程在对应进程重启后加载 overlay。
- 重建主进程全部存活 Activity，包括其它任务栈和多窗口实例；独立进程中的 Activity 需要在对应进程重启后刷新。
- 新类、结构变化、APK 根目录资源和 Compose 资源继续走需要进程重启的路径。
- Direct 整批提交本轮变化，不提供官方 Apply Changes 的切片进度和分片重试。
- 必须已有可校验的 deployment cache 和 overlay 状态；缺失或不匹配时先进入 recover 或 reinstall。

## 相关页面

- [Code Swap](./code-swap.md)
- [Full Swap](./full-swap.md)
- [Direct Overlay](./direct-overlay.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [Apply Changes 中的 class 与 overlay](../../concepts/apply-changes.md)
- [APK 更新与安装](../../concepts/apk-update-and-install.md)
