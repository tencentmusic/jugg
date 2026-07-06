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
- [兼容层](../concepts/compatibility-layer.md)
- [部署问题排查](../troubleshooting/deploy.md)
