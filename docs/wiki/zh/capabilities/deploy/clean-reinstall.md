---
title: Clean Reinstall
description: 说明 Jugg 何时重新安装应用，以及 clean reinstall 如何重建设备部署基线。
status: active
tags:
  - capability
  - deploy
  - install
---

# Clean Reinstall

Clean Reinstall 用于重新建立 App 在设备上的可信基线。Jugg 会安装当前 APK，并在需要时清理 App 数据、重置部署历史和 overlay checkpoint，避免后续增量部署建立在不匹配的状态上。

## 何时会重新安装

| 操作场景 | 当前支持情况 | 部署策略 |
|---|---|---|
| Gradle 构建后的首次部署 | 支持 | 直接安装本轮 APK，并写入 deployment cache |
| 设备未安装目标 App | 支持 | install 替代增量部署 |
| clean reinstall 选项开启 | 支持 | 先清理 App 数据，再安装 APK |
| overlay 或 cache 状态不匹配 | 支持 | recover 失败后重新安装 |
| APK install 遇到可恢复异常 | 支持有限重试 | 必要时卸载当前 applicationId 后重新安装 |
| 多 APK 应用 | 支持 | 按 applicationId 分组安装 base、split 或 test APK |

> [!NOTE]
> Clean Reinstall 会重置增量部署基线。它不是普通热更新路径，但能让下一轮增量部署重新获得可信的 APK、history 和 overlay 状态。

## 这项能力如何生效

```text
需要 install
  -> 停止目标 App
  -> 组装 install 用 JuggDeployData
  -> 按 applicationId 分组安装 APK
  -> 写入 Android Studio deployment cache
  -> 更新 Jugg 部署历史和 overlay id
  -> reset staging / deployed 文件状态
```

安装前先停止 App，避免用户看到“安装成功后又被停止”的体验。安装成功后，Jugg 会把当前 APK 与 overlay id 作为新的部署 checkpoint；如果是 recover 触发的 reinstall，还会清空旧的 deployed data、resource APK 和 staging 状态。

## 与增量部署的关系

Clean Reinstall 通常出现在以下边界：

- Gradle 编译成功后，需要用完整 APK 重新建立基线。
- dry deploy 或 Direct Overlay 状态校验失败，说明设备端不适合继续热更。
- App 被外部安装、卸载、清数据或切换工程后，历史 checkpoint 已不可信。

成功 reinstall 后，后续修改仍会优先走增量部署。

## 关联能力

- [Recover 与 Retry](./recover-and-retry.md)
- [部署历史与缓存](./deploy-history-cache.md)
- [多 APK](./multi-apk.md)
- [Gradle 回退](../compile/gradle-fallback.md)
