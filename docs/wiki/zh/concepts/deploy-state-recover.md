---
title: 部署状态与恢复
description: 从“增量部署依赖上一轮成功状态”出发，解释 Jugg 如何用三处一致性 checkpoint 判断设备是否可信，以及状态不一致时如何恢复基线。
status: active
tags:
  - concept
  - deploy
  - recover
---

# 部署状态与恢复

增量部署不是凭空把 overlay 写到设备上，而是建立在“设备仍停在上一轮成功状态”这个前提之上。一旦这个前提不成立，本轮直接写新 overlay 就会让设备进入既不是旧状态、也不是新状态的中间态。

状态恢复处理的是增量部署最容易被忽略的前置条件：设备、本地历史和 Apply Changes cache 必须对同一轮成功结果达成一致。Jugg 先用多处 checkpoint 判断设备是否可信，不可信时再恢复或重装基线，而不是继续叠加新的差异。

## 增量部署依赖上一轮成功状态

增量部署只下发“相对上一轮的差异”。这意味着设备当前的内容必须正好等于上一轮部署成功后的内容，差异叠上去才是正确结果。如果设备被其他途径改动过，比如重装、系统清理，或换了一个工程部署，差异的基准就错了，叠加结果不可预测。

因此每轮增量部署前，Jugg 都要先确认设备状态可信。判断依据是三处独立记录的一致性 checkpoint。

## 三处一致性 checkpoint

| checkpoint | 记录的内容 | 用途 |
|---|---|---|
| Jugg 自维护的部署历史 | 上一轮下发的部署数据和 overlay id | 本地视角的“上轮成功状态”。 |
| Apply Changes 的 deployment cache | Apply Changes 通道记录的部署快照 | 在线替换链路的一致性凭据。 |
| 设备端 overlay id | 设备上当前实际生效的 overlay id 与文件 | 设备视角的真实状态。 |

这三处分别来自本地、Apply Changes 通道和设备，是三个独立的事实来源。只有三者相互对得上，才能认为设备仍停在预期状态。任意一处对不上，例如本地历史为空但设备上却有 overlay，或设备 overlay id 与预期不符，本轮就把状态视为不可信，转入恢复流程而不是继续叠加差异。

## 状态不可信时如何恢复

恢复的目标是把设备和本地基线重新对齐。Jugg 不会一上来就重装，而是先做一次试探性校验：用一份不产生真实业务变更的部署，验证设备是否还能在预期基线上正常接受 overlay。

```text
状态可能不可信
  -> 先做试探性校验
  -> 校验通过：证明设备仍在预期基线，继续本轮增量
  -> 校验失败 / App 未安装 / App 已被外部更新：重装 APK
  -> 重装后清空本地已部署数据，重建基线
```

试探性校验通过，说明设备状态其实可信，本轮可以继续增量，省下一次重装；校验失败或根本没法校验（App 未安装、已被外部更新），则只能重装 APK，并把本地记录的已部署数据、资源产物和暂存状态清空，重新建立基线。

> [!NOTE]
> 用户在日志里看到 `Deploy state not match, start reinstalling app...` 这类提示，或出现 `OVERLAY_ID_MISMATCH` 字样时，对应的就是某处 checkpoint 对不上、Jugg 选择重装恢复基线。这通常不是错误，而是 Jugg 主动放弃一次不可信的增量。下一步无需特别处理，等重装完成后继续即可。

## 状态提交顺序：要么一起前进，要么都不动

恢复机制要可靠，前提是“上一轮成功状态”本身被正确记录。这里有一条硬约束：部署历史只能在整轮部署成功之后提交，且三处状态必须一起前进。

```text
编译成功
  -> 变化产物先进入暂存，不动历史
  -> 用暂存产物加历史生成本轮下发数据
  -> 部署成功
  -> 同时推进：部署历史、文件状态、设备 overlay id
```

顺序不能颠倒，也不能只提交其中一部分。如果部署还没成功就更新了历史，或者只更新了历史却没更新 overlay id，下一轮校验就会看到自相矛盾的 checkpoint，从而误判状态不可信、触发不必要的重装或错误回退。半提交的状态比没有状态更危险。

## Direct Overlay 的恢复分支

[Direct Overlay](../capabilities/deploy/direct-overlay.md) 是一条在设备非就绪时直接写入 overlay 的旁路。当它启用且调用方允许时，恢复阶段可以更轻：直接比对 deployment cache 和设备 overlay id，状态匹配就不必启动 App 做完整的试探性校验。

但这条旁路有一个明确的退路约束：一旦 Direct Overlay 写入本身失败过，后续恢复会主动关闭这条旁路，改走需要启动 App 的常规试探性校验，重新下发时也不再使用 Direct Overlay。这样做是因为写入失败可能已经让设备 overlay 目录处于半提交状态，此时再走旁路校验会基于不可信的现场做判断。

## 恢复的代价与约束

恢复机制能保证状态可信，但本身也有代价和必须守住的约束。最直接的代价是重装：试探性校验失败时只能重装并重建基线，这一轮失去增量速度收益，换取后续状态可信。为了不把不可信现场带进下一轮，半提交状态必须被清理；切片部署中如果前面的片段已成功、后面的片段失败，必须先清掉设备端已写入的 overlay 再返回失败，否则下一轮会基于半提交现场误判。出于同样的原因，Direct Overlay 一旦写入失败，本轮及后续恢复都会退回常规路径，不再走这条旁路。

还有一条贯穿始终的约束：裁剪后的数据不能更新全局状态。下发给单个 APK 的部署数据是按目标裁剪出来的临时数据，只能用于该次传输；全局部署历史只能用整轮原始数据在成功后提交，否则全局基线会被局部数据污染。

## 相关页面

- [清理数据](../guide/clean-data.md)
- [Clean Reinstall 能力](../capabilities/deploy/clean-reinstall.md)
- [部署历史与缓存](../capabilities/deploy/deploy-history-cache.md)
- [部署策略](./deploy-strategy.md)
- [部署数据与影响分析](./deploy-data-and-impact.md)
- [Apply Changes 中的 class 与 overlay](./apply-changes.md)
- [Direct Overlay 部署机制](./direct-overlay.md)
- [Direct Overlay 能力](../capabilities/deploy/direct-overlay.md)
- [Recover 与 Retry 能力](../capabilities/deploy/recover-and-retry.md)
