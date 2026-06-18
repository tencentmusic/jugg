---
title: Android Test 流程
description: 说明 Android Test 如何复用 Jugg 编译部署链路，并在部署后执行 instrumentation。
status: active
tags:
  - concept
  - android-test
---

# Android Test 流程

Android Test 不是一条独立于 Jugg 的测试通道。它先复用 Jugg 的 Gradle 基线、增量编译、APK 归属和部署策略，再在部署成功后执行 instrumentation，并把输出映射到测试结果视图。

## 运行模型

Android Test 模式使用 `BuildTarget.ANDROID_TEST`。这个 target 表示当前会话同时关注被测 app APK 和 test APK，不表示 androidTest 变成独立应用。

```text
App Run Configuration 开启 Android Test
  -> Gradle full build 产出 app APK 与 test APK 基线
  -> sourcePath 锚定 test class 或 method
  -> app / androidTest 变化进入增量编译
  -> 部署阶段按 APK 归属拆分 deploy data
  -> 部署成功后执行 am instrument
  -> instrumentation 输出进入 console 和 Test Results
```

如果基线缺失、构建参数不可信，或本轮变化超出增量边界，应回到 Gradle 构建。

## APK 归属

Android Test 会同时处理 app APK 和 test APK。app 源码变化部署到被测 app；`src/androidTest` 源码变化部署到对应 test APK。

self-targeting library Android Test 还需要补齐 library test APK。缺失时，Jugg 会按 sourcePath 命中结果做单模块 Test APK 懒加载，并在成功后记录 build history。

## 目标选择

`sourcePath` 是 Android Test 目标锚点：

| 入口 | 目标 |
|---|---|
| class gutter | test class |
| method gutter | test method |
| rerun failed | 失败 leaf tests |
| CLI `instrument` | sourcePath 指向的 class 或 method |

rerun failed 会把失败节点转成新的 test filters，不会反写 Run Configuration 的 General 页测试范围。

## 输出归档

instrumentation 运行后，Jugg 会解析测试输出并渲染到 Run 窗口。logcat 会按设备和测试 method 归档，失败用例可以用于后续 rerun failed。

```text
am instrument output
  -> InstrumentationOutputParser
  -> AndroidTestResultModel
  -> InstrumentationConsoleRenderer / SM Test Runner
```

## 边界

- Android Test 仍依赖可信 Gradle baseline。
- androidTest resource 增量、androidTestAnnotationProcessor / androidTestKapt、常驻 test harness 和 Debug Executor 不是当前主路径。
- 多设备运行时，每台设备的部署和 instrumentation 结果独立收口。

## 相关页面

- [工程上下文获取](./project-model.md)
- [部署策略](./deploy-strategy.md)
- [Android Test 指南](../guide/android-test.md)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
