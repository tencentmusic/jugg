---
title: 源码编译
description: 说明 Jugg 对 Java、Kotlin、已生成源码和 class 输入的增量编译支持范围。
status: active
tags:
  - capability
  - compile
  - source
---

# 源码编译

Jugg 支持增量编译本轮变化的 Java 和 Kotlin 源码，并可继续处理已支持编译能力产生的源码或 class 输入。编译结果会形成可部署的 DEX 或 release 重混淆产物。资源、AndroidManifest、`.so` 等文件不属于本页范围；编译器隔离、Java/Kotlin 混编顺序和 DEX 结构对齐见[源码增量编译原理](../../concepts/incremental-compile/source.md)。

## 支持范围

| 用户场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 修改 Java 源码 | 支持 | 本轮修改进入增量编译和后续部署 |
| 修改 Kotlin 源码 | 支持 | 本轮修改进入增量编译和后续部署 |
| 同轮修改相互引用的 Java 与 Kotlin | 支持 | 两种语言使用本轮最新声明完成编译 |
| Kotlin Android Extensions | 支持旧项目兼容 | 旧项目 synthetic 引用仍可参与增量编译 |
| Kotlin Compose 源码 | 支持 | Compose 相关 class/DEX 随本轮增量产出；详见 [Kotlin Compose](./kotlin-compose.md) |
| 已支持能力产生的源码 | 支持作为输入 | [DataBinding/ViewBinding](./databinding-viewbinding.md) 或[明确支持的注解入口](./annotation-processors.md)等生成源码可继续编译 |
| 已生成或转换的 class 产物 | 支持作为输入 | 继续生成可部署 DEX，release 场景进入重混淆处理 |

## 触发与结果

```text
Java / Kotlin 或已支持的生成产物变化
  -> 编译本轮直接变化和已生成源码
  -> 必要时追加受影响源码继续编译
  -> 生成 DEX 或 release 重混淆产物
  -> 交给部署阶段
```

用户最常见的结果是：直接修改的源码先进入编译；如果接口、父类、常量、生成源码或 release inline 又影响了其它文件，Jugg 会在交给部署前继续追加编译。日志中出现多轮编译不代表首轮失败。

## 使用边界

- 源码增量编译需要最近一次可信 Gradle 构建提供 APK、classpath、编译参数和生成源码基线；首次运行或基线失效时会先执行 Gradle。
- 变化范围超过当前增量限制，或构建配置、依赖与 source set 上下文发生变化时，Jugg 会回退 Gradle。
- 删除或重命名整个 Java/Kotlin 源文件时，Jugg 不会移除设备中已有的 class；旧 class 仍可能通过直接引用、反射或类加载被访问。只有需要让旧 class 真正消失时，才执行完整 Gradle 构建刷新 APK 和引用基线。
- 生成源码需要先由对应的已支持能力产出；不应默认任意 annotation processor、KSP 或 KAPT 都可以脱离 Gradle 完整运行。
- release/minified 场景依赖与当前 APK 匹配的 mapping 基线；基线缺失、失配或运行结果异常时，使用 Gradle release 构建重新建立基线。

## 相关页面

- [资源编译](./resource-compile.md)
- [重编译/扩散编译](./recompile-propagation.md)
- [Release 编译](./release-compile.md)
- [Gradle 回退](./gradle-fallback.md)
- [编译阶段说明](../../guide/compile.md)
- [源码增量编译原理](../../concepts/incremental-compile/source.md)
