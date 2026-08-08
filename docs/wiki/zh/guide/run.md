---
title: 运行 App
description: 从 Android Studio 的 Jugg Run 入口出发，说明怎么选择配置、选择设备、点击 Run，并判断本轮结果。
status: active
tags:
  - guide
  - run
---

# 运行 App

日常开发时，修改代码或资源后直接点击 Jugg Run。你不需要把“编译”和“部署”拆开操作；Jugg 会保存文件、判断变化、更新设备并启动 App。

## 什么时候直接点 Run

适合直接点击 Jugg Run 的场景：

- 修改 Java / Kotlin 方法体、layout、drawable、values、assets 等常见文件。
- 修改后想马上在设备上验证 App 表现。
- 不确定本次改动能否增量处理，希望 Jugg 自动判断。
- 希望必要时自动降级到 Gradle，而不是手动切换流程。

需要准备 Gradle 对照或额外操作的场景：

- 刚切分支、拉取大量代码，或改了 Gradle 插件 / 依赖配置。
- 明确需要清 App 数据并重装。
- 正在验证 release 构建、注解处理、字节码插桩或完整 Gradle 链路。

这些场景仍然可以从 Jugg Run 开始，但要准备接受 Gradle 降级，或直接使用清理数据、降级 Gradle 编译。

## 在 Android Studio 里怎么操作

1. 确认使用的是 Jugg Run Configuration，而不是原生 App configuration。
2. 选择目标设备。普通 Run 可以选择多台设备；Debug 只选择一台。
3. 保存或等待 Jugg 自动保存当前修改。
4. 点击 Run。
5. 在 Run tool window 里看本轮结果。

如果你点击的是 Debug，前半段仍然是同一条运行链路：Jugg 先编译并部署，成功后再用 debug 模式重启 App，并交给 Android Studio 原生 debugger attach。

## 点击 Run 后发生什么

```text
Jugg Run
  -> 保存文件并刷新文件状态
  -> 检查设备、安装状态和文件变化
  -> 优先尝试增量编译
  -> 必要时提示或降级 Gradle
  -> 编译成功后自动部署到设备
  -> 根据修改类型选择 Hot Reload、Hot Fix、安装或重启
  -> 输出本轮运行结果
```

编译和设备更新是内部阶段。日常使用时，先看 Run tool window 里的最终结果，再决定是否重启、清数据或降级 Gradle。

## 运行结果怎么看

| 你看到的结果 | 说明 | 下一步 |
|---|---|---|
| Jugg Hot Reload / 热重载成功 | 修改已在线生效，通常不重启 App | 直接在当前页面验证 |
| Jugg Hot Fix / 热修复成功 | 修改已下发，App 会重启后生效 | 等待 App 重新启动后验证 |
| Gradle 编译安装成功 | 本轮走完整 Gradle 构建和安装 | 后续小改动可继续 Jugg Run |
| Clean Reinstall 成功 | 已清数据、重装 APK，并恢复 Jugg 部署状态 | 重新进入需要验证的页面 |
| compile 成功但 deploy 失败 | 代码已经编译完成，设备部署或启动失败 | 先看设备连接、兼容模式和部署日志 |
| 没有检测到文件变化 | Jugg 未发现可处理改动 | 确认文件已保存，必要时 Sync 或直接 Gradle 对照 |

## 什么时候主动选别的入口

| 你想做什么 | 推荐入口 |
|---|---|
| 清 App 数据并重装 | [清理数据](./clean-data.md) |
| 完整 Gradle 构建一次 | [降级 Gradle 编译](./downgrade-gradle.md) / `jugg gradle-build` |
| 改完后马上进断点 | Debug |
| 跑 `src/androidTest` | 测试 gutter 或 Android Test Run Configuration |
| 给 Agent 或脚本触发运行 | `jugg deploy` 或 Jugg CLI Skill |
| App 没重启但你改了启动初始化逻辑 | [重启 App](./restart-app.md) |

> [!NOTE]
> Hot Reload 不会重新执行所有已经初始化过的逻辑。修改启动逻辑、单例缓存、static / companion / Kotlin 顶层声明后，即使本轮显示 Hot Reload，也建议主动 Restart 一次。

## 常见问题先看哪里

| 现象 | 先看什么 |
|---|---|
| Run 后没有进入 Jugg 输出 | 确认 Run Configuration 是 Jugg 类型 |
| 编译失败 | 看 [编译问题排查](../troubleshooting/compile.md) |
| 部署失败、App 没启动 | 看 [部署问题排查](../troubleshooting/deploy.md) |
| Debug 断点不可用 | 看 [Debug](./debug.md) 和 [Debug 问题排查](../troubleshooting/debug.md) |
| 增量结果和预期不一致 | 先执行一次 Gradle 构建对照，再保留日志 |

日志统一从这里开始看：

```bash
build/jugg/log/compile_latest.log
```

## 相关页面

- [降级 Gradle 编译](./downgrade-gradle.md)
- [重启 App](./restart-app.md)
- [清理数据](./clean-data.md)
- [多设备选择](./multi-device.md)
- [Android RemoteViews](./android-remoteviews.md)
- [设备兼容部署](./compat-device.md)
- [高级选项](./advanced-options.md)
- [Debug](./debug.md)
- [Android Test](./android-test.md)
- [Jugg 工作原理](../concepts/how-jugg-works.md)
- [重试、重装与 Gradle 构建](../concepts/fallback-and-limits.md)
