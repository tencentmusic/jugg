---
title: Logcat 归因
description: 说明 Jugg Android Test 如何把 logcat 归属到具体设备和测试方法。
status: active
tags:
  - capability
  - test
  - logcat
---

# Logcat 归因

Jugg 支持在 Android Test 运行时捕获 logcat，并把和测试方法相关的短日志展示到对应 Test Results 节点。完整设备日志仍保留在设备详情中，便于排查 instrumentation 或设备级问题。

## 日志展示与归因范围

| 用户场景 | 当前支持情况 | 结果呈现 |
|---|---|---|
| 查看某个失败方法的相关 logcat | 支持 | method 节点详情展示方法级日志 |
| 查看某台设备的完整测试日志 | 支持 | device detail 展示设备级原始日志 |
| 多设备测试日志分离 | 支持 | 每台设备维护独立 logcat 起点与 method 窗口 |
| instrumentation 失败时保留已归因日志 | 支持 | active method 窗口内日志继续展示 |

## Logcat 如何归因

```text
每台设备启动 instrumentation 前读取设备时间
  -> 使用 logcat -T 捕获本轮之后的日志
  -> instrumentation 事件生成 test lifecycle
  -> AndroidX TestRunner marker 优先确定 method 窗口
  -> 缺少完整 marker 时回退到 instrumentation lifecycle
  -> method 日志输出到对应 Test Results 节点
```

Jugg 使用设备侧时间作为 logcat 起点，避免主机和设备时钟偏移导致本轮日志被过滤。`logcat -T` 也能减少旧 buffer 中的历史日志污染第一个测试方法。

## 归因边界

| 日志类型 | 展示位置 |
|---|---|
| method 窗口内、属于当前 test process 的日志 | 对应 method 节点 |
| method 窗口外日志 | 设备详情 |
| 非当前 test process 的设备噪声 | 设备详情 |
| 失败 stack trace | method 日志后追加展示 |
| 超过方法级上限的日志 | 截断后展示 |

> [!IMPORTANT]
> Jugg 不会根据业务 tag、message 文本或相邻时间猜测日志属于哪个测试方法。方法归因只依赖 AndroidX TestRunner marker 或 instrumentation lifecycle。

## 相关页面

- [Application Android Test](./application-android-test.md)
- [Library Android Test](./library-android-test.md)
- [Test Results UI](./test-results-ui.md)
