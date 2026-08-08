---
title: 部署失败如何恢复
description: 解释增量编译成功后，Jugg 如何通过有限重试、切换生效方式、Recover 和重新安装当前 APK 恢复部署。
status: active
tags:
  - concept
  - deploy
  - recover
---

# 部署失败如何恢复

增量编译成功后，Jugg 已经得到本轮 class、DEX、资源或 assets 等局部产物。部署失败时，这些产物通常仍然有效，恢复流程会先修复传输条件、运行时生效方式或设备状态，再决定是否放弃本轮增量结果。

Manifest patch、已经生成的 native lib 等内容写回 APK 并重新安装，属于正常部署路径。本页讨论的是部署过程出现失败后，Jugg 如何继续使用已经生成的产物。

```text
增量编译成功
  -> 传输暂时失败：在条件变化后重试
  -> 当前进程无法接收：改为 Hot Fix / 兼容部署
  -> 设备状态对不上：Recover
  -> Recover 无法恢复：重新安装当前 APK
  -> 仍然失败：结束本轮，或把 Gradle 回退资格交给 Run 层
```

## 增量产物先保留

部署失败不代表编译产物错误。ADB 离线、在线类替换失败、设备 checkpoint 不匹配等问题都发生在产物生成之后，重新编译无法直接改变这些失败条件。

Jugg 因此保留本轮部署数据，先根据错误信号选择恢复动作。只有恢复后的产物仍无法形成可信结果，并且失败允许扩大处理范围时，Run 层才会考虑完整 Gradle 构建。

## 传输暂时失败：在条件变化后重试

ADB 短暂离线、可恢复的 I/O 错误或部署超时，可以继续使用原有部署数据。重试前会改变导致失败的条件，例如等待设备重新连接、降低单次下发的数据量，或者先恢复设备状态。

重试只处理已知且可恢复的错误，并设置次数上限。相同条件持续失败时，流程会进入下一种恢复方式或结束当前 Run，避免不断重复同一条命令。

## 在线替换失败：改为重启后生效

部分 class 在当前进程中无法通过 JVMTI 在线替换，设备也可能出现 agent 无响应或运行时兼容问题。此时 class、DEX 和资源产物本身仍然可以使用，Jugg 会调整它们的生效方式：

- class redefine 失败时，将在线 class 变化转为 Hot Fix；
- JVMTI 或设备环境不适合普通 Apply Changes 时，改走兼容部署；
- 新产物在 App 重启后由运行时加载。

这条路径会中断当前页面和内存状态，但仍然使用本轮增量编译结果。兼容判断和产物转换见[兼容部署](./compat-deploy.md)。

## checkpoint 不匹配：先 Recover

增量部署只下发相对上一轮成功结果的差异，因此本地部署历史、Android Studio deployment cache 和设备 overlay checkpoint 必须指向同一轮状态。

```text
本地按状态 A 生成差异
  -> 设备实际停在状态 B
  -> 继续叠加会得到无法确认的混合状态
  -> Jugg 停止本轮写入并进入 Recover
```

Recover 会先验证设备是否仍能承接预期差异。checkpoint 能够重新对齐时继续部署；校验失败、App 被外部覆盖或根本没有安装时，恢复范围扩大到重新安装。

三处 checkpoint 和校验过程见[部署状态与恢复](./deploy-state-recover.md)。

## Recover 无法恢复：重新安装当前 APK

重新安装使用最近一次可信 APK，把设备恢复到已知起点。安装完成后，Jugg 会重建设备 checkpoint，并按需要重新下发已编译的 class、资源和 assets。

这个过程修复的是设备状态，通常不会重新编译工程。Clean Reinstall 则是用户明确要求清理 App 数据并重装的操作，与普通 Recover 触发的重新安装用途不同。

## 仍然失败：结束本轮或交给 Gradle

Retry、Hot Fix、兼容部署和 Recover 都无法完成部署时，本轮会返回真实失败原因以及是否允许 Gradle 回退。

自动回退开启且结果允许时，Run 层可以从编译阶段重新开始。多设备运行会汇总全部设备结果，只有整组结果都允许回退时才会统一切换；用户取消、设备丢失或无法安全恢复的安装错误会直接结束当前 Run。

完整构建的触发条件和结果见[什么时候需要完整 Gradle 构建](./full-gradle-build.md)。

## 相关页面

- [部署结果说明](../guide/deploy.md)
- [部署策略](./deploy-strategy.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [兼容部署](./compat-deploy.md)
- [Recover 与 Retry](../capabilities/deploy/recover-and-retry.md)
- [Clean Reinstall](../capabilities/deploy/clean-reinstall.md)
- [什么时候需要完整 Gradle 构建](./full-gradle-build.md)
