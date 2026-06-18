---
title: JVMTI Agent
description: 说明 Apply Changes 中 JVMTI Agent 的作用，以及 Jugg 为什么需要自有 Agent 做设备兼容。
status: active
tags:
  - concept
  - deploy
  - runtime
---

# JVMTI Agent

JVMTI 是 JVM 暴露的调试接口，Android 8.0 以后也支持类似能力。Apply Changes 基于 JVMTI 实现运行时 class 替换，也就是热重载。

Jugg 复用 Apply Changes 通道完成热重载，同时也在设备兼容问题上引入了自己的 JVMTI Agent。

## Apply Changes 中的 Agent

Apply Changes 的运行时替换流程包括：

```text
生成 APK
  -> IDE 比较新旧 APK，找出变化 class 和资源
  -> push agent.so 到 code_cache/startup_agents
  -> push 增量文件到 code_cache/.overlay
  -> attach agent 到 App 虚拟机
  -> 通过 JVMTI RedefineClasses 替换 class 实现
  -> 通过启动加载逻辑在重启后恢复增量部署
```

Apply Changes 还会把 `instruments.jar` 加入 bootstrap class loader，并修改 `DexPathList`、`LoadedApk`、`ResourcesManager` 等运行时逻辑，使 DEX、native lib 和资源 overlay 可以被加载。

## Jugg 为什么需要自己的 Agent

部分系统会提前 ClassLoader 初始化时机，导致 Apply Changes 修改 Dex 搜索逻辑失效。Jugg 因此构建了自有 JVMTI Agent，在 Application 创建时检测 ClassLoader；如果 Dex 没有正确加载，则重新触发 ClassLoader 创建。

部分设备系统也可能存在 JVMTI 兼容问题。Jugg 不只依赖 JVMTI 绕过，而是在 JVMTI 不可用时切到经典热修复，以提高部署兼容性。

## 与热重载和热修复的关系

热重载依赖 JVMTI 在线替换 class 实现，不需要重启 App，但不支持所有类结构变化。

热修复通过插入 DEX、native lib 或资源路径，让重启后的 App 读取新增量产物。它不依赖同一套 JVMTI class redefine 能力，覆盖面更广，但通常需要重启。

Jugg 的部署策略是在能热重载时优先热重载，不适合时转向热修复或更保守路径。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [回退与限制](./fallback-and-limits.md)
- [兼容层](./compatibility-layer.md)
