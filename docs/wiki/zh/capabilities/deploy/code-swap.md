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

Code Swap 表示 Apply Changes 可以把结构保持兼容的 class 变化应用到正在运行的 App。Jugg 会把方法体等不改变运行时结构的修改标记为可在线替换，但当前普通非空部署通常还会重建 Activity，让代码和资源结果在当前界面重新加载。

## 适合 Code Swap 的修改

| 修改类型 | 当前支持情况 | 部署策略 |
|---|---|---|
| 方法体修改 | 支持 | 作为 modified class 进入 Apply Changes |
| 可 hot reload 的 class 修改 | 支持 | 进入 `HOT_RELOAD` payload，通常随 Full Swap 重建 Activity |
| 空变更或纯 overlay 更新 | 支持跳过 redefiner | 不创建 debugger redefiner，避免误触发 class swap |
| 新增 class | 支持下发，不作为 modified class redefine | 作为 new class 进入 Apply Changes |
| 字段、方法签名、继承或泛型结构变化 | 不作为纯 code swap | 触发 hot fix、full swap、重编译或重装判断 |

> [!TIP]
> 如果修改会改变 class 结构，Jugg 会把它交给更合适的部署策略，而不是强行在线 redefine。

## 这项能力如何生效

```text
源码编译生成 dex / class 变化
  -> 判断 class 结构是否允许在线替换
  -> 作为 modified class 进入 Apply Changes payload
  -> 普通非空部署执行 Apply Changes and Restart Activity
  -> 成功后提交部署历史
```

Code Swap 的关键判断发生在部署数据生成阶段。Jugg 会比较新旧 class 结构，只有适合在线替换的修改才进入 modified class；其它变化会被放入 Hot Fix 或 APK 更新相关策略。当前普通方法体修改不会单独映射为“不重建 Activity”的部署动作，而是随 Full Swap 一起应用。

> [!NOTE]
> `HOT_RELOAD` 是 Jugg 对在线增量部署结果的分类，不表示当前 Activity 一定保持不变。普通非空 Hot Reload 当前会使用 Full Swap 重建 Activity。

## 与其它部署策略的关系

- [Hot Reload](./hot-reload.md) 是用户感知的在线增量部署总能力，Code Swap 描述其中可在线替换的 class 输入。
- [Full Swap](./full-swap.md) 用于需要重启 Activity 的 Apply Changes。
- [Restart](./restart.md) 用于用户显式要求重启 App，或 payload 本身需要 App 重启才生效。

## 相关页面

- [Hot Reload](./hot-reload.md)
- [Full Swap](./full-swap.md)
- [Restart](./restart.md)
- [重编译/扩散编译](../compile/recompile-propagation.md)
- [Apply Changes 中的 class 与 overlay](../../concepts/apply-changes.md)
