---
title: 自定义编译器
description: 说明 Jugg 自定义编译器 SPI、阶段插入点和用户可获得的扩展能力。
status: active
tags:
  - capability
  - compile
  - custom
---

# 自定义编译器

Jugg 支持通过自定义编译器 SPI 扩展内置增量编译链。自定义编译器可以在 asset、resource、source、minify、dex 等阶段前后插入逻辑，处理项目特有的生成、转换或校验需求。本页说明接入能力和常见插入点，加载与阶段边界见[自定义编译器原理](../../concepts/incremental-compile/custom-compiler.md)。

## 支持的接入方式

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 本地 jar 自定义编译器 | 支持 | 本地扩展逻辑可进入 Jugg 编译链 |
| 远端 jar 自定义编译器 | 支持 | 远端扩展下载校验后参与编译 |
| 阶段前置处理 | 支持 | 可在内置阶段前消费或改写输入 |
| 阶段后置处理 | 支持 | 可在内置阶段后处理产物 |
| 扩展执行失败 | 支持 | 扩展编译逻辑抛出异常时，当前任务失败并给出可见提示 |

## 接入方式

自定义编译器以 jar 提供。扩展需要实现 `ICompilerCreator`，由它为当前编译上下文创建 `ICompiler`；Jugg 通过 SPI 加载 jar 中的实现。

项目后台只需在 `customCompilers` 配置中声明 jar 文件名、路径和 md5。路径可以是本地绝对路径、相对项目目录的路径或 HTTP / HTTPS 地址。

> [!NOTE]
> 自定义编译器运行在 Jugg 编译流程内，适合补充增量链路，不适合替代完整 Gradle task graph。

## 触发与结果

```text
自定义编译器配置可用
  -> 加载并校验扩展 jar
  -> 按配置插入编译阶段
  -> 参与本轮增量编译
  -> 成功产物继续交给后续阶段
```

选择插入点时，应按用户目标选择阶段，而不是按 jar 加载时机判断。

## 常见插入点

| 目标 | 推荐区间 |
|---|---|
| 最早或最后处理本轮输入和产物 | `atFirst` / `atLast` |
| assets 和 native lib 前后处理 | `beforeAsset` / `afterAsset` |
| 资源前后处理 | `beforeRes` / `afterRes` |
| Java/Kotlin 前后处理 | `beforeSource` / `afterSource` |
| 混淆前后处理 | `beforeMinify` / `afterMinify` |
| dex 前后处理 | `beforeDex` / `afterDex` |

## 相关页面

- [自定义编译器指南](../../guide/custom-compiler.md)
- [Jugg 后台项目配置下发](../../guide/jugg-backend/project-config.md)
- [源码编译](./source-compile.md)
- [自定义编译器原理](../../concepts/incremental-compile/custom-compiler.md)
