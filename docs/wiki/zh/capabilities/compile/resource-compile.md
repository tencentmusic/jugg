---
title: 资源编译
description: 说明 Jugg 对 res、assets、R 文件和资源 overlay 的增量处理能力。
status: active
tags:
  - capability
  - compile
  - resource
---

# 资源编译

Jugg 支持对 Android 资源相关文件进行增量编译，并把结果交给部署阶段。资源编译覆盖 `res/`、`assets/`、`resources.arsc`、`R.java` 以及 ViewBinding/DataBinding layout 相关产物交接。

## 已支持能力

| 修改类型 | 当前支持情况 | 结果 |
|---|---|---|
| 普通 `res/` 文件 | 支持 | 通过 aapt2 compile 生成 `.flat`，再 link 为可部署资源 overlay |
| `res/values` | 支持 | 更新资源表，产出新的 `resources.arsc` |
| `assets/` | 支持 | 作为 overlay 下发到目标 APK |
| `AndroidManifest.xml` | 支持增量 patch | 作为资源 link 输入参与产物生成，最终通过更新 APK 并重签名生效；完整能力见 [AndroidManifest 编译](./manifest.md) |
| ViewBinding/DataBinding layout | 支持资源阶段交接 | 先处理 split XML / 生成源码，再进入资源和源码编译；完整能力见 [DataBinding/ViewBinding](./databinding-viewbinding.md) |
| `R.java` / `R.dex` | 由资源 link 触发 | 资源表变化后生成并修正 `R.java`，必要时继续编译为 dex |

> [!TIP]
> 如果变更的是 Gradle 配置、source set、variant 或资源生成逻辑，先完成对应 Gradle 构建或 Sync，让 Jugg 基于新的构建结果继续增量。

## 资源编译如何生效

一次资源变化通常会走下面的链路：

```text
发现 res / AndroidManifest / assets 变化
  -> 按目标 APK 拆分编译任务
  -> 处理 AndroidManifest diff
  -> 处理 ViewBinding / DataBinding layout 交接
  -> 使用 aapt2 compile 生成 .flat
  -> 使用 aapt2 inclink 生成 resources.arsc、compiled res、R.java
  -> 过滤不应部署的额外产物
  -> 资源 / assets overlay 交给部署，AndroidManifest 相关产物进入 APK 更新与重签名链路
  -> 生成源码继续交给源码编译
```

用户需要关注三个结果：

- 大多数 `res/` 文件不是直接复制，会先经过 aapt2 compile / link。
- 资源变化可能继续触发源码编译，例如 `R.java`、ViewBinding/DataBinding 生成源码。
- 多 APK 或 dynamic feature 场景会按目标 APK 分流，不会把同一份资源产物复制到所有 APK。

更细的 aapt2 `inclink`、APK scoped link、资源表复用和 DataBinding 交接机制，见 [资源增量编译原理](../../concepts/incremental-compile/resource.md)。

## 相关页面

- [源码编译](./source-compile.md)
- [AndroidManifest 编译](./manifest.md)
- [so 更新](./so-update.md)
- [DataBinding/ViewBinding](./databinding-viewbinding.md)
- [编译阶段说明](../../guide/compile.md)
- [编译问题排查](../../troubleshooting/compile.md)
- [限制](../../reference/limits.md)
