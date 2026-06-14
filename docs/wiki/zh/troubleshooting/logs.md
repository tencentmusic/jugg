---
title: 日志
description: 说明 Jugg 问题排查时优先保存和查看的日志、数据库与提交问题信息。
status: active
tags:
  - troubleshooting
  - logs
---

# 日志

排查 Jugg 问题时，最重要的是保留本轮现场。优先查看和保存项目目录下的 Jugg 日志。

## 编译与部署日志

优先查看：

```text
build/jugg/log/compile_latest.log
```

如果这个快捷文件不存在，查看同目录下最新的：

```text
build/jugg/log/compile_*.log
```

常用搜索关键词：

```text
Found incremental compile error
No file changes
fallback
Deploy Changes failed
Install APK failed
Try recover deploy state failed
MISSING_AGENT_RESPONSES
Got deploy timeout exception
Jugg Debug attach failed
Instrumentation test run reported failures
```

## 状态与数据库

如果问题和增量部署状态、设备状态恢复、代码不生效有关，保存：

```text
build/jugg/database/
```

其中部署历史异常时，可以重点关注：

```text
build/jugg/database/deploy_history.db/
```

> [!WARNING]
> 不建议在没有备份的情况下删除整个 `build/jugg/`。这会丢失日志、数据库和现场产物。

## 提交问题时附带什么

请尽量附带：

- `build/jugg/log/compile_latest.log` 或最新 `compile_*.log`。
- 本轮修改了哪些文件。
- 是否是 debug / release / androidTest。
- 是否使用多设备、dynamic feature、资源混淆、远端 Gradle 或依赖库增量编译。
- 设备型号、Android 版本。
- 如果有 crash，附完整 crash 堆栈。

## 相关页面

- [日志文件参考](../reference/log-files.md)
- [编译问题排查](./compile.md)
- [部署问题排查](./deploy.md)
