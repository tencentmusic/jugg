---
title: 编译能力
description: 汇总 Jugg 编译相关能力，用于判断哪些修改可增量处理、哪些场景会回退 Gradle。
status: active
tags:
  - capability
  - compile
---

# 编译能力

Jugg 编译能力建立在最近一次可用的 Gradle 构建结果之上。它会优先处理本轮变化文件，生成可部署产物；当本轮修改更适合由 Gradle 接管时，会提示或回退到 Gradle。

## 能力总览

### 核心编译链

| 能力 | 当前支持情况 | 典型结果 |
|---|---|---|
| [源码编译](./source-compile.md) | 支持 Java、Kotlin、class 输入 | 生成 class、dex 或 release 重混淆产物 |
| [重编译/扩散编译](./recompile-propagation.md) | 支持受影响源码继续编译 | 找到调用方、子类、常量引用方等并追加下一轮 |
| [资源编译](./resource-compile.md) | 支持 `res/`、`assets/`、`resources.arsc`、`R.java` 相关链路 | 生成资源 overlay 或触发源码编译 |
| [AndroidManifest 编译](./manifest.md) | 支持基于 merged manifest 的增量 patch | 写入 APK 后重签名生效 |
| [so 更新](./so-update.md) | 支持更新已产出的 `.so` | 写入目标 APK 后重签名生效 |

### 生成源码与语言扩展

| 能力 | 当前支持情况 | 典型结果 |
|---|---|---|
| [DataBinding/ViewBinding](./databinding-viewbinding.md) | 支持 layout 变化后的两阶段处理 | 资源阶段生成 base/split 产物，源码阶段生成 mapper/BR |
| [Kotlin Compose](./kotlin-compose.md) | 支持常见 Compose Kotlin 源码增量编译 | 加载项目 Compose 编译插件后生成 class/dex |
| [注解器](./annotation-processors.md) | 支持明确列出的注解入口 | 生成源码继续进入源码编译 |
| [自定义编译器](./custom-compiler.md) | 支持通过 SPI 插入编译阶段 | 扩展 Jugg 内置编译链 |

### 依赖、发布与回退

| 能力 | 当前支持情况 | 典型结果 |
|---|---|---|
| [依赖库增量编译](./dependency-incremental.md) | 支持对部分依赖变化做 diff 后增量处理 | 新旧 library 产物进入本轮编译/部署判断 |
| [Release 编译](./release-compile.md) | 支持 mapping 一致性、inline 与删除成员补偿 | 产物重混淆后进入部署 |
| [常量引用分析](./const-ref.md) | 支持 Java/Kotlin 内联常量影响分析 | 找到引用方源码并触发扩散编译 |
| [AabResGuard](./aab-resguard.md) | 支持读取 `resources-mapping.txt` 辅助资源增量 link | 保持资源混淆名称一致 |
| [Gradle 回退](./gradle-fallback.md) | 支持自动或用户触发回退 | 重新建立可信构建基线 |

> [!IMPORTANT]
> Jugg 不替代完整 Gradle pipeline。修改 Gradle 脚本、依赖、变体、source set、复杂插件配置或大批量跨模块代码时，仍可能需要 Gradle 构建。

## 编译链路如何串起来

```text
发现文件变化
  -> 判断是否适合增量
  -> assets / native lib / AndroidManifest
  -> res / R.java / DataBinding/ViewBinding 资源阶段
  -> 注解器 / KSP / KAPT / Compose 等源码扩展
  -> Kotlin / Java / class
  -> dex / release minify
  -> 重编译/扩散编译发现受影响源码
  -> 产物交给部署
```

用户通常不需要手动选择阶段。Jugg 会根据变化文件类型、模块归属、APK 归属和当前部署状态决定本轮执行哪些阶段。

## 相关页面

- [编译阶段说明](../../guide/compile.md)
- [增量编译概念](../../concepts/incremental-compile/)
- [编译问题排查](../../troubleshooting/compile.md)
- [限制](../../reference/limits.md)
