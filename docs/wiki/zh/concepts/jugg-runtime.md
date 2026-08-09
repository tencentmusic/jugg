---
title: App 进程内 Jugg runtime
description: 解释 Jugg 注入目标 App 进程的 runtime 如何接入构建产物、修正运行环境差异，并为 UI 工具提供 App 内服务。
status: active
tags:
  - concept
  - runtime
  - deploy
---

# App 进程内 Jugg runtime

Jugg 的编译、部署和流程决策主要发生在 Android Studio、Gradle 和 ADB 一侧，但 ClassLoader、Resources、Application 生命周期和当前 View 树的真实状态只存在于目标 App 进程。Jugg 因此会把一组运行组件放进 APK，在 App 启动和运行期间接入部署产物、修正运行环境，并为 UI 工具提供进程内服务。

本文将这些组件统称为 **App 进程内 Jugg runtime**。这里的 runtime 特指进入目标 App 进程的部分，不是 Kotlin 编译器运行环境，也不是 IDE 插件内负责接收命令和编排任务的服务。

## 为什么有些工作必须在 App 进程内完成

完整 Gradle 构建会把代码、资源和 native lib 打包进 APK，Android 再根据安装包建立 ClassLoader、Resources 和 Application。Jugg 增量运行只生成并下发本轮局部产物时，文件到达设备并不表示它已经进入这些运行时对象。

App 进程外可以生成和传输文件，却无法直接判断增量 DEX 是否已经进入当前 ClassLoader、资源 overlay 是否污染了其它 package 的 AssetManager，或者页面上正在显示哪一棵 View 树。这些工作需要在真实 App 环境中完成。

| App 内职责 | 为什么需要进入进程 | 用户可见结果 |
|---|---|---|
| 修正运行环境差异 | 需要读取或调整 ClassLoader、Resources、ActivityThread 等真实对象 | 避免部署后代码未加载、资源异常或特定系统组合下的运行时崩溃 |
| 接入增量产物 | 重启后的新进程需要重新建立代码、资源和 native lib 加载路径 | 结构变化或兼容部署产物在 App 启动后生效 |
| 提供 ViewHierarchy 服务 | 当前 View、Compose 节点和属性只存在于 App 进程 | `layout-dump`、`view-locate`、`view-inspect` 和 `tap` 能读取或操作真实页面 |

## Jugg runtime 如何无侵入地进入 APK

业务工程不需要显式依赖 Jugg SDK，也不需要修改自己的 Application。Jugg 发起会生成 APK 的 Gradle 构建时，会通过本次构建调用附加的 init script，把 runtime 加入目标 application variant，并调整构建生成的合并 Manifest。工程中的 Gradle 配置、Manifest 源文件和 Application 实现保持不变。

```text
Jugg 发起生成 APK 的 Gradle 构建
  -> 通过本次 Gradle 调用注册 runtime 注入
  -> 将 Jugg runtime 加入目标 application variant
  -> 保存合并 Manifest 中的原始 Application 和 AppComponentFactory
  -> 把本轮 APK 的启动入口替换为 Jugg runtime
  -> Gradle 将 runtime 打包进 APK
  -> App 启动后恢复原始入口和生命周期
```

处理合并 Manifest 时，Jugg 会记录原始 Application 和 AppComponentFactory。App 启动后，runtime 先完成运行环境初始化，再创建原始对象并继续执行原有生命周期。release 混淆构建还会保留这些启动入口，避免它们在打包时被移除。

> [!NOTE]
> 这里的“无侵入”是指业务工程无需修改源码、Manifest 源文件或 Gradle 配置。Jugg 会有意调整目标 variant 的构建依赖和合并 Manifest 产物，否则 runtime 无法进入最终 APK。

## App 启动时如何建立运行环境

Jugg runtime 有两个需要区分的启动时机。startup agent 由系统在进程启动时加载，用于确认 JVMTI 能力并安装必要的 Framework hook；runtime 的启动入口则在原始 Application 之前工作，用于准备增量加载路径并恢复业务工程自己的启动对象。

```text
App 进程启动
  -> 必要时系统加载 startup agent
  -> 检测 JVMTI 并安装运行环境修正
  -> Jugg runtime 初始化代码、资源和 native lib 加载路径
  -> 创建并恢复原始 Application / AppComponentFactory
  -> 执行原始 Application 生命周期
  -> 初始化 App 内 ViewHierarchy 服务
```

恢复过程不只是重新调用一次 `Application.onCreate()`。Jugg 还会把 Framework 中指向临时启动对象的引用换回原始 Application，并迁移已经注册的 ActivityLifecycleCallbacks，使业务代码继续面对自己的 Application 实例。

## 修正运行环境差异和运行时崩溃

Android Studio、Android 版本和厂商系统对 Apply Changes 的处理并不完全一致。Jugg runtime 位于真实 App 进程，可以根据实际对象状态只修正命中的问题，而不需要为所有设备切换同一套部署策略。

### 增量 DEX 没有进入 ClassLoader

部分定制系统会提前初始化 ClassLoader。Apply Changes 已经把 DEX 写入 App 缓存目录时，当前 ClassLoader 的搜索路径仍可能缺少这些文件。Jugg runtime 会对比增量 DEX 与当前 dex elements；确认缺失后，再补齐 DEX 加载路径。判断结果会留在 App 缓存中，避免每次启动重复扫描。

### overlay 进入了错误的资源环境

Apply Changes overlay 应用于宿主 App 资源，但 WebView provider 等独立 package 也会在宿主进程创建自己的 AssetManager。如果宿主 overlay 被带入这些资源环境，provider 初始化可能因资源 package ID 冲突而失败。

Jugg runtime 会识别当前 Resources 对应的 APK。宿主资源继续保留 overlay，非宿主资源环境则移除这条 overlay，避免局部资源更新扩大成其它组件的初始化异常。

### Android 版本与 Apply Changes 行为不匹配

Android 15 与较旧 Android Studio 组合中，Apply Changes 可能已经更新资源，却没有触发完整的资源刷新和 Activity 重建。Jugg runtime 会在命中该组合时补发 ApplicationInfo 更新，并按本轮部署要求重建 Activity，使页面读取新的资源状态。

runtime 还会为 APK 根目录的 classpath resource 提供 overlay-first 查找。未命中增量文件或发生读取异常时继续执行原始 ClassLoader 查询，避免辅助兼容逻辑截断 App 原有资源加载。

## 增量产物如何在 App 内生效

普通方法体修改仍优先通过 Apply Changes 和 JVMTI 在线替换。结构变化 class、兼容部署产物或具有进程级缓存的资源需要重启 App；新进程启动时，Jugg runtime 再把对应 DEX、资源或 native lib 路径接入运行环境。

这一过程只是 App 内运行机制的一部分。产物为什么转入兼容路径、部署数据如何变化以及何时重启，见[兼容部署](./compat-deploy.md)；在线替换与 Activity 重建的边界见[Apply Changes 中的 class 与 overlay](./apply-changes.md)。

## 为 UI 工具提供 App 内服务

原始 Application 启动后，Jugg runtime 会初始化 ViewHierarchy LocalSocket 服务。IDE/CLI 侧通过 ADB 转发连接该服务，在 App 主线程读取实时 View 树、查询 View 属性或执行触控。

```text
layout-dump / view-locate / view-inspect / tap
  -> 检查目标 App 是否在线并处于可观察状态
  -> 连接 App 内 ViewHierarchy 服务
  -> 读取当前 View 树或执行触控
  -> 返回页面结构、bounds、属性值或操作结果
```

这条通道直接读取 App 内状态，不依赖截图推断，也不会在 socket 不可用时自动切换到 uiautomator。节点范围、Compose 支持和工具边界见[布局 dump 与 UI 证据](./layout-dump-and-ui-evidence.md)。

## 失败收口与边界

- App 内热修复加载当前要求 Android 8.0 / API 26 及以上；更低版本不会初始化对应加载逻辑。
- runtime 自身发生变化时，需要通过完整 Gradle 构建和安装更新 APK。普通增量部署只能复用当前 APK 已包含的 runtime。
- JVMTI 不可用时，部署链路会记录设备状态并转入兼容部署，不会持续尝试当前设备无法完成的在线替换。
- ViewHierarchy 初始化失败会记录原因并放弃 App 内 UI 服务，不会阻止原始 Application 继续启动；使用 UI 工具前仍需保证 App 在线且前台可观察。
- 构建脚本、依赖、注解处理器结果或 APK 基线不可信时，必须回到 Gradle。App 内 runtime 不能修复构建阶段缺失的产物。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [Apply Changes 中的 class 与 overlay](./apply-changes.md)
- [兼容部署](./compat-deploy.md)
- [Jugg JVMTI Agent](./jugg-jvmti-agent.md)
- [布局 dump 与 UI 证据](./layout-dump-and-ui-evidence.md)
- [UI 检查](../guide/ui-inspection.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
