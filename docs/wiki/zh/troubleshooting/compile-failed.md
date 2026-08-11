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

## Q：Java 或 Kotlin 提示找不到符号怎么办？

常见提示包括 `unresolved reference` 和 `cannot find symbol`。

1. 先按第一条错误修正源码，确认文件已经保存。
2. 如果刚修改过依赖、source set、生成源码配置或切换分支，执行 Gradle Sync。
3. Sync 完成后重新运行 Jugg。
4. 仍然失败时，执行一次完整 Gradle 构建，重新建立 classpath 和生成源码基线。

如果 Gradle 也失败，继续按 Gradle 给出的编译错误修复工程；这不是 Jugg 增量编译问题。

## Q：增量编译失败，并提示下次会回退 Gradle，怎么办？

看到 `Found incremental compile error` 后，可以直接重新运行。下一次运行会改走 Gradle，成功后使用新的 APK、classpath 和资源产物作为后续增量基线。

如果不想等待自动回退，也可以直接使用[降级 Gradle 编译](../guide/downgrade-gradle.md)。

## Q：aapt2 或资源编译失败怎么办？

1. 如果错误明确指向 XML、资源名、引用或重复资源，先修正对应资源。
2. 如果错误发生在资源 link、资源表、dynamic feature 或资源混淆阶段，执行一次完整 Gradle 构建。
3. Gradle 成功后重新运行 Jugg，确认后续普通资源修改能够继续增量处理。

首次启用 DataBinding/ViewBinding、修改资源目录、variant 或资源生成配置时，也应先 Sync 并完成一次 Gradle 构建。当前支持范围见[资源编译](../capabilities/compile/resource-compile.md)。

## Q：Manifest 编译失败怎么办？

先修正 Run 输出中的 Manifest 语法或属性错误。涉及 placeholder、`tools:*` merge、删除节点、variant 或构建脚本变化时，使用完整 Gradle 构建生成新的 merged manifest。

## Q：注解或生成源码相关文件编译失败怎么办？

Jugg 只对明确列出的注解入口提供增量处理。

1. 如果刚修改 processor 依赖、compiler plugin、参数或生成规则，先 Sync。
2. 对未支持的 APT、KAPT 或 KSP processor，执行对应 Gradle task 重新生成源码。
3. Gradle 完成后再继续普通源码增量编译。

不要沿用旧资料中“所有注解都不支持”或“所有注解都支持”的判断。以[注解器支持范围](../capabilities/compile/annotation-processors.md)为准。

## Q：只有 release 增量编译失败怎么办？

先用相同 variant 执行一次完整 Gradle release 构建。如果 Gradle 也失败，按 R8、mapping 或源码错误处理；如果只有 Jugg 增量失败，先使用 Gradle 结果继续开发，再[报告问题](../guide/report-issue.md)。

## Q：Gradle 能成功，Jugg 仍然失败怎么办？

1. 确认已经完成 Sync。
2. 使用完整 Gradle 构建刷新一次基线。
3. 只做一个小范围源码或资源修改，再重新运行 Jugg。
4. 如果仍能稳定复现，使用[报告问题](../guide/report-issue.md)上传现场，并说明本轮修改的文件。

## 相关页面

- [改动没有生效](./changes-not-applied.md)
- [编译阶段说明](../guide/compile.md)
- [降级 Gradle 编译](../guide/downgrade-gradle.md)
- [Gradle 回退](../capabilities/compile/gradle-fallback.md)
