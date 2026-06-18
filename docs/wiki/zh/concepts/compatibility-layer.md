---
title: 兼容层
description: 说明 Jugg 如何隔离 Android Studio deploy API 差异，避免主流程绑定具体 IDE 版本。
status: active
tags:
  - concept
  - compatibility
---

# 兼容层

Jugg 是 IDE 插件，既依赖 IntelliJ 平台接口，也会调用 Android Studio 插件里的部署能力。Android Studio 的 deploy 类型、包名、方法签名和运行行为会随版本变化。Jugg 用兼容层把这些差异收在主流程之外。

## 为什么需要兼容层

Jugg 需要调用 Android Studio 的安装、Apply Changes 通道和设备侧部署接口。这些接口并不总是稳定公开 API。版本变化可能带来包名、类名、方法签名或运行行为差异。

如果主流程直接依赖这些实现，升级 Android Studio 后容易出现启动失败或部署失败。兼容层把这些差异集中到版本适配实现中，主流程只调用统一接口。

## 兼容方式

部署主流程面向统一接口，例如安装、Apply Changes、overlay、deployment cache 和 Java debugger attach。不同 Android Studio 版本在 `deploy_compat/*` 下提供各自实现。

```text
Jugg deploy flow
  -> IAsDeployerCompat
  -> 当前 IDE 版本对应实现
  -> Android Studio deploy runtime
```

Android Studio 同一个大版本也可能有小版本差异。适配实现需要处理 IDE runtime 类型迁移、install session、overlay/cache entry wrapper 和 debugger attach API 差异。

## 命令行与平台桩

核心 `main` 模块不能直接依赖 IDE runtime。`platform_compat/base_api` 提供 IntelliJ / Android API mock，让核心逻辑可以脱离 IDE 编译；命令行入口复用可运行的核心能力和 Gradle 编译客户端。

## 隐形约束

- 主流程不要直接引用具体 Android Studio deploy runtime 类型。
- 新版本 API 差异应落在 `deploy_compat/*`，而不是扩散到编译或部署编排层。
- deployment cache 持久化使用 Jugg 自有 snapshot，避免把 IDE 内部类型作为长期存储契约。
- Debug attach 也走兼容层，避免 Jugg Debug executor 绑定某个 Android Studio debugger API。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [JVMTI Agent](./jvmti-agent.md)
- [兼容性参考](../reference/compatibility.md)
