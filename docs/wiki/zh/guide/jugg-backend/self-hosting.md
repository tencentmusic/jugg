---
title: Jugg 后台自建接入清单
description: 说明自建 Jugg 后台时的最小接口、可选接口和上线前检查项。
status: active
tags:
  - guide
  - backend
---

# Jugg 后台自建接入清单

自建后台的目标通常有两类：一类是让团队内部统一下发 Jugg 配置和插件版本，另一类是收集诊断信息、提供热更新和远端机器申请。建议先完成最小接口，再逐步接入增强能力。

## 最小可用接口

| 接口 | 方法 | 最小行为 |
|---|---|---|
| `/check_update` | `GET` | 返回当前最新版本、是否需要升级、下载入口、通知和项目配置 |
| `/report_event` | `POST` | 接收事件 JSON，成功时返回 2xx |
| `/report_issue` | `POST multipart` | 接收日志 zip，成功时返回 200 |
| `/check_hot_update` | `GET` | 不使用热更新时返回空更新结果 |

如果只需要项目配置下发，可以让 `/check_update` 返回 `isNeedUpgrade=false`，并在 `customConfigJson` 中放入项目配置。其它接口返回成功或空结果即可。

## `/check_update` 返回内容

| 字段 | 说明 |
|---|---|
| `latestVersion` | 后台认为的最新完整插件版本 |
| `isNeedUpgrade` | 是否提示用户下载安装完整插件包 |
| `downloadUrl` | 完整插件包下载页或下载地址 |
| `templateList` | 旧字段，当前可返回空数组 |
| `notification` | 可选通知，插件会在 IDE 中展示 |
| `customConfigJson` | 可选项目配置，插件会应用到当前项目 |

`customConfigJson` 是自建后台最常用的能力。它可以按项目名返回不同配置，详见 [项目配置下发](./project-config.md)。

## 可选增强接口

| 能力 | 相关接口 | 什么时候接入 |
|---|---|---|
| 完整插件包下载 | `/download_page`、`/download` | 希望从内部后台统一发布插件包 |
| 热更新下载 | `/check_hot_update`、`/download_hot_update` | 希望下发 jar 级别更新 |
| 热更新状态 | `/check_hot_update_status` | 运维或灰度排查需要查看当前热更新状态 |
| 自定义编译器下载 | `/download_custom_compiler` | 项目配置中下发自定义编译器 jar |
| 远端机器申请 | `/remote_apply` 等交互接口 | 团队有内部云开发机申请系统 |

## 部署前检查

- 后台域名必须能被安装 Jugg 的开发机访问。
- 插件包、热更新 jar 和自定义编译器 jar 的下载链接必须可直接下载。
- 如果返回 `md5`，文件内容必须和 md5 匹配。
- `/report_issue` 应接受较大的 zip 文件，避免用户提交日志失败。
- 如果接入数据库，至少保存事件时间、用户标识、项目、版本、动作和结果。
- 如果不使用某项能力，应返回空配置或空更新，而不是返回 500。

## 与本地能力的关系

后台只负责配置、分发和诊断，不接管本地编译部署流程。Jugg Run、Debug、Android Test、CLI 和 MCP 的执行仍发生在本地 Android Studio 或本机命令行环境中。

## 相关页面

- [Jugg 后台](./index.md)
- [项目配置下发](./project-config.md)
- [插件分发与热更新](./plugin-delivery.md)
- [诊断上报](./diagnostics.md)
