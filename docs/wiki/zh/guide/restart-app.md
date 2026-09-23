---
title: 重启 App
description: 说明什么时候只需要重启目标 App，以及 IDE、CLI 和部署设置中的重启入口。
status: active
tags:
  - guide
  - restart
---

# 重启 App

有些改动已经部署到设备，但旧进程里的状态不会自己重新执行。此时不用重新编译，直接重启 App 更快。

## 什么时候重启

建议主动重启的情况：

- 修改了启动流程、登录态初始化或路由初始化。
- 改了单例缓存、static、companion object、Kotlin 顶层声明。
- 本轮显示 Hot Reload，但页面表现仍像旧逻辑。
- 刚推送过资源或 agent，想确认新进程状态。

如果本轮已经是 Hot Fix、Clean Reinstall 或 Debug，Jugg 通常会自动重启。

## 怎么重启

在 Android Studio 里使用 Jugg 的 Restart 入口。终端或 Agent 场景可以执行：

```bash
jugg restart
```

Restart 只重启当前 Jugg Run Configuration 对应的 App。它不会重新编译，也不会清理 App 数据。

启动目标按 launch Activity、HOME Activity 的顺序降级。如果这个 App 两者都没有，Restart 没有页面可以拉起，也不会改启动其它普通 Activity，只会执行 `am force-stop <package>` 停止 App，并在日志中说明。这是 Restart 的最终降级结果，App 数据仍然保留。

## 每次部署后都重启

如果当前改动频繁碰到启动态问题，可以在 More Options 中打开：

```text
Always restart app after deployment
```

这个开关会让后续部署成功后都重启 App。确认问题后建议关掉，否则会失去 Hot Reload 保持页面状态的优势。

## 相关页面

- [运行 App](./run.md)
- [清理数据](./clean-data.md)
- [高级选项](./advanced-options.md)
- [部署策略](../concepts/deploy-strategy.md)
- [Restart 能力](../capabilities/deploy/restart.md)
