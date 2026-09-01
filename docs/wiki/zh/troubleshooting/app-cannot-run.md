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

## Q：提示 No Device

1. 如果 Android Studio 选择的是虚拟机，请确认虚拟机已启动 -- Jugg 不会自动启动虚拟机，这在仅需要编译的场景通常体验更好。
2. 如果选择的是真实设备，请先确认 `adb device` 能看到设备 online，如果不能推荐 kill 掉 adb 进程再试。

## Q：部署失败如 `Try recover deploy state failed`、`MISSING_AGENT_RESPONSES`、`AGENT_ATTACH_FAILED`、`deploy timeout`

通常是设备 adb 状态异常导致。

1. 确认 `adb device` 能看到设备 online，如果不能推荐 kill 掉 adb 进程再试。
2. 关闭其他可能同时使用该设备的 Android Studio 实例或 ADB 工具。
3. 测试 `adb install` 能否正常完成 APK 安装。
4. 如果 Android Studio 自带的 `Attach Debugger to Android Process` 也提示失败，应先恢复 ADB 正常能力。
5. 如果仍能稳定复现，使用[报告问题](../guide/report-issue.md)向维护者反馈。

## Q：提示 `MISSING_AGENT_RESPONSES` 或 `AGENT_ATTACH_FAILED` 怎么办？

这表示 Apply Changes agent 附加后没有响应。Jugg 会先重试，并在检测到 JVMTI 兼容问题时改用兼容部署。

如果同一设备仍然反复出现，按[设备兼容部署](../guide/compat-device.md)为该设备开启兼容模式后重新运行。

## Q：提示 `Got deploy timeout exception, retry after 5s` 怎么办？

Jugg 会对部署超时执行有限重试。重试后仍失败时，卸载设备上的 App 后重新部署；也可以使用 [Clean Reinstall](../guide/clean-data.md) 重新安装。

## Q：APK 安装失败

- `INSTALL_FAILED_USER_RESTRICT`：在设备上允许当前来源安装或解除企业设备限制。
- `INSTALL_FAILED_INVALID_APK`：重新执行完整 Gradle 构建，再使用 Clean Reinstall。
- `The application could not be installed`：先用 Android Studio 原生 Run 验证相同 APK 是否能够安装。
- base APK 含有旧的 Jugg 增量 overlay：重新生成不包含旧增量数据的完整 APK。

## 相关页面

- [Debug 指南](../guide/debug.md)
- [设备兼容部署](../guide/compat-device.md)
- [Clean Reinstall](../guide/clean-data.md)
- [部署后 App 崩溃](./runtime-crash.md)
