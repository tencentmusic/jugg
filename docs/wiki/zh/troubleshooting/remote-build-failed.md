---
title: 云端编译失败
description: 处理远端工程同步、Gradle Wrapper、Windows 换行与编码、APK 回传和工程信息刷新问题。
status: active
tags:
  - troubleshooting
  - remote-gradle
---

# 云端编译失败

云端编译包含本地准备、工程同步、远端 Gradle、产物回传和本地工程信息刷新。先根据可见现象执行对应操作，不需要从内部阶段逐项检查。

## Q：远端提示 `/usr/bin/env: sh\r: No such file or directory` 怎么办？

这表示 Linux 读取到 CRLF 格式的 Unix `gradlew`。

1. 确认远端命令使用的是 `gradlew`，不是 `gradlew.bat`。
2. 把实际同步到远端的 `gradlew` 转成 LF。
3. 重新同步并执行远端构建。

Windows 本机使用远端编译时，Jugg 会尝试在同步前转换实际使用的 `gradlew`；仍出现错误时，应检查命令是否指向了另一份 wrapper。

## Q：远端缺少 Gradle Wrapper 文件怎么办？

确认命令目录存在 `gradle/wrapper/gradle-wrapper.properties`。有该文件时，Jugg 可以补齐缺失的 `gradlew`、`gradlew.bat` 和 wrapper jar；没有 wrapper properties 时，应先从工程补充完整 Wrapper，或改用远端已经安装的系统 Gradle。

## Q：本地修改没有上传到远端怎么办？

1. 确认当前工程位于实际传输根目录内。
2. 检查 `Exclude patterns`，它使用 rsync pattern，不是 gitignore。
3. 删除过宽的目录排除规则，或为需要同步的目录重新编写规则。
4. 多工程模式下，确认相关工程都位于同步范围。

`.gradle` 和 `build` 由 Jugg 固定排除，不要依赖这些目录在本地与远端之间同步。

## Q：远端构建成功，但本地找不到 APK 怎么办？

1. 确认 Run Configuration 中的编译命令生成了目标 variant。
2. 检查 Output APK pattern 是否匹配实际文件名和目录。
3. 使用自定义 Gradle build directory 时，确保 APK 回传配置指向该目录。
4. 确认产物目录没有被同步排除规则挡住。

## Q：修改远端编译命令后仍使用旧 classpath 或旧生成源码怎么办？

执行一次完整远端构建，让 Jugg 按新命令重新读取 APK、classpath 和 generated source。构建后仍使用旧结果时，再确认命令和 build target 是否已经保存到当前 Run Configuration。

## Q：远端输出出现中文乱码怎么办？

Jugg 会先按 UTF-8 读取 Windows Gradle 输出，无效时再尝试 GBK。如果仍然乱码，确认输出是否来自 Gradle 之外的脚本或工具，并统一该工具的输出编码后重试。

## Q：include build 模块在远端缺失怎么办？

把被 include 的工程加入同步范围，然后执行一次完整远端构建，刷新依赖和工程信息。只同步主工程但不传输 include build，无法生成正确 classpath。

## 相关页面

- [远端 Gradle](../guide/remote-gradle.md)
- [工程信息刷新与恢复](../concepts/project-info-refresh.md)
- [云开发机配置](../onboarding/agent-setup.md)
