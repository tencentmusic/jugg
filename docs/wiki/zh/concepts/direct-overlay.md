---
title: Direct Overlay 部署机制
description: 解释设备未进入 Apply Changes ready 状态时，Jugg 如何直接写入 overlay，并在写入失败时避免留下不可恢复的半提交状态。
status: active
tags:
  - concept
  - deploy
  - direct-overlay
---

# Direct Overlay 部署机制

普通 Apply Changes 需要目标 App 进入可通信的部署状态，再由 Android Studio 的部署通道发送 class 和 overlay。设备尚未 ready、agent 暂时没有响应时，等待在线通道会让本轮已经生成的增量产物无法继续下发。

Direct Overlay 在状态可校验时直接写入 App sandbox 中的 overlay 目录。它只替换文件传输方式；产物分类、App 启停、状态提交、androidTest 和失败恢复仍由同一套增量部署流程负责。

## 为什么不能直接向 overlay 目录复制文件

设备 overlay 是相对上一轮成功状态的完整结果。写入过程中如果先删除旧文件、再复制部分新文件，任何一次 ADB 或 shell 失败都可能留下混合状态：部分文件来自上一轮，部分来自本轮，overlay ID 又无法说明目录实际属于哪次部署。

```text
旧 overlay 状态 A
  -> 删除或覆盖部分文件
  -> 传输中断
  -> 目录内容不再等于 A
  -> 新 overlay ID 尚未提交
  -> 后续部署无法判断当前设备状态
```

因此 Direct Overlay 在写入前先确认设备处于预期 checkpoint，并把新 overlay ID 放到最后提交。失败发生在修改目录之前时，可以回到普通 Apply Changes；目录已经被修改后失败时，必须把现场标记为不可信并进入 Recover。

## Direct Overlay 如何写入

Direct Overlay 需要已有 deployment cache，用它还原目标 APK、现有 overlay 和预期 overlay ID。设备端检查通过后，Jugg 将本轮文件打包，通过 ADB 推送到临时目录，再以目标 App 身份更新 sandbox。

```text
设备未 ready，且调用方允许 Direct Overlay
  -> 读取 deployment cache
  -> 校验设备端 overlay ID
  -> 准备 Apply Changes startup agent
  -> 打包本轮 class 与 overlay 文件
  -> 更新 App sandbox 中的 overlay
  -> 最后提交新 overlay ID
  -> 更新 deployment cache
  -> 由外层流程启动或重启 App
```

写入使用 Android 8.0 及以上设备提供的 App sandbox 和 `run-as` 能力。startup agent 的准备不依赖 App 进程已经在线，因此 Direct Overlay 可以在普通 Apply Changes 尚未 ready 时完成文件下发。

## 它与 Apply Changes 的关系

Direct Overlay 和普通 Apply Changes 使用相同的部署数据、目标 APK 归属与 overlay 状态，只在传输环节不同。

| 环节 | 普通 Apply Changes | Direct Overlay |
|---|---|---|
| 设备前提 | App 已进入在线部署状态 | App 可以未 ready，但 sandbox 和 checkpoint 必须可访问 |
| class 与资源输入 | 同一份 overlay update | 同一份 overlay update |
| 文件写入 | Android Studio 在线部署通道 | ADB push + App 身份写入 sandbox |
| 生命周期动作 | 外层部署流程决定 | 仍由外层部署流程决定 |
| 状态提交 | 成功后更新 cache 与 overlay ID | 成功后更新同一组状态 |

Direct Overlay 不是另一套热修复格式，也不会改变 class 原本属于在线替换还是 Hot Fix。需要重启 App 的产物写入完成后仍会重启，需要重建 Activity 的普通 Hot Reload 仍会执行对应生命周期动作。

## 写入失败后的恢复边界

Direct Overlay 把失败分成两类：

- **写入前失败**：设备状态检查、agent 准备或 payload 构造失败，但 overlay 目录未被修改，可以继续尝试普通 Apply Changes。
- **写入后失败**：脚本已经开始删除或覆盖文件，目录可能处于半提交状态，不能再假设旧 checkpoint 有效。

第二类失败不会立即转回普通 Apply Changes。后续 Recover 会禁用 Direct Overlay，改用启动 App 后的常规状态校验；必要时重新安装 APK 并清理 overlay，重新建立可信基线。

## 使用边界

Direct Overlay 只有在以下条件同时满足时才会参与：

- 功能开关和当前调用方允许；
- 设备尚未进入普通 ready deploy 状态，或失败恢复明确要求尝试这条路径；
- 本轮不是 install，且部署数据非空；
- deployment cache 存在；
- 设备端 overlay ID 与 cache 记录一致；
- App 可通过 `run-as` 访问 sandbox。

状态无法读取时，Jugg 会回到常规校验；状态明确不匹配时进入 Recover。Direct Overlay 不会为了减少等待而绕过 checkpoint。

## 相关页面

- [增量部署总览](./deploy-strategy.md)
- [Apply Changes 中的 class 与 overlay](./apply-changes.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [部署自愈机制](./deploy-self-healing.md)
- [Direct Overlay 能力](../capabilities/deploy/direct-overlay.md)
- [Recover 与 Retry 能力](../capabilities/deploy/recover-and-retry.md)
