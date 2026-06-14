---
title: Android Test 流程
description: 解释 Jugg 如何编译、部署并运行 androidTest instrumentation。
status: active
tags:
  - concept
  - android-test
---

# Android Test 流程

Jugg 的 Android Test 支持不是一条独立测试通道，而是复用 Jugg 的编译和部署链路：先保证 app 与 test APK 的产物正确，再在部署完成后执行 instrumentation。

## Android Test 和普通 App Run 的区别

| 项目 | 普通 App Run | Android Test Run |
|---|---|---|
| 编译目标 | App variant | App variant + androidTest variant |
| 启动方式 | 启动 App | 执行 `am instrument` |
| 目标定位 | 运行配置和 APK | 测试源文件、class/method、test APK |
| 结果展示 | Run 输出 | Run 输出 + Test Results |
| 无文件变化 | 可能提示回退或直接部署 | 可以直接重跑 instrumentation |

切换到 Android Test 目标时，Jugg 通常需要一次完整 Gradle 构建来建立 app APK 和 test APK 基线。

## 为什么需要 sourcePath

在多模块、多 test APK 或 library-style Test APK 场景中，只给 class 名不一定能知道测试属于哪个 APK。Jugg 使用测试源文件路径作为锚点，用它解析：

- 测试类和测试方法。
- 对应的 androidTest module。
- 应该使用哪个 test APK。
- 是否需要补齐缺失的 library Test APK。

这让 class/method 级运行更稳定，也避免把测试部署到错误的 APK。

## 编译和部署如何配合

Android Test 运行时，Jugg 会先完成必要的编译和部署：

1. 确认 Android Test baseline 是否存在。
2. 编译 app 源码和 androidTest 源码变化。
3. 根据 APK 归属裁剪部署数据。
4. 安装或增量部署 app APK / test APK。
5. 部署成功后执行 instrumentation。

如果 instrumentation 断言失败，本轮测试结果失败；但如果部署已经成功，部署历史仍会推进。这样下一次重跑失败测试时，不需要因为测试失败而重新编译同一批产物。

## Test Results 如何形成

Jugg 会解析 instrumentation 输出，并把测试事件映射到 Test Results 树：

- 单设备运行时，通常直接展示 class / method 节点。
- 多设备运行时，会按设备分组展示。
- 失败测试可以 rerun failed。
- 测试方法的日志会尽量归属到对应 method 节点。

日志归属依赖 instrumentation 生命周期或测试框架 marker，不会随意根据业务日志内容猜测属于哪个测试。

## Library Test APK

library-style self-targeting Test APK 有自己的 runtime package 和安装目标。Jugg 会在必要时补齐这类 Test APK，并把它合入本轮部署目标。

如果缺少 baseline，Jugg 会提示先执行一次 Gradle 构建。构建成功后，后续相同目标可以走更快的增量路径。

## 相关页面

- [项目模型](./project-model.md)
- [部署策略](./deploy-strategy.md)
- [MCP 与 CLI](./mcp-and-cli.md)
- [Android Test 指南](../guide/android-test.md)
