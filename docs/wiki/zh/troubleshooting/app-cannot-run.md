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
2. 如果选择的是真实设备，请先确认 `adb devices` 能看到设备为 `device` 状态；如果不能，重启 ADB 后再试。

## App not launched 或 Recovery failed

这表示 Jugg 没有找到可附加的目标 App 进程，或启动后的部署状态恢复没有完成。

1. 确认设备上安装的是当前 variant 的 debuggable App，并先把 App 启动到前台。
2. 确认 `adb devices` 能看到设备为 `device` 状态。
3. 关闭其他可能同时使用该设备的 Android Studio 实例或 ADB 工具。
4. 使用 Android Studio 自带的 `Attach Debugger to Android Process` 做对照；它也无法找到或附加进程时，先恢复 App 或 ADB 状态。
5. 重新执行一次 Jugg Run；仍失败时使用 [Clean Reinstall](../guide/clean-data.md) 重建安装和部署状态。

## Try recover deploy state failed

这表示设备上的 App 安装或数据仍在，但 Jugg 没能恢复一套可继续增量部署的状态。

1. 确认设备连接正常，且 applicationId、variant 和当前 Jugg Run Configuration 一致。
2. 如果刚手动清过 App 数据、替换过 APK 或切换过 variant，使用 [Clean Reinstall](../guide/clean-data.md)。
3. 未做过这些操作时，重新执行一次 Jugg Run，让 Jugg 进行有限恢复重试。
4. 仍能稳定复现时，保留当前现场并[报告问题](../guide/report-issue.md)。

## 其它部署失败先检查什么

1. 确认 `adb devices` 能看到设备为 `device` 状态。
2. 关闭其他可能同时使用该设备的 Android Studio 实例或 ADB 工具。
3. 测试 `adb install` 能否正常完成 APK 安装。
4. 如果 Android Studio 自带的 `Attach Debugger to Android Process` 也提示失败，应先恢复 ADB 正常能力。
5. 如果仍能稳定复现，使用[报告问题](../guide/report-issue.md)向维护者反馈。

## Q：提示 `MISSING_AGENT_RESPONSES` 或 `AGENT_ATTACH_FAILED` 怎么办？

这表示 Apply Changes agent 附加失败或附加后没有响应。Jugg 会先重试，并在检测到 JVMTI 兼容问题时改用兼容部署。

如果同一设备仍然反复出现，按[设备兼容部署](../guide/compat-device.md)为该设备开启兼容模式后重新运行。

## Q：提示 `Got deploy timeout exception, retry after 5s` 怎么办？

Jugg 会依次尝试缩减资源 overlay、等待后重试，并在最后一次重试时重新安装 APK。仍失败时，使用 [Clean Reinstall](../guide/clean-data.md) 重建安装和部署状态。

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
