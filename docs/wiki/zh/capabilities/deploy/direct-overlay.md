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
| 设备未 ready，但历史和 cache 匹配 | 支持 | 直接写入 `code_cache/.overlay` |
| Android O 及以上设备 | 支持 | 使用 run-as 写入 App sandbox |
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
  -> push 到 /data/local/tmp/jugg/
  -> run-as 目标 package 原子写入 code_cache/.overlay
  -> 最后写入新 overlay id
  -> 更新 deployment cache
```

Direct Overlay 会先检查 history、cache 和设备端 overlay 状态，确认当前基线可信后才写入。写入过程中，新 overlay id 最后提交；如果在修改 overlay 目录后失败，会把状态视为 dirty，不再尝试旧 Apply Changes 伪回退。

## 使用边界

Direct Overlay 需要同时满足这些条件：

- 用户或调用方允许 Direct Overlay。
- 设备不是普通 ready deploy 状态。
- deployment cache 存在且 overlay checkpoint 可校验。
- deploy data 非空，且不是 install。
- 设备系统版本满足要求，App sandbox 可通过 `run-as` 写入。

## 相关页面

- [部署状态与恢复](../../concepts/deploy-state-recover.md)
- [Hot Reload](./hot-reload.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [部署历史与缓存](./deploy-history-cache.md)
- [JVMTI Runtime](./jvmti-runtime.md)
