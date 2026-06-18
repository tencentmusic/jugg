---
title: 兼容层
description: 解释 Android Studio 部署能力为什么不是稳定边界，Jugg 如何用自有抽象、按版本实现和显式分发隔离这些差异。
status: active
tags:
  - concept
  - compatibility
---

# 兼容层

Jugg 是 IDE 插件，既依赖 IntelliJ 平台接口，也要复用 Android Studio 插件里的部署能力。兼容层的任务，是把 Android Studio 部署能力的版本差异收敛在一处，让编译和部署主流程只面对 Jugg 自己的抽象。

## 部署能力不是稳定的公开边界

Jugg 需要复用 Android Studio 的安装、Apply Changes、overlay swap 和 Java debugger attach 能力。但这些能力对插件来说不是有稳定契约的公开 API，不同 Android Studio 版本之间会变：

- 部署运行时的类型可能迁移包名。
- 安装方式、安装器、相关 UI 服务等对象可能更换构造方式。
- deployment cache 条目和 overlay id 的内部类型可能变化。
- debugger attach 入口可能迁移。
- 新版 IDE 可能直接移除旧的部署运行时。

主流程直接 import 这些内部类型时，问题不会停在“某个功能不可用”。JVM 在类加载阶段就会解析被引用的类型；升级后的 Android Studio 删除或改名旧类型后，插件会在项目打开阶段抛出 `NoClassDefFoundError` 或 `NoSuchMethodError`，业务还没开始就初始化失败。

## 主流程只面对 Jugg 自有抽象

为避免这种启动期就崩溃的风险，兼容层把版本差异收在内部，主流程只面对 Jugg 自己定义的抽象接口和中立的数据模型：

```text
Jugg 部署主流程
  -> Jugg 自有部署抽象（统一门面 + 中立数据模型）
  -> 按当前 Android Studio 版本选中的实现
  -> Android Studio 部署运行时
```

这套抽象靠几项设计支撑：

- **按版本选择实现**：门面在初始化时读取当前 Android Studio 版本，选定优先实现；真正调用某个兼容能力时再分发到具体版本实现。
- **显式分发而非通用 Proxy**：兼容门面不使用会在启动期反射解析接口全部方法签名的通用动态代理。否则一旦某个版本已移除旧部署类型，启动期的方法签名解析就会先于业务兜底直接终止插件初始化。改用显式分发后，只有在实际调用某个方法时才接触对应版本类型，并在那一刻捕获 API 形态差异错误。
- **中立的数据模型**：主流程拿到的安装会话、overlay id、deployment cache 条目、部署异常等，都是 Jugg 自有的中立类型。Android Studio 的内部类型只在具体版本实现里局部出现，不外泄到部署编排层。
- **自有快照持久化 deployment cache**：deployment cache 的落盘改用 Jugg 自有快照，只保存 APK 路径、overlay 标识与校验等中立字段。重新加载时再经兼容层解析 APK、重建当前版本能识别的 cache 条目，避免把某个 IDE 版本的内部对象当成长期存储契约。
- **平台桩支撑核心模块脱离 IDE 编译**：核心模块不能直接依赖 IDE 运行时，因此另有一层平台桩提供 IntelliJ / Android API 的编译期替身，让核心模块在没有 IDE 的环境下也能编译；命令行入口正是借此在无 IDE 场景复用核心编译能力。

## 版本选择与兜底

兼容层按 Android Studio 版本拆分实现，覆盖 Chipmunk、Giraffe、Hedgehog、Iguana、Meerkat、Narwhal、Otter、Panda、Quail 等版本代号。门面按版本从高到低维护实现列表：

- 当前版本能精确匹配时，使用对应实现。
- 当前 IDE 高于已知最高版本时，先使用最高版本实现。
- 当前 IDE 低于已知最低版本时，退到最低版本实现。

调用具体能力时，先用优先实现；若因 API 形态差异抛出兼容错误，再逐个尝试其他版本实现，全部失败才上报。

Quail 是一个典型边界：它不再携带旧的部署运行时，部署 API 已迁移到新的包，因此这一版的兼容实现独立编写，不继承旧实现链，避免父类或方法签名在启动期解析到已不存在的旧类型。

## 兜底只覆盖 API 形态差异

兼容设计有明确的作用范围：

- 兼容兜底只处理 Android Studio 的 API 形态差异。安装失败、设备离线、payload 不合法这类业务错误不会被吞掉，否则用户会被引向错误的恢复路径；业务失败仍走部署重试、状态恢复或 Gradle 回退。
- 主流程不直接引用 Android Studio 的部署运行时类型；新版本出现差异时，应新增或调整对应版本实现，而不是把版本分支写进部署编排层。
- 平台桩只解决编译期的 API 缺口，不代表运行时一定具备同样行为。运行时能力仍以当前 Android Studio API、设备状态和具体版本实现为准。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [JVMTI Agent](./jvmti-agent.md)
- [兼容性参考](../reference/compatibility.md)
