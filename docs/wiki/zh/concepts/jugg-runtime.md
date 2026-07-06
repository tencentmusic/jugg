---
title: Jugg Runtime
description: 解释 Jugg 放进目标 App 进程里的运行时能力如何服务热修复、兼容部署和 UI 工具。
status: active
tags:
  - concept
  - runtime
  - deploy
---

# Jugg Runtime

Jugg 的编译和部署主要发生在 Android Studio、Gradle 和 ADB 这一侧。但有些事情只能在目标 App 进程里做：增量 DEX 和资源要在 App 启动时接入，JVMTI 能力要由真实设备运行时确认，UI 工具也要读取当前页面的 View 树。Jugg Runtime 就是这层进入 App 进程的能力。它不是 Gradle，也不只是一个 JVMTI agent；它把部署结果、设备兼容判断和运行态工具接起来。

## 为什么需要 App 内运行时

增量部署不会直接安装一个新 APK。它会把本轮变化拆成 DEX、resource overlay、assets、native lib，或者少量必须写回 APK 的文件。部分产物可以通过 Apply Changes 在线生效，部分产物只能在 App 重启后通过新的加载路径生效。IDE 侧可以把文件送到设备上，但要让这些文件进入 ClassLoader、Resources 或 View 树，还得靠 App 进程内的运行时协作。

Jugg Runtime 主要做这些事：

- 在 App 启动时接入热修复 DEX、资源包和 native lib 路径。
- 在目标进程里确认 JVMTI 是否可用，并把结果反馈给部署链路。
- 在 App 内提供 ViewHierarchy 通道，让 CLI/MCP 工具读取页面结构、定位元素、查询 View 属性并执行触控。

没有这层 runtime，Jugg 仍然可以编译和下发文件，但很难稳定回答两个问题：这台设备能不能在线替换？重启后的 App 会不会先加载本轮增量产物？

## Runtime 包含哪些能力

| 能力 | 进入 App 的方式 | 解决的问题 |
|---|---|---|
| Startup agent | 部署后写入 App `code_cache/startup_agents`，App 重启时由系统加载 | 检测 JVMTI、加载 instrumentation、修正启动期运行环境 |
| Hotfix bootstrap | App 使用 Jugg bootstrap Application 启动，再切回原始 Application | 在启动早期插入 DEX、资源和 native lib 路径 |
| Runtime instrumentation | startup agent 加载 instrumentation jar 后对系统类做 hook | 修正 ClassLoader、Resources、ActivityThread 等在线部署相关行为 |
| 兼容部署标记 | App `code_cache` 中的 flag 文件和设备记录 | 标记 JVMTI 可用性，触发后续 compat deploy |
| ViewHierarchy 服务 | App 启动后创建 LocalSocket server | 提供 `layout-dump`、`view-locate`、`view-inspect`、`tap` 的 App 内通道 |

这些能力都运行在用户 App 的真实环境里。部署相关 runtime 关心产物怎么生效；UI 相关 runtime 关心当前界面怎么被观察和操作。

## 部署后如何进入 App 进程

Jugg 不会在安装 APK 时一律推送 runtime。install 阶段通常没有增量部署文件，也不需要立刻准备 startup agent。增量部署完成后，Jugg 才检查目标 App sandbox 中是否已有 Jugg agent 和 Apply Changes agent；缺了再补。

```text
增量部署完成
  -> 检查 App sandbox 中的 startup agents
  -> 必要时 push Jugg agent bundle 到设备临时目录
  -> 通过 run-as 把 agent setup 到 App code_cache/startup_agents
  -> 按本轮部署结果启动或重启 App
  -> 系统加载 startup agent
  -> Jugg Runtime 在 App 进程内初始化
```

agent push 放在部署之后，是为了避开 Apply Changes 首次准备 startup agent 时清理目录的行为。推得太早，后续 Apply Changes 可能把 Jugg agent 删掉。JVMTI 检测也要等 App 重启，因为 startup agent 只有进程启动时才会加载。

## 启动期做了什么

App 启动时，Jugg Runtime 先处理运行环境，再把控制权交还给原始 App。

```text
startup agent 被加载
  -> 尝试取得 JVMTI / JNI
  -> 写入 available 或 not-available 标记
  -> 加载 Jugg instrumentation jar
  -> hook Application、AppComponentFactory、ResourcesManager、ActivityThread
  -> bootstrap Application 接入热修复产物
  -> 创建原始 Application 并调用原始生命周期
  -> 初始化 ViewHierarchy server
```

这里有两个时机需要分清。startup agent 进入得更早，用来检测 JVMTI 并安装必要 hook。bootstrap Application 在原始 Application 前运行，负责补齐 DEX、资源和 native lib 的加载路径，然后再创建并替换回用户工程自己的 Application。

为了减少侵入感，bootstrap 会把原始 Application 和 AppComponentFactory 恢复到运行时对象里，也会迁移已注册的 ActivityLifecycleCallbacks。用户代码最终看到的仍然是自己的 Application。

## 热修复和资源如何生效

兼容部署、结构变化 class、已加载 class 这些场景不能依赖在线类替换。App 重启后，Jugg Runtime 会让新产物排在旧产物前面。

- 新 DEX 接入 ClassLoader 搜索路径。
- 新资源包接入 Resources / AssetManager。
- native lib 路径进入 so 搜索路径。
- 必要时根据兼容 flag 修正定制系统上的 DEX 路径。

普通热重载仍然优先使用 Apply Changes / JVMTI 在线替换。只有本轮产物或设备状态要求重启后生效时，runtime 才承担热修复加载职责。Jugg Runtime 不会把所有变更都改慢；它只在在线路径不可靠时提供另一条生效路径。

## 和兼容部署的关系

兼容部署依赖 Jugg Runtime 的设备反馈。startup agent 启动后会写入 JVMTI 可用或不可用标记；部署链路读到不可用标记后，记录当前 app/device 组合，后续部署直接转入兼容路径。

```text
App 重启
  -> Runtime 检测 JVMTI
  -> 写入 not-available flag
  -> 部署链路记录 compat device
  -> 后续本设备跳过在线热重载
  -> 增量产物改走重启后生效的热修复路径
```

HarmonyOS 等定制系统上的 DEX 路径修复 flag 只表示需要修正加载路径，不表示 JVMTI 不可用。只有明确的 not-available 标记，或部署失败链路确认属于 JVMTI 兼容问题时，才会把设备记录为 compat device。

## 和 UI 工具的关系

Jugg 的 UI 工具不是解析截图，也不是默认走 uiautomator。目标 App 启动后，Jugg Runtime 会初始化 App 内 ViewHierarchy 服务；IDE/CLI 侧通过 ADB 转发连接到这个 LocalSocket，再请求当前页面结构、元素位置、View getter 属性或触控动作。

```text
layout-dump / view-locate / view-inspect / tap
  -> 等待设备和 App 可观察
  -> 连接 App 内 ViewHierarchy LocalSocket
  -> 在主线程读取 View 树或执行触控
  -> 返回 HTML 证据、bounds、属性值或操作结果
```

这条通道读到的是 App 内实时 View 树，所以能返回 bounds、density、隐藏节点属性等截图拿不到的信息。边界也很直接：App 不在前台、设备不可交互、runtime server 未初始化或 socket 不可用时，公开工具不会自动切换到 uiautomator。

## 边界与代价

Jugg Runtime 解决的是运行中怎么生效、怎么检测、怎么观测。它不解决构建问题。构建脚本变化、依赖变化、注解处理器结果不可信、APK 基线过期时，仍然需要回到 Gradle。

使用 runtime 时还要记住几条限制：

- startup agent 只有 App 重启后才会加载；首次检测 JVMTI 需要一次启动时机。
- install 通常不会触发部署后补 push agent，不能用 install 后缺 agent 判断 runtime 异常。
- 32 位和 64 位进程需要匹配不同 agent so；选错会导致 agent 加载失败。
- 可选 hook 会受 Android 版本或厂商实现影响；Jugg 会尽量收集失败信息，不会把所有 hook 失败都升级为部署失败。
- ViewHierarchy server 依赖目标 App 进程在线且前台可观察；socket 不可用时，需要先恢复 App 状态。

这些限制让 Jugg Runtime 更像一层运行态协作机制，而不是万能旁路。它把设备差异和 App 运行状态纳入部署判断，让 Jugg 可以在速度和结果可信之间切换。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [兼容部署](./compat-deploy.md)
- [JVMTI Agent](./jvmti-agent.md)
- [布局 dump 与 UI 证据](./layout-dump-and-ui-evidence.md)
- [UI 检查](../guide/ui-inspection.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
