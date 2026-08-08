---
title: Android Test 流程
description: 解释 Android Test 为什么复用 Jugg 编译部署链路，部署后如何执行 instrumentation，以及当前能力边界。
status: active
tags:
  - concept
  - android-test
---

# Android Test 流程

Android Test 不是一条独立于 Jugg 的测试通道。它先复用 Jugg 的 Gradle 基线、增量编译、APK 归属和部署策略，再在部署成功后执行 instrumentation，把测试结果送回 Run 窗口。

## 测试改一行也走一遍完整构建

androidTest 的日常工作模式是反复改一个测试方法、跑一次、看结果。Gradle 完整构建需要同时处理被测 app 与 test 两个 APK，再完成安装；这些固定开销与“只改了一个测试方法”的变化范围不成比例。但 androidTest 又不能简单当成普通 app run：它同时关注被测 app APK 和 test APK，测试代码的归属、安装顺序和运行方式都和普通启动不同。

## 复用增量链路，部署后再 instrument

Android Test 模式使用构建目标 `BuildTarget=ANDROID_TEST`。这个目标表示当前会话同时关注被测 app APK 和 test APK，并不表示 androidTest 变成一个独立运行的应用。它把测试的编译部署直接接到 Jugg 已有的增量链路上，只在末端补上 instrumentation：

```text
App 运行配置开启 Android Test
  -> Gradle 完整构建产出 app APK 与 test APK 基线
  -> 锚定要运行的测试 class 或 method
  -> app 与 androidTest 源码变化进入增量编译
  -> 部署阶段按 APK 归属拆分，分别投放到被测 app 与 test APK
  -> 部署成功后执行 am instrument
  -> instrumentation 输出进入 Run 窗口与测试结果树
```

改一个测试方法时，走的仍是增量编译和增量部署；只有基线缺失、构建参数不可信或变化超出增量边界时，才回到 Gradle 完整构建。

### APK 归属

Android Test 会同时处理被测 app APK 和 test APK：app 源码变化部署到被测 app；`src/androidTest` 下的测试源码变化部署到对应的 test APK。部署阶段会按应用包名拆分要投放的数据，并保证 app APK 先于 test APK 安装，避免两者互相错投。

self-targeting 的 library Android Test 还需要补齐一个 library test APK。这个 APK 缺失时，Jugg 会按当前锚定的测试目标只为命中的那个模块补一次 test APK 构建，成功后记录构建历史，供后续直接复用，而不是每次都全量重建。

### 目标选择

测试目标由当前锚定的源码位置决定，几个入口对应不同粒度：

| 入口 | 目标 |
|---|---|
| class 行号标记（gutter） | 整个测试 class |
| method 行号标记（gutter） | 单个测试 method |
| rerun failed | 上一轮失败的叶子用例 |
| CLI `instrument` | 锚定位置指向的 class 或 method |

rerun failed 会把失败节点转成新的测试过滤条件单独重跑，不会反写运行配置 General 页里的测试范围。

## 输出归档

instrumentation 运行后，Jugg 会解析 `am instrument` 的输出并渲染到 Run 窗口的测试结果树，支持测试节点的源码跳转与失败用例 rerun。logcat 会按设备和测试 method 归档：每台设备从本轮启动时刻开始采集，再按测试 method 的生命周期边界把日志归到对应用例，避免历史日志污染当前方法详情。多设备运行时，每台设备的部署和 instrumentation 结果各自独立收口。

大范围回归可以分两步走：先用一次 CLI `instrument` 让 Jugg 完成编译、部署和目标 APK 刷新；该命令成功后，app 与 androidTest 的源码变更都已写入对应 APK，此时可以直接用 `adb shell am instrument` 跑更大范围的 class / package / suite 回归。

## 边界

- Android Test 仍依赖可信的 Gradle 基线；基线缺失或不可信时回到 Gradle。
- androidTest 资源增量、androidTest 专用注解处理、常驻 test harness 和 Debug 执行器不是当前主路径。
- **instrumentation 结果与部署状态分离**：测试断言失败仍按测试失败返回，但已成功的部署状态会正常推进，避免下次重跑因状态错位而重新编译或重装。
- 多设备运行时，每台设备的部署与 instrumentation 结果独立收口，任一设备失败都会让整轮 Run 失败。

## 相关页面

- [工程上下文获取](./project-model.md)
- [部署策略](./deploy-strategy.md)
- [Android Test 指南](../guide/android-test.md)
- [测试能力](../capabilities/test/)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
- [Test Results UI](../capabilities/test/test-results-ui.md)
- [Android Test CLI](../capabilities/tools/cli-android-test.md)
