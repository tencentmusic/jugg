---
title: 性能问题排查
description: 排查 Jugg 编译部署耗时、Gradle 回退、依赖库增量编译耗时和增量 APK 导出耗时问题。
status: active
tags:
  - troubleshooting
  - performance
---

# 性能问题排查

Jugg 的常规增量路径应该比完整 Gradle 构建更快。耗时异常时，先确认本轮是否真的走了增量编译，而不是主动或自动回退到 Gradle。

## 如何分辨增量和 Gradle

参考文档中给出的判断方式：

- 降级为 Gradle 编译时会弹出 Toast。
- 增量编译完成后，提示 Jugg 热重载或热修复成功。
- Gradle 编译完成后，提示 Gradle 编译安装成功。
- 底部会展示已运行时间；编译超过 1 分钟后会展示。

## 直接走 Gradle

如果日志里出现：

```text
Compile modules too much(... modules), will fallback to gradle compile for better performance.
Compile files too much(... files), will fallback to gradle compile for better performance.
No file changes. will fallback to gradle compile.
```

说明 Jugg 认为本轮不适合继续增量，或没有发现可部署变化。

处理方式：

1. 确认是否修改了大量源码文件。
2. 确认文件已经保存。
3. 如果只是误触发无变化运行，可以点击取消降级。
4. 如果是 build 文件或依赖变化，按依赖库增量编译或 Gradle 回退处理。

## 依赖库增量编译较慢

参考文档中说明，依赖库增量编译会先执行一段 Gradle 命令找出变化依赖，整体耗时约 `40-80s`，主要耗时在 Gradle 读取依赖变化阶段。

当检测到 build 文件修改时，弹窗选项包括：

- `Fallback to Gradle`：降级为 Gradle 编译。
- `Find out changed Libraries`：找出依赖库变化。
- `Ignore build changes`：忽略 build 文件变更。
- 关闭弹窗：取消。

如果只是升级依赖库，优先确认变化列表符合预期后再继续增量编译。

## 导出增量 APK 耗时

导出增量 APK 会把当前增量改动打入 APK，并使用工程签名文件重新签名。它不是简单复制 base APK，因此耗时会包含写入 APK 和重签名过程。

如果导出失败并提示：

```text
Not all files are compiled:
Export incremental apk failed: ...
```

先确保所有改动已经完成编译，再重新导出。

## 相关页面

- [增量编译](../concepts/incremental-compile/)
- [依赖库增量编译](../capabilities/compile/dependency-incremental.md)
- [Gradle 回退](../capabilities/compile/gradle-fallback.md)
