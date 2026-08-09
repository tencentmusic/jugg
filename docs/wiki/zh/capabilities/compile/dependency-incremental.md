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

Jugg 支持在修改构建文件或依赖声明后，读取 Gradle dependency diff，并经过两次用户确认把变化的 library 产物纳入增量编译和部署判断。本页只说明可增量处理的场景；依赖基线、library diff 和回退原理见[依赖库增量编译原理](../../concepts/incremental-compile/dependency-incremental.md)。

## 可增量处理的依赖变化

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 构建文件仅修改依赖声明 | 支持检查 | Jugg 展示构建文件 diff，并询问是否读取依赖变化 |
| library 依赖新增或更新 | 支持按内容差分处理 | 变化的 class、资源、Manifest、assets 和 native lib 进入对应增量阶段 |
| 依赖版本回到完整 Gradle 基线 | 仅支持字节码回退 | 移除此前增量部署的 library DEX，恢复使用 APK 内的基线字节码 |
| 用户选择不增量处理依赖 | 支持回退 | 本轮转为 Gradle 构建，重新建立基线 |

> [!NOTE]
> Jugg 会运行 Gradle 读取当前依赖图和 library diff，但不会执行完整 assemble、DEX、资源链接和 APK 打包流程。涉及插件、source set、variant 或 classpath 生成规则变化时，仍需 Gradle 构建。

## 触发与结果

```text
构建文件变化
  -> 展示构建文件 diff，询问是否检查依赖变化
  -> 用户确认后通过 Gradle 读取依赖 diff
  -> 展示 library 变化，再次确认增量处理或回退 Gradle
  -> 可增量处理的 library 变化进入本轮
  -> 不适合增量时回退 Gradle
```

依赖 diff 失败或用户选择回退时，本轮转为 Gradle 构建。用户取消确认时，本轮停止，未处理的构建文件变化会保留到后续运行。

## 使用边界

- 只有能够确认构建文件仅包含依赖声明变化时，才应选择依赖库增量编译。
- 选择忽略构建文件变化时，Jugg 不会验证新旧脚本等价；后续出现 classpath、生成代码或打包结果异常时，需要 Gradle 构建。
- library DEX 可以回退；如果依赖版本回退还需要恢复资源、Manifest、assets 或 native lib，则需要 Gradle 重新构建。
- 修改 Gradle 插件、source set、variant、annotation processor 或 Kotlin compiler 插件配置时，需要直接使用 Gradle。
- 如果依赖变化后出现源码解析失败，Jugg 会尝试更新 compile context 并重试一次；仍失败时需要 Gradle 构建。

## 相关页面

- [Gradle 回退](./gradle-fallback.md)
- [源码编译](./source-compile.md)
- [编译阶段说明](../../guide/compile.md)
- [依赖库增量编译原理](../../concepts/incremental-compile/dependency-incremental.md)
