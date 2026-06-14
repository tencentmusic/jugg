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

## 资源编译如何运作

### 资源编译链路

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

这里有两个关键点：

1. **资源不是简单复制文件**。大多数 `res/` 文件需要经过 aapt2 编译和 link。
2. **资源编译可能触发源码编译**。例如 `R.java`、ViewBinding/DataBinding 生成源码会继续进入 Java/Kotlin 编译阶段。
3. **link 会基于最近一次可用资源表继续进行**。这让多轮资源增量可以叠加，而不是每次都从原始 APK 的旧资源表开始。

### APK scoped 编译

在多 APK 或 dynamic feature 场景下，同一个模块的资源可能影响不同 APK。Jugg 会按 APK scoped 编译，而不是把一份资源产物复制到所有 APK。

| 场景 | 处理方式 |
|---|---|
| 单 APK | 基于当前 APK 的资源表继续增量 link |
| base APK | 先更新 base 资源表 |
| dynamic feature | link 时考虑 base APK 的资源表和本轮 base 资源变化 |
| 多 APK 归属 | 每个目标 APK 生成自己的 overlay 产物 |

> [!NOTE]
> 如果资源 overlay 被下发到错误 APK，通常会表现为运行时找不到资源、资源 ID 异常或 feature 模块资源不一致。

### AndroidManifest 编译

AndroidManifest 在资源链路中作为 aapt2 link 输入参与部署产物生成。Jugg 不重新完整运行 Gradle 的 Manifest 合并流程，而是基于最近一次构建得到的 merged manifest，对本轮 AndroidManifest 变化做增量 patch。

AndroidManifest 最终不是普通资源 overlay：部署阶段会把更新后的 `AndroidManifest.xml` 写入 APK 并重新签名。AndroidManifest 的支持边界、placeholder 限制和生效方式见 [AndroidManifest 编译](./manifest.md)。

### so 更新

`.so` 更新不属于资源编译，也不叫 so 编译。Jugg 支持基于已有 native lib 产物更新 APK 并重新签名，见 [so 更新](./so-update.md)。

### DataBinding/ViewBinding

layout 文件变化时，资源阶段会先识别是否需要 ViewBinding/DataBinding 处理。必要时，Jugg 会用 split XML 参与 aapt2 compile，并把生成源码交给源码编译阶段。

ViewBinding/DataBinding 的生成源码、mapper 处理和源码编译交接见 [DataBinding/ViewBinding](./databinding-viewbinding.md)。

### 资源混淆和 R 文件

在 release 或资源混淆场景下，Jugg 会沿用现有映射信息，使增量产物和已安装 APK 的资源命名保持一致。

资源 link 后会生成 `R.java`。Jugg 会修正并编译它，必要时还会生成 `R.dex`，用于处理 `R.styleable` 或部分无法内联的 `R.*` 引用。

## 相关页面

- [源码编译](./source-compile.md)
- [AndroidManifest 编译](./manifest.md)
- [so 更新](./so-update.md)
- [DataBinding/ViewBinding](./databinding-viewbinding.md)
- [编译阶段说明](../../guide/compile.md)
- [编译问题排查](../../troubleshooting/compile.md)
- [限制](../../reference/limits.md)
