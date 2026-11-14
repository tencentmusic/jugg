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

## Windows 使用 rsync

Windows x64 本地 NTFS 工程可使用 `rsync_simple` 或完整 `rsync` 连接 Linux 或 macOS 构建机。Windows 统一使用本机 Cygwin 的 rsync 和 SSH；SSH 配置字段可填写密码或私钥文件路径。构建机需要安装 rsync，并允许 SSH shell 登录。

macOS 上请选择该账号可写的同步目录，不要直接沿用 Linux 的 `/home` 路径。在远程编译环境变量中配置构建机实际的 `ANDROID_HOME`，确认 JDK 和依赖仓库网络可用。Windows 上传后，Jugg 会在执行前为本次命令指定的工程内相对 `gradlew` 补齐执行权限。

自行安装 Cygwin x64 的 `rsync` 和 `openssh` 包，将该安装的 `bin` 目录加入用户 PATH，并重启 Android Studio。Jugg 只选择 IDE 继承的 PATH 中第一个 `rsync.exe`，使用它同目录的 `ssh.exe` 和 `cygpath.exe`。如果该目录不完整，补全安装或调整 PATH 顺序；Jugg 不会跳过它去寻找另一套工具，也不使用 `JUGG_CYGWIN_HOME` 或固定安装目录。

环境缺失或工具无法启动时，本次同步结束，控制台会说明原因并提供“Windows rsync 环境配置 / Windows rsync setup”入口。点击后可切换中文或英文，预览并复制配置 Prompt 交给自己的 Agent；路径可能包含本地用户名，请先检查预览。复制失败时仍可选择预览文字手工复制。该入口只提供本地工具配置引导，不采集工程现场或上传报告。Jugg 不自动安装工具或修改 PATH；完成配置后重启 Android Studio，再手动重试。

本地需要 Windows 原生符号链接权限。上传保留链接本身，树外链接目标内容不会自动上传；需要这些资源时，应扩大完整 `rsync` 的同步范围或预先复制文件。外部 SSH 不读取个人 SSH 配置；依赖跳板机、HTTP 代理或交互式二次认证的连接不在此 Windows 接入范围内。

## 控制同步排除内容

Remote Compile Options 中的 `Exclude patterns` 会显示当前可配置排除列表。未修改时使用以下默认值：

```text
local.properties; .idea/; *.iml; .git/objects/; .git/modules/; .cxx/
```

该字段使用 rsync pattern，并相对于实际传输根目录解释。用分号分隔多条规则；换行和旧的逗号分隔输入也能读取。修改后，界面中的列表会替换上述默认列表：删除某项会同步对应路径，清空字段表示不应用这些可配置排除规则。

`.gradle` 和 `build` 始终由 Jugg 固定排除，不能通过该字段移除；其中 `.gradle/jugg` 和 `build/jugg` 的必要配置文件仍会同步。

> [!WARNING]
> 删除 `.git/objects/` 或 `.git/modules/` 会放开整个目录，不是只同步其中某个文件。大目录会显著增加上传时间和远端磁盘占用。

旧版本的 `Additional exclude patterns` 是附加规则，升级后不会转换为新的完整列表。升级后如果仍需要这些规则，请在 `Exclude patterns` 中重新填写。

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

使用 iFT 或完整 `rsync` 模式时，可以在 Remote Compile Options 中打开：

```text
Enable multiple projects mode (sync and fetch all projects in [Local to remote sync path])
```

开启后，Jugg 会以 `Local to remote sync path` 为范围同步和拉取其中的多个工程，适合 include build、依赖源码或其它跨工程构建关系。常见目录策略有两种：

| 方式 | 适合场景 |
|---|---|
| 同步 iFT 目录下所有文件 | iFT 目录只包含有限工程 |
| 把需要同步的工程放到同一个子目录 | iFT 目录工程很多，但本次只关心其中一组 |

`rsync_simple` 只同步当前工程根目录，因此界面不会显示多工程开关。需要跨工程同步时，改用 iFT 或完整 `rsync`，并把相关工程放进同一个同步范围；否则 include build、依赖源码或跨工程 classpath 可能缺失。

## 生成代码报红

远端 Gradle 构建成功后，本地 IDE 仍可能缺少远端生成的 `BuildConfig`、R 文件或其它 generated source，表现为代码报红但远端能编译。

这时可以使用 Jugg 提供的生成文件拉取入口，把远端生成代码同步回本地。同步后必要时 reload `build/` 目录，或重新打开工程。

## 与 androidTest 的关系

AndroidTest 首次启用时需要额外构建 test APK。远端 Gradle 场景下，这个 baseline 也会在远端生成，并拉回 app APK / test APK / classpath。后续 `src/androidTest` 修改才能进入 Jugg 增量链路。

Library Test APK 首次缺失时，也会通过远端 Gradle 构建生成对应 Test APK。

## 常见问题

| 现象 | 处理方式 |
|---|---|
| 远端 Gradle 编译失败 | 查看 [远端编译失败](../troubleshooting/remote-build-failed.md)，确认 SDK/JDK/私服权限 |
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
- [远端编译失败](../troubleshooting/remote-build-failed.md)
