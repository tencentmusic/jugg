---
title: Jugg JVMTI Agent
description: 解释 Jugg 为什么在 Apply Changes 之外使用自有 JVMTI Agent，以及它如何检测 JVMTI、修复已知运行时问题并触发兼容部署。
status: active
tags:
  - concept
  - deploy
  - runtime
---

# Jugg JVMTI Agent

Android Studio Apply Changes 依赖 JVMTI Agent，把结构未变化的 class 实现替换进正在运行的 App。Jugg 目前直接复用 Apply Changes 的热重载通道：对于可以在线替换的 class，仍由 Apply Changes Agent 通过 JVMTI 应用到当前进程；Jugg 自有 startup agent 只补充 JVMTI 可用性检测和已知运行时问题修复，并把结果交给部署链路决定是否切换兼容部署。

## Apply Changes 为什么还需要 Jugg Agent

把这些功能直接合入 Apply Changes Agent 也是可行的，但这意味着 Jugg 需要同时接管 Agent 分发、class 在线替换、部署通信以及不同 Android Studio 和设备版本的适配，也就是完整管理整条部署链路。

当前 Jugg 选择增加一个独立 startup agent，只处理需要补充的两件事：检测当前 App 进程能否取得 JVMTI，以及为已知运行时问题安装修正 hook。这样可以继续使用 Android Studio 的部署实现，同时由 Jugg 掌握兼容检测和回退决策。

## 检测 JVMTI 并修复已知问题

系统加载 Jugg startup agent 后，它先尝试取得 JVMTI 和 JNI，并将结果记为可用或不可用；App 尚未启动或结果尚未生成时，状态保持未确定。

取得 JVMTI 后，Agent 会对 Application、Resources、ClassLoader 等 Framework 入口安装 hook，只在命中已知问题时改变行为：

- 部分系统提前初始化 ClassLoader，导致已下发的增量 DEX 没有进入加载路径时，App runtime 会在启动阶段补入对应 DEX。
- Apply Changes 把宿主 App 的资源 overlay 带入 WebView provider 等非宿主资源环境时，provider 初始化可能触发 `IllegalStateException: Already registered a list of actions in this process`。Agent hook 会从这些资源环境中移除宿主 overlay，同时保留宿主 App 自己的资源更新。
- Android 15 与较旧 Android Studio 组合没有完整刷新资源和 Activity 时，Agent hook 会补齐资源更新通知和需要的 Activity 重建。

这些修正相互独立。某个系统版本不存在对应 Framework 类或单个 hook 安装失败时，其它可用修正仍会继续执行。DEX、资源和 Application 的完整处理方式见[App 进程内 Jugg runtime](./jugg-runtime.md)。

## 为什么必须部署后推送、重启后检测

Apply Changes 首次准备自己的 startup agent 时可能清理 App 中已有的 startup agent。如果 Jugg 提前推送，后续部署动作可能把它删除。因此 Jugg 先完成 Apply Changes 或其它增量部署，再检查并补齐自己的 Agent。

JVMTI 检测必须等到 App 重启。startup agent 由系统在进程启动时加载，Activity 重建或在旧进程中等待都不会触发这次初始化。本轮没有重启时，检测会留到下一次 App 进程启动后完成。

## 检测结果如何改变部署路径

App 重启并产生检测结果后，Jugg 部署链路按实际状态继续处理：

```text
App 启动并加载 Jugg startup agent
  -> 检测 JVMTI
  -> 可用：继续使用普通在线替换路径
  -> 不可用：记录当前 app/device 组合
  -> 使用本轮增量产物重新尝试兼容部署
```

自动探测记录按 app/device 组合生效。同一台设备上的另一个 App 不会因为这条记录自动进入兼容部署；用户手动开启的设备兼容模式则按设备生效。两者的选择范围不同。

## 相关页面

- [Apply Changes 中的 class 与 overlay](./apply-changes.md)
- [兼容部署](./compat-deploy.md)
- [App 进程内 Jugg runtime](./jugg-runtime.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
- [Hot Reload](../capabilities/deploy/hot-reload.md)
