---
title: KMP 与 Compose Multiplatform
description: 说明 Jugg 对 KMP expect/actual 源码和 Compose Multiplatform 资源增量编译的支持范围。
status: active
tags:
  - capability
  - compile
  - kmp
  - compose
---

# KMP 与 Compose Multiplatform

Kotlin Multiplatform 的 Android 目标不仅要编译当前修改文件，还要保持 common 与 platform 声明、source set 层级和资源 accessor 一致。Jugg 会复用 Gradle 暴露的 Kotlin 与 Compose 元数据，在不执行完整 task graph 的情况下补齐本轮 Android 增量输入。

## expect/actual 不能只按文件路径配对

\`expect\` 与 \`actual\` 的关系来自 Kotlin 编译模型，不一定是 \`commonMain -> androidMain\` 的直接两层结构。工程还可以存在 \`sharedMain\` 等中间 source set。

Jugg 从当前 Android Kotlin 编译任务读取 common roots、fragment 身份和 refinement 关系，并结合项目 Kotlin incremental cache 查找本轮需要一起编译的 complementary files。

\`\`\`text
修改 common 或 Android Kotlin 文件
  -> 确认当前 Android compilation
  -> 查找 expect/actual 互补文件
  -> 保留 fragment 与 refines 关系
  -> 使用项目 Kotlin compiler 编译
  -> class / dex 进入普通部署链路
\`\`\`

这条链路兼容 Kotlin 1.9 与 K2 时代不同的缓存和 fragment 形态。Kotlin 1.9 下还会隔离旧 baseline 输出，避免未被本轮重新生成的旧 \`actual\` class 干扰结果。

## Compose Multiplatform 资源

Jugg 支持 Compose Multiplatform 资源的新增和修改，并调用项目 Compose plugin 提供的官方 generator 生成 typed accessor。

| 资源类型 | 支持情况 |
|---|---|
| string | 支持 |
| string-array | 现代资源管线支持 |
| plurals | 现代资源管线支持 |
| drawable | 支持 |
| font | 支持 |
| files | 作为资源文件部署，不生成 typed accessor |

生成 accessor 时会读取工程快照中所有已知资源目录，避免只看 changed file 导致已有 key 丢失；实际部署只包含本轮新增或修改的资源。

## 现代与 legacy 资源生效路径

现代 Compose 资源按 Gradle 元数据写入 assets 路径。legacy 资源仍通过 classloader 从 APK 根目录读取，因此 Jugg 会保持原始 classpath 相对路径，并让运行时优先命中 overlay 中的新文件。

两种路径都会在存在真实部署数据时重启 App 进程。Compose runtime 和 AssetManager 可能已经缓存资源，单纯重启 Activity 不足以保证读取新值。

## IDE 高亮与自动导入

Jugg 生成的 Compose accessor 会同步回模块的 generated source 目录，使 Android Studio 能索引新增资源并恢复高亮、补全和自动 import。

该同步属于 IDE 辅助能力。同步失败时，已经生成的增量编译产物仍然有效，但编辑器可能暂时无法识别新 accessor；可执行一次 Gradle 构建恢复 IDE 侧生成目录。

## 回退边界

- 删除 KMP 源码或 Compose 资源需要完整 Gradle 构建。
- 修改 source set、target、Kotlin/Compose plugin 或 compiler args 后应重新 Sync 并建立基线。
- complementary cache 或 fragment 信息缺失时，Jugg 采用 best-effort 输入并让 Kotlin compiler 给出最终结果，不猜测文件名或目录名配对。
- Compose task 或 generator API 结构不受支持时会明确报告 unsupported，不会把资源静默当成普通 Android \`res/\`。
- 非 KMP 工程中名为 \`commonMain\` 的普通目录不会自动切换到 multiplatform 编译。

## 相关页面

- [Kotlin Compose](./kotlin-compose.md)
- [资源编译](./resource-compile.md)
- [源码编译](./source-compile.md)
- [资源增量编译原理](../../concepts/incremental-compile/resource.md)
- [工程信息刷新与恢复](../../concepts/project-info-refresh.md)
