---
title: Jugg 后台插件分发与热更新
description: 说明 Jugg 后台如何支持完整插件包升级、热更新、灰度和自定义编译器下载。
status: active
tags:
  - guide
  - backend
  - update
---

# Jugg 后台插件分发与热更新

Jugg 后台可以提供两类更新能力：完整插件包升级和热更新。完整插件包适合常规版本发布；热更新适合在不立即更换整包的情况下下发插件 jar 更新。

## 完整插件包升级

完整升级由 `/check_update` 返回结果驱动：

```text
插件检查更新
  -> 后台比较当前版本和最新版本
  -> 返回 isNeedUpgrade 与 downloadUrl
  -> 插件在 IDE 中展示升级通知
  -> 用户打开下载页或下载插件包后手动安装
```

后台通常需要准备：

- 一个插件 zip 存放目录。
- 一个可访问的下载页或直接下载地址。
- 最新版本号的选择规则。
- 可选的版本变更说明。

如果团队不希望后台管理插件包，可以始终返回 `isNeedUpgrade=false`。

## 热更新

热更新由 `/check_hot_update` 和 `/download_hot_update` 组成。后台返回目标版本、更新说明、是否需要重新安装，以及一组 jar 文件信息。

| 字段 | 说明 |
|---|---|
| `isNeedUpdate` | 是否需要热更新 |
| `targetVersion` | 目标版本 |
| `updateInfo` | 更新完成后展示给用户的通知 |
| `jarFileInfos` | 需要下载和校验的 jar 列表 |
| `isNeedReinstall` | 更新后是否需要重新安装插件 |

每个 jar 文件信息包含唯一文件名、下载 URL 和 md5。插件会下载缺失文件并校验 md5；校验失败时不会继续使用该文件。

## 灰度策略

后台可以自行决定哪些用户命中热更新。常见策略包括：

- 指定用户灰度。
- 按发布时间分阶段扩大范围。
- 对已知问题版本强制提供更新。
- 用户手动检查更新时直接返回可用更新。

灰度策略只影响后台是否返回 `isNeedUpdate=true`，不改变插件端的下载和校验流程。

## 自定义编译器下载

自定义编译器可以作为项目配置的一部分下发。后台只需要保证 `customCompilers.path` 指向可下载 jar，并提供对应 md5。

如果使用 `/download_custom_compiler`，建议只允许访问项目配置目录下的文件，并拒绝包含路径穿越的 file key。

## 相关页面

- [自建接入清单](./self-hosting.md)
- [项目配置下发](./project-config.md)
- [自定义编译器](../custom-compiler.md)
