---
title: Full Swap
description: 说明 Jugg 需要重启 Activity 的 Apply Changes 部署能力。
status: active
tags:
  - capability
  - deploy
  - full-swap
---

# Full Swap

Full Swap 用于那些不能只靠在线 Code Swap 收口、但仍不需要完整 reinstall 的增量部署。Jugg 会先下发增量产物，再通过 Apply Changes and Restart Activity 让当前界面重新加载变化。

## Full Swap 触发场景

| 操作场景 | 当前支持情况 | 部署策略 |
|---|---|---|
| 需要重启 Activity 的增量变更 | 支持 | 执行 Apply Changes and Restart Activity |
| 资源 overlay 更新后需要界面刷新 | 支持 | 下发 overlay 后重启 Activity |
| 不需要重启 App 的非空变更 | 支持 | 根据 `isNeedRestartActivity` 选择 full swap |
| warm-up / dry deploy | 不触发用户可见 full swap | 仅用于状态探测 |
| 需要 App 进程重启的 hot fix | 不走 full swap | 转入 [Restart](./restart.md) 或 install |

## 这项能力如何生效

```text
增量编译成功
  -> 生成非空部署数据
  -> 判断不需要重启 App，但需要重启 Activity
  -> 执行 Apply Changes and Restart Activity
  -> 成功后提交部署历史
```

Full Swap 仍然属于增量部署。它的重点不是重新安装 APK，而是在 Apply Changes 成功后刷新 Activity 生命周期，让资源、布局或部分运行态变化在当前界面重新加载。

当前实现对普通、非空且不要求重启 App 的增量数据使用 Full Swap。因此方法体修改虽然可以在线替换 class，Activity 通常仍会重建；App 进程和进程内状态继续保留。

## 与 Code Swap 的区别

| 策略 | 用户感知 | 适合场景 |
|---|---|---|
| Code Swap | class 可以在线替换；是否重建 Activity 由外层动作决定 | 方法体等结构保持兼容的代码 |
| Full Swap | 当前 Activity 会重启 | overlay 或代码变化需要界面重新加载 |
| Restart | App 进程会重启 | hot fix、agent、调试或用户显式重启 |

## 相关页面

- [Code Swap](./code-swap.md)
- [Hot Reload](./hot-reload.md)
- [Restart](./restart.md)
- [资源编译](../compile/resource-compile.md)
- [Apply Changes 中的 class 与 overlay](../../concepts/apply-changes.md)
