---
title: 首次运行
description: 说明第一次点击 Jugg Run 时会发生什么、如何判断结果，以及哪些情况需要主动回退 Gradle。
status: active
tags:
  - onboarding
  - run
---

# 首次运行

安装插件并等待工程 Sync 完成后，可以用 Jugg 跑一次 App。第一次运行要建立 Gradle 基线和部署状态，不以增量速度为目标。

## 点击 Run 前

点击 Run 前先确认：

1. 运行配置选择的是 `jugg:模块名`，不是原生 App 配置。
2. 已选择目标设备，设备 Android 版本为 Android 8 或以上。
3. 工程 Sync 已完成，没有正在进行的 Gradle import。

确认后点击 Android Studio 的 Run 按钮。Jugg Run 支持取消；如果发现选错设备或配置，直接停止本轮运行。

## 首次运行会发生什么

```text
点击 Jugg Run
  -> 检查工程和设备状态
  -> 首次缺少增量基线，回退 Gradle 编译
  -> 编译并安装 APK
  -> 后台收集后续增量编译需要的产物
  -> 后续小改动优先进入增量编译和部署
```

首次运行、修改 `build.gradle`、依赖变化或切分支后，Jugg 会在基线不可信时回退到 Gradle。回退时会提示原因。

## 日常修改后怎么用

小范围修改代码、资源、layout 或 assets 后，继续点击同一个 Jugg Run Configuration。Jugg 会根据文件变化选择执行路径：

| 修改类型 | 常见结果 |
|---|---|
| Java / Kotlin 方法体、小范围资源修改 | 增量编译后热部署 |
| 需要重启才能生效的代码 | 编译后重启 App |
| Gradle、依赖或基线缺失 | 回退 Gradle 编译 |
| 明确不支持的场景 | 提示失败或建议 Gradle 对照 |

运行结束后，以 Run tool window 里的最终结果为准。

## 先记住这些限制

- Jugg 会忽略删除操作。删除类、资源或 Manifest 节点后，如果要确认旧内容真的不存在，做一次完整 Gradle 构建或重新安装。
- 反射相关逻辑会绕过部分静态影响分析，删除验证时不要只看增量结果。
- 注解器只支持已适配能力。已有生成代码不受影响；新增或修改未适配注解时，先用 Gradle 构建确认结果。
- 清除 App 数据会导致部署历史丢失。重新点击一次 Run 后，Jugg 会恢复部署状态。
- 如果增量结果不符合预期，先跑一次 Gradle 对照；确认是 Jugg 问题后再上传日志。

## 主动回退 Gradle

遇到下面情况，先主动回退：

| 场景 | 原因 |
|---|---|
| 手动清理过 `build` 目录 | 增量编译依赖的产物缺失 |
| 改了构建脚本、插件、依赖版本 | 需要完整 Gradle pipeline 重新计算 |
| 增量编译失败且无法自动恢复 | 先重建基线，再继续增量 |
| 怀疑本轮运行结果不正确 | 用 Gradle 结果做对照 |

在没有文件修改的情况下再次点击 Jugg Run，也可以选择降级到 Gradle。这个入口适合手动刷新基线。

## 反馈问题

遇到问题时，优先用 [报告问题](../guide/report-issue.md) 上传日志。上传完成后，把 Issue ID 和操作步骤发给维护者。

本地日志入口是 `build/jugg/log/compile_latest.log`。

## 下一步

- [运行 App](../guide/run.md)
- [报告问题](../guide/report-issue.md)
- [编译问题排查](../troubleshooting/compile.md)
- [部署问题排查](../troubleshooting/deploy.md)
