---
title: Jugg 后台诊断上报
description: 说明 Jugg 后台当前支持的事件上报和问题日志上传能力。
status: active
tags:
  - guide
  - backend
  - diagnostics
---

# Jugg 后台诊断上报

诊断上报用于帮助团队了解 Jugg 使用情况，并在用户提交问题时收集日志。它不会影响本地编译部署结果；上报失败时，插件通常只记录日志并继续当前流程。

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

## 问题日志上传

用户提交问题时，插件会将 Jugg 日志和相关上下文打成 zip，通过 `/report_issue` 以 multipart 方式上传。

日志包通常用于回答：

- 本轮编译是否走了增量、Gradle 回退或 Clean Reinstall。
- 部署失败发生在安装、热更、重启还是设备通信阶段。
- 远端编译或自定义编译器是否产生日志。
- 用户设备上的关键 logcat 片段是什么。

后台应保存原始 zip，并返回 200 表示上传成功。插件侧会生成本次上传的 report id，便于用户把问题和后台文件对应起来。

## 存储建议

- 按日期、项目或 report id 组织日志文件。
- 保留上传时间、用户、项目、插件版本和客户端 IP。
- 对日志包设置合理保留期，避免长期保存过多本地路径和运行日志。
- 如果团队有隐私或合规要求，发布前先明确日志包可包含的本地路径和构建信息。
- 上传失败时返回清晰错误，方便用户重新提交或改用手动打包日志。

## 相关页面

- [日志文件](../../reference/log-files.md)
- [编译问题排查](../../troubleshooting/compile.md)
- [部署问题排查](../../troubleshooting/deploy.md)
