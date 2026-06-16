---
title: 自定义编译器
description: 说明 Jugg 自定义编译器如何通过 jar、SPI 和 CompileOrder 插入增量编译阶段。
status: active
tags:
  - concept
  - compile
  - custom-compiler
---

# 自定义编译器

不同工程可能有自己的编译前后处理，例如识别特定配置文件、构建协议包、生成模板代码或使用定制产物路径。Jugg 不把这些业务逻辑写进内置编译链，而是通过后台配置和自定义编译器 SPI 接入。

自定义编译器运行在 Jugg 增量编译流程内。它可以补充 asset、resource、source、minify、dex 等阶段，但不替代完整 Gradle task graph。

## 装载流程

自定义编译器以 jar 形式提供。jar 可以是本地路径，也可以由后台下发远端地址。Jugg 收到配置后，会校验路径和 md5，再通过 `ServiceLoader` 创建编译器实例。

```text
server config / local config
  -> CustomCompilerManager 解析 jar 路径
  -> 校验本地 jar 或下载远端 jar
  -> 校验 md5
  -> URLClassLoader 加载 jar
  -> ServiceLoader 加载 ICompilerCreator
  -> ICompilerCreator 创建 ICompiler
```

`null` 配置不会清空旧状态。只有收到非 null 列表时，Jugg 才会重新计算有效 jar，并清理不再使用的缓存。

远端 jar 下载是异步的。如果本轮编译开始时 jar 还没有下载完成，本轮可能不会执行对应自定义编译器；下载成功后会清空已创建的编译器缓存，下轮编译再重新加载。

## SPI 接口

自定义 jar 需要提供 `ICompilerCreator` 的服务声明。`ICompilerCreator` 根据当前 `ICompileContext` 和 `Disposable` 创建 `ICompiler`。

```text
META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator
  -> ICompilerCreator
  -> ICompiler
```

`ICompiler.order` 决定它插入哪个阶段。jar 的加载时机不等于执行时机；执行位置由 `CompileOrder` 和具体内置编译器暴露的 before / after 区间共同决定。

## 插入点

| 插入点 | 语义 |
|---|---|
| `atFirst` | 整轮编译较早的扩展点 |
| `beforeAsset` / `afterAsset` | assets 和 native lib 阶段前后 |
| `beforeRes` / `afterRes` | 资源阶段前后 |
| `beforeSource` / `afterSource` | Java / Kotlin / class 输入阶段前后 |
| `beforeMinify` / `afterMinify` | release 混淆处理前后 |
| `beforeDex` / `afterDex` | dex 阶段前后 |
| `atLast` | 整轮编译较晚的扩展点 |

`BaseCompiler` 会在内置阶段前执行 before hook，在内置阶段后执行 after hook。

before hook 可以通过 `consumeFiles()` 过滤后续输入，也可以把工程特有文件转成内置编译链能处理的输入。after hook 拿到的是内置阶段产物转换后的 `CompileFile`，适合继续生成或整理部署产物。

## 典型链路

```text
BaseCompileContext.customCompilers
  -> CustomCompilerManager.getCustomCompilers()
  -> ServiceLoader 创建 ICompiler
  -> BaseCompiler.compile(task)
     -> executeBeforeCustomCompilers()
     -> 内置 doCompile()
     -> executeAfterCustomCompilers()
```

已有接入场景包括：JOOX 协议文件变化时，通过自定义编译器触发指定脚本并构建协议包；模板代码生成也可以放在这一类扩展里。

## 失败与类加载边界

- 自定义编译器抛出的异常会被 `BaseCompiler` 捕获，Jugg 打印 warn，并把当前 task 收口为失败。
- 自定义 jar 的 parent classloader 是 Jugg 当前 classloader，可以复用 Jugg API。
- jar 内如果打包了与 Jugg 冲突的依赖版本，类加载行为可能不可预期。
- `CompileUiHandler.DEFAULT` 是无 UI 默认实现；CLI 或测试场景不会弹出确认窗口。

## 相关页面

- [增量编译总览](./index.md)
- [源码增量编译](./source.md)
- [资源增量编译](./resource.md)
- [自定义编译器能力](../../capabilities/compile/custom-compiler.md)
- [自定义编译器指南](../../guide/custom-compiler.md)
