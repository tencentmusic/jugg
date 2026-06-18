---
title: 部署结果说明
description: 解释 Jugg Run 内部的部署阶段，以及 Hot Reload、Hot Fix、恢复状态、重装和多设备结果。
status: active
tags:
  - guide
  - deploy
---

# 部署结果说明

Jugg 部署发生在 Run 的编译阶段成功之后。日常使用先看 [运行 App](./run.md)，不需要先手动编译、再手动部署。本页用于解释 Run 结束后看到的 Hot Reload、Hot Fix、安装、恢复状态、重装和多设备结果。

## 什么时候需要看本页

适合阅读本页的场景：

- Run 已完成编译，但部署或启动失败。
- 你想判断本轮为什么是 Hot Reload、Hot Fix、Install 或 Clean Reinstall。
- 你需要了解兼容模式、多设备部署或 Restart 的行为。
- 你在 CLI / MCP 中显式调用 `jugg deploy`、`jugg clean-reinstall` 或 `jugg restart`。

## Jugg 会选择哪种部署

| 部署结果 | 用户感知 | 常见触发场景 |
|---|---|---|
| Install | 安装 APK 并启动 App | 首次运行、Gradle 构建后、部署状态需要重建 |
| Hot Reload | 不重启 App 或只重建 Activity | 方法体等小范围代码变化、资源/asset 变化 |
| Hot Fix | 重启 App 后生效 | 类结构变化、静态初始化相关变化、兼容部署 |
| Compat Hot Fix | 使用经典热修复路径并重启 | 用户开启兼容部署，或 Jugg 检测到当前设备需要兼容路径 |
| Clean Reinstall | 清数据并重装 APK | 你明确需要清理 App 数据、测试安装或恢复基线 |

> [!NOTE]
> Hot Reload 不等于所有代码都会重新执行。修改启动逻辑、单例初始化、static / companion / Kotlin 顶层声明后，未重启的 App 会继续保留旧进程里的已初始化状态。

## 什么时候会重启 App

按变化类型判断：

- 修改 `res` 或 `assets`，优先走 overlay 生效。
- 只改方法体，类、方法、字段签名不变时，优先 Hot Reload。
- 修改类结构、字段、方法签名、继承关系或需要推送完整 overlay 时，走 Hot Fix 并重启。
- 开启兼容模式后，走经典热修复路径并重启。
- Jugg Debug 会强制以 debug 模式重启 App，让进程等待 debugger attach。

如果你修改的是只在进程启动时执行一次的逻辑，即使本轮提示 Hot Reload，也建议点击 Restart 或用 `jugg restart`。

## 兼容模式

兼容模式会让指定设备改走经典热修复路径，不再优先依赖在线热重载。它主要用于处理当前设备在普通部署路径上反复失败的情况。

常见触发信号包括：

- Jugg 明确提示需要 `fallback to compat deploy`。
- `agent no response`、`deploy timeout` 等失败在重试后仍不能恢复。
- 同一工程只在某台设备上反复部署失败。
- 部署资源后，App 反复出现资源读取异常、`AssetManager` 相关崩溃或启动失败。
- App 自身有资源加载、类加载或热修复 hook，普通热重载后结果不符合预期。

连接设备后，可以在 More Options 中为指定设备开启兼容部署。该设置会按设备持久化，跨工程生效。

## Clean Reinstall 和直接 Gradle

如果你只是想“完整构建一次”，使用直接降级或 `jugg gradle-build`。

如果你还需要清理 App 数据并重装，使用 Clean Reinstall。它相当于把“清数据 + 重装 APK + 恢复 Jugg 增量部署状态”放在一次操作里，避免你在手机设置里手动清除数据后丢失部署记录。

> [!WARNING]
> 直接在手机上清除 App 数据会同时清掉增量部署记录。下一次运行时 Jugg 会尝试自动恢复，但如果你本来就想清数据测试，优先使用 Clean Reinstall。

## 多设备部署

多设备部署会逐台执行。最终结果中只要有任一设备失败，本轮就会显示失败；当失败允许回退且自动回退开启时，Jugg 会把整轮 Run 回退到 Gradle，而不是只重跑失败设备。

Debug attach 当前只支持单设备。如果要使用 Jugg Debug，请只选择一个目标设备。

## 常见部署失败先看哪里

| 现象 | 先做什么 |
|---|---|
| 提示 App 未启动或不可 debug | 确认 App 是 debug 包，ADB 没被其它 Android Studio 占用 |
| 提示恢复部署状态失败 | 尝试重新运行；仍失败时执行 Clean Reinstall |
| `MISSING_AGENT_RESPONSE` 或 deploy timeout | 先看本轮是否已经重试；同一设备反复出现时，再尝试兼容模式 |
| 修改后没检测到文件变化 | 取消后重新运行，必要时 Sync 一次 |
| 部署后代码没生效 | 重启 App 或主动 Gradle 构建对照 |

日志入口：

```bash
build/jugg/log/compile_latest.log
```

## 相关页面

- [运行 App](./run.md)
- [部署策略](../concepts/deploy-strategy.md)
- [部署历史与缓存](../capabilities/deploy/deploy-history-cache.md)
- [Hot Reload](../capabilities/deploy/hot-reload.md)
- [Clean Reinstall](../capabilities/deploy/clean-reinstall.md)
- [Recover 与 Retry](../capabilities/deploy/recover-and-retry.md)
- [部署问题排查](../troubleshooting/deploy.md)
