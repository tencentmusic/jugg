---
title: 改动没有生效
description: 处理文件变化未识别、代码或资源仍为旧结果、依赖更新未生效和初始化逻辑未重新执行。
status: active
tags:
  - troubleshooting
  - runtime
  - changes
---

# 改动没有生效

如果 Jugg 显示运行成功，但页面或代码行为仍是旧结果，先判断改动是没有被识别、已经部署但需要重启，还是必须由完整 Gradle 构建更新。

## Q：提示 `no file changes`，但刚修改过文件怎么办？

这通常表示 IDE 尚未把本次文件变化通知给 Jugg，或 Jugg 的工程结构还没有刷新。

1. 取消本轮运行后重新执行一次。
2. 仍然出现时，先执行一次 Gradle Sync，再重新运行。
3. 仍未生效时，执行一次 [降级 Gradle 编译](../guide/downgrade-gradle.md) 。
4. 仍无法恢复，请 [报告问题](../guide/report-issue.md)。

## Q：编译和部署成功，但代码仍是旧逻辑怎么办？

修改启动流程、`static`、`companion object` 或 Kotlin 顶层声明后，如果本轮命中 Hot Reload，旧进程中已经初始化的值不会重新执行。

1. 使用 [重启 App](../guide/restart-app.md) 重启目标进程。
2. 重启后仍未生效时，执行一次 [降级 Gradle 编译](../guide/downgrade-gradle.md) 作为对照。

## Q：修改 style 后没有生效怎么办？

执行一次 [降级 Gradle 编译](../guide/downgrade-gradle.md)，用完整 Gradle 构建刷新结果。

## 相关页面

- [重启 App](../guide/restart-app.md)
- [降级 Gradle 编译](../guide/downgrade-gradle.md)
- [DataBinding/ViewBinding](../capabilities/compile/databinding-viewbinding.md)
- [资源编译](../capabilities/compile/resource-compile.md)
