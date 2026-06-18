---
title: Code Swap
description: 说明 Jugg 对运行中代码的在线替换能力和适用边界。
status: active
tags:
  - capability
  - deploy
  - code-swap
---

# Code Swap

Code Swap 用于把可在线替换的 class 变化应用到正在运行的 App。它优先处理方法体等不改变运行时结构的修改，目标是在不重启 App 的情况下让新代码生效。

## 适合 Code Swap 的修改

| 修改类型 | 当前支持情况 | 部署策略 |
|---|---|---|
| 方法体修改 | 支持 | 通过 Apply Changes 的 class redefine 能力在线替换 |
| 可 hot reload 的 class 修改 | 支持 | 进入 `HOT_RELOAD` payload，优先不重启 App |
| 空变更或纯 overlay 更新 | 支持跳过 redefiner | 不创建 debugger redefiner，避免误触发 class swap |
| 新增 class | 支持下发，不作为纯 code swap | 进入 overlay 或 hot fix 路径 |
| 字段、方法签名、继承或泛型结构变化 | 不作为纯 code swap | 触发 hot fix、full swap、重编译或重装判断 |

> [!TIP]
> 如果修改会改变 class 结构，Jugg 会把它交给更合适的部署策略，而不是强行在线 redefine。

## 这项能力如何生效

```text
源码编译生成 dex / class 变化
  -> DeployDataGenerator 判断是否可 hot reload
  -> JuggDeployData 进入 HOT_RELOAD
  -> JuggDeployTask 执行 APPLY_CHANGES
  -> JuggDeployer.codeSwap()
  -> 成功后提交部署历史
```

Code Swap 的关键判断发生在部署数据生成阶段。Jugg 会比较新旧 class 结构，只有适合在线替换的修改才进入轻量路径；其它变化会被放入 hot fix、full swap 或 reinstall 相关策略。

## 与其它部署策略的关系

- [Hot Reload](./hot-reload.md) 是用户感知的在线增量部署总能力，Code Swap 是其中处理 class redefine 的一类实现。
- [Full Swap](./full-swap.md) 用于需要重启 Activity 的 Apply Changes。
- [Restart](./restart.md) 用于用户显式要求重启 App，或 payload 本身需要 App 重启才生效。

## 关联能力

- [Hot Reload](./hot-reload.md)
- [Full Swap](./full-swap.md)
- [Restart](./restart.md)
- [重编译/扩散编译](../compile/recompile-propagation.md)
