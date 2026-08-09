---
title: 兼容部署
description: 解释 Jugg 为什么需要 compat deploy，以及它如何从在线热重载切换到更稳妥的热修复路径。
status: active
tags:
  - concept
  - deploy
  - compat
---

# 兼容部署

日常 Run 会优先走增量编译和 Apply Changes。方法体改动可以通过 JVMTI 在线替换，资源和 assets 通过 overlay 生效。这条路径要求设备运行时、ClassLoader 初始化时机、Apply Changes 通信和资源加载路径都正常。某台设备不满足这些条件时，Jugg 会把本轮增量产物改走兼容部署：重启 App，让产物在下一次启动时生效。

## 在线热重载为什么会失效

在线热重载要求运行中的 App 能接收增量 DEX 或 overlay，并通过 JVMTI 把可替换 class 应用到当前进程。真实设备上，这件事并不总是成立。

- 部分设备或系统版本取不到 JVMTI 能力，class 无法在线替换。
- 定制系统可能提前初始化 ClassLoader，增量 DEX 的搜索路径没有接进去。
- Apply Changes agent 可能没有响应、通信超时，或在线 redefiner 返回不可恢复错误。
- App 自身有资源加载、类加载或热修复 hook，普通 overlay 或在线替换后的结果不稳定。

这些情况不等于增量编译失败。编译产物仍然可以下发，只是不能再指望当前进程直接接住这些产物。兼容部署做的事很简单：把生效点从当前进程挪到下一次 App 启动。

## 兼容部署和普通热重载的差异

| 对比项 | 普通热重载 | 兼容部署 |
|---|---|---|
| class 生效方式 | 结构未变的 class 尝试在线替换 | 转为重启后生效的热修复 DEX |
| 资源 / assets | 优先通过 overlay 在线更新 | 改走兼容热修复路径，不依赖在线 overlay 生效 |
| 运行时依赖 | 依赖 JVMTI、Apply Changes agent 和进程可部署状态 | 依赖 App 重启后的 DEX、资源和 native lib 加载 |
| 用户可见结果 | 尽量不重启 App，必要时重启 Activity | 通常需要重启 App |
| 适用场景 | 设备可以在线替换，改动边界较小 | JVMTI 不可用、agent 无响应、用户强制开启或设备表现不稳定 |

兼容部署不是完整 Gradle 重装。它仍然使用 Jugg 的增量编译结果和部署历史，只是把原本准备在线应用的产物换成更保守的生效方式。

## 什么时候会进入兼容部署

进入兼容部署有两种方式：Jugg 自动判断，或者用户为某台设备手动开启。

自动判断来自运行时和部署失败信号。Jugg agent 在 App 启动时检测 JVMTI 是否可用，并在 App 缓存目录写入可用或不可用标记。部署链路读到不可用标记后，会记录当前 app 和设备组合；后续部署直接进入兼容路径，不再每一轮都尝试不可用的在线替换。

Retry 链路也会做这件事。遇到 agent 无响应、部署超时，或 Jugg 输出 `fallback to compat deploy` 之类的信号时，它会先检查是否属于 JVMTI 兼容问题；确认后，本轮会重新组织为兼容部署。

用户也可以在 More Options 中为指定设备开启：

```text
Force use compat deploy for <device>
```

这个设置按设备生效。开启或关闭后，下一次运行会重新安装目标 App，让设备和本地部署历史重新对齐。

## 本轮产物如何改走兼容路径

兼容部署不会重新分析源码变更。它接收已经生成好的增量部署数据，再把“在线生效”的部分改成“重启后生效”。

```text
增量编译产物
  -> 生成本轮部署数据
  -> 发现当前设备需要兼容部署
  -> 可在线替换的 class 转为热修复 DEX
  -> 资源 / assets overlay 改成兼容资源产物
  -> 写入兼容启用信号
  -> 下发产物并重启 App
  -> App 启动时优先加载本轮新产物
```

变化主要在生效时机。普通热重载会尽量把改动送进当前进程；兼容部署让 App 下次启动时加载新的 DEX、资源或 native lib 路径。结构变化 class、已加载 class、JVMTI 不可用设备，都更适合这条路径。

## Jugg agent 在兼容判断中的作用

Jugg agent 不是用来强行让所有设备热重载。它做的是一次真实检测：这台设备、这个 App、这次启动，JVMTI 到底能不能用。

```text
增量部署完成
  -> 必要时把 Jugg agent 准备到 App sandbox
  -> 重启或启动 App
  -> startup agent 被系统加载
  -> 写入 JVMTI available / not-available 标记
  -> Jugg 读取标记
  -> 不可用时记录 compat device，并转入兼容部署
```

agent push 放在部署之后，是因为 Android Studio Apply Changes 首次准备 startup agent 时可能清理 App 目录下已有 agent。Jugg 如果推得太早，后续部署动作可能把它删掉。JVMTI 检测也必须等 App 重启；startup agent 只有进程启动时才会加载。

还有一个容易误判的点：部分定制系统上的 DEX 路径修复信号只是修正加载路径，不表示 JVMTI 不可用。只有明确写出不可用标记，或部署失败链路确认属于兼容问题时，才按兼容设备处理。

## 状态恢复和重试如何收口

兼容部署仍然要遵守部署状态。设备状态未知时，Jugg 会先确认本地 history、部署缓存和设备端 overlay checkpoint 是否对得上，不会继续叠一轮新产物。状态不可信时先 recover；recover 失败或 App 不存在时，再重新安装。

```text
部署失败或设备状态不确定
  -> 判断是否可以原地 retry
  -> 检测是否存在 JVMTI 兼容问题
  -> 必要时切换 compat deploy payload
  -> 状态不匹配先 recover 或 reinstall
  -> 成功后提交新的部署历史
```

所以，手动开启或关闭某台设备的兼容部署后，下一次 Run 会重新安装。兼容模式改变了产物的应用方式，必须先把设备和本地历史放回同一条基线上。

## 代价和边界

兼容部署的主要代价是重启。页面状态、内存状态和部分调试现场会被打断；在普通设备、普通方法体修改场景下，它通常比热重载慢。

它也不能替代 Gradle fallback。构建脚本变化、依赖变化、注解处理器结果不可信、完整 APK 基线过期时，仍然需要回到 Gradle。兼容部署处理的是另一类问题：增量产物已经生成，但当前设备不适合在线接收这些产物。

不要把所有设备长期打开兼容部署。比较合适的做法是：某台设备反复出现 agent 无响应、JVMTI 不可用、资源或类加载表现不稳定时，为这台设备开启；设备更换、系统升级或环境恢复后，再关闭并重新建立部署基线。

## 排查入口

| 现象 | 第一跳 |
|---|---|
| Jugg 输出 `fallback to compat deploy` | 查看[设备兼容部署](../guide/compat-device.md)，确认是否要为当前设备长期开启 |
| `MISSING_AGENT_RESPONSES` / `AGENT_ATTACH_FAILED` | 查看[部署问题排查](../troubleshooting/deploy.md#jvmti-agent-无响应) |
| 开启兼容部署后仍失败 | 先执行 Clean Reinstall 或 Gradle 安装，确认 APK 基线和设备状态可信 |
| 兼容部署后变慢 | 检查是否误把普通设备长期设为兼容模式 |
| 资源或类加载结果和预期不一致 | 对照 Gradle 安装结果，区分设备兼容问题和代码/构建结果问题 |

## 相关页面

- [设备兼容部署](../guide/compat-device.md)
- [部署策略](./deploy-strategy.md)
- [Apply Changes 中的 class 与 overlay](./apply-changes.md)
- [Direct Overlay 部署机制](./direct-overlay.md)
- [App 进程内 Jugg runtime](./jugg-runtime.md)
- [Jugg JVMTI Agent](./jugg-jvmti-agent.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [HarmonyOS 兼容部署](../capabilities/deploy/harmonyos-compat.md)
- [Recover 与 Retry](../capabilities/deploy/recover-and-retry.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
