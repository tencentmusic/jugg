---
title: KMP 源码增量编译
description: 解释 Jugg 如何依据 Gradle 编译模型补齐 KMP expect/actual 源码，并隔离过期 Kotlin 输出。
status: active
tags:
  - concept
  - compile
  - kmp
  - kotlin
---

# KMP 源码增量编译

KMP 的 Android 目标不仅包含当前修改的 Kotlin 文件，还可能依赖 common、Android 平台和中间 source set 中的 `expect` / `actual` 声明。Jugg 从 Gradle 同步结果恢复这些关系，在增量编译时补齐必要源码，而不是把目录结构当作编译模型。

## `expect` / `actual` 关系不等于目录配对

同名文件、同名声明或 `commonMain` / `androidMain` 目录只能提供线索，不能完整描述 Kotlin 编译关系。例如项目可能使用 `sharedMain` 等中间 source set，也可能在普通 Android 模块中创建同名目录。

因此，Jugg 不根据文件名或目录名猜测互补源码。只有 Gradle 模型确认某个 source set 参与当前 Android 编译时，它才会进入对应的增量编译上下文。

## Gradle 编译模型确定 Android 目标

Gradle 同步会提供 Android 目标对应的 Kotlin 编译、公共源码目录以及 source set fragment 依赖关系。Jugg 使用这些信息回答三个问题：

- 当前文件是否属于 KMP 的 Android 编译目标；
- 哪些公共、平台或中间 source set 与它共同参与编译；
- 本轮 Kotlin 编译需要使用哪些目标参数和依赖。

项目新增 target、调整 source set 层级或修改 Kotlin 编译参数后，需要先同步 Gradle，让这些关系进入新的编译基线。

## 互补源码如何进入同一轮编译

每次成功的项目编译都会记录能够确认的源码互补关系。后续只修改其中一侧时，Jugg 会把另一侧的必要源码补入本轮输入：

```text
本轮修改的 KMP 源码
  → 定位所属 Android 编译目标
  → 从已确认关系中补齐 common、platform 或中间 source set 源码
  → 使用同一套 Kotlin 编译参数完成编译
```

如果互补信息缺失或存在歧义，Jugg 采用 Best-effort：保留当前能够确认的源码输入，不按名称强行配对。这样可以避免把不相关文件加入编译；如果必要源码确实无法恢复，本轮会保留真实编译错误，提示使用 Gradle 刷新基线。

## 过期 Kotlin 输出为什么会干扰编译

Kotlin 1.9 场景可能把上一轮 `expect` / `actual` 相关输出重新加入增量分析。如果这些输出与本轮源码同时出现，编译器可能把同一声明识别两次，产生重复声明或错误的符号解析。

Jugg 会依据源码与历史输出的映射，在本轮编译输入中隔离可能冲突的旧输出。正式 Gradle 基线文件不会被移动或删除；处理只影响当前 Jugg 编译过程。

## 只有成功编译才推进互补关系

互补关系来自最近一次成功的项目编译。失败编译中的输入可能不完整，不能成为下一轮的可靠依据。因此，Jugg 只在 Kotlin 编译成功后更新关系记录；失败时保留原有基线和最终异常。

## 使用边界

- 首次启用 KMP、变更 target 或重组 source set 后，先执行 Gradle 同步和完整 Gradle 编译。
- 普通 Android 模块中的目录名不会自动触发 KMP 处理。
- 删除源码可能需要清理历史输出和重新计算关系，应使用 Gradle 编译。
- 遇到无法补齐的 `expect` / `actual`、fragment 或符号解析错误时，使用 Gradle 编译刷新项目模型和输出基线。

## 相关页面

- [KMP 与 Compose Multiplatform](/zh/capabilities/compile/kmp-compose-multiplatform)
- [源码增量编译](/zh/concepts/incremental-compile/source)
- [项目模型](/zh/concepts/project-model)
- [工程信息刷新与恢复](/zh/concepts/project-info-refresh)
- [Gradle 编译回退](/zh/capabilities/compile/gradle-fallback)
