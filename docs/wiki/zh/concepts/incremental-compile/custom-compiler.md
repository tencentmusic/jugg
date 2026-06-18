---
title: 自定义编译器
description: 解释工程特有的前后处理为何不应写进内置编译链，Jugg 如何用 jar + ServiceLoader + 阶段插入点接入自定义编译器。
status: active
tags:
  - concept
  - compile
  - custom-compiler
---

# 自定义编译器

不同工程常有自己的编译前后处理：识别特定配置文件、构建协议包、生成模板代码或使用定制产物路径。这类逻辑因工程而异，把它们写进 Jugg 的内置编译链并不合适。

## 工程特有逻辑不该污染内置链

内置编译链要对所有用户稳定通用。把某个工程特有的前后处理（比如某种协议包生成）硬编码进去，会让内置链膨胀，也会把只对一部分工程有效的行为强加给所有用户。但这些处理又确实需要嵌入增量流程的特定阶段，不能简单地放在编译之外。

## 用 jar + ServiceLoader + 阶段插入点接入

Jugg 通过外置 jar 接入自定义编译器，用 JVM 标准的 `ServiceLoader` 发现实现，再按声明的阶段插入点嵌入增量流程。它能补充 asset、resource、source、minify、dex 等阶段的前后处理，但不替代完整的 Gradle task graph。

装载流程如下：

```text
本地或远端 jar 配置
  -> 解析 jar 路径
  -> 校验本地 jar，或下载远端 jar
  -> 校验文件指纹（md5）
  -> 用独立 ClassLoader 加载 jar
  -> 通过 ServiceLoader 发现并创建自定义编译器实例
```

加载 jar 的时机不等于执行时机。每个自定义编译器声明一个插入点，决定它嵌在哪个内置阶段的前后：

| 插入点 | 语义 |
|---|---|
| `atFirst` | 整轮编译较早的扩展点 |
| `beforeAsset` / `afterAsset` | assets 和 native lib 阶段前后 |
| `beforeRes` / `afterRes` | 资源阶段前后 |
| `beforeSource` / `afterSource` | Java / Kotlin / class 输入阶段前后 |
| `beforeMinify` / `afterMinify` | release 混淆处理前后 |
| `beforeDex` / `afterDex` | DEX 阶段前后 |
| `atLast` | 整轮编译较晚的扩展点 |

before 插入点可以过滤后续输入，或把工程特有文件转成内置链能处理的输入；after 插入点拿到的是内置阶段产物，适合继续生成或整理部署产物。已有接入场景包括：协议文件变化时触发指定脚本并构建协议包，以及模板代码生成。

## 自定义编译器的加载与执行边界

外置接入带来灵活性，也带来加载时机、类隔离和异常处理上的约束：

- **远端 jar 异步下载，本轮按已下载状态执行**：远端 jar 在后台下载；本轮编译开始时 jar 尚未下载完成，对应自定义编译器不会执行。下载完成后，下一轮会重新加载。
- **类加载冲突不可预期**：自定义 jar 复用 Jugg 当前 ClassLoader 作为 parent，可以调用 Jugg 公开 API；但如果 jar 内打包了与 Jugg 冲突的依赖版本，类加载行为会变得不可预期。
- **异常收口为失败**：自定义编译器抛出的异常会被捕获、打印 warn，并把当前任务收口为失败，不会穿透到 IDE 进程。
- **空配置不清状态**：收到空（`null`）配置时不会清空已有状态，只有收到非空列表才会重算有效 jar 并清理废弃缓存。

## 相关页面

- [增量编译总览](./index.md)
- [源码增量编译](./source.md)
- [资源增量编译](./resource.md)
- [自定义编译器能力](../../capabilities/compile/custom-compiler.md)
- [自定义编译器指南](../../guide/custom-compiler.md)
