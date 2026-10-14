---
title: 部署后 App 崩溃
description: 处理 Jugg 编译部署成功后的 Java 异常、native crash 和增量结果差异。
status: active
tags:
  - troubleshooting
  - crash
  - runtime
---

# 部署后 App 崩溃

本页只处理编译和部署已经完成、App 随后崩溃的情况。App 无法安装、无法启动或部署阶段已经失败时，请查看[无法安装、启动或进入 Debug](./app-cannot-run.md)。

## Q：如何保存崩溃现场并交给自己的 Agent？

在重建或重新安装前，从 `Jugg Running Panel` 的 `Jugg Plugin` 分组点击 `Ask AI`。选择 `Runtime crash`，填写崩溃操作、预期结果和实际结果，选择需要采集日志的设备，然后点击 `Save Evidence and Generate Prompt`。

Jugg 保存当前日志、按设备区分的 logcat，以及现存 APK 和增量部署产物。logcat 包含崩溃缓冲区，但只覆盖设备仍保留的最近日志；设备断开或部分采集失败时，其余有效证据仍会保存，现场清单会记录缺失项，Agent 按 prompt 中的路径读取。

采集完成后选择 `Copy prompt`，粘贴到能够访问当前 Android 工程的 Agent。Agent 按 prompt 读取 Jugg 知识库和源码，输出根因证据、建议和未解决时的下一步；需要提交 Issue 时提供草稿。prompt 显式允许在独立调查目录 clone Jugg，必要时 fetch；调查阶段只读取现有证据。

现场保存在本机独立目录，后续构建和 Clear Jugg Build 不会删除这份副本，默认保留 30 天。请在 Jugg 任务结束后采集；本地部署记录和产物本身不能证明设备已加载相同内容。

prompt 使用 Markdown 格式：系统语言为中文时生成中文，其余语言生成英文；Agent 的分析结果和 Issue 内容使用相同语言。用户填写的描述和原始日志保留原文，弹窗说明始终使用英文。

Agent 建议提交 Issue 时，会提供已预填写的 Bug 反馈链接和按字段可复制的完整内容。点击链接后核对标题、问题描述和诊断信息，登录 GitHub，选择适用的表单选项并完成提交确认，再提交。链接过长或未成功预填写时，可将完整内容复制到对应字段；本地现场文件仍需另行提供，链接不会自动附加或上传它们。

## Q：出现 `NoSuchMethodError`、`AbstractMethodError` 或类似异常怎么办？

这些异常通常表示当前运行时类结构与调用方不一致。相同类型的异常还包括：

- `NoSuchFieldError`
- `IllegalAccessError`
- `IncompatibleClassChangeError`
- `NoClassDefFoundError`

处理步骤：

1. 执行一次完整 Gradle 构建和安装。
2. 如果 Gradle 结果仍然崩溃，按工程自身的依赖、混淆或代码问题处理。
3. 如果 Gradle 恢复正常、只有 Jugg 增量结果崩溃，保留当前修改并[报告问题](../guide/report-issue.md)。

单纯重启 App 通常不能修复类结构或 DEX 引用不一致。

## Q：部署资源后出现 `AssetManager` native crash 怎么办？

部分 Android 11 OEM 系统与 Apply Changes 的资源部署存在兼容性问题。

1. 连接问题设备。
2. 在 Jugg More Options 中为该设备开启兼容模式。
3. 重新运行本轮修改。
4. 仍然崩溃时执行 Clean And Reinstall，再重新验证。

兼容模式会使用经典热修复部署，并为该设备持久化设置。

## Q：修改 DataBinding/ViewBinding XML 后崩溃/不生效怎么办？

DataBinding/ViewBinding 当前支持增量生成绑定相关源码。确认 Gradle 正常而 Jugg 稳定复现时，[报告问题](../guide/report-issue.md)。

## Q：不知道崩溃是否与 Jugg 增量编译有关怎么办？

最直接的判断方式是使用相同 variant 执行一次完整 Gradle 构建并安装：

- Gradle 结果也崩溃：先修复工程本身的问题。
- Gradle 结果正常：继续使用 Gradle 结果，并[报告问题](../guide/report-issue.md)。

## 相关页面

- [设备兼容部署](../guide/compat-device.md)
- [Clean Reinstall](../guide/clean-data.md)
- [release 编译](../capabilities/compile/release-compile.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
