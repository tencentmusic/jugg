---
title: 报告问题
description: 说明如何通过 Jugg 上传问题日志，以及 Issue ID 代表什么。
status: active
tags:
  - guide
  - report
  - logs
---

# 报告问题

报告问题功能用来把本地 Jugg 日志和设备错误日志上传到团队后台。它适合在增量编译失败、部署失败、运行结果不符合预期时使用。

## 从哪里打开

常用入口在 Jugg Run Configuration 里：

1. 打开 `Edit Configurations...`。
2. 选择当前 Jugg 配置。
3. 点击 `Report Issues`。
4. 确认后等待上传完成。
5. 上传完成后，在弹窗里点击 `Copy Issue ID and Close`。
6. 把复制的 Issue ID 粘贴给 Jugg 插件维护人员，并附上本轮操作步骤。

触发后会弹出确认框，随后在后台收集工程信息、dump 目标设备的错误 logcat，并上传日志包。

## 会上传什么

上传内容主要用于定位本轮 Jugg 行为：

- Jugg 编译和部署日志。
- 工程与插件相关上下文。
- 目标设备上的错误 logcat。
- 这次上传对应的 Issue ID。

日志包由插件打包后提交到后台 `/report_issue` 接口。后台保存原始 zip，并用 Issue ID 帮维护者找到这次上传。

> [!NOTE]
> 上传失败不会改变本地编译部署结果。失败时可以重新提交，或直接把本地日志路径发给维护者。

## 本地日志位置

如果暂时无法上传，可以先查看最新日志：

```bash
build/jugg/log/compile_latest.log
```

这个文件记录最近一次编译部署的主日志。排查部署、回退和运行时问题时，通常先看这里。

## 相关页面

- [首次运行](../onboarding/first-run.md)
- [日志文件](../reference/log-files.md)
- [编译问题排查](../troubleshooting/compile.md)
- [部署问题排查](../troubleshooting/deploy.md)
