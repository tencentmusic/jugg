---
title: 工程信息刷新与恢复
description: 解释 Jugg 如何刷新 Gradle 工程信息、处理缺失快照，并适配 AGP 9、复合构建和自定义 build directory。
status: active
tags:
  - concept
  - project-info
  - gradle
  - agp
---

# 工程信息刷新与恢复

增量编译依赖最近一次完整构建确认的模块、依赖、variant、classpath 和输出路径。当 Gradle 命令、Build Variant、插件版本或工程结构变化后，旧快照即使还能读取，也不再足以证明本轮产物正确。

## 缺失工程信息时不会继续冒险部署

Jugg 发现 Gradle 工程信息缺失或无效时，会先触发重建。增量任务若与重建并发，会等待当前重建结束；重建完成后重新判断是否具备增量条件。

\`\`\`text
发现工程信息缺失
  -> 启动 Gradle 工程信息读取
  -> 增量任务等待重建结束
  -> 快照有效：继续重新判断
  -> 快照仍不可用：转为完整 Gradle 构建
\`\`\`

这条保护避免在没有依赖、classpath 或 APK 归属证据时继续生成和部署局部产物。

## 哪些变化会刷新快照

- Gradle 构建文件、依赖或 version catalog 变化。
- Run Configuration 的编译命令或 BuildTarget 变化。
- Android Studio Sync 或完整 Gradle 构建完成。
- 远端编译命令变化。
- 快照文件缺失、损坏或被判定为过期。

远端编译初始化只等待与本轮远端命令相关的刷新，不会被其它无关后台读取长期阻塞。

## 完整构建是依赖事实来源

完整构建完成后，Gradle 产生的依赖关系、classpath 和产物路径是本轮最权威的数据。即使 IDE 同时补充了模块与 source root 信息，也不会用较新的 IDE 文件时间覆盖同一次完整构建给出的 library dependencies。

复合构建中，某个 included build 本轮读取失败时会尽量保留它上一次有效副本；从未成功读取过的 included build 才会被跳过。IDE 未识别但 Gradle 已确认的模块和依赖也会在不引入依赖环的前提下补回。

## AGP 与 Kotlin 兼容

新版本 AGP 会改变 variant API、Kotlin task 和输出目录。Jugg 的当前处理包括：

- AGP 9 legacy variant API 无结果时，从 Android Components 收集 variant 名称。
- 识别 AGP 9 Built-in Kotlin 的 task 与输出路径。
- 从项目 Android Gradle Plugin 读取可用的 R8/D8 分发路径；Gradle 插桩 JAR 不可直接复用时回到原始 artifact 或 Jugg 内置实现。
- Kotlin 2.x 优先读取 typed compiler options，旧版 Kotlin 才读取 legacy options。
- 自定义或集中式 build directory 从工程模型读取，不硬编码模块 \`build/\`。

这些适配只保证 Jugg 能建立与当前环境匹配的增量输入，不代表 Jugg 会替代完整 Gradle task graph。

## 快照恢复仍有边界

| 场景 | 处理 |
|---|---|
| 工程信息正在重建 | 等待本轮重建完成 |
| 重建失败或完整构建基线不存在 | 转完整 Gradle 构建 |
| included build 暂时读取失败 | 保留上次有效副本 |
| 自定义 build directory | 按实际路径读取和同步产物 |
| 切换 variant / BuildTarget | 建立新完整构建基线 |
| 修改插件或 task graph | 由 Gradle 重新确认 |

## 相关页面

- [工程上下文获取](./project-model.md)
- [运行配置与构建变体](../guide/run-configuration.md)
- [远端 Gradle 问题排查](../troubleshooting/remote-gradle.md)
- [Gradle 回退](../capabilities/compile/gradle-fallback.md)
