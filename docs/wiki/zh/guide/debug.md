---
title: Debug
description: 介绍 Jugg Debug 的使用方式、运行链路、单设备限制和断点不可用时的排查入口。
status: active
tags:
  - guide
  - debug
---

# Debug

Jugg Debug 用于替代“先 Jugg Run，再手动 Attach debugger”的流程。点击 Debug 后，Jugg 会复用普通编译和部署链路，部署成功后以 debug 模式重启 App，并请求 Android Studio 原生 Java debugger attach。

## 使用场景

Jugg Debug 适合：

- 你希望修改代码后直接进入断点调试。
- App 启动很快，手动点击 Attach 容易错过时机。
- 不想手动打开开发者选项里的 waiting debugger。

当前边界：

- 多设备同时 Debug。
- androidTest Debug。
- 需要调试 native / C++ 的场景。

## 如何使用

1. 只选择一个目标设备。
2. 选择 Jugg Run Configuration。
3. 点击 Debug 按钮。
4. 等待 Jugg 编译、部署、重启 App。
5. Android Studio Debug tool window 出现后，再判断断点是否可用。

Jugg Debug 会保存当前打开文件并刷新文件状态，避免普通 Run 能识别改动而 Debug 误判没有文件变化。

## 运行链路

```text
Jugg Debug
  -> 保存文件并刷新 VFS
  -> 执行 Jugg 编译和部署
  -> 使用 am start -D -S 重启 App
  -> 等待目标进程进入 debugger WAITING 状态
  -> 请求 Android Studio 原生 attach flow
  -> 由 Android Studio 创建并激活 Debug session
```

Jugg 不直接接管 Debug tool window，也不直接创建最终的 XDebugSession。它只负责把 App 启动到可 attach 状态，然后交给 Android Studio 原生调试流程。

## 与普通 Jugg Run 的差异

| 行为 | Jugg Run | Jugg Debug |
|---|---|---|
| 部署后是否强制重启 | 取决于修改类型和设置 | 强制 debug 重启 |
| 无文件变化时 | 可能提示是否回退 Gradle | 仍可走空部署并进入 attach |
| 多设备 | 支持逐台部署 | 不支持 Debug attach |
| 结果窗口 | Run tool window | 编译部署输出在 Run，调试由 Debug tool window 接管 |

## 断点不可用时先看什么

断点是否可用不能只看 Jugg 日志里“已进入等待 debugger”。建议按顺序检查：

1. `build/jugg/log/compile_latest.log` 中是否出现 `Jugg Debug attach`。
2. Android Studio `idea.log` 中是否出现目标 package 已进入 debuggable 状态。
3. Debug tool window 是否出现。
4. `idea.log` 中是否出现 `Connected to the target VM`。
5. 原生 Android Studio Attach 是否能命中同一断点。

如果只看到 “Debugger is waiting for application to start”，但没有 “Connected to the target VM”，说明 Android Studio Java debugger 还没有完成 VM 连接，断点不会生效。

## 常见问题

| 现象 | 处理方式 |
|---|---|
| 选择了多个设备 | 只保留一个设备后重试 |
| 点击 Debug 后仍像普通 Run | 确认使用的是 Jugg Run Configuration，而不是原生 App configuration |
| App 没有进入等待 debugger | 看部署是否成功，以及目标包名是否正确 |
| 有 Run 输出但没有 Debug 窗口 | 看 Android Studio attach flow 是否成功 |
| 旧 Android Studio 版本提示不支持 | 检查当前 Jugg 与 Android Studio 兼容性 |

## 相关页面

- [运行 App](./run.md)
- [部署结果说明](./deploy.md)
- [Debug 问题排查](../troubleshooting/debug.md)
- [限制](../reference/limits.md)
- [兼容性](../reference/compatibility.md)
