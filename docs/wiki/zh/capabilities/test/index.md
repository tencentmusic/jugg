---
title: 测试能力
description: 概览 Jugg 对 Application Android Test、Library Android Test、测试结果展示和 logcat 归因的支持范围。
status: active
tags:
  - capability
  - test
---

# 测试能力

Jugg 支持在保留增量编译与部署能力的同时运行 Android instrumentation test。用户可以从 app 的 `androidTest`、library 的 self-targeting `androidTest`、测试结果树和方法级 logcat 四个角度判断当前能力是否适用。

## 能力入口

| 用户场景 | 当前支持情况 | 入口 |
|---|---|---|
| 运行 app 模块的 instrumentation test | 支持 | [Application Android Test](./application-android-test.md) |
| 运行 library 模块的 self-targeting instrumentation test | 支持 | [Library Android Test](./library-android-test.md) |
| 在 Run 窗口查看测试树、失败节点和重跑失败用例 | 支持 | [Test Results UI](./test-results-ui.md) |
| 把 logcat 归到具体测试方法 | 支持 | [Logcat 归因](./logcat-attribution.md) |

> [!IMPORTANT]
> 第一次进入 Android Test 模式前，需要在 Jugg App Run Configuration 中开启 Android Test，并完成一次 Android Test 目标的 Gradle full build。这样 Jugg 才能拿到 app APK、test APK、runner 和测试模块信息。

## 测试运行如何生效

```text
选择测试入口
  -> Android Test RunSpec 记录 sourcePath、class 或 method
  -> BuildTarget 切到 ANDROID_TEST
  -> 编译 app 与 androidTest 产物
  -> 按 APK 归属部署 app APK / test APK
  -> 部署成功后执行 am instrument
  -> Run 窗口展示测试树、失败信息和方法级 logcat
```

`Application Android Test` 是最常见入口，适合 app 模块的 `src/androidTest`。`Library Android Test` 处理有独立 self-targeting Test APK 的 library 场景，重点是补齐和安装正确的 Test APK。两类测试最终都通过 instrumentation 运行，并共享 Test Results UI 与 logcat 归因能力。

## 当前边界

| 场景 | 当前口径 |
|---|---|
| app 源码变更 | 支持进入 Android Test 增量编译与部署 |
| `app/src/androidTest` 源码变更 | 支持进入 Android Test 增量编译与部署 |
| library self-targeting `src/androidTest` 源码变更 | 支持，必要时补齐 Test APK |
| androidTest resource 增量编译 | 暂不覆盖 |
| `androidTestAnnotationProcessor` / `androidTestKapt` | 暂不覆盖 |
| Debug Executor | 暂不覆盖 |

## 相关页面

- [Android Test 指南](../../guide/android-test.md)
- [Android Test 流程](../../concepts/android-test-flow.md)
- [Application Android Test](./application-android-test.md)
- [Library Android Test](./library-android-test.md)
- [Test Results UI](./test-results-ui.md)
- [Logcat 归因](./logcat-attribution.md)
- [多 APK](../deploy/multi-apk.md)
