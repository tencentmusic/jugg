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

Jugg 只对少量明确列出的注解入口提供增量处理，并把对应生成源码纳入本轮源码编译。未列出的 APT、KAPT 或 KSP processor 不会由 Jugg 独立增量执行，需要交给 Gradle。

## 支持范围

| 注解 / 入口 | 是否支持 | 实现方式 |
|---|---|---|
| 新增 `com.tencent.kuikly.core.annotations.Page` / `@Page` 页面 | 支持 | 基于已有路由入口补充缺失注册，并将更新后的入口加入本轮源码编译 |
| 删除、重命名或修改已有 Kuikly `@Page` 路由 | 不支持 | 不移除旧路由注册，需要由 Gradle 重新生成完整入口 |
| 使用 KSP1 的 `com.squareup.moshi.JsonClass` / `@JsonClass` | 支持 | 通过项目 KSP1 compiler plugin 生成 Moshi adapter，并继续源码编译 |
| 使用 KSP2 的 `com.squareup.moshi.JsonClass` / `@JsonClass` | 不支持 | 不独立运行 KSP2 processor，只能继续编译 Gradle 已生成的源码 |
| [DataBinding `<layout>`](./databinding-viewbinding.md) | 支持 | 通过专用 DataBinding annotation processor 生成 mapper、BR 和绑定相关源码 |

> [!NOTE]
> 除上表明确列出的入口外，其他 annotation processor 均视为不支持 Jugg 独立增量执行。相关源码需要重新生成时，使用对应 Gradle 构建。

## 使用边界

- Kuikly `@Page` 增量处理依赖最近一次 Gradle/KSP 生成的路由入口基线，并且只补充缺失注册。删除页面、修改路由或重命名页面类时，应通过 Gradle 清理旧注册。
- Moshi KSP 只在本轮 Kotlin 源码明确使用 `com.squareup.moshi.JsonClass` 且项目存在对应 KSP 依赖时触发。
- KSP2 不由 Jugg 独立运行 processor；Jugg 只能继续编译 Gradle 已经生成的源码。
- 修改 processor 依赖、compiler plugin、参数或生成规则后，工程模型变化时先完成 Sync，再执行 Gradle 构建刷新生成源码基线。

## 相关页面

- [DataBinding/ViewBinding](./databinding-viewbinding.md)
- [源码编译](./source-compile.md)
- [Gradle 回退](./gradle-fallback.md)
