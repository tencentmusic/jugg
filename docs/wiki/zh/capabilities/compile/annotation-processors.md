---
title: 注解器
description: 说明 Jugg 当前明确支持的注解入口，以及这些注解如何进入源码增量编译。
status: active
tags:
  - capability
  - compile
  - apt
  - kapt
  - ksp
---

# 注解器

Jugg 支持少量明确识别的注解入口，并把对应生成源码纳入本轮源码编译。对用户来说，先判断自己使用的注解是否在支持列表中，比理解 JuggApt、KAPT 或 KSP 的内部路径更重要。

## 已支持能力

| 注解 / 入口 | 当前支持情况 | 生效方式 |
|---|---|---|
| `com.tencent.kuikly.core.annotations.Page` / `@Page` | 支持 | 更新 `KuiklyCoreEntry.kt` 中缺失的 page router 注册 |
| `com.squareup.moshi.JsonClass` / `@JsonClass` | 支持 KSP 触发 | 命中后启用 KSP 相关参数或收集 KSP 生成源码 |
| DataBinding `<layout>` XML | 支持 | 生成 DataBinding trigger，并运行 DataBinding annotation processor 生成 mapper / BR |
| `androidx.databinding.BindingBuildInfo` / `android.databinding.BindingBuildInfo` | Jugg 内部生成 | 作为 DataBinding annotation processor 的 trigger source，用户通常不需要手写 |

> [!NOTE]
> 除上表明确列出的入口外，不应默认认为任意 annotation processor 都能由 Jugg 完整增量执行。修改注解器依赖、参数或生成规则时，建议先 Gradle 构建。

## 实现方式

```text
源码或 layout 变化
  -> Kuikly @Page 由 JuggApt 在语言编译前改写 KuiklyCoreEntry.kt
  -> Moshi @JsonClass 命中 KSP 白名单后进入 KSP 参数/生成源码路径
  -> DataBinding layout 生成 BindingBuildInfo trigger
  -> DataBinding processor 生成 mapper / BR
  -> 生成源码登记为 changed file
  -> 源码编译继续 Kotlin / Java / dex
```

JuggApt 是 fail-open：processor 异常会打印 warn，然后继续主编译。若生成源码导致语言编译失败，Jugg 会在命中 JuggApt 产物时移除相关 changed-file 登记并重试一次。

## KAPT / KSP 边界

- KAPT 输出会从临时目录中收集 Java source 和 class 输出，继续交给源码编译后续阶段。
- KSP 当前按源码 import 触发白名单场景；不是所有 KSP processor 都保证由 Jugg 独立运行。
- KSP1 可通过 compiler plugin 参数运行；KSP2 更偏向两阶段生成源码再编译。
- 修改注解器依赖、compiler plugin 或 processor 参数后，应通过 Gradle 刷新项目快照。

## 相关页面

- [DataBinding/ViewBinding](./databinding-viewbinding.md)
- [Kotlin Compose](./kotlin-compose.md)
- [源码编译](./source-compile.md)
- [Gradle 回退](./gradle-fallback.md)
