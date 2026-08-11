---
title: Android Test 无法运行或测试失败
description: 处理 androidTest 源码、test APK、测试类、测试方法和 instrumentation 运行问题。
status: active
tags:
  - troubleshooting
  - android-test
---

# Android Test 无法运行或测试失败

Jugg 需要从当前 androidTest 源文件确定测试模块、test APK、测试类和测试方法。先修正最靠前的解析错误，再判断是否已经进入 instrumentation。

## Q：提示 `sourcePath` 不存在或不属于 androidTest 怎么办？

1. 使用当前工程中真实存在的 androidTest 源文件路径。
2. 确认文件位于对应模块的 androidTest source root 下。
3. 新增 source set、切换分支或修改工程结构后，先执行 Gradle Sync。
4. Sync 完成后重新运行测试。

## Q：找不到 test APK 怎么办？

看到 `unable to resolve test APK` 或 `Library Test APK missing` 时：

1. 用 Gradle 执行一次对应模块的 androidTest 构建，生成 test APK。
2. 确认当前 Run Configuration 选择了正确的 app 和 test variant。
3. library androidTest 还需要确认对应 library Test APK 已经构建。
4. Gradle 完成后重新从测试源码运行。

## Q：匹配到多个 androidTest 模块或多个 test APK 怎么办？

检查 source root、运行目标和 APK 归属，确保当前源文件只对应一个测试模块和一个 test APK。工程结构刚发生变化时，先 Sync 再重新选择运行目标。

## Q：找不到测试类或测试方法怎么办？

- 一个文件包含多个测试类时，明确指定要运行的 class。
- 指定的 class 不在当前文件时，修正 class 参数或改用正确的源文件。
- 指定的 method 不属于该测试类时，修正 method 参数。
- 通过 gutter 运行时，重新从目标类或方法旁的运行图标触发。

## Q：提示 instrumentation 运行失败怎么办？

如果已经看到 `Instrumentation test run reported failures`，说明测试进程已经启动。此时先查看 Test Results 中的失败用例和测试输出，修复测试断言、初始化或运行环境问题，不需要重新排查 Jugg 编译入口。

如果 instrumentation 没有启动，再检查设备、test APK 和 runner 配置。

## Q：通过 Agent 调用 Android Test 时参数错误怎么办？

`instrument` 需要有效的 `sourcePath`，并要求 class、method、runner 和 extras 使用受支持的组合。参数错误请查看[Agent 或命令执行失败](./agent-command-failed.md)。

## 相关页面

- [Android Test 指南](../guide/android-test.md)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
- [Test Results UI](../capabilities/test/test-results-ui.md)
