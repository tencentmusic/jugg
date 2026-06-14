---
title: 安装
description: 下载并安装 Jugg Android Studio 插件，确认 IDE 重启后自动生成 Jugg Run Configuration。
status: active
tags:
  - onboarding
  - installation
---

# 安装

Jugg 以 Android Studio 插件形式提供。安装完成后，不需要修改工程代码或 Gradle 配置；IDE 重启并完成 Sync 后，插件会为 Android App 模块自动生成对应的 Jugg Run Configuration。

## 下载插件

从团队内的 Jugg 下载页获取插件包。如果当前网络无法访问内网下载页，使用维护者提供的备用下载地址。

下载到本地后，确认文件是 Android Studio 可安装的插件包，通常是 `.zip`。

## 在 Android Studio 中安装

1. 打开 Android Studio。
2. 进入 `Settings`。
3. 打开 `Plugins`。
4. 点击右上角齿轮菜单。
5. 选择 `Install Plugin from Disk...`。
6. 选择刚下载的 Jugg 插件包。
7. 按提示重启 IDE。

重启后如果工程正在 Sync，先等待 Sync 完成。Jugg 会在 Sync 后读取工程模块和 Gradle 产物信息，再生成运行配置。

## 确认运行配置

打开运行配置下拉框，正常情况下可以看到类似下面的配置：

```text
jugg:app
```

其中 `app` 对应 Android App 模块名。多 App 模块工程会生成多个 Jugg 配置，选择你要运行的目标模块即可。

如果没有看到 Jugg 配置，先检查：

| 现象 | 处理方式 |
|---|---|
| IDE 刚重启，工程仍在 Sync | 等待 Sync 完成 |
| 插件安装后没有重启 | 重启 Android Studio |
| 工程没有可运行的 Android App 模块 | 先确认原生 App Run Configuration 是否存在 |
| 配置仍未生成 | 重新打开工程，保留日志后反馈 |

## 可选：调整编译命令

一般情况下不需要手动配置，Jugg 会读取 Android Studio 已经配置好的 Gradle 命令和 APK 输出信息。

如果自动生成的命令不是你日常开发调试使用的命令，可以进入 `Edit Configurations...` 调整：

| 参数 | 含义 |
|---|---|
| `Compile command` | 生成 APK 的 Gradle 命令，应与当前 App 运行配置一致 |
| `Output APK name` | 编译产物 APK 路径或名称，应与 `Compile command` 输出一致 |

修改后建议先运行一次 Gradle 编译，确认基线产物正确。

## 下一步

- [首次运行](./first-run.md)
- [云开发机配置](./agent-setup.md)
