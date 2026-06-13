---
title: 资源编译
description: 说明 Jugg 对 res、assets、Manifest、R 文件和资源 overlay 的增量处理能力。
status: active
tags:
  - capability
  - compile
  - resource
---

# 资源编译

Jugg 支持对 Android 资源相关文件进行增量编译，并把结果作为 overlay 交给部署阶段。资源编译覆盖的不只是 `res/`，还包括 `assets/`、native lib、`AndroidManifest.xml`、`resources.arsc`、`R.java` 以及部分 ViewBinding/DataBinding 生成产物。

> [!IMPORTANT]
> 资源增量编译依赖最近一次可用 APK 的资源表。Jugg 会尽量基于最新已部署资源继续 link，但复杂资源结构或构建插件行为仍可能需要 Gradle 回退。

## 支持的资源类型

| 类型 | 示例 | Jugg 处理方式 |
|---|---|---|
| 普通资源 | `res/layout/activity_main.xml`、`res/drawable/icon.xml` | 编译为 `.flat`，再 link 为可部署 overlay |
| values 资源 | `res/values/strings.xml`、`colors.xml` | 编译并更新 `resources.arsc` |
| Manifest | `AndroidManifest.xml` | 基于已合并 Manifest 做增量 patch |
| assets | `assets/config.json` | 作为 overlay 下发 |
| native lib | `jniLibs/**/*.so` | 作为 overlay 下发 |
| ViewBinding/DataBinding layout | `res/layout/*.xml` | 先生成必要源码或 split XML，再进入资源/源码链路 |

## 资源编译链路

一次资源变化通常会走下面的链路：

```text
发现 res / Manifest 变化
  -> 按目标 APK 拆分编译任务
  -> 处理 Manifest diff
  -> 处理 ViewBinding / DataBinding layout
  -> 使用 aapt2 compile 生成 .flat
  -> 使用 aapt2 inclink 生成 resources.arsc、compiled res、R.java
  -> 过滤不应部署的额外产物
  -> overlay 交给部署，生成源码继续交给源码编译
```

这里有两个关键点：

1. **资源不是简单复制文件**。大多数 `res/` 文件需要经过 aapt2 编译和 link。
2. **资源编译可能触发源码编译**。例如 `R.java`、ViewBinding/DataBinding 生成源码会继续进入 Java/Kotlin 编译阶段。

## APK scoped 编译

在多 APK 或 dynamic feature 场景下，同一个模块的资源可能影响不同 APK。Jugg 会按 APK scoped 编译，而不是把一份资源产物复制到所有 APK。

| 场景 | 处理方式 |
|---|---|
| 单 APK | 基于当前 APK 的资源表继续增量 link |
| base APK | 先更新 base 资源表 |
| dynamic feature | link 时需要考虑 base APK 的资源表和本轮 base 资源变化 |
| 多 APK 归属 | 每个目标 APK 生成自己的 overlay 产物 |

> [!NOTE]
> 如果资源 overlay 被下发到错误 APK，通常会表现为运行时找不到资源、资源 ID 异常或 feature 模块资源不一致。

## Manifest 如何处理

Jugg 不会重新完整运行 Gradle 的 Manifest 合并流程。它会基于最近一次构建得到的 merged manifest，将本轮 Manifest 变化增量 patch 到部署产物中。

这意味着：

- 普通 Manifest 节点或属性变化可以增量处理。
- 如果 Manifest 变化依赖复杂 Gradle placeholder、插件生成逻辑或变体切换，可能需要 Gradle 回退。
- 如果 Manifest 没有真实变化，Jugg 会避免输出根 `AndroidManifest.xml`，防止触发不必要的重打包。

## ViewBinding 和 DataBinding

layout 文件变化时，Jugg 会先识别是否需要 ViewBinding/DataBinding 处理。

```text
layout XML 变化
  -> 生成 ViewBinding/DataBinding 需要的基础类或触发文件
  -> 必要时用 split XML 替换原 layout 参与 aapt2 compile
  -> 生成源码交给源码编译阶段
```

> [!TIP]
> 如果你修改 layout 后看到源码编译也被触发，这是正常现象。资源变化可能会产生新的 Java/Kotlin 输入。

## 资源混淆和 R 文件

在 release 或资源混淆场景下，Jugg 会尽量沿用现有映射信息，保持增量产物和已安装 APK 的资源命名一致。

资源 link 后会生成 `R.java`。Jugg 会修正并编译它，必要时还会生成 `R.dex`，用于处理 `R.styleable` 或部分无法内联的 `R.*` 引用。

## 常见现象

| 现象 | 可能含义 |
|---|---|
| 修改 layout 后同时编译资源和源码 | ViewBinding/DataBinding 或 `R.java` 触发源码链路 |
| 修改 values 后生成 `resources.arsc` | 资源表需要更新 |
| dynamic feature 资源异常 | base 与 feature 的资源表可能需要重新对齐 |
| Manifest 修改后回退 Gradle | 变化可能依赖完整 Manifest 合并或构建逻辑 |
| aapt2 报错 | 资源本身不合法，或当前增量资源表无法继续 link |

## 适合增量的修改

- 修改普通 layout、drawable、mipmap、values 文件。
- 修改 assets 中的小文件。
- 修改简单 Manifest 节点或属性。
- 修改会触发 ViewBinding/DataBinding 重新生成的 layout。

## 建议回退 Gradle 的修改

- 修改资源目录结构、source set 或 variant 选择规则。
- 修改影响资源生成的 Gradle 插件配置。
- 修改复杂 Manifest placeholder 或 manifestPlaceholders 来源。
- 修改后出现资源 ID、大量 dynamic feature 资源或 release 资源混淆异常。

## 相关页面

- [增量编译](../../concepts/incremental-compile.md)
- [编译指南](../../guide/compile.md)
- [编译问题排查](../../troubleshooting/compile.md)
- [限制](../../reference/limits.md)
