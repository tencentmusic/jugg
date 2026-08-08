---
title: 部署历史与缓存
description: 说明部署历史和缓存不一致时，用户会看到哪些恢复、重装或回退行为。
status: active
tags:
  - capability
  - deploy
  - cache
---

# 部署历史与缓存

部署历史与缓存决定设备是否还能继续增量部署。用户通常不需要直接操作这些状态，但它们会影响本轮是继续 Hot Reload / Direct Overlay，还是先 Recover、Clean Reinstall 或 Gradle 回退。

## 用户可见行为

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| history、cache 和设备 checkpoint 匹配 | 支持 | 继续增量部署 |
| checkpoint 不匹配 | 支持恢复 | 进入 Recover，必要时重新安装 APK |
| App 被手动卸载或覆盖安装 | 支持恢复 | 重新安装并重建设备基线 |
| Direct Overlay 前状态不可信 | 支持拦截 | 不直接写 overlay，转 Recover 或 Reinstall |
| 部署成功 | 支持提交 | 后续运行继续复用新的增量基线 |

> [!IMPORTANT]
> 如果部署历史或设备 checkpoint 不一致，Jugg 会优先恢复可信状态，而不是继续把新 overlay 写到未知现场。

## 什么时候会影响本轮运行

- 切换设备、手动安装 APK、清理 App 数据或覆盖安装后，设备状态可能和本地 history 不一致。
- Direct Overlay 需要先校验 deployment cache 和 overlay id，校验失败不会强行写入。
- Reinstall 会清空旧 deployed data、resource APK 和 staging 状态，再建立新的部署基线。

部署历史、deployment cache 和 overlay id 的状态模型见 [部署状态与恢复](../../concepts/deploy-state-recover.md)。

## 相关页面

- [部署结果说明](../../guide/deploy.md)
- [部署状态与恢复](../../concepts/deploy-state-recover.md)
- [Hot Reload](./hot-reload.md)
- [Direct Overlay](./direct-overlay.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [多 APK](./multi-apk.md)
