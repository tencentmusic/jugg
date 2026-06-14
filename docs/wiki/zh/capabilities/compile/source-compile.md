---
title: 源码编译
description: 说明 Jugg 对 Java、Kotlin 和 class 输入的源码增量编译能力。
status: active
tags:
  - capability
  - compile
  - source
---

# 源码编译

Jugg 支持对 Java、Kotlin 和 class 输入做源码侧增量编译，并把 class、dex 或 release 重混淆产物交给后续部署链路。资源、AndroidManifest、`.so` 等文件不属于源码编译页面的范围。

## 已支持能力

| 修改类型 | 当前支持情况 | 生效方式 |
|---|---|---|
| Java 源码 | 支持 | javac 输出 class，再进入 dex |
| Kotlin 源码 | 支持 | Kotlin 编译输出 class，并让后续 Java 阶段可见 |
| Kotlin Android Extensions | 支持旧项目兼容 | 发现 synthetic import 后补充对应 compiler plugin 参数 |
| Kotlin Compose 源码 | 支持 | 通过项目 Kotlin compiler 和 Compose plugin 编译；详见 [Kotlin Compose](./kotlin-compose.md) |
| 原始 class 输入 | 支持 | 直接进入 dex 或 release minify |
| 生成源码 | 支持作为输入 | DataBinding/ViewBinding、注解器、KSP/KAPT 等生成源码继续进入语言编译 |
| dex 输出 | 支持 | class 编译成功后生成 dex，release 场景再进入 minify |

> [!TIP]
> 如果一次源码修改跨越大量文件、多个模块或依赖构建配置，Jugg 可能直接回退 Gradle。回退是为了重新建立可信基线，不代表本次运行失败。

## 源码编译如何生效

```text
Java / Kotlin / class 输入
  -> JuggApt 和 DataBinding mapper 等生成源码先准备
  -> Kotlin 先编译 Kotlin 与 KSP/KAPT 相关输入
  -> Java 再编译 Java、KAPT Java、DataBinding Java
  -> class 输出进入 dex
  -> release/minified 场景进入重混淆
  -> 成功产物写入 staging
```

这条顺序的关键点是：生成源码必须早于语言编译，Kotlin 必须早于 Java，dex 必须在 class 成功后执行。资源阶段产生的 `R.java`、DataBinding/ViewBinding 源码也会回流到这里继续编译。

## 相关机制

源码编译会引用以下机制，但不在本页重复展开：

- [重编译/扩散编译](./recompile-propagation.md)：源码编译成功后，继续查找调用方、子类、常量引用方等受影响源码。
- [DataBinding/ViewBinding](./databinding-viewbinding.md)：layout 触发的生成源码如何进入源码编译。
- [Kotlin Compose](./kotlin-compose.md)：Compose plugin 如何参与 Kotlin 编译。
- [注解器](./annotation-processors.md)：当前支持的具体注解和生成源码入口。

## 关联能力

- [资源编译](./resource-compile.md)
- [重编译/扩散编译](./recompile-propagation.md)
- [Release 编译](./release-compile.md)
- [Gradle 回退](./gradle-fallback.md)
