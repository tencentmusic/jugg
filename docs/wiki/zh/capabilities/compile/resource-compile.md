---
title: 资源编译
description: 说明 Jugg 对 Android res、assets 和资源生成产物的增量处理范围与回退边界。
status: active
tags:
  - capability
  - compile
  - resource
---

# 资源编译

Jugg 支持增量处理 Android `res/` 和 `assets/`，并衔接 AndroidManifest、DataBinding/ViewBinding 等资源相关能力。Android `res/` 会经过 `aapt2` 编译和增量 link，`assets/` 则直接组织为 overlay；`resources.arsc`、`R.java` 和 `R.dex` 是资源变化可能产生的后续产物，不是用户直接修改的资源输入。

本页用于判断资源修改是否支持，以及部署时会看到什么结果。aapt2 `inclink` 与资源表复用机制见[资源增量编译原理](../../concepts/incremental-compile/resource.md)。Compose Multiplatform 资源使用独立的 generator 和部署路径，见 [KMP 与 Compose Multiplatform](./kmp-compose-multiplatform.md)。

## 支持范围

| 资源或场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 普通 Android `res/` 文件 | 支持 | 生成归属于目标 APK 的资源 overlay |
| `res/values` | 支持 | 更新资源表；资源符号变化时可能继续生成和编译 R 声明 |
| `assets/` | 支持 | 保持 `assets/` 相对路径，作为 overlay 下发到目标 APK |
| `AndroidManifest.xml` | 支持增量 patch | 更新 APK 并重签名；完整范围见 [AndroidManifest 编译](./manifest.md) |
| ViewBinding/DataBinding layout | 支持资源阶段交接 | 同时产生资源产物和绑定相关生成源码；详见 [DataBinding/ViewBinding](./databinding-viewbinding.md) |
| 已有 AabResGuard mapping 的资源混淆工程 | 支持 | 增量资源尽量沿用已安装 APK 的资源名称；详见 [AabResGuard](./aab-resguard.md) |

## 资源变化会产生什么结果

| 产物或处理 | 来源 | 后续结果 |
|---|---|---|
| 编译后资源与 `resources.arsc` | Android `res/` 增量 link | 作为资源 overlay 进入部署 |
| `R.java`，以及部分 R 引用场景需要的 `R.dex` | 资源 ID 或符号发生变化 | `R.java` 继续进入源码编译，生成的 DEX 随资源产物部署 |
| ViewBinding/DataBinding 生成源码 | 绑定 layout 变化 | 继续进入 Java/Kotlin 源码编译 |
| asset overlay | `assets/` 变化 | 不经过 `aapt2`，按目标 APK 部署 |
| 更新后的 Manifest | Manifest 存在真实增量变化 | 写入目标 APK，重签名并安装 |

```text
Android res 变化
  -> aapt2 compile 生成本轮 flat
  -> 基于当前 APK 资源表执行增量 link
  -> 输出 compiled res、resources.arsc 和可选 R.java

assets 变化
  -> 保持 assets 下的相对路径
  -> 生成 asset overlay

生成源码
  -> 进入后续源码编译
  -> 所有产物按目标 APK 分流后进入部署
```

普通资源或 asset overlay 部署后通常会重新启动 Activity。Manifest 真实变化会进入 APK 更新、重签名和安装路径。多 APK 或 dynamic feature 工程会分别生成对应资源产物，不会把同一份 overlay 复制到所有 APK。

## 使用边界

- 删除 `res/` 或 assets 文件时，Jugg 不会生成移除设备端文件或资源 entry 的部署数据。原有资源仍可通过 `Resources` 或 `AssetManager` 读取，资源 ID 也保持不变；只有需要让删除真正生效时，才执行完整 Gradle 构建。
- Manifest 删除节点、删除属性或依赖完整 merge 的 `tools:*` 操作不会产生对应的移除或合并结果，设备仍使用原有 merged manifest 内容。具体表现见 [AndroidManifest 编译](./manifest.md)。
- 修改 source set、variant、资源目录、资源生成逻辑或资源混淆配置时，工程模型变化应先完成 Gradle Sync，再为目标变体执行完整 Gradle 构建，建立新的 APK 和资源表基线。
- 新增或修改 styleable 依赖最近一次构建提供的 R 声明，资源混淆依赖与当前 APK 匹配的 mapping；基线缺失或不匹配时使用 Gradle 构建刷新。
- Compose Multiplatform 资源不经过 Android `aapt2`，也不会按本页的 Android `res/` 规则处理。
- 首次部署资源 overlay 时，Jugg 可能补齐基线中的资源文件，因此部署文件数量可能多于本轮直接修改的文件。

## 相关页面

- [源码编译](./source-compile.md)
- [AndroidManifest 编译](./manifest.md)
- [DataBinding/ViewBinding](./databinding-viewbinding.md)
- [AabResGuard](./aab-resguard.md)
- [KMP 与 Compose Multiplatform](./kmp-compose-multiplatform.md)
- [资源增量编译原理](../../concepts/incremental-compile/resource.md)
- [assets 与 native lib 原理](../../concepts/incremental-compile/assets-native.md)
- [编译阶段说明](../../guide/compile.md)
- [编译失败](../../troubleshooting/compile-failed.md)
- [改动没有生效](../../troubleshooting/changes-not-applied.md)
- [限制](../../reference/limits.md)
