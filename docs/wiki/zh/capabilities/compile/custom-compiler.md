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

Jugg 支持通过自定义编译器 SPI 扩展内置增量编译链。自定义编译器可以在 asset、resource、source、minify、dex 等阶段前后插入逻辑，处理项目特有的生成、转换或校验需求。

## 支持的接入方式

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 本地 jar 自定义编译器 | 支持 | 通过 server 配置 jar 路径和 md5 后加载 |
| 远端 jar 自定义编译器 | 支持 | 下载、校验 md5 后懒加载 |
| 阶段前置处理 | 支持 | before hook 可消费或改写后续输入 |
| 阶段后置处理 | 支持 | after hook 可处理内置阶段产物 |
| 编译失败收口 | 支持 | 自定义编译器异常会让当前任务失败并给出 warn |

> [!NOTE]
> 自定义编译器运行在 Jugg 编译流程内，适合补充增量链路，不适合替代完整 Gradle task graph。

## 自定义编译器如何接入

```text
server 下发自定义编译器配置
  -> 校验本地或远端 jar 与 md5
  -> 通过 ServiceLoader 加载 ICompilerCreator
  -> 创建自定义 ICompiler
  -> 按 CompileOrder 插入对应阶段
  -> before / after hook 参与本轮编译
```

before hook 可以过滤或消费输入文件；after hook 拿到的是内置阶段产物转换后的 `CompileFile`。选择插入点时，应按用户目标选择阶段，而不是按 jar 加载时机判断。

## 常见插入点

| 目标 | 推荐区间 |
|---|---|
| 最早处理文件 | `atFirst` |
| 资源前后处理 | `beforeRes` / `afterRes` |
| Java/Kotlin 前后处理 | `beforeSource` / `afterSource` |
| 混淆前后处理 | `beforeMinify` / `afterMinify` |
| dex 前后处理 | `beforeDex` / `afterDex` |

## 关联能力

- [自定义编译器指南](../../guide/custom-compiler.md)
- [源码编译](./source-compile.md)
- [编译阶段说明](../../guide/compile.md)
