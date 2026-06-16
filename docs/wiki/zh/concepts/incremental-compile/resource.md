---
title: 资源增量编译
description: 说明 Jugg 如何通过 aapt2 compile 和定制 inclink 编译 res 资源，并生成资源 overlay。
status: active
tags:
  - concept
  - compile
  - resource
---

# 资源增量编译

Android 资源需要经过 aapt2 处理后才能进入 APK。Jugg 复用 Gradle 生成的资源基线，只编译本轮变化的资源文件，再通过定制 aapt2 `inclink` 生成可部署的资源 overlay。

## aapt2 compile 与 link

aapt2 把资源编译拆成两步：

```text
aapt2 compile
  -> 把单个 XML、图片等资源编译为 .flat 中间产物

aapt2 link
  -> 读取所有 .flat 和 Manifest
  -> 分配资源 ID
  -> 输出 res、resources.arsc、AndroidManifest.xml 和 R.java
```

`compile` 可以按单文件执行，耗时通常较低。`link` 需要读取完整输入并分配资源 ID，大工程中仍可能有 10 秒以上耗时。

## Jugg 的 inclink

Jugg 定制 aapt2，新增 `inclink` 命令，把 link 拆成加载和增量链接：

```text
inclink --load
  -> 从 APK 读取 res、resources.arsc、Manifest 和必要 symbol
  -> 在 aapt2 daemon 中缓存 LinkContext

inclink
  -> 接收本轮变化资源编译出的 .flat
  -> 在缓存上下文中新增或覆盖资源
  -> 输出 resources.arsc、compiled res、Manifest 和可选 R.java
```

这套方案的目标是减少重复 IO 和全量资源表构建。参考资料中的数据是：资源 link 耗时从 10 到 15 秒降低到约 0.2 秒，部分 `inclink` 命令约 100 毫秒。

如果本轮没有新增资源 ID，`inclink` 可以跳过 `R.java` 生成，避免后续继续编译 `R.java`。

## 为什么从 APK 加载

`inclink --load` 直接读取 APK 中的 `resources.arsc`，得到最终资源表。这样不需要读取所有历史 `.flat`，也不需要通过 `--emit-ids` / `--stable-ids` 固化 ID。

但 `resources.arsc` 不保存 `styleable` 信息。Jugg 会从 APK 的 dex 中导出 `R.styleable` 声明，并在 `inclink --load` 时额外导入。

## 资源阶段链路

```text
ResourceOverlayCompiler
  -> 按 APK scoped 拆分输入
  -> AndroidManifestCompiler 生成可选 Manifest overlay
  -> ResourceCompiler 编译 changed res 为 .flat
  -> ArscCompiler loadTable / inclink
  -> ResourceOverlayCompiler 过滤不应部署的额外产物
  -> 输出 resources.arsc、res overlay、Manifest 和 R.java
```

多 APK 场景下，资源编译不会把同一份输出复制到所有 APK。每个 APK 都有自己的资源表、package id、Manifest 和 dynamic feature 依赖关系，因此 Jugg 会按 APK 单独 link。

## 与 DataBinding / ViewBinding 的关系

layout 资源进入 aapt2 前，`ResourceCompiler` 会先处理 DataBinding / ViewBinding。资源阶段负责生成 base class、split XML、stripped XML 和触发源码；DataBinding mapper 和 BR 合并交给源码阶段继续处理。

资源阶段产生的 Java/Kotlin 源不会直接部署，会回流到 `SourceCompiler`。

## 约束

- 资源删除不会立刻从 `resources.arsc` 中移除，对应资源 ID 会保留到下一次 Gradle 构建刷新基线。
- `ArscCompiler` 为每个 APK 缓存一个 `Aapt2DaemonInvoker`。invoker 死亡或 link 失败后会释放，下轮重新加载资源表。
- dynamic feature 编译依赖 base APK。base 资源表更新后，feature link 需要带上 base 本轮 flat 文件，保持资源 ID 一致。
- 如果当前 APK 已经部署过新的 `resources.arsc`，Jugg 会优先用已部署资源表和 Manifest 组成临时 res APK，而不是只读原始 APK。

## 相关页面

- [增量编译总览](./index.md)
- [DataBinding / ViewBinding](./databinding-viewbinding.md)
- [Android Manifest 编译与 release 增量编译](./manifest-minify.md)
- [资源编译能力](../../capabilities/compile/resource-compile.md)
