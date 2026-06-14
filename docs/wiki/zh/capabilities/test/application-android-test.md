---
title: Application Android Test
description: 说明 Jugg 对 app 模块 androidTest 的运行入口、支持范围和生效机制。
status: active
tags:
  - capability
  - test
  - android-test
---

# Application Android Test

Jugg 支持运行 app 模块的 Android instrumentation test。开启 Android Test 后，app 源码和 `app/src/androidTest` 源码都可以进入 Jugg 的 Android Test 编译、部署和 instrumentation 链路。

## 运行入口与支持范围

| 运行入口 | 当前支持情况 | 结果呈现 |
|---|---|---|
| 从 Jugg App Run Configuration 运行 Android Test | 支持 | 编译、部署后执行 instrumentation |
| 从 `src/androidTest` 的 class gutter 运行 | 支持 | 运行指定 test class |
| 从 `src/androidTest` 的 method gutter 运行 | 支持 | 运行指定 test method |
| 修改 app 源码后重跑测试 | 支持 | 编译产物部署到 app APK |
| 修改 app androidTest 源码后重跑测试 | 支持 | 编译产物部署到对应 test 归属 |
| rerun failed tests | 支持 | 只重跑失败 leaf tests |

> [!IMPORTANT]
> Jugg 不会在用户点击 gutter 时自动修改 App Run Configuration。需要先手动开启 Android Test，并完成一次 Android Test 目标的 Gradle full build。

## Android Test 如何运行

```text
App Run Configuration 开启 Android Test
  -> BuildTarget 切到 ANDROID_TEST
  -> Gradle full build 产出 app APK 与 app test APK
  -> 后续 app / androidTest 源码变更进入增量编译
  -> 部署阶段按 APK 归属写入 app APK 或 test APK
  -> 部署成功后执行 am instrument
```

Jugg 使用 `sourcePath` 作为测试目标锚点。class gutter 会生成 class 级目标，method gutter 会生成 method 级目标；rerun failed tests 会把失败节点转成新的 test filters，不会反写 General 页中的测试范围。

## Target 与 APK 归属

Android Test 模式使用 `BuildTarget.ANDROID_TEST`。这个 target 表示当前运行会话需要同时关注 app 和 androidTest 产物，不表示 androidTest 变成了一个独立 app。

部署阶段会继续使用当前 install / code swap / full swap 策略，并根据 APK 归属拆分部署数据。app-style androidTest 运行在被测 app 进程内；self-targeting library test APK 的处理方式见 [Library Android Test](./library-android-test.md)。

## 当前边界

| 场景 | 当前口径 |
|---|---|
| `org.junit.Test` | 支持 gutter 识别 |
| `org.junit.jupiter.api.Test` | 支持 gutter 识别 |
| Java / Kotlin 测试文件 | 支持 |
| 自定义 androidTest source root | 支持通过 source root 识别 |
| androidTest resource 增量编译 | 暂不覆盖 |
| `androidTestAnnotationProcessor` / `androidTestKapt` | 暂不覆盖 |
| 常驻 test harness 或测试进程内 redefine | 暂不覆盖 |
| Debug Executor | 暂不覆盖 |

## 关联能力

- [Library Android Test](./library-android-test.md)
- [Test Results UI](./test-results-ui.md)
- [Logcat 归因](./logcat-attribution.md)
- [多 APK](../deploy/multi-apk.md)
