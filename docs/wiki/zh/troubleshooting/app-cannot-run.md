---
title: 无法安装、启动或进入 Debug
description: 处理设备不可用、APK 安装失败、App 无法启动、部署恢复失败和 Debug attach 失败。
status: active
tags:
  - troubleshooting
  - device
  - debug
---

# 无法安装、启动或进入 Debug

本页处理点击 Run 或 Debug 后，App 没有成功安装、启动或进入调试状态的问题。已经进入 App、但改动不生效或随后崩溃时，请选择对应的场景页面。

## Q：Jugg 提示没有可用设备怎么办？

1. 连接设备或启动模拟器。
2. 点亮并解锁设备，接受 USB 调试授权。
3. 在 Android Studio 设备选择器中重新选择目标设备。
4. 确认 Android Studio 原生 Run 也能识别该设备，再重新运行 Jugg。

## Q：提示 App 没有启动或 ADB 被占用怎么办？

常见提示包含 `App not launched` 或 `Recovery failed for app not launched`。

1. 确认设备上的 App 能够正常启动。
2. 确认当前安装包是 debuggable 构建。
3. 关闭其他可能同时使用该设备的 Android Studio 实例或 ADB 工具。
4. 执行 `adb kill-server`，等待 Android Studio 重新连接设备。
5. 重新选择设备并运行 Jugg。

如果 Android Studio 自带的 `Attach Debugger to Android Process` 也失败，应先恢复 ADB 或设备连接。

## Q：提示 `Try recover deploy state failed` 怎么办？

1. 如果刚安装过不可 debug 的 APK，先改用 debuggable variant。
2. 数据线或 ADB 不稳定时，重新连接设备并重启 ADB。
3. 设备安装状态和 Jugg 记录已经不一致时，执行 [Clean Reinstall](../guide/clean-data.md)。

Clean Reinstall 会清理 App 数据、重新安装 APK，并重新建立增量部署状态。

## Q：JVMTI agent 没有响应或部署超时怎么办？

常见提示包含 `MISSING_AGENT_RESPONSES`、`AGENT_ATTACH_FAILED` 或 deploy timeout。

1. 先等待 Jugg 完成自动重试。
2. 自动重试仍失败时，为该设备开启兼容模式后重新运行。
3. 设备状态仍无法恢复时，执行 Clean Reinstall。

不要在 Jugg 正在自动重试时连续点击多次 Run。

## Q：APK 安装失败怎么办？

- `INSTALL_FAILED_USER_RESTRICT`：在设备上允许当前来源安装或解除企业设备限制。
- `INSTALL_FAILED_INVALID_APK`：重新执行完整 Gradle 构建，再使用 Clean Reinstall。
- `The application could not be installed`：先用 Android Studio 原生 Run 验证相同 APK 是否能够安装。
- base APK 含有旧的 Jugg 增量 overlay：重新生成不包含旧增量数据的完整 APK。

## Q：Jugg Debug 不工作怎么办？

1. Debug 只选择一台设备，多设备部署不能同时创建多个 Debug session。
2. 确认当前安装包是 debuggable。
3. 确认 App 已经正常启动。
4. 尝试 Android Studio 自带的 `Attach Debugger to Android Process`。
5. 当前 Android Studio 版本不支持 Jugg Debug 时，使用普通 Jugg Run 后手动 Attach，或升级到受支持版本。

## Q：App 进程一直没有进入 debugger ready 状态怎么办？

先确认 App 没有在启动阶段崩溃，并检查设备上是否存在同包名的不可 debug 安装包。必要时执行 Clean Reinstall 后重新 Debug。

## 相关页面

- [Debug 指南](../guide/debug.md)
- [设备兼容部署](../guide/compat-device.md)
- [Clean Reinstall](../guide/clean-data.md)
- [部署后 App 崩溃](./runtime-crash.md)
