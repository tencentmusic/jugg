---
title: 设备兼容部署
description: 说明某台设备增量部署反复失败时，如何为该设备开启兼容部署。
status: active
tags:
  - guide
  - device
  - compat
---

# 设备兼容部署

兼容部署会让指定设备避开在线热重载路径，改用覆盖面更广、但通常需要重启 App 的热修复路径。某台设备反复部署失败，或 Jugg 明确提示需要 compat deploy 时，可以为这台设备开启该设置。

## 什么时候考虑开启

优先看 Jugg 输出和部署日志，而不是只按单个关键词判断。可以考虑开启的情况：

- Jugg 输出提示需要 `fallback to compat deploy`，或部署日志中出现 `agent no response` / `deploy timeout` 后仍无法恢复。
- 同一工程在别的设备正常，只在某台设备失败。
- 部署资源后，App 反复出现资源读取异常、`AssetManager` 相关崩溃或启动失败。
- App 自身有资源加载、类加载或热修复 hook，普通热重载后结果不符合预期。

如果只是本轮代码结果不符合预期，先使用 Restart 或 Gradle 构建做对照；不要仅因为日志中出现 `JVMTI`、`Apply Changes` 或 `classloader` 字样就直接判定为设备兼容问题。

## 哪些情况会自动使用兼容部署

在全局允许兼容部署的前提下，Jugg 会自动为以下设备或 App 使用兼容路径：

| 情况 | 原因 |
|---|---|
| Android 8～10 设备 | 系统不支持 Jugg 普通资源 overlay 切换路径 |
| 能读取到 HarmonyOS 版本属性的 Android 设备 | 自动使用 HarmonyOS 兼容路径 |
| ASUS 设备 | 自动规避已知的普通部署兼容问题 |
| 已被 Jugg 记录为兼容设备，或记录了特定 App | 后续运行继续复用对应的兼容设置 |

自动命中时不需要再次手动勾选。如果设备没有命中以上条件，但普通部署仍反复失败，再按下面的入口为当前设备手动开启。

入口在 More Options。连接设备后会出现类似选项：

```text
Force use compat deploy for <device>
```

开启或关闭后，Jugg 会让下一次运行重新安装目标 App，避免继续复用旧部署状态。

## 这个设置按设备生效

兼容部署记录跟设备绑定。换一台设备不会自动继承这个设置；同一台设备跨工程可能继续使用记录。

不要把所有设备都长期打开兼容部署。兼容部署会减少在线热重载机会，普通热更新通常会更慢。

## 相关页面

- [兼容部署原理](../concepts/compat-deploy.md)
- [运行 App](./run.md)
- [多设备选择](./multi-device.md)
- [清理数据](./clean-data.md)
- [HarmonyOS（非纯血鸿蒙）兼容部署](../capabilities/deploy/harmonyos-compat.md)
- [无法安装、启动或进入 Debug](../troubleshooting/app-cannot-run.md)
- [部署后 App 崩溃](../troubleshooting/runtime-crash.md)
