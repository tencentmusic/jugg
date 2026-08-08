---
title: Apply Changes 中的 class 与 overlay
description: 解释 Android Studio Apply Changes 如何组合 class 替换与文件 overlay，以及 Jugg 为什么通常会在部署后重建 Activity。
status: active
tags:
  - concept
  - deploy
  - apply-changes
---

# Apply Changes 中的 class 与 overlay

Android Studio 的 Apply Changes 不会重新安装一份完整 APK。它以设备上已经安装的 APK 为起点，把本轮 class 变化和资源文件组织成一次增量更新，再决定是否重建当前 Activity。Jugg 复用这套设备端应用机制，但 class 和 overlay 的输入来自自己的增量编译结果。

理解这条路径可以解释两个常见现象：只修改方法体时为什么不用重装 App，以及 Run 结果显示 Hot Reload 时为什么当前 Activity 仍会重新执行生命周期。

## Apply Changes 使用 APK 基线和局部更新

完整安装会把 APK 中的 DEX、资源和 Manifest 一起替换。Apply Changes 则保留已安装 APK，只发送相对当前部署状态发生变化的内容。

```text
已安装 APK 与 deployment cache
  -> 本轮 class 变化
  -> 本轮资源和 assets overlay
  -> 生成一次 overlay update
  -> 写入设备并更新 overlay ID
  -> 按部署类型保持进程、重建 Activity 或重启 App
```

deployment cache 记录了设备上一次成功安装或 Apply Changes 后的 APK 快照和 overlay ID。新的局部更新必须基于这份快照生成；如果本地记录和设备状态对不上，Jugg 会先进入 Recover，而不是继续叠加差异。

## class 分为在线修改和新增内容

Jugg 在部署前比较新旧 class 结构，并把 class 变化交给 Apply Changes 的两个输入集合。

| class 变化 | Apply Changes 输入 | 生效边界 |
|---|---|---|
| 方法体变化，class 结构保持不变 | modified class | 可以由运行时替换已有 class 实现 |
| 新增 class | new class | 作为新的 DEX 内容加入 overlay，由当前或下一次进程加载 |
| 字段、方法签名、继承或泛型结构变化 | new class / Hot Fix 数据 | 不能依赖当前进程中的 class redefine，需要重启 App 后加载 |
| library dex、multi-dex 等不能稳定在线替换的 class | Hot Fix 数据 | 重启 App 后由运行时加载 |

方法体变化能够进入 modified class，不代表 Jugg 会让当前 Activity 保持不动。class 是否可以在线替换和界面是否需要刷新是两个不同判断：前者决定字节码怎样应用，后者决定用户何时能看到新的代码和资源结果。

## overlay 承载资源、assets 和 DEX 文件

资源增量编译会输出 `resources.arsc`、`res/**` 或 `assets/**` 等局部文件，新增 class 和需要重启后加载的 DEX 也会进入设备 overlay。Apply Changes 按目标 APK 组织这些文件，让 base APK、split APK 和 test APK 的内容写入各自的 overlay 位置。

首次向某个部署基线发送资源 overlay 时，Jugg 会补齐完整资源集合。设备端此前没有可复用的资源 overlay，只发送单个变化文件无法构成完整的新资源视图。后续部署已经有可信资源状态时，才继续叠加本轮差异。

Manifest 和 native lib 不走普通 overlay。它们需要成为安装包内容时，会进入[APK 更新与安装](./apk-update-and-install.md)路径。

## Code Swap 和 Full Swap 的区别

Android Studio 的增量部署可以选择只应用变化，也可以在应用变化后重建 Activity。

| Apply Changes 动作 | class 与 overlay 处理 | 生命周期结果 |
|---|---|---|
| Apply Changes | 写入局部更新，可按需执行 class redefine | 不主动重建 Activity |
| Apply Changes and Restart Activity | 写入同类局部更新，并在完成后重建 Activity | App 进程保留，当前 Activity 重新执行生命周期 |

Jugg 当前对普通、非空且不要求重启 App 的增量部署使用 Apply Changes and Restart Activity。这样资源、布局以及在 Activity 生命周期中读取的新代码能够在当前界面重新加载。因此 Jugg 的 `HOT_RELOAD` 表示本轮仍属于在线增量部署，不表示 Activity 一定保持不变。

warm-up、状态探测或不需要界面刷新的特殊调用可以使用不重建 Activity 的 Apply Changes。需要 Hot Fix、兼容部署或进程级缓存刷新的产物则会重启整个 App，不由 Activity 重建收口。

## 哪些内容必须重启 App

以下变化不能只依赖 Activity 重建：

- class 结构变化或其它需要 Hot Fix 加载的 DEX；
- 兼容部署中只在重启后读取的 overlay；
- APK 根目录的 classpath resource；
- Compose 资源等具有进程级缓存的内容；
- 用户开启“部署后始终重启”，或从 Debug 入口运行。

这些场景仍可使用本轮增量编译产物，但新进程需要重新建立 ClassLoader、Resources 或运行时缓存。具体加载方式见[Jugg Runtime](./jugg-runtime.md)和[兼容部署](./compat-deploy.md)。

## 相关页面

- [增量部署总览](./deploy-strategy.md)
- [部署数据与影响分析](./deploy-data-and-impact.md)
- [APK 更新与安装](./apk-update-and-install.md)
- [Direct Overlay 部署机制](./direct-overlay.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [Code Swap 能力](../capabilities/deploy/code-swap.md)
- [Full Swap 能力](../capabilities/deploy/full-swap.md)
