---
title: Debug 问题排查
description: 排查 Jugg Debug attach 失败、多设备不支持、旧版 Android Studio 不支持等问题。
status: active
tags:
  - troubleshooting
  - debug
---

# Debug 问题排查

Jugg Debug 会先完成 Jugg 编译和部署，再尝试 attach Java debugger。Debug 问题通常发生在部署成功之后的 attach 阶段。

## 常见报错速查

| 报错 | 第一判断 | 建议处理 |
|---|---|---|
| `Jugg Debug attach failed: Jugg Debug does not support multiple devices.` | 当前选择了多个设备 | 只选择一个设备后重新 Debug |
| `Jugg Debug attach failed: Unable to resolve package name: ...` | 无法解析当前运行目标包名 | 检查 Run Configuration 和部署目标 |
| `Jugg Debug attach failed: Unable to get selected device: ...` | 无法读取设备选择 | 检查设备连接和 Android Studio 设备选择器 |
| `Jugg Debug attach failed: Jugg Debug is not supported in this Android Studio version.` | 当前 Android Studio 版本对应兼容层不支持 Jugg Debug | 使用普通 Run，或升级到支持的 Android Studio 版本 |
| `App process not found` | App 进程还没进入 debugger 可 attach 状态 | Jugg 会短暂重试；持续失败时先确认 App 是否正常启动 |

## 多设备不支持

Jugg Debug 当前只支持单设备 attach。Run 选择多个设备时，部署阶段可以执行，但 Debug attach 会失败。

处理方式：

1. 在 Android Studio 设备选择器中只勾选一个设备。
2. 重新点击 Debug。

## App 进程未就绪

Jugg Debug attach 前会等待 App 进入 debugger WAITING 状态：

```text
Waiting for <packageName> to enter debugger WAITING state.
```

如果 App 进程暂时未找到，Jugg 会自动重试。持续失败时，按顺序检查：

1. App 是否能正常启动。
2. 部署阶段是否已经成功。
3. 设备上是否存在同包名但不可 debug 的安装包。
4. Android Studio 自带的 `Attach Debugger to Android Process` 是否正常。

## 相关页面

- [Debug 指南](../guide/debug.md)
- [部署问题排查](./deploy.md)
- [兼容性](../reference/compatibility.md)
