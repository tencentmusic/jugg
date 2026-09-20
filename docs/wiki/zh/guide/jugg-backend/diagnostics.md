---
title: Jugg 后台诊断上报
description: 说明 Jugg 后台当前支持的使用情况事件上报，以及它与问题日志上传的边界。
status: active
tags:
  - guide
  - backend
  - diagnostics
---

# Jugg 后台诊断上报

后台诊断配置覆盖使用情况事件，以及可选的失败日志自动上传开关。它不会影响本地编译部署结果；上报失败时，插件只记录日志并继续当前流程。

用户手工提交和失败后自动提交的日志包都不经过自建后台，也不会使用 Custom Server。自建后台只负责在项目配置中决定是否启用自动上传和排除哪些已知错误。

## 事件上报

插件会向 `/report_event` 发送事件 JSON。后台可以保存这些字段用于统计和排查：

| 字段 | 说明 |
|---|---|
| `version` | Jugg 插件版本 |
| `ide_version` | Android Studio / IntelliJ 版本 |
| `username` | 用户标识 |
| `project_id` | 项目标识，通常来自 Git 仓库名或项目名 |
| `session_id` | 本轮编译部署会话标识 |
| `action` | 动作名，例如检查更新、编译、部署等 |
| `is_success` | 动作是否成功 |
| `cost_time` | 耗时 |
| `detail` | 附加信息 |

自建后台可以只返回事件 ID，也可以简单返回成功文本。关键是避免事件上报失败影响用户本地开发。

无论服务器是否存在或请求是否成功，插件都会把同一事件写入 `~/.jugg/action.db` 的 `jugg_event` 表。本地数据库只用于保留事件历史，不是远端失败后的自动补偿队列。

## 失败日志自动上传

项目配置中的 `autoUploadFailureLogs=true` 会开启最终失败日志自动上传。`autoUploadFailureLogsExcludeRegex` 是可选的排除正则：它对当前 Run 的最终错误摘要做包含匹配，命中时不上传。字段为空时不过滤；正则非法时跳过本次上传。

自动上传只覆盖最终编译失败和已经实际进入部署后的最终失败。取消、跳过部署、没有设备且部署尚未开始，以及降级 Gradle 后最终成功不会触发。上传包只有最近两份脱敏 Jugg 日志和 manifest，一次 Run 最多上传一次。

## 问题日志不经过自建后台

问题日志由插件上传到固定的问题报告服务 `https://jugg.sickworm.com/report_issue`。这同时适用于用户手工上传和后台配置启用的自动上传。自建后台不需要实现 `/report_issue`；实现该接口也不会改变日志上传路径。

需要了解用户侧操作、诊断包内容和 Report ID 时，见 [报告问题](../report-issue.md)。

## 存储建议

- 按日期、项目或事件动作组织事件记录。
- 保留上报时间、用户、项目、插件版本、动作名和结果。
- 对事件记录设置合理保留期。
- 如果团队有隐私或合规要求，发布前先明确事件字段可包含的用户标识和项目信息。
- 上报失败时返回清晰错误，但不要让该失败影响本地编译部署。

## 相关页面

- [报告问题](../report-issue.md)
- [日志文件](../../reference/log-files.md)
- [编译失败](../../troubleshooting/compile-failed.md)
- [无法安装、启动或进入 Debug](../../troubleshooting/app-cannot-run.md)
