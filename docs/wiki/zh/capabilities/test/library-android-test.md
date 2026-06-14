---
title: Library Android Test
description: 说明 Jugg 对 library self-targeting Android Test 的 Test APK 补齐、部署和运行能力。
status: active
tags:
  - capability
  - test
  - library-android-test
---

# Library Android Test

Jugg 支持运行 library 模块的 self-targeting Android Test。此类测试有自己的 Test APK 和 runtime package，Jugg 会在 sourcePath 命中目标 library androidTest 后，确保本轮部署和 instrumentation 使用正确的 Test APK。

## Library 测试场景

| 用户场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 从 library `src/androidTest` 运行测试 | 支持 | sourcePath 命中对应 androidTest module |
| 当前 APK 列表缺少 library Test APK | 支持补齐 | 只为命中的 module 执行对应 AndroidTest assemble task |
| 安装 self-targeting library Test APK | 支持 | 作为独立 runtime package 部署 |
| 后续复用近期 Test APK build 记录 | 支持 | Android Test full build 时回放近期 task |
| 多 APK 场景下写入正确目标 | 支持 | 通过 target APK 归属过滤部署项 |

> [!NOTE]
> 这里的 Library Android Test 指 self-targeting Test APK，也就是 test package 与 instrumentation target package 对齐的 library 测试形态。app-style other-targeting test APK 不走这里的缺失 APK 懒加载补齐。

## Test APK 如何补齐

```text
sourcePath 指向 library src/androidTest
  -> 唯一命中 androidTest module
  -> 当前 APK 列表缺少该 module 的 Test APK
  -> 确认它是 self-targeting Test APK
  -> 执行对应 assemble<Variant>AndroidTest
  -> 把新 Test APK 合入本轮部署目标
  -> 安装后更新部署历史与 overlay id
```

Jugg 只补齐当前 `sourcePath` 精确命中的 library Test APK，避免一次测试运行扩散成全项目 test APK 构建。补齐后的 Test APK 会进入本轮 APK 列表，并按独立 runtime package 参与部署。

## 与 Application Android Test 的区别

| 对比项 | Application Android Test | Library Android Test |
|---|---|---|
| 常见源码位置 | app `src/androidTest` | library `src/androidTest` |
| 运行进程 | 被测 app 进程 | self-targeting test package |
| Test APK 来源 | Android Test full build 产出 | 可由命中的 library androidTest 懒加载补齐 |
| 部署策略 | app APK 与 app test APK 按归属分流 | library Test APK 作为独立目标部署 |

## Build history

当 library Test APK 构建成功并通过目标校验后，Jugg 会记录对应 module 的 AndroidTest Gradle task 和 APK output pattern。后续 Android Test full build 可以回放近期记录，减少再次手动补齐 Test APK 的概率。

历史记录只用于重新找到近期 library Test APK 构建任务，不代表 Test APK 永久有效；如果产物不存在，Jugg 会跳过该 optional APK，并在需要时重新进入补齐流程。

## 关联能力

- [Application Android Test](./application-android-test.md)
- [Test Results UI](./test-results-ui.md)
- [Logcat 归因](./logcat-attribution.md)
- [多 APK](../deploy/multi-apk.md)
