---
title: 部署历史与缓存
description: 说明 Jugg 如何使用部署历史、deployment cache 和 overlay id 判断增量部署基线。
status: active
tags:
  - capability
  - deploy
  - cache
---

# 部署历史与缓存

部署历史与缓存是 Jugg 判断“设备是否还能继续增量部署”的核心依据。Jugg 会同时维护自己的部署历史、Android Studio deployment cache 和设备端 overlay id；三者一致时，后续 Hot Reload 或 Direct Overlay 才有可信基线。

## 维护的状态对象

| 状态对象 | 当前支持情况 | 用途 |
|---|---|---|
| Jugg 部署历史 | 支持 | 记录已部署文件、resource APK 和上次 overlay ids |
| Android Studio deployment cache | 支持 | 给 Apply Changes / Direct Overlay 提供 cache entry |
| 设备端 overlay id | 支持校验 | 判断设备 sandbox 中 overlay 是否符合预期 |
| install 后基线更新 | 支持 | 写入 cache 并推进 overlay checkpoint |
| 增量部署成功后 commit | 支持 | 先更新 history，再提交文件状态，最后写 overlay ids |
| reinstall 后 reset | 支持 | 清空旧 deployed data、resource APK 和 staging 状态 |

> [!IMPORTANT]
> 部署历史只能在整轮部署成功后提交。按 APK 裁剪出来的 scoped data 只服务当前 transport，不能作为全局 history 写回。

## 这项能力如何生效

```text
编译成功
  -> changed / compiled 文件进入 staging
  -> buildDeployData() 使用 staging + history 生成 payload
  -> deploy 成功
  -> 更新 deploy history
  -> DeployFileManager.commit(deployData)
  -> 写入 lastDeployOverlayIds
```

这个顺序不能随意调整。history、文件状态和 overlay id 必须一起前进，否则下一轮 recover 可能看到不一致的 checkpoint，从而触发 reinstall 或错误回退。

## checkpoint 判断规则

| 场景 | 判断方式 |
|---|---|
| 普通增量部署 | 比对预期 overlay id 和设备端 overlay id |
| Recover | 对比 Jugg history、deployment cache 和设备状态 |
| Direct Overlay | 先确认 cache 存在且设备 overlay id 匹配，再写入新 overlay |
| base install 空 overlay id | 允许 expected overlay id 为空字符串 |

当 cache 缺失、history 为空但 cache 有值、或设备 overlay id 与预期不一致时，Jugg 会把状态视为不可信，进入 recover 或 reinstall。

## 关联能力

- [Hot Reload](./hot-reload.md)
- [Direct Overlay](./direct-overlay.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [多 APK](./multi-apk.md)
