---
title: Restart
description: 说明 Jugg 部署后重启 App 或 Activity 的触发场景和生效方式。
status: active
tags:
  - capability
  - deploy
  - restart
---

# Restart

Restart 用于让需要生命周期刷新或进程重新加载的部署结果生效。Jugg 会根据部署数据、运行配置和 Debug 入口决定重启 Activity、重启 App，或保持当前进程运行。

## 重启触发场景

| 操作场景 | 当前支持情况 | 重启方式 |
|---|---|---|
| Apply Changes 后需要刷新当前界面 | 支持 | 重启 Activity |
| hot fix class 或 push-only overlay | 支持 | 重启 App |
| 用户选择始终重启 | 支持 | 部署成功后重启 App |
| Debug executor 启动 | 支持 | 成功部署后重启 App，再 attach debugger |
| JVMTI agent 首次推送后检测 | 支持 | 重启 App，让 startup agent 被系统加载 |
| install 后启动 App | 支持 | 安装完成后按 runTask 收口启动 |

## 这项能力如何生效

```text
runTask()
  -> 根据 JuggDeployData 判断 deploy action
  -> 必要时 push JVMTI agent
  -> 根据部署类型和用户设置 restart app / restart activity / start app
  -> Debug 场景再 attach Java debugger
```

`isNeedRestartApp` 主要来自 hot fix classes 或 push-only overlay；`isNeedRestartActivity` 只在非 warm-up、payload 非空且不需要重启 App 时成立。Debug 入口会把“部署后重启 App”作为默认行为，以保证调试会话建立在新的进程状态上。

## 与部署策略的关系

| 策略 | 是否重启 |
|---|---|
| Code Swap | 通常不重启 |
| Full Swap | 重启 Activity |
| Hot Fix | 重启 App |
| Clean Reinstall | 重新安装后启动 App |
| JVMTI agent 更新 | 通常需要重启 App 后检测 |

## 相关页面

- [重启 App](../../guide/restart-app.md)
- [部署策略](../../concepts/deploy-strategy.md)
- [Full Swap](./full-swap.md)
- [Hot Reload](./hot-reload.md)
- [JVMTI Runtime](./jvmti-runtime.md)
- [Clean Reinstall](./clean-reinstall.md)
