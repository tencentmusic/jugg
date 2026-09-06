---
title: 自定义 APK 安装脚本
description: 说明如何让 Jugg 在普通 App 的安装和重装阶段调用项目脚本，以支持系统应用、厂商任务或其它非标准安装流程。
status: active
tags:
  - capability
  - deploy
  - install
---

# 自定义 APK 安装脚本

系统应用、厂商工程或定制设备可能不能通过 Android Studio 默认 installer 安装。Jugg 可以在需要安装普通 App APK 时执行项目提供的脚本，同时保留后续增量部署需要的 deployment cache 和 overlay checkpoint。

## 启用方式

打开 Jugg Run Configuration，勾选 `Enable custom APK install script`。勾选后，下方会出现单行高度的脚本输入框；未勾选时只显示这个开关，现有安装行为不变。

脚本在本地工程根目录执行。macOS/Linux 使用 Bash，Windows 使用 `cmd.exe`。即使启用了远程编译，脚本也会在连接 Android 设备的本地 IDE 主机执行。

例如调用项目脚本：

```bash
./scripts/install-system-app.sh
```

也可以调用项目定义的 Gradle task：

```bash
./gradlew :app:installToSystem
```

Jugg 不注入设备、applicationId 或 APK 路径变量。脚本继承 Android Studio 进程环境以及 Jugg 已解析的 Gradle JDK、Android SDK 环境；当前 Android SDK 的 `platform-tools` 会加入 `PATH`，因此脚本可以直接调用 `adb`。Bash 不加载 `.zshrc`、`.bashrc` 等用户 shell 启动文件，项目需要的其它变量应由 Android Studio 的启动环境或脚本自身提供。

## 生效范围

自定义脚本会接管普通 App APK 的 install/reinstall，包括：

- Gradle 编译完成后的安装。
- Clean Reinstall 和 recover 触发的重装。
- Manifest、native library 等 APK 更新后的安装。
- Embedded APK 安装。

每台设备上的每个普通 App applicationId 分别执行一次脚本，同一 applicationId 的 base/split APK 作为一组处理。

纯 class 或 overlay 增量部署不会执行脚本。androidTest APK 也继续使用 Android Studio 默认 installer，避免系统应用脚本误处理测试包。

## 成功与失败判定

脚本必须安装 Jugg 本轮提供的 APK，并以退出码 `0` 结束。如果脚本触发 reboot，应自行等待设备启动和 PackageManager 完成包扫描后再退出。Jugg 随后会处理短暂的 ADB transport 恢复，确认目标包已经安装，并校验设备上的 APK 与本轮输入 APK 的 checksum；全部通过后才写入新的 deployment cache 和 overlay checkpoint。

以下情况会让本轮部署失败：

- 脚本退出码非零。
- 用户取消 Run，脚本进程被终止。
- 脚本执行后设备没有恢复连接。
- 目标 applicationId 未安装。
- 脚本安装了不同 APK，导致 checksum 不匹配。

脚本执行或安装结果校验失败时，Jugg 不会通过 deploy retry 或 Gradle fallback 自动重跑脚本。脚本安装成功后，其它部署步骤失败仍按原有规则重试或回退。例如，后续 androidTest APK 安装报 `INSTALL_FAILED_INVALID_APK` 时，卸载重试会再次执行普通 App 的安装脚本。默认 installer 自身的短暂 ADB transport 恢复仍然保留。脚本重复执行时的安装逻辑、副作用和错误处理由业务方负责。

## 系统应用边界

Jugg 只负责调用脚本和校验安装结果，不内置以下行为：

- `adb root`、`adb remount` 或 system 分区写入。
- `/system/app`、`/system/priv-app` 路径选择。
- platform 签名、shared UID 或特权权限白名单。
- reboot、zygote restart 或厂商刷机流程。

脚本执行成功也不能单独证明 App 已成为系统应用。仍应通过 `dumpsys package <applicationId>` 检查 `codePath`、`SYSTEM`、`PRIVILEGED` 和权限授予状态。

## 相关页面

- [Clean Reinstall](./clean-reinstall.md)
- [APK 更新与安装](../../concepts/apk-update-and-install.md)
- [多 APK](./multi-apk.md)
- [多设备](./multi-device.md)
- [部署历史与缓存](./deploy-history-cache.md)
- [无法安装、部署、启动或 Debug](../../troubleshooting/app-cannot-run.md)
