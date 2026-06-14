---
title: Recover 与 Retry
description: 说明 Jugg 如何在设备状态不匹配、部署失败或兼容问题时恢复并重试。
status: active
tags:
  - capability
  - deploy
  - recover
  - retry
---

# Recover 与 Retry

Recover 与 Retry 用于保护增量部署基线。Jugg 会在设备状态未知、overlay id 不匹配、transient offline、JVMTI 兼容问题或 install 异常时，先恢复到可信状态，再决定重试、兼容部署、hot fix 或重新安装。

## 会自动恢复或重试的信号

| 失败或状态信号 | 当前支持情况 | 处理方式 |
|---|---|---|
| App 未安装或 pm path 不存在 | 支持 | 重新安装 APK |
| history/cache/device overlay 不匹配 | 支持 | dry deploy 失败后 recover 或 reinstall |
| transient offline | 支持 | 等待 ADB transport 恢复后重试 |
| JVMTI unmodifiable class 或 redefiner 错误 | 支持 | fallback 到 hot fix 后重新部署 |
| agent 无响应或部署超时 | 支持 | 检测 JVMTI 兼容性，必要时 compat deploy |
| Direct Overlay dirty failure | 支持停止伪回退 | 阻止旧 Apply Changes 在半提交状态继续执行 |
| install invalid APK | 支持恢复 | 卸载相关 applicationId 后重新 install |

> [!NOTE]
> Recover 不是简单重试同一条命令。它会先判断设备端是否仍在可信 checkpoint 上，不可信时会重装或切换部署策略。

## Recover 如何生效

```text
需要恢复部署状态
  -> 可选 clean data
  -> 尝试 dry deploy 或 Direct Overlay recover check
  -> 状态匹配: 继续增量部署
  -> 状态不匹配或 App 缺失: reinstall
  -> reinstall 成功后 reset DeployFileManager 状态
```

dry deploy 用于验证设备、cache 和 history 是否仍能承接增量部署。Direct Overlay recover 在开关和调用方允许时参与；如果 direct deploy 本身失败后进入 retry，recover 会禁用 Direct Overlay，改走 legacy 启动 App + dry deploy 路径。

## Retry 如何生效

```text
deploy 失败
  -> 按失败信号分类
  -> 可原地等待的 offline 先等待
  -> 可改 payload 的失败转 hot fix 或 compat deploy
  -> 状态不匹配先 recover
  -> 仍不可恢复时向 Run 层返回失败和 fallback 资格
```

Run 层拿到的是每台设备的 `DeployTaskResult`。如果失败允许 fallback 且配置开启自动回退，整轮 Run 会切到 Gradle 重新执行，而不是只重跑某一台设备。

## 关联能力

- [Clean Reinstall](./clean-reinstall.md)
- [Direct Overlay](./direct-overlay.md)
- [部署历史与缓存](./deploy-history-cache.md)
- [JVMTI Runtime](./jvmti-runtime.md)
