---
title: 部署状态与恢复
description: 说明 Jugg 如何用部署历史、deployment cache 和设备 overlay id 判断是否能继续增量部署。
status: active
tags:
  - concept
  - deploy
  - recover
---

# 部署状态与恢复

Jugg 的增量部署依赖上一次成功部署留下的状态。状态对不上时，本轮不能直接把新 overlay 写到设备上。Jugg 会先 recover：能 dry deploy 证明状态一致，就继续增量；证明不了，就重新安装 APK 并重置本地部署状态。

## 三个状态来源

部署状态来自三处：

| 来源 | 保存的东西 | 作用 |
|---|---|---|
| `DeployHistoryManager` | Jugg 自己记录的上次部署数据和 overlay id | 判断本地历史。 |
| `JuggDeploymentService` | Android Studio deployment cache entry | 给 deployer / Direct Overlay 提供 checkpoint。 |
| 设备端 `code_cache/.overlay` | 当前应用实际使用的 overlay id 和 overlay 文件 | 判断设备是否还停在预期状态。 |

这三处任一不一致，都可能触发 recover 或 reinstall。

## 状态提交顺序

部署历史只能在整轮部署成功后提交。编译产物会先进入 staging，部署成功后再同时推进 deploy history、文件状态和 overlay checkpoint。

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

## recover 流程

`DeployStateRecover.recoverDeployState()` 负责恢复基线：

```text
recoverDeployState()
  -> clean reinstall: 先 pm clear
  -> 需要 dry deploy: tryDryDeploy()
  -> dry deploy 成功: 不重装
  -> dry deploy 失败 / app updated / clean reinstall: install apks
  -> resetAfterReinstall()
```

`tryDryDeploy()` 先检查 App 是否安装。未安装时返回 `APP_NOT_INSTALLED`。已安装时，Jugg 会先尝试 Direct Overlay 状态检查；结果未知时，再启动 App，等待 deployable，然后跑 dry deploy payload。

```text
tryDryDeploy()
  -> app 未安装: APP_NOT_INSTALLED
  -> DirectOverlayStateChecker.checkRecover()
     -> MATCHED: SUCCESS
     -> MISMATCHED: FAILED
     -> UNKNOWN: 继续 legacy dry deploy
  -> restart app + waitingForDeployable()
  -> run dry deploy payload
```

reinstall 成功后，`DeployFileManager.resetAfterReinstall()` 会清掉 deployed data、resource APK 和 staging 状态。

## Direct Overlay 的 recover 分支

Direct Overlay recover 只在两个条件同时满足时参与：

- 调用方允许 `allowDirectOverlayRecover`。
- `JuggSettings.isEnableDirectOverlayDeploy` 已开启。

命中后，recover 可以直接检查 deployment cache 和设备 overlay。状态匹配时不需要启动 App 做 legacy dry deploy。

direct deploy failed 之后的 retry 会把 `allowDirectOverlayRecover` 关掉。后续 recover 走 legacy dry deploy，并且 redeploy 时也禁用 Direct Overlay。

## retry 如何进入 recover

`DeployRetryHandler` 根据失败原因选择下一步。以下失败会进入 recover 后 redeploy：

| 失败信号 | 处理 |
|---|---|
| overlay id mismatch | recover deploy state 后 redeploy。 |
| class not found | recover deploy state 后 redeploy。 |
| direct deploy failed | 禁用 Direct Overlay recover，legacy recover 后 redeploy。 |

其它失败不一定进 recover。比如 JVMTI class redefine 不兼容会转 HOT_FIX；install `INSTALL_FAILED_INVALID_APK` 会 uninstall 当前 applicationId 后重新 install。

## scoped data 的边界

`JuggDeployTask` 会按 applicationId 和 APK 把 `JuggDeployData` 裁成 scoped data，再交给 deployer transport。这个裁剪结果只能用于一次 transport 调用。

不能用 scoped data 更新全局历史。全局状态只能用原始 `JuggDeployData` 在整轮成功后提交。

## checkpoint 判断

| 场景 | 判断方式 |
|---|---|
| 普通增量部署 | 比对预期 overlay id 和设备端 overlay id |
| Recover | 对比 Jugg history、deployment cache 和设备状态 |
| Direct Overlay | 先确认 cache 存在且设备 overlay id 匹配，再写入新 overlay |
| base install 空 overlay id | 允许 expected overlay id 为空字符串 |

当 cache 缺失、history 为空但 cache 有值、或设备 overlay id 与预期不一致时，Jugg 会把状态视为不可信，进入 recover 或 reinstall。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [部署数据与影响分析](./deploy-data-and-impact.md)
- [Direct Overlay 能力](../capabilities/deploy/direct-overlay.md)
- [Recover 与 Retry 能力](../capabilities/deploy/recover-and-retry.md)
