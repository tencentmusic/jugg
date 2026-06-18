---
title: 远端 Gradle
description: 介绍 Jugg 远端 Gradle 编译的适用场景、配置思路、运行结果和常见排查入口。
status: active
tags:
  - guide
  - gradle
  - remote
---

# 远端 Gradle

远端 Gradle 用于把耗时的 Gradle 构建放到云开发机或远端构建机执行，本地 Android Studio 仍负责 IDE 交互、Jugg 状态管理和设备部署。它适合本地机器性能不足、工程很大或团队已有可复用构建机的场景。

## 使用前提

远端 Gradle 适合把完整 Gradle 构建放到云开发机或远端构建机执行。本地仍负责 IDE 交互和设备部署。使用前建议确认：

- 本地 Gradle 构建耗时已经明显影响开发。
- 团队已有云开发机、rsync 或 iFT 等同步环境。
- 远端目录能对应本地工程，并覆盖源码、Gradle 文件、include build 和必要配置。
- 构建机已经具备 Android SDK、JDK、Gradle cache 和工程依赖访问权限。
- 远端构建过程不依赖临时人工输入，例如交互式权限确认或二次认证。

## 配置思路

需要准备：

1. 可 SSH 访问的构建机。
2. 与本地工程对应的远端目录。
3. 文件同步方式，例如 rsync 或 iFT。
4. 构建所需的 JDK、Android SDK、Gradle cache 和私有仓库访问权限。
5. Jugg 中配置服务器 IP、账号、密码或密钥等连接信息。

参考使用手册中的描述，远端编译可以在几分钟内完成基础配置，并且配置可在多个工程间复用。

## 运行时发生什么

```text
本地触发 Jugg Run / Gradle fallback
  -> 同步本地变更到远端
  -> 远端执行 Gradle 构建或 project info 读取
  -> 拉回 APK、classpath、生成源码和日志
  -> 本地更新 Jugg 编译上下文
  -> 本地继续部署到设备
```

远端 Gradle 不改变 Jugg 增量编译的判断规则：当需要完整 Gradle 构建、依赖 diff、AndroidTest baseline 或 project info 更新时，Gradle 执行位置从本地变为远端。

## 同步多个工程

如果你的开发目录中有多个相关工程，常见策略有两种：

| 方式 | 适合场景 |
|---|---|
| 同步 iFT 目录下所有文件 | iFT 目录只包含有限工程 |
| 把需要同步的工程放到同一个子目录 | iFT 目录工程很多，但本次只关心其中一组 |

同步多个工程时，不建议使用过于简化的同步方式；否则 include build、依赖源码或跨工程 classpath 可能缺失。

## 生成代码报红

远端 Gradle 构建成功后，本地 IDE 仍可能缺少远端生成的 `BuildConfig`、R 文件或其它 generated source，表现为代码报红但远端能编译。

这时可以使用 Jugg 提供的生成文件拉取入口，把远端生成代码同步回本地。同步后必要时 reload `build/` 目录，或重新打开工程。

## 与 androidTest 的关系

AndroidTest 首次启用时需要额外构建 test APK。远端 Gradle 场景下，这个 baseline 也会在远端生成，并拉回 app APK / test APK / classpath。后续 `src/androidTest` 修改才能进入 Jugg 增量链路。

Library Test APK 首次缺失时，也会通过远端 Gradle 构建生成对应 Test APK。

## 常见问题

| 现象 | 处理方式 |
|---|---|
| 远端 Gradle 编译失败 | 先看远端构建日志，再确认 SDK/JDK/私服权限 |
| 本地代码报红但远端能编译 | 拉回远端生成代码或重新同步 project info |
| 修改没有同步到远端 | 检查同步目录、忽略规则和当前工程路径 |
| include build 模块缺失 | 确认相关工程是否在同步范围内 |
| `gradlew clean` 后缺少 Jugg runtime | 使用当前版本 Jugg；runtime 已避开 `build/`，放到 `.gradle/jugg` |

## 相关页面

- [编译阶段说明](./compile.md)
- [Gradle 回退](../capabilities/compile/gradle-fallback.md)
- [项目模型](../concepts/project-model.md)
- [Android Test](./android-test.md)
- [日志文件](../reference/log-files.md)
