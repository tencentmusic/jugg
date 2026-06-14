---
title: Test Results UI
description: 说明 Jugg Android Test 在 Run 窗口中的测试树、失败展示、源码跳转和 rerun failed 能力。
status: active
tags:
  - capability
  - test
  - test-results
---

# Test Results UI

Jugg 支持把 Android Test 结果展示到 IntelliJ / Android Studio 的 Test Results UI。测试运行仍由 Jugg 完成编译、部署和 instrumentation；SM Test Runner 负责展示测试树、失败详情、源码跳转和 rerun failed。

## 结果展示范围

| 用户场景 | 当前支持情况 | 结果呈现 |
|---|---|---|
| 查看测试 class / method 树 | 支持 | Run 窗口 Test Results tab |
| 单设备运行 | 支持 | 隐藏设备层，直接展示 class / method |
| 多设备运行 | 支持 | 按设备分组展示 class / method |
| 跳转到测试源码 | 支持 | class / method 节点使用 Java test location |
| 重跑失败用例 | 支持 | failed leaf tests 转成 test filters |
| 查看多设备结果矩阵 | 支持 | 文本矩阵展示每个测试在各设备上的状态 |

## Test Results 如何接入

```text
Android Test run 创建 SM Runner console
  -> InstrumentationOutputParser 解析 am instrument 输出
  -> InstrumentationSmRunnerBridge 生成 test started / finished / failed 事件
  -> SM Test Runner 展示测试树
  -> rerun failed 把失败节点转回 AndroidTestRunSpec
```

普通 app run 仍使用普通 text console；只有 Android Test run 会创建 Test Results UI。编译和部署阶段的 Jugg 日志仍会显示在 Run 输出中，测试节点详情只承载 instrumentation 事件和方法级 logcat。

## 设备展示规则

| 运行设备 | 展示方式 |
|---|---|
| 单设备 | 隐藏 device suite，减少一层树结构 |
| 多设备 | 展示 device suite，避免不同设备结果混在一起 |
| 设备详情 | 展示 serial、name、API 和设备级 instrumentation 原始日志 |

多设备运行时，Jugg 会按设备顺序执行 instrumentation，并把每台设备的结果写入同一个 Test Results session。任一设备出现 instrumentation 非 0 退出、aborted、failure、error 或 assumption failure，本轮 Android Test run 都会失败。

## Rerun failed

Rerun failed 只收集失败的 leaf test 节点，并生成新的 test filters。它会保留原 runner override 与 instrumentation arguments，但不会修改 Run Configuration General 页中的 class / method scope。

## 关联能力

- [Application Android Test](./application-android-test.md)
- [Library Android Test](./library-android-test.md)
- [Logcat 归因](./logcat-attribution.md)
