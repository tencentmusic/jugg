---
title: 首次运行
description: 建立 Gradle 基线、验证首次 Run 的结果，并理解后续增量运行。
status: active
tags:
  - onboarding
  - run
---

# 首次运行

在完成 Jugg 插件安装并等待工程 Sync 结束后，即可开始首次运行。首次运行的核心目标是**构建完整 APK 并建立增量编译基线与部署状态**，此轮耗时等同于一次常规 Gradle 构建。

## 1. 运行前检查

点击 Run 前，请快速确认以下状态：

1. **运行配置**：工具栏选中 `jugg:<moduleName>`（而非原生 App 配置）。
2. **目标设备**：已连接 Android 8.0 (API 26) 及以上的物理设备或模拟器。
3. **Sync 状态**：Android Studio 右下角无正在运行的 Gradle Sync 或索引任务。

确认后点击 Android Studio 工具栏的绿色 **Run** 按钮。如果选错设备或目标，可随时点击红色停止按钮中断。

## 2. 首次运行机制：建立 Gradle Baseline 基线

首次执行时，Jugg 会按以下机制自动完成初始化：

```text
点击 Jugg Run
  -> 检测本地环境与设备连接状态
  -> 检测到尚无增量基线，自动调用 Gradle 编译完整 APK
  -> 安装并启动目标 App
  -> 后台收集 Class 关系、资源映射与 R 文件等后续增量所需基线产物
  -> 基线建立完毕，后续受支持的代码与资源修改可走增量编译
```

> [!NOTE]
> 没有可用基线时，首次 Run 需要完整 Gradle 构建。后续 Run 可在基线有效时增量编译；构建配置或依赖变化、不受支持的处理，以及主动回退，都可能需要再次完整构建。

## 3. 验证成功标志：控制台日志特征

首次运行成功后，Android Studio 底部的 **Run** 工具窗口会输出 Jugg 的编排进度：

```text
Gradle BUILD_AND_INSTALL SUCCESSFUL in <elapsed>s.
App launched.
```

这些日志确认 Gradle 构建、安装和启动成功。基线准备还会在后台继续；仅凭这些日志不能确认增量编译已经就绪。后续 Run 的结果见[运行 App](../guide/run.md)。

## 4. 日常开发：3 秒增量热更与生效路径

在基线建立后，日常对 Java、Kotlin、XML 布局或 Assets 的小范围修改，直接继续点击 **Jugg Run**（或快捷键 `Ctrl + R` / `Shift + F10`）：

| 修改场景 | Jugg 决策路径 | 预期结果 |
|---|---|---|
| **修改方法体** | 增量编译变化的 class；受支持时走 Hot Reload | 可避免重启 App；设备兼容性或其它变化仍可能要求重启 |
| **修改 XML Layout / 图片资源** | 编译并部署变化的资源 | 受支持时刷新 Activity，否则重启 App |
| **新增方法 / 修改类签名 / 新增 Activity** | 受支持时走 Hot Fix 并重启 App；必要时回退 Gradle | 重启后进程状态重置；Activity 声明还可能需要处理 Manifest |
| **修改 `build.gradle` / 依赖库** | 判断是否需要完整 Gradle 构建，并刷新基线 | 准备好完整构建；总耗时取决于工程 |

## 5. 什么时候需要主动回退 Gradle

在以下情况下，推荐主动使用原生 Gradle 构建刷新基线：

| 场景 | 推荐操作 |
|---|---|
| **手动执行了 `clean` 或删除了 `build` 目录** | 此时增量基线已丢失，直接跑一次原生 App Run 重建基线 |
| **切换了 Git 分支或大版本升级依赖** | 依赖版本与 Manifest 大幅变动，建议完整构建一次 |
| **需要验证未适配的复杂注解处理器** | 先用原生 Gradle 构建验证生成代码正确性 |

---

## 下一步指引

基线已成功建立！继续探索 Jugg 丰富的高效开发能力：

- 📖 **[日常运行与热更指南](../guide/run.md)**：深入了解热更模式与多设备运行
- ⚡ **[Jugg CLI 命令行实战](../guide/cli.md)**：在终端或脚本中享受 3 秒极速构建
- 🛠️ **[支持的打包方式与兼容性](../troubleshooting/supported-packaging.md)**：查看混淆、AabResGuard 与 Dynamic Feature 支持
