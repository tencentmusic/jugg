---
title: 自定义编译器
description: 介绍 Jugg 自定义编译器的接入方式、执行顺序、配置要求和排查入口。
status: active
tags:
  - guide
  - custom-compiler
---

# 自定义编译器

自定义编译器用于把业务专用的生成、转换或校验逻辑插入 Jugg 增量编译链路。它适合团队内部已有特殊编译步骤，但又不希望每次都回退完整 Gradle 的场景。

## 使用前提

自定义编译器适合补充 Jugg 增量编译链路中的工程专用前后处理，例如：

- 编译前消费特定文件，并阻止后续内置编译器重复处理。
- 资源、源码或 dex 阶段前后执行业务生成逻辑。
- 把已有自研转换工具接入 Jugg 增量链路。
- 对编译产物执行校验或补充产物输出。

它有明确边界：

- 替代 Gradle plugin 的完整生命周期。
- 接管安装、启动、热重载或部署状态提交。
- 执行需要长时间阻塞 IDE 的任务。
- 依赖人工 UI 交互才能完成的编译逻辑。

## 接入方式

自定义编译器以 jar 形式提供，并通过 SPI 暴露入口：

```text
自定义 jar
  -> META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator
  -> ICompilerCreator.create(...)
  -> 返回 ICompiler
  -> 根据 CompileOrder 插入 Jugg 编译阶段
```

jar 可以来自：

- 本地绝对路径。
- 相对工程目录的路径。
- HTTP / HTTPS 远端地址。

配置中需要包含 jar 文件名、路径和 md5。Jugg 会校验 md5；本地已有 jar 或远端下载 jar 不匹配时，不会作为有效自定义编译器使用。

## 执行顺序

自定义编译器通过 `CompileOrder` 选择插入点：

| 插入点 | 常见用途 |
|---|---|
| `atFirst` / `atLast` | 整轮编译前后 |
| `beforeAsset` / `afterAsset` | assets 或 native lib 处理前后 |
| `beforeRes` / `afterRes` | 资源、Manifest、R 相关处理前后 |
| `beforeSource` / `afterSource` | Java/Kotlin/DataBinding mapper 处理前后 |
| `beforeMinify` / `afterMinify` | release 混淆阶段前后 |
| `beforeDex` / `afterDex` | DEX 生成前后 |

before hook 可以通过消费输入文件影响后续内置编译；after hook 主要处理内置编译产物。

## 使用建议

- 让自定义编译器只处理明确文件类型或明确模块，避免每轮扫描全工程。
- 失败时给出用户可读错误，避免只抛异常。
- 选择最窄的 `CompileOrder` 区间，减少对其它阶段的影响。
- 不要打包与 Jugg API 冲突的依赖版本。
- 远端 jar 更新后，下一轮编译才一定会重新加载新实例。

## 常见问题

| 现象 | 处理方式 |
|---|---|
| jar 配置后不生效 | 检查路径、md5 和配置是否已下发 |
| 远端 jar 下载成功但本轮没执行 | 再触发一轮编译，确认懒加载缓存已刷新 |
| `ServiceLoader` 找不到实现 | 检查 `META-INF/services/...ICompilerCreator` |
| 执行阶段不对 | 检查 `ICompiler.order` 是否落在目标阶段区间内 |
| 自定义编译器失败导致整轮失败 | 查看 `compile_latest.log` 中的 warn 和异常摘要 |

## 相关页面

- [编译阶段说明](./compile.md)
- [自定义编译器能力](../capabilities/compile/custom-compiler.md)
- [编译流水线](../concepts/compile-pipeline.md)
- [编译问题排查](../troubleshooting/compile.md)
