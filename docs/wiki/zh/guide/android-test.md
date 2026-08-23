---
title: Android Test
description: 介绍如何用 Jugg 运行 androidTest，并理解 test APK、Test Results、CLI instrument 和当前边界。
status: active
tags:
  - guide
  - android-test
---

# Android Test

Jugg 支持以增量编译方式运行 `src/androidTest`。使用体验尽量贴近 Android Studio 自带 Instrumented Tests：你可以从 gutter 运行 class 或 method，Jugg 会先编译和部署，再执行 `am instrument`，并把结果展示到 Test Results。

## 前置条件

首次使用前需要完成一次 AndroidTest 基线构建：

1. 打开对应 App 的 Jugg Run Configuration。
2. 开启 Android Test / `enableAndroidTest`。
3. 运行一次 Jugg Android Test 或执行一次 `jugg gradle-build`，让 Jugg 生成 app APK 与 test APK 基线。
4. 后续修改 app 源码或 `src/androidTest` 源码时，再进入增量编译。

如果 CLI `jugg status` 返回 `enabledAndroidTest=false`，请先完成上述步骤，不要直接调用 `jugg instrument`。

## 从 IDE 运行

在 `src/androidTest` 的测试类或测试方法旁点击 Jugg gutter 即可运行。Jugg 会生成临时 Run Configuration，运行范围可以是：

| Scope | 含义 |
|---|---|
| Class | 运行指定测试类 |
| Method | 运行指定测试方法 |

运行时 Jugg 会使用测试源文件路径作为 `sourcePath`，用它解析测试类、方法、所属 androidTest module 以及目标 Test APK。

## 从 CLI 运行

常用命令：

```bash
jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt
jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt --class com.example.FooTest
jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt --class com.example.FooTest --method testLogin
```

可选参数：

| 参数 | 用途 |
|---|---|
| `--source-path` | 必填，用于定位测试源码、模块和 test APK |
| `--class` | 指定测试类；单 class 文件可省略 |
| `--method` | 指定测试方法 |
| `--runner` | 覆盖 instrumentation runner |
| `--extras` | 传递额外 `-e key value` 参数，格式如 `foo=bar;debug=true` |

当前不支持用 package 或 regex 作为测试入口；多 test APK 场景必须通过 `sourcePath` 定位。

## 运行链路

```text
androidTest gutter / jugg instrument
  -> 解析 sourcePath、测试 class/method 和 test APK
  -> 以 AndroidTest target 编译 app 与测试代码
  -> 部署 app APK 和 test APK
  -> 执行 am instrument
  -> 输出 Test Results 树和日志
```

部署成功和测试成功是两件事：如果 instrumentation 断言失败，Jugg 仍会保留已经成功部署的历史状态，下一次重跑不会因为这次测试失败而重新编译所有内容。

## Library Android Test

Jugg 也支持 library-style self-targeting Test APK。首次运行某个 library 的 androidTest 时，如果缺少对应 Test APK，Jugg 会提示需要执行一次 Gradle 构建来生成基线。之后 Jugg 会记录近期构建过的 library Test APK，后续 Gradle baseline 会自动回放最近记录，减少等待。

## Test Results

Android Test 会使用 Test Results UI：

- 单设备运行时直接展示 class / method 节点。
- 多设备运行时按设备分组展示。
- 失败节点支持 source navigation。
- 支持 rerun failed tests，重跑时保留原 runner 和 extras。
- method 级 logcat 会尽量归因到对应测试节点。

## 当前边界

- 当前不支持 androidTest Debug Executor。
- 不覆盖 androidTest resource 增量编译。
- 不覆盖 `androidTestAnnotationProcessor` / `androidTestKapt`。
- app-style other-targeting test APK 的懒加载补齐不是当前主要路径。
- 运行失败时要区分编译失败、部署失败和测试断言失败。

## 相关页面

- [Android Test 流程](../concepts/android-test-flow.md)
- [测试能力](../capabilities/test/)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
- [Test Results UI](../capabilities/test/test-results-ui.md)
- [CLI Android Test](../capabilities/tools/cli-android-test.md)
- [Android Test 运行或测试失败](../troubleshooting/android-test-failed.md)
