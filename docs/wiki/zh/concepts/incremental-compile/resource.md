---
title: 资源增量编译
description: 解释 aapt2 原生 link 在大型工程中的固定耗时来源，以及 Jugg 如何用定制 inclink 把资源链接转为内存级 overlay 注入。
status: active
tags:
  - concept
  - compile
  - resource
---

# 资源增量编译

Android 资源要经过 aapt2 处理后才能进入 APK。Jugg 复用最近一次 Gradle 构建留下的资源基线，只编译本轮变化的资源文件，再通过定制 aapt2 的 `inclink` 生成可部署的资源 overlay。

资源增量的难点不在 `compile`，而在原生 `link` 缺少局部状态缓存。Jugg 的资源链路围绕这个固定耗时展开：先复用 Gradle 基线，再把 link 上下文常驻到 aapt2 daemon，最后用 overlay 交给部署阶段。

## 原生 link 缺少局部状态缓存

aapt2 把资源处理拆成 `compile` 和 `link` 两步：

```text
aapt2 compile
  -> 把单个 XML、图片等资源编译为中间产物（flat）

aapt2 link
  -> 读取全部 flat、Manifest 和符号表
  -> 分配资源 ID
  -> 输出 resources.arsc、二进制资源、Manifest 和 R.java
```

`compile` 可以按单文件执行，耗时很低。瓶颈在 `link`：它没有局部状态缓存，每次都要全量读取所有 flat 中间产物、重建完整资源表并分配资源 ID。这个开销与本轮改动大小无关，即使只改一个 XML，link 也要把整个工程的资源重新链接一遍。在百万行级、数百依赖的工程里，单次 link 有 10 到 15 秒的固定耗时。

## inclink 把 link 上下文常驻内存

Jugg 定制了 aapt2，新增 `inclink` 命令，把一次性的全量链接拆成「加载基线」和「增量注入」两个动作：

```text
加载基线（一次）
  -> 直接从 APK 读取 resources.arsc、二进制资源、Manifest 和必要符号
  -> 把链接上下文常驻为内存级缓存

增量链接（每轮）
  -> 接收本轮变化资源编译出的 flat
  -> 在缓存上下文中新增或覆盖资源
  -> 输出 resources.arsc、二进制资源、Manifest 和可选 R.java
```

基线只需加载一次，之后日常增量就从「全量 link」转为「轻量级 overlay 注入」。历史测试数据中，资源链接耗时从 10 到 15 秒降到约 0.2 秒，部分 `inclink` 调用约 100 毫秒。如果本轮没有新增资源 ID，还可以跳过 `R.java` 生成，省去后续对 `R.java` 的编译。

这里有两个工程取舍。

第一，基线直接从 APK 的 `resources.arsc` 载入，得到的就是最终资源表，不需要回读所有历史 flat，也不需要额外固化资源 ID 的步骤。第二，`resources.arsc` 不保存 `styleable` 信息，Jugg 会从 APK 的 DEX 中导出 `R.styleable` 声明，在加载基线时一并补回，保证自定义属性引用可用。

如果当前 APK 已经被 Jugg 部署过新的 `resources.arsc`，加载基线时会优先用已部署的资源表和 Manifest 组成临时资源 APK，而不是只读原始 APK，避免在过期资源表上继续链接。

## 资源阶段的流转

资源阶段串联 Manifest 增量、flat 编译和增量链接，并在最后过滤掉不应部署的额外产物：

```text
资源变化输入
  -> 按目标 APK 拆分输入
  -> 生成可选的 Manifest overlay
  -> 把变化资源编译为 flat
  -> 加载基线资源表，注入本轮 flat
  -> 过滤不应部署的额外产物
  -> 输出 resources.arsc、资源 overlay、Manifest 和 R.java
```

layout 资源在进入 aapt2 前会先经过 DataBinding / ViewBinding 处理；资源阶段生成的 Java/Kotlin 源不会直接部署，而是回流到源码阶段继续编译（见 [DataBinding / ViewBinding](./databinding-viewbinding.md)）。

## inclink 的代价与适用边界

常驻缓存换来的速度也带来几条约束，它只适合日常 debug 增量：

- **资源表只增不减**：删除资源后，对应资源 ID 不会立刻从 `resources.arsc` 中消失，要等下一次 Gradle 构建刷新基线。因此 `inclink` 面向 debug 开发，不用于生产构建。
- **多 APK 各自链接**：每个 APK 的资源表、package id、Manifest 和 dynamic feature 依赖关系不同，资源不会把同一份输出复制给所有 APK，而是按 APK 单独链接。
- **dynamic feature 依赖 base**：base 资源表更新后，feature 链接需要带上 base 本轮的 flat，保持资源 ID 一致。
- **缓存失效会重载**：当资源链接的常驻进程失效或链接失败时，会释放缓存，下一轮重新加载基线资源表。

## 相关页面

- [增量编译总览](./index.md)
- [DataBinding / ViewBinding](./databinding-viewbinding.md)
- [Android Manifest 编译与 release 增量编译](./manifest-minify.md)
- [资源编译能力](../../capabilities/compile/resource-compile.md)
