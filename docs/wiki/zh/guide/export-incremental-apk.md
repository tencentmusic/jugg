---
title: 导出增量 APK
description: 说明如何从 Jugg 的确认弹窗导出当前增量 APK，以及导出前需要先完成哪些操作。
status: active
tags:
  - guide
  - apk
  - export
---

# 导出增量 APK

导出增量 APK 会把当前已经编译过的增量结果写入 APK，并重新签名。它适合把本轮改动交给别人安装验证，或在降级 Gradle 前保留一个可安装产物。

## 从哪里打开

入口在降级 Gradle 的确认弹窗里：

```text
Confirm fallback
  -> Export incremental APK
```

点击后选择输出目录。导出成功后，Jugg 会打开该目录。

## 导出前先编译

导出只处理已经编译过的文件。如果当前还有未编译文件，Jugg 会提示：

```text
Not all files are compiled:
```

遇到这个提示时，先运行一次 Jugg Run 或 Jugg compile，让本轮改动完成编译，再重新导出。

## 什么时候适合用

- 本轮增量已经成功，想把 APK 给其他人安装。
- 准备降级 Gradle，但想先留一份当前增量结果。
- 需要确认 APK 内容里是否已经包含本轮资源、Manifest 或 `.so` 变化。

导出不是普通 Run 的替代品。它不会帮你判断设备状态，也不会自动启动 App。

## 相关页面

- [降级 Gradle 编译](./downgrade-gradle.md)
- [运行 App](./run.md)
- [性能问题排查](../troubleshooting/performance.md)
