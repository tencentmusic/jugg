---
title: Direct Overlay
description: 说明 Jugg 在设备未 ready 时直接写入 overlay 的部署能力。
status: active
tags:
  - capability
  - deploy
  - direct-overlay
---

# Direct Overlay

Direct Overlay 是 Jugg 在设备尚未进入在线 Apply Changes ready 状态时使用的 overlay 写入旁路。它直接把增量 overlay 写入 App sandbox，再由外层部署流程负责启动、重启或测试收口。

## Direct Overlay 适用条件

| 操作场景 | 当前支持情况 | 部署策略 |
|---|---|---|
| 设备未 ready，但历史和 cache 匹配 | 支持 | 直接写入 App sandbox 中的 overlay |
| Android O 及以上、Apply Changes 前提成立 | 支持 | 使用 `run-as` 写入 App sandbox |
| `run-as` 无成功标记、UID 越界或 SELinux label 不一致的 class、资源或 assets 变更 | 权限探测成功时支持 | 固定使用普通 shell、root adbd 或设备支持的非交互 `su` 命令形式写入真实 data 目录 |
| 需要提前准备 Apply Changes startup agent | 支持 | 由 Direct Overlay 路径推送 AS startup agent |
| overlay id 与预期不匹配 | 不强行写入 | 转 recover 或 reinstall |
| writer 已修改 overlay 后失败 | 不回退旧 Apply Changes | 阻止在半提交状态继续部署 |

> [!IMPORTANT]
> Direct Overlay 只替换 overlay update transport，不接管完整部署生命周期。启动 App、重启 App、运行 androidTest 和提交历史仍由外层部署流程负责。

## 这项能力如何生效

```text
设备不 ready 且允许 Direct Overlay
  -> 读取 deployment cache 和预期 overlay id
  -> 检查设备端 overlay id
  -> 构造 overlay zip payload
  -> 通过 ADB 传输到设备
  -> 以目标 App 身份原子更新 sandbox 中的 overlay
  -> 最后写入新 overlay id
  -> 更新 deployment cache
```

Recover 阶段会检查 history、cache 和设备端 overlay 状态；真正写入前至少会使用 cache 校验设备端 overlay ID。写入过程中，新 overlay ID 最后提交；如果在修改 overlay 目录后失败，会把状态视为 dirty，不再尝试旧 Apply Changes 伪回退。

当 `run-as`、UID 或 SELinux label 不满足 Android Studio Deployer 前提时，同一轮部署会复用一个已经选定权限模式的 sandbox executor，确保 overlay、startup agent 和 JVMTI Hot Reload 不会在 root 状态变化后切换路径。

Direct 权限模式会分别修复数据文件与 Agent `.so` 的 SELinux label：overlay 数据继承 App 缓存目录的动态 categories，`.so` 使用 App 进程可执行的类型。修复工具打印附加信息时，不会改变写入命令的成功判断。

## 使用边界

Direct Overlay 需要同时满足这些条件：

- 用户或调用方允许 Direct Overlay。
- 设备不是普通 ready deploy 状态。
- deployment cache 存在且 overlay checkpoint 可校验。
- deploy data 非空，且不是 install。
- 设备系统版本满足要求，App sandbox 可通过本轮选定的 `run-as`、普通 shell、root adbd 或非交互 `su` 模式写入。

`run-as`、UID 或 SELinux label 不兼容时，Jugg 可以直接下发 class、资源和 assets。纯方法体变化可在线替换；Android 11+ 的资源、assets 或与代码混合的变化会刷新当前进程资源并重建 Activity，刷新失败时再重启 App。Android 8～10 和兼容部署沿用需要重启进程的资源路径。Manifest 和 native library 仍走 APK 更新与安装流程。权限探测失败或缺少 deployment cache 时会直接报告失败。

## 相关页面

- [部署状态与恢复](../../concepts/deploy-state-recover.md)
- [Hot Reload](./hot-reload.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [部署历史与缓存](./deploy-history-cache.md)
- [JVMTI Runtime](./jvmti-runtime.md)
- [Direct Overlay 部署机制](../../concepts/direct-overlay.md)
