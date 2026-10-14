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

## Q：如何让自己的 Agent 分析编译失败？

最终编译失败时，Task Output 末尾会出现 `Ask AI`。点击后预览 prompt，选择 `Copy prompt`，粘贴到能够访问当前 Android 工程的 Agent 中。

Jugg 在失败时保存当次日志、工程上下文和现有编译部署产物，后续运行不会覆盖这份现场。Agent 通过调查指南先了解 Jugg 的工作方式，再结合保存的现场和对应版本源码分析，给出原因、关键依据和可执行的建议。已有材料不足时会指出具体缺失项；需要提交 Issue 时提供可复制的草稿。

也可以从 `Jugg Running Panel` 的 `Jugg Plugin` 分组点击 `Ask AI`，选择 `Compilation failed`，填写问题描述后选择 `Save Evidence and Generate Prompt`。这条入口保存的是采集时的当前现场。

现场保存在本机独立目录，默认保留 30 天。环境信息和采集失败原因保存在现场清单中，Agent 会按 prompt 中的路径读取。prompt 显式允许 Agent 在独立调查目录 clone Jugg，必要时 fetch；调查阶段只读取现有证据。

prompt 使用 Markdown 格式：系统语言为中文时生成中文，其余语言生成英文；Agent 的分析结果和 Issue 内容使用相同语言。用户填写的描述和原始日志保留原文，弹窗说明始终使用英文。

Agent 建议提交 Issue 时，会提供已预填写的 Bug 反馈链接和按字段可复制的完整内容。点击链接后核对标题、问题描述和诊断信息，登录 GitHub，选择适用的表单选项并完成提交确认，再提交。链接过长或未成功预填写时，可将完整内容复制到对应字段；本地现场文件仍需另行提供，链接不会自动附加或上传它们。

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
