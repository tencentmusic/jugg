---
title: Android Test 问题排查
description: 排查 Jugg Android Test 的 sourcePath、test APK 解析、instrumentation 运行失败和可选 library Test APK 缺失问题。
status: active
tags:
  - troubleshooting
  - android-test
---

# Android Test 问题排查

Jugg Android Test 以 `sourcePath` 为锚点解析 androidTest 源文件、所属模块和 test APK。排查时先确认源文件路径、模块归属和 test APK 是否可解析。

## 常见报错速查

| 报错或提示 | 第一判断 | 建议处理 |
|---|---|---|
| `sourcePath must be an existing file.` | 传入的 `sourcePath` 文件不存在 | 使用真实存在的 androidTest 源文件路径 |
| `sourcePath is not under any known androidTest source root.` | 源文件不在已知 androidTest source root 下 | 确认项目已 Sync，且路径属于 androidTest |
| `multiple androidTest modules match sourcePath.` | 一个源文件匹配到多个 androidTest 模块 | 检查工程 source root 配置 |
| `unable to resolve test APK for androidTest module.` | 找不到确定的 test APK | 先执行一次 Gradle 编译生成 test APK |
| `multiple test APKs match androidTest module.` | 匹配到多个 test APK | 检查运行目标和 APK 归属 |
| `no test class found in sourcePath.` | 源文件里没有可解析的测试类 | 检查测试类定义 |
| `multiple test classes found in sourcePath.` | 一个源文件里有多个测试类但未指定 class | 指定要运行的 class |
| `class is not found in sourcePath.` | 指定 class 不在该源文件中 | 修正 class 参数 |
| `method is not found in sourcePath.` | 指定 method 不在该测试类中 | 修正 method 参数 |
| `Instrumentation test run reported failures.` | instrumentation 执行返回失败 | 查看测试输出和 logcat |

## 找不到 test APK

如果看到：

```text
Library Test APK missing. Run Gradle compile once to build the test APK.
```

或：

```text
unable to resolve test APK for androidTest module.
```

处理方式：

1. 先用 Gradle 跑一次对应 androidTest 构建，生成 test APK。
2. 确认本轮 `sourcePath` 指向正确的 androidTest 文件。
3. 如果是 library androidTest，确认 library Test APK 已被构建并能被 Jugg 读取。

## instrumentation 运行失败

如果部署完成后出现：

```text
Instrumentation test run reported failures.
```

说明 Jugg 已经启动 instrumentation，但测试本身返回失败。此时优先查看测试输出、失败用例和 logcat，而不是从编译或部署链路开始排查。

## MCP instrument 参数

通过 MCP 触发 Android Test 时，`instrument` 工具要求 `sourcePath`：

```text
sourcePath is required for Jugg instrument.
```

同时，参数只支持 `sourcePath` 搭配 `class` / `method`，或 `class` / `runner` / `extras` 这类明确组合。看到 `... is not supported` 时，按错误提示调整参数。

## 相关页面

- [Android Test 指南](../guide/android-test.md)
- [Application Android Test](../capabilities/test/application-android-test.md)
- [Library Android Test](../capabilities/test/library-android-test.md)
- [Test Results UI](../capabilities/test/test-results-ui.md)
