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

Jugg 支持对 Java、Kotlin 和 class 输入做源码侧增量编译，并把 class、dex 或 release 重混淆产物交给后续部署链路。资源、AndroidManifest、`.so` 等文件不属于源码编译页面的范围；编译器隔离、Kotlin/Java 顺序和生成源码交接机制见[源码增量编译原理](../../concepts/incremental-compile/source.md)。

## 已支持能力

| 修改类型 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| Java 源码 | 支持 | 生成 class，并继续生成可部署 dex |
| Kotlin 源码 | 支持 | 生成 class，并让后续 Java 编译可见 |
| Kotlin Android Extensions | 支持旧项目兼容 | 旧项目 synthetic 引用仍可参与增量编译 |
| Kotlin Compose 源码 | 支持 | Compose 相关 class/dex 随本轮增量产出；详见 [Kotlin Compose](./kotlin-compose.md) |
| 原始 class 输入 | 支持 | 进入 dex 或 release 重混淆链路 |
| 生成源码 | 支持作为输入 | DataBinding/ViewBinding、注解器、KSP/KAPT 等生成源码继续编译 |
| dex 输出 | 支持 | class 编译成功后生成部署所需 dex |

> [!TIP]
> 一次源码修改跨越大量文件、多个模块或依赖构建配置时，Jugg 会回退 Gradle 重建可信基线。这不代表本次运行失败。

## 触发与结果

```text
Java / Kotlin / class 输入变化
  -> 生成或更新 class
  -> 生成 dex 或 release 重混淆产物
  -> 交给部署阶段
  -> 必要时追加受影响源码继续编译
```

用户最常见的可见结果是：直接修改的源码先被编译；如果接口、父类、常量、生成源码或 release inline 影响了其它文件，Jugg 会继续追加编译受影响源码。

## 使用边界

- 一次源码修改跨越大量文件、多个模块或依赖构建配置时，Jugg 会回退 Gradle。
- 生成源码需要先由对应能力产出，例如 DataBinding/ViewBinding、注解器、KSP/KAPT 或 Compose。
- release/minified 场景依赖当前 APK 对应的 mapping 基线，异常时优先用 Gradle release 构建对照。

## 关联能力

- [资源编译](./resource-compile.md)
- [重编译/扩散编译](./recompile-propagation.md)
- [Release 编译](./release-compile.md)
- [Gradle 回退](./gradle-fallback.md)
- [源码增量编译原理](../../concepts/incremental-compile/source.md)
