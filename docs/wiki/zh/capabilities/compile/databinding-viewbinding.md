---
title: DataBinding/ViewBinding
description: 说明 Jugg 对 DataBinding 和 ViewBinding layout 修改的增量处理能力。
status: active
tags:
  - capability
  - compile
  - databinding
  - viewbinding
---

# DataBinding/ViewBinding

Jugg 支持 DataBinding / ViewBinding 相关 layout 修改的增量处理。它会先在资源阶段处理 layout split、base class 或 trigger source，再在源码阶段生成 mapper、BR 并继续 Java/Kotlin 编译。

## 已支持能力

| 修改类型 | 当前支持情况 | 生效方式 |
|---|---|---|
| ViewBinding layout 修改 | 支持 | 生成 binding base classes，继续资源与源码链路 |
| DataBinding layout 修改 | 支持 | 生成 stripped XML、trigger source、mapper 和 BR |
| `<include>` 影响 | 支持基于 layout info 补齐 | 找到受影响 layout info 后参与 mapper 生成 |
| Kotlin 源参与 DataBinding | 支持重试路径 | APT 失败后可先编译 Kotlin class 再重试 mapper |
| Gradle layout info 维护 | 支持 | 将增量 layout info 复制回 Gradle 目录，避免后续 Gradle 缺基线 |

> [!TIP]
> 如果是首次启用 DataBinding/ViewBinding、升级 AGP，或修改相关 Gradle 配置，先执行 Gradle 构建或 Sync，让中间产物路径和 layout info 成为新基线。

## DataBinding / ViewBinding 如何生效

```text
layout 资源变化
  -> 资源阶段识别 DataBinding / ViewBinding
  -> 生成 split XML、base classes 或 DataBinding trigger
  -> split XML 进入 aapt2 compile/link
  -> 生成源码进入 SourceCompiler
  -> DataBinding mapper / BR 生成
  -> Kotlin / Java / dex 继续编译
```

ViewBinding 通常停留在 base class 生成和源码编译；DataBinding 还需要 annotation processor 生成 mapper、BR 和增量 holder。

## 关键边界

- 普通 layout 不会因为开启 ViewBinding 就一定进入 DataBinding mapper。
- DataBinding mapper 依赖上次 Gradle 产出的 layout info 和 BR 基线；缺失时需要 Gradle 重建。
- stripped XML 既是资源产物，也是源码阶段判断 mapper 的输入之一，不能只看 Java 输出判断是否成功。

## 关联能力

- [资源编译](./resource-compile.md)
- [注解器](./annotation-processors.md)
- [源码编译](./source-compile.md)
