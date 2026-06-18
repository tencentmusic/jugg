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

Jugg 支持对 Android 资源相关文件进行增量编译，并把结果交给部署阶段。资源编译覆盖 `res/`、`assets/`、`resources.arsc`、`R.java` 以及 ViewBinding/DataBinding layout 相关产物交接。本页只说明能力范围和用户可见结果，aapt2 `inclink` 与资源表复用机制见[资源增量编译原理](../../concepts/incremental-compile/resource.md)。

## 已支持能力

| 修改类型 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 普通 `res/` 文件 | 支持 | 生成可部署资源 overlay |
| `res/values` | 支持 | 更新资源表，必要时触发 `R` 相关源码编译 |
| `assets/` | 支持 | 作为 overlay 下发到目标 APK |
| `AndroidManifest.xml` | 支持增量 patch | 更新 APK 并重签名；完整能力见 [AndroidManifest 编译](./manifest.md) |
| ViewBinding/DataBinding layout | 支持资源阶段交接 | 生成绑定相关产物，并继续进入源码编译 |
| `R.java` / `R.dex` | 由资源表变化触发 | 资源 ID 或符号变化后，相关代码重新编译并随部署生效 |

> [!TIP]
> 如果变更的是 Gradle 配置、source set、variant 或资源生成逻辑，先完成对应 Gradle 构建或 Sync，让 Jugg 基于新的构建结果继续增量。

## 触发与结果

一次资源变化通常会带来三类结果：

- 大多数 `res/` 文件不是直接复制，会先生成可部署资源产物。
- 资源变化可能继续触发源码编译，例如 `R.java`、ViewBinding/DataBinding 生成源码。
- 多 APK 或 dynamic feature 场景会按目标 APK 分流，不会把同一份资源产物复制到所有 APK。

如果资源变化最终需要更新 APK 内容，部署阶段会走 APK 更新与重签名；如果只需要 overlay，则优先走增量部署。

## 使用边界

- 修改 Gradle 配置、source set、variant 或资源生成逻辑时，先完成对应 Gradle 构建或 Sync。
- 删除资源后，资源 ID 和资源表基线可能需要下一次 Gradle 构建彻底刷新。
- 资源、Manifest 和 DataBinding/ViewBinding 经常会联动，不能只按单个文件后缀判断本轮结果。

## 相关页面

- [源码编译](./source-compile.md)
- [AndroidManifest 编译](./manifest.md)
- [so 更新](./so-update.md)
- [DataBinding/ViewBinding](./databinding-viewbinding.md)
- [资源增量编译原理](../../concepts/incremental-compile/resource.md)
- [编译阶段说明](../../guide/compile.md)
- [编译问题排查](../../troubleshooting/compile.md)
- [限制](../../reference/limits.md)
