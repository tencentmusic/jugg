# Jugg 问题调查

请在当前 Android 工程中调查以下 Jugg 问题，结合 Jugg 知识和已保存的现场定位原因，给出可执行的建议。请使用中文分析、回复和填写 Issue 内容。

## 已保存的现场

- 问题类型：{{problemType}}
- 工程目录：{{projectDir}}
- 现场目录：{{directory}}
- 证据清单：{{manifest}}
- 采集时间：{{createdAt}}

## 问题描述

{{description}}

## 调查入口

请读取最新调查指南，按其要求建立 Jugg 前置知识并分析现场：
https://raw.githubusercontent.com/tencentmusic/jugg/main/.agents/skills/issue-handler/references/local-agent-investigation-guide.md

现场已由 ASK AI 保存。环境、任务信息和采集失败项均在 manifest 中；直接使用现有副本，仅在关键证据缺失时按指南补充。
本次允许在独立调查目录获取或更新官方 Jugg 源码；用户工程和已有源码仓库保持只读，不运行构建、复现或设备命令，不自动上传现场或提交 Issue。
若指南无法读取，说明原因，先读 manifest 和同一任务的完整日志，给出有证据支持的结论、必要的版本或知识缺口及下一步。
