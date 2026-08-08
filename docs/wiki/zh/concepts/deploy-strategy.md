---
title: 部署策略
description: 从完整 APK 安装与局部产物部署的差异出发，解释 Jugg 如何选择 APK 更新、Apply Changes、Activity 重建、App 重启和状态恢复。
status: active
tags:
  - concept
  - deploy
---

# 部署策略

一次标准 Android Run 会安装完整且已签名的 APK，系统随后启动新的应用进程。Jugg 增量编译得到的则是本轮变化对应的局部产物，例如 class、DEX、资源 overlay、assets、Manifest patch 或已经生成的 native lib。

部署阶段需要把这些不同形态的产物组合成可运行结果。它先判断安装包内容是否需要更新，再判断当前 Activity 或 App 进程需要怎样刷新；设备状态无法承接本轮变化时，还要先恢复部署基线。

```text
本轮编译产物
  -> 是否需要修改 APK
  -> 设备能否承接增量部署
  -> 下发 class 与 overlay
  -> 重建 Activity / 重启 App / 启动新安装的 App
  -> 成功后提交部署状态
```

## 完整安装和增量部署使用不同输入

Gradle 构建会生成一组完整 APK。部署阶段直接安装这些 APK，并以安装结果作为设备的新基线。

增量编译保留最近一次 Gradle APK，只生成本轮变化。普通 class、资源和 assets 可以作为增量数据下发；Manifest、native lib 等需要成为安装包内容的文件，则写回基线 APK 并重新签名。一次增量 Run 因此可以包含 APK 更新和运行时增量部署两个连续步骤。

## 先判断 APK 是否需要更新

| 本轮产物 | APK 处理 | 用户可见结果 |
|---|---|---|
| Gradle 构建生成的完整 APK | 直接安装完整 APK | App 重新安装并启动，设备获得新的完整基线 |
| 可增量 patch 的 Manifest、已经生成的 native lib | 写入最近一次 Gradle APK 并重新签名 | 安装更新后的 APK，无需重新执行完整 Gradle 构建 |
| 普通 class、资源 overlay、assets | 保持 APK 基线，生成增量部署数据 | 通过 Apply Changes 或 overlay 通道下发 |
| 同时包含 APK 条目和普通增量产物 | 先更新并安装 APK，再重新组织剩余增量数据 | 安装包和运行时内容在同一轮完成对齐 |

APK 更新依赖可用的签名配置。Manifest 删除、完整 merge 规则变化、C/C++ 源码编译或 ABI 与 packaging 配置变化，仍需要 Gradle 重新生成对应产物。具体边界见 [Android Manifest 编译](./incremental-compile/manifest.md)和[assets 与 native lib](./incremental-compile/assets-native.md)。

## 再判断运行态需要刷新到哪一层

APK 处理完成后，Jugg 根据 class 结构、overlay 内容、设备兼容性和运行配置决定生命周期动作。

| 部署数据 | 生效方式 | 用户看到什么 |
|---|---|---|
| 可在线替换的 class、普通资源或 assets overlay | Apply Changes | App 进程继续运行，当前实现通常会重建 Activity 以重新加载界面和资源 |
| class 结构变化、需要进程重新加载的 overlay、Compose 资源等 | Hot Fix 后重启 App | 页面和进程内状态重新建立，新产物在新进程中生效 |
| 当前设备需要兼容部署 | 将在线产物转换为兼容热修复数据并重启 App | 保留增量编译结果，改由下次进程启动加载 |
| Debug、用户设置始终重启，或平台兼容处理需要新进程 | 部署完成后额外重启 App | 调试器或运行时组件连接到新的进程 |

`Hot Reload` 是 Jugg 对本轮结果的分类，不等于 Activity 一定保持不变。当前实现对非空、无需重启 App 的增量数据使用 Apply Changes and Restart Activity；Activity 会重新执行生命周期，而 App 进程继续保留。空部署和 warm-up 探测使用不重建 Activity 的 Apply Changes。

## 设备状态决定能否继续叠加差异

增量数据以设备上一轮成功状态为起点。部署前，Jugg 会对照本地部署历史、Android Studio deployment cache 和设备端 overlay checkpoint。

状态匹配时继续下发本轮差异。状态未知、App 被外部覆盖安装或 checkpoint 不匹配时，Jugg 先执行 Recover；校验仍然失败时，重新安装当前 APK 并重建部署状态。这个过程修复的是设备基线，通常不需要重新编译工程。

设备尚未进入在线 Apply Changes 状态但 checkpoint 可以校验时，Jugg 还可以使用 Direct Overlay 写入增量文件。Direct Overlay 只改变传输方式，后续启动、重启和状态提交仍由同一套部署流程完成。状态模型见[部署状态与恢复](./deploy-state-recover.md)。

## 同一轮变化如何组合生效

源码、资源、Manifest 和 native lib 可以在同一轮发生变化。部署顺序需要保证安装包、运行时内容和设备 checkpoint 一起前进：

```text
生成完整部署数据
  -> 写回需要更新的 APK 条目并重新签名
  -> 安装更新后的 APK，恢复设备部署基线
  -> 重新读取待部署的 class 与 overlay
  -> Apply Changes 或 Hot Fix
  -> 完成目标设备部署后提交本轮历史
```

多 APK 工程还会按 base、split、test APK 的真实归属裁剪每次传输数据；这些局部数据只用于对应 APK，全局历史始终使用完整部署结果推进。多设备运行会逐台部署，再由 Run 层汇总结果并决定是否整体回到 Gradle。

## 部署失败如何收口

失败处理会优先改变导致失败的最小条件：ADB 短暂离线时等待恢复，在线类替换失败时转 Hot Fix，JVMTI 或 agent 不兼容时转兼容部署，checkpoint 不匹配时先 Recover。只有这些路径无法形成可信结果，并且失败允许自动回退时，整轮 Run 才会改走 Gradle。

各级恢复行为见[部署失败如何恢复](./deploy-failure-recovery.md)。

## 相关页面

- [部署结果说明](../guide/deploy.md)
- [重启 App](../guide/restart-app.md)
- [部署能力](../capabilities/deploy/)
- [Restart 能力](../capabilities/deploy/restart.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [兼容部署](./compat-deploy.md)
- [Recover 与 Retry](../capabilities/deploy/recover-and-retry.md)
- [Full Swap](../capabilities/deploy/full-swap.md)
- [Direct Overlay](../capabilities/deploy/direct-overlay.md)
- [部署失败如何恢复](./deploy-failure-recovery.md)
- [什么时候需要完整 Gradle 构建](./full-gradle-build.md)
