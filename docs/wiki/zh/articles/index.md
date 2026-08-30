---
title: "Jugg 技术文章"
description: "按发布时间收录 Jugg 的方案设计、实现探索、版本演进和使用数据文章。"
tags:
  - article
---

# Jugg 技术文章

这里收录 Jugg 从早期方案验证到 Agent 自验证能力形成过程中的技术分享。文章按首次发布顺序排列，保留当时的问题背景、实现选择和版本状态。

> [!NOTE]
> 文章中的界面、性能数据、兼容范围和“正在开发”等描述反映首次发布时的状态。最新产品行为以[实现原理](../concepts/)、[能力](../capabilities/)和[参考](../reference/)页面为准。

## 文章列表

| 顺序 | 文章 | 主要内容 |
|---|---|---|
| 1 | [Android增量编译插件Jugg（1） - 平均耗时3.2秒，邀请大家使用](./01-jugg-introduction/) | Jugg 的使用方式、使用数据和整体方案。 |
| 2 | [Android增量编译插件Jugg（2） - 源码增量编译方案](./02-source-incremental-compilation/) | Java、Kotlin、混编、扩散编译和 DEX 处理。 |
| 3 | [Android 极速编译插件Jugg（3） - 资源增量编译，我不做 AAPT2 啦 JoJo！](./03-resource-incremental-compilation/) | Android 资源编译和 aapt2 增量链接。 |
| 4 | [Android 极速编译插件Jugg（4） - 增量部署方案，安卓也能实现热重载？](./04-incremental-deployment/) | 热修复、Instant Run、Apply Changes 与 Jugg 增量部署。 |
| 5 | [Android 极速编译插件Jugg 2.0，更好的稳定性，更多的增量编译](./05-jugg-2-0/) | Jugg 2.0 新能力和重点问题修复。 |
| 6 | [Jugg 节省了安卓开发多少编译时间？](./06-time-savings/) | 编译等待时间的估算方法和统计数据。 |
| 7 | [节省 3.6 万小时编译等待：Jugg 大规模工程秒级构建方案 2.X 能力演进](./07-jugg-2-x-evolution/) | Jugg 2.X 的关键能力演进。 |
| 8 | [从秒级编译到 Agent 自验证：Jugg 3.0 新能力与体验优化](./08-jugg-3-0/) | CLI Skill、Hooks、Android Test、Debug 和 Agent 验证工具。 |

## 阅读方式

- 想理解设计过程，可以按顺序阅读前四篇。
- 想查看版本演进，可以直接阅读 Jugg 2.0、2.x 和 3.0。
- 想确认最新版本是否支持某个场景，请进入[能力概览](../capabilities/)，不要根据旧文章中的版本状态作判断。
