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
| install 后启动 App | 支持 | 安装完成后由部署流程启动 |

## 这项能力如何生效

```text
部署数据生成
  -> 判断部署动作与生命周期边界
  -> 必要时 push JVMTI agent
  -> 根据部署类型和用户设置 restart app / restart activity / start app
  -> Debug 场景再 attach Java debugger
```

Hot Fix class、push-only overlay 和部分进程级缓存会要求重启 App；普通非空且不要求重启 App 的增量部署会重建 Activity。Debug 入口会把“部署后重启 App”作为默认行为，以保证调试会话建立在新的进程状态上。

## 启动目标的选择顺序

Restart、install 后启动和 Recover 启动共用同一套启动目标选择，按整个 APK 集合（含 split APK）依次降级：

| 顺序 | 启动目标 | 命中条件 | 最终动作 |
|---|---|---|---|
| 1 | launch Activity | `MAIN` + `LAUNCHER`/`LEANBACK_LAUNCHER` | 启动该 Activity |
| 2 | HOME Activity | enabled、exported，且含 `MAIN` + `HOME` | 启动该 Activity |
| 3 | 停止 App | 前两类都不存在 | `am force-stop <package>` |

降级跨全部 APK 分阶段执行：先遍历所有 APK 找 launch Activity，找不到才遍历所有 APK 找 HOME Activity。因此后续 split APK 的 launch Activity 仍然优先于前面 APK 的 HOME Activity。两者都不存在时 Jugg 不会改启动其它普通 Activity，只停止 App。

restart 时启动命令附带 `-S`，Debug 时附带 `-D`；第 2 级也保持这些参数。走到第 3 级时 restart 已经完成，但 App 是停止状态而不是启动状态，Restart 不会因此清理 App 数据。

## 与部署策略的关系

| 策略 | 是否重启 |
|---|---|
| Code Swap | 不要求重启 App；普通非空部署通常仍重建 Activity |
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
- [Apply Changes 中的 class 与 overlay](../../concepts/apply-changes.md)
