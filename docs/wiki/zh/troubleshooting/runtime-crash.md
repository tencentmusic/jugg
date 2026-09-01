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

部分设备系统与 Apply Changes 的资源部署存在兼容性问题，尤其是 Android 11 的 Oppo/Vivo 设备。

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
