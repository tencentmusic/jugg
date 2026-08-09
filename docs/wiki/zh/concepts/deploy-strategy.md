---
title: 部署策略
description: 从增量编译产物出发，解释 Jugg 如何选择 Apply Changes、APK 更新、兼容部署、状态恢复和生命周期动作。
status: active
tags:
  - concept
  - deploy
---

# 部署策略

增量编译得到的是本轮变化对应的局部产物，例如 DEX、资源 overlay、assets、Manifest patch 或已经生成的 native lib。部署阶段需要把这些产物应用到最近一次可信 APK 和设备状态上，再决定保持进程、重建 Activity、重启 App 或重新安装。

Jugg 将这套过程称为增量部署。它不固定使用一种热更新方式，而是根据产物的生效边界和设备当前状态组合多条路径。

## 一轮增量部署需要完成哪些判断

```text
本轮增量编译产物
  -> 哪些源码还需要补编译
  -> class、overlay 和 APK 文件如何分类
  -> 是否需要更新并安装 APK
  -> 设备状态能否承接下一轮差异
  -> 使用 Apply Changes、Direct Overlay 或兼容部署
  -> 重建 Activity / 重启 App / 启动新安装的 App
  -> 全部成功后提交部署历史
```

这些判断有明确的先后关系。部署数据必须先完整，设备状态必须先可信，新的 overlay 和历史才能一起前进。任一步失败都不能把本轮结果提前记成下一轮基线。

## 产物决定从哪条路径生效

| 本轮产物 | 主要生效路径 | 用户可见结果 |
|---|---|---|
| 方法体等结构不变的 class | Apply Changes class 更新 | 保留 App 进程，当前实现通常会重建 Activity |
| 新增 class | Apply Changes new class | 随增量 overlay 下发，通常重建 Activity |
| 结构变化 class | Hot Fix DEX | 重启 App 后加载 |
| `res/**`、`assets/**`、`resources.arsc` 等 overlay | Apply Changes 或 Direct Overlay | 重建 Activity，或在需要时重启 App |
| Manifest、配套资源表、已经生成的 native lib | 写回最近的 Gradle APK 并重新签名 | 安装更新后的 APK，再继续本轮剩余增量部署 |
| 兼容设备上的 class 和资源 | 兼容热修复产物 | 重启 App 后加载，不依赖当前进程在线替换 |

具体分类由[部署数据与影响分析](./deploy-data-and-impact.md)说明。Apply Changes 如何组合 class 和 overlay，见[Apply Changes 中的 class 与 overlay](./apply-changes.md)。

## 设备状态决定能否继续叠加差异

增量部署只发送相对上一轮成功状态的变化，因此本地部署历史、Android Studio deployment cache 和设备端 overlay ID 必须指向同一轮结果。

状态匹配时，Jugg 可以继续应用本轮差异。状态未知、App 被外部覆盖安装或 overlay ID 不匹配时，Jugg 会先 Recover；无法恢复时重新安装当前 APK 并重建基线。这个过程修复的是设备状态，通常不需要重新编译工程。

三处 checkpoint 与提交顺序由[部署状态与恢复](./deploy-state-recover.md)负责解释。

## 在线部署、直接写入和兼容部署

设备状态可信后，Jugg 再选择传输与生效方式。

| 路径 | 适用条件 | 改变的内容 |
|---|---|---|
| Apply Changes | App 已进入在线部署状态 | 通过 Android Studio 部署通道应用 class 与 overlay |
| Direct Overlay | App 未 ready，但 cache 和设备 checkpoint 可以校验 | 直接写入相同 overlay，后续生命周期仍由正常部署流程完成 |
| 兼容部署 | JVMTI 不可用、agent 无响应、定制系统需要兼容路径或用户强制开启 | 把在线生效改为 App 重启后加载 |

Direct Overlay 只改变文件传输方式；兼容部署会改变产物的生效时机。两者都继续使用本轮增量编译结果，不等于 Gradle 构建或完整重装。

## Activity 重建和 App 重启是不同边界

Jugg 当前对普通、非空且无需重启 App 的增量数据使用 Apply Changes and Restart Activity。Activity 会重新执行生命周期，但 App 进程继续保留。因此日志中的 `HOT_RELOAD` 表示本轮仍属于在线增量部署，不表示 Activity 一定不重建。

以下内容需要重启整个 App：

- class 结构变化和其它 Hot Fix DEX；
- 兼容部署产物；
- APK 根目录的 classpath resource；
- Compose 资源等具有进程级缓存的内容；
- Debug 或“部署后始终重启”设置。

重启 App 会丢失当前进程内存状态，但可以让 ClassLoader、Resources 和 startup agent 在新进程中重新初始化。

## 部署失败如何扩大恢复范围

部署失败后，Jugg 优先改变导致失败的最小条件：ADB 短暂离线时等待恢复，在线 class 应用失败时切换 Hot Fix，JVMTI 不可用时切换兼容部署，checkpoint 不匹配时 Recover。Recover 仍无法建立可信状态时，才重新安装当前 APK。

只有这些步骤都无法完成，并且失败允许自动回退时，Run 层才会重新执行 Gradle 构建。完整恢复顺序见[部署自愈机制](./deploy-self-healing.md)。

## 增量部署专题

| 页面 | 主要问题 |
|---|---|
| [部署数据与影响分析](./deploy-data-and-impact.md) | 编译产物如何分类，为什么部分源码需要继续补编译 |
| [Apply Changes 中的 class 与 overlay](./apply-changes.md) | class 与资源怎样进入在线增量更新，为什么通常重建 Activity |
| [APK 更新与安装](./apk-update-and-install.md) | Manifest 和 native lib 为什么需要写回 APK 并安装 |
| [Direct Overlay 部署机制](./direct-overlay.md) | 设备未 ready 时怎样直接写入 overlay，并避免半提交状态 |
| [兼容部署](./compat-deploy.md) | 为什么部分设备要改为重启后加载增量产物 |
| [部署状态与恢复](./deploy-state-recover.md) | history、cache 与 overlay ID 如何共同维持设备状态 |
| [部署自愈机制](./deploy-self-healing.md) | Retry、切换生效方式、Recover 和重新安装如何收口失败 |

## 相关页面

- [部署结果说明](../guide/deploy.md)
- [部署能力](../capabilities/deploy/)
- [App 进程内 Jugg runtime](./jugg-runtime.md)
- [Jugg JVMTI Agent](./jugg-jvmti-agent.md)
- [Gradle 回退与基线重建](./gradle-fallback-baseline.md)
