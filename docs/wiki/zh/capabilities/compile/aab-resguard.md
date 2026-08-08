---
title: AabResGuard
description: 说明 Jugg 如何在资源增量编译中使用 AabResGuard resources-mapping.txt。
status: active
tags:
  - capability
  - compile
  - resource
  - aabresguard
---

# AabResGuard

Jugg 支持读取 AabResGuard 生成的 `resources-mapping.txt`，并把资源名称映射写入 aapt2 `inclink` 使用的 res guard mapping 文件，让资源增量产物尽量保持与已构建 APK/AAB 的资源混淆命名一致。

## AabResGuard mapping 处理范围

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 存在 `resources-mapping.txt` | 支持 | 解析 `res id mapping:` 段落并生成 aapt2 mapping 输入 |
| 普通资源增量 link | 支持配合 | aapt2 load/link 时传入 `--res-guard-mapping` |
| mapping 缺失 | 自动跳过 | 不阻断资源编译 |
| mapping 解析失败 | 降级跳过 | 打印 warning，不直接中断本轮 |

> [!TIP]
> 如果刚启用或修改 AabResGuard 配置，先执行一次对应 Gradle 构建，让 `resources-mapping.txt` 成为 Jugg 可读取的基线。

## AabResGuard 如何生效

```text
Gradle 构建产出 resources-mapping.txt
  -> Jugg 资源阶段读取 mapping
  -> 转成 aapt2 inclink 使用的 res guard mapping 文件
  -> aapt2 load/link 时带上 --res-guard-mapping
  -> 增量资源产物保持混淆名称一致
```

Jugg 当前使用的默认路径是模块 build 目录下的 `outputs/bundle/<variant>/resources-mapping.txt`。

## 相关页面

- [资源编译](./resource-compile.md)
- [Release 编译](./release-compile.md)
- [资源增量编译原理](../../concepts/incremental-compile/resource.md)
- [限制](../../reference/limits.md)
