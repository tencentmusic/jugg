---
title: 依赖库增量编译
description: 说明 Jugg 对 Gradle 依赖变化的检测、确认与增量处理能力。
status: active
tags:
  - capability
  - compile
  - dependency
---

# 依赖库增量编译

Jugg 支持在修改构建文件或依赖声明后，读取 Gradle dependency diff，并在用户确认后把变化的 library 产物纳入增量编译/部署判断。

## 可增量处理的依赖变化

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 构建文件发生变化 | 支持识别 | 先确认是否需要读取依赖 diff |
| library 依赖新增或更新 | 支持按 diff 处理 | 把新 library 产物加入待编译集合 |
| library 依赖删除 | 支持进入部署判断 | 作为 removed library 产物影响部署数据 |
| 用户选择不增量处理依赖 | 支持回退 | 标记需要 Gradle rebuild |

> [!NOTE]
> 依赖库增量编译不是重新执行完整 Gradle 依赖解析 pipeline。Jugg 会借助 Gradle 读取 diff；涉及插件、source set、variant 或 classpath 生成规则变化时，仍建议 Gradle 构建。

## 依赖变化如何处理

```text
发现 build.gradle / 构建文件变化
  -> 询问是否检查依赖变化
  -> 通过 Gradle diff 读取新旧依赖产物
  -> 用户确认增量处理
  -> 新 library 文件加入本轮待编译
  -> removed library 进入部署数据判断
  -> 成功后清理依赖变化状态
```

如果用户取消、diff 失败或本轮不适合增量，Jugg 会把构建文件变化标记为需要 rebuild，后续走 Gradle 回退。

## 使用边界

- 修改依赖版本但 ABI/API 变化较小，更适合尝试依赖库增量。
- 修改 Gradle 插件、source set、variant、annotation processor 或 Kotlin compiler 插件配置时，更适合直接 Gradle。
- 如果依赖变化后出现源码解析失败，Jugg 会尝试更新 compile context 并重试一次；仍失败时建议 Gradle 构建。

## 关联能力

- [Gradle 回退](./gradle-fallback.md)
- [源码编译](./source-compile.md)
- [编译阶段说明](../../guide/compile.md)
