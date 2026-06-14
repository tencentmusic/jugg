---
title: 使用指南
description: 从 Android Studio 界面和日常开发动作出发，说明如何使用 Jugg 运行、调试、测试、CLI、MCP、UI 检查、远端 Gradle 和自定义编译器。
status: active
tags:
  - guide
---

# 使用指南

这一组页面面向日常使用者：你已经安装了 Jugg，需要知道修改代码后在 Android Studio 里点哪里、运行结束后怎么看结果、什么时候主动回退 Gradle，以及出问题时先看哪里。

## 按任务选择入口

| 你想做什么 | 推荐页面 | 适合场景 |
|---|---|---|
| 修改代码或资源后运行 App | [运行 App](./run.md) | 点击 Jugg Run 后，一次完成编译、部署、启动和结果判断 |
| 进入断点调试 | [Debug](./debug.md) | 点击 Debug 后，让 Jugg 完成编译部署，并接入 Android Studio Java debugger |
| 跑 `src/androidTest` 测试 | [Android Test](./android-test.md) | 从 gutter、Run Configuration 或 CLI 运行 instrumentation 测试 |
| 在终端或 Agent 中使用 Jugg | [CLI](./cli.md) | 使用 `jugg compile`、`deploy`、`instrument`、UI 工具和日志命令 |
| 配置 MCP | [MCP](./mcp.md) | 了解 MCP 的公开能力、端口、返回模型和为什么多数场景更推荐 CLI |
| 导出布局、定位元素、点击设备 | [UI 检查](./ui-inspection.md) | 给 Agent 或脚本提供 UI 层级、属性查询和触控能力 |
| 使用云开发机/远端构建 | [远端 Gradle](./remote-gradle.md) | 本地只保留 IDE 与部署，Gradle 构建在远端执行 |
| 扩展编译阶段 | [自定义编译器](./custom-compiler.md) | 接入业务专用生成、转换或校验逻辑 |

## 一次普通开发循环

```text
修改代码或资源
  -> 在 Android Studio 选择设备和 Jugg Run Configuration
  -> 点击 Run 或 Debug
  -> Jugg 自动保存文件、判断增量或 Gradle
  -> 编译成功后自动部署到设备
  -> 根据结果 Hot Reload、重启 App、安装 APK 或提示失败
```

大部分业务代码、资源和 layout 修改都可以直接点击 Run。Jugg 会在后台处理“编译”和“部署”两个阶段；你通常只需要关注这次运行是否成功、App 是否重启、以及是否回退到了 Gradle。

## 推荐使用习惯

- 首次接入、切分支、拉取大量代码或修改 Gradle 配置后，先接受一次 Gradle 构建，建立可信基线。
- 小范围 Java/Kotlin、资源、layout、assets 修改，优先直接 Jugg Run。
- 明确需要重新安装、清数据或验证完整构建链路时，使用直接降级或 Clean Reinstall。
- 修改 App 启动逻辑、静态初始化、单例缓存或 object 初始化后，如果本轮命中 Hot Reload，主动重启一次 App。
- 碰到“增量结果不符合预期”时，先做一次 Gradle 构建对照，再提交 Jugg 日志。
- 给 Agent 使用时优先配置 Jugg CLI Skill；MCP 只在需要直接接入 MCP 客户端时使用。

## 如何判断结果

| 结果 | 含义 |
|---|---|
| Jugg Hot Reload / 热重载成功 | 修改已在线生效，通常不重启 App |
| Jugg Hot Fix / 热修复成功 | 修改已下发，App 会重启后生效 |
| Gradle 编译安装成功 | 本轮走完整 Gradle 构建和安装，增量基线会被刷新 |
| Clean Reinstall 成功 | 已清理 App 数据、重新安装，并恢复 Jugg 部署状态 |
| compile 成功但 deploy 失败 | 编译完成，设备部署或启动阶段失败，需要看部署日志 |
| androidTest failed | 编译和部署可能已经成功，测试断言或 instrumentation 失败 |

日志统一从这里开始看：

```bash
build/jugg/log/compile_latest.log
```

## 相关页面

- [运行 App](./run.md)
- [Jugg 工作原理](../concepts/how-jugg-works.md)
- [回退与限制](../concepts/fallback-and-limits.md)
- [编译阶段说明](./compile.md)
- [部署结果说明](./deploy.md)
- [编译问题排查](../troubleshooting/compile.md)
- [部署问题排查](../troubleshooting/deploy.md)
- [日志文件](../reference/log-files.md)
