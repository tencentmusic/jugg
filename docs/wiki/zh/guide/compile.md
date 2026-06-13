---
title: 编译
description: 介绍如何使用 Jugg 编译、理解增量与 Gradle 回退，并处理常见编译结果。
status: active
tags:
  - guide
  - compile
---

# 编译

Jugg 编译通常发生在你点击 Jugg Run、Jugg Debug 或相关工具触发构建时。大多数情况下，你不需要手动选择编译阶段：Jugg 会先尝试增量编译，必要时自动提示或回退到 Gradle。

## 开始一次编译

常见入口包括：

- 点击 Jugg Run。
- 点击 Jugg Debug。
- 运行 androidTest 相关配置。
- 通过 Jugg CLI 或 MCP 工具触发编译。

开始前建议确认：

1. 文件已经保存。
2. Android Studio 没有正在执行耗时同步或索引任务。
3. 目标设备已连接并处于可部署状态。
4. 最近至少有一次成功的 Gradle 构建作为基线。

> [!TIP]
> 如果不确定当前改动是否适合增量，直接运行即可。Jugg 会先判断，不适合时再回退 Gradle。

## Jugg 会优先尝试什么

默认情况下，Jugg 会按下面的顺序处理：

```text
检查设备和文件变化
  -> 判断是否适合增量编译
  -> 编译变化文件
  -> 检查受影响源码并补编译
  -> 进入部署
```

如果前置检查发现风险较高，Jugg 会跳过增量，进入 Gradle 构建。

## 常见修改类型

| 修改类型 | 通常行为 | 说明 |
|---|---|---|
| Java / Kotlin 小范围修改 | 增量编译 | 可能继续补编译受影响源码 |
| layout / drawable / values 修改 | 资源增量编译 | 可能生成 `R.java` 或 ViewBinding/DataBinding 源码 |
| `AndroidManifest.xml` 简单修改 | Manifest 增量处理 | 复杂合并逻辑可能回退 |
| assets / native lib 修改 | overlay 下发 | 通常不需要完整 Gradle |
| Gradle 脚本或依赖修改 | 可能回退 Gradle | 取决于依赖变化判断和用户选择 |
| 大批量跨模块修改 | 可能回退 Gradle | Jugg 会优先保证稳定性 |
| release 混淆相关修改 | 谨慎增量 | 运行时异常时建议 Gradle 验证 |

## 什么时候会回退 Gradle

常见回退原因包括：

- 你选择了强制 Gradle 构建。
- 没有检测到可增量编译的文件变化。
- 修改文件过多或跨模块过多。
- 设备、安装状态或部署历史不满足增量条件。
- build target 发生切换，例如 App 与 androidTest 之间切换。
- 修改了构建脚本、依赖或构建插件配置。
- 增量编译失败后需要重新建立可信基线。

回退并不代表 Jugg 失效。它通常表示当前修改更适合交给 Gradle 处理。

## 如何看编译结果

你可以从运行输出或日志中判断当前状态：

| 日志 / 输出 | 含义 |
|---|---|
| `Compile files:` | Jugg 正在编译检测到的变化文件 |
| `Detect effected sources` | 编译成功后发现受影响源码，继续补编译 |
| `Compile finished` | 本轮编译结束 |
| `fallback` / `Fallback` | 当前进入或准备进入 Gradle 回退 |
| `Found incremental compile error` | 增量编译失败，需要查看具体错误 |
| `No file changes` | 当前没有发现可处理的文件变化 |

日志位置：

```bash
build/jugg/log/compile_latest.log
```

## 编译失败时怎么做

建议按顺序处理：

1. 先看运行输出中的第一条明确错误。
2. 打开 `build/jugg/log/compile_latest.log`，搜索 `Found incremental compile error`、`aapt2`、`unresolved reference` 等关键词。
3. 如果是源码或资源本身错误，先修复代码。
4. 如果是构建脚本、依赖、资源表或 release 混淆相关问题，执行一次 Gradle 构建重新建立基线。
5. 如果要提交问题，先备份 `build/jugg/log/` 和 `build/jugg/database/`。

> [!WARNING]
> 不建议在没有备份现场的情况下直接删除整个 `build/`。这会让后续排查失去日志和数据库证据。

## 建议的使用习惯

- 小范围业务代码修改：直接 Jugg Run。
- 修改资源或 layout：直接 Jugg Run，必要时观察是否触发源码补编译。
- 修改 Gradle、依赖、插件或 source set：优先准备接受 Gradle 回退。
- 切分支或拉取大量代码后：建议先执行一次 Gradle 构建。
- release 问题：先用 Gradle 构建确认是否为增量链路差异。

## 相关页面

- [增量编译](../concepts/incremental-compile.md)
- [资源编译](../capabilities/compile/resource-compile.md)
- [编译问题排查](../troubleshooting/compile.md)
- [限制](../reference/limits.md)
