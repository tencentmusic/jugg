---
title: 部署能力
description: 汇总 Jugg 部署相关能力，用于判断本轮修改会安装、热更新、重启还是恢复部署状态。
status: active
tags:
  - capability
  - deploy
---

# 部署能力

Jugg 部署能力承接编译产物，把 APK、dex、资源 overlay、Manifest、`.so` 和运行时 agent 等变化应用到目标设备。它会根据产物类型、设备状态和历史 checkpoint 选择 install、Apply Changes、重启、Direct Overlay 或 recover/retry。

## 能力总览

### 部署策略

| 能力 | 当前支持情况 | 典型结果 |
|---|---|---|
| [Clean Reinstall](./clean-reinstall.md) | 支持重新安装，可按需清理 app 数据 | 重新建立 APK、部署历史和 overlay 基线 |
| [Code Swap](./code-swap.md) | 支持可在线替换的方法体级 class 更新 | 尽量不重启 App，直接更新运行中代码 |
| [Full Swap](./full-swap.md) | 支持需要重启 Activity 的 Apply Changes | 更新代码或 overlay 后重启当前 Activity |
| [Hot Reload](./hot-reload.md) | 支持在线增量 overlay 和 class 更新 | App 保持运行，必要时只重启 Activity |
| [Restart](./restart.md) | 支持按部署结果或用户选择重启 App | 让 hot fix、agent 或调试场景生效 |

### 状态恢复与复杂目标

| 能力 | 当前支持情况 | 典型结果 |
|---|---|---|
| [Direct Overlay](./direct-overlay.md) | 支持在设备未 ready 时直接写入 overlay | 不依赖在线 Apply Changes transport 完成 overlay 更新 |
| [Recover 与 Retry](./recover-and-retry.md) | 支持状态恢复、兼容部署和失败重试 | 避免状态不一致后继续在错误基线上热更 |
| [多 APK](./multi-apk.md) | 支持 base、split、test APK 等目标归属分流 | 同一轮产物按 APK/applicationId 正确下发 |
| [多设备](./multi-device.md) | 支持对选择的多台设备逐台部署 | 汇总成功状态和失败回退资格 |
| [部署历史与缓存](./deploy-history-cache.md) | 支持维护 Jugg history 与 Android Studio deployment cache | 判断 overlay checkpoint 是否可信 |
| [JVMTI Runtime](./jvmti-runtime.md) | 支持部署后准备 Jugg agent 并检测兼容性 | 支撑兼容部署、运行时插桩和后续工具能力 |

> [!IMPORTANT]
> 部署策略由本轮编译结果和设备状态共同决定。Gradle 编译成功后走 install；Jugg 增量编译成功后进入增量部署，失败时按失败类型进入 recover、retry 或 Gradle 回退。

## 部署链路如何串起来

```text
Run 触发
  -> 编译产物进入 staging
  -> 生成 JuggDeployData
  -> 判断 install / hot reload / hot fix / full swap
  -> 按设备和 APK 归属分流
  -> 执行 install、Apply Changes 或 Direct Overlay
  -> 成功后提交部署历史与 overlay checkpoint
```

用户不需要手动选择部署类型。Jugg 会根据 class 结构变化、资源和 APK 更新、设备是否 ready、overlay checkpoint 是否匹配等信息决定本轮策略。

## 相关页面

- [部署结果说明](../../guide/deploy.md)
- [部署策略概念](../../concepts/deploy-strategy.md)
- [部署数据与影响分析](../../concepts/deploy-data-and-impact.md)
- [部署问题排查](../../troubleshooting/deploy.md)
- [限制](../../reference/limits.md)
