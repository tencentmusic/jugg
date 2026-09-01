---
title: Android Test 运行或测试失败
description: 处理 androidTest 源码、test APK、测试类、测试方法和 instrumentation 运行问题。
status: active
tags:
  - troubleshooting
  - android-test
---

# Android Test 运行或测试失败

Jugg 需要从当前 androidTest 源文件确定测试模块、test APK、测试类和测试方法。先修正最靠前的解析错误，再判断是否已经进入 instrumentation。

如果无法通过错误信息解决，请 [报告问题](../guide/report-issue.md)。

## 相关页面

- [Android Test 指南](../guide/android-test.md)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
- [Test Results UI](../capabilities/test/test-results-ui.md)
