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

Jugg 支持在修改构建文件或依赖声明后，读取 Gradle dependency diff，并在用户确认后把变化的 library 产物纳入增量编译/部署判断。本页只说明可增量处理的场景；两步确认、library diff 和回退原因见[依赖库增量编译原理](../../concepts/incremental-compile/dependency-incremental.md)。

## 可增量处理的依赖变化

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 构建文件发生变化 | 支持识别 | Jugg 会询问是否检查依赖变化 |
| library 依赖新增或更新 | 支持按 diff 处理 | 新 library 产物进入本轮增量判断 |
| library 依赖删除 | 支持进入部署判断 | 删除影响会进入后续编译/部署判断 |
| 用户选择不增量处理依赖 | 支持回退 | 本轮转为 Gradle 构建，重新建立基线 |

> [!NOTE]
> 依赖库增量编译不是重新执行完整 Gradle 依赖解析 pipeline。Jugg 会借助 Gradle 读取 diff；涉及插件、source set、variant 或 classpath 生成规则变化时，仍建议 Gradle 构建。

## 触发与结果

```text
构建文件变化
  -> 询问是否检查依赖变化
  -> 用户确认后读取依赖 diff
  -> 可增量处理的 library 变化进入本轮
  -> 不适合增量时回退 Gradle
```

如果用户取消、diff 失败或本轮不适合增量，Jugg 会把构建文件变化视为需要 rebuild，后续走 Gradle 回退。

## 使用边界

- 修改依赖版本但 ABI/API 变化较小，更适合尝试依赖库增量。
- 修改 Gradle 插件、source set、variant、annotation processor 或 Kotlin compiler 插件配置时，更适合直接 Gradle。
- 如果依赖变化后出现源码解析失败，Jugg 会尝试更新 compile context 并重试一次；仍失败时建议 Gradle 构建。

## 相关页面

- [Gradle 回退](./gradle-fallback.md)
- [源码编译](./source-compile.md)
- [编译阶段说明](../../guide/compile.md)
- [依赖库增量编译原理](../../concepts/incremental-compile/dependency-incremental.md)
