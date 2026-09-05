---
title: 使用指南
description: 从 Android Studio 界面和日常开发动作出发，说明如何使用 Jugg 运行、降级 Gradle、重启、清数据、调试、测试、CLI 和远端 Gradle。
status: active
tags:
  - guide
---

# 使用指南

这一组页面面向日常使用者：你已经安装了 Jugg，需要知道修改代码后点哪里、弹窗怎么选、什么时候重启或清数据，以及出问题时先看哪里。

## 按任务选择入口

| 你想做什么 | 推荐页面 | 适合场景 |
|---|---|---|
| 修改代码或资源后运行 App | [运行 App](./run.md) | 点击 Jugg Run 后，一次完成编译、部署、启动和结果判断 |
| 没有文件变化但想重新构建 | [降级 Gradle 编译](./downgrade-gradle.md) | 处理无文件变化运行降级、主动点击降级按钮、依赖变化弹窗、源码过多确认 |
| 把本轮增量结果导成 APK | [导出增量 APK](./export-incremental-apk.md) | 从降级确认弹窗导出已编译的增量 APK |
| 不重新编译，只重启当前 App | [重启 App](./restart-app.md) | 验证启动逻辑、缓存、单例、static / companion 变化 |
| 清理 App 数据并重装 | [清理数据](./clean-data.md) | 需要干净安装现场，或设备部署状态需要重建 |
| 同时跑多台设备 | [多设备选择](./multi-device.md) | 编译一次，按 Android Studio 设备选择逐台部署 |
| 更新桌面小组件或通知 RemoteViews | [Android RemoteViews](./android-remoteviews.md) | 增量变化需要写入 APK 内容才能被系统读取 |
| 某台设备部署反复失败 | [设备兼容部署](./compat-device.md) | 为指定设备改用兼容热修复路径 |
| 进入断点调试 | [Debug](./debug.md) | 点击 Debug 后，让 Jugg 完成编译部署，并接入 Android Studio Java debugger |
| 跑 `src/androidTest` 测试 | [Android Test](./android-test.md) | 从 gutter、Run Configuration 或 CLI 运行 instrumentation 测试 |
| 在终端或 Agent 中使用 Jugg | [CLI](./cli.md) | 使用 `jugg compile`、`deploy`、`instrument`、UI 工具和日志命令 |
| 配置 MCP | [MCP](./mcp.md) | 了解 MCP 的公开能力、端口、返回模型和为什么多数场景更推荐 CLI |
| 导出布局、定位元素、点击设备 | [UI 检查](./ui-inspection.md) | 给 Agent 或脚本提供 UI 层级、属性查询和触控能力 |
| 使用云开发机/远端构建 | [远端 Gradle](./remote-gradle.md) | 本地只保留 IDE 与部署，Gradle 构建在远端执行 |
| 扩展编译阶段 | [自定义编译器](./custom-compiler.md) | 接入业务专用生成、转换或校验逻辑 |
| 调整 More Options 开关 | [高级选项](./advanced-options.md) | 查看运行策略、工具入口和内部排查项的作用 |
| 上传问题日志 | [报告问题](./report-issue.md) | 增量编译、部署或运行结果异常时，上传日志并获取 Issue ID |
| 自建配置、更新和事件上报后台 | [Jugg 后台](./jugg-backend/) | 集中下发项目配置、插件升级、热更新和使用情况事件 |

## 一次普通开发循环

```text
修改代码或资源
  -> 在 Android Studio 选择设备和 Jugg Run Configuration
  -> 点击 Run 或 Debug
  -> Jugg 自动保存文件，并判断走增量还是 Gradle
  -> 编译成功后自动部署到设备
  -> 根据结果 Hot Reload、重启 App、安装 APK 或提示失败
```

大部分业务代码、资源和 layout 修改都可以直接点击 Run。Jugg 会在后台处理编译和设备更新；你主要看三件事：这次是否成功、App 有没有重启、是否降级到了 Gradle。

## 推荐使用习惯

- 首次接入、切分支、拉取大量代码或修改 Gradle 配置后，先接受一次 Gradle 构建，建立可信基线。
- 小范围 Java/Kotlin、资源、layout、assets 修改，优先直接 Jugg Run。
- 明确需要完整构建时，使用 [降级 Gradle 编译](./downgrade-gradle.md)。
- 明确需要清数据时，使用 [清理数据](./clean-data.md)，不要先去系统设置里手动清。
- 修改 App 启动逻辑、静态初始化、单例缓存或 object 初始化后，如果本轮命中 Hot Reload，主动重启一次 App。
- 碰到“增量结果不符合预期”时，先做一次 Gradle 构建对照，再提交 Jugg 日志。
- 给 Agent 使用时优先配置 Jugg CLI Skill；MCP 只在需要直接接入 MCP 客户端时使用。
- 需要提交问题时，使用 [报告问题](./report-issue.md) 上传日志，再把 Issue ID 发给维护者。

## 相关页面

- [运行 App](./run.md)
- [降级 Gradle 编译](./downgrade-gradle.md)
- [导出增量 APK](./export-incremental-apk.md)
- [重启 App](./restart-app.md)
- [清理数据](./clean-data.md)
- [多设备选择](./multi-device.md)
- [Android RemoteViews](./android-remoteviews.md)
- [设备兼容部署](./compat-device.md)
- [实现原理](../concepts/)
- [Jugg 工作原理](../concepts/how-jugg-works.md)
- [Gradle 回退与基线重建](../concepts/gradle-fallback-baseline.md)
- [Jugg 能力概览](../capabilities/)
- [高级选项](./advanced-options.md)
- [报告问题](./report-issue.md)
- [编译失败](../troubleshooting/compile-failed.md)
- [改动没有生效](../troubleshooting/changes-not-applied.md)
- [无法安装、启动或进入 Debug](../troubleshooting/app-cannot-run.md)
- [日志文件](../reference/log-files.md)
