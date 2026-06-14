---
title: 部署问题排查
description: 按部署报错文案定位设备、ADB、Apply Changes、JVMTI agent、部署状态恢复和安装失败问题。
status: active
tags:
  - troubleshooting
  - deploy
---

# 部署问题排查

部署失败时，先确认错误发生在安装 APK、Apply Changes、部署状态恢复，还是 JVMTI agent 阶段。Jugg 会把主要错误写入 Run 窗口和 `build/jugg/log/compile_latest.log`。

## 常见报错速查

| 报错或现象 | 第一判断 | 建议处理 |
|---|---|---|
| `No device found. Stop deploying.` / `no device connected` | 没有可用设备 | 检查设备连接、授权和 Android Studio 设备选择 |
| `App not launched, please check the app is started and debuggable, and adb is not occupied by other process` | App 没有进入可部署状态，或 ADB 被占用 | 先确认 App 可启动且 debuggable，再检查 ADB 和其他 Android Studio |
| `Try recover deploy state failed.` | Jugg 尝试恢复设备部署状态失败 | 检查安装的 App 是否 debuggable；数据线、ADB 异常时可重启 ADB 后重试 |
| `Try recover deploy state failed on retry.` | 重试阶段恢复部署状态失败 | 保留日志，优先执行一次 Gradle 安装或 Clean And Reinstall |
| `MISSING_AGENT_RESPONSES` / `AGENT_ATTACH_FAILED` | JVMTI agent 没有响应 | Jugg 会检测 JVMTI 兼容性；仍失败时可尝试兼容模式 |
| `Got deploy timeout exception, retry after 5s.` | Apply Changes 通信超时 | 等待自动重试；持续出现时可卸载或 Clean And Reinstall 后再部署 |
| `INSTALL_FAILED_USER_RESTRICT` | 设备限制安装 | 在设备上解除安装限制后重试 |
| `INSTALL_FAILED_INVALID_APK` | 安装包无效 | Jugg 会尝试卸载相关包后重新安装 |
| `The application could not be installed.` | APK 安装失败 | 按 Android Studio/设备安装错误处理 |
| `overlay has no readable id file` | base APK 是 Jugg 增量嵌入 APK，与增量部署冲突 | 换用不包含 `.overlay` 增量数据的 base APK |

## App not launched / Recovery failed

如果看到：

```text
App not launched, please check the app is started and debuggable, and adb is not occupied by other process
```

或参考文档中的：

```text
Recovery failed for app not launched.
```

优先检查：

1. 设备上 App 能否正常启动。
2. 安装的 App 是否是 debuggable 包。
3. 是否同时打开了多个 Android Studio 或其他占用 ADB 的工具。
4. Android Studio 的 `Attach Debugger to Android Process` 是否能正常工作。

如果 attach debugger 本身也失败，先处理 ADB 或设备连接问题，例如重启 ADB。

## Try recover deploy state failed

这个错误表示 Jugg 发现设备上的部署状态和本地记录不一致，并且自动恢复失败。

常见处理：

1. 如果刚用 Gradle 安装过不可 debug 的 App，改用 debuggable 构建重新安装。
2. 如果怀疑数据线或 ADB 异常，重启 ADB 后重试。
3. 如果设备状态已经明显不一致，使用 `Clean And Reinstall` 让 Jugg 清理数据、重装 APK 并恢复增量部署记录。

## JVMTI agent 无响应

如果错误里包含：

```text
MISSING_AGENT_RESPONSES
AGENT_ATTACH_FAILED
MessagePipeWrapper read() timeout
```

Jugg 会尝试判断是否是 JVMTI 兼容性问题。检测到兼容性问题时，会回退到兼容部署模式：

```text
Detect JVMTI compat issue, need to fallback to compat deploy.
```

参考文档中提到的典型场景包括：

- Oppo / 一加 Android 11 设备启动 App 等待时间较长，容易出现 agent 无响应。
- 小米 HyperOS 特定包偶发 JVMTI 不可用。

如果仍持续失败，可以手动开启兼容模式。

## 部署超时

如果看到：

```text
Got deploy timeout exception, retry after 5s.
Got deploy timeout exception, retry the last time with reinstalling APK.
Deploy timeout, retry times: ..., stop retry.
```

Jugg 会先自动重试；第三次会尝试通过重装 APK 恢复。如果最后仍失败，保留日志后尝试卸载 APK 或执行 `Clean And Reinstall`。

## Clean And Reinstall

当你明确希望清理数据并重装 APK 时，使用 Jugg 的 `Clean And Reinstall`。它不是单纯的手机“清除数据”，还会恢复 Jugg 的增量部署记录。

适合场景：

- App 被卸载或设备状态明显不一致。
- 希望验证完整安装链路。
- 直接清除数据后，增量部署记录丢失，代码回到 base APK 状态。

## 相关页面

- [部署策略](../concepts/deploy-strategy.md)
- [Clean Reinstall](../capabilities/deploy/clean-reinstall.md)
- [Recover 与 Retry](../capabilities/deploy/recover-and-retry.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
