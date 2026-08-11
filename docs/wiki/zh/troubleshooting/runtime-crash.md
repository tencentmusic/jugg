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

部分设备系统与 Apply Changes 的资源部署存在兼容差异。

1. 连接问题设备。
2. 在 Jugg More Options 中为该设备开启兼容模式。
3. 重新运行本轮修改。
4. 仍然崩溃时执行 Clean And Reinstall，再重新验证。

兼容模式会使用经典热修复部署，并为该设备持久化设置。

## Q：看到 JVMTI 兼容提示后仍然崩溃怎么办？

如果输出提示 Jugg 已切换兼容部署，先等待本轮自动恢复完成。App 仍然崩溃时，手动开启该设备的兼容模式并重新运行；设备安装状态已经不可信时，再执行 Clean And Reinstall。

## Q：修改 DataBinding/ViewBinding XML 后崩溃怎么办？

DataBinding/ViewBinding 当前支持增量生成绑定相关源码。不要直接沿用旧资料中的“不支持”结论。

1. 如果刚启用 DataBinding/ViewBinding、升级 AGP 或修改相关 Gradle 配置，先 Sync 并执行一次完整 Gradle 构建。
2. 如果普通 layout 修改只在 Jugg 增量运行后崩溃，使用 Gradle 构建对照。
3. Gradle 正常而 Jugg 稳定崩溃时，[报告问题](../guide/report-issue.md)。

## Q：不知道崩溃是否与增量部署有关怎么办？

最直接的判断方式是使用相同 variant 执行一次完整 Gradle 构建并安装：

- Gradle 结果也崩溃：先修复工程本身的问题。
- Gradle 结果正常：继续使用 Gradle 结果，并提交 Jugg 问题现场。

## 相关页面

- [设备兼容部署](../guide/compat-device.md)
- [Clean Reinstall](../guide/clean-data.md)
- [release 编译](../capabilities/compile/release-compile.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
