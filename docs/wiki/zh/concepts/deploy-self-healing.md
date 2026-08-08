---
title: 部署自愈机制
description: 解释增量编译成功后，Jugg 如何通过有限重试、切换生效方式、Recover 和重新安装当前 APK 恢复部署。
status: active
tags:
  - concept
  - deploy
  - recover
---

# 部署自愈机制

增量编译成功后，Jugg 已经得到本轮 class、DEX、资源或 assets 等局部产物。ADB 暂时离线、当前进程无法接收 class、设备 checkpoint 不匹配等部署失败，并不表示这些产物需要重新编译。

部署自愈会保留已经生成的产物，先改变传输条件、生效方式或设备状态。只有这些恢复路径仍不能形成可信结果时，才结束本轮或把 Gradle 回退资格交给 Run 层。

## 恢复范围按失败边界逐步扩大

```text
部署失败
  -> 传输条件可恢复：有限重试
  -> 当前进程无法应用：切换 Hot Fix 或兼容部署
  -> 设备状态不可信：Recover
  -> Recover 无法恢复：重新安装当前 APK
  -> 仍然失败：结束本轮，或由 Run 层决定 Gradle 回退
```

每一步都需要改变导致失败的条件。相同命令在相同状态下持续失败时不会无限重试。

## 传输失败只重试已知可恢复错误

ADB 短暂离线、可恢复的 I/O 错误或部署超时可以继续使用原部署数据。Jugg 会等待设备连接恢复、降低单次 overlay 数量，或在有限次数后扩大到重新安装。

Direct Overlay 在修改 overlay 目录之前失败，可以退回普通 Apply Changes。写入已经开始后失败，设备可能处于半提交状态，不能继续使用原 checkpoint；后续恢复会禁用 Direct Overlay，先清理或重新安装。具体边界见[Direct Overlay 部署机制](./direct-overlay.md)。

## 当前进程无法应用时改变生效方式

class redefine 返回 unmodifiable class、在线替换内部错误或要求 App 重启时，Jugg 会把原本准备在线应用的 class 转为 Hot Fix，重启 App 后加载。

agent 无响应、JVMTI 不可用或设备环境不适合普通 Apply Changes 时，会改走兼容部署。兼容部署重新组织现有 class 和资源产物，不重新分析源码，也不重新执行编译。

## checkpoint 不匹配时先 Recover

增量数据建立在上一轮成功状态上。本地部署历史、Android Studio deployment cache 和设备 overlay ID 指向不同结果时，继续写入会产生无法确认的混合状态。

Recover 会先验证设备是否仍能承接预期差异。状态能够重新对齐时继续本轮部署；App 不存在、被外部覆盖安装或校验失败时，恢复范围扩大到重新安装当前 APK。三处 checkpoint 的具体语义见[部署状态与恢复](./deploy-state-recover.md)。

## 重新安装修复设备状态

重新安装使用当前可信 APK，让设备回到已知起点。安装完成后，Jugg 会重建 deployment cache 和 overlay ID，并重新组织本轮已经编译的 class、资源和 assets。

这类 reinstall 修复的是设备状态，通常不执行 Gradle。用户主动选择的 Clean Reinstall 还会清理 App 数据，适用于需要显式测试干净安装环境的场景。

## Run 层决定是否回到 Gradle

Retry、Hot Fix、兼容部署、Recover 和 reinstall 都无法完成时，部署结果会携带真实失败原因以及是否允许 Gradle 回退。

自动回退开启且所有失败设备都允许时，Run 层会重新执行完整 Gradle 构建和安装。用户取消、设备丢失、安装受系统限制或其它无法安全恢复的失败会直接结束，不伪造成功，也不强行扩大处理范围。

多设备运行中的 Gradle 回退以整轮 Run 为单位。不能只让失败设备进入新的 APK 基线，而让其它设备继续停在旧的增量结果上。

## 相关页面

- [增量部署总览](./deploy-strategy.md)
- [Direct Overlay 部署机制](./direct-overlay.md)
- [兼容部署](./compat-deploy.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [APK 更新与安装](./apk-update-and-install.md)
- [Recover 与 Retry 能力](../capabilities/deploy/recover-and-retry.md)
- [Clean Reinstall 能力](../capabilities/deploy/clean-reinstall.md)
- [Gradle 回退与基线重建](./gradle-fallback-baseline.md)
