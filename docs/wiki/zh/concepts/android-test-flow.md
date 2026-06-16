---
title: Android Test 流程
description: 说明指定原文对 Android Test 的覆盖范围，以及它与 Jugg 增量编译基线的关系。
status: active
tags:
  - concept
  - android-test
---

# Android Test 流程

指定原文主要介绍 Jugg 的 App 增量编译、资源编译和部署方案，没有展开 Android Test instrumentation 的实现细节。因此本页只说明它和 Jugg 通用链路的关系，不补充原文之外的具体实现。

## 与普通运行的共同点

Android Test 仍然依赖 Jugg 的基本前提：先有可信 Gradle 基线，再基于变化文件执行增量编译和部署。

通用链路如下：

```text
Gradle 基线
  -> 收集 APK、class、依赖和编译参数
  -> 检测文件变化
  -> 编译变化源码、资源和其他产物
  -> 部署到设备
```

如果基线缺失、构建参数不可信，或本轮变化超出增量边界，应回到 Gradle 构建。

## 原文未覆盖的内容

以下内容不在指定原文范围内：

- androidTest source set 如何解析。
- test APK 如何定位和安装。
- `am instrument` 参数如何生成。
- 测试结果树、失败用例重跑和日志归属。
- 多 APK 或 library test APK 的处理规则。

这些能力如果需要说明，应以当前代码和对应专题文档为依据单独整理，不能从本次指定原文推导。

## 相关页面

- [项目模型](./project-model.md)
- [部署策略](./deploy-strategy.md)
- [Android Test 指南](../guide/android-test.md)
