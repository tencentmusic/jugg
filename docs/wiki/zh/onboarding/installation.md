---
title: 安装
description: 下载并安装 Jugg Android Studio 插件，环境兼容性要求与自动生成 Jugg Run Configuration 验证。
status: active
tags:
  - onboarding
  - installation
---

# 安装

Jugg 以 Android Studio 插件形式提供，无需修改工程现有代码或 `build.gradle` 配置。安装并重启 IDE 后，在 Gradle Sync 完成时会自动为工程中的 Android App 模块生成 Jugg 运行配置。

## 1. 环境准备与兼容性要求

在安装前，请确认你的本地开发环境符合以下要求：

| 项目 | 最低要求 | 推荐与支持版本 |
|---|---|---|
| **操作系统** | macOS / Linux / Windows | macOS (Apple Silicon / Intel), Linux, Windows 10/11 |
| **Android Studio** | Android Studio Chipmunk (2021.2.1)+ | Hedgehog, Iguana, Jellyfish, Koala, Ladybug, Meerkat, Narwhal |
| **Gradle / AGP** | AGP 7.0+ | AGP 7.x, 8.x |
| **Java 开发环境** | JDK 11+ | JDK 17 / JDK 21 |
| **目标测试设备** | Android 8.0 (API 26)+ | 物理机或模拟器均可，开启 USB 调试 |

## 2. 获取 Jugg 插件安装包

可以从官方 GitHub Releases 获取插件包（通常为 `.zip` 格式）：

- **[最新稳定版 Releases](https://github.com/tencentmusic/jugg/releases/latest)**：推荐日常团队开发使用。
- **[最新 Canary 构建](https://github.com/tencentmusic/jugg/releases/download/canary-nightly/jugg-canary-nightly.zip)**：从触发 Canary workflow 的分支自动构建，包含最新特性。

> [!NOTE]
> 企业内网或特定团队如果部署了内部私有分发渠道，优先遵循团队内部版本指引。

## 3. 在 Android Studio 中本地安装

1. 打开 Android Studio。
2. 打开设置面板：
   - macOS: `Android Studio -> Settings...`（或 `Preferences...`，快捷键 `Cmd + ,`）
   - Windows / Linux: `File -> Settings...`（快捷键 `Ctrl + Alt + S`）
3. 在左侧导航中选择 **Plugins**。
4. 点击顶部齿轮图标 ⚙️，在下拉菜单中选择 **Install Plugin from Disk...**。
5. 选择第 2 步下载的 `jugg-*.zip` 插件压缩包。
6. 点击 **OK**，并在弹出提示框中点击 **Restart IDE** 重启 Android Studio。

## 4. 验证 Jugg 运行配置生成

IDE 重启并等待底部的 Gradle Sync 完成后，打开顶部工具栏的运行配置下拉框，确认自动出现了类似如下命名的运行配置：

```text
jugg:app
```

其中 `app` 为当前主 Android Application 模块名。如果工程包含多个 App 模块，Jugg 会分别为每个可运行模块生成对应的 `jugg:<moduleName>` 配置。

如果未自动生成 Jugg 运行配置，按以下顺序排查：

| 排查点 | 检查与处理方式 |
|---|---|
| **Gradle Sync 状态** | 确认右下角 Sync 是否仍在进行，待其全部完成 |
| **原生 App 配置** | 确认工程本身是否存在可运行的原生 App Run Configuration |
| **重启确认** | 确认安装插件后已完整重启 IDE |
| **重新加载工程** | 尝试 `File -> Invalidate Caches / Restart` 重新加载工程 |

## 5. 可选：调整编译命令与参数

绝大多数工程在 Sync 后能自动推导正确的 Gradle 构建任务与 APK 产物路径。如需针对自定义 build variant 进行微调，可点击运行配置下拉框选择 **Edit Configurations...**：

| 配置项 | 默认推导与作用 |
|---|---|
| **Compile command** | 用于首次建立基线的 Gradle 命令（如 `:app:assembleDebug`） |
| **Output APK name** | 目标构建产物 APK 路径，应与编译命令产物一致 |

---

## 下一步指引

安装并确认运行配置生成后，即可开始首次运行建立构建基线：

👉 **[前往首次运行与建立基线](./first-run.md)**
