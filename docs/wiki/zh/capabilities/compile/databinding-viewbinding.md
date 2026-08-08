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

Jugg 支持 DataBinding / ViewBinding 相关 layout 修改的增量处理。它会把 layout 修改转成资源产物和生成源码，再继续进入资源编译与源码编译。本页只说明支持范围和使用边界，两阶段交接机制见 [DataBinding / ViewBinding 增量编译原理](../../concepts/incremental-compile/databinding-viewbinding.md)。

## 已支持能力

| 修改类型 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| ViewBinding layout 修改 | 支持 | binding 相关源码会被更新并继续编译 |
| DataBinding layout 修改 | 支持 | mapper、BR 等相关源码会被更新并继续编译 |
| `<include>` 影响 | 支持基于 layout info 补齐 | 被 include 关系影响的 layout 会一起进入生成源码更新 |
| Kotlin 源参与 DataBinding | 支持重试路径 | Kotlin 相关 class 准备完成后，DataBinding 生成可继续推进 |
| Gradle layout info 维护 | 支持 | 后续 Gradle 构建仍能拿到必要 layout 基线 |

> [!TIP]
> 如果是首次启用 DataBinding/ViewBinding、升级 AGP，或修改相关 Gradle 配置，先执行 Gradle 构建或 Sync，让中间产物路径和 layout info 成为新基线。

## 触发与结果

```text
DataBinding / ViewBinding layout 变化
  -> 更新资源侧产物
  -> 更新绑定相关生成源码
  -> 继续资源编译和源码编译
  -> 部署阶段应用结果
```

用户需要关注的是：layout 修改不只生成资源 overlay；本轮涉及 `R`、binding class 或 mapper 变化时，还会追加 Java/Kotlin 编译。日志中看到多阶段编译是正常现象。

## 关键边界

- 普通 layout 不会因为开启 ViewBinding 就一定进入 DataBinding mapper。
- DataBinding mapper 依赖上次 Gradle 产出的 layout info 和 BR 基线；缺失时需要 Gradle 重建。
- stripped XML 既是资源产物，也是源码阶段判断 mapper 的输入之一，不能只看 Java 输出判断是否成功。

## 相关页面

- [资源编译](./resource-compile.md)
- [注解器](./annotation-processors.md)
- [源码编译](./source-compile.md)
- [DataBinding / ViewBinding 增量编译原理](../../concepts/incremental-compile/databinding-viewbinding.md)
