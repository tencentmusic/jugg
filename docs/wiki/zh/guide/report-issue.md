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

推荐使用以下任一入口：

- 在 Android Studio 中双击 `Shift`，搜索并选择 `Report Jugg Issue`。
- 打开 `Jugg Running Panel`，点击 `Report Issue`。

也可以从 Jugg Run Configuration 打开：

1. 打开 `Edit Configurations...`。
2. 选择当前 Jugg 配置。
3. 点击 `Report issues`。

打开报告窗口后：

1. 核对并选择诊断文件。
2. 选择 `Upload logs` 上传，或勾选 `Save locally without uploading` 后创建本地诊断包。
3. 上传完成后复制 Report ID；失败时把保留的 zip 交给维护人员。

插件会先生成脱敏候选文件，再展示文件路径和大小。上传固定请求 Jugg 问题报告服务，失败后不会切换服务器。选择本地保存后，系统文件管理器会选中新生成的 zip。

## 会上传什么

上传内容主要用于定位本轮 Jugg 行为：

- Jugg 编译和部署日志。
- 结构化的环境和工程摘要，不包含原始工程模型。
- 可取消的目标设备错误 logcat。
- 可选的 hook 调试日志。
- 描述实际 zip entry 的 `manifest.json`。

原始 `project_infos`、签名密码、Manifest placeholders、APT/KAPT 参数、源码和二进制依赖不会进入诊断包。hook 调试日志在诊断包中保存为 `diagnostics/cli/hook-debug.log`。

> [!NOTE]
> 上传失败不会改变本地编译部署结果。临时 zip 保留在 `build/jugg/tmp/diagnostics`，可以重试上传；达到 7 天后会在项目启动后的清理任务中删除。

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
