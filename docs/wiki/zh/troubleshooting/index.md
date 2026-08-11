---
title: 问题排查
description: 从编译失败、改动未生效、运行时崩溃、无法启动和运行缓慢等用户可见现象开始恢复 Jugg。
status: active
tags:
  - troubleshooting
---

# 问题排查

遇到问题时，可按照具体表现进行查询，也可以直接使用搜索。每个页面优先给出可以直接执行的恢复动作；如果仍未解决问题，请前往 Github 报告问题。

## 日常开发问题

### [编译失败](./compile-failed.md)

适用于 Java、Kotlin、Res、Assets、Manifest 等出现编译错误的情况。尤其是 Jugg 增量编译失败但 Gradle 能够成功。

### [改动没有生效](./changes-not-applied.md)

适用于修改文件后没有文件变化，或编译成功后代码或资源仍是旧结果等情况。

### [部署后 App 崩溃](./runtime-crash.md)

适用于编译和部署已经成功，但 App 随后出现 Java 异常、native crash。尤其是 Jugg 增量编译 Crash 但 Gradle 没有问题。

### [无法安装、部署、启动或 Debug](./app-cannot-run.md)

适用于出现设备不可用、APK 安装失败、App 无法启动、部署状态恢复失败、JVMTI agent 无响应或 Debug attach 失败。

### [Jugg 运行缓慢或卡住](./jugg-slow-or-stuck.md)

适用于非预期的 Gradle 构建导致编译长耗时、App 启动卡死无法进入主页面，或 Android Studio 持续高 CPU 占用/卡死等情况。

## 特定功能问题

### [Android Test 无法运行或测试失败](./android-test-failed.md)

适用于测试源码、test APK、测试类或 instrumentation 无法解析和运行。

### [远程编译失败](./remote-build-failed.md)

适用于打开远程编译后的编译失败。如远端工程未同步、Gradle Wrapper、Windows 换行与编码、APK 回传和自定义输出目录问题。

### [Agent 或命令执行失败](./agent-command-failed.md)

适用于使用了 Jugg CLI、MCP，但遇到 UI 自动化、布局检查、截图和录屏工具的参数或运行环境错误。

## 常用恢复动作怎么选

- 编译失败，报错引用不存在，执行 Gradle Sync 通常能解决问题。
- 改动未生效，判断是否是改动的是启动阶段的代码且部署命中 HOT RELOAD，如果是，重启 App 即可。
- 如果问题只在特定设备上出现，尝试打开兼容模式后重新点击运行，看问题是否消失。
- 如果以上情况皆不符合，且你的 Gradle 构建耗时不算特别长，进行一次 Gradle 构建看问题是否不再出现，是最快速的自愈路径。
  > Jugg 会保存最近 10 次 Gradle 构建/项目打开的历史日志。你依然可以在问题消失后在 Github 进行问题反馈。

## 仍然没有解决

使用 [报告问题](../guide/report-issue.md) 上传现场并复制 Report ID。如果需要自行排查，参考[日志文件](../reference/log-files.md)。
