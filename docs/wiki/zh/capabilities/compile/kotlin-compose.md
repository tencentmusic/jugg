---
title: Kotlin Compose
description: 说明 Jugg 对 Kotlin Compose 源码增量编译的支持方式和前置条件。
status: active
tags:
  - capability
  - compile
  - kotlin
  - compose
---

# Kotlin Compose

Jugg 支持常见 Kotlin Compose 源码的增量编译。它会在 Kotlin 源码中识别 Compose import，并尽量使用项目自身的 Kotlin compiler classpath 和 Compose compiler plugin 完成本轮编译。

## Compose 编译支持范围

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 修改包含 `androidx.compose.*` import 的 Kotlin 文件 | 支持 | 启用 Compose 编译参数并运行 Kotlin 编译 |
| Android Compose compiler plugin | 支持 | 从 Kotlin extension / plugin classpath 中查找 `androidx.compose` 插件 |
| KMM / JetBrains Compose plugin | 支持 | 从 Kotlin plugin classpath 中查找 `org.jetbrains.compose` 插件 |
| Kotlin 2.x Compose compiler plugin | 支持识别 | 查找 `kotlin-compose-compiler` 插件 |
| 找不到 Compose plugin | 降级继续 | 打印 warning，编译结果可能不完整 |

> [!IMPORTANT]
> Compose 增量编译依赖项目 Kotlin compiler 和 Compose compiler plugin。若刚升级 Kotlin、Compose、AGP 或插件配置，先执行 Sync 和一次 Gradle 构建建立基线。

## Compose 编译如何生效

```text
Kotlin 源码变化
  -> 扫描 import，发现 androidx.compose.*
  -> 选择项目 Kotlin compiler classpath
  -> 从 kotlinExtensions / kotlinPlugins 中查找 Compose plugin
  -> 增加 Compose plugin 参数
  -> Kotlin 编译输出 class
  -> Java / dex / minify 继续执行
```

Jugg 会优先使用项目 Kotlin 编译器。当不能使用项目编译器时，Compose plugin 可能无法正确启用，此时日志会提示启用项目 Kotlin compiler 或先 fallback。

## 使用边界

- 仅修改 Compose UI Kotlin 源码通常适合增量。
- 修改 Compose compiler plugin、Kotlin 版本、Gradle plugin 或 compiler args 时，建议 Gradle。
- 如果 Compose 编译后运行时异常只在 Jugg 增量出现，先用 Gradle 构建验证插件基线是否一致。

## 相关页面

- [源码编译](./source-compile.md)
- [注解器](./annotation-processors.md)
- [Gradle 回退](./gradle-fallback.md)
