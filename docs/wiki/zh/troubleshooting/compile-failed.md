---
title: 编译失败
description: 处理 Jugg Java、Kotlin、资源、Manifest、注解生成源码和 release 编译失败。
status: active
tags:
  - troubleshooting
  - compile
---

# 编译失败

编译失败时，先处理 Run 窗口中第一条明确的源码或资源错误。只有 Gradle 能成功而 Jugg 仍失败时，才把问题判断为增量编译差异。

## Q：Java 或 Kotlin 提示找不到符号

常见提示包括 `unresolved reference` 和 `cannot find symbol`。

1. 执行一次 Gradle Sync。
2. 仍然失败时，执行一次完整 Gradle 构建，重新建立 classpath 和生成源码基线。
3. 如果 Gradle 失败，通常可说明不是 Jugg 带来的问题；
4. 如果 Gradle 成功，Jugg 仍能稳定复现失败，使用[报告问题](../guide/report-issue.md)向维护者反馈。

## Q：注解或生成源码相关文件编译失败

Jugg 已处理 Compose、`@Parcelize`、ViewBinding 和 DataBinding。其它注解器的支持范围以[注解器支持范围](../capabilities/compile/annotation-processors.md)为准。对于尚未支持的注解器：

* 修改不会影响已有生成代码时，增量编译可以继续使用。
* 新增或修改注解、或修改注解参数时，生成代码不会更新；执行一次[降级 Gradle 编译](../guide/downgrade-gradle.md)。


## Q：Gradle 能成功，Jugg 仍然失败

1. 先尝试 Android Studio Sync 一次，看问题是否解决；
2. 如果仍能稳定复现，使用[报告问题](../guide/report-issue.md)向维护者反馈。

## 相关页面

- [改动没有生效](./changes-not-applied.md)
- [编译阶段说明](../guide/compile.md)
- [降级 Gradle 编译](../guide/downgrade-gradle.md)
- [Gradle 回退](../capabilities/compile/gradle-fallback.md)
