---
title: 兼容层
description: 说明 Jugg 为什么需要隔离 Android Studio 版本差异，以及原文中提到的兼容策略。
status: active
tags:
  - concept
  - compatibility
---

# 兼容层

Jugg 是 IDE 插件，依赖 IntelliJ IDEA 标准接口，也会使用 Android Studio 插件中的部分部署相关能力。原文提到，IntelliJ IDEA 的标准接口相对稳定；Android Studio 插件内部类变化较多，基本每个版本都需要适配。

兼容层的作用是隔离这些版本差异，避免编译和部署主流程直接绑定某个 Android Studio 版本的内部实现。

## 为什么需要兼容层

Jugg 需要调用 Android Studio 的部署能力，包括安装、Apply Changes 相关通道和设备侧部署接口。这些接口并不总是稳定公开 API。版本变化可能带来包名、类名、方法签名或运行行为差异。

如果主流程直接依赖这些实现，升级 Android Studio 后容易出现启动失败或部署失败。兼容层把这些差异集中到版本适配实现中，主流程只调用统一接口。

## 原文中的兼容方式

原文描述的做法是：为不同 Android Studio 版本提供隔离实现，外部统一通过接口调用。插件启动时根据 IDE 版本选择对应实现。

同时，Android Studio 的同一个大版本也可能存在多个小版本差异。发生不兼容调用时，Jugg 会尝试其他版本的兼容实现，直到找到可用实现。

## 已提到的版本范围

原文第一篇提到，当时 Jugg 已支持从 Chipmunk 到 Iguana 的 Android Studio 版本。后续答辩稿还提到 Jugg 继续兼容更早和更新的 IDE 版本，但没有展开具体实现。

## 相关页面

- [部署策略](./deploy-strategy.md)
- [JVMTI Agent](./jvmti-agent.md)
- [兼容性参考](../reference/compatibility.md)
