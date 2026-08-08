---
title: 远端 Gradle 问题排查
description: 汇总远端 Gradle 的同步排除、Wrapper 修复、Windows 编码与换行、自定义输出目录和工程信息刷新问题。
status: active
tags:
  - troubleshooting
  - remote-gradle
  - windows
---

# 远端 Gradle 问题排查

远端 Gradle 同时跨越本地文件、同步工具、远端 shell、JDK/SDK 和产物回传。很多“Gradle 没启动”或“构建成功但本地仍异常”的问题，实际发生在 Gradle task 之前或之后。

## 先判断失败发生在哪一段

\`\`\`text
本地准备
  -> 同步到远端
  -> 远端启动 Gradle
  -> 执行构建
  -> 拉回 APK / classpath / generated source
  -> 刷新本地工程信息
\`\`\`

优先用日志判断失败边界，不要只看最后一行错误。

## Gradle Wrapper 自动修复

当编译命令使用 \`gradlew\` 或 \`gradlew.bat\`，且对应目录已经存在 \`gradle/wrapper/gradle-wrapper.properties\` 时，Jugg 会检查以下启动文件：

- \`gradlew\`
- \`gradlew.bat\`
- \`gradle/wrapper/gradle-wrapper.jar\`

缺失文件会从插件内置资源补齐，已有文件不会被覆盖。工程没有 wrapper properties，或命令使用系统 Gradle 时，Jugg 不会修改工程。

## Windows CRLF 与命令输出

Windows 工作区的 \`gradlew\` 使用 CRLF 时，同步到 Linux 后可能出现：

\`\`\`text
/usr/bin/env: sh\r: No such file or directory
\`\`\`

在 Windows 本机启用远端编译时，Jugg 会在同步前把实际使用的 Unix \`gradlew\` 从 CRLF 转为 LF。它不会修改 \`gradlew.bat\`、其它 shell 脚本或 Gradle 文件。

Windows Gradle 输出按 UTF-8 严格解码；不是有效 UTF-8 时回退 GBK。若日志仍出现乱码，先确认错误是否来自外部工具自行混合编码。

## Exclude patterns

`Exclude patterns` 展示并控制可配置排除列表，使用 rsync pattern，不是 gitignore：

- 推荐用分号分隔，旧的逗号和换行仍兼容。
- 规则相对于实际传输根目录。
- 以 `/` 开头表示锚定传输根。
- 不支持通过父目录路径越出同步根。
- `.gradle` 和 `build` 始终由 Jugg 固定排除，其中 Jugg 必需文件通过更早的 include 规则放行。
- 未修改时显示并使用 `local.properties`、`.idea/`、`*.iml`、`.git/objects/`、`.git/modules/`、`.cxx/`。
- 修改后只使用界面中保存的可配置列表；清空字段表示不应用这些可配置排除规则，固定 `.gradle` 和 `build` 规则不受影响。

示例：

```text
.git/objects/; local-temp/**
```

多工程模式下，用户自定义规则仍按实际传输根解释，不会自动加当前项目路径前缀。

如果升级前配置过 `Additional exclude patterns`，升级后需要在新的完整列表中重新填写。删除目录规则会同步整个目录；如果只想减少目录中的部分内容，需要继续用 rsync exclude pattern 描述不需要同步的部分。

## 命令和输出目录变化

远端编译命令改变后，Jugg 会先刷新与新命令对应的工程信息，再启动远端构建。自定义 Gradle build directory 会用于 APK、classpath 和 generated source 的拉回路径。

如果远端构建成功但本地仍使用旧产物，检查：

1. Run Configuration 的编译命令是否已切到新 task。
2. Output APK pattern 是否匹配实际 build directory。
3. 远端产物是否被同步规则排除。
4. 本地工程信息刷新是否成功。

## 症状速查

| 现象 | 第一处理 |
|---|---|
| \`sh\\r\` / \`env: sh\` 错误 | 确认本机是 Windows 远端编译，并检查同步前本地 \`gradlew\` 是否已转 LF |
| wrapper 文件缺失 | 确认 wrapper properties 存在且命令使用该目录的 wrapper |
| 中文日志乱码 | 确认输出来自 Gradle；外部工具混合编码需单独处理 |
| 修改未上传 | 检查传输根和 Exclude patterns |
| 构建成功但 APK 找不到 | 检查自定义 build directory 与 Output APK pattern |
| include build 模块缺失 | 确认相关工程在同步范围，查看工程信息刷新日志 |
| 改命令后仍用旧 classpath | 重新执行一次完整远端构建并确认工程信息更新成功 |

## 相关页面

- [远端 Gradle](../guide/remote-gradle.md)
- [云开发机配置](../onboarding/agent-setup.md)
- [工程信息刷新与恢复](../concepts/project-info-refresh.md)
- [日志文件](../reference/log-files.md)
